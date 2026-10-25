package at.mtr.logmygut.ui.meal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import at.mtr.logmygut.data.FoodHit
import at.mtr.logmygut.data.FoodRepository
import at.mtr.logmygut.data.JournalRepository
import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.data.db.MealEntity
import at.mtr.logmygut.data.db.MealItemEntity
import at.mtr.logmygut.domain.Amount
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.IngredientTag
import at.mtr.logmygut.domain.PortionSize
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.domain.effectiveTags
import at.mtr.logmygut.ui.common.EntryEditViewModel
import at.mtr.logmygut.ui.common.newEntryTime
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One item in the meal being edited. For dishes, [ingredients] is what this meal actually contained. */
data class MealItemUi(
    val key: Int,
    val food: FoodEntity,
    val amount: Amount,
    val ingredients: List<FoodEntity>,
    val effectiveTags: Set<Tag>,
)

/** What the "neu anlegen" dialog produces. */
data class NewFood(
    val name: String,
    val kind: FoodKind,
    val unit: FoodUnit,
    val unitLabel: String?,
    val tags: Set<Tag>,
    val ingredientIds: List<Long>,
)

class MealEditViewModel(
    private val foods: FoodRepository,
    private val journal: JournalRepository,
    private val mealId: Long?,
    day: LocalDate? = null,
) : EntryEditViewModel(newEntryTime(day)) {
    private var existing: MealEntity? = null
    private var nextKey = 0
    private val tagsById = mutableMapOf<Long, List<IngredientTag>>()
    private var searchJob: Job? = null

    var eatenOut by mutableStateOf(false)
    var note by mutableStateOf("")
    val items = mutableStateListOf<MealItemUi>()

    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<List<FoodHit>>(emptyList())
        private set

    /** Key of the item added last from the search, so the screen can scroll it into view. */
    var addedKey by mutableStateOf<Int?>(null)
        private set

    override val isEdit get() = mealId != null
    override val isComplete get() = items.isNotEmpty()

    init {
        if (mealId != null) viewModelScope.launch { load(mealId) }
    }

    private suspend fun load(id: Long) {
        val meal = journal.getMeal(id) ?: return
        existing = meal.meal
        timestamp = meal.meal.timestamp
        eatenOut = meal.meal.eatenOut
        note = meal.meal.note.orEmpty()
        val sorted = meal.items.sortedBy { it.item.id }
        val actual = journal.getMealItemIngredients(sorted.map { it.item.id })
        for (row in sorted) {
            val amount = row.item.count?.let { Amount.Count(it) } ?: Amount.Size(row.item.size ?: PortionSize.M)
            val ingredients = if (row.food.kind == FoodKind.DISH) {
                foods.getFoods(actual[row.item.id] ?: foods.getDishIngredientIds(row.food.id))
            } else {
                emptyList()
            }
            items += buildItem(row.food, amount, ingredients)
        }
    }

    fun onQueryChange(value: String) {
        query = value
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(150) // debounce typing
            results = foods.searchHits(value, limit = 12)
        }
    }

    suspend fun searchIngredients(text: String): List<FoodHit> = foods.searchHits(text, FoodKind.INGREDIENT, limit = 8)

    fun suggestTags(name: String): Set<Tag> = foods.suggestTags(name)

    fun addFood(food: FoodEntity) {
        query = ""
        results = emptyList()
        viewModelScope.launch {
            val ingredients = if (food.kind == FoodKind.DISH) foods.getFoods(foods.getDishIngredientIds(food.id)) else emptyList()
            val amount = if (food.unit == FoodUnit.COUNT) Amount.Count(1) else Amount.Size(PortionSize.M)
            val item = buildItem(food, amount, ingredients)
            items += item
            addedKey = item.key
        }
    }

    fun createFood(newFood: NewFood) {
        viewModelScope.launch {
            val id = when (newFood.kind) {
                FoodKind.INGREDIENT -> foods.createIngredient(
                    newFood.name, newFood.unit, newFood.unitLabel, newFood.tags.map { IngredientTag(it) },
                )
                FoodKind.DISH -> foods.createDish(newFood.name, newFood.unit, newFood.unitLabel, newFood.ingredientIds)
            }
            foods.getFood(id)?.let(::addFood)
        }
    }

    fun setAmount(key: Int, amount: Amount) = update(key) { it.copy(amount = amount) }

    fun removeItem(key: Int) {
        items.removeAll { it.key == key }
    }

    /** Applies an ingredient change to one meal item; [saveToDish] also changes the saved recipe from now on. */
    fun setIngredients(key: Int, ingredients: List<FoodEntity>, saveToDish: Boolean) {
        val item = items.firstOrNull { it.key == key } ?: return
        viewModelScope.launch {
            loadTags(ingredients)
            update(key) { it.copy(ingredients = ingredients) }
            if (saveToDish) foods.setDishIngredients(item.food.id, ingredients.map { it.id })
        }
    }

    override suspend fun save() {
        val meal = (existing ?: MealEntity(timestamp = timestamp, zoneId = ZoneId.systemDefault().id))
            .copy(timestamp = timestamp, eatenOut = eatenOut, note = note.trim().ifEmpty { null })
        val rows = items.map { item ->
            MealItemEntity(
                mealId = meal.id,
                foodId = item.food.id,
                count = (item.amount as? Amount.Count)?.n,
                size = (item.amount as? Amount.Size)?.size,
            )
        }
        val dishIngredients = items.withIndex()
            .filter { (_, item) -> item.food.kind == FoodKind.DISH }
            .associate { (index, item) -> index to item.ingredients.map { it.id } }
        journal.saveMeal(meal, rows, dishIngredients)
    }

    override suspend fun delete(): suspend () -> Unit = mealId?.let { journal.deleteMeal(it) } ?: {}

    private suspend fun buildItem(food: FoodEntity, amount: Amount, ingredients: List<FoodEntity>): MealItemUi {
        loadTags(if (food.kind == FoodKind.DISH) ingredients else listOf(food))
        return withTags(MealItemUi(nextKey++, food, amount, ingredients, emptySet()))
    }

    private suspend fun loadTags(foodsToLoad: List<FoodEntity>) {
        val missing = foodsToLoad.map { it.id }.filterNot { it in tagsById }
        if (missing.isEmpty()) return
        val loaded = foods.getIngredientTags(missing)
        missing.forEach { tagsById[it] = loaded[it].orEmpty() }
    }

    /** Effective tags: the ingredient's own, or the union over a dish's ingredients at the dish's amount. */
    private fun withTags(item: MealItemUi): MealItemUi {
        val sources = if (item.food.kind == FoodKind.DISH) item.ingredients else listOf(item.food)
        val tags = sources.flatMapTo(mutableSetOf()) { effectiveTags(tagsById[it.id].orEmpty(), item.amount) }
        return item.copy(effectiveTags = tags)
    }

    private fun update(key: Int, change: (MealItemUi) -> MealItemUi) {
        val index = items.indexOfFirst { it.key == key }
        if (index >= 0) items[index] = withTags(change(items[index]))
    }
}

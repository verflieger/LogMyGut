package at.mtr.logmygut.ui.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.mtr.logmygut.R
import at.mtr.logmygut.data.DishDraft
import at.mtr.logmygut.data.FoodHit
import at.mtr.logmygut.data.FoodRepository
import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.domain.isDrinkUnit
import at.mtr.logmygut.ui.common.EditorScaffold
import at.mtr.logmygut.ui.common.appContainer
import at.mtr.logmygut.ui.common.label
import at.mtr.logmygut.ui.common.unitName
import at.mtr.logmygut.ui.meal.IngredientListEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DishDetailViewModel(
    private val foods: FoodRepository,
    private val id: Long?,
    defaultUnitLabel: String,
    /** Shows a stored unit label in the app's language ([unitName]). */
    private val unitText: (String) -> String,
) : ViewModel() {
    var loaded by mutableStateOf(id == null); private set
    var food by mutableStateOf<FoodEntity?>(null); private set
    var name by mutableStateOf("")
    var aliases by mutableStateOf("")
    var unit by mutableStateOf(FoodUnit.SIZE); private set
    var unitLabel by mutableStateOf(defaultUnitLabel); private set
    var drink by mutableStateOf(false); private set
    /** Millilitres of one portion, for a drink. */
    var volume by mutableStateOf("")
    private var drinkTouched = false
    var ingredients by mutableStateOf<List<FoodEntity>>(emptyList()); private set
    /** Union of the ingredients' tags, whatever the amount (SPEC 4: a dish has no tags of its own). */
    var triggers by mutableStateOf<Set<Tag>>(emptySet()); private set
    var unitLocked by mutableStateOf(false); private set

    val isNew get() = id == null
    val canSave get() = loaded && name.isNotBlank() && ingredients.isNotEmpty() && (unit == FoodUnit.SIZE || unitLabel.isNotBlank())

    init {
        if (id != null) viewModelScope.launch { load(id) }
    }

    private suspend fun load(id: Long) {
        val f = foods.getFood(id) ?: return
        food = f
        name = f.name
        aliases = f.aliases.joinToString(", ")
        unit = f.unit
        f.unitLabel?.let { unitLabel = unitText(it) }
        drink = f.drink
        volume = amountText(f.portionGrams)
        unitLocked = foods.isUsedInMeals(id)
        updateIngredients(foods.getFoods(foods.getDishIngredientIds(id)))
        loaded = true
    }

    fun changeUnit(value: FoodUnit) { unit = value; followUnit() }
    fun changeUnitLabel(value: String) { unitLabel = value; followUnit() }
    fun changeDrink(value: Boolean) { drinkTouched = true; drink = value }

    /** A new dish counted in Tasse or Glas (a Melange) is a drink, until the user decides otherwise. */
    private fun followUnit() { if (isNew && !drinkTouched) drink = isDrinkUnit(unit, unitLabel) }

    fun changeIngredients(list: List<FoodEntity>) {
        viewModelScope.launch { updateIngredients(list) }
    }

    private suspend fun updateIngredients(list: List<FoodEntity>) {
        ingredients = list
        triggers = foods.getIngredientTags(list.map { it.id }).values.flatten().mapTo(sortedSetOf<Tag>()) { it.tag }
    }

    suspend fun searchIngredients(text: String): List<FoodHit> = foods.searchHits(text, FoodKind.INGREDIENT, limit = 8)

    suspend fun save() {
        foods.saveDish(
            DishDraft(
                id = id ?: 0,
                name = name,
                aliases = aliases.split(","),
                unit = unit,
                unitLabel = unitLabel.takeIf { unit == FoodUnit.COUNT },
                ingredientIds = ingredients.map { it.id },
                drink = drink,
                portionGrams = parseAmount(volume),
            ),
        )
    }

    suspend fun setHidden(hidden: Boolean) {
        val f = food ?: return
        foods.setHidden(f.id, hidden)
        food = f.copy(hidden = hidden)
    }

    suspend fun duplicate(copySuffix: String): Long = foods.duplicate(id!!, copySuffix)

    /** Returns the message to show when nothing could be reset, see [failureMessage]. */
    @StringRes
    suspend fun resetToSeed(): Int? {
        val result = foods.resetToSeed(id!!)
        load(id)
        return result.failureMessage()
    }
}

/** Dish detail (SPEC 4.1): name, unit, recipe and the triggers that follow from it. */
@Composable
fun DishDetailScreen(id: Long?, onDone: () -> Unit, onOpenCopy: (Long) -> Unit) {
    val container = appContainer()
    val defaultUnitLabel = stringResource(R.string.unit_label_default)
    // The application's resources, so the ViewModel doesn't keep an Activity alive.
    val appResources = LocalContext.current.applicationContext.resources
    val vm = viewModel { DishDetailViewModel(container.foodRepository, id, defaultUnitLabel, appResources::unitName) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val copySuffix = stringResource(R.string.food_copy_suffix)
    val food = vm.food

    EditorScaffold(
        title = if (vm.isNew) stringResource(R.string.title_dish_new) else food?.name.orEmpty(),
        onBack = onDone,
        onSave = { scope.launch(Dispatchers.Main.immediate) { vm.save(); onDone() } },
        saveEnabled = vm.canSave,
        onDelete = null,
        menu = if (food != null) {
            {
                FoodMenu(
                    food = food,
                    onHide = { hidden -> scope.launch { vm.setHidden(hidden) } },
                    onDuplicate = { scope.launch(Dispatchers.Main.immediate) { onOpenCopy(vm.duplicate(copySuffix)) } },
                    onReset = { scope.launch { context.showResetFailure(vm.resetToSeed()) } },
                )
            }
        } else {
            null
        },
    ) { modifier ->
        Column(
            modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FoodHeaderHints(food)
            OutlinedTextField(
                vm.name, { vm.name = it }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.food_name)) }, singleLine = true,
            )
            OutlinedTextField(
                vm.aliases, { vm.aliases = it }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.food_aliases)) },
                supportingText = { Text(stringResource(R.string.food_aliases_hint)) },
            )
            UnitFields(vm.unit, vm::changeUnit, vm.unitLabel, vm::changeUnitLabel, vm.unitLocked)
            PortionFields(vm.unit, vm.unitLabel, vm.drink, vm::changeDrink, vm.volume, { vm.volume = it }, showAmount = false)
            HorizontalDivider()
            Text(stringResource(R.string.dish_ingredients), style = MaterialTheme.typography.titleMedium)
            IngredientListEditor(vm.ingredients, vm::changeIngredients, vm::searchIngredients)
            HorizontalDivider()
            Text(stringResource(R.string.dish_triggers), style = MaterialTheme.typography.titleMedium)
            Text(
                vm.triggers.map { stringResource(it.label()) }.joinToString(" · ").ifEmpty { stringResource(R.string.dish_no_triggers) },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

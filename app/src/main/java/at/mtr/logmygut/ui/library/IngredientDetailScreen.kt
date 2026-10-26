package at.mtr.logmygut.ui.library

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.mtr.logmygut.R
import at.mtr.logmygut.data.FoodHit
import at.mtr.logmygut.data.FoodRepository
import at.mtr.logmygut.data.IngredientDraft
import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.IngredientTag
import at.mtr.logmygut.domain.PortionSize
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.domain.Threshold
import at.mtr.logmygut.domain.isDrinkUnit
import at.mtr.logmygut.ui.common.EditorScaffold
import at.mtr.logmygut.ui.common.SegmentedChoice
import at.mtr.logmygut.ui.common.appContainer
import at.mtr.logmygut.ui.common.label
import at.mtr.logmygut.ui.common.unitName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Ingredient editor state (SPEC 4.1). [tags] holds the final tags (present = on, value = threshold). */
class IngredientDetailViewModel(
    private val foods: FoodRepository,
    private val id: Long?,
    defaultUnitLabel: String,
    /** Shows a stored unit label in the app's language ([unitName]). */
    private val unitText: (String) -> String,
) : ViewModel() {
    var loaded by mutableStateOf(id == null); private set
    var food by mutableStateOf<FoodEntity?>(null); private set
    var name by mutableStateOf(""); private set
    var aliases by mutableStateOf("")
    var unit by mutableStateOf(FoodUnit.SIZE); private set
    var unitLabel by mutableStateOf(defaultUnitLabel); private set
    var drink by mutableStateOf(false); private set
    var grams by mutableStateOf("")
    var note by mutableStateOf("")
    var parent by mutableStateOf<FoodEntity?>(null); private set
    var parentTags by mutableStateOf<List<IngredientTag>>(emptyList()); private set
    val tags = mutableStateMapOf<Tag, Threshold?>()
    var unitLocked by mutableStateOf(false); private set
    var dishes by mutableStateOf<List<FoodEntity>>(emptyList()); private set
    private var tagsTouched = false
    private var drinkTouched = false

    val isNew get() = id == null
    val canSave get() = loaded && name.isNotBlank() && (unit == FoodUnit.SIZE || unitLabel.isNotBlank())

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
        grams = amountText(f.portionGrams)
        note = f.note.orEmpty()
        parent = f.parentId?.let { foods.getFood(it) }
        parentTags = f.parentId?.let { foods.getIngredientTags(it) }.orEmpty()
        setTags(foods.getIngredientTags(id))
        unitLocked = foods.isUsedInMeals(id)
        dishes = foods.dishesUsing(id)
        loaded = true
    }

    fun onNameChange(value: String) {
        name = value
        // A new ingredient gets keyword-based suggestions until the user picks tags or a parent.
        if (isNew && !tagsTouched && parent == null) setTags(foods.suggestTags(value).map { IngredientTag(it) })
    }

    fun changeUnit(value: FoodUnit) { unit = value; followUnit() }
    fun changeUnitLabel(value: String) { unitLabel = value; followUnit() }
    fun changeDrink(value: Boolean) { drinkTouched = true; drink = value }

    /** A new food counted in Glas or Tasse is a drink, until the user decides otherwise. */
    private fun followUnit() { if (isNew && !drinkTouched) drink = isDrinkUnit(unit, unitLabel) }

    fun setTag(tag: Tag, choice: TagChoice) {
        tagsTouched = true
        when (choice) {
            TagChoice.Off -> tags.remove(tag)
            is TagChoice.On -> tags[tag] = choice.threshold
        }
    }

    fun choice(tag: Tag): TagChoice = if (tag in tags) TagChoice.On(tags[tag]) else TagChoice.Off

    /** The value [tag] would inherit from the parent, or null without a parent. */
    fun inheritedChoice(tag: Tag): TagChoice? = parent?.let {
        parentTags.firstOrNull { t -> t.tag == tag }?.let { t -> TagChoice.On(t.threshold) } ?: TagChoice.Off
    }

    fun chooseParent(newParent: FoodEntity?) {
        viewModelScope.launch {
            parent = newParent
            parentTags = newParent?.let { foods.getIngredientTags(it.id) }.orEmpty()
            // New ingredients start from the parent's tags; existing ones keep what they have.
            if (isNew && !tagsTouched) setTags(parentTags)
        }
    }

    suspend fun parentCandidates(query: String): List<FoodHit> {
        val parentMap = foods.parentMap()
        return foods.searchHits(query, FoodKind.INGREDIENT, limit = 12).filter { foods.canBeParent(parentMap, id, it.food.id) }
    }

    suspend fun save() {
        foods.saveIngredient(
            IngredientDraft(
                id = id ?: 0,
                name = name,
                aliases = aliases.split(","),
                unit = unit,
                unitLabel = unitLabel.takeIf { unit == FoodUnit.COUNT },
                portionGrams = parseAmount(grams),
                note = note.trim().ifEmpty { null },
                parentId = parent?.id,
                tags = Tag.entries.filter { it in tags }.map { IngredientTag(it, tags[it]) },
                drink = drink,
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
        tagsTouched = false
        load(id)
        return result.failureMessage()
    }

    private fun setTags(list: List<IngredientTag>) {
        tags.clear()
        list.forEach { tags[it.tag] = it.threshold }
    }
}

/** How one tag is set in the editor: off, or on with an optional dose threshold. */
sealed interface TagChoice {
    data object Off : TagChoice
    data class On(val threshold: Threshold?) : TagChoice
}

/** Choices offered for a unit; an unusual current value (e.g. "ab 4") stays selectable. */
fun tagChoices(unit: FoodUnit, current: TagChoice): List<TagChoice> {
    val doses = when (unit) {
        FoodUnit.SIZE -> listOf(Threshold.MinSize(PortionSize.M), Threshold.MinSize(PortionSize.L))
        FoodUnit.COUNT -> listOf(Threshold.MinCount(2), Threshold.MinCount(3))
    }
    val base = listOf(TagChoice.Off, TagChoice.On(null)) + doses.map { TagChoice.On(it) }
    return if (current in base) base else base + current
}

@Composable
fun TagChoice.label(): String = when (this) {
    TagChoice.Off -> stringResource(R.string.tag_off)
    is TagChoice.On -> when (val t = threshold) {
        null -> stringResource(R.string.tag_always)
        is Threshold.MinSize -> stringResource(R.string.tag_from, t.size.name)
        is Threshold.MinCount -> stringResource(R.string.tag_from, t.n.toString())
    }
}

@Composable
fun IngredientDetailScreen(id: Long?, onDone: () -> Unit, onOpenFood: (FoodEntity) -> Unit, onOpenCopy: (Long) -> Unit) {
    val container = appContainer()
    val defaultUnitLabel = stringResource(R.string.unit_label_default)
    // The application's resources, so the ViewModel doesn't keep an Activity alive.
    val appResources = LocalContext.current.applicationContext.resources
    val vm = viewModel { IngredientDetailViewModel(container.foodRepository, id, defaultUnitLabel, appResources::unitName) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val copySuffix = stringResource(R.string.food_copy_suffix)
    var pickingParent by remember { mutableStateOf(false) }
    val food = vm.food

    EditorScaffold(
        title = if (vm.isNew) stringResource(R.string.title_ingredient_new) else food?.name.orEmpty(),
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
                vm.name, vm::onNameChange, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.food_name)) }, singleLine = true,
            )
            OutlinedTextField(
                vm.aliases, { vm.aliases = it }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.food_aliases)) },
                supportingText = { Text(stringResource(R.string.food_aliases_hint)) },
            )
            UnitFields(vm.unit, vm::changeUnit, vm.unitLabel, vm::changeUnitLabel, vm.unitLocked)
            PortionFields(vm.unit, vm.unitLabel, vm.drink, vm::changeDrink, vm.grams, { vm.grams = it })

            Text(stringResource(R.string.food_parent), style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = { pickingParent = true }, modifier = Modifier.fillMaxWidth()) {
                Text(vm.parent?.name ?: stringResource(R.string.food_parent_none))
            }
            Text(stringResource(R.string.food_parent_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            HorizontalDivider()
            Text(stringResource(R.string.food_tags), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.food_tags_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Tag.entries.forEach { tag ->
                TagRow(tag, vm.unit, vm.choice(tag), vm.inheritedChoice(tag), vm.parent?.name) { vm.setTag(tag, it) }
            }
            HorizontalDivider()

            OutlinedTextField(vm.note, { vm.note = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.food_note)) })
            if (vm.dishes.isNotEmpty()) {
                Text(stringResource(R.string.food_used_in), style = MaterialTheme.typography.labelLarge)
                vm.dishes.forEach { dish ->
                    ListItem(modifier = Modifier.clickable { onOpenFood(dish) }, headlineContent = { Text(dish.name) })
                }
            }
        }
    }

    if (pickingParent) {
        ParentPickerDialog(
            search = vm::parentCandidates,
            onPick = { picked ->
                vm.chooseParent(picked)
                pickingParent = false
            },
            onDismiss = { pickingParent = false },
        )
    }
}

@Composable
private fun TagRow(tag: Tag, unit: FoodUnit, choice: TagChoice, inherited: TagChoice?, parentName: String?, onChange: (TagChoice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(tag.label()), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (inherited != null && inherited != TagChoice.Off && choice == inherited && parentName != null) {
                Text(
                    stringResource(R.string.food_tag_inherited, parentName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SegmentedChoice(tagChoices(unit, choice), choice, { it.label() }, onChange)
    }
}

/** Unit choice; locked once the food is logged, because meal items store amounts in that unit. */
@Composable
fun UnitFields(unit: FoodUnit, onUnit: (FoodUnit) -> Unit, unitLabel: String, onUnitLabel: (String) -> Unit, locked: Boolean) {
    Text(stringResource(R.string.food_unit), style = MaterialTheme.typography.labelLarge)
    if (locked) {
        Text(stringResource(if (unit == FoodUnit.SIZE) R.string.unit_size else R.string.unit_count) + if (unit == FoodUnit.COUNT) " ($unitLabel)" else "")
        Text(stringResource(R.string.food_unit_locked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        SegmentedChoice(
            options = listOf(FoodUnit.SIZE, FoodUnit.COUNT),
            selected = unit,
            label = { stringResource(if (it == FoodUnit.SIZE) R.string.unit_size else R.string.unit_count) },
            onSelect = onUnit,
        )
        if (unit == FoodUnit.COUNT) {
            OutlinedTextField(
                unitLabel, onUnitLabel, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.new_food_unit_label)) }, singleLine = true,
            )
        }
    }
}

/**
 * Drink switch and portion size: grams, or millilitres for a drink. Drinks count toward the daily fluid total.
 * [showAmount] false hides the amount for foods that only need it as drinks (dishes).
 */
@Composable
fun PortionFields(
    unit: FoodUnit,
    unitLabel: String,
    drink: Boolean,
    onDrink: (Boolean) -> Unit,
    amount: String,
    onAmount: (String) -> Unit,
    showAmount: Boolean = true,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.food_drink), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.food_drink_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = drink, onCheckedChange = onDrink)
    }
    if (showAmount || drink) {
        OutlinedTextField(
            amount, onAmount, Modifier.fillMaxWidth(),
            label = {
                Text(
                    when {
                        unit == FoodUnit.SIZE -> stringResource(if (drink) R.string.food_ml_size else R.string.food_grams_size)
                        else -> stringResource(if (drink) R.string.food_ml_count else R.string.food_grams_count, unitLabel)
                    },
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )
    }
}

/** Parses an amount field ("250", "12,5"); empty or invalid means unknown. */
fun parseAmount(text: String): Double? = text.replace(',', '.').trim().toDoubleOrNull()?.takeIf { it > 0 }

/** The amount as the editor shows it: "250", or "12.5". */
fun amountText(value: Double?): String = value?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty()

/** "geändert" / "ausgeblendet" state and the from-now-on rule, shown above the fields. */
@Composable
fun FoodHeaderHints(food: FoodEntity?) {
    if (food?.hidden == true) {
        Text(stringResource(R.string.food_hidden_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    if (food?.tagsDetached == true) {
        Text(stringResource(R.string.food_tags_detached_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (food != null) {
        Text(stringResource(R.string.food_from_now_on), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

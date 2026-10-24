package at.mtr.logmygut.ui.meal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import at.mtr.logmygut.R
import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.ui.common.SegmentedChoice
import at.mtr.logmygut.ui.common.label
import kotlinx.coroutines.delay

/** "Zutaten anpassen" for one dish item (SPEC 3.2). */
@Composable
fun AdjustIngredientsDialog(
    dishName: String,
    initial: List<FoodEntity>,
    search: suspend (String) -> List<FoodEntity>,
    onConfirm: (ingredients: List<FoodEntity>, saveToDish: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    var saveToDish by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.adjust_title, dishName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                IngredientListEditor(selected, { selected = it }, search)
                Row(Modifier.fillMaxWidth().clickable { saveToDish = !saveToDish }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = saveToDish, onCheckedChange = null)
                    Column {
                        Text(stringResource(R.string.adjust_save_to_dish))
                        Text(stringResource(R.string.adjust_save_to_dish_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected, saveToDish) }, enabled = selected.isNotEmpty()) {
                Text(stringResource(R.string.action_done))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** "„…“ neu anlegen": creates an ingredient (with suggested tags) or a dish (with ingredients). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewFoodDialog(
    initialName: String,
    suggestTags: (String) -> Set<Tag>,
    search: suspend (String) -> List<FoodEntity>,
    onCreate: (NewFood) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName.trim()) }
    var kind by remember { mutableStateOf(FoodKind.INGREDIENT) }
    var unit by remember { mutableStateOf(FoodUnit.SIZE) }
    val defaultUnitLabel = stringResource(R.string.unit_label_default)
    var unitLabel by remember { mutableStateOf(defaultUnitLabel) }
    var tags by remember { mutableStateOf(suggestTags(initialName)) }
    var tagsTouched by remember { mutableStateOf(false) }
    var ingredients by remember { mutableStateOf<List<FoodEntity>>(emptyList()) }

    val valid = name.isNotBlank() &&
        (unit == FoodUnit.SIZE || unitLabel.isNotBlank()) &&
        (kind == FoodKind.INGREDIENT || ingredients.isNotEmpty())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_food_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    name,
                    {
                        name = it
                        // Keep suggestions in sync with the name until the user edits tags by hand.
                        if (!tagsTouched) tags = suggestTags(it)
                    },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.new_food_name)) },
                    singleLine = true,
                )
                SegmentedChoice(
                    options = listOf(FoodKind.INGREDIENT, FoodKind.DISH),
                    selected = kind,
                    label = { stringResource(if (it == FoodKind.DISH) R.string.kind_dish else R.string.kind_ingredient) },
                    onSelect = { kind = it },
                )
                Text(stringResource(R.string.new_food_unit), style = MaterialTheme.typography.labelLarge)
                SegmentedChoice(
                    options = listOf(FoodUnit.SIZE, FoodUnit.COUNT),
                    selected = unit,
                    label = { stringResource(if (it == FoodUnit.SIZE) R.string.unit_size else R.string.unit_count) },
                    onSelect = { unit = it },
                )
                if (unit == FoodUnit.COUNT) {
                    OutlinedTextField(
                        unitLabel, { unitLabel = it }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.new_food_unit_label)) },
                        singleLine = true,
                    )
                }
                when (kind) {
                    FoodKind.INGREDIENT -> {
                        Text(stringResource(R.string.new_food_tags), style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(R.string.new_food_tags_hint), style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Tag.entries.forEach { tag ->
                                FilterChip(
                                    selected = tag in tags,
                                    onClick = {
                                        tagsTouched = true
                                        tags = if (tag in tags) tags - tag else tags + tag
                                    },
                                    label = { Text(stringResource(tag.label())) },
                                )
                            }
                        }
                    }
                    FoodKind.DISH -> {
                        Text(stringResource(R.string.new_food_ingredients), style = MaterialTheme.typography.labelLarge)
                        IngredientListEditor(ingredients, { ingredients = it }, search)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(
                        NewFood(
                            name = name.trim(),
                            kind = kind,
                            unit = unit,
                            unitLabel = unitLabel.trim().takeIf { unit == FoodUnit.COUNT },
                            tags = if (kind == FoodKind.INGREDIENT) tags else emptySet(),
                            ingredientIds = ingredients.map { it.id },
                        ),
                    )
                },
                enabled = valid,
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Current ingredients with ✕, plus a search to add more. Used inside dialogs, so no lazy lists. */
@Composable
private fun IngredientListEditor(
    selected: List<FoodEntity>,
    onChange: (List<FoodEntity>) -> Unit,
    search: suspend (String) -> List<FoodEntity>,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FoodEntity>>(emptyList()) }
    LaunchedEffect(query) {
        delay(150)
        results = search(query).filterNot { r -> selected.any { it.id == r.id } }
    }

    Column {
        selected.forEach { ingredient ->
            ListItem(
                headlineContent = { Text(ingredient.name) },
                trailingContent = {
                    IconButton(onClick = { onChange(selected - ingredient) }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_remove))
                    }
                },
            )
        }
        if (selected.isEmpty()) {
            Text(stringResource(R.string.picker_need_one), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.picker_search_hint)) },
            singleLine = true,
        )
        results.forEach { result ->
            ListItem(
                modifier = Modifier.clickable {
                    onChange(selected + result)
                    query = ""
                },
                headlineContent = { Text(result.name) },
                leadingContent = { Icon(Icons.Outlined.Add, contentDescription = null) },
            )
        }
    }
}


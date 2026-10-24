package at.mtr.logmygut.ui.meal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.LunchDining
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import at.mtr.logmygut.R
import at.mtr.logmygut.data.FoodHit
import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.ui.common.AmountSelector
import at.mtr.logmygut.ui.common.DateTimeField
import at.mtr.logmygut.ui.common.EditorScaffold
import at.mtr.logmygut.ui.common.appContainer
import at.mtr.logmygut.ui.common.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** [onOpenFood] opens a food's library detail; reached by long-pressing a search result (SPEC 4.1). */
@Composable
fun MealEditScreen(mealId: Long?, onDone: () -> Unit, onDeleted: (suspend () -> Unit) -> Unit, onOpenFood: (FoodEntity) -> Unit) {
    val container = appContainer()
    val vm = viewModel { MealEditViewModel(container.foodRepository, container.journalRepository, mealId) }
    val scope = rememberCoroutineScope()
    var adjusting by remember { mutableStateOf<MealItemUi?>(null) }
    var creatingName by remember { mutableStateOf<String?>(null) }

    EditorScaffold(
        title = stringResource(if (vm.isEdit) R.string.meal_title_edit else R.string.meal_title_new),
        onBack = onDone,
        onSave = { scope.launch(Dispatchers.Main.immediate) { vm.save(); onDone() } },
        saveEnabled = vm.canSave,
        onDelete = if (vm.isEdit) ({ scope.launch(Dispatchers.Main.immediate) { onDeleted(vm.delete()); onDone() } }) else null,
    ) { modifier ->
        LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { DateTimeField(vm.timestamp, { vm.timestamp = it }, isError = vm.isFuture) }

            item {
                OutlinedTextField(
                    vm.query, vm::onQueryChange, Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.meal_search_hint)) },
                    singleLine = true,
                )
            }
            if (vm.query.isNotBlank()) {
                items(vm.results, key = { "result-${it.food.id}" }) { hit ->
                    SearchResultRow(hit, onClick = { vm.addFood(hit.food) }, onLongClick = { onOpenFood(hit.food) })
                }
                item {
                    ListItem(
                        modifier = Modifier.clickable { creatingName = vm.query },
                        leadingContent = { Icon(Icons.Outlined.Add, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.meal_create_new, vm.query.trim())) },
                    )
                    HorizontalDivider()
                }
            }

            if (vm.items.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.meal_no_items),
                        Modifier.padding(vertical = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(vm.items, key = { "item-${it.key}" }) { item ->
                MealItemCard(
                    item = item,
                    onAmount = { vm.setAmount(item.key, it) },
                    onRemove = { vm.removeItem(item.key) },
                    onAdjust = { adjusting = item },
                )
            }

            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.meal_eaten_out))
                        Text(stringResource(R.string.meal_eaten_out_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = vm.eatenOut, onCheckedChange = { vm.eatenOut = it })
                }
            }
            item {
                OutlinedTextField(vm.note, { vm.note = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_note)) })
            }
        }
    }

    adjusting?.let { item ->
        AdjustIngredientsDialog(
            dishName = item.food.name,
            initial = item.ingredients,
            search = vm::searchIngredients,
            onConfirm = { ingredients, saveToDish ->
                vm.setIngredients(item.key, ingredients, saveToDish)
                adjusting = null
            },
            onDismiss = { adjusting = null },
        )
    }
    creatingName?.let { name ->
        NewFoodDialog(
            initialName = name,
            suggestTags = vm::suggestTags,
            search = vm::searchIngredients,
            onCreate = {
                vm.createFood(it)
                creatingName = null
            },
            onDismiss = { creatingName = null },
        )
    }
}

@Composable
private fun SearchResultRow(hit: FoodHit, onClick: () -> Unit, onLongClick: () -> Unit) {
    val kind = stringResource(if (hit.food.kind == FoodKind.DISH) R.string.kind_dish else R.string.kind_ingredient)
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        leadingContent = { Icon(hit.food.kindIcon(), contentDescription = null) },
        headlineContent = { Text(hit.food.name) },
        supportingContent = { Text(hitContext(hit, kind)) },
    )
}

@Composable
private fun MealItemCard(item: MealItemUi, onAmount: (at.mtr.logmygut.domain.Amount) -> Unit, onRemove: () -> Unit, onAdjust: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(item.food.kindIcon(), contentDescription = null)
                Text(item.food.name, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_remove)) }
            }
            AmountSelector(item.amount, item.food.unitLabel, item.food.portionGrams, onAmount)
            if (item.food.kind == FoodKind.DISH) {
                Text(item.ingredients.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onAdjust, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.meal_adjust_ingredients)) }
            }
            val tags = item.effectiveTags.sortedBy { it.ordinal }.map { stringResource(it.label()) }
            Text(
                if (tags.isEmpty()) stringResource(R.string.meal_no_triggers) else stringResource(R.string.meal_triggers, tags.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Dish or ingredient icon, shared by the meal editor and the library. */
fun FoodEntity.kindIcon() = if (kind == FoodKind.DISH) Icons.Outlined.LunchDining else Icons.Outlined.Eco

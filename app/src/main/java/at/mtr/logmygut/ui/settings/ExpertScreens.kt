package at.mtr.logmygut.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.mtr.logmygut.R
import at.mtr.logmygut.data.FoodRepository
import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.data.seed.SeedChangesRow
import at.mtr.logmygut.data.seed.SeedDifference
import at.mtr.logmygut.ui.common.appContainer
import at.mtr.logmygut.ui.common.label
import at.mtr.logmygut.ui.library.LibraryRow
import at.mtr.logmygut.ui.library.TagChoice
import at.mtr.logmygut.ui.library.label
import at.mtr.logmygut.ui.meal.kindIcon
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class DetachedIngredientsViewModel(foods: FoodRepository) : ViewModel() {
    val rows: StateFlow<List<LibraryRow>?> = foods.observeDetached().map { list ->
        val parentNames = foods.getFoods(list.mapNotNull { it.parentId }.distinct()).associate { it.id to it.name }
        list.map { LibraryRow(it, it.parentId?.let(parentNames::get)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

class SeedChangesViewModel(foods: FoodRepository) : ViewModel() {
    val rows: StateFlow<List<SeedChangesRow>?> =
        foods.observeSeedChanges().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** "Abgekoppelte Zutaten" (SPEC 2): built-in ingredients whose tags no longer follow the seed. */
@Composable
fun DetachedIngredientsScreen(onBack: () -> Unit, onOpen: (FoodEntity) -> Unit) {
    val container = appContainer()
    val vm = viewModel { DetachedIngredientsViewModel(container.foodRepository) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    ExpertList(
        title = stringResource(R.string.expert_detached_title),
        hint = stringResource(R.string.expert_detached_hint),
        empty = stringResource(R.string.expert_detached_empty),
        rows = rows?.map { ExpertRow(it.food, listOf(it.parentName ?: stringResource(R.string.expert_none))) },
        onBack = onBack,
        onOpen = onOpen,
    )
}

/** "Änderungen gegenüber Standard" (SPEC 2): built-in foods that differ from their seed entry, and how. */
@Composable
fun SeedChangesScreen(onBack: () -> Unit, onOpen: (FoodEntity) -> Unit) {
    val container = appContainer()
    val vm = viewModel { SeedChangesViewModel(container.foodRepository) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    ExpertList(
        title = stringResource(R.string.expert_changes_title),
        hint = null,
        empty = stringResource(R.string.expert_changes_empty),
        rows = rows?.map { row ->
            ExpertRow(row.food, row.differences.map { it.text() }.ifEmpty { listOf(stringResource(R.string.expert_no_difference)) })
        },
        onBack = onBack,
        onOpen = onOpen,
    )
}

/** A food in an [ExpertList], with lines of supporting text. */
private data class ExpertRow(val food: FoodEntity, val lines: List<String>)

/** A list of foods, each with lines of supporting text; [rows] null while loading. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpertList(
    title: String,
    hint: String?,
    empty: String,
    rows: List<ExpertRow>?,
    onBack: () -> Unit,
    onOpen: (FoodEntity) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            if (hint != null) {
                item {
                    Text(
                        hint,
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (rows != null && rows.isEmpty()) {
                item {
                    Text(
                        empty,
                        Modifier.fillMaxWidth().padding(24.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(rows.orEmpty(), key = { it.food.id }) { (food, lines) ->
                ListItem(
                    modifier = Modifier.clickable { onOpen(food) },
                    leadingContent = { Icon(food.kindIcon(), contentDescription = null) },
                    headlineContent = { Text(food.name) },
                    supportingContent = { Column { lines.forEach { Text(it) } } },
                )
            }
        }
    }
}

@Composable
private fun SeedDifference.text(): String = when (this) {
    is SeedDifference.Renamed -> stringResource(R.string.expert_diff_renamed, seedName)
    SeedDifference.Aliases -> stringResource(R.string.expert_diff_aliases)
    SeedDifference.Note -> stringResource(R.string.expert_diff_note)
    SeedDifference.UnitChanged -> stringResource(R.string.expert_diff_unit)
    SeedDifference.PortionWeight -> stringResource(R.string.expert_diff_portion)
    SeedDifference.MarkedDrink -> stringResource(R.string.expert_diff_drink_on)
    SeedDifference.UnmarkedDrink -> stringResource(R.string.expert_diff_drink_off)
    is SeedDifference.Parent -> stringResource(
        R.string.expert_diff_parent,
        current ?: stringResource(R.string.expert_none),
        seed ?: stringResource(R.string.expert_none),
    )
    is SeedDifference.RecipeAdded ->
        pluralStringResource(R.plurals.expert_diff_recipe_added, names.size, names.joinToString())
    is SeedDifference.RecipeRemoved ->
        pluralStringResource(R.plurals.expert_diff_recipe_removed, names.size, names.joinToString())
    is SeedDifference.TagAdded -> {
        val tag = stringResource(tag.label())
        threshold?.let { stringResource(R.string.expert_diff_tag_added_from, tag, TagChoice.On(it).label()) }
            ?: stringResource(R.string.expert_diff_tag_added, tag)
    }
    is SeedDifference.TagRemoved -> stringResource(R.string.expert_diff_tag_removed, stringResource(tag.label()))
    is SeedDifference.TagThreshold ->
        stringResource(R.string.expert_diff_tag_threshold, stringResource(tag.label()), TagChoice.On(threshold).label())
    SeedDifference.NotInSeed -> stringResource(R.string.expert_diff_not_in_seed)
    SeedDifference.Detached -> stringResource(R.string.expert_diff_detached)
}

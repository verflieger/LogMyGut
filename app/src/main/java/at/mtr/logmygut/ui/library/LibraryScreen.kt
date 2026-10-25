package at.mtr.logmygut.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import at.mtr.logmygut.data.db.matchesSearch
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.normalizeForSearch
import at.mtr.logmygut.ui.common.appContainer
import at.mtr.logmygut.ui.meal.kindIcon
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

data class LibraryRow(val food: FoodEntity, val parentName: String?)

/** The list split into the screen's sections (SPEC 4.1). */
data class LibrarySections(val own: List<LibraryRow>, val builtIn: List<LibraryRow>, val hidden: List<LibraryRow>) {
    val isEmpty get() = own.isEmpty() && builtIn.isEmpty() && hidden.isEmpty()
}

class LibraryViewModel(foods: FoodRepository) : ViewModel() {
    val kind = MutableStateFlow(FoodKind.DISH)
    val query = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val sections: StateFlow<LibrarySections?> = combine(kind.flatMapLatest { foods.observeLibrary(it) }, query) { list, query ->
        val namesById = list.associate { it.id to it.name }
        val normalized = normalizeForSearch(query)
        val rows = list
            .filter { normalized.isEmpty() || it.matchesSearch(normalized) }
            .map { LibraryRow(it, it.parentId?.let(namesById::get)) }
        LibrarySections(
            own = rows.filter { !it.food.builtIn && !it.food.hidden },
            builtIn = rows.filter { it.food.builtIn && !it.food.hidden },
            hidden = rows.filter { it.food.hidden },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** "Lebensmittel & Gerichte" (SPEC 4.1): browse, search and open any food; create new ones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onBack: () -> Unit, onOpen: (FoodEntity) -> Unit, onNew: (FoodKind) -> Unit) {
    val container = appContainer()
    val vm = viewModel { LibraryViewModel(container.foodRepository) }
    val kind by vm.kind.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val sections by vm.sections.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onNew(kind) },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(if (kind == FoodKind.DISH) R.string.library_new_dish else R.string.library_new_ingredient)) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            val tabs = listOf(FoodKind.DISH to R.string.library_tab_dishes, FoodKind.INGREDIENT to R.string.library_tab_ingredients)
            PrimaryTabRow(selectedTabIndex = tabs.indexOfFirst { it.first == kind }) {
                tabs.forEach { (tabKind, label) ->
                    Tab(selected = kind == tabKind, onClick = { vm.kind.value = tabKind }, text = { Text(stringResource(label)) })
                }
            }
            OutlinedTextField(
                query, { vm.query.value = it },
                Modifier.fillMaxWidth().padding(16.dp),
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.library_search)) },
                singleLine = true,
            )
            val current = sections
            // Bottom padding keeps the last row clear of the floating button.
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                if (current != null) {
                    section(R.string.library_own, current.own, onOpen)
                    section(R.string.library_builtin, current.builtIn, onOpen)
                    section(R.string.library_hidden, current.hidden, onOpen)
                    if (current.isEmpty) {
                        item {
                            Text(
                                stringResource(R.string.library_nothing_found),
                                Modifier.fillMaxWidth().padding(24.dp),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.section(title: Int, rows: List<LibraryRow>, onOpen: (FoodEntity) -> Unit) {
    if (rows.isEmpty()) return
    item(key = "header-$title") {
        Text(
            stringResource(title),
            Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    items(rows, key = { it.food.id }) { row ->
        val modified = stringResource(R.string.library_modified).takeIf { row.food.builtIn && row.food.userModified }
        val supporting = listOfNotNull(row.parentName, modified).joinToString(" · ")
        ListItem(
            modifier = Modifier.clickable { onOpen(row.food) },
            leadingContent = { Icon(row.food.kindIcon(), contentDescription = null) },
            headlineContent = { Text(row.food.name) },
            supportingContent = supporting.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
        )
    }
}

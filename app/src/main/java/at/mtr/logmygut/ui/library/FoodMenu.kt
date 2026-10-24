package at.mtr.logmygut.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import at.mtr.logmygut.R
import at.mtr.logmygut.data.FoodHit
import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.ui.meal.hitContext
import kotlinx.coroutines.delay

/** ⋮ menu of a food's detail screen (SPEC 4.1): hide/show, duplicate, and reset for edited built-ins. */
@Composable
fun FoodMenu(food: FoodEntity, onHide: (Boolean) -> Unit, onDuplicate: () -> Unit, onReset: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.food_menu))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(if (food.hidden) R.string.food_unhide else R.string.food_hide)) },
            onClick = {
                open = false
                onHide(!food.hidden)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.food_duplicate)) },
            onClick = {
                open = false
                onDuplicate()
            },
        )
        if (food.builtIn && food.userModified) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.food_reset)) },
                onClick = {
                    open = false
                    onReset()
                },
            )
        }
    }
}

/** Search for an ingredient to become the parent; [search] already leaves out invalid choices. */
@Composable
fun ParentPickerDialog(search: suspend (String) -> List<FoodHit>, onPick: (FoodEntity?) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FoodHit>>(emptyList()) }
    LaunchedEffect(query) {
        delay(150)
        results = search(query)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.food_parent_choose)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.picker_search_hint)) },
                    singleLine = true,
                )
                results.forEach { hit ->
                    ListItem(
                        modifier = Modifier.clickable { onPick(hit.food) },
                        headlineContent = { Text(hit.food.name) },
                        supportingContent = hitContext(hit, kind = null).ifEmpty { null }?.let { { Text(it) } },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.food_parent_none)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

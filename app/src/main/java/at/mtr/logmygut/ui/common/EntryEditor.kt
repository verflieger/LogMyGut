package at.mtr.logmygut.ui.common

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import at.mtr.logmygut.R
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * What the four entry editors (meal, bowel movement, symptoms, medication) share: a time that may not lie in the
 * future, and saving or deleting the entry.
 */
abstract class EntryEditViewModel(initialTime: Instant) : ViewModel() {
    var timestamp by mutableStateOf(initialTime)

    abstract val isEdit: Boolean
    /** Whether the entry's own fields are filled in; saving also needs a time that isn't in the future. */
    protected abstract val isComplete: Boolean

    val isFuture get() = timestamp.isAfter(Instant.now())
    val canSave get() = isComplete && !isFuture

    abstract suspend fun save()

    /** Deletes the entry and returns how to undo it. */
    abstract suspend fun delete(): suspend () -> Unit
}

/**
 * [EditorScaffold] for an entry: the title depends on new or edit, delete is offered when editing, and
 * [onOpenInfo] adds a help button. [onDeleted] receives the undo.
 */
@Composable
fun EntryEditorScaffold(
    vm: EntryEditViewModel,
    @StringRes titleNew: Int,
    @StringRes titleEdit: Int,
    onDone: () -> Unit,
    onDeleted: (suspend () -> Unit) -> Unit,
    onOpenInfo: (() -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    EditorScaffold(
        title = stringResource(if (vm.isEdit) titleEdit else titleNew),
        onBack = onDone,
        onSave = { scope.launch(Dispatchers.Main.immediate) { vm.save(); onDone() } },
        saveEnabled = vm.canSave,
        onDelete = if (vm.isEdit) ({ scope.launch(Dispatchers.Main.immediate) { onDeleted(vm.delete()); onDone() } }) else null,
        menu = onOpenInfo?.let { open ->
            {
                IconButton(onClick = open) {
                    Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = stringResource(R.string.info_more))
                }
            }
        },
        content = content,
    )
}

/** The entry's date and time, marked as an error when it lies in the future. */
@Composable
fun EntryTimeField(vm: EntryEditViewModel) {
    DateTimeField(vm.timestamp, { vm.timestamp = it }, isError = vm.isFuture)
}

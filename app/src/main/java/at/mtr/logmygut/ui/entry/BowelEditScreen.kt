package at.mtr.logmygut.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.mtr.logmygut.R
import at.mtr.logmygut.data.JournalRepository
import at.mtr.logmygut.data.db.BowelMovementEntity
import at.mtr.logmygut.domain.PainChange
import at.mtr.logmygut.ui.common.DateTimeField
import at.mtr.logmygut.ui.common.EditorScaffold
import at.mtr.logmygut.ui.common.SegmentedChoice
import at.mtr.logmygut.ui.common.appContainer
import at.mtr.logmygut.ui.common.bristolDescription
import at.mtr.logmygut.ui.common.bristolGroup
import at.mtr.logmygut.ui.common.label
import at.mtr.logmygut.ui.common.nowMinute
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BowelEditViewModel(private val journal: JournalRepository, private val id: Long?) : ViewModel() {
    private var existing: BowelMovementEntity? = null
    var timestamp by mutableStateOf(nowMinute())
    var bristol by mutableStateOf<Int?>(null)
    var urgent by mutableStateOf(false)
    var painBefore by mutableStateOf(false)
    var painAfter by mutableStateOf<PainChange?>(null)
    var note by mutableStateOf("")

    val isEdit get() = id != null
    val isFuture get() = timestamp.isAfter(Instant.now())
    /** With pain before, "danach" must be answered, so the Rome IV evaluation has complete data. */
    val canSave get() = bristol != null && !isFuture && (!painBefore || painAfter != null)

    init {
        if (id != null) viewModelScope.launch {
            journal.getBowelMovement(id)?.let {
                existing = it
                timestamp = it.timestamp
                bristol = it.bristol
                urgent = it.urgent
                painBefore = it.painBefore
                painAfter = it.painAfter
                note = it.note.orEmpty()
            }
        }
    }

    suspend fun save() {
        val base = existing ?: BowelMovementEntity(timestamp = timestamp, zoneId = ZoneId.systemDefault().id, bristol = 4)
        journal.saveBowelMovement(
            base.copy(
                timestamp = timestamp,
                bristol = requireNotNull(bristol),
                urgent = urgent,
                painBefore = painBefore,
                painAfter = painAfter.takeIf { painBefore },
                note = note.trim().ifEmpty { null },
            ),
        )
    }

    suspend fun delete(): suspend () -> Unit = existing?.let { journal.deleteBowelMovement(it) } ?: {}
}

@Composable
fun BowelEditScreen(id: Long?, onDone: () -> Unit, onDeleted: (suspend () -> Unit) -> Unit) {
    val container = appContainer()
    val vm = viewModel { BowelEditViewModel(container.journalRepository, id) }
    val scope = rememberCoroutineScope()

    EditorScaffold(
        title = stringResource(if (vm.isEdit) R.string.bowel_title_edit else R.string.bowel_title_new),
        onBack = onDone,
        onSave = { scope.launch(Dispatchers.Main.immediate) { vm.save(); onDone() } },
        saveEnabled = vm.canSave,
        onDelete = if (vm.isEdit) ({ scope.launch(Dispatchers.Main.immediate) { onDeleted(vm.delete()); onDone() } }) else null,
    ) { modifier ->
        Column(
            modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DateTimeField(vm.timestamp, { vm.timestamp = it }, isError = vm.isFuture)

            Text(stringResource(R.string.bowel_bristol), style = MaterialTheme.typography.labelLarge)
            Column {
                for (type in 1..7) BristolOption(type, selected = vm.bristol == type, onSelect = { vm.bristol = type })
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.bowel_urgent), Modifier.weight(1f))
                Switch(checked = vm.urgent, onCheckedChange = { vm.urgent = it })
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.bowel_pain_before), Modifier.weight(1f))
                Switch(checked = vm.painBefore, onCheckedChange = { vm.painBefore = it })
            }
            if (vm.painBefore) {
                Text(stringResource(R.string.bowel_pain_after), style = MaterialTheme.typography.labelLarge)
                SegmentedChoice(
                    options = PainChange.entries,
                    selected = vm.painAfter,
                    label = { stringResource(it.label()) },
                    onSelect = { vm.painAfter = it },
                )
            }

            OutlinedTextField(vm.note, { vm.note = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_note)) })
        }
    }
}

@Composable
private fun BristolOption(type: Int, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp).size(32.dp),
        ) {
            Text("$type", Modifier.padding(4.dp), style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(bristolDescription(type)), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(bristolGroup(type)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

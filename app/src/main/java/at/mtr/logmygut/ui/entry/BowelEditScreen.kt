package at.mtr.logmygut.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.mtr.logmygut.R
import at.mtr.logmygut.data.JournalRepository
import at.mtr.logmygut.data.db.BowelMovementEntity
import at.mtr.logmygut.domain.PainChange
import at.mtr.logmygut.ui.common.BristolIcon
import at.mtr.logmygut.ui.common.EntryEditViewModel
import at.mtr.logmygut.ui.common.EntryEditorScaffold
import at.mtr.logmygut.ui.common.EntryTimeField
import at.mtr.logmygut.ui.common.SegmentedChoice
import at.mtr.logmygut.ui.common.appContainer
import at.mtr.logmygut.ui.common.bristolDescription
import at.mtr.logmygut.ui.common.bristolGroup
import at.mtr.logmygut.ui.common.label
import at.mtr.logmygut.ui.common.newEntryTime
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

class BowelEditViewModel(private val journal: JournalRepository, private val id: Long?, day: LocalDate? = null) :
    EntryEditViewModel(newEntryTime(day)) {
    private var existing: BowelMovementEntity? = null
    var bristol by mutableStateOf<Int?>(null)
    var urgent by mutableStateOf(false)
    var painBefore by mutableStateOf(false)
    var painAfter by mutableStateOf<PainChange?>(null)
    var note by mutableStateOf("")

    override val isEdit get() = id != null
    /** With pain before, "danach" must be answered, so the Rome IV evaluation has complete data. */
    override val isComplete get() = bristol != null && (!painBefore || painAfter != null)

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

    override suspend fun save() {
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

    override suspend fun delete(): suspend () -> Unit = existing?.let { journal.deleteBowelMovement(it) } ?: {}
}

@Composable
fun BowelEditScreen(id: Long?, day: LocalDate?, onDone: () -> Unit, onDeleted: (suspend () -> Unit) -> Unit, onOpenInfo: () -> Unit) {
    val container = appContainer()
    val vm = viewModel { BowelEditViewModel(container.journalRepository, id, day) }

    EntryEditorScaffold(vm, R.string.bowel_title_new, R.string.bowel_title_edit, onDone, onDeleted, onOpenInfo) { modifier ->
        Column(
            modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EntryTimeField(vm)

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
        BristolIcon(type, Modifier.padding(horizontal = 8.dp), height = 32.dp)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.bowel_type, type), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(bristolDescription(type)), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(bristolGroup(type)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

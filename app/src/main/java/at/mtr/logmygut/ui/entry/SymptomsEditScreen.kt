package at.mtr.logmygut.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.mtr.logmygut.R
import at.mtr.logmygut.data.JournalRepository
import at.mtr.logmygut.data.db.SymptomEntryEntity
import at.mtr.logmygut.domain.SymptomType
import at.mtr.logmygut.ui.common.DateTimeField
import at.mtr.logmygut.ui.common.EditorScaffold
import at.mtr.logmygut.ui.common.appContainer
import at.mtr.logmygut.ui.common.label
import at.mtr.logmygut.ui.common.nowMinute
import at.mtr.logmygut.ui.common.severityLabel
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Edits one symptom group; [groupTimestamp] identifies an existing group, null creates a new one. */
class SymptomsEditViewModel(private val journal: JournalRepository, private val groupTimestamp: Instant?) : ViewModel() {
    var timestamp by mutableStateOf(groupTimestamp ?: nowMinute())
    /** Only touched symptoms are present. */
    val severities = mutableStateMapOf<SymptomType, Int>()
    var note by mutableStateOf("")

    val isEdit get() = groupTimestamp != null
    val isFuture get() = timestamp.isAfter(Instant.now())
    val canSave get() = severities.isNotEmpty() && !isFuture

    init {
        if (groupTimestamp != null) viewModelScope.launch {
            val entries = journal.getSymptomsAt(groupTimestamp)
            entries.forEach { severities[it.type] = it.severity }
            note = entries.firstNotNullOfOrNull { it.note }.orEmpty()
        }
    }

    suspend fun save() {
        val zone = ZoneId.systemDefault().id
        val trimmedNote = note.trim().ifEmpty { null }
        // The note is stored on the first entry of the group only.
        val entries = SymptomType.entries.mapNotNull { type -> severities[type]?.let { type to it } }
            .mapIndexed { index, (type, severity) ->
                SymptomEntryEntity(timestamp = timestamp, zoneId = zone, type = type, severity = severity, note = trimmedNote.takeIf { index == 0 })
            }
        journal.saveSymptoms(groupTimestamp, entries)
    }

    suspend fun delete(): suspend () -> Unit = groupTimestamp?.let { journal.deleteSymptomsAt(it) } ?: {}
}

@Composable
fun SymptomsEditScreen(groupTimestamp: Instant?, onDone: () -> Unit, onDeleted: (suspend () -> Unit) -> Unit, onOpenInfo: () -> Unit) {
    val container = appContainer()
    val vm = viewModel { SymptomsEditViewModel(container.journalRepository, groupTimestamp) }
    val scope = rememberCoroutineScope()

    EditorScaffold(
        title = stringResource(if (vm.isEdit) R.string.symptoms_title_edit else R.string.symptoms_title_new),
        onBack = onDone,
        onSave = { scope.launch(Dispatchers.Main.immediate) { vm.save(); onDone() } },
        saveEnabled = vm.canSave,
        onDelete = if (vm.isEdit) ({ scope.launch(Dispatchers.Main.immediate) { onDeleted(vm.delete()); onDone() } }) else null,
        menu = {
            IconButton(onClick = onOpenInfo) {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = stringResource(R.string.info_more))
            }
        },
    ) { modifier ->
        Column(
            modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DateTimeField(vm.timestamp, { vm.timestamp = it }, isError = vm.isFuture)
            Text(stringResource(R.string.symptoms_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (type in SymptomType.entries) {
                SeverityRow(
                    label = stringResource(type.label()),
                    value = vm.severities[type],
                    onChange = { vm.severities[type] = it },
                    onClear = { vm.severities.remove(type) },
                )
            }
            if (vm.severities.isEmpty()) {
                Text(stringResource(R.string.symptoms_need_one), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(vm.note, { vm.note = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_note)) })
        }
    }
}

/** A 0–10 slider that starts as "nicht erfasst" until touched; ✕ clears it again. */
@Composable
private fun SeverityRow(label: String, value: Int?, onChange: (Int) -> Unit, onClear: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(
                if (value == null) stringResource(R.string.symptoms_not_set) else "$value · ${stringResource(severityLabel(value))}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )
            IconButton(onClick = onClear, enabled = value != null) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_remove))
            }
        }
        Slider(
            value = (value ?: 0).toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..10f,
            steps = 9,
            modifier = Modifier.alpha(if (value == null) 0.4f else 1f),
        )
    }
}

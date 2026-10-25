package at.mtr.logmygut.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.mtr.logmygut.R
import at.mtr.logmygut.data.JournalRepository
import at.mtr.logmygut.data.db.MedicationIntakeEntity
import at.mtr.logmygut.domain.MedEffect
import at.mtr.logmygut.domain.normalizeForSearch
import at.mtr.logmygut.ui.common.EntryEditViewModel
import at.mtr.logmygut.ui.common.EntryEditorScaffold
import at.mtr.logmygut.ui.common.EntryTimeField
import at.mtr.logmygut.ui.common.appContainer
import at.mtr.logmygut.ui.common.label
import at.mtr.logmygut.ui.common.newEntryTime
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

class MedicationEditViewModel(private val journal: JournalRepository, private val id: Long?, day: LocalDate? = null) :
    EntryEditViewModel(newEntryTime(day)) {
    private var existing: MedicationIntakeEntity? = null
    private var known: List<Pair<String, MedEffect?>> by mutableStateOf(emptyList())
    var name by mutableStateOf("")
        private set
    var effect by mutableStateOf<MedEffect?>(null)
    var note by mutableStateOf("")

    override val isEdit get() = id != null
    override val isComplete get() = name.isNotBlank()

    /** Previously used names matching the current input (all of them when the field is empty). */
    val suggestions: List<Pair<String, MedEffect?>>
        get() {
            val query = normalizeForSearch(name)
            return known.filter { (n, _) -> normalizeForSearch(n).let { it.contains(query) && it != query } }.take(6)
        }

    init {
        viewModelScope.launch {
            known = journal.medicationSuggestions()
            if (id != null) journal.getMedication(id)?.let {
                existing = it
                timestamp = it.timestamp
                name = it.name
                effect = it.effect
                note = it.note.orEmpty()
            }
        }
    }

    fun onNameChange(value: String) {
        name = value
    }

    /** Picking a known name also pre-fills its last effect tag (SPEC 3.5). */
    fun pickSuggestion(suggestion: Pair<String, MedEffect?>) {
        name = suggestion.first
        effect = suggestion.second
    }

    override suspend fun save() {
        val base = existing ?: MedicationIntakeEntity(timestamp = timestamp, zoneId = ZoneId.systemDefault().id, name = name)
        journal.saveMedication(base.copy(timestamp = timestamp, name = name, effect = effect, note = note.trim().ifEmpty { null }))
    }

    override suspend fun delete(): suspend () -> Unit = existing?.let { journal.deleteMedication(it) } ?: {}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MedicationEditScreen(id: Long?, day: LocalDate?, onDone: () -> Unit, onDeleted: (suspend () -> Unit) -> Unit) {
    val container = appContainer()
    val vm = viewModel { MedicationEditViewModel(container.journalRepository, id, day) }

    EntryEditorScaffold(vm, R.string.med_title_new, R.string.med_title_edit, onDone, onDeleted) { modifier ->
        Column(
            modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EntryTimeField(vm)

            OutlinedTextField(
                vm.name, vm::onNameChange, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.med_name)) },
                singleLine = true,
            )
            val suggestions = vm.suggestions
            if (suggestions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { s -> SuggestionChip(onClick = { vm.pickSuggestion(s) }, label = { Text(s.first) }) }
                }
            }

            Text(stringResource(R.string.med_effect), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MedEffect.entries.forEach { e ->
                    FilterChip(
                        selected = vm.effect == e,
                        onClick = { vm.effect = if (vm.effect == e) null else e },
                        label = { Text(stringResource(e.label())) },
                    )
                }
            }

            OutlinedTextField(vm.note, { vm.note = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_note)) })
        }
    }
}

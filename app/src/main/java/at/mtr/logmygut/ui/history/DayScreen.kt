package at.mtr.logmygut.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import at.mtr.logmygut.data.JournalRepository
import at.mtr.logmygut.data.TimelineEntry
import at.mtr.logmygut.data.fluidMillilitres
import at.mtr.logmygut.data.db.DailyCheckInEntity
import at.mtr.logmygut.ui.common.CheckInCard
import at.mtr.logmygut.ui.common.Loaded
import at.mtr.logmygut.ui.common.LongDayFormat
import at.mtr.logmygut.ui.common.appContainer
import at.mtr.logmygut.ui.common.newEntryTime
import at.mtr.logmygut.ui.common.volumeLabel
import at.mtr.logmygut.ui.today.FluidRow
import at.mtr.logmygut.ui.today.QuickActions
import at.mtr.logmygut.ui.today.TimelineRow
import at.mtr.logmygut.ui.today.key
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DayViewModel(private val journal: JournalRepository, val date: LocalDate) : ViewModel() {
    val timeline: StateFlow<List<TimelineEntry>?> = journal.observeDay(date)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val checkIn: StateFlow<Loaded<DailyCheckInEntity?>?> = journal.observeCheckIn(date).map { Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun saveSleep(value: Int) { viewModelScope.launch { journal.saveSleep(date, value) } }
    fun saveStress(value: Int) { viewModelScope.launch { journal.saveStress(date, value) } }
    fun addGlassOfWater() { viewModelScope.launch { journal.addGlassOfWater(newEntryTime(date)) } }
}

/** The kinds of entry the day view can add. */
enum class EntryKind { MEAL, BOWEL, SYMPTOMS, MEDICATION }

/**
 * One day from the calendar (SPEC 2, 6.1): add buttons, fluid total, check-in and timeline. Entries open their
 * editors; new entries default to this day, so forgotten ones can be added later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(date: LocalDate, onBack: () -> Unit, onOpen: (TimelineEntry) -> Unit, onAdd: (EntryKind) -> Unit) {
    val container = appContainer()
    val vm = viewModel(key = date.toString()) { DayViewModel(container.journalRepository, date) }
    val timeline by vm.timeline.collectAsStateWithLifecycle()
    val checkIn by vm.checkIn.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(LongDayFormat.format(date)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "add") {
                QuickActions(
                    onAddMeal = { onAdd(EntryKind.MEAL) },
                    onAddBowel = { onAdd(EntryKind.BOWEL) },
                    onAddSymptoms = { onAdd(EntryKind.SYMPTOMS) },
                    onAddMedication = { onAdd(EntryKind.MEDICATION) },
                )
            }
            timeline?.let { entries ->
                item(key = "fluid") { FluidRow(stringResource(R.string.fluid_day, volumeLabel(fluidMillilitres(entries))), vm::addGlassOfWater) }
            }
            checkIn?.let { loaded -> item(key = "checkin") { CheckInCard(loaded.value, vm::saveSleep, vm::saveStress) } }
            val entries = timeline
            if (entries != null && entries.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.day_empty),
                        Modifier.fillMaxWidth().padding(top = 24.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(entries.orEmpty(), key = { it.key() }) { entry -> TimelineRow(entry, onClick = { onOpen(entry) }) }
        }
    }
}

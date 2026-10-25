package at.mtr.logmygut.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SentimentDissatisfied
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Wc
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import at.mtr.logmygut.data.db.DailyCheckInEntity
import at.mtr.logmygut.data.fluidMillilitres
import at.mtr.logmygut.domain.Amount
import at.mtr.logmygut.ui.common.BristolIcon
import at.mtr.logmygut.ui.common.CheckInCard
import at.mtr.logmygut.ui.common.DayHeaderFormat
import at.mtr.logmygut.ui.common.Loaded
import at.mtr.logmygut.ui.common.TimeFormat
import at.mtr.logmygut.ui.common.appContainer
import at.mtr.logmygut.ui.common.bristolGroup
import at.mtr.logmygut.ui.common.label
import at.mtr.logmygut.ui.common.local
import at.mtr.logmygut.ui.common.nowMinute
import at.mtr.logmygut.ui.common.short
import at.mtr.logmygut.ui.common.volumeLabel
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TodayViewModel(private val journal: JournalRepository) : ViewModel() {
    val date: LocalDate = LocalDate.now()
    val timeline: StateFlow<List<TimelineEntry>?> = journal.observeDay(date)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val checkIn: StateFlow<Loaded<DailyCheckInEntity?>?> = journal.observeCheckIn(date).map { Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun saveSleep(value: Int) { viewModelScope.launch { journal.saveSleep(date, value) } }
    fun saveStress(value: Int) { viewModelScope.launch { journal.saveStress(date, value) } }
    fun addGlassOfWater() { viewModelScope.launch { journal.addGlassOfWater(nowMinute()) } }
}

@Composable
fun TodayScreen(
    onAddMeal: () -> Unit,
    onAddBowel: () -> Unit,
    onAddSymptoms: () -> Unit,
    onAddMedication: () -> Unit,
    onOpen: (TimelineEntry) -> Unit,
) {
    val container = appContainer()
    val vm = viewModel { TodayViewModel(container.journalRepository) }
    val timeline by vm.timeline.collectAsStateWithLifecycle()
    val checkIn by vm.checkIn.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(DayHeaderFormat.format(vm.date), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            QuickActions(onAddMeal, onAddBowel, onAddSymptoms, onAddMedication)
            Spacer(Modifier.height(8.dp))
            timeline?.let { FluidRow(fluidMillilitres(it), vm::addGlassOfWater) }
        }
        // While part of today's check-in is missing (SPEC 2); afterwards it's edited from the day view.
        checkIn?.value.let { current ->
            if (checkIn != null && (current?.sleep == null || current.stress == null)) {
                item(key = "checkin") { CheckInCard(current, vm::saveSleep, vm::saveStress) }
            }
        }
        val entries = timeline
        if (entries != null && entries.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.today_empty),
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(entries.orEmpty(), key = { it.key() }) { entry -> TimelineRow(entry, onClick = { onOpen(entry) }) }
    }
}

@Composable
private fun QuickActions(onAddMeal: () -> Unit, onAddBowel: () -> Unit, onAddSymptoms: () -> Unit, onAddMedication: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickButton(R.string.quick_meal, Icons.Outlined.Restaurant, onAddMeal, Modifier.weight(1f))
            QuickButton(R.string.quick_bowel, Icons.Outlined.Wc, onAddBowel, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickButton(R.string.quick_symptoms, Icons.Outlined.SentimentDissatisfied, onAddSymptoms, Modifier.weight(1f))
            QuickButton(R.string.quick_medication, Icons.Outlined.Medication, onAddMedication, Modifier.weight(1f))
        }
    }
}

@Composable
private fun QuickButton(label: Int, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(64.dp)) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
    }
}

/** Today's fluid total from all logged drinks, with a one-tap glass of water (SPEC 3.2). */
@Composable
private fun FluidRow(millilitres: Double, onAddWater: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.WaterDrop, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.fluid_today, volumeLabel(millilitres)),
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedButton(onClick = onAddWater) {
            Icon(Icons.Outlined.Add, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.fluid_add_water))
        }
    }
}

/** A timeline row; also used by the history day view. */
@Composable
fun TimelineRow(entry: TimelineEntry, onClick: () -> Unit) {
    val time = entry.timestamp.local()
    val (icon, headline, supporting) = when (entry) {
        // What was eaten is the headline; the time above it says when.
        is TimelineEntry.Meal -> Triple(
            if (entry.drinksOnly) Icons.Outlined.LocalCafe else Icons.Outlined.Restaurant,
            entry.items.joinToString(", ") { item ->
                val amount = item.count?.let { Amount.Count(it) } ?: item.size?.let { Amount.Size(it) }
                if (amount == null) item.name else "${item.name} (${amount.short(item.unitLabel)})"
            },
            stringResource(R.string.timeline_eaten_out).takeIf { entry.eatenOut },
        )
        is TimelineEntry.BowelMovement -> Triple(
            Icons.Outlined.Wc,
            stringResource(R.string.timeline_bristol, entry.entry.bristol) + " · " + stringResource(bristolGroup(entry.entry.bristol)),
            listOfNotNull(
                stringResource(R.string.timeline_urgent).takeIf { entry.entry.urgent },
                entry.entry.painAfter?.let { stringResource(R.string.timeline_pain, stringResource(it.label())) },
            ).joinToString(" · ").ifEmpty { null },
        )
        is TimelineEntry.Symptoms -> Triple(
            Icons.Outlined.SentimentDissatisfied,
            stringResource(R.string.quick_symptoms),
            entry.values.map { (type, severity) -> "${stringResource(type.label())} $severity" }.joinToString(" · "),
        )
        is TimelineEntry.Medication -> Triple(
            Icons.Outlined.Medication,
            entry.name,
            entry.effect?.let { stringResource(it.label()) },
        )
    }
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        ListItem(
            leadingContent = {
                if (entry is TimelineEntry.BowelMovement) BristolIcon(entry.entry.bristol) else Icon(icon, contentDescription = null)
            },
            overlineContent = { Text(TimeFormat.format(time)) },
            headlineContent = { Text(headline) },
            supportingContent = supporting?.let { { Text(it) } },
        )
    }
}

/** Stable LazyColumn key; also used by the history day view. */
fun TimelineEntry.key(): String = when (this) {
    is TimelineEntry.Meal -> "meal-$mealId"
    is TimelineEntry.BowelMovement -> "bowel-${entry.id}"
    is TimelineEntry.Symptoms -> "symptoms-${timestamp.toEpochMilli()}"
    is TimelineEntry.Medication -> "med-$id"
}

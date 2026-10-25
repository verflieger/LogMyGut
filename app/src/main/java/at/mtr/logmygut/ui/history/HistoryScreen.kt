package at.mtr.logmygut.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.mtr.logmygut.R
import at.mtr.logmygut.data.DaySummary
import at.mtr.logmygut.data.JournalRepository
import at.mtr.logmygut.domain.ScoreBand
import at.mtr.logmygut.ui.common.AppLocale
import at.mtr.logmygut.ui.common.LongDayFormat
import at.mtr.logmygut.ui.common.appContainer
import at.mtr.logmygut.ui.common.bandColors
import at.mtr.logmygut.ui.common.severityLabel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

private val MonthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL yyyy", AppLocale)

class HistoryViewModel(private val journal: JournalRepository) : ViewModel() {
    val today: LocalDate = LocalDate.now()
    private val currentMonth = YearMonth.from(today)
    private val _month = MutableStateFlow(currentMonth)
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val days: StateFlow<Map<LocalDate, DaySummary>?> = _month
        .flatMapLatest { journal.observeMonth(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val canGoNext get() = _month.value < currentMonth

    fun previous() { _month.value = _month.value.minusMonths(1) }
    fun next() { if (canGoNext) _month.value = _month.value.plusMonths(1) }
}

/** Verlauf tab (SPEC 2): month calendar coloured by the day score (SPEC 6.2). */
@Composable
fun HistoryScreen(onOpenDay: (LocalDate) -> Unit) {
    val container = appContainer()
    val vm = viewModel { HistoryViewModel(container.journalRepository) }
    val month by vm.month.collectAsStateWithLifecycle()
    val days by vm.days.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::previous) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.history_prev_month))
            }
            Text(
                MonthFormat.format(month).replaceFirstChar { it.titlecase(AppLocale) },
                Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = vm::next, enabled = vm.canGoNext) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.history_next_month))
            }
        }
        MonthGrid(month, days.orEmpty(), vm.today, onOpenDay)
        Legend()
        Text(stringResource(R.string.history_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MonthGrid(month: YearMonth, days: Map<LocalDate, DaySummary>, today: LocalDate, onOpenDay: (LocalDate) -> Unit) {
    // Austrian weeks start on Monday.
    val leadingBlanks = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
    val cells: List<LocalDate?> = List(leadingBlanks) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            DayOfWeek.entries.forEach { day ->
                Text(
                    day.getDisplayName(TextStyle.SHORT, AppLocale).removeSuffix("."),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (week + List(7 - week.size) { null }).forEach { date ->
                    Box(Modifier.weight(1f).aspectRatio(1f)) {
                        if (date != null) DayCell(date, days[date], isToday = date == today, isFuture = date > today) { onOpenDay(date) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, summary: DaySummary?, isToday: Boolean, isFuture: Boolean, onClick: () -> Unit) {
    val score = summary?.score
    val (background, content) = when {
        score != null -> bandColors(ScoreBand.of(score))
        summary != null -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Color.Transparent to MaterialTheme.colorScheme.onSurface
    }
    val label = LongDayFormat.format(date)
    val description = when {
        score != null -> stringResource(R.string.history_day_score, label, score)
        summary != null -> stringResource(R.string.history_day_logged, label)
        else -> stringResource(R.string.history_day_empty, label)
    } + if (summary?.antibiotic == true) ", " + stringResource(R.string.antibiotic_day) else ""
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .fillMaxSize()
            .alpha(if (isFuture) 0.38f else 1f)
            .background(background, shape)
            .then(if (isToday) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape) else Modifier)
            .then(if (isFuture) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyLarge, color = content)
        if (summary != null && score == null) {
            // Logged, but nothing to score: a small dot instead of a colour.
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).size(4.dp).background(content, CircleShape))
        }
        if (summary?.antibiotic == true) {
            Icon(Icons.Outlined.Medication, null, Modifier.align(Alignment.TopEnd).padding(3.dp).size(12.dp), tint = content)
        }
    }
}

@Composable
private fun Legend() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.history_legend_title), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "0", 2 to "1–3", 5 to "4–6", 8 to "7–10").forEach { (sample, range) ->
                LegendItem(bandColors(ScoreBand.of(sample)).first, "$range ${stringResource(severityLabel(sample))}")
            }
            LegendItem(MaterialTheme.colorScheme.surfaceVariant, stringResource(R.string.history_legend_logged))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Medication, null, Modifier.size(14.dp))
                Text(stringResource(R.string.antibiotic_day), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(14.dp).background(color, RoundedCornerShape(4.dp)))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

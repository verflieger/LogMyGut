package at.mtr.logmygut.ui.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.mtr.logmygut.R
import at.mtr.logmygut.domain.ScoreBand
import at.mtr.logmygut.domain.SymptomType
import at.mtr.logmygut.ui.common.AppLocale
import at.mtr.logmygut.ui.common.appContainer
import at.mtr.logmygut.ui.common.bandColors
import at.mtr.logmygut.ui.common.label
import at.mtr.logmygut.ui.common.severityLabel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val ReadoutDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EE, d. MMM", AppLocale)

/** Analyse tab (SPEC 6): trends and the trigger analysis. */
@Composable
fun AnalysisScreen(onOpenDay: (LocalDate) -> Unit, onOpenMeal: (Long) -> Unit, onOpenSettings: () -> Unit) {
    val container = appContainer()
    val vm = viewModel { AnalysisViewModel(container.analysisRepository, container.settings) }
    // New entries and changed settings show up when coming back to the tab.
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    var tab by rememberSaveable { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.analysis_tab_trends)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.analysis_tab_triggers)) })
        }
        when (tab) {
            0 -> TrendsTab(vm, onOpenDay)
            else -> TriggersTab(vm, onOpenMeal, onOpenSettings)
        }
    }
}

@Composable
private fun TrendsTab(vm: AnalysisViewModel, onOpenDay: (LocalDate) -> Unit) {
    val trends by vm.trends.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val symptom by vm.symptom.collectAsStateWithLifecycle()
    // One selected day for all charts, so they can be compared.
    var selected by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    val data = trends ?: return

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrendRanges.forEach { days ->
                FilterChip(
                    selected = range == days,
                    onClick = { vm.setRange(days) },
                    label = { Text(stringResource(R.string.analysis_range_days, days)) },
                )
            }
        }
        if (data.antibioticDays.any { it in data.days }) {
            LegendRow { LegendSwatch(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f), stringResource(R.string.antibiotic_day)) }
        }

        ChartCard(stringResource(R.string.analysis_bristol_title)) {
            Readout(selected, data) { day ->
                val types = data.bristol[day].orEmpty()
                if (types.isEmpty()) stringResource(R.string.analysis_no_bowel)
                else stringResource(R.string.analysis_bristol_readout, types.joinToString(", ")) +
                    (data.bristolMedian[day]?.let { " · " + stringResource(R.string.analysis_median_readout, decimal(it)) } ?: "")
            }
            BristolChart(data, selected, { selected = it }, stringResource(R.string.analysis_bristol_description, data.days.size))
            LegendRow {
                LegendDot(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), stringResource(R.string.analysis_legend_bowel))
                LegendLine(MaterialTheme.colorScheme.primary, stringResource(R.string.analysis_legend_median))
                LegendSwatch(bandColors(ScoreBand.NONE).first, stringResource(R.string.analysis_legend_normal))
            }
        }

        ChartCard(stringResource(R.string.analysis_symptom_title)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SymptomType.entries.forEach { type ->
                    FilterChip(selected = symptom == type, onClick = { vm.setSymptom(type) }, label = { Text(stringResource(type.shortLabel())) })
                }
            }
            val values = data.symptomMax.getValue(symptom)
            Readout(selected, data) { day ->
                values[day]?.let { stringResource(R.string.analysis_severity_readout, it, stringResource(severityLabel(it))) }
                    ?: stringResource(R.string.analysis_no_entry)
            }
            SeverityChart(
                data.days, values, data.antibioticDays, selected, { selected = it },
                stringResource(R.string.analysis_symptom_description, stringResource(symptom.label()), data.days.size),
            )
        }

        ChartCard(stringResource(R.string.analysis_count_title)) {
            Readout(selected, data) { day ->
                val n = data.bowelCounts[day] ?: 0
                pluralStringResource(R.plurals.analysis_bowel_count, n, n)
            }
            CountChart(
                data.days, data.bowelCounts, data.antibioticDays, selected, { selected = it },
                stringResource(R.string.analysis_count_description, data.days.size),
            )
        }

        ChartCard(stringResource(R.string.analysis_heatmap_title)) {
            Text(
                stringResource(R.string.analysis_heatmap_hint, HeatmapWeeks),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ScoreHeatmap(
                data.dayScores, data.antibioticDays, data.today, HeatmapWeeks, onOpenDay,
                stringResource(R.string.analysis_heatmap_description, HeatmapWeeks),
            )
            LegendRow {
                listOf(0 to "0", 2 to "1–3", 5 to "4–6", 8 to "7–10").forEach { (sample, range) ->
                    LegendSwatch(bandColors(ScoreBand.of(sample)).first, range)
                }
                LegendSwatch(MaterialTheme.colorScheme.surfaceVariant, stringResource(R.string.analysis_legend_no_score))
                LegendDot(MaterialTheme.colorScheme.onSurface, stringResource(R.string.antibiotic_day))
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** The selected day's values above a chart, or a hint to tap. */
@Composable
private fun Readout(selected: LocalDate?, data: TrendData, text: @Composable (LocalDate) -> String) {
    val line = if (selected == null || selected !in data.days) {
        stringResource(R.string.analysis_tap_hint)
    } else {
        ReadoutDayFormat.format(selected) + ": " + text(selected) +
            if (selected in data.antibioticDays) " · " + stringResource(R.string.antibiotic_day) else ""
    }
    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LegendRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun LegendSwatch(color: Color, label: String) = LegendItem(label) {
    Box(Modifier.size(12.dp).background(color, RoundedCornerShape(3.dp)))
}

@Composable
private fun LegendDot(color: Color, label: String) = LegendItem(label) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

@Composable
private fun LegendLine(color: Color, label: String) = LegendItem(label) {
    Canvas(Modifier.width(16.dp).size(16.dp, 8.dp)) {
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx())
    }
}

@Composable
private fun LegendItem(label: String, key: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        key()
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** "4,5" in Austrian German. */
internal fun decimal(value: Double): String = String.format(AppLocale, "%.1f", value)

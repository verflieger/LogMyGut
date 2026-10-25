package at.mtr.logmygut.ui.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.mtr.logmygut.R
import at.mtr.logmygut.data.MealSummary
import at.mtr.logmygut.domain.Hint
import at.mtr.logmygut.domain.MealOutcome
import at.mtr.logmygut.domain.Outcome
import at.mtr.logmygut.domain.Rate
import at.mtr.logmygut.domain.SymptomType
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.domain.TagWindow
import at.mtr.logmygut.domain.TriggerResult
import at.mtr.logmygut.ui.common.ShortDateTimeFormat
import at.mtr.logmygut.ui.common.label
import at.mtr.logmygut.ui.common.local
import at.mtr.logmygut.ui.info.InfoPage
import kotlin.math.roundToInt

/** Trigger → symptom analysis (SPEC 6.3): disclaimer, current hints, then all tags for one outcome. */
@Composable
fun TriggersTab(vm: AnalysisViewModel, onOpenMeal: (Long) -> Unit, onOpenSettings: () -> Unit, onOpenInfo: (InfoPage) -> Unit) {
    val state by vm.triggers.collectAsStateWithLifecycle()
    var outcomeIndex by rememberSaveable { mutableStateOf(0) }
    var open by remember { mutableStateOf<Pair<Tag, Outcome>?>(null) }
    val current = state ?: return
    val outcome = Outcome.all[outcomeIndex]
    val hints = current.results.filter { it.hint == Hint.STRONG || it.hint == Hint.WEAK }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "disclaimer") {
            Disclaimer()
            Row {
                TextButton(onClick = { onOpenInfo(InfoPage.TRIGGERS) }) { Text(stringResource(R.string.analysis_triggers_info)) }
                TextButton(onClick = { onOpenInfo(InfoPage.ABOUT) }) { Text(stringResource(R.string.info_disclaimer_title)) }
            }
        }
        item(key = "hints-title") { SectionHeader(stringResource(R.string.analysis_hints_title)) }
        if (hints.isEmpty()) {
            item(key = "no-hints") {
                Text(stringResource(R.string.analysis_no_hints), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(hints, key = { "hint-${it.tag}-${it.outcome}" }) { result ->
            TriggerRow(result, current.params.window(result.tag), showOutcome = true) { open = result.tag to result.outcome }
        }
        item(key = "all-title") { SectionHeader(stringResource(R.string.analysis_all_title)) }
        item(key = "outcomes") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Outcome.all.size) { index ->
                    FilterChip(
                        selected = index == outcomeIndex,
                        onClick = { outcomeIndex = index },
                        label = { Text(outcomeLabel(Outcome.all[index])) },
                    )
                }
            }
        }
        items(current.results.filter { it.outcome == outcome }, key = { "all-${it.tag}-${it.outcome}" }) { result ->
            TriggerRow(result, current.params.window(result.tag), showOutcome = false) { open = result.tag to result.outcome }
        }
        item(key = "settings") {
            Column(Modifier.padding(top = 8.dp)) {
                Text(
                    stringResource(R.string.analysis_params_summary, current.params.symptomThreshold, current.params.minExposures),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenSettings, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.analysis_settings_link)) }
            }
        }
    }

    open?.let { (tag, outcome) ->
        current.results.firstOrNull { it.tag == tag && it.outcome == outcome }?.let { result ->
            MealsSheet(result, current.meals, onDismiss = { open = null }, onOpenMeal = { id -> open = null; onOpenMeal(id) })
        }
    }
}

@Composable
private fun Disclaimer() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = null)
            Text(stringResource(R.string.analysis_disclaimer), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun outcomeLabel(outcome: Outcome): String = when (outcome) {
    is Outcome.Symptom -> stringResource(outcome.type.shortLabel())
    Outcome.Diarrhea -> stringResource(R.string.outcome_diarrhea)
}

/** Short symptom names for chips and row titles. */
internal fun SymptomType.shortLabel(): Int = when (this) {
    SymptomType.BLOATING -> R.string.symptom_short_bloating
    SymptomType.PAIN -> R.string.symptom_short_pain
    SymptomType.GAS -> R.string.symptom_short_gas
    SymptomType.NAUSEA -> R.string.symptom_nausea
    SymptomType.FATIGUE -> R.string.symptom_fatigue
    SymptomType.HEADACHE -> R.string.symptom_headache
}

@Composable
private fun TriggerRow(result: TriggerResult, window: TagWindow, showOutcome: Boolean, onClick: () -> Unit) {
    val tag = stringResource(result.tag.label())
    val title = if (showOutcome) "$tag → ${outcomeLabel(result.outcome)}" else tag
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        if (result.hint == Hint.INSUFFICIENT) {
            Row(Modifier.padding(16.dp).alpha(0.6f), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.analysis_too_little_data, result.exposed.n), style = MaterialTheme.typography.bodySmall)
            }
            return@OutlinedCard
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                HintBadge(result.hint)
            }
            Text(stringResource(R.string.analysis_with, rateText(result.exposed)), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.analysis_without, rateText(result.control)) + (ratioText(result.ratio)?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (result.withEnzyme.isNotEmpty()) {
                val enzyme = Rate(result.withEnzyme.count { it.positive }, result.withEnzyme.size)
                Text(stringResource(R.string.analysis_with_lactase, rateText(enzyme)), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                stringResource(R.string.analysis_window, windowText(window)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HintBadge(hint: Hint) {
    // Neutral colours, no warning icon: a badge describes the user's entries, it is not an alarm (SPEC 6.3).
    val (container, content, text) = when (hint) {
        Hint.STRONG -> Triple(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, R.string.hint_strong)
        Hint.WEAK -> Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, R.string.hint_weak)
        else -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, R.string.hint_none)
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(stringResource(text), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun rateText(rate: Rate): String =
    stringResource(R.string.analysis_rate, rate.positive, rate.n, (rate.value * 100).roundToInt())

@Composable
private fun ratioText(ratio: Double?): String? = when {
    ratio == null -> null
    ratio.isInfinite() -> stringResource(R.string.analysis_ratio_only_with)
    else -> stringResource(R.string.analysis_ratio, decimal(ratio))
}

@Composable
internal fun windowText(window: TagWindow): String =
    stringResource(R.string.analysis_window_range, durationText(window.from), durationText(window.to))

/** "0", "30 min", "8 h", "1 h 30 min". */
internal fun durationText(d: java.time.Duration): String {
    val hours = d.toHours()
    val minutes = d.toMinutesPart()
    return when {
        d.isZero -> "0"
        hours == 0L -> "$minutes min"
        minutes == 0 -> "$hours h"
        else -> "$hours h $minutes min"
    }
}

/** The meals behind a result, newest first, marking those followed by the outcome. Tapping one opens it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MealsSheet(result: TriggerResult, meals: Map<Long, MealSummary>, onDismiss: () -> Unit, onOpenMeal: (Long) -> Unit) {
    val tag = stringResource(result.tag.label())
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("$tag → ${outcomeLabel(result.outcome)}", style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.analysis_sheet_hint, tag),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (result.exposures.isEmpty()) {
                item { Text(stringResource(R.string.analysis_sheet_empty, tag), Modifier.padding(16.dp)) }
            }
            items(result.exposures, key = { "e${it.mealId}" }) { MealRow(it, meals[it.mealId], onOpenMeal) }
            if (result.withEnzyme.isNotEmpty()) {
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(stringResource(R.string.analysis_sheet_lactase), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall)
                }
                items(result.withEnzyme, key = { "l${it.mealId}" }) { MealRow(it, meals[it.mealId], onOpenMeal) }
            }
        }
    }
}

@Composable
private fun MealRow(outcome: MealOutcome, meal: MealSummary?, onOpen: (Long) -> Unit) {
    meal ?: return
    val time = meal.time.local()
    ListItem(
        modifier = Modifier.clickable { onOpen(meal.id) },
        overlineContent = { Text(ShortDateTimeFormat.format(time)) },
        headlineContent = { Text(meal.itemNames.joinToString(", ")) },
        supportingContent = if (outcome.positive) {
            { Text(stringResource(R.string.analysis_sheet_followed), color = MaterialTheme.colorScheme.error) }
        } else {
            null
        },
        // An empty slot keeps rows without the icon aligned with those that have it.
        leadingContent = {
            if (outcome.positive) Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else Spacer(Modifier.size(24.dp))
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

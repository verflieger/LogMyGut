package at.mtr.logmygut.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.mtr.logmygut.R
import at.mtr.logmygut.data.SettingsRepository
import at.mtr.logmygut.domain.AnalysisParams
import at.mtr.logmygut.domain.DefaultTagWindows
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.domain.TagWindow
import at.mtr.logmygut.ui.analysis.durationText
import at.mtr.logmygut.ui.analysis.windowText
import at.mtr.logmygut.ui.common.appContainer
import at.mtr.logmygut.ui.common.label
import java.time.Duration
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AnalysisSettingsViewModel(private val settings: SettingsRepository) : ViewModel() {
    val params: StateFlow<AnalysisParams?> = settings.analysis.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setThreshold(value: Int) { viewModelScope.launch { settings.setSymptomThreshold(value) } }
    fun setMinExposures(value: Int) { viewModelScope.launch { settings.setMinExposures(value) } }
    fun setWindow(tag: Tag, window: TagWindow) { viewModelScope.launch { settings.setTagWindow(tag, window) } }
    fun reset() { viewModelScope.launch { settings.resetAnalysis() } }
}

/** Choices for a window end; coarser as the times grow. */
private val WindowSteps: List<Duration> =
    listOf(0L, 15, 30, 45).map(Duration::ofMinutes) + listOf(1L, 2, 3, 4, 5, 6, 8, 10, 12, 18, 24, 36, 48, 72).map(Duration::ofHours)

/** Analysis parameters (SPEC 5, 6.3): symptom threshold, minimum exposures and the window per tag. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisSettingsScreen(onBack: () -> Unit) {
    val container = appContainer()
    val vm = viewModel { AnalysisSettingsViewModel(container.settings) }
    val params by vm.params.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Tag?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.analysis_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        val p = params ?: return@Scaffold
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.analysis_settings_threshold)) },
                supportingContent = { Text(stringResource(R.string.analysis_settings_threshold_hint)) },
                trailingContent = { Stepper(p.symptomThreshold, 1..10, vm::setThreshold) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.analysis_settings_min_exposures)) },
                supportingContent = { Text(stringResource(R.string.analysis_settings_min_exposures_hint)) },
                trailingContent = { Stepper(p.minExposures, 1..50, vm::setMinExposures) },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                stringResource(R.string.analysis_settings_windows),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.analysis_settings_windows_hint),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Tag.entries.forEach { tag ->
                val window = p.window(tag)
                ListItem(
                    modifier = Modifier.clickable { editing = tag },
                    headlineContent = { Text(stringResource(tag.label())) },
                    trailingContent = { Text(windowText(window), style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = if (window != DefaultTagWindows[tag]) {
                        { Text(stringResource(R.string.analysis_settings_default, windowText(DefaultTagWindows.getValue(tag)))) }
                    } else {
                        null
                    },
                )
            }
            TextButton(onClick = { confirmReset = true }, modifier = Modifier.padding(8.dp)) {
                Text(stringResource(R.string.analysis_settings_reset))
            }
        }

        editing?.let { tag ->
            WindowDialog(stringResource(tag.label()), p.window(tag), onDismiss = { editing = null }) {
                vm.setWindow(tag, it)
                editing = null
            }
        }
        if (confirmReset) {
            AlertDialog(
                onDismissRequest = { confirmReset = false },
                text = { Text(stringResource(R.string.analysis_settings_reset_confirm)) },
                confirmButton = {
                    TextButton(onClick = { vm.reset(); confirmReset = false }) { Text(stringResource(R.string.analysis_settings_reset)) }
                },
                dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
    }
}

@Composable
private fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalIconButton(onClick = { onChange(value - 1) }, enabled = value > range.first) {
            Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.action_decrease))
        }
        Text(value.toString(), Modifier.widthIn(min = 24.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        FilledTonalIconButton(onClick = { onChange(value + 1) }, enabled = value < range.last) {
            Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.action_increase))
        }
    }
}

/** Picks the start and end of a window from [WindowSteps]; the start always stays before the end. */
@Composable
private fun WindowDialog(title: String, initial: TagWindow, onDismiss: () -> Unit, onSave: (TagWindow) -> Unit) {
    fun indexOf(d: Duration) = WindowSteps.indexOfFirst { it >= d }.takeIf { it >= 0 } ?: WindowSteps.lastIndex
    var from by remember { mutableStateOf(indexOf(initial.from).coerceAtMost(WindowSteps.lastIndex - 1)) }
    var to by remember { mutableStateOf(indexOf(initial.to).coerceAtLeast(from + 1)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StepRow(stringResource(R.string.analysis_settings_from), durationText(WindowSteps[from]), from > 0, from < to - 1, { from-- }, { from++ })
                StepRow(stringResource(R.string.analysis_settings_to), durationText(WindowSteps[to]), to > from + 1, to < WindowSteps.lastIndex, { to-- }, { to++ })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(TagWindow(WindowSteps[from], WindowSteps[to])) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun StepRow(label: String, value: String, canDecrease: Boolean, canIncrease: Boolean, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        FilledTonalIconButton(onClick = onDecrease, enabled = canDecrease) {
            Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.action_decrease))
        }
        Text(value, Modifier.widthIn(min = 72.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        FilledTonalIconButton(onClick = onIncrease, enabled = canIncrease) {
            Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.action_increase))
        }
    }
}

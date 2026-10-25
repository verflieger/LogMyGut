package at.mtr.logmygut.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import at.mtr.logmygut.LogMyGutApp
import at.mtr.logmygut.AppContainer
import at.mtr.logmygut.R
import at.mtr.logmygut.domain.Amount
import at.mtr.logmygut.domain.PortionSize
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The app's dependency container, for creating ViewModels in composables. */
@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as LogMyGutApp).container

/** Common frame for the entry editors: back arrow, title, optional delete, and a save button at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScaffold(
    title: String,
    onBack: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    onDelete: (() -> Unit)?,
    menu: (@Composable () -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    // Guards against double taps saving an entry twice; the screen closes after saving.
    var saving by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                    menu?.invoke()
                },
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    saving = true
                    onSave()
                },
                enabled = saveEnabled && !saving,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text(stringResource(R.string.action_save)) }
        },
    ) { padding ->
        // Edge-to-edge: the keyboard doesn't resize the window, it covers the save button. The content ends
        // above whichever is higher, so the field being typed in and items just added stay visible.
        val direction = LocalLayoutDirection.current
        val keyboard = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        content(
            Modifier.padding(
                start = padding.calculateStartPadding(direction),
                top = padding.calculateTopPadding(),
                end = padding.calculateEndPadding(direction),
                bottom = maxOf(padding.calculateBottomPadding(), keyboard),
            ),
        )
    }
}

/** Shows a timestamp and lets the user pick a date, then a time. Future dates can't be selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeField(value: Instant, onChange: (Instant) -> Unit, isError: Boolean, modifier: Modifier = Modifier) {
    val zone = ZoneId.systemDefault()
    val current = value.atZone(zone)
    var showDate by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }

    Column(modifier) {
        Text(stringResource(R.string.field_time), style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Schedule, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(ShortDateTimeFormat.format(current))
        }
        if (isError) {
            Text(stringResource(R.string.error_future_time), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showDate) {
        // DatePicker works in UTC midnight millis for the selected calendar date.
        val today = LocalDate.now(zone)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = current.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate() <= today
                override fun isSelectableYear(year: Int) = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickedDate = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    showDate = false
                }) { Text(stringResource(R.string.action_next)) }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = state) }
    }

    pickedDate?.let { date ->
        val state = rememberTimePickerState(initialHour = current.hour, initialMinute = current.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickedDate = null },
            confirmButton = {
                TextButton(onClick = {
                    onChange(date.atTime(LocalTime.of(state.hour, state.minute)).atZone(zone).toInstant())
                    pickedDate = null
                }) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = { TextButton(onClick = { pickedDate = null }) { Text(stringResource(R.string.action_cancel)) } },
            text = { TimePicker(state = state) },
        )
    }
}

/**
 * Count stepper or S/M/L segmented control, depending on the amount type.
 * [portionGrams] (M portion or one piece) adds gram hints: "S · 15 g", "2 Stück · ≈ 120 g"; for a [drink]
 * they are millilitres: "1 Tasse · ≈ 200 ml".
 */
@Composable
fun AmountSelector(
    amount: Amount,
    unitLabel: String?,
    portionGrams: Double?,
    onChange: (Amount) -> Unit,
    modifier: Modifier = Modifier,
    drink: Boolean = false,
) {
    when (amount) {
        is Amount.Count -> Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = { onChange(Amount.Count(amount.n - 1)) }, enabled = amount.n > 1) {
                Icon(Icons.Outlined.Remove, contentDescription = null)
            }
            Text(
                countLabel(amount.n, unitLabel) + (portionGrams?.let { " · ≈ " + portionLabel(amount.grams(it), drink) } ?: ""),
                style = MaterialTheme.typography.bodyLarge,
            )
            FilledTonalIconButton(onClick = { onChange(Amount.Count(amount.n + 1)) }) {
                Icon(Icons.Outlined.Add, contentDescription = null)
            }
        }
        // Full width, no check icon: the fill marks the selection, and "M · 150 g" needs the room.
        is Amount.Size -> SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
            PortionSize.entries.forEachIndexed { index, size ->
                SegmentedButton(
                    selected = amount.size == size,
                    onClick = { onChange(Amount.Size(size)) },
                    shape = SegmentedButtonDefaults.itemShape(index, PortionSize.entries.size),
                    icon = {},
                ) {
                    Text(
                        size.name + (portionGrams?.let { " · " + portionLabel(Amount.Size(size).grams(it), drink) } ?: ""),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** Single-choice segmented control over [options]; [selected] null means nothing chosen yet. */
@Composable
fun <T : Any> SegmentedChoice(options: List<T>, selected: T?, label: @Composable (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label(option)) }
        }
    }
}

/** Wraps a loaded value so a StateFlow can tell "loaded, but empty" from "still loading" (null). */
data class Loaded<T>(val value: T)

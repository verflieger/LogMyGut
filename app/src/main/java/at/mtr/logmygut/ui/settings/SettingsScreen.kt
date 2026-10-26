package at.mtr.logmygut.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.mtr.logmygut.R
import at.mtr.logmygut.data.ReminderSettings
import at.mtr.logmygut.data.SettingsRepository
import at.mtr.logmygut.ui.common.TimeFormat
import at.mtr.logmygut.ui.info.InfoPage
import at.mtr.logmygut.ui.common.appContainer
import java.time.LocalTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {
    val reminder: StateFlow<ReminderSettings?> = settings.reminder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val expertMode: StateFlow<Boolean> = settings.expertMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setReminderEnabled(enabled: Boolean) { viewModelScope.launch { settings.setReminderEnabled(enabled) } }
    fun setReminderTime(time: LocalTime) { viewModelScope.launch { settings.setReminderTime(time) } }
    fun setExpertMode(enabled: Boolean) { viewModelScope.launch { settings.setExpertMode(enabled) } }
}

/**
 * Einstellungen tab (SPEC 2): reminder, food library, analysis parameters, export, backup, the info pages and the
 * expert mode with its screens.
 */
@Composable
fun SettingsScreen(
    onOpenLibrary: () -> Unit,
    onOpenAnalysisSettings: () -> Unit,
    onOpenInfo: (InfoPage) -> Unit,
    onOpenDetached: () -> Unit,
    onOpenSeedChanges: () -> Unit,
) {
    val container = appContainer()
    val vm = viewModel { SettingsViewModel(container.settings) }
    val reminder by vm.reminder.collectAsStateWithLifecycle()
    val expertMode by vm.expertMode.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionTitle(stringResource(R.string.settings_reminder_section))
        reminder?.let { ReminderSettingsRows(it, vm::setReminderEnabled, vm::setReminderTime) }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionTitle(stringResource(R.string.settings_food_section))
        ListItem(
            modifier = Modifier.clickable(onClick = onOpenLibrary),
            headlineContent = { Text(stringResource(R.string.library_title)) },
            supportingContent = { Text(stringResource(R.string.settings_library_hint)) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionTitle(stringResource(R.string.settings_analysis_section))
        ListItem(
            modifier = Modifier.clickable(onClick = onOpenAnalysisSettings),
            headlineContent = { Text(stringResource(R.string.analysis_settings_title)) },
            supportingContent = { Text(stringResource(R.string.settings_analysis_hint)) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionTitle(stringResource(R.string.settings_export_section))
        ExportSection()
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionTitle(stringResource(R.string.settings_backup_section))
        BackupSection()
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionTitle(stringResource(R.string.settings_info_section))
        InfoPage.entries.forEach { page ->
            ListItem(
                modifier = Modifier.clickable { onOpenInfo(page) },
                headlineContent = { Text(stringResource(page.title)) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionTitle(stringResource(R.string.settings_expert_section))
        ListItem(
            modifier = Modifier.clickable { vm.setExpertMode(!expertMode) },
            headlineContent = { Text(stringResource(R.string.settings_expert_mode)) },
            supportingContent = { Text(stringResource(R.string.settings_expert_mode_hint)) },
            trailingContent = { Switch(checked = expertMode, onCheckedChange = vm::setExpertMode) },
        )
        if (expertMode) {
            ListItem(
                modifier = Modifier.clickable(onClick = onOpenDetached),
                headlineContent = { Text(stringResource(R.string.expert_detached_title)) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onOpenSeedChanges),
                headlineContent = { Text(stringResource(R.string.expert_changes_title)) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderSettingsRows(reminder: ReminderSettings, onEnabled: (Boolean) -> Unit, onTime: (LocalTime) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(notificationsGranted(context)) }
    var denied by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    // The user may change the permission in the system settings and come back.
    LifecycleResumeEffect(Unit) {
        granted = notificationsGranted(context)
        onPauseOrDispose { }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }

    ListItem(
        modifier = Modifier.clickable { onEnabled(!reminder.enabled) },
        headlineContent = { Text(stringResource(R.string.settings_reminder)) },
        supportingContent = { Text(stringResource(R.string.settings_reminder_hint)) },
        trailingContent = { Switch(checked = reminder.enabled, onCheckedChange = onEnabled) },
    )
    if (reminder.enabled) {
        ListItem(
            modifier = Modifier.clickable { pickingTime = true },
            headlineContent = { Text(stringResource(R.string.settings_reminder_time)) },
            trailingContent = { Text(TimeFormat.format(reminder.time), style = MaterialTheme.typography.titleMedium) },
        )
        if (!granted) {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.settings_notifications_off), color = MaterialTheme.colorScheme.error)
                },
                trailingContent = {
                    // After a denial Android may not show the dialog again; the system settings always work.
                    TextButton(onClick = {
                        if (denied) context.startActivity(appNotificationSettings(context))
                        else request.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }) { Text(stringResource(R.string.settings_notifications_allow)) }
                },
            )
        }
    }

    if (pickingTime) {
        val state = rememberTimePickerState(initialHour = reminder.time.hour, initialMinute = reminder.time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = {
                TextButton(onClick = {
                    onTime(LocalTime.of(state.hour, state.minute))
                    pickingTime = false
                }) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text(stringResource(R.string.action_cancel)) } },
            text = { TimePicker(state = state) },
        )
    }
}

/**
 * Asks for the notification permission once, on the first start with the reminder switched on
 * (it's on by default). Later the settings screen offers it again.
 */
@Composable
fun NotificationPermissionOnFirstStart() {
    val context = LocalContext.current
    val settings = appContainer().settings
    val scope = rememberCoroutineScope()
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val asked = settings.notificationPermissionAsked.first()
        if (!asked && settings.reminder.first().enabled && !notificationsGranted(context)) {
            scope.launch { settings.markNotificationPermissionAsked() }
            request.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private fun notificationsGranted(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun appNotificationSettings(context: Context) =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

package at.mtr.logmygut.ui.settings

import android.content.ContentResolver
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.mtr.logmygut.R
import at.mtr.logmygut.data.SettingsRepository
import at.mtr.logmygut.data.backup.BackupException
import at.mtr.logmygut.data.backup.BackupFile
import at.mtr.logmygut.data.backup.BackupRepository
import at.mtr.logmygut.ui.common.Loaded
import at.mtr.logmygut.ui.common.LongDayFormat
import at.mtr.logmygut.ui.common.appContainer
import at.mtr.logmygut.ui.common.local
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Days after which the settings remind the user to make a new backup (SPEC 7). */
private const val BackupReminderDays = 30

sealed interface BackupState {
    data object Idle : BackupState
    data object Working : BackupState
    data object Exported : BackupState
    /** A valid backup was read and waits for the user's confirmation. */
    data class Confirm(val backup: BackupFile) : BackupState
    data object Imported : BackupState
    data class Failed(val reason: BackupException.Reason?, val detail: String) : BackupState
}

class BackupViewModel(
    private val backup: BackupRepository,
    private val settings: SettingsRepository,
    private val resolver: ContentResolver,
) : ViewModel() {
    val lastExport: StateFlow<Loaded<LocalDate?>?> = settings.lastExport.map { Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    fun export(uri: Uri) = launchTask {
        val text = backup.export()
        withContext(Dispatchers.IO) {
            (resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open $uri")).bufferedWriter().use { it.write(text) }
        }
        settings.setLastExport(LocalDate.now())
        BackupState.Exported
    }

    fun read(uri: Uri) = launchTask {
        val text = withContext(Dispatchers.IO) {
            (resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")).bufferedReader().use { it.readText() }
        }
        BackupState.Confirm(backup.read(text))
    }

    fun confirmImport() {
        val pending = (_state.value as? BackupState.Confirm)?.backup ?: return
        launchTask {
            backup.restore(pending)
            BackupState.Imported
        }
    }

    fun dismiss() { _state.value = BackupState.Idle }

    private fun launchTask(block: suspend () -> BackupState) {
        _state.value = BackupState.Working
        viewModelScope.launch {
            _state.value = try {
                block()
            } catch (e: BackupException) {
                BackupState.Failed(e.reason, e.message.orEmpty())
            } catch (e: IOException) {
                BackupState.Failed(null, e.message.orEmpty())
            }
        }
    }
}

/** Datensicherung (SPEC 7): export to a file, import from one, and when the last export was. */
@Composable
fun BackupSection() {
    val container = appContainer()
    val context = LocalContext.current
    val vm = viewModel { BackupViewModel(container.backupRepository, container.settings, context.applicationContext.contentResolver) }
    val loadedLastExport by vm.lastExport.collectAsStateWithLifecycle()
    val lastExport = loadedLastExport?.value
    val state by vm.state.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(vm::export)
    }
    // File managers label .json files inconsistently, so plain text and generic binaries are offered too.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::read) }

    val overdue = loadedLastExport != null && lastExport?.let { ChronoUnit.DAYS.between(it, LocalDate.now()) > BackupReminderDays } ?: true
    ListItem(
        modifier = Modifier.clickable(enabled = state != BackupState.Working) { exportLauncher.launch("logmygut-backup-${LocalDate.now()}.json") },
        headlineContent = { Text(stringResource(R.string.backup_export)) },
        supportingContent = {
            Column {
                if (loadedLastExport != null) {
                    Text(lastExport?.let { stringResource(R.string.backup_last_export, LongDayFormat.format(it)) } ?: stringResource(R.string.backup_never))
                }
                if (overdue) Text(stringResource(R.string.backup_overdue, BackupReminderDays), color = MaterialTheme.colorScheme.error)
            }
        },
    )
    ListItem(
        modifier = Modifier.clickable(enabled = state != BackupState.Working) {
            importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*"))
        },
        headlineContent = { Text(stringResource(R.string.backup_import)) },
        supportingContent = { Text(stringResource(R.string.backup_import_hint)) },
    )
    if (state == BackupState.Working) LinearProgressIndicator(Modifier.fillMaxWidth())

    val exportedMessage = stringResource(R.string.backup_exported)
    LaunchedEffect(state) {
        if (state == BackupState.Exported) {
            Toast.makeText(context, exportedMessage, Toast.LENGTH_SHORT).show()
            vm.dismiss()
        }
    }
    when (val s = state) {
        is BackupState.Confirm -> ConfirmImportDialog(s.backup, onConfirm = vm::confirmImport, onDismiss = vm::dismiss)
        BackupState.Imported -> MessageDialog(stringResource(R.string.backup_imported), onDismiss = vm::dismiss)
        is BackupState.Failed -> MessageDialog(
            stringResource(
                when (s.reason) {
                    BackupException.Reason.NOT_A_BACKUP -> R.string.backup_error_not_a_backup
                    BackupException.Reason.TOO_NEW -> R.string.backup_error_too_new
                    BackupException.Reason.TOO_OLD -> R.string.backup_error_too_old
                    BackupException.Reason.INVALID -> R.string.backup_error_invalid
                    null -> R.string.backup_error_io
                },
            ),
            detail = s.detail,
            onDismiss = vm::dismiss,
        )
        else -> Unit
    }
}

@Composable
private fun ConfirmImportDialog(backup: BackupFile, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val date = runCatching { LongDayFormat.format(Instant.parse(backup.exportedAt).local()) }.getOrDefault(backup.exportedAt)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_confirm_title)) },
        text = {
            Text(
                stringResource(
                    R.string.backup_confirm_text,
                    date,
                    backup.meals.size,
                    backup.bowelMovements.size,
                    backup.symptoms.map { it.timestamp }.distinct().size,
                    backup.medication.size,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.backup_confirm_action), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun MessageDialog(message: String, onDismiss: () -> Unit, detail: String? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column {
                Text(message)
                if (!detail.isNullOrBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
    )
}

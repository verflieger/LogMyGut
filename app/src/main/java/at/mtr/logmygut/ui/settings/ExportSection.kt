package at.mtr.logmygut.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.mtr.logmygut.R
import at.mtr.logmygut.data.SettingsRepository
import at.mtr.logmygut.data.export.ExportRepository
import at.mtr.logmygut.data.export.toCsv
import at.mtr.logmygut.ui.common.appContainer
import at.mtr.logmygut.ui.export.reportBlocks
import at.mtr.logmygut.ui.export.writeReportPdf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The two exports of SPEC 7.1. */
enum class ExportKind(val mimeType: String, val extension: String) {
    REPORT("application/pdf", "pdf"),
    CSV("text/csv", "csv");

    fun fileName(today: LocalDate): String = when (this) {
        REPORT -> "logmygut-report-$today.$extension"
        CSV -> "logmygut-entries-$today.$extension"
    }
}

/** Periods offered for the report, in days; null = all entries. */
val ReportPeriods: List<Int?> = listOf(14, 30, 90, null)

sealed interface ExportState {
    data object Idle : ExportState
    data object Working : ExportState
    data object Saved : ExportState
    /** Nothing to export in the chosen period. */
    data object Empty : ExportState
    data object Failed : ExportState
    /** A file in the app's cache, ready to hand to the share sheet. */
    data class Share(val uri: Uri, val kind: ExportKind) : ExportState
}

/** Folder in the cache for files handed to other apps; emptied before each new export (see res/xml/export_paths.xml). */
private const val ShareFolder = "exports"

class ExportViewModel(
    private val context: Context,
    private val exports: ExportRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    fun save(kind: ExportKind, periodDays: Int?, uri: Uri) = launchTask {
        val bytes = render(kind, periodDays) ?: return@launchTask ExportState.Empty
        withContext(Dispatchers.IO) {
            (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open $uri")).use { it.write(bytes) }
        }
        ExportState.Saved
    }

    fun share(kind: ExportKind, periodDays: Int?) = launchTask {
        val bytes = render(kind, periodDays) ?: return@launchTask ExportState.Empty
        val file = withContext(Dispatchers.IO) {
            // Only the latest export stays in the cache: it holds health data.
            val folder = File(context.cacheDir, ShareFolder).apply { deleteRecursively(); mkdirs() }
            File(folder, kind.fileName(LocalDate.now())).apply { writeBytes(bytes) }
        }
        ExportState.Share(FileProvider.getUriForFile(context, "${context.packageName}.exports", file), kind)
    }

    fun dismiss() { _state.value = ExportState.Idle }

    /** The file's content, or null when there is nothing to export. */
    private suspend fun render(kind: ExportKind, periodDays: Int?): ByteArray? {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        return when (kind) {
            ExportKind.CSV -> exports.load().takeUnless { it.isEmpty }?.toCsv()?.toByteArray()
            ExportKind.REPORT -> {
                val from = periodDays?.let { today.minusDays(it - 1L) } ?: exports.load().firstDay(zone) ?: today
                val report = exports.report(from, today, settings.analysis.first(), zone)
                if (report.data.isEmpty) return null
                withContext(Dispatchers.Default) {
                    val resources = context.resources
                    val blocks = resources.reportBlocks(report, zone, today)
                    ByteArrayOutputStream().also { out ->
                        writeReportPdf(blocks, { page -> resources.getString(R.string.report_footer, page) }, out)
                    }.toByteArray()
                }
            }
        }
    }

    private fun launchTask(block: suspend () -> ExportState) {
        _state.value = ExportState.Working
        viewModelScope.launch {
            _state.value = try {
                block()
            } catch (e: IOException) {
                ExportState.Failed
            }
        }
    }
}

/** Export section of the settings (SPEC 7.1): a PDF report for a period and a CSV of all entries. */
@Composable
fun ExportSection() {
    val container = appContainer()
    val context = LocalContext.current
    val vm = viewModel { ExportViewModel(context.applicationContext, container.exportRepository, container.settings) }
    val state by vm.state.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<ExportKind?>(null) }
    var period by rememberSaveable { mutableStateOf<Int?>(30) }

    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportKind.REPORT.mimeType)) { uri ->
        uri?.let { vm.save(ExportKind.REPORT, period, it) }
    }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportKind.CSV.mimeType)) { uri ->
        uri?.let { vm.save(ExportKind.CSV, null, it) }
    }

    ListItem(
        modifier = Modifier.clickable(enabled = state != ExportState.Working) { dialog = ExportKind.REPORT },
        headlineContent = { Text(stringResource(R.string.export_report)) },
        supportingContent = { Text(stringResource(R.string.export_report_hint)) },
    )
    ListItem(
        modifier = Modifier.clickable(enabled = state != ExportState.Working) { dialog = ExportKind.CSV },
        headlineContent = { Text(stringResource(R.string.export_csv)) },
        supportingContent = { Text(stringResource(R.string.export_csv_hint)) },
    )
    if (state == ExportState.Working) LinearProgressIndicator(Modifier.fillMaxWidth())

    dialog?.let { kind ->
        ExportDialog(
            kind = kind,
            period = period,
            onPeriod = { period = it },
            onShare = {
                dialog = null
                vm.share(kind, period.takeIf { kind == ExportKind.REPORT })
            },
            onSave = {
                dialog = null
                val name = kind.fileName(LocalDate.now())
                if (kind == ExportKind.REPORT) savePdf.launch(name) else saveCsv.launch(name)
            },
            onDismiss = { dialog = null },
        )
    }

    val messages = mapOf(
        ExportState.Saved to stringResource(R.string.export_saved),
        ExportState.Empty to stringResource(R.string.export_empty),
        ExportState.Failed to stringResource(R.string.export_failed),
    )
    val shareTitle = stringResource(R.string.export_share_title)
    LaunchedEffect(state) {
        when (val s = state) {
            is ExportState.Share -> {
                val send = Intent(Intent.ACTION_SEND)
                    .setType(s.kind.mimeType)
                    .putExtra(Intent.EXTRA_STREAM, s.uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // The clip data lets the share sheet preview the file and passes the read permission on.
                send.clipData = ClipData.newRawUri(null, s.uri)
                context.startActivity(Intent.createChooser(send, shareTitle))
                vm.dismiss()
            }
            ExportState.Saved, ExportState.Empty, ExportState.Failed -> {
                Toast.makeText(context, messages.getValue(s), Toast.LENGTH_SHORT).show()
                vm.dismiss()
            }
            else -> Unit
        }
    }
}

@Composable
private fun ExportDialog(
    kind: ExportKind,
    period: Int?,
    onPeriod: (Int?) -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (kind == ExportKind.REPORT) R.string.export_report else R.string.export_csv)) },
        text = {
            Column {
                if (kind == ExportKind.REPORT) {
                    ReportPeriods.forEach { days ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = period == days, onClick = { onPeriod(days) }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = period == days, onClick = null)
                            Text(
                                days?.let { stringResource(R.string.export_period_days, it) } ?: stringResource(R.string.export_period_all),
                                Modifier.padding(start = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.export_privacy),
                    Modifier.padding(top = if (kind == ExportKind.REPORT) 12.dp else 0.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onSave) { Text(stringResource(R.string.export_save)) }
                TextButton(onClick = onShare) { Text(stringResource(R.string.export_share)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

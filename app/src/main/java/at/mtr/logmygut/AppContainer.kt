package at.mtr.logmygut

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import at.mtr.logmygut.data.AnalysisRepository
import at.mtr.logmygut.data.FoodRepository
import at.mtr.logmygut.data.JournalRepository
import at.mtr.logmygut.data.SettingsRepository
import at.mtr.logmygut.data.backup.BackupRepository
import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.data.seed.SeedFile
import at.mtr.logmygut.data.seed.SeedImporter
import at.mtr.logmygut.domain.TagSuggester
import at.mtr.logmygut.reminder.Reminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Creates and owns long-lived objects (database, repositories, settings). */
class AppContainer(private val context: Context) {
    /** Lives as long as the app process; for work that must not be cancelled with a screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: LogMyGutDatabase by lazy { LogMyGutDatabase.create(context) }

    private val tagSuggester: TagSuggester by lazy { TagSuggester.fromJson(readAsset("keyword_rules.de.json")) }

    val foodRepository: FoodRepository by lazy {
        FoodRepository(database, tagSuggester, loadSeed = { SeedFile.parse(readAsset("foods.de.json")) })
    }

    val journalRepository: JournalRepository by lazy { JournalRepository(database) }

    val analysisRepository: AnalysisRepository by lazy { AnalysisRepository(database, foodRepository) }

    val settings: SettingsRepository by lazy { SettingsRepository.create(context) }

    val backupRepository: BackupRepository by lazy {
        BackupRepository(database, settings, context.packageManager.getPackageInfo(context.packageName, 0).versionName)
    }

    /**
     * Keeps the daily reminder scheduled for the life of the process. On start only a missing schedule is
     * created (KEEP), so a process started by the reminder itself doesn't cancel it; later changes replace it.
     */
    fun keepReminderScheduled() {
        appScope.launch {
            var first = true
            settings.reminder.distinctUntilChanged().collect { reminder ->
                Reminder.schedule(context, reminder, if (first) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE)
                first = false
            }
        }
    }

    /** Syncs the built-in foods with the seed file (see [SeedImporter]). Cheap when nothing changed. */
    fun importSeedInBackground() {
        appScope.launch(Dispatchers.IO) {
            val result = SeedImporter(database).import(SeedFile.parse(readAsset("foods.de.json")))
            if (result.inserted + result.updated > 0) {
                Log.i("LogMyGut", "Seed import: added ${result.inserted}, updated ${result.updated} foods")
            }
        }
    }

    private fun readAsset(name: String): String = context.assets.open(name).bufferedReader().use { it.readText() }
}

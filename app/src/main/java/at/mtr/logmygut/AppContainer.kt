package at.mtr.logmygut

import android.content.Context
import android.util.Log
import at.mtr.logmygut.data.FoodRepository
import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.data.seed.SeedFile
import at.mtr.logmygut.data.seed.SeedImporter
import at.mtr.logmygut.domain.TagSuggester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Creates and owns long-lived objects (database, repositories, settings). */
class AppContainer(private val context: Context) {
    /** Lives as long as the app process; for work that must not be cancelled with a screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: LogMyGutDatabase by lazy { LogMyGutDatabase.create(context) }

    private val tagSuggester: TagSuggester by lazy { TagSuggester.fromJson(readAsset("keyword_rules.de.json")) }

    val foodRepository: FoodRepository by lazy { FoodRepository(database, tagSuggester) }

    /** Adds any built-in foods missing from the database. Cheap when there is nothing to add. */
    fun importSeedInBackground() {
        appScope.launch(Dispatchers.IO) {
            val inserted = SeedImporter(database).import(SeedFile.parse(readAsset("foods.de.json")))
            if (inserted > 0) Log.i("LogMyGut", "Seed import: added $inserted foods")
        }
    }

    private fun readAsset(name: String): String = context.assets.open(name).bufferedReader().use { it.readText() }
}

package at.mtr.logmygut.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.data.seed.SeedFile
import at.mtr.logmygut.data.seed.SeedImporter
import at.mtr.logmygut.domain.TagSuggester
import java.io.File

/** Shared fixtures for Robolectric database tests. */
object TestDatabase {
    val seed: SeedFile by lazy { SeedFile.parse(File("src/main/assets/foods.json").readText()) }
    val tagSuggester: TagSuggester by lazy { TagSuggester.fromJson(File("src/main/assets/keyword_rules.de.json").readText(), File("src/main/assets/keyword_rules.en.json").readText()) }

    /**
     * In-memory database whose queries run on the calling thread, so ViewModel coroutines
     * on a test dispatcher complete deterministically.
     */
    fun create(): LogMyGutDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LogMyGutDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()

    suspend fun createSeeded(): LogMyGutDatabase = create().also { SeedImporter(it).import(seed) }
}

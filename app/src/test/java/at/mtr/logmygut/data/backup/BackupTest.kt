package at.mtr.logmygut.data.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import at.mtr.logmygut.data.FoodRepository
import at.mtr.logmygut.data.IngredientDraft
import at.mtr.logmygut.data.JournalRepository
import at.mtr.logmygut.data.SettingsRepository
import at.mtr.logmygut.data.TestDatabase
import at.mtr.logmygut.data.db.BowelMovementEntity
import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.data.db.MealEntity
import at.mtr.logmygut.data.db.MealItemEntity
import at.mtr.logmygut.data.db.MedicationIntakeEntity
import at.mtr.logmygut.data.db.SymptomEntryEntity
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.IngredientTag
import at.mtr.logmygut.domain.MedEffect
import at.mtr.logmygut.domain.PainChange
import at.mtr.logmygut.domain.PortionSize
import at.mtr.logmygut.domain.SymptomType
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.domain.TagWindow
import at.mtr.logmygut.domain.Threshold
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupTest {
    @get:Rule val folder = TemporaryFolder()

    private val zone = "Europe/Vienna"
    private val now: Instant = Instant.parse("2026-10-25T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val databases = mutableListOf<LogMyGutDatabase>()

    @After fun tearDown() {
        databases.forEach { it.close() }
        scope.cancel()
    }

    private fun settings(name: String) = SettingsRepository(
        PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("$name.preferences_pb") },
    )

    private suspend fun seededDb() = TestDatabase.createSeeded().also { databases += it }
    private fun emptyDb() = TestDatabase.create().also { databases += it }

    private fun at(hours: Long): Instant = now - Duration.ofHours(hours)

    /** A database with one entry of every kind, edited library foods and changed settings. */
    private suspend fun fullSource(): Pair<LogMyGutDatabase, SettingsRepository> {
        val db = seededDb()
        val foods = FoodRepository(db, TestDatabase.tagSuggester)
        val journal = JournalRepository(db)

        val vinegar = foods.search("Essig").first { it.kind == FoodKind.INGREDIENT }
        val ownVinegar = foods.saveIngredient(
            IngredientDraft(
                id = 0, name = "Hausessig", aliases = listOf("Mein Essig"), unit = FoodUnit.SIZE, unitLabel = null, portionGrams = 10.0,
                note = "selbst gemacht", parentId = vinegar.id,
                tags = listOf(IngredientTag(Tag.FRUCTOSE, Threshold.MinSize(PortionSize.L))),
            ),
        )
        val pizza = foods.search("Pizza", FoodKind.DISH).first()
        foods.setDishIngredients(pizza.id, foods.getDishIngredientIds(pizza.id) + ownVinegar)
        foods.setHidden(foods.search("Semmel").first().id, true)

        val semmel = foods.search("Semmel").first()
        journal.saveMeal(
            MealEntity(timestamp = at(5), zoneId = zone, eatenOut = true, note = "Kantine"),
            listOf(MealItemEntity(mealId = 0, foodId = pizza.id, size = PortionSize.L), MealItemEntity(mealId = 0, foodId = semmel.id, count = 2)),
            mapOf(0 to listOf(ownVinegar)),
        )
        journal.saveBowelMovement(BowelMovementEntity(timestamp = at(4), zoneId = zone, bristol = 6, urgent = true, painBefore = true, painAfter = PainChange.BETTER))
        journal.saveSymptoms(
            null,
            listOf(
                SymptomEntryEntity(timestamp = at(3), zoneId = zone, type = SymptomType.BLOATING, severity = 7, note = "nach dem Essen"),
                SymptomEntryEntity(timestamp = at(3), zoneId = zone, type = SymptomType.PAIN, severity = 0),
            ),
        )
        journal.saveMedication(MedicationIntakeEntity(timestamp = at(2), zoneId = zone, name = "Laktase", effect = MedEffect.ENZYME))
        journal.saveSleep(LocalDate.of(2026, 10, 24), 4)
        journal.saveStress(LocalDate.of(2026, 10, 24), 2)
        journal.saveSleep(LocalDate.of(2026, 10, 25), 3) // stress not answered yet

        val settings = settings("source").apply {
            setReminderEnabled(false)
            setReminderTime(LocalTime.of(20, 15))
            setSymptomThreshold(5)
            setMinExposures(8)
            setTagWindow(Tag.GLUTEN, TagWindow(Duration.ofHours(1), Duration.ofHours(36)))
        }
        return db to settings
    }

    @Test fun exportThenImportReproducesEverything() = runTest {
        val (sourceDb, sourceSettings) = fullSource()
        val exported = BackupRepository(sourceDb, sourceSettings).export(now)

        val targetSettings = settings("target")
        val target = BackupRepository(emptyDb(), targetSettings)
        target.restore(target.read(exported))

        assertEquals(exported, target.export(now))
        assertEquals(LocalTime.of(20, 15), targetSettings.reminder.first().time)
        assertEquals(5, targetSettings.analysis.first().symptomThreshold)
    }

    @Test fun restoredFoodsAreSearchable() = runTest {
        val exported = BackupRepository(seededDb(), settings("source")).export(now)
        val targetDb = emptyDb()
        val target = BackupRepository(targetDb, settings("target"))
        target.restore(target.read(exported))

        // The backup has no search column; the restore must build it.
        assertTrue(FoodRepository(targetDb, TestDatabase.tagSuggester).search("apfel").any { it.seedKey == "Apfel" })
    }

    @Test fun importReplacesExistingData() = runTest {
        val (sourceDb, sourceSettings) = fullSource()
        val exported = BackupRepository(sourceDb, sourceSettings).export(now)

        val targetDb = seededDb()
        val journal = JournalRepository(targetDb)
        journal.saveBowelMovement(BowelMovementEntity(timestamp = at(100), zoneId = zone, bristol = 1))
        val targetSettings = settings("target").apply { setTagWindow(Tag.LACTOSE, TagWindow(Duration.ZERO, Duration.ofHours(1))) }

        val target = BackupRepository(targetDb, targetSettings)
        target.restore(target.read(exported))

        val bowel = targetDb.backupDao().getBowelMovements()
        assertEquals(listOf(6), bowel.map { it.bristol })
        // A setting the backup doesn't contain goes back to its default.
        assertEquals(Duration.ofMinutes(30), targetSettings.analysis.first().window(Tag.LACTOSE).from)
    }

    @Test fun aFailedImportLeavesTheDatabaseUnchanged() = runTest {
        val (db, settings) = fullSource()
        val repo = BackupRepository(db, settings)
        val before = repo.export(now)

        // Passes the JSON checks but breaks a foreign key, so the database insert fails midway.
        val broken = repo.read(before).let { it.copy(mealItems = it.mealItems.map { item -> item.copy(mealId = 999_999) }) }
        try {
            repo.restore(broken)
            fail("Expected the import to fail")
        } catch (e: BackupException) {
            assertEquals(BackupException.Reason.INVALID, e.reason)
        }
        assertEquals(before, repo.export(now))
    }

    @Test fun version1FilesGetDrinksFromTheirUnit() = runTest {
        val (db, settings) = fullSource()
        val current = parseBackup(BackupRepository(db, settings).export(now))
        val v1 = current.copy(version = 1, foods = current.foods.map { it.copy(drink = false) })

        val target = BackupRepository(emptyDb(), settings("target"))
        target.restore(target.read(BackupJson.encodeToString(BackupFile.serializer(), v1)))

        val drinks = target.read(target.export(now)).foods.filter { it.drink }.map { it.name }
        assertTrue("Kaffee" in drinks && "Bier" in drinks && "Wasser" in drinks)
        assertTrue("Semmel" !in drinks)
    }

    @Test fun rejectsFilesThatAreNotBackups() {
        assertReason(BackupException.Reason.NOT_A_BACKUP, "kein JSON")
        assertReason(BackupException.Reason.NOT_A_BACKUP, """{"version": 4, "ingredients": []}""") // a seed file
        assertReason(BackupException.Reason.TOO_NEW, """{"format": "logmygut-backup", "version": 99, "anything": "new"}""")
        assertReason(BackupException.Reason.INVALID, """{"format": "logmygut-backup", "version": 1}""")
    }

    @Test fun rejectsBrokenReferencesAndValues() = runTest {
        val (db, settings) = fullSource()
        val good = BackupRepository(db, settings).export(now)
        val backup = parseBackup(good)
        fun assertInvalid(changed: BackupFile) =
            assertReason(BackupException.Reason.INVALID, BackupJson.encodeToString(BackupFile.serializer(), changed))

        assertInvalid(backup.copy(mealItems = backup.mealItems.map { it.copy(foodId = 999_999) }))
        assertInvalid(backup.copy(bowelMovements = backup.bowelMovements.map { it.copy(bristol = 8) }))
        assertInvalid(backup.copy(bowelMovements = backup.bowelMovements.map { it.copy(painAfter = null) }))
        assertInvalid(backup.copy(foodTags = backup.foodTags + FoodTagJson(backup.foods.first().id, "sugar")))
        assertInvalid(backup.copy(checkIns = backup.checkIns.map { it.copy(stress = 0) }))
        assertInvalid(backup.copy(settings = backup.settings.copy(windows = mapOf("gluten" to "60-30"))))
        // Two foods that are each other's parent.
        val (a, b) = backup.foods.filter { it.kind == FoodKind.INGREDIENT }.take(2)
        assertInvalid(backup.copy(foods = backup.foods.map { if (it.id == a.id) it.copy(parentId = b.id) else if (it.id == b.id) it.copy(parentId = a.id) else it }))
    }

    private fun assertReason(reason: BackupException.Reason, text: String) {
        try {
            parseBackup(text)
            fail("Expected $reason for: ${text.take(80)}")
        } catch (e: BackupException) {
            assertEquals(e.message, reason, e.reason)
            assertTrue(e.message!!.isNotEmpty())
        }
    }
}

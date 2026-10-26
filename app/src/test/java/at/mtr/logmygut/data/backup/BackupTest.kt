package at.mtr.logmygut.data.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import at.mtr.logmygut.data.FoodRepository
import at.mtr.logmygut.data.IngredientDraft
import at.mtr.logmygut.data.JournalRepository
import at.mtr.logmygut.data.SettingsRepository
import at.mtr.logmygut.data.TestDatabase
import at.mtr.logmygut.data.db.BowelMovementEntity
import at.mtr.logmygut.data.db.FoodEntity
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
import at.mtr.logmygut.domain.TagSource
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

    /** Ids of an edited built-in that still follows the seed, a detached one, a user-created ingredient and a built-in dish. */
    private data class EditedFoods(val attached: Long, val detached: Long, val userCreated: Long, val dish: Long)

    /** A seeded database with an attached edited built-in (seed and user rows), a detached one and a user-created one. */
    private suspend fun editedSource(): Pair<LogMyGutDatabase, EditedFoods> {
        val db = seededDb()
        val foods = FoodRepository(db, TestDatabase.tagSuggester)
        fun draft(food: FoodEntity, parentId: Long?, tags: List<IngredientTag>) = IngredientDraft(
            id = food.id, name = food.name, aliases = food.aliases, unit = food.unit, unitLabel = food.unitLabel,
            portionGrams = food.portionGrams, note = food.note, parentId = parentId, tags = tags, drink = food.drink,
        )
        // Removing its own seed tag histamine, same parent: a seed row and a user removal for the same tag.
        val weichkaese = foods.search("Weichkäse").first { it.name == "Weichkäse" }
        foods.saveIngredient(draft(weichkaese, weichkaese.parentId, listOf(IngredientTag(Tag.FAT, Threshold.MinSize(PortionSize.L)))))
        // Another parent detaches a built-in.
        val child = db.foodDao().getAllFoods().first { it.builtIn && it.kind == FoodKind.INGREDIENT && it.parentId != null && it.id != weichkaese.id }
        foods.saveIngredient(draft(child, null, foods.getIngredientTags(child.id)))
        val own = foods.saveIngredient(
            IngredientDraft(
                id = 0, name = "Hausessig", aliases = emptyList(), unit = FoodUnit.SIZE, unitLabel = null, portionGrams = null,
                note = null, parentId = null, tags = listOf(IngredientTag(Tag.FRUCTOSE)),
            ),
        )
        val dish = foods.search("Pizza", FoodKind.DISH).first().id
        return db to EditedFoods(weichkaese.id, child.id, own, dish)
    }

    @Test fun roundTripKeepsRowSourcesAndDetachedFoods() = runTest {
        val (sourceDb, ids) = editedSource()
        val sourceTags = sourceDb.backupDao().getFoodTags()
        val attachedSources = sourceTags.filter { it.foodId == ids.attached }.map { it.source }.toSet()
        assertEquals(setOf(TagSource.SEED, TagSource.USER), attachedSources)
        assertEquals(setOf(TagSource.USER), sourceTags.filter { it.foodId == ids.detached }.map { it.source }.toSet())
        val foodIds = sourceDb.foodDao().getIngredientParents().map { it.id }
        val before = FoodRepository(sourceDb, TestDatabase.tagSuggester).getIngredientTags(foodIds).mapValues { it.value.toSet() }

        val exported = BackupRepository(sourceDb, settings("source")).export(now)
        val targetDb = emptyDb()
        val target = BackupRepository(targetDb, settings("target"))
        target.restore(target.read(exported))

        assertEquals(sourceTags, targetDb.backupDao().getFoodTags())
        val restored = targetDb.foodDao()
        assertEquals(false, restored.getFood(ids.attached)!!.tagsDetached)
        assertEquals(true, restored.getFood(ids.attached)!!.userModified)
        assertEquals(true, restored.getFood(ids.detached)!!.tagsDetached)
        assertEquals(before, FoodRepository(targetDb, TestDatabase.tagSuggester).getIngredientTags(foodIds).mapValues { it.value.toSet() })
        assertEquals(setOf(IngredientTag(Tag.FAT, Threshold.MinSize(PortionSize.L))), before[ids.attached])
        assertEquals(exported, target.export(now))
    }

    @Test fun rejectsSeedRowsAndDetachedFlagsWhereTheyCantBe() = runTest {
        val (db, ids) = editedSource()
        val backup = parseBackup(BackupRepository(db, settings("source")).export(now))
        fun assertInvalid(changed: BackupFile, problem: String) {
            try {
                parseBackup(BackupJson.encodeToString(BackupFile.serializer(), changed))
                fail("Expected INVALID: $problem")
            } catch (e: BackupException) {
                assertEquals(BackupException.Reason.INVALID, e.reason)
                assertTrue(e.message, e.message!!.contains(problem))
            }
        }
        fun seedRowOn(id: Long) = backup.copy(foodTags = backup.foodTags + FoodTagJson(id, Tag.CARBONATED.key, source = TagSource.SEED))
        fun detached(id: Long) = backup.copy(foods = backup.foods.map { if (it.id == id) it.copy(tagsDetached = true) else it })

        assertInvalid(seedRowOn(ids.userCreated), "seed row")
        assertInvalid(seedRowOn(ids.detached), "seed row")
        // Detaching an attached food without dropping its seed rows.
        assertInvalid(detached(ids.attached), "seed row")
        assertInvalid(detached(ids.userCreated), "detached")
        assertInvalid(detached(ids.dish), "detached")
        // Two rows of the same source for one tag.
        val row = backup.foodTags.first { it.foodId == ids.attached && it.source == TagSource.USER }
        assertInvalid(backup.copy(foodTags = backup.foodTags + row.copy(removed = !row.removed)), "duplicate")
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

    @Test fun filesBeforeVersion3AreTooOld() = runTest {
        val (db, settings) = fullSource()
        val repo = BackupRepository(db, settings)
        val current = repo.read(repo.export(now))
        for (version in listOf(1, 2)) {
            assertReason(BackupException.Reason.TOO_OLD, BackupJson.encodeToString(BackupFile.serializer(), current.copy(version = version)))
        }
        // Rejected by its version, before its old structure (no tag source) is looked at.
        assertReason(BackupException.Reason.TOO_OLD, """{"format": "logmygut-backup", "version": 2, "foodTags": [{"foodId": 1, "tag": "gluten"}]}""")
    }

    @Test fun rejectsFilesThatAreNotBackups() {
        assertReason(BackupException.Reason.NOT_A_BACKUP, "kein JSON")
        assertReason(BackupException.Reason.NOT_A_BACKUP, """{"version": 4, "ingredients": []}""") // a seed file
        assertReason(BackupException.Reason.TOO_NEW, """{"format": "logmygut-backup", "version": 99, "anything": "new"}""")
        assertReason(BackupException.Reason.INVALID, """{"format": "logmygut-backup", "version": 3}""")
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
        assertInvalid(backup.copy(foodTags = backup.foodTags + FoodTagJson(backup.foods.first().id, "sugar", source = TagSource.USER)))
        // A removal can't carry a threshold.
        val untagged = backup.foods.first { f -> f.kind == FoodKind.INGREDIENT && backup.foodTags.none { it.foodId == f.id } }
        assertInvalid(backup.copy(foodTags = backup.foodTags + FoodTagJson(untagged.id, Tag.GLUTEN.key, minCount = 1, removed = true, source = TagSource.USER)))
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

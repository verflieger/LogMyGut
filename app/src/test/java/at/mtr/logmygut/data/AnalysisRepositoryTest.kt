package at.mtr.logmygut.data

import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.data.db.MealEntity
import at.mtr.logmygut.data.db.MealItemEntity
import at.mtr.logmygut.data.db.MedicationIntakeEntity
import at.mtr.logmygut.data.db.SymptomEntryEntity
import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.IngredientTag
import at.mtr.logmygut.domain.MealOutcome
import at.mtr.logmygut.domain.MedEffect
import at.mtr.logmygut.domain.Outcome
import at.mtr.logmygut.domain.PortionSize
import at.mtr.logmygut.domain.SymptomType
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.domain.analyzeTriggers
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnalysisRepositoryTest {
    private val zone = ZoneId.of("Europe/Vienna")
    private val start = Instant.parse("2026-10-01T10:00:00Z")
    private lateinit var db: LogMyGutDatabase
    private lateinit var journal: JournalRepository
    private lateinit var foods: FoodRepository

    @Before fun setUp() = runTest {
        db = TestDatabase.createSeeded()
        journal = JournalRepository(db)
        foods = FoodRepository(db, TestDatabase.tagSuggester)
    }

    @After fun tearDown() = db.close()

    private fun at(hours: Long): Instant = start + Duration.ofHours(hours)

    private suspend fun meal(time: Instant, item: MealItemEntity, dishIngredients: List<Long>? = null): Long =
        journal.saveMeal(MealEntity(timestamp = time, zoneId = zone.id), listOf(item), dishIngredients?.let { mapOf(0 to it) }.orEmpty())

    @Test fun dishMealsUseTheirActualIngredientsAndInheritedTags() = runTest {
        val milk = foods.createIngredient("Testmilch", FoodUnit.SIZE, null, listOf(IngredientTag(Tag.LACTOSE)))
        val rice = foods.createIngredient("Testreis", FoodUnit.SIZE, null, emptyList())
        val dish = foods.createDish("Testauflauf", FoodUnit.SIZE, null, listOf(milk, rice))
        // Weißbrot has no tags of its own; it inherits gluten from Brot (seed data).
        val whiteBread = foods.search("Weißbrot").first()

        val withMilk = meal(at(0), MealItemEntity(mealId = 0, foodId = dish, size = PortionSize.M), listOf(milk, rice))
        val milkRemoved = meal(at(24), MealItemEntity(mealId = 0, foodId = dish, size = PortionSize.M), listOf(rice))
        val bread = meal(at(48), MealItemEntity(mealId = 0, foodId = whiteBread.id, count = 2))
        journal.saveSymptoms(null, listOf(SymptomEntryEntity(timestamp = at(1), zoneId = zone.id, type = SymptomType.BLOATING, severity = 6)))

        val data = AnalysisRepository(db, foods).load()
        val results = analyzeTriggers(data.meals, data.tagsOf, data.symptoms, data.bowelMovements, data.medication)
        val bloating = Outcome.Symptom(SymptomType.BLOATING)

        val lactose = results.single { it.tag == Tag.LACTOSE && it.outcome == bloating }
        assertEquals(listOf(MealOutcome(withMilk, positive = true)), lactose.exposures)
        assertEquals(2, lactose.control.n)
        assertEquals(listOf(bread), results.single { it.tag == Tag.GLUTEN && it.outcome == bloating }.exposures.map { it.mealId })
        assertEquals(listOf("Testauflauf"), data.mealSummaries.getValue(milkRemoved).itemNames)
    }

    @Test fun medicationEffectsReachTheAnalysis() = runTest {
        val milk = foods.createIngredient("Testmilch", FoodUnit.SIZE, null, listOf(IngredientTag(Tag.LACTOSE)))
        val mealId = meal(at(0), MealItemEntity(mealId = 0, foodId = milk, size = PortionSize.M))
        journal.saveMedication(MedicationIntakeEntity(timestamp = at(0), zoneId = zone.id, name = "Laktase", effect = MedEffect.ENZYME))

        val data = AnalysisRepository(db, foods).load()
        val lactose = analyzeTriggers(data.meals, data.tagsOf, data.symptoms, data.bowelMovements, data.medication)
            .first { it.tag == Tag.LACTOSE }
        assertEquals(listOf(mealId), lactose.withEnzyme.map { it.mealId })
        assertEquals(0, lactose.exposures.size)
    }
}

package at.mtr.logmygut.data

import at.mtr.logmygut.data.db.BowelMovementEntity
import at.mtr.logmygut.data.db.DailyCheckInEntity
import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.data.db.MealEntity
import at.mtr.logmygut.data.db.MealItemEntity
import at.mtr.logmygut.data.db.MedicationIntakeEntity
import at.mtr.logmygut.data.db.SymptomEntryEntity
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.MedEffect
import at.mtr.logmygut.domain.PortionSize
import at.mtr.logmygut.domain.SymptomType
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JournalRepositoryTest {
    private val zone = ZoneId.of("Europe/Vienna")
    private val day = LocalDate.of(2026, 10, 24)
    private lateinit var db: LogMyGutDatabase
    private lateinit var journal: JournalRepository
    private lateinit var foods: FoodRepository

    @Before fun setUp() = runTest {
        db = TestDatabase.createSeeded()
        journal = JournalRepository(db)
        foods = FoodRepository(db, TestDatabase.tagSuggester)
    }

    @After fun tearDown() = db.close()

    private fun at(hour: Int, minute: Int = 0): Instant = day.atTime(hour, minute).atZone(zone).toInstant()

    private suspend fun food(name: String) = foods.search(name).first()

    @Test fun timelineMergesEntriesChronologicallyAndGroupsSymptoms() = runTest {
        val semmel = food("semmel")
        journal.saveMeal(MealEntity(timestamp = at(12), zoneId = zone.id), listOf(MealItemEntity(mealId = 0, foodId = semmel.id, count = 2)), emptyMap())
        journal.saveBowelMovement(BowelMovementEntity(timestamp = at(9), zoneId = zone.id, bristol = 6, urgent = true))
        journal.saveSymptoms(
            null,
            listOf(
                SymptomEntryEntity(timestamp = at(14), zoneId = zone.id, type = SymptomType.PAIN, severity = 5),
                SymptomEntryEntity(timestamp = at(14), zoneId = zone.id, type = SymptomType.BLOATING, severity = 7),
            ),
        )
        journal.saveMedication(MedicationIntakeEntity(timestamp = at(8), zoneId = zone.id, name = "Lactase", effect = MedEffect.ENZYME))
        // Outside the day: must not appear.
        journal.saveBowelMovement(BowelMovementEntity(timestamp = at(23, 59).plusSeconds(120), zoneId = zone.id, bristol = 4))

        val timeline = journal.observeDay(day, zone).first()

        assertEquals(
            listOf(TimelineEntry.Medication::class, TimelineEntry.BowelMovement::class, TimelineEntry.Meal::class, TimelineEntry.Symptoms::class),
            timeline.map { it::class },
        )
        val meal = timeline[2] as TimelineEntry.Meal
        assertEquals(listOf(TimelineEntry.Meal.Item("Semmel", FoodKind.INGREDIENT, 2, "Stück", null)), meal.items)
        // Symptoms are ordered by type, not by insertion.
        assertEquals(listOf(SymptomType.BLOATING to 7, SymptomType.PAIN to 5), (timeline[3] as TimelineEntry.Symptoms).values)
    }

    @Test fun deletingMealAndUndoingRestoresItemsAndDishIngredients() = runTest {
        val bolognese = foods.search("bolognese").first { it.kind == FoodKind.DISH }
        val ingredients = foods.getDishIngredientIds(bolognese.id).drop(1)
        val mealId = journal.saveMeal(
            MealEntity(timestamp = at(19), zoneId = zone.id, eatenOut = true),
            listOf(MealItemEntity(mealId = 0, foodId = bolognese.id, size = PortionSize.L)),
            mapOf(0 to ingredients),
        )

        val undo = journal.deleteMeal(mealId)
        assertEquals(null, journal.getMeal(mealId))
        undo()

        val restored = journal.getMeal(mealId)!!
        assertTrue(restored.meal.eatenOut)
        val item = restored.items.single()
        assertEquals(PortionSize.L, item.item.size)
        assertEquals(ingredients.toSet(), journal.getMealItemIngredients(listOf(item.item.id))[item.item.id]!!.toSet())
    }

    @Test fun symptomGroupEditMovesAndMerges() = runTest {
        fun entry(t: Instant, type: SymptomType, severity: Int) = SymptomEntryEntity(timestamp = t, zoneId = zone.id, type = type, severity = severity)
        journal.saveSymptoms(null, listOf(entry(at(10), SymptomType.GAS, 3)))
        journal.saveSymptoms(null, listOf(entry(at(11), SymptomType.GAS, 5), entry(at(11), SymptomType.NAUSEA, 2)))

        // Move the 10:00 group to 11:00 with a new GAS value: merges, and the edited value wins for GAS.
        journal.saveSymptoms(at(10), listOf(entry(at(11), SymptomType.GAS, 8)))

        assertEquals(emptyList<SymptomEntryEntity>(), journal.getSymptomsAt(at(10)))
        assertEquals(
            setOf(SymptomType.GAS to 8, SymptomType.NAUSEA to 2),
            journal.getSymptomsAt(at(11)).map { it.type to it.severity }.toSet(),
        )
    }

    @Test fun deletingSymptomGroupCanBeUndone() = runTest {
        journal.saveSymptoms(null, listOf(SymptomEntryEntity(timestamp = at(10), zoneId = zone.id, type = SymptomType.PAIN, severity = 6)))
        val undo = journal.deleteSymptomsAt(at(10))
        assertEquals(0, journal.getSymptomsAt(at(10)).size)
        undo()
        assertEquals(6, journal.getSymptomsAt(at(10)).single().severity)
    }

    @Test fun medicationSuggestionsAreUniqueNewestFirst() = runTest {
        journal.saveMedication(MedicationIntakeEntity(timestamp = at(8), zoneId = zone.id, name = "Imodium", effect = MedEffect.ANTIDIARRHEAL))
        journal.saveMedication(MedicationIntakeEntity(timestamp = at(9), zoneId = zone.id, name = "Lactase", effect = MedEffect.ENZYME))
        journal.saveMedication(MedicationIntakeEntity(timestamp = at(10), zoneId = zone.id, name = " imodium ", effect = null))

        assertEquals(listOf("imodium" to null, "Lactase" to MedEffect.ENZYME), journal.medicationSuggestions())
    }

    @Test fun permanentDishChangeDoesNotRewritePastMeals() = runTest {
        val currywurst = foods.search("currywurst").single()
        val recipe = foods.getDishIngredientIds(currywurst.id)
        val mealId = journal.saveMeal(
            MealEntity(timestamp = at(12), zoneId = zone.id),
            listOf(MealItemEntity(mealId = 0, foodId = currywurst.id, count = 1)),
            mapOf(0 to recipe),
        )

        foods.setDishIngredients(currywurst.id, recipe.take(1))

        val item = journal.getMeal(mealId)!!.items.single().item
        assertEquals(recipe.toSet(), journal.getMealItemIngredients(listOf(item.id))[item.id]!!.toSet())
        assertEquals(recipe.take(1), foods.getDishIngredientIds(currywurst.id))
        assertTrue(foods.getFood(currywurst.id)!!.userModified)
    }

    @Test fun monthSummaryScoresDaysAndMarksLoggedOnes() = runTest {
        val semmel = food("semmel")
        // 24th: a meal only. 25th: Bristol 6 plus mild pain. 1 Nov: outside October.
        journal.saveMeal(MealEntity(timestamp = at(12), zoneId = zone.id), listOf(MealItemEntity(mealId = 0, foodId = semmel.id, count = 1)), emptyMap())
        val nextDay = at(9).plus(Duration.ofDays(1))
        journal.saveBowelMovement(BowelMovementEntity(timestamp = nextDay, zoneId = zone.id, bristol = 6))
        journal.saveSymptoms(null, listOf(SymptomEntryEntity(timestamp = nextDay, zoneId = zone.id, type = SymptomType.PAIN, severity = 2)))
        journal.saveBowelMovement(BowelMovementEntity(timestamp = at(9).plus(Duration.ofDays(8)), zoneId = zone.id, bristol = 4))

        val month = journal.observeMonth(YearMonth.of(2026, 10), zone).first()

        assertEquals(mapOf(day to DaySummary(null), day.plusDays(1) to DaySummary(7)), month)
    }

    @Test fun checkInHalvesAreSavedSeparately() = runTest {
        assertEquals(null, journal.observeCheckIn(day).first())
        // Sleep in the morning, stress in the evening.
        journal.saveSleep(day, 2)
        assertEquals(DailyCheckInEntity(day, stress = null, sleep = 2), journal.observeCheckIn(day).first())
        journal.saveStress(day, 4)
        journal.saveStress(day, 3)

        assertEquals(DailyCheckInEntity(day, stress = 3, sleep = 2), journal.observeCheckIn(day).first())
        assertEquals(null, journal.observeCheckIn(day.plusDays(1)).first())
    }

    @Test(expected = IllegalArgumentException::class)
    fun checkInRejectsOutOfRange() = runTest { journal.saveStress(day, 0) }

    @Test fun glassesOfWaterAddUpInOneEntry() = runTest {
        assertTrue(journal.addGlassOfWater(at(8), zone))
        assertTrue(journal.addGlassOfWater(at(10), zone))
        assertTrue(journal.addGlassOfWater(at(15), zone))

        val meals = journal.observeDay(day, zone).first().filterIsInstance<TimelineEntry.Meal>()
        val water = meals.single()
        assertEquals(at(8), water.timestamp) // stays at the first glass
        assertEquals(3, water.items.single().count)
        assertTrue(water.drinksOnly)
        // The next day starts a new entry.
        assertTrue(journal.addGlassOfWater(at(8).plus(Duration.ofDays(1)), zone))
        assertEquals(1, journal.observeDay(day.plusDays(1), zone).first().size)
    }

    @Test fun fluidTotalCountsEveryDrinkIncludingBeer() = runTest {
        val beer = food("Bier") // 330 ml a glass
        val coffee = food("Kaffee") // 200 ml a cup
        val cappuccino = foods.search("Cappuccino").first() // a drink dish, 200 ml
        val semmel = food("semmel")
        journal.saveMeal(
            MealEntity(timestamp = at(12), zoneId = zone.id),
            listOf(MealItemEntity(mealId = 0, foodId = semmel.id, count = 1), MealItemEntity(mealId = 0, foodId = beer.id, count = 2)),
            emptyMap(),
        )
        journal.saveMeal(MealEntity(timestamp = at(9), zoneId = zone.id), listOf(MealItemEntity(mealId = 0, foodId = coffee.id, count = 1)), emptyMap())
        journal.saveMeal(
            MealEntity(timestamp = at(16), zoneId = zone.id),
            listOf(MealItemEntity(mealId = 0, foodId = cappuccino.id, count = 1)),
            mapOf(0 to foods.getDishIngredientIds(cappuccino.id)),
        )
        journal.addGlassOfWater(at(18), zone)

        val timeline = journal.observeDay(day, zone).first()
        assertEquals(2 * 330.0 + 200.0 + 200.0 + 250.0, fluidMillilitres(timeline), 0.001)
        assertEquals(listOf(true, false, true, true), timeline.filterIsInstance<TimelineEntry.Meal>().map { it.drinksOnly })
    }
}

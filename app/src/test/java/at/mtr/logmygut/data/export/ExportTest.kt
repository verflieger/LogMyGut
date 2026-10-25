package at.mtr.logmygut.data.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import at.mtr.logmygut.data.AnalysisRepository
import at.mtr.logmygut.data.FoodRepository
import at.mtr.logmygut.data.JournalRepository
import at.mtr.logmygut.data.TestDatabase
import at.mtr.logmygut.data.db.BowelMovementEntity
import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.data.db.MealEntity
import at.mtr.logmygut.data.db.MealItemEntity
import at.mtr.logmygut.data.db.MedicationIntakeEntity
import at.mtr.logmygut.data.db.SymptomEntryEntity
import at.mtr.logmygut.domain.AnalysisParams
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.MedEffect
import at.mtr.logmygut.domain.PainChange
import at.mtr.logmygut.domain.PortionSize
import at.mtr.logmygut.domain.SymptomType
import at.mtr.logmygut.ui.export.ReportBlock
import at.mtr.logmygut.ui.export.reportBlocks
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rGB")
class ExportTest {
    private val zone = ZoneId.of("Europe/Vienna")
    private val day = LocalDate.of(2026, 10, 24)
    private lateinit var db: LogMyGutDatabase
    private lateinit var journal: JournalRepository
    private lateinit var foods: FoodRepository
    private lateinit var exports: ExportRepository

    @Before fun setUp() = runTest {
        db = TestDatabase.createSeeded()
        journal = JournalRepository(db)
        foods = FoodRepository(db, TestDatabase.tagSuggester)
        exports = ExportRepository(db, foods, AnalysisRepository(db, foods))
    }

    @After fun tearDown() = db.close()

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Instant = date.atTime(hour, minute).atZone(zone).toInstant()

    private suspend fun logDay() {
        val semmel = foods.search("semmel").first()
        val pizza = foods.search("pizza", FoodKind.DISH).first()
        val pizzaIngredients = db.foodDao().getDishIngredientIds(pizza.id)
        journal.saveMeal(
            MealEntity(timestamp = at(day, 12), zoneId = zone.id, eatenOut = true, note = "with \"extra\" cheese, lots"),
            listOf(
                MealItemEntity(mealId = 0, foodId = semmel.id, count = 2),
                MealItemEntity(mealId = 0, foodId = pizza.id, size = PortionSize.L),
            ),
            mapOf(1 to pizzaIngredients),
        )
        journal.saveBowelMovement(
            BowelMovementEntity(timestamp = at(day, 9), zoneId = zone.id, bristol = 6, urgent = true, painBefore = true, painAfter = PainChange.BETTER),
        )
        journal.saveSymptoms(
            null,
            listOf(
                SymptomEntryEntity(timestamp = at(day, 14), zoneId = zone.id, type = SymptomType.PAIN, severity = 5),
                SymptomEntryEntity(timestamp = at(day, 14), zoneId = zone.id, type = SymptomType.BLOATING, severity = 7),
            ),
        )
        journal.saveMedication(MedicationIntakeEntity(timestamp = at(day, 8), zoneId = zone.id, name = "Lactase", effect = MedEffect.ENZYME))
        journal.saveSleep(day, 2)
    }

    @Test fun loadsEntriesChronologicallyWithNamesAndDishIngredients() = runTest {
        logDay()
        val data = exports.load()
        assertEquals(
            listOf(ExportEntry.Medication::class, ExportEntry.BowelMovement::class, ExportEntry.Meal::class, ExportEntry.Symptom::class, ExportEntry.Symptom::class),
            data.entries.map { it::class },
        )
        val meal = data.entries.filterIsInstance<ExportEntry.Meal>().single()
        assertEquals("Semmel", meal.items[0].name)
        assertTrue(meal.items[0].ingredients.isEmpty())
        assertTrue("dish items list their ingredients", meal.items[1].ingredients.isNotEmpty())
        assertEquals(day, data.firstDay(zone))
    }

    @Test fun betweenKeepsOnlyThePeriod() = runTest {
        logDay()
        journal.saveBowelMovement(BowelMovementEntity(timestamp = at(day.minusDays(3), 9), zoneId = zone.id, bristol = 4))
        val data = exports.load()
        assertEquals(6, data.entries.size)
        assertEquals(5, data.between(day, day, zone).entries.size)
        assertTrue(data.between(day.plusDays(1), day.plusDays(2), zone).isEmpty)
    }

    @Test fun csvHasOneRowPerItemAndValueAndQuotesFields() = runTest {
        logDay()
        val lines = exports.load().toCsv().removePrefix("﻿").split("\r\n").filter { it.isNotEmpty() }
        assertEquals(CsvColumns.joinToString(","), lines.first())
        // 1 medication, 1 bowel movement, 2 meal items, 2 symptoms, 1 check-in value
        assertEquals(7, lines.size - 1)
        assertTrue(lines[1].startsWith("2026-10-24,08:00,Europe/Vienna,medication,,Lactase,"))
        assertTrue(lines[2].contains(",bowel_movement,") && lines[2].contains(",6,true,true,better,"))
        assertTrue(lines.any { it.contains(",symptom,,bloating,") && it.contains(",7,") })
        assertTrue("check-ins come last on their day", lines.last().startsWith("2026-10-24,,,check_in,,sleep,"))
        val semmel = lines.single { it.contains(",Semmel,") }
        assertTrue(semmel.contains(",ingredient,2,Stück,"))
        assertTrue("quotes are doubled and the field quoted", semmel.endsWith(",true,,\"with \"\"extra\"\" cheese, lots\""))
    }

    @Test fun summaryCountsEntriesWithoutInterpreting() = runTest {
        logDay()
        val report = exports.report(day.minusDays(6), day, AnalysisParams(), zone)
        val summary = summarize(report, zone)
        assertEquals(7, summary.days)
        assertEquals(1, summary.mealEntries)
        assertEquals(mapOf(6 to 1), summary.bristolCounts)
        assertEquals(mapOf(PainChange.BETTER to 1), summary.painAfter)
        assertEquals(SymptomSummary(daysLogged = 1, daysFromThreshold = 1, highest = 7), summary.symptoms[SymptomType.BLOATING])
        assertEquals(listOf(MedicationSummary("Lactase", MedEffect.ENZYME, 1, 1)), summary.medication)
        assertEquals(CheckInAverage(2.0, 1), summary.sleep)
        assertEquals(listOf(day), reportDays(report, zone))
    }

    @Test fun reportListsTheDayAndKeepsToTheWordingRules() = runTest {
        logDay()
        val report = exports.report(day.minusDays(13), day, AnalysisParams(), zone)
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val blocks = resources.reportBlocks(report, zone, day)
        val text = blocks.joinToString("\n") {
            when (it) {
                is ReportBlock.Title -> it.text
                is ReportBlock.Section -> it.text
                is ReportBlock.Day -> it.text
                is ReportBlock.Line -> it.text
                is ReportBlock.Entry -> "${it.time} ${it.label} ${it.text}"
            }
        }
        assertTrue(text.contains("24 October 2026"))
        assertTrue(text.contains("Pizza (L, "))
        assertTrue(text.contains("Semmel (2 pieces)"))
        assertTrue(text.contains("Type 6 (diarrhoea) · urgent · Cramps before, better afterwards"))
        assertTrue(text.contains("Bloating / fullness 7 · Abdominal pain / cramps 5"))
        assertTrue(text.contains("Note: with \"extra\" cheese, lots"))
        assertTrue(text.contains("Sleep last night: 2 of 5"))
        assertTrue(text.contains("Lactase (enzyme): 1× on 1 day"))
        for (word in listOf("suspect", "intoleran", "diagnosis", "cause of")) {
            assertFalse("the report must not say \"$word\"", text.contains(word, ignoreCase = true))
        }
        // Drawing the PDF itself isn't tested: Robolectric has no PdfDocument (it reports every document as closed).
    }
}

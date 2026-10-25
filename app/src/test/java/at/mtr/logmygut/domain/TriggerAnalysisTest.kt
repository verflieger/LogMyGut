package at.mtr.logmygut.domain

import at.mtr.logmygut.domain.Amount.Count
import at.mtr.logmygut.domain.Amount.Size
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerAnalysisTest {
    private val start: Instant = Instant.parse("2026-10-01T12:00:00Z")
    private fun t(hours: Long, minutes: Long = 0): Instant = start + Duration.ofHours(hours) + Duration.ofMinutes(minutes)

    // Ingredient ids
    private val milk = 1L
    private val ketchup = 2L // fructose from L
    private val oatMilk = 3L // fructans from 2 glasses
    private val rice = 4L
    private val tags = mapOf(
        milk to listOf(IngredientTag(Tag.LACTOSE)),
        ketchup to listOf(IngredientTag(Tag.FRUCTOSE, Threshold.MinSize(PortionSize.L))),
        oatMilk to listOf(IngredientTag(Tag.FRUCTANS, Threshold.MinCount(2))),
    )

    private fun ingredient(id: Long, amount: Amount = Size(PortionSize.M)) = AnalysisItem(amount, listOf(id), isDish = false)
    private fun meal(id: Long, time: Instant, vararg items: AnalysisItem) = AnalysisMeal(id, time, items.toList())
    private fun bloating(time: Instant, severity: Int = 6) = SymptomEvent(time, SymptomType.BLOATING, severity)

    private fun List<TriggerResult>.get(tag: Tag, outcome: Outcome) = single { it.tag == tag && it.outcome == outcome }
    private val bloatingOutcome = Outcome.Symptom(SymptomType.BLOATING)

    @Test fun countsExposuresAndControlsWithinTheTagWindow() {
        // Lactose window: 30 min – 8 h, both ends inclusive.
        val meals = listOf(
            meal(1, t(0), ingredient(milk)), // bloating 30 min later → positive
            meal(2, t(24), ingredient(milk)), // bloating 10 min later only → negative
            meal(3, t(48), ingredient(milk)), // bloating exactly 8 h later → positive
            meal(4, t(72), ingredient(rice)), // control, bloating 1 h later → positive
            meal(5, t(96), ingredient(rice)), // control, nothing → negative
        )
        val symptoms = listOf(bloating(t(0, 30)), bloating(t(24, 10)), bloating(t(56)), bloating(t(73)))
        val result = analyzeTriggers(meals, tags, symptoms, emptyList(), emptyList()).get(Tag.LACTOSE, bloatingOutcome)

        assertEquals(Rate(2, 3), result.exposed)
        assertEquals(Rate(1, 2), result.control)
        assertEquals(listOf(3L, 2L, 1L), result.exposures.map { it.mealId }) // newest first
        assertEquals((2.0 / 3) / 0.5, result.ratio!!, 1e-9)
    }

    @Test fun symptomsBelowTheThresholdDoNotCount() {
        val meals = listOf(meal(1, t(0), ingredient(milk)))
        val symptoms = listOf(bloating(t(1), severity = 3))
        val default = analyzeTriggers(meals, tags, symptoms, emptyList(), emptyList()).get(Tag.LACTOSE, bloatingOutcome)
        assertEquals(Rate(0, 1), default.exposed)

        val lowered = analyzeTriggers(meals, tags, symptoms, emptyList(), emptyList(), AnalysisParams(symptomThreshold = 3))
            .get(Tag.LACTOSE, bloatingOutcome)
        assertEquals(Rate(1, 1), lowered.exposed)
    }

    @Test fun doseTagsApplyOnlyFromTheirThreshold() {
        val meals = listOf(
            meal(1, t(0), ingredient(ketchup, Size(PortionSize.M))),
            meal(2, t(24), ingredient(ketchup, Size(PortionSize.L))),
            meal(3, t(48), ingredient(oatMilk, Count(1))),
            meal(4, t(72), ingredient(oatMilk, Count(2))),
        )
        val results = analyzeTriggers(meals, tags, emptyList(), emptyList(), emptyList())
        assertEquals(listOf(2L), results.get(Tag.FRUCTOSE, bloatingOutcome).exposures.map { it.mealId })
        assertEquals(listOf(4L), results.get(Tag.FRUCTANS, bloatingOutcome).exposures.map { it.mealId })
    }

    @Test fun dishItemsCountTheirActualIngredientsAtTheDishAmount() {
        fun dish(amount: Amount, vararg ingredients: Long) = AnalysisItem(amount, ingredients.toList(), isDish = true)
        val meals = listOf(
            meal(1, t(0), dish(Size(PortionSize.M), ketchup, rice)), // ketchup at M: no fructose
            meal(2, t(24), dish(Size(PortionSize.L), ketchup, rice)), // ketchup at L: fructose
            meal(3, t(48), dish(Count(1), oatMilk)), // 1 piece → 1 glass: no fructans
            meal(4, t(72), dish(Count(3), oatMilk)), // 3 pieces count as 2: fructans
            meal(5, t(96), dish(Size(PortionSize.M), rice)), // milk removed for this meal
        )
        val results = analyzeTriggers(meals, tags, emptyList(), emptyList(), emptyList())
        assertEquals(listOf(2L), results.get(Tag.FRUCTOSE, bloatingOutcome).exposures.map { it.mealId })
        assertEquals(listOf(4L), results.get(Tag.FRUCTANS, bloatingOutcome).exposures.map { it.mealId })
        assertTrue(results.get(Tag.LACTOSE, bloatingOutcome).exposures.isEmpty())
        assertEquals(Count(2), dish(Count(3), oatMilk).ingredientAmount)
    }

    @Test fun lactaseWithin30MinutesKeepsAMealOutOfLactoseExposures() {
        val meals = listOf(
            meal(1, t(0), ingredient(milk)),
            meal(2, t(24), ingredient(milk)),
            meal(3, t(48), ingredient(rice)),
        )
        val medication = listOf(
            MedicationEvent(t(0, -30), MedEffect.ENZYME), // 30 min before meal 1: counts
            MedicationEvent(t(24, 31), MedEffect.ENZYME), // 31 min after meal 2: too late
            MedicationEvent(t(48), MedEffect.ENZYME), // with a lactose-free meal: no effect
        )
        val result = analyzeTriggers(meals, tags, listOf(bloating(t(1))), emptyList(), medication).get(Tag.LACTOSE, bloatingOutcome)

        assertEquals(listOf(MealOutcome(1, positive = true)), result.withEnzyme)
        assertEquals(listOf(2L), result.exposures.map { it.mealId })
        assertEquals(1, result.control.n)
        // Other tags are unaffected: all three meals are fructose controls.
        assertEquals(3, analyzeTriggers(meals, tags, emptyList(), emptyList(), medication).get(Tag.FRUCTOSE, bloatingOutcome).control.n)
    }

    @Test fun antidiarrhealExcludesMealsOfTheNext24HoursFromDiarrheaOnly() {
        val meals = listOf(
            meal(1, t(0), ingredient(milk)), // before the intake: kept
            meal(2, t(10), ingredient(milk)), // 8 h after: excluded
            meal(3, t(26), ingredient(milk)), // exactly 24 h after: excluded
            meal(4, t(27), ingredient(milk)), // 25 h after: kept
        )
        val medication = listOf(MedicationEvent(t(2), MedEffect.ANTIDIARRHEAL))
        val bowel = listOf(BowelEvent(t(1), 7), BowelEvent(t(28), 6), BowelEvent(t(30), 4))
        val results = analyzeTriggers(meals, tags, emptyList(), bowel, medication)

        val diarrhea = results.get(Tag.LACTOSE, Outcome.Diarrhea)
        assertEquals(listOf(MealOutcome(4, true), MealOutcome(1, true)), diarrhea.exposures)
        assertEquals(4, results.get(Tag.LACTOSE, bloatingOutcome).exposures.size)
    }

    @Test fun hintLabels() {
        assertEquals(Hint.INSUFFICIENT, hintFor(Rate(4, 4), Rate(0, 50), minExposures = 5))
        assertEquals(Hint.STRONG, hintFor(Rate(5, 10), Rate(10, 50), minExposures = 5)) // 2.5×, +30 pp
        assertEquals(Hint.WEAK, hintFor(Rate(4, 10), Rate(10, 50), minExposures = 5)) // 2.0× but only +20 pp
        assertEquals(Hint.WEAK, hintFor(Rate(3, 5), Rate(10, 50), minExposures = 5)) // n < 10
        assertEquals(Hint.NONE, hintFor(Rate(2, 10), Rate(10, 50), minExposures = 5)) // 1.0×
        assertEquals(Hint.NONE, hintFor(Rate(0, 10), Rate(0, 50), minExposures = 5)) // nothing happened at all
        assertEquals(Hint.STRONG, hintFor(Rate(4, 10), Rate(0, 50), minExposures = 5)) // infinite ratio
        assertEquals(Hint.INSUFFICIENT, hintFor(Rate(8, 10), Rate(1, 50), minExposures = 12))
    }

    @Test fun ratioIsInfiniteWithoutControlEventsAndNullWithoutControls() {
        val meals = listOf(meal(1, t(0), ingredient(milk)), meal(2, t(24), ingredient(rice)))
        val withControl = analyzeTriggers(meals, tags, listOf(bloating(t(1))), emptyList(), emptyList()).get(Tag.LACTOSE, bloatingOutcome)
        assertEquals(Double.POSITIVE_INFINITY, withControl.ratio!!, 0.0)

        val onlyMilk = analyzeTriggers(meals.take(1), tags, listOf(bloating(t(1))), emptyList(), emptyList()).get(Tag.LACTOSE, bloatingOutcome)
        assertNull(onlyMilk.ratio)
    }

    @Test fun resultsAreSortedByHintThenRatio() {
        // Milk is followed by bloating every time, rice never.
        val meals = (0 until 10).flatMap { day ->
            val base = day * 24L
            listOf(
                meal(day * 10L + 1, t(base), ingredient(milk)),
                meal(day * 10L + 2, t(base + 12), ingredient(rice)),
            )
        }
        val symptoms = (0 until 10).map { bloating(t(it * 24L + 1)) }
        val results = analyzeTriggers(meals, tags, symptoms, emptyList(), emptyList())
        assertEquals(Tag.LACTOSE to bloatingOutcome, results.first().let { it.tag to it.outcome })
        assertEquals(Hint.STRONG, results.first().hint)
        assertTrue(results.zipWithNext().all { (a, b) -> a.hint < b.hint || (a.hint == b.hint && (a.ratio ?: -1.0) >= (b.ratio ?: -1.0)) })
        assertEquals(Tag.entries.size * Outcome.all.size, results.size)
    }

    @Test fun customWindowsReplaceTheDefault() {
        val meals = listOf(meal(1, t(0), ingredient(milk)))
        val symptoms = listOf(bloating(t(10)))
        assertEquals(Rate(0, 1), analyzeTriggers(meals, tags, symptoms, emptyList(), emptyList()).get(Tag.LACTOSE, bloatingOutcome).exposed)
        val params = AnalysisParams(windows = mapOf(Tag.LACTOSE to TagWindow(Duration.ZERO, Duration.ofHours(12))))
        assertEquals(Rate(1, 1), analyzeTriggers(meals, tags, symptoms, emptyList(), emptyList(), params).get(Tag.LACTOSE, bloatingOutcome).exposed)
    }
}

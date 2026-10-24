package at.mtr.logmygut.domain

import at.mtr.logmygut.domain.Amount.Count
import at.mtr.logmygut.domain.Amount.Size
import at.mtr.logmygut.domain.PortionSize.L
import at.mtr.logmygut.domain.PortionSize.M
import at.mtr.logmygut.domain.PortionSize.S
import org.junit.Assert.assertEquals
import org.junit.Test

class EffectiveTagsTest {
    // Ketchup: fructose and fructans only from L (seed data).
    private val ketchup = listOf(
        IngredientTag(Tag.FRUCTOSE, Threshold.MinSize(L)),
        IngredientTag(Tag.FRUCTANS, Threshold.MinSize(L)),
    )
    // Hafermilch: fructans from 2 glasses.
    private val oatMilk = listOf(IngredientTag(Tag.FRUCTANS, Threshold.MinCount(2)))
    private val apple = listOf(IngredientTag(Tag.FRUCTOSE), IngredientTag(Tag.POLYOLS))

    @Test fun alwaysOnTagsApplyAtAnyAmount() {
        assertEquals(setOf(Tag.FRUCTOSE, Tag.POLYOLS), effectiveTags(apple, Count(1)))
        assertEquals(setOf(Tag.FRUCTOSE, Tag.POLYOLS), effectiveTags(apple, Size(S)))
    }

    @Test fun sizeThresholdForSizeAmounts() {
        assertEquals(emptySet<Tag>(), effectiveTags(ketchup, Size(S)))
        assertEquals(emptySet<Tag>(), effectiveTags(ketchup, Size(M)))
        assertEquals(setOf(Tag.FRUCTOSE, Tag.FRUCTANS), effectiveTags(ketchup, Size(L)))
    }

    @Test fun countThresholdForCountAmounts() {
        assertEquals(emptySet<Tag>(), effectiveTags(oatMilk, Count(1)))
        assertEquals(setOf(Tag.FRUCTANS), effectiveTags(oatMilk, Count(2)))
        assertEquals(setOf(Tag.FRUCTANS), effectiveTags(oatMilk, Count(3)))
    }

    // SPEC 4.2 table: dish amount → ingredient amount.

    @Test fun sizeIngredientInCountDish() {
        // Ketchup in 1 Currywurst counts as M, in 2 as L.
        assertEquals(emptySet<Tag>(), effectiveTags(ketchup, Count(1)))
        assertEquals(setOf(Tag.FRUCTOSE, Tag.FRUCTANS), effectiveTags(ketchup, Count(2)))
    }

    @Test fun countIngredientInSizeDish() {
        // Hafermilch in an S/M dish counts as 1, in an L dish as 2.
        assertEquals(emptySet<Tag>(), effectiveTags(oatMilk, Size(S)))
        assertEquals(emptySet<Tag>(), effectiveTags(oatMilk, Size(M)))
        assertEquals(setOf(Tag.FRUCTANS), effectiveTags(oatMilk, Size(L)))
    }

    @Test fun mixedTags() {
        val tags = listOf(IngredientTag(Tag.GOS), IngredientTag(Tag.FRUCTANS, Threshold.MinSize(M)))
        assertEquals(setOf(Tag.GOS), effectiveTags(tags, Size(S)))
        assertEquals(setOf(Tag.GOS, Tag.FRUCTANS), effectiveTags(tags, Size(M)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun countMustBePositive() {
        Count(0)
    }
}

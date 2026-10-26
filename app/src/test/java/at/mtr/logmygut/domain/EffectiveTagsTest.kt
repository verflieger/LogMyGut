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

    // Tag rows: seed vs. user (SPEC 4)

    private fun seed(tag: Tag, threshold: Threshold? = null) = TagRow(tag, threshold, removed = false, TagSource.SEED)
    private fun user(tag: Tag, threshold: Threshold? = null) = TagRow(tag, threshold, removed = false, TagSource.USER)
    private fun userRemoved(tag: Tag) = TagRow(tag, threshold = null, removed = true, TagSource.USER)

    @Test fun userRowWinsOverSeedRow() {
        val rows = listOf(seed(Tag.FRUCTOSE), seed(Tag.POLYOLS), userRemoved(Tag.FRUCTOSE), user(Tag.POLYOLS, Threshold.MinSize(L)))
        assertEquals(setOf(userRemoved(Tag.FRUCTOSE), user(Tag.POLYOLS, Threshold.MinSize(L))), ownRows(rows).toSet())
    }

    @Test fun resolutionAppliesOwnRowsOverTheParent() {
        val parent = listOf(IngredientTag(Tag.HISTAMINE), IngredientTag(Tag.FAT))
        val rows = listOf(seed(Tag.FRUCTOSE), userRemoved(Tag.HISTAMINE), user(Tag.FAT, Threshold.MinSize(L)))
        assertEquals(
            setOf(IngredientTag(Tag.FRUCTOSE), IngredientTag(Tag.FAT, Threshold.MinSize(L))),
            resolveTags(parent, rows).toSet(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun removalCarriesNoThreshold() {
        TagRow(Tag.FAT, Threshold.MinSize(L), removed = true, TagSource.USER)
    }

    @Test fun fullRowsAreRelativeToTheParent() {
        val parent = listOf(IngredientTag(Tag.HISTAMINE), IngredientTag(Tag.FAT))
        val edited = listOf(IngredientTag(Tag.HISTAMINE), IngredientTag(Tag.FRUCTOSE, Threshold.MinSize(L)))
        val rows = fullRowsFor(edited, parent)
        assertEquals(setOf(user(Tag.FRUCTOSE, Threshold.MinSize(L)), userRemoved(Tag.FAT)), rows.toSet())
        assertEquals(edited.toSet(), resolveTags(parent, rows).toSet())
    }

    @Test fun userRowsStoreOnlyDifferencesFromTheSeed() {
        val parent = listOf(IngredientTag(Tag.HISTAMINE))
        val seedRows = listOf(seed(Tag.FRUCTOSE), seed(Tag.FAT))
        val unchanged = listOf(IngredientTag(Tag.HISTAMINE), IngredientTag(Tag.FRUCTOSE), IngredientTag(Tag.FAT))
        assertEquals(emptyList<TagRow>(), userRowsFor(unchanged, seedRows, parent))

        // A changed threshold, a removed own seed tag, a removed inherited tag and a new tag.
        val edited = listOf(IngredientTag(Tag.FRUCTOSE, Threshold.MinSize(L)), IngredientTag(Tag.SPICY))
        val rows = userRowsFor(edited, seedRows, parent)
        assertEquals(
            setOf(user(Tag.FRUCTOSE, Threshold.MinSize(L)), user(Tag.SPICY), userRemoved(Tag.FAT), userRemoved(Tag.HISTAMINE)),
            rows.toSet(),
        )
        assertEquals(edited.toSet(), resolveTags(parent, seedRows + rows).toSet())
    }
}

package at.mtr.logmygut.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DayScoreTest {
    @Test fun highestSeverityWins() = assertEquals(6, dayScore(listOf(2, 6, 3), emptyList()))

    @Test fun diarrheaCountsAsSeven() {
        assertEquals(7, dayScore(listOf(3), listOf(4, 6)))
        assertEquals(8, dayScore(listOf(8), listOf(7)))
    }

    @Test fun normalBowelMovementScoresZero() = assertEquals(0, dayScore(emptyList(), listOf(4)))

    @Test fun nothingToScore() = assertNull(dayScore(emptyList(), emptyList()))

    @Test fun bands() {
        assertEquals(
            listOf(ScoreBand.NONE, ScoreBand.MILD, ScoreBand.MILD, ScoreBand.MODERATE, ScoreBand.MODERATE, ScoreBand.SEVERE, ScoreBand.SEVERE),
            listOf(0, 1, 3, 4, 6, 7, 10).map(ScoreBand::of),
        )
    }
}

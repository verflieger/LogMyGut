package at.mtr.logmygut.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class TrendsTest {
    private val today = LocalDate.of(2026, 10, 25)

    @Test fun lastDaysEndsToday() {
        assertEquals(listOf(today.minusDays(2), today.minusDays(1), today), lastDays(today, 3))
    }

    @Test fun medianOfOddAndEvenCounts() {
        assertEquals(4.0, median(listOf(7, 4, 1)), 0.0)
        assertEquals(4.5, median(listOf(6, 3, 4, 5)), 0.0)
    }

    @Test fun movingMedianLooksBackSevenDays() {
        val values = mapOf(
            today.minusDays(7) to listOf(1), // just outside today's window
            today.minusDays(6) to listOf(6),
            today to listOf(4, 7),
        )
        val result = movingMedian(values, lastDays(today, 8))
        assertEquals(1.0, result.getValue(today.minusDays(7)), 0.0)
        assertEquals(3.5, result.getValue(today.minusDays(6)), 0.0) // 1 and 6
        assertEquals(6.0, result.getValue(today), 0.0) // 6, 4, 7
    }

    @Test fun movingMedianSkipsDaysWithoutValuesInTheWindow() {
        val values = mapOf(today.minusDays(20) to listOf(4))
        assertEquals(emptyMap<LocalDate, Double>(), movingMedian(values, lastDays(today, 7)))
    }

    @Test fun dailyMaxPerDay() {
        assertEquals(mapOf(today to 8), dailyMax(mapOf(today to listOf(3, 8, 5), today.minusDays(1) to emptyList())))
    }
}

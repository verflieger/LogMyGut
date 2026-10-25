package at.mtr.logmygut.domain

import java.time.LocalDate

// Day-based series for the trend charts (SPEC 6.2). Callers assign entries to days in the phone's time zone.

/** Inclusive range of days ending today: the last [days] days. */
fun lastDays(today: LocalDate, days: Int): List<LocalDate> = (days - 1 downTo 0).map { today.minusDays(it.toLong()) }

/**
 * Median of the values in the trailing 7 days (the day itself and the 6 before), for each day in [days]
 * that has at least one value in that window. Even counts average the middle two.
 */
fun movingMedian(valuesByDay: Map<LocalDate, List<Int>>, days: List<LocalDate>, windowDays: Int = 7): Map<LocalDate, Double> =
    days.mapNotNull { day ->
        val window = (0 until windowDays).flatMap { valuesByDay[day.minusDays(it.toLong())].orEmpty() }
        if (window.isEmpty()) null else day to median(window)
    }.toMap()

fun median(values: List<Int>): Double {
    require(values.isNotEmpty()) { "Median of nothing" }
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
}

/** Highest severity per day; days without an entry for the symptom are absent. */
fun dailyMax(valuesByDay: Map<LocalDate, List<Int>>): Map<LocalDate, Int> =
    valuesByDay.filterValues { it.isNotEmpty() }.mapValues { (_, values) -> values.max() }

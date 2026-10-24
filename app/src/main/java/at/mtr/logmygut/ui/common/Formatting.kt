package at.mtr.logmygut.ui.common

import at.mtr.logmygut.domain.Amount
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Austrian German, so months read "Jänner" etc. */
val AppLocale: Locale = Locale.forLanguageTag("de-AT")

val DayHeaderFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d. MMMM", AppLocale)
val ShortDateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EE, d. MMM yyyy · HH:mm", AppLocale)
val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", AppLocale)

fun Instant.local(zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime = atZone(zone)

/** Now, truncated to the minute: the default time for a new entry. */
fun nowMinute(): Instant = Instant.now().truncatedTo(ChronoUnit.MINUTES)

private val plurals = mapOf(
    "Scheibe" to "Scheiben",
    "Tasse" to "Tassen",
    "Kugel" to "Kugeln",
    "Dose" to "Dosen",
    "Glas" to "Gläser",
    "Flasche" to "Flaschen",
    "Portion" to "Portionen",
)

/** "1 Scheibe", "3 Scheiben", "2 Stück". Unknown labels are used unchanged. */
fun countLabel(n: Int, unitLabel: String?): String {
    val label = unitLabel ?: "Stück"
    return "$n ${if (n == 1) label else plurals[label] ?: label}"
}

/** Short amount for timelines: "2 Stück" or "L". */
fun Amount.short(unitLabel: String?): String = when (this) {
    is Amount.Count -> countLabel(n, unitLabel)
    is Amount.Size -> size.name
}

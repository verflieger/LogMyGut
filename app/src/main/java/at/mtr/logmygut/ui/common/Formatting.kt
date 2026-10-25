package at.mtr.logmygut.ui.common

import at.mtr.logmygut.domain.Amount
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/** Austrian German, so months read "Jänner" etc. */
val AppLocale: Locale = Locale.forLanguageTag("de-AT")

val DayHeaderFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d. MMMM", AppLocale)
val LongDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", AppLocale)
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

/** "120 g" with a non-breaking space, rounded to whole grams; amounts below 1 g show as "< 1 g". */
fun gramsLabel(grams: Double): String = if (grams < 1) "<\u00A01\u00A0g" else "${grams.roundToInt()}\u00A0g"

/** A portion amount: grams, or millilitres for a drink. */
fun portionLabel(amount: Double, drink: Boolean): String =
    if (drink) "${amount.roundToInt()}\u00A0ml" else gramsLabel(amount)

/** A day's fluid total: "750 ml" below a litre, then "1,3 l". */
fun volumeLabel(millilitres: Double): String =
    if (millilitres < 1000) "${millilitres.roundToInt()}\u00A0ml" else String.format(AppLocale, "%.1f\u00A0l", millilitres / 1000)

/** Short amount for timelines: "2 Stück" or "L". */
fun Amount.short(unitLabel: String?): String = when (this) {
    is Amount.Count -> countLabel(n, unitLabel)
    is Amount.Size -> size.name
}

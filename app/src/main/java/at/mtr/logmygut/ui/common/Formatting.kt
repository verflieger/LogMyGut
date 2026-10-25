package at.mtr.logmygut.ui.common

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.annotation.PluralsRes
import at.mtr.logmygut.R
import at.mtr.logmygut.domain.Amount
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * The app's language (the phone's, or the per-app language setting). German always formats as Austrian German,
 * so months read "Jänner" etc.
 */
val AppLocale: Locale
    get() = Locale.getDefault().let { if (it.language == "de") Locale.forLanguageTag("de-AT") else it }

private val formatters = ConcurrentHashMap<Pair<Locale, String>, DateTimeFormatter>()

/**
 * A formatter in the app's language for date/time skeletons (e.g. "EEEEdMMMM": weekday, day, month), each turned
 * into that language's own order and punctuation; several skeletons are joined with " · ".
 */
fun localizedFormat(vararg skeletons: String): DateTimeFormatter {
    val locale = AppLocale
    return formatters.getOrPut(locale to skeletons.joinToString("|")) {
        DateTimeFormatter.ofPattern(skeletons.joinToString(" · ") { DateFormat.getBestDateTimePattern(locale, it) }, locale)
    }
}

/** "Montag, 3. März" / "Monday, March 3". */
val DayHeaderFormat: DateTimeFormatter get() = localizedFormat("EEEEdMMMM")
/** "Montag, 3. März 2025" / "Monday, March 3, 2025". */
val LongDayFormat: DateTimeFormatter get() = localizedFormat("EEEEdMMMMy")
/** "Mo., 3. März 2025 · 14:30" / "Mon, Mar 3, 2025 · 2:30 PM". */
val ShortDateTimeFormat: DateTimeFormatter get() = localizedFormat("EEEdMMMy", "jm")
/** "14:30" / "2:30 PM", following the language's 12/24-hour convention. */
val TimeFormat: DateTimeFormatter get() = localizedFormat("jm")

fun Instant.local(zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime = atZone(zone)

/** Now, truncated to the minute: the default time for a new entry. */
fun nowMinute(): Instant = Instant.now().truncatedTo(ChronoUnit.MINUTES)

/**
 * Default time for a new entry added from a day view: that [day] at the current time of day, so a forgotten
 * entry only needs its time adjusted. Without a day (or for today) it is now.
 */
fun newEntryTime(day: LocalDate?, zone: ZoneId = ZoneId.systemDefault()): Instant {
    val now = nowMinute()
    if (day == null) return now
    return minOf(now, ZonedDateTime.of(day, now.atZone(zone).toLocalTime(), zone).toInstant())
}

/** Translations of the built-in unit labels (keys, see UnitLabelKeys). */
@PluralsRes private fun unitPlurals(key: String): Int? = when (key) {
    "Stück" -> R.plurals.unit_piece
    "Scheibe" -> R.plurals.unit_slice
    "Glas" -> R.plurals.unit_glass
    "Tasse" -> R.plurals.unit_cup
    "Dose" -> R.plurals.unit_can
    "Flasche" -> R.plurals.unit_bottle
    "Kugel" -> R.plurals.unit_scoop
    "Portion" -> R.plurals.unit_portion
    else -> null
}

/** A unit label as shown: a built-in one translated ("Scheibe" / "slice"), the user's own as typed. */
fun Resources.unitName(unitLabel: String, count: Int = 1): String =
    unitPlurals(unitLabel)?.let { getQuantityString(it, count) } ?: unitLabel

/** "1 Scheibe", "3 Scheiben", "2 Stück" / "3 slices". The user's own labels are used unchanged. */
fun Resources.countLabel(n: Int, unitLabel: String?): String = "$n ${unitName(unitLabel ?: "Stück", n)}"

/** "120 g" with a non-breaking space, rounded to whole grams; amounts below 1 g show as "< 1 g". */
fun gramsLabel(grams: Double): String = if (grams < 1) "<\u00A01\u00A0g" else "${grams.roundToInt()}\u00A0g"

/** A portion amount: grams, or millilitres for a drink. */
fun portionLabel(amount: Double, drink: Boolean): String =
    if (drink) "${amount.roundToInt()}\u00A0ml" else gramsLabel(amount)

/** A day's fluid total: "750 ml" below a litre, then "1,3 l". */
fun volumeLabel(millilitres: Double): String =
    if (millilitres < 1000) "${millilitres.roundToInt()}\u00A0ml" else String.format(AppLocale, "%.1f\u00A0l", millilitres / 1000)

/** Short amount for timelines: "2 Stück" or "L". */
fun Resources.shortAmount(amount: Amount, unitLabel: String?): String = when (amount) {
    is Amount.Count -> countLabel(amount.n, unitLabel)
    is Amount.Size -> amount.size.name
}

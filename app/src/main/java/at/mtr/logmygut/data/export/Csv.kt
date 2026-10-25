package at.mtr.logmygut.data.export

import at.mtr.logmygut.domain.FoodKind
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Column names of the CSV export (SPEC 7.1), English and stable so scripts can rely on them. */
val CsvColumns = listOf(
    "date", "time", "zone", "entry", "meal", "name", "kind", "amount", "unit", "ml", "ingredients",
    "value", "urgent", "pain_before", "pain_after", "eaten_out", "effect", "note",
)

private val TimeOfDay: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * All entries as CSV (RFC 4180, comma-separated, CRLF line ends), one row per meal item, bowel movement,
 * symptom, medication and check-in value. Dates and times are in the zone the entry was logged in.
 * Starts with a byte order mark so spreadsheet programs detect UTF-8 (umlauts).
 */
fun ExportData.toCsv(): String = buildString {
    append('﻿')
    appendRow(CsvColumns)
    val rows = entries.flatMap { it.csvRows() } + checkIns.flatMap { checkIn ->
        listOfNotNull(
            checkIn.sleep?.let { row(date = checkIn.date.toString(), entry = "check_in", name = "sleep", value = "$it") },
            checkIn.stress?.let { row(date = checkIn.date.toString(), entry = "check_in", name = "stress", value = "$it") },
        )
    }
    // Check-ins have no time and go after the day's other rows.
    rows.sortedWith(compareBy({ it[0] }, { it[1].isEmpty() }, { it[1] })).forEach { appendRow(it) }
}

private fun ExportEntry.csvRows(): List<List<String>> {
    val local = time.atZone(ZoneId.of(zoneId))
    val date = local.toLocalDate().toString()
    val clock = TimeOfDay.format(local)
    return when (this) {
        is ExportEntry.Meal -> items.map { item ->
            row(
                date, clock, zoneId, entry = "meal", meal = "$id", name = item.name,
                kind = if (item.kind == FoodKind.DISH) "dish" else "ingredient",
                amount = item.count?.toString() ?: item.size?.name.orEmpty(),
                unit = if (item.count != null) item.unitLabel ?: "Stück" else "",
                ml = item.millilitres?.roundToInt()?.toString().orEmpty(),
                ingredients = item.ingredients.joinToString("; "),
                eatenOut = "$eatenOut", note = note.orEmpty(),
            )
        }
        is ExportEntry.BowelMovement -> listOf(
            row(
                date, clock, zoneId, entry = "bowel_movement", value = "$bristol", urgent = "$urgent",
                painBefore = "$painBefore", painAfter = painAfter?.name?.lowercase().orEmpty(), note = note.orEmpty(),
            ),
        )
        is ExportEntry.Symptom -> listOf(
            row(date, clock, zoneId, entry = "symptom", name = type.name.lowercase(), value = "$severity", note = note.orEmpty()),
        )
        is ExportEntry.Medication -> listOf(
            row(date, clock, zoneId, entry = "medication", name = name, effect = effect?.name?.lowercase().orEmpty(), note = note.orEmpty()),
        )
    }
}

@Suppress("LongParameterList")
private fun row(
    date: String = "", time: String = "", zone: String = "", entry: String, meal: String = "", name: String = "",
    kind: String = "", amount: String = "", unit: String = "", ml: String = "", ingredients: String = "",
    value: String = "", urgent: String = "", painBefore: String = "", painAfter: String = "", eatenOut: String = "",
    effect: String = "", note: String = "",
) = listOf(date, time, zone, entry, meal, name, kind, amount, unit, ml, ingredients, value, urgent, painBefore, painAfter, eatenOut, effect, note)

private fun StringBuilder.appendRow(fields: List<String>) {
    fields.joinTo(this, ",") { field ->
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${field.replace("\"", "\"\"")}\"" else field
    }
    append("\r\n")
}

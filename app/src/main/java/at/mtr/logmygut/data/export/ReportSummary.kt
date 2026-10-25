package at.mtr.logmygut.data.export

import at.mtr.logmygut.domain.MedEffect
import at.mtr.logmygut.domain.PainChange
import at.mtr.logmygut.domain.SymptomType
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** [daysLogged]: days with any entry of the symptom; [daysFromThreshold]: days on which it reached the threshold. */
data class SymptomSummary(val daysLogged: Int, val daysFromThreshold: Int, val highest: Int)

data class MedicationSummary(val name: String, val effect: MedEffect?, val intakes: Int, val days: Int)

/** Mean of a check-in value over the [days] it was answered. */
data class CheckInAverage(val mean: Double, val days: Int)

/** The report's overview (SPEC 7.1): counts from the entries, nothing interpreted. */
data class ReportSummary(
    val days: Int,
    val mealEntries: Int,
    val bowelMovements: Int,
    /** Bristol type → count; only types that occur. */
    val bristolCounts: Map<Int, Int>,
    val urgent: Int,
    val painBefore: Int,
    val painAfter: Map<PainChange, Int>,
    /** Only symptoms with entries, in [SymptomType] order. */
    val symptoms: Map<SymptomType, SymptomSummary>,
    /** Most often taken first. */
    val medication: List<MedicationSummary>,
    val sleep: CheckInAverage?,
    val stress: CheckInAverage?,
)

fun summarize(report: Report, zone: ZoneId): ReportSummary {
    val entries = report.data.entries
    val day = { e: ExportEntry -> e.time.atZone(zone).toLocalDate() }
    val bowel = entries.filterIsInstance<ExportEntry.BowelMovement>()
    val symptoms = entries.filterIsInstance<ExportEntry.Symptom>()
    val medication = entries.filterIsInstance<ExportEntry.Medication>()
    val threshold = report.params.symptomThreshold

    return ReportSummary(
        days = (ChronoUnit.DAYS.between(report.from, report.to) + 1).toInt(),
        mealEntries = entries.count { it is ExportEntry.Meal },
        bowelMovements = bowel.size,
        bristolCounts = bowel.groupingBy { it.bristol }.eachCount().toSortedMap(),
        urgent = bowel.count { it.urgent },
        painBefore = bowel.count { it.painBefore },
        painAfter = bowel.mapNotNull { it.painAfter }.groupingBy { it }.eachCount(),
        symptoms = symptoms.groupBy { it.type }.toSortedMap().mapValues { (_, list) ->
            val maxByDay = list.groupBy(day).mapValues { (_, e) -> e.maxOf { it.severity } }
            SymptomSummary(maxByDay.size, maxByDay.values.count { it >= threshold }, maxByDay.values.max())
        },
        // Names differ in case only when typed differently; they count as one medication.
        medication = medication.groupBy { it.name.lowercase() }.values.map { list ->
            MedicationSummary(list.last().name, list.last().effect, list.size, list.map(day).toSet().size)
        }.sortedWith(compareByDescending<MedicationSummary> { it.intakes }.thenBy { it.name.lowercase() }),
        sleep = average(report.data.checkIns.mapNotNull { it.sleep }),
        stress = average(report.data.checkIns.mapNotNull { it.stress }),
    )
}

private fun average(values: List<Int>): CheckInAverage? =
    if (values.isEmpty()) null else CheckInAverage(values.average(), values.size)

/** Days of [report] with at least one entry or check-in value, in order. */
fun reportDays(report: Report, zone: ZoneId): List<LocalDate> =
    (report.data.entries.map { it.time.atZone(zone).toLocalDate() } +
        report.data.checkIns.filter { it.sleep != null || it.stress != null }.map { it.date }).distinct().sorted()

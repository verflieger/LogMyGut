package at.mtr.logmygut.ui.export

import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import at.mtr.logmygut.R
import at.mtr.logmygut.data.export.ExportEntry
import at.mtr.logmygut.data.export.Report
import at.mtr.logmygut.data.export.reportDays
import at.mtr.logmygut.data.export.summarize
import at.mtr.logmygut.domain.Amount
import at.mtr.logmygut.domain.Hint
import at.mtr.logmygut.domain.Outcome
import at.mtr.logmygut.domain.PainChange
import at.mtr.logmygut.domain.Rate
import at.mtr.logmygut.domain.TriggerResult
import at.mtr.logmygut.ui.analysis.decimal
import at.mtr.logmygut.ui.analysis.shortLabel
import at.mtr.logmygut.ui.common.LongDayFormat
import at.mtr.logmygut.ui.common.TimeFormat
import at.mtr.logmygut.ui.common.bristolGroup
import at.mtr.logmygut.ui.common.label
import at.mtr.logmygut.ui.common.localizedFormat
import at.mtr.logmygut.ui.common.shortAmount
import at.mtr.logmygut.ui.common.volumeLabel
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** The report's content in reading order (SPEC 7.1), kept apart from drawing so it can be tested. */
sealed interface ReportBlock {
    data class Title(val text: String) : ReportBlock
    data class Section(val text: String) : ReportBlock
    data class Day(val text: String) : ReportBlock
    /** [keepWithNext]: never the last line on a page (a heading for the line after it). */
    data class Line(val text: String, val small: Boolean = false, val keepWithNext: Boolean = false) : ReportBlock
    /** One diary entry: time, what kind of entry ([label]) and its details. */
    data class Entry(val time: String, val label: String, val text: String) : ReportBlock
}

/** The report for [report] as blocks, in the app's language. Describes the entries; interprets nothing. */
fun Resources.reportBlocks(report: Report, zone: ZoneId, created: LocalDate): List<ReportBlock> {
    val summary = summarize(report, zone)
    val dateFormat = localizedFormat("dMMMy")
    val blocks = mutableListOf<ReportBlock>()
    fun line(text: String, small: Boolean = false) { blocks += ReportBlock.Line(text, small) }

    blocks += ReportBlock.Title(getString(R.string.report_title))
    line(
        getString(
            R.string.report_period,
            dateFormat.format(report.from),
            dateFormat.format(report.to),
            days(summary.days),
        ),
    )
    line(getString(R.string.report_created, dateFormat.format(created)), small = true)
    line(getString(R.string.report_purpose), small = true)

    blocks += ReportBlock.Section(getString(R.string.report_overview))
    line(getString(R.string.report_meals, summary.mealEntries))
    line(getString(R.string.report_bowel, summary.bowelMovements, decimal(summary.bowelMovements.toDouble() / summary.days)))
    if (summary.bristolCounts.isNotEmpty()) {
        line(summary.bristolCounts.entries.joinToString(" · ") { (type, n) -> getString(R.string.report_bristol_count, type, n) })
    }
    if (summary.urgent > 0) line(getString(R.string.report_urgent, summary.urgent))
    if (summary.painBefore > 0) {
        line(
            getString(
                R.string.report_pain,
                summary.painBefore,
                summary.painAfter[PainChange.BETTER] ?: 0,
                summary.painAfter[PainChange.SAME] ?: 0,
                summary.painAfter[PainChange.WORSE] ?: 0,
            ),
        )
    }

    blocks += ReportBlock.Section(getString(R.string.quick_symptoms))
    if (summary.symptoms.isEmpty()) line(getString(R.string.report_no_symptoms))
    summary.symptoms.forEach { (type, s) ->
        line(getString(R.string.report_symptom, getString(type.label()), onDays(s.daysLogged), s.daysFromThreshold, report.params.symptomThreshold, s.highest))
    }

    blocks += ReportBlock.Section(getString(R.string.report_medication_title))
    if (summary.medication.isEmpty()) line(getString(R.string.report_no_medication))
    summary.medication.forEach { m ->
        val name = m.effect?.let { "${m.name} (${getString(it.label())})" } ?: m.name
        line(getString(R.string.report_medication, name, m.intakes, onDays(m.days)))
    }

    if (summary.sleep != null || summary.stress != null) {
        blocks += ReportBlock.Section(getString(R.string.checkin_title))
        summary.sleep?.let { line(getString(R.string.report_sleep, decimal(it.mean), days(it.days))) }
        summary.stress?.let { line(getString(R.string.report_stress, decimal(it.mean), days(it.days))) }
    }

    blocks += ReportBlock.Section(getString(R.string.analysis_hints_title))
    if (report.hints.isEmpty()) line(getString(R.string.report_no_hints))
    report.hints.forEach { result -> blocks += hintBlocks(result) }
    line(getString(R.string.analysis_params_summary, report.params.symptomThreshold, report.params.minExposures) + " " + getString(R.string.report_hints_basis), small = true)
    line(getString(R.string.analysis_disclaimer), small = true)

    blocks += ReportBlock.Section(getString(R.string.report_diary))
    val byDay = report.data.entries.groupBy { it.time.atZone(zone).toLocalDate() }
    val checkIns = report.data.checkIns.associateBy { it.date }
    for (day in reportDays(report, zone)) {
        val entries = byDay[day].orEmpty()
        blocks += ReportBlock.Day(LongDayFormat.format(day))
        val details = listOfNotNull(
            checkIns[day]?.sleep?.let { getString(R.string.checkin_value, getString(R.string.checkin_sleep), it) },
            checkIns[day]?.stress?.let { getString(R.string.checkin_value, getString(R.string.checkin_stress), it) },
            entries.filterIsInstance<ExportEntry.Meal>().sumOf { meal -> meal.items.sumOf { it.millilitres ?: 0.0 } }
                .takeIf { it > 0 }?.let { getString(R.string.fluid_day, volumeLabel(it)) },
        )
        if (details.isNotEmpty()) line(details.joinToString(" · "), small = true)
        blocks += entryBlocks(entries, zone)
    }
    return blocks
}

private fun Resources.days(n: Int) = getQuantityString(R.plurals.report_days, n, n)
private fun Resources.onDays(n: Int) = getQuantityString(R.plurals.report_on_days, n, n)

private fun Resources.hintBlocks(result: TriggerResult): List<ReportBlock> {
    val outcome = when (val o = result.outcome) {
        is Outcome.Symptom -> getString(o.type.shortLabel())
        Outcome.Diarrhea -> getString(R.string.outcome_diarrhea)
    }
    val hint = getString(if (result.hint == Hint.STRONG) R.string.hint_strong else R.string.hint_weak)
    val ratio = result.ratio?.let { if (it.isInfinite()) getString(R.string.analysis_ratio_only_with) else getString(R.string.analysis_ratio, decimal(it)) }
    return listOf(
        ReportBlock.Line("${getString(result.tag.label())} → $outcome: $hint", keepWithNext = true),
        ReportBlock.Line(
            listOfNotNull(
                getString(R.string.analysis_with, rateText(result.exposed)),
                getString(R.string.analysis_without, rateText(result.control)),
                ratio,
            ).joinToString(" · "),
            small = true,
        ),
    )
}

private fun Resources.rateText(rate: Rate): String =
    getString(R.string.analysis_rate, rate.positive, rate.n, (rate.value * 100).roundToInt())

/** A day's entries; symptoms saved together (same time) form one entry, as in the timeline. */
private fun Resources.entryBlocks(entries: List<ExportEntry>, zone: ZoneId): List<ReportBlock> {
    val blocks = mutableListOf<ReportBlock>()
    var index = 0
    while (index < entries.size) {
        val entry = entries[index]
        val time = TimeFormat.format(entry.time.atZone(zone))
        val withNote = { text: String, notes: List<String?> ->
            (listOf(text) + notes.filterNot { it.isNullOrBlank() }.distinct().map { getString(R.string.report_note, it) }).joinToString("\n")
        }
        when (entry) {
            is ExportEntry.Meal -> {
                val items = entry.items.joinToString(" · ") { item ->
                    val amount = item.count?.let { Amount.Count(it) } ?: item.size?.let { Amount.Size(it) }
                    val parts = listOfNotNull(
                        amount?.let { shortAmount(it, item.unitLabel) },
                        item.millilitres?.let { "≈ ${volumeLabel(it)}" },
                    ) + item.ingredients
                    if (parts.isEmpty()) item.name else "${item.name} (${parts.joinToString(", ")})"
                }
                val text = if (entry.eatenOut) "$items · ${getString(R.string.timeline_eaten_out)}" else items
                blocks += ReportBlock.Entry(time, getString(R.string.quick_meal), withNote(text, listOf(entry.note)))
            }
            is ExportEntry.BowelMovement -> {
                val text = listOfNotNull(
                    getString(R.string.report_bristol, entry.bristol, getString(bristolGroup(entry.bristol))),
                    getString(R.string.timeline_urgent).takeIf { entry.urgent },
                    entry.painAfter?.let { getString(R.string.timeline_pain, getString(it.label())) },
                ).joinToString(" · ")
                blocks += ReportBlock.Entry(time, getString(R.string.quick_bowel), withNote(text, listOf(entry.note)))
            }
            is ExportEntry.Symptom -> {
                val group = entries.drop(index).takeWhile { it is ExportEntry.Symptom && it.time == entry.time }.map { it as ExportEntry.Symptom }
                val text = group.joinToString(" · ") { "${getString(it.type.label())} ${it.severity}" }
                blocks += ReportBlock.Entry(time, getString(R.string.quick_symptoms), withNote(text, group.map { it.note }))
                index += group.size - 1
            }
            is ExportEntry.Medication -> {
                val text = entry.effect?.let { "${entry.name} (${getString(it.label())})" } ?: entry.name
                blocks += ReportBlock.Entry(time, getString(R.string.quick_medication), withNote(text, listOf(entry.note)))
            }
        }
        index++
    }
    return blocks
}

// A4 in PostScript points.
private const val PageWidth = 595
private const val PageHeight = 842
private const val Margin = 48f
private const val TimeColumn = 44f
private const val FooterHeight = 24f

/** Draws [blocks] onto A4 pages; [footer] gives each page's footer text (1-based page number). */
fun writeReportPdf(blocks: List<ReportBlock>, footer: (Int) -> String, out: OutputStream) {
    val document = PdfDocument()
    try {
        PdfWriter(document, footer).apply {
            blocks.forEach(::draw)
            finish()
        }
        document.writeTo(out)
    } finally {
        document.close()
    }
}

private class PdfWriter(private val document: PdfDocument, private val footer: (Int) -> String) {
    private val width = PageWidth - 2 * Margin
    private val bottom = PageHeight - Margin - FooterHeight
    private val pages = mutableListOf<PdfDocument.Page>()
    private var y = 0f

    private fun paint(size: Float, bold: Boolean = false, grey: Boolean = false) = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        color = if (grey) Color.rgb(90, 90, 90) else Color.BLACK
    }

    private val title = paint(18f, bold = true)
    private val section = paint(13f, bold = true)
    private val day = paint(11f, bold = true)
    private val body = paint(9.5f)
    private val small = paint(8.5f, grey = true)

    private fun layout(text: CharSequence, paint: TextPaint, width: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .build()

    fun draw(block: ReportBlock) {
        when (block) {
            is ReportBlock.Title -> place(layout(block.text, title, width), before = 0f, after = 6f)
            // Headings keep at least a line of what follows on their page.
            is ReportBlock.Section -> place(layout(block.text, section, width), before = 14f, after = 4f, keepWithNext = 40f)
            is ReportBlock.Day -> place(layout(block.text, day, width), before = 10f, after = 2f, keepWithNext = 30f)
            is ReportBlock.Line -> place(
                layout(block.text, if (block.small) small else body, width), before = 0f, after = 3f, keepWithNext = if (block.keepWithNext) 20f else 0f,
            )
            is ReportBlock.Entry -> {
                val text = SpannableStringBuilder(block.label).apply {
                    setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append("  ").append(block.text)
                }
                val main = layout(text, body, width - TimeColumn)
                ensure(main.height.toFloat() + 3f)
                canvas().apply {
                    save()
                    translate(Margin, y)
                    layout(block.time, body, TimeColumn).draw(this)
                    translate(TimeColumn, 0f)
                    main.draw(this)
                    restore()
                }
                y += main.height + 3f
            }
        }
    }

    private fun place(layout: StaticLayout, before: Float, after: Float, keepWithNext: Float = 0f) {
        val atTop = pages.isEmpty() || y == Margin
        ensure((if (atTop) 0f else before) + layout.height + keepWithNext)
        if (y > Margin) y += before
        canvas().apply {
            save()
            translate(Margin, y)
            layout.draw(this)
            restore()
        }
        y += layout.height + after
    }

    private fun ensure(height: Float) {
        if (pages.isEmpty() || y + height > bottom) newPage()
    }

    private fun newPage() {
        pages.lastOrNull()?.let(::finishPage)
        pages += document.startPage(PdfDocument.PageInfo.Builder(PageWidth, PageHeight, pages.size + 1).create())
        y = Margin
    }

    private fun canvas() = pages.last().canvas

    fun finish() {
        if (pages.isEmpty()) newPage()
        finishPage(pages.last())
    }

    private fun finishPage(page: PdfDocument.Page) {
        page.canvas.drawText(footer(page.info.pageNumber), Margin, PageHeight - Margin, small)
        document.finishPage(page)
    }
}

package at.mtr.logmygut.ui.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import at.mtr.logmygut.R
import at.mtr.logmygut.domain.ScoreBand
import at.mtr.logmygut.ui.common.AppLocale
import at.mtr.logmygut.ui.common.bandColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.time.temporal.TemporalAdjusters

// Trend charts (SPEC 6.2), drawn by hand: hairline grid, 2 dp lines, 8 dp dots with a surface ring,
// bars capped at 24 dp with rounded tops. Tapping a day selects it; the screen shows its values above.

private val AxisDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.", AppLocale)

/** Maps days to x (one slot per day) and values to y inside [plot]. */
class DayAxis(val days: List<LocalDate>, val plot: Rect, private val yMin: Float, private val yMax: Float) {
    val slot: Float = plot.width / days.size
    private val index = days.withIndex().associate { it.value to it.index }

    fun x(day: LocalDate): Float = plot.left + slot * (index.getValue(day) + 0.5f)
    fun y(value: Float): Float = plot.bottom - (value - yMin) / (yMax - yMin) * plot.height
    fun dayAt(px: Float): LocalDate? = ((px - plot.left) / slot).toInt().takeIf { px >= plot.left && it in days.indices }?.let(days::get)
}

private fun Density.axisFor(width: Float, height: Float, days: List<LocalDate>, yMin: Float, yMax: Float) = DayAxis(
    days,
    Rect(left = 24.dp.toPx(), top = 8.dp.toPx(), right = width - 4.dp.toPx(), bottom = height - 20.dp.toPx()),
    yMin,
    yMax,
)

/**
 * Frame shared by the day charts: y gridlines with labels, three x labels, antibiotic days shaded,
 * the selected day as a cursor. [underlay] draws behind the grid, [marks] on top.
 */
@Composable
fun DayChart(
    days: List<LocalDate>,
    yMin: Float,
    yMax: Float,
    yTicks: List<Int>,
    antibioticDays: Set<LocalDate>,
    selected: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    underlay: DrawScope.(DayAxis) -> Unit = {},
    marks: DrawScope.(DayAxis) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val shade = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
    val cursor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(days, selected) {
                detectTapGestures { offset ->
                    val day = axisFor(size.width.toFloat(), size.height.toFloat(), days, yMin, yMax).dayAt(offset.x)
                    onSelect(if (day == selected) null else day)
                }
            },
    ) {
        val axis = axisFor(size.width, size.height, days, yMin, yMax)
        val plot = axis.plot
        days.filter { it in antibioticDays }.forEach { day ->
            drawRect(shade, Offset(axis.x(day) - axis.slot / 2, plot.top), Size(axis.slot, plot.height))
        }
        underlay(axis)
        yTicks.forEach { tick ->
            val y = axis.y(tick.toFloat())
            drawLine(grid, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1.dp.toPx())
            val label = measurer.measure(tick.toString(), labelStyle)
            drawText(label, topLeft = Offset(plot.left - 6.dp.toPx() - label.size.width, y - label.size.height / 2))
        }
        listOf(days.first(), days[days.size / 2], days.last()).distinct().forEach { day ->
            val label = measurer.measure(AxisDayFormat.format(day), labelStyle)
            val x = (axis.x(day) - label.size.width / 2).coerceIn(plot.left, size.width - label.size.width)
            drawText(label, topLeft = Offset(x, plot.bottom + 4.dp.toPx()))
        }
        selected?.takeIf { it in days }?.let { day ->
            drawLine(cursor, Offset(axis.x(day), plot.top), Offset(axis.x(day), plot.bottom), strokeWidth = 1.dp.toPx())
        }
        marks(axis)
    }
}

/** A filled dot with a ring in the surface colour, so overlapping dots stay apart. */
private fun DrawScope.ringedDot(color: Color, ring: Color, center: Offset) {
    drawCircle(ring, radius = 6.dp.toPx(), center = center)
    drawCircle(color, radius = 4.dp.toPx(), center = center)
}

/** A 2 dp line through [points] in order. */
private fun DrawScope.polyline(color: Color, points: List<Offset>) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Bristol types as dots with the 7-day moving median as a line; the normal range 3–5 is tinted. */
@Composable
fun BristolChart(trends: TrendData, selected: LocalDate?, onSelect: (LocalDate?) -> Unit, description: String) {
    val measurer = rememberTextMeasurer()
    val zoneStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val normalBand = bandColors(ScoreBand.NONE).first.copy(alpha = 0.5f)
    val dot = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val line = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.surface
    // The normal band is explained by the legend; a label inside it would sit on the median line.
    val zones = listOf(
        6.5f to stringResource(R.string.bristol_group_diarrhea),
        1.5f to stringResource(R.string.bristol_group_constipation),
    )
    DayChart(
        days = trends.days, yMin = 0.5f, yMax = 7.5f, yTicks = (1..7).toList(),
        antibioticDays = trends.antibioticDays, selected = selected, onSelect = onSelect, description = description,
        height = 200.dp,
        underlay = { axis ->
            val top = axis.y(5.5f)
            drawRect(normalBand, Offset(axis.plot.left, top), Size(axis.plot.width, axis.y(2.5f) - top))
            zones.forEach { (value, text) -> drawZoneLabel(measurer, text, zoneStyle, axis, value) }
        },
    ) { axis ->
        trends.days.forEach { day -> trends.bristol[day]?.forEach { ringedDot(dot, ring, Offset(axis.x(day), axis.y(it.toFloat()))) } }
        // The median line breaks where a whole week has no bowel movement.
        trends.days.fold(mutableListOf(mutableListOf<Offset>())) { runs, day ->
            val median = trends.bristolMedian[day]
            if (median == null) { if (runs.last().isNotEmpty()) runs += mutableListOf<Offset>() }
            else runs.last() += Offset(axis.x(day), axis.y(median.toFloat()))
            runs
        }.forEach { polyline(line, it) }
    }
}

private fun DrawScope.drawZoneLabel(measurer: TextMeasurer, text: String, style: TextStyle, axis: DayAxis, value: Float) {
    val label = measurer.measure(text, style)
    drawText(label, topLeft = Offset(axis.plot.right - label.size.width - 4.dp.toPx(), axis.y(value) - label.size.height / 2))
}

/** Daily maximum severity of one symptom: dots joined by a line. */
@Composable
fun SeverityChart(days: List<LocalDate>, values: Map<LocalDate, Int>, antibioticDays: Set<LocalDate>, selected: LocalDate?, onSelect: (LocalDate?) -> Unit, description: String) {
    val color = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.surface
    DayChart(
        days = days, yMin = 0f, yMax = 10f, yTicks = listOf(0, 2, 4, 6, 8, 10),
        antibioticDays = antibioticDays, selected = selected, onSelect = onSelect, description = description,
    ) { axis ->
        val points = days.mapNotNull { day -> values[day]?.let { Offset(axis.x(day), axis.y(it.toFloat())) } }
        polyline(color, points)
        points.forEach { ringedDot(color, ring, it) }
    }
}

/** Bowel movements per day as columns. */
@Composable
fun CountChart(days: List<LocalDate>, counts: Map<LocalDate, Int>, antibioticDays: Set<LocalDate>, selected: LocalDate?, onSelect: (LocalDate?) -> Unit, description: String) {
    val color = MaterialTheme.colorScheme.primary
    val max = maxOf(3, counts.values.maxOrNull() ?: 0)
    val ticks = (0..max).filter { max <= 5 || it % 2 == 0 }
    DayChart(
        days = days, yMin = 0f, yMax = max.toFloat(), yTicks = ticks,
        antibioticDays = antibioticDays, selected = selected, onSelect = onSelect, description = description, height = 140.dp,
    ) { axis ->
        val width = (axis.slot - 2.dp.toPx()).coerceIn(1.dp.toPx(), 24.dp.toPx())
        val radius = minOf(4.dp.toPx(), width / 2)
        counts.forEach { (day, n) ->
            if (day !in days || n == 0) return@forEach
            val top = axis.y(n.toFloat())
            val rect = Rect(axis.x(day) - width / 2, top, axis.x(day) + width / 2, axis.plot.bottom)
            // Rounded at the top, square at the baseline.
            val path = Path().apply {
                addRoundRect(
                    RoundRect(rect, topLeft = CornerRadius(radius), topRight = CornerRadius(radius), bottomLeft = CornerRadius.Zero, bottomRight = CornerRadius.Zero),
                )
            }
            drawPath(path, color)
        }
    }
}

/**
 * Calendar heatmap (SPEC 6.2): one column per week (Monday on top), coloured by the day score.
 * Antibiotic days get a dot. Tapping a past day opens it.
 */
@Composable
fun ScoreHeatmap(scores: Map<LocalDate, Int>, antibioticDays: Set<LocalDate>, today: LocalDate, weeks: Int, onOpenDay: (LocalDate) -> Unit, description: String) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val bands = ScoreBand.entries.associateWith { bandColors(it) }
    val dotColor = MaterialTheme.colorScheme.onSurface
    val firstMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(weeks - 1L)
    val dayLabels = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        .associateWith { it.getDisplayName(JavaTextStyle.SHORT, AppLocale).removeSuffix(".") }

    fun Density.layout(width: Float): Triple<Float, Float, Float> {
        val left = 24.dp.toPx()
        val top = 16.dp.toPx()
        return Triple(left, top, (width - left) / weeks)
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(16.dp + (maxWidth - 24.dp) / weeks * 7)
                .semantics { contentDescription = description }
                .pointerInput(today, weeks) {
                    detectTapGestures { offset ->
                        val (left, top, cell) = layout(size.width.toFloat())
                        val week = ((offset.x - left) / cell).toInt()
                        val row = ((offset.y - top) / cell).toInt()
                        if (offset.x < left || offset.y < top || week !in 0 until weeks || row !in 0..6) return@detectTapGestures
                        val day = firstMonday.plusWeeks(week.toLong()).plusDays(row.toLong())
                        if (day <= today) onOpenDay(day)
                    }
                },
        ) {
            val (left, top, cell) = layout(size.width)
            val gap = 2.dp.toPx()
            val corner = CornerRadius(2.dp.toPx())
            dayLabels.forEach { (day, text) ->
                val label = measurer.measure(text, labelStyle)
                val y = top + (day.value - 1) * cell + (cell - label.size.height) / 2
                drawText(label, topLeft = Offset(0f, y))
            }
            var labelEnd = 0f
            for (week in 0 until weeks) {
                val monday = firstMonday.plusWeeks(week.toLong())
                val x = left + week * cell
                // A month is named above the week holding its 1st; labels that would overlap or be cut off are skipped.
                val month = (0..6L).map(monday::plusDays).firstOrNull { it.dayOfMonth == 1 }?.month ?: monday.month.takeIf { week == 0 }
                if (month != null) {
                    val label = measurer.measure(month.getDisplayName(JavaTextStyle.SHORT, AppLocale).removeSuffix("."), labelStyle)
                    if (x >= labelEnd && x + label.size.width <= size.width) {
                        drawText(label, topLeft = Offset(x, 0f))
                        labelEnd = x + label.size.width + 4.dp.toPx()
                    }
                }
                for (row in 0..6) {
                    val day = monday.plusDays(row.toLong())
                    if (day > today) continue
                    val y = top + row * cell
                    val color = scores[day]?.let { bands.getValue(ScoreBand.of(it)).first } ?: empty
                    drawRoundRect(color, Offset(x + gap / 2, y + gap / 2), Size(cell - gap, cell - gap), corner)
                    if (day in antibioticDays) drawCircle(dotColor, radius = cell / 6, center = Offset(x + cell / 2, y + cell / 2))
                }
            }
        }
    }
}

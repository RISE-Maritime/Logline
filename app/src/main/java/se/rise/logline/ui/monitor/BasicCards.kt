package se.rise.logline.ui.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.rise.logline.monitor.MonitorCard
import se.rise.logline.monitor.MonitorSnapshot
import se.rise.logline.monitor.gapColumns
import se.rise.logline.monitor.inputs
import se.rise.logline.monitor.isCircularSubject
import se.rise.logline.monitor.timeColumns
import se.rise.logline.monitor.unitsForSubject
import se.rise.logline.monitor.within
import se.rise.logline.ui.Bounds
import se.rise.logline.ui.boundsOf
import se.rise.logline.ui.components.readAsOneItem
import se.rise.logline.ui.fmt
import se.rise.logline.ui.formatBearing
import se.rise.logline.ui.normalise
import se.rise.logline.ui.unwrapAngles

/** One big number with its unit: the card to glance at. */
@Composable
internal fun ValueCard(card: MonitorCard, snapshot: MonitorSnapshot) {
    val reading = snapshot.reading(card, "value")
    val subject = card.string("subject")
    val decimals = card.num("decimals", 1.0).toInt()
    val text = if (isCircularSubject(subject)) reading.value?.let(::formatBearing) ?: "—" else formatValue(reading.value, decimals)
    Figure(
        caption = subject,
        reading = reading,
        text = text,
        unit = if (isCircularSubject(subject)) "" else unitsForSubject(subject),
        modifier = Modifier.fillMaxWidth(),
        large = true,
    )
}

private val PLOT_HEIGHT = 160.dp

/**
 * Up to three subjects on one time axis, the window ending now.
 *
 * Same drawing as the Live tab's detail plot — a translucent band holding every sample in a pixel
 * column, a mean line through it — so a spike survives binning. Two things differ, both because
 * there can be several series: columns are binned **by time** rather than by index (see
 * `timeColumns`), and a gap in the stream is drawn as a gap rather than bridged, since a replay
 * paused for a minute is not a minute of straight line.
 *
 * The axis is the data's own range unless the card pins one, and deliberately not `plotBounds`'s
 * floor, for the reason `DetailPlot` gives: this axis is labelled.
 */
@Composable
internal fun PlotCard(card: MonitorCard, snapshot: MonitorSnapshot) {
    val windowMs = (card.num("windowS", 60.0) * 1000).toLong()
    val to = snapshot.nowMillis
    val from = to - windowMs
    val palette = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.error,
    )
    val series = listOf("s1", "s2", "s3").mapIndexedNotNull { i, role ->
        val input = card.inputs().firstOrNull { it.role == role } ?: return@mapIndexedNotNull null
        val w = snapshot.window(card, role).within(from, to)
        val circular = isCircularSubject(input.subject)
        Series(
            subject = input.subject,
            reading = snapshot.reading(card, role),
            times = w.timesMillis,
            values = if (circular) unwrapAngles(w.values) else w.values,
            circular = circular,
            color = palette[i],
        )
    }
    val auto = series.mapNotNull { boundsOf(it.values) }.reduceOrNull { a, b ->
        Bounds(minOf(a.min, b.min), maxOf(a.max, b.max))
    }
    val yMin = card.number("yMin")?.toFloat()
    val yMax = card.number("yMax")?.toFloat()
    val axis = auto?.let { Bounds(yMin ?: it.min, yMax ?: it.max) }
        ?: if (yMin != null && yMax != null) Bounds(yMin, yMax) else null
    val allCircular = series.isNotEmpty() && series.all { it.circular }
    fun label(v: Float) = if (allCircular) formatBearing(v) else "%.4g".fmt(v)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (axis == null || axis.max <= axis.min) {
            Box(Modifier.fillMaxWidth().height(PLOT_HEIGHT), contentAlignment = Alignment.Center) {
                Text(
                    "No samples in the last ${trimNumber(card.num("windowS", 60.0))} s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val grid = MaterialTheme.colorScheme.outlineVariant
            Row(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.width(IntrinsicSize.Min).height(PLOT_HEIGHT).padding(end = 6.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    AxisText(label(axis.max))
                    AxisText(label((axis.min + axis.max) / 2f))
                    AxisText(label(axis.min))
                }
                Column(Modifier.weight(1f)) {
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(PLOT_HEIGHT)
                            .readAsOneItem(
                                series.joinToString("; ") { "${it.subject}, ${it.values.size} samples" } +
                                    " over ${trimNumber(card.num("windowS", 60.0))} seconds",
                            ),
                    ) {
                        fun y(v: Float) = size.height * (1f - normalise(v, axis).coerceIn(0f, 1f))
                        listOf(axis.max, (axis.min + axis.max) / 2f, axis.min).forEach {
                            drawLine(grid, Offset(0f, y(it)), Offset(size.width, y(it)), strokeWidth = 1f)
                        }
                        val columns = (size.width / 2f).toInt().coerceAtLeast(2)
                        val stepX = size.width / (columns - 1)
                        series.forEach { s ->
                            val bins = timeColumns(s.times, s.values, from, to, columns)
                            // Occupied columns are joined unless the time between them is a real gap
                            // in the stream — longer than a few of this series' own sample intervals.
                            // Joining only *adjacent* columns would draw nothing at all for a 10 Hz
                            // series spread over 500 columns, most of which are empty.
                            val maxGap = gapColumns(s.times, from, to, columns)
                            val occupied = (0 until columns).filter { !bins.means[it].isNaN() }
                            var prev = -1
                            val trend = Path()
                            for (i in occupied) {
                                val joined = prev >= 0 && i - prev <= maxGap
                                if (joined) {
                                    val band = Path().apply {
                                        moveTo(prev * stepX, y(bins.maxs[prev]))
                                        lineTo(i * stepX, y(bins.maxs[i]))
                                        lineTo(i * stepX, y(bins.mins[i]))
                                        lineTo(prev * stepX, y(bins.mins[prev]))
                                        close()
                                    }
                                    drawPath(band, s.color.copy(alpha = 0.25f))
                                    trend.lineTo(i * stepX, y(bins.means[i]))
                                } else {
                                    trend.moveTo(i * stepX, y(bins.means[i]))
                                    // A point with no neighbour draws nothing as a path; mark it.
                                    val next = occupied.firstOrNull { it > i }
                                    if (next == null || next - i > maxGap) {
                                        drawCircle(s.color, 3.5f, Offset(i * stepX, y(bins.means[i])))
                                    }
                                }
                                prev = i
                            }
                            drawPath(trend, s.color, style = Stroke(width = 2.5f))
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                        AxisText("−${trimNumber(card.num("windowS", 60.0))} s")
                        Box(Modifier.weight(1f))
                        AxisText("now")
                    }
                }
            }
        }
        series.forEach { s -> Legend(s) }
    }
}

private class Series(
    val subject: String,
    val reading: Reading,
    val times: LongArray,
    val values: FloatArray,
    val circular: Boolean,
    val color: Color,
)

@Composable
private fun Legend(s: Series) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(s.color))
        Text(
            s.reading.topic?.let { "${it.subject} · ${it.source}" } ?: "${s.subject} · not seen",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val v = s.reading.value
        Text(
            when {
                v == null -> "—"
                s.circular -> formatBearing(v)
                else -> "%.4g".fmt(v) + unitsForSubject(s.subject).let { if (it.isEmpty()) "" else " $it" }
            },
            style = MaterialTheme.typography.bodySmall,
            color = readingColor(s.reading),
        )
    }
}

@Composable
internal fun AxisText(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

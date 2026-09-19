package se.rise.logline.ui.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import se.rise.logline.monitor.ImuFrame
import se.rise.logline.monitor.MonitorCard
import se.rise.logline.monitor.MonitorSnapshot
import se.rise.logline.monitor.VectorWindow
import se.rise.logline.monitor.imuAnalysis
import se.rise.logline.monitor.inputs
import se.rise.logline.monitor.resolve
import se.rise.logline.monitor.within
import se.rise.logline.publish.SampleWindow
import se.rise.logline.ui.boundsOf
import se.rise.logline.ui.components.readAsOneItem
import se.rise.logline.ui.fmt
import se.rise.logline.ui.normalise

/**
 * Acceleration and rotation per vessel axis, total and dynamic G, and a G-G diagram — the
 * `keelson-imu` Foxglove panel, trimmed to phone width.
 *
 * Axes are named for the **vessel** (forward, starboard, down) through the mounting preset, because a
 * raw `x` means nothing to somebody looking at a boat. The default preset is Logline's own frame —
 * forward is the phone's +y — which is what the Foxglove panel defaults to as well.
 */
@Composable
internal fun ImuCard(card: MonitorCard, snapshot: MonitorSnapshot) {
    val accelInput = card.inputs().firstOrNull { it.role == "accel" }
    val gyroInput = card.inputs().firstOrNull { it.role == "gyro" }
    val accelTopic = accelInput?.let(snapshot::resolve)
    val gyroTopic = gyroInput?.let(snapshot::resolve)
    val accel = accelTopic?.let { snapshot.vectors[it] }
    val gyro = gyroTopic?.let { snapshot.vectors[it] }
    val now = snapshot.nowMillis
    val stale = card.num("staleAfterS", 5.0) * 1000
    val accelStale = accelTopic?.let(snapshot::info)?.let { now - it.lastArrivalMillis > stale } ?: false
    val frame = if (card.string("mountPreset") == "frd") ImuFrame.Frd else ImuFrame.Logline
    val sparkMs = (card.num("sparklineSeconds", 30.0) * 1000).toLong()
    val peakMs = (card.num("peakMinutes", 30.0) * 60_000).toLong()

    val analysis = accel?.let {
        imuAnalysis(
            it,
            frame,
            card.string("gravityMode"),
            now,
            peakMs,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (accel == null && gyro == null) {
            MissingLine(listOfNotNull(accelInput?.subject, gyroInput?.subject))
            return@Column
        }
        if (analysis != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val r = Reading(accelTopic, analysis.totalG, null, accelStale)
                Figure("Total", r, formatValue(analysis.totalG, 2), "g")
                Figure("Dynamic", r.copy(value = analysis.dynamicG), formatValue(analysis.dynamicG, 2), "g")
                Figure("Peak", r.copy(value = analysis.peakDynamicG, stale = false), formatValue(analysis.peakDynamicG, 2), "g")
            }
            Text(
                "Gravity ${if (analysis.gravityIncluded) "included in" else "removed from"} the signal" +
                    if (card.string("gravityMode") == "auto") " (detected)" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        accel?.let { w ->
            Text("Acceleration · m/s²", style = MaterialTheme.typography.labelLarge)
            AxisRows(w, frame, now, sparkMs, accelStale)
        }
        gyro?.let { w ->
            Text("Rotation · rad/s", style = MaterialTheme.typography.labelLarge)
            AxisRows(w, frame, now, sparkMs, false)
        }
        if (analysis != null && card.flag("ggEnabled")) {
            GgDiagram(analysis.gg, analysis.ggLatest)
        }
    }
}

@Composable
private fun AxisRows(w: VectorWindow, frame: ImuFrame, now: Long, sparkMs: Long, stale: Boolean) {
    val axes = frame.vesselAxes(w)
    val colors = listOf(Color(0xFFE53935), Color(0xFF43A047), Color(0xFF1E88E5))
    axes.forEachIndexed { i, (name, window) ->
        val recent = window.within(now - sparkMs, now)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(72.dp))
            Spark(recent, colors[i], Modifier.weight(1f).height(26.dp))
            Text(
                formatValue(window.latest, 2),
                style = MaterialTheme.typography.bodyMedium,
                color = if (stale) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(64.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

/** A bare line over its own range with a zero line when zero is in view — the Foxglove panel's sparkline. */
@Composable
private fun Spark(w: SampleWindow, color: Color, modifier: Modifier) {
    val zero = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val b = boundsOf(w.values) ?: return@Canvas
        if (w.size < 2) return@Canvas
        fun y(v: Float) = size.height * (1f - normalise(v, b))
        if (0f in b.min..b.max) drawLine(zero, Offset(0f, y(0f)), Offset(size.width, y(0f)), 1f)
        val t0 = w.timesMillis.first()
        val span = (w.timesMillis.last() - t0).coerceAtLeast(1).toFloat()
        val path = Path()
        for (i in 0 until w.size) {
            val x = (w.timesMillis[i] - t0) / span * size.width
            if (i == 0) path.moveTo(x, y(w.values[i])) else path.lineTo(x, y(w.values[i]))
        }
        drawPath(path, color, style = Stroke(1.5f))
    }
}

/**
 * Longitudinal against lateral acceleration in g, the recent trace plus a dot for now. Forward is up
 * and starboard is right, so a hard turn to starboard draws toward the left — the centripetal pull is
 * what the accelerometer feels.
 */
@Composable
private fun GgDiagram(points: List<Pair<Float, Float>>, latest: Pair<Float, Float>?) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val trace = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val scale = maxOf(0.25f, points.maxOfOrNull { maxOf(kotlin.math.abs(it.first), kotlin.math.abs(it.second)) } ?: 0f) * 1.1f
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Canvas(Modifier.size(160.dp).readAsOneItem("G-G diagram, full scale ${"%.2f".fmt(scale)} g")) {
            val c = center
            val r = size.minDimension / 2f
            drawCircle(grid, r, c, style = Stroke(1f))
            drawCircle(grid, r / 2, c, style = Stroke(1f))
            drawLine(grid, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
            drawLine(grid, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
            fun p(fwd: Float, stbd: Float) = Offset(c.x + stbd / scale * r, c.y - fwd / scale * r)
            points.forEach { (f, s) -> drawCircle(trace.copy(alpha = 0.35f), 2f, p(f, s)) }
            latest?.let { (f, s) -> drawCircle(ink, 5f, p(f, s)) }
        }
        AxisText("G-G · full scale %.2f g".fmt(scale))
    }
}

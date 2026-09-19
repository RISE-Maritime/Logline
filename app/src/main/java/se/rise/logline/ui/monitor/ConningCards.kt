package se.rise.logline.ui.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.rise.logline.monitor.MPS_TO_KNOTS
import se.rise.logline.monitor.MonitorCard
import se.rise.logline.monitor.MonitorSnapshot
import se.rise.logline.monitor.bearingOver
import se.rise.logline.monitor.bowSternOffsetsM
import se.rise.logline.monitor.driftAngleDeg
import se.rise.logline.monitor.meanOver
import se.rise.logline.monitor.rotFromHeadings
import se.rise.logline.monitor.transverseAtKn
import se.rise.logline.monitor.within
import se.rise.logline.ui.components.readAsOneItem
import se.rise.logline.ui.fmt
import se.rise.logline.ui.formatBearing
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// The conning cards, ported from foxglove-custom-panels/src/conning/panels. Each keeps its panel's
// inputs and defaults; the drawing is simplified to what reads at phone width. Two rules from those
// panels survive intact: a missing input is `—`, never zero, and anything computed rather than
// received says so.

private fun Double.toRad() = this * Math.PI / 180.0

/** `12.3° STBD` / `4.0° PORT` / `0.0°` — a side word rather than a sign, the way it is said on a bridge. */
private fun sided(value: Float?, decimals: Int = 1): String = when {
    value == null -> "—"
    abs(value) < 0.05f -> "%.${decimals}f°".fmt(0f)
    value > 0 -> "%.${decimals}f° STBD".fmt(value)
    else -> "%.${decimals}f° PORT".fmt(-value)
}

// ── Heading ──────────────────────────────────────────────────────────────────────────────────────────

@Composable
internal fun HeadingCard(card: MonitorCard, snapshot: MonitorSnapshot) {
    val now = snapshot.nowMillis
    val headingReading = snapshot.reading(card, "heading")
    val yawReading = snapshot.reading(card, "yaw")
    val cogReading = snapshot.reading(card, "cog")
    val rotReading = snapshot.reading(card, "rot")
    val angleSpan = (card.num("angleSmoothingS", 0.0) * 1000).toLong()
    val rotSpan = (card.num("rotSmoothingS", 3.0) * 1000).toLong()

    // Heading falls back to yaw, as the Foxglove panel's does — and the caption says which.
    val usingYaw = !headingReading.present && yawReading.present
    val hdgWindow = snapshot.window(card, if (usingYaw) "yaw" else "heading")
    val heading = bearingOver(hdgWindow, now, angleSpan)?.toFloat()
    val hdgReading = if (usingYaw) yawReading else headingReading
    val cog = cogReading.value
    val rotDegps = meanOver(snapshot.window(card, "rot"), now, rotSpan)
        ?: rotFromHeadings(snapshot.window(card, if (usingYaw) "yaw" else "heading"), now)
    val rotDerived = !rotReading.present && rotDegps != null
    val courseUp = card.string("centre") == "course" && cog != null

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        CompassDial(
            heading = heading,
            cog = cog,
            up = if (courseUp) cog ?: 0f else heading ?: 0f,
            stale = hdgReading.stale,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Figure(if (usingYaw) "HDG (yaw)" else "HDG T", hdgReading, heading?.let(::formatBearing) ?: "—", "")
            Figure("COG", cogReading, cog?.let(::formatBearing) ?: "—", "")
            Figure(
                if (rotDerived) "ROT (derived)" else "ROT",
                if (rotDerived) hdgReading else rotReading,
                rotDegps?.let { "%+.0f".fmt(it * 60) } ?: "—",
                "°/min",
            )
        }
        MissingLine(snapshot.missing(card).filter { it != "yaw_deg" || !headingReading.present })
    }
}

@Composable
private fun CompassDial(heading: Float?, cog: Float?, up: Float, stale: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val course = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 12.sp, color = muted)
    Canvas(
        Modifier
            .size(200.dp)
            .readAsOneItem(
                "Compass, heading ${heading?.let(::formatBearing) ?: "unknown"}, " +
                    "course ${cog?.let(::formatBearing) ?: "unknown"}",
            ),
    ) {
        val r = size.minDimension / 2f - 18.dp.toPx()
        val c = center
        drawCircle(muted.copy(alpha = 0.4f), r, c, style = Stroke(1.dp.toPx()))
        // The card rotates, the lubber line at the top stays put — as on a real repeater.
        rotate(-up, c) {
            for (deg in 0 until 360 step 10) {
                val long = deg % 30 == 0
                val a = (deg.toDouble()).toRad()
                val outer = Offset(c.x + r * sin(a).toFloat(), c.y - r * cos(a).toFloat())
                val len = if (long) 12.dp.toPx() else 6.dp.toPx()
                val inner = Offset(c.x + (r - len) * sin(a).toFloat(), c.y - (r - len) * cos(a).toFloat())
                drawLine(muted, inner, outer, strokeWidth = if (long) 2f else 1f)
            }
            listOf(0 to "N", 90 to "E", 180 to "S", 270 to "W").forEach { (deg, text) ->
                val a = deg.toDouble().toRad()
                val p = Offset(c.x + (r + 10.dp.toPx()) * sin(a).toFloat(), c.y - (r + 10.dp.toPx()) * cos(a).toFloat())
                rotate(up, p) { centredText(measurer, text, p, labelStyle) }
            }
            if (cog != null) {
                val a = cog.toDouble().toRad()
                val tip = Offset(c.x + (r - 16.dp.toPx()) * sin(a).toFloat(), c.y - (r - 16.dp.toPx()) * cos(a).toFloat())
                drawLine(course, c, tip, strokeWidth = 3.dp.toPx())
            }
            if (heading != null) {
                val a = heading.toDouble().toRad()
                val tip = Offset(c.x + r * sin(a).toFloat(), c.y - r * cos(a).toFloat())
                drawLine(if (stale) ink.copy(alpha = 0.38f) else ink, c, tip, strokeWidth = 2.dp.toPx())
            }
        }
        // Lubber line.
        drawLine(ink, Offset(c.x, c.y - r - 4.dp.toPx()), Offset(c.x, c.y - r + 14.dp.toPx()), strokeWidth = 3.dp.toPx())
    }
}

private fun DrawScope.centredText(measurer: TextMeasurer, text: String, at: Offset, style: TextStyle) {
    val layout = measurer.measure(text, style)
    drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
}

// ── Rudder ───────────────────────────────────────────────────────────────────────────────────────────

@Composable
internal fun RudderCard(card: MonitorCard, snapshot: MonitorSnapshot) {
    val reading = snapshot.reading(card, "rudder")
    val max = card.num("maxAngle", 60.0).toFloat()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        SidedGauge(reading.value?.let { it / max }, reading.stale, "Rudder ${sided(reading.value)}")
        Figure("Rudder", reading, sided(reading.value), "")
        MissingLine(snapshot.missing(card))
    }
}

/**
 * A half-dial from port to starboard, needle hanging down from the top — a rudder indicator's own shape.
 * `fraction` is −1…1 of full scale, clamped so an over-range reading pins rather than wraps.
 */
@Composable
private fun SidedGauge(fraction: Float?, stale: Boolean, description: String) {
    val port = Color(0xFFD32F2F)
    val stbd = Color(0xFF2E7D32)
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(110.dp).readAsOneItem(description)) {
        val r = minOf(size.width / 2f, size.height) - 8.dp.toPx()
        val c = Offset(size.width / 2f, 6.dp.toPx())
        val box = Size(2 * r, 2 * r)
        val tl = Offset(c.x - r, c.y - r)
        // Port on the left, starboard on the right, as seen facing forward.
        drawArc(port.copy(alpha = 0.5f), 90f, 90f, false, tl, box, style = Stroke(6.dp.toPx()))
        drawArc(stbd.copy(alpha = 0.5f), 0f, 90f, false, tl, box, style = Stroke(6.dp.toPx()))
        if (fraction != null) {
            val a = (fraction.coerceIn(-1f, 1f) * 90.0).toRad()
            // Hanging down from the stock: starboard (positive) swings the needle to the right.
            val tip = Offset(c.x + r * sin(a).toFloat(), c.y + r * cos(a).toFloat())
            drawLine(if (stale) ink.copy(alpha = 0.38f) else ink, c, tip, strokeWidth = 3.dp.toPx())
        }
        drawCircle(ink, 4.dp.toPx(), c)
    }
}

// ── Engine ───────────────────────────────────────────────────────────────────────────────────────────

@Composable
internal fun EngineCard(card: MonitorCard, snapshot: MonitorSnapshot) {
    val rpm = snapshot.reading(card, "rpm")
    val pitch = snapshot.reading(card, "pitch")
    val maxRpm = card.num("maxRpm", 2000.0).toFloat()
    val bar = when (card.string("barSource")) {
        "pitch" -> BarSource.Pitch
        "rpm" -> BarSource.Rpm
        else -> if (pitch.present) BarSource.Pitch else BarSource.Rpm
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        when (bar) {
            BarSource.Pitch -> CentredBar(pitch.value?.let { it / 100f }, pitch.stale, "Pitch ${formatValue(pitch.value, 0)} percent")
            BarSource.Rpm -> FillBar(rpm.value?.let { it / maxRpm }, rpm.stale, "RPM ${formatValue(rpm.value, 0)}")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Figure("RPM", rpm, formatValue(rpm.value, 0), "rpm")
            Figure("Pitch", pitch, pitch.value?.let { "%+.0f".fmt(it) } ?: "—", "%")
        }
        MissingLine(snapshot.missing(card))
    }
}

private enum class BarSource { Pitch, Rpm }

/** A bar filling left to right, 0…1 of full scale. */
@Composable
private fun FillBar(fraction: Float?, stale: Boolean, description: String) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(18.dp).readAsOneItem(description)) {
        val radius = CornerRadius(4.dp.toPx())
        drawRoundRect(track, cornerRadius = radius)
        if (fraction != null) {
            val w = size.width * fraction.coerceIn(0f, 1f)
            drawRoundRect(if (stale) fill.copy(alpha = 0.38f) else fill, size = Size(w, size.height), cornerRadius = radius)
        }
    }
}

/** A bar growing from the centre, −1…1: astern/port to the left, ahead/starboard to the right. */
@Composable
private fun CentredBar(fraction: Float?, stale: Boolean, description: String) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(18.dp).readAsOneItem(description)) {
        val radius = CornerRadius(4.dp.toPx())
        drawRoundRect(track, cornerRadius = radius)
        val mid = size.width / 2f
        if (fraction != null) {
            val f = fraction.coerceIn(-1f, 1f)
            val w = mid * abs(f)
            val left = if (f >= 0) mid else mid - w
            drawRect(if (stale) fill.copy(alpha = 0.38f) else fill, Offset(left, 0f), Size(w, size.height))
        }
        drawLine(ink, Offset(mid, 0f), Offset(mid, size.height), strokeWidth = 2f)
    }
}

// ── Bow thruster ─────────────────────────────────────────────────────────────────────────────────────

@Composable
internal fun ThrusterCard(card: MonitorCard, snapshot: MonitorSnapshot) {
    val power = snapshot.reading(card, "power")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            AxisText("PORT")
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            AxisText("STBD")
        }
        CentredBar(power.value?.let { it / 100f }, power.stale, "Thruster ${formatValue(power.value, 0)} percent")
        Figure(
            "Thrust",
            power,
            power.value?.let { v -> if (abs(v) < 0.5f) "0" else "%.0f %s".fmt(abs(v), if (v > 0) "STBD" else "PORT") } ?: "—",
            "%",
            modifier = Modifier.fillMaxWidth(),
        )
        val subject = card.string("subject")
        if (!se.rise.logline.monitor.isKnownSubject(subject)) {
            Text(
                "$subject is not a keelson subject — pick the one this vessel publishes in the card's settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        } else {
            MissingLine(snapshot.missing(card))
        }
    }
}

// ── Wind ─────────────────────────────────────────────────────────────────────────────────────────────

@Composable
internal fun WindCard(card: MonitorCard, snapshot: MonitorSnapshot) {
    val angle = snapshot.reading(card, "angle")
    val speed = snapshot.reading(card, "speed")
    val trailMs = (card.num("trailS", 60.0) * 1000).toLong()
    val trail = snapshot.window(card, "angle").within(snapshot.nowMillis - trailMs, snapshot.nowMillis).values
        .let { if (it.size > 120) it.copyOfRange(it.size - 120, it.size) else it }
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val wind = MaterialTheme.colorScheme.primary
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Canvas(
            Modifier.size(170.dp).readAsOneItem("Apparent wind ${sided(angle.value, 0)}, ${formatValue(speed.value?.times(MPS_TO_KNOTS.toFloat()), 1)} knots"),
        ) {
            val r = size.minDimension / 2f - 8.dp.toPx()
            val c = center
            drawCircle(muted.copy(alpha = 0.4f), r, c, style = Stroke(1.dp.toPx()))
            // The hull, bow up: the angle is relative to the bow, so the boat never turns here.
            val hull = Path().apply {
                moveTo(c.x, c.y - r * 0.45f)
                quadraticTo(c.x + r * 0.2f, c.y, c.x + r * 0.15f, c.y + r * 0.4f)
                lineTo(c.x - r * 0.15f, c.y + r * 0.4f)
                quadraticTo(c.x - r * 0.2f, c.y, c.x, c.y - r * 0.45f)
                close()
            }
            drawPath(hull, muted, style = Stroke(1.5f.dp.toPx()))
            trail.forEachIndexed { i, a ->
                val t = (i + 1f) / trail.size
                val rad = a.toDouble().toRad()
                drawCircle(
                    wind.copy(alpha = 0.1f + 0.4f * t),
                    2.5f.dp.toPx(),
                    Offset(c.x + r * sin(rad).toFloat(), c.y - r * cos(rad).toFloat()),
                )
            }
            angle.value?.let { a ->
                val rad = a.toDouble().toRad()
                val rim = Offset(c.x + r * sin(rad).toFloat(), c.y - r * cos(rad).toFloat())
                val inner = Offset(c.x + r * 0.55f * sin(rad).toFloat(), c.y - r * 0.55f * cos(rad).toFloat())
                drawLine(if (angle.stale) ink.copy(alpha = 0.38f) else wind, rim, inner, strokeWidth = 4.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            // Folded to ±180 so it reads as a side, whichever convention the publisher used.
            val folded = angle.value?.let { ((it + 540f) % 360f) - 180f }
            Figure("AWA", angle, sided(folded, 0), "")
            Figure("AWS", speed, formatValue(speed.value?.times(MPS_TO_KNOTS.toFloat()), 1), "kn")
        }
        MissingLine(snapshot.missing(card))
    }
}

// ── Pitch & roll ─────────────────────────────────────────────────────────────────────────────────────

@Composable
internal fun PitchRollCard(card: MonitorCard, snapshot: MonitorSnapshot) {
    val windowMs = (card.num("windowS", 60.0) * 1000).toLong()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        AttitudeRow("Pitch", snapshot.reading(card, "pitch"), snapshot, card, "pitch", windowMs, card.num("maxPitchAdvice", 25.0).toFloat())
        AttitudeRow("Roll", snapshot.reading(card, "roll"), snapshot, card, "roll", windowMs, card.num("maxRollAdvice", 30.0).toFloat())
        MissingLine(snapshot.missing(card))
    }
}

/**
 * One attitude angle: its value, a bar against the advice limit either side, and the range it swept
 * over the window drawn as a band behind the needle. Amber past the advice — a limit, not an alarm.
 */
@Composable
private fun AttitudeRow(
    label: String,
    reading: Reading,
    snapshot: MonitorSnapshot,
    card: MonitorCard,
    role: String,
    windowMs: Long,
    advice: Float,
) {
    val w = snapshot.window(card, role).within(snapshot.nowMillis - windowMs, snapshot.nowMillis).values
    val lo = w.minOrNull()
    val hi = w.maxOrNull()
    val over = reading.value?.let { abs(it) > advice } == true
    val track = MaterialTheme.colorScheme.surfaceVariant
    val band = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val needle = if (over) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
    val scale = maxOf(advice * 1.2f, abs(lo ?: 0f), abs(hi ?: 0f))
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                reading.value?.let { "%+.1f°".fmt(it) } ?: "—",
                style = MaterialTheme.typography.headlineSmall,
                color = if (over) MaterialTheme.colorScheme.tertiary else readingColor(reading),
            )
        }
        Canvas(Modifier.fillMaxWidth().height(14.dp).readAsOneItem("$label ${reading.value ?: "unknown"} degrees")) {
            fun x(v: Float) = size.width / 2f + (v / scale).coerceIn(-1f, 1f) * size.width / 2f
            drawRect(track)
            if (lo != null && hi != null) drawRect(band, Offset(x(lo), 0f), Size(x(hi) - x(lo), size.height))
            listOf(-advice, advice).forEach {
                drawLine(Color(0xFFF9A825), Offset(x(it), 0f), Offset(x(it), size.height), strokeWidth = 2f)
            }
            reading.value?.let { drawLine(needle, Offset(x(it), 0f), Offset(x(it), size.height), strokeWidth = 3.dp.toPx()) }
        }
        Text(
            if (lo != null && hi != null) "range %+.1f° to %+.1f° · advice ±%.0f°".fmt(lo, hi, advice) else "advice ±%.0f°".fmt(advice),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Ship velocity ────────────────────────────────────────────────────────────────────────────────────

@Composable
internal fun ShipVelocityCard(card: MonitorCard, snapshot: MonitorSnapshot) {
    val heading = snapshot.reading(card, "heading")
    val cog = snapshot.reading(card, "cog")
    val sog = snapshot.reading(card, "sog")
    val sway = snapshot.reading(card, "sway")
    val rot = snapshot.reading(card, "rot")
    val offsets = bowSternOffsetsM(card.num("loa", 100.0))
    fun at(x: Double?) = x?.let {
        transverseAtKn(
            it,
            sway.fresh?.toDouble(),
            rot.fresh?.toDouble(),
            sog.fresh?.toDouble(),
            cog.fresh?.toDouble(),
            heading.fresh?.toDouble(),
        )
    }?.toFloat()
    val bow = at(offsets?.first)
    val stern = at(offsets?.second)
    val drift = driftAngleDeg(cog.fresh?.toDouble(), heading.fresh?.toDouble())?.toFloat()
    val derivedReading = Reading(null, bow, null, false)
    val sternReading = Reading(null, stern, null, false)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Figure("SOG", sog, formatValue(sog.value, 1), "kn")
            Figure("Drift", Reading(null, drift, null, false), sided(drift), "")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Figure("Bow sway", derivedReading, sidedKnots(bow), "kn")
            Figure("Stern sway", sternReading, sidedKnots(stern), "kn")
        }
        Text(
            if (sway.present) "Bow and stern are derived from sway and rate of turn." else "Bow and stern are estimated from drift and rate of turn — this vessel publishes no sway.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MissingLine(snapshot.missing(card).filter { it != "sway_velocity_mps" })
    }
}

private fun sidedKnots(v: Float?): String = when {
    v == null -> "—"
    abs(v) < 0.05f -> "0.0"
    v > 0 -> "%.1f ▶".fmt(v)
    else -> "◀ %.1f".fmt(-v)
}

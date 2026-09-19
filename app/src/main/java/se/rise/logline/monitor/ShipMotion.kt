package se.rise.logline.monitor

import se.rise.logline.publish.SampleWindow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Vessel motion as the conning cards derive it, transcribed from `foxglove-custom-panels`'
 * `conning/shipMotion.ts` so the phone and the Foxglove panel compute the same numbers from the same
 * inputs. Null in, null out throughout: a derived figure with a missing input is absent, not zero.
 */
const val MPS_TO_KNOTS = 1.0 / 0.514444
private const val RAD = PI / 180.0

/** COG − heading, folded into (−180, 180]. Positive means the vessel is moving to starboard of her head. */
fun driftAngleDeg(cogDeg: Double?, headingDeg: Double?): Double? {
    if (cogDeg == null || headingDeg == null) return null
    return (((cogDeg - headingDeg + 540.0) % 360.0) + 360.0) % 360.0 - 180.0
}

/** Bow and stern at ±LOA/2 from the reference point, or null for a length that is not one. */
fun bowSternOffsetsM(loaM: Double): Pair<Double, Double>? = if (loaM > 0) loaM / 2 to -loaM / 2 else null

/** Sway plus the yaw contribution at a point `xM` forward of the reference, in knots. */
fun transverseSpeedKn(swayMps: Double?, rotDegPerSec: Double?, xM: Double): Double? {
    if (swayMps == null || rotDegPerSec == null) return null
    return (swayMps + rotDegPerSec * RAD * xM) * MPS_TO_KNOTS
}

fun lateralFromDriftKn(sogKn: Double?, driftDeg: Double?): Double? {
    if (sogKn == null || driftDeg == null) return null
    return sogKn * sin(driftDeg * RAD)
}

fun longitudinalFromDriftKn(sogKn: Double?, driftDeg: Double?): Double? {
    if (sogKn == null || driftDeg == null) return null
    return sogKn * cos(driftDeg * RAD)
}

fun rotationalTransverseKn(rotDegPerSec: Double?, xM: Double): Double? =
    rotDegPerSec?.let { it * RAD * xM * MPS_TO_KNOTS }

/**
 * Transverse speed at `xM`: from sway where the vessel publishes it, otherwise estimated from the
 * drift angle and SOG. Either way it is **derived** — no keelson subject measures it — which the
 * card says in words.
 */
fun transverseAtKn(
    xM: Double,
    swayMps: Double?,
    rotDegPerSec: Double?,
    sogKn: Double?,
    cogDeg: Double?,
    headingDeg: Double?,
): Double? {
    if (swayMps != null) return transverseSpeedKn(swayMps, rotDegPerSec, xM)
    val lateral = lateralFromDriftKn(sogKn, driftAngleDeg(cogDeg, headingDeg)) ?: return null
    val turn = rotationalTransverseKn(rotDegPerSec, xM) ?: return null
    return lateral + turn
}

/**
 * Rate of turn in °/s derived from a heading series over the last `spanMillis`, for a vessel that
 * publishes `yaw_deg` but no `yaw_rate_degps`. The Foxglove heading panel does the same over 2 s.
 * Unwrapped across north first, or a turn through 000° reads as −359°/s.
 */
fun rotFromHeadings(window: SampleWindow, nowMillis: Long, spanMillis: Long = 2_000): Double? {
    if (window.size < 2) return null
    val newest = window.size - 1
    var oldest = newest
    while (oldest > 0 && window.timesMillis[oldest - 1] >= nowMillis - spanMillis) oldest--
    if (oldest == newest) oldest = newest - 1
    val dt = (window.timesMillis[newest] - window.timesMillis[oldest]) / 1000.0
    if (dt <= 0) return null
    var delta = (window.values[newest] - window.values[oldest]).toDouble()
    delta = ((delta + 540.0) % 360.0 + 360.0) % 360.0 - 180.0
    return delta / dt
}

/** Arithmetic mean of the samples in the last `spanMillis`, or the newest when the span is zero. */
fun meanOver(window: SampleWindow, nowMillis: Long, spanMillis: Long): Double? {
    if (window.isEmpty) return null
    if (spanMillis <= 0) return window.values.last().toDouble()
    val w = window.within(nowMillis - spanMillis, nowMillis)
    if (w.isEmpty) return window.values.last().toDouble()
    return w.values.average()
}

/** Circular mean of the last `spanMillis`, 0–360, or the newest when the span is zero. */
fun bearingOver(window: SampleWindow, nowMillis: Long, spanMillis: Long): Double? {
    if (window.isEmpty) return null
    if (spanMillis <= 0) return window.values.last().toDouble()
    val w = window.within(nowMillis - spanMillis, nowMillis)
    return (circularMeanDeg(if (w.isEmpty) floatArrayOf(window.values.last()) else w.values))?.toDouble()
}

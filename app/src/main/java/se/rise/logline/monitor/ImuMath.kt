package se.rise.logline.monitor

import se.rise.logline.publish.SampleWindow
import kotlin.math.sqrt

/** Exact by definition (CGPM 1901), not 9.81 — the constant the Foxglove IMU panel uses too. */
const val STANDARD_GRAVITY = 9.80665f

/**
 * How a sensor is mounted, as a mapping from its axes to the vessel's forward / starboard / down.
 *
 * Two presets rather than a free mapping, as in the Foxglove panel's defaults. `Logline` is this app's
 * own frame (a phone lying screen up, top edge forward: forward = +y, starboard = +x, down = −z) —
 * two axes swapped and one negated, i.e. a rotation, not a mirror. `Frd` is the marine convention.
 */
enum class ImuFrame {
    Logline, Frd;

    /** The three vessel axes of a vector window, named, in forward/starboard/down order. */
    fun vesselAxes(w: VectorWindow): List<Pair<String, SampleWindow>> = when (this) {
        Logline -> listOf("Forward" to w.y, "Starboard" to w.x, "Down" to w.z.negated())
        Frd -> listOf("Forward" to w.x, "Starboard" to w.y, "Down" to w.z)
    }
}

private fun SampleWindow.negated(): SampleWindow =
    SampleWindow(timesMillis, FloatArray(size) { -values[it] })

/** What the IMU card shows about acceleration. Every figure in g. */
data class ImuAnalysis(
    val totalG: Float,
    val dynamicG: Float,
    val peakDynamicG: Float,
    val gravityIncluded: Boolean,
    /** Recent (forward, starboard) dynamic acceleration, for the G-G diagram. */
    val gg: List<Pair<Float, Float>>,
    val ggLatest: Pair<Float, Float>?,
)

/**
 * Total, dynamic and peak acceleration from a vector window.
 *
 * **Gravity is detected, not assumed**, in `auto`: `linear_acceleration_mpss` is gravity-free by name
 * on some publishers and raw on others, and a mean magnitude near 1 g over the last two seconds is the
 * tell. Where it is included, the gravity vector is estimated as that two-second mean and subtracted,
 * which is the Foxglove panel's low-pass with its default two-second constant, done as a window mean
 * because the window is what the card has. Null when there is nothing to analyse.
 */
fun imuAnalysis(
    accel: VectorWindow,
    frame: ImuFrame,
    gravityMode: String,
    nowMillis: Long,
    peakMillis: Long,
    ggMillis: Long = 30_000,
): ImuAnalysis? {
    val n = minOf(accel.x.size, accel.y.size, accel.z.size)
    if (n == 0) return null
    val (fwd, stbd, down) = frame.vesselAxes(accel).map { it.second }
    val recentStart = firstIndexAtOrAfter(fwd, nowMillis - 2_000).coerceAtMost(n - 1)
    fun mean(w: SampleWindow, from: Int) = (from until n).map { w.values[it] }.average().toFloat()
    val gF = mean(fwd, recentStart)
    val gS = mean(stbd, recentStart)
    val gD = mean(down, recentStart)
    val meanMagnitude = sqrt(gF * gF + gS * gS + gD * gD)
    val included = when (gravityMode) {
        "included" -> true
        "removed" -> false
        else -> meanMagnitude > 0.5f * STANDARD_GRAVITY
    }
    fun dyn(i: Int, ref: FloatArray): FloatArray =
        if (included) floatArrayOf(fwd.values[i] - ref[0], stbd.values[i] - ref[1], down.values[i] - ref[2])
        else floatArrayOf(fwd.values[i], stbd.values[i], down.values[i])
    fun norm(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    val last = n - 1
    val total = norm(floatArrayOf(fwd.values[last], stbd.values[last], down.values[last])) / STANDARD_GRAVITY
    val recentRef = floatArrayOf(gF, gS, gD)
    val dynamic = norm(dyn(last, recentRef)) / STANDARD_GRAVITY

    val peakStart = firstIndexAtOrAfter(fwd, nowMillis - peakMillis).coerceAtMost(last)
    val peakRef = floatArrayOf(mean(fwd, peakStart), mean(stbd, peakStart), mean(down, peakStart))
    var peak = 0f
    for (i in peakStart..last) peak = maxOf(peak, norm(dyn(i, peakRef)) / STANDARD_GRAVITY)

    val ggStart = firstIndexAtOrAfter(fwd, nowMillis - ggMillis).coerceAtMost(last)
    val step = ((n - ggStart) / GG_MAX_POINTS).coerceAtLeast(1)
    val gg = (ggStart..last step step).map { i ->
        val d = dyn(i, recentRef)
        d[0] / STANDARD_GRAVITY to d[1] / STANDARD_GRAVITY
    }
    val latest = dyn(last, recentRef).let { it[0] / STANDARD_GRAVITY to it[1] / STANDARD_GRAVITY }
    return ImuAnalysis(total, dynamic, maxOf(peak, dynamic), included, gg, latest)
}

private const val GG_MAX_POINTS = 300

private fun firstIndexAtOrAfter(w: SampleWindow, t: Long): Int {
    var i = 0
    while (i < w.size && w.timesMillis[i] < t) i++
    return i
}

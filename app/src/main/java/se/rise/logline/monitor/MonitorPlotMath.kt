package se.rise.logline.monitor

import se.rise.logline.publish.SampleWindow

/**
 * A series binned into pixel columns **by time**, each with its min, max and mean; NaN where a column
 * holds no sample.
 *
 * Not `envelope()` from `ui/Sparkline.kt`, which bins by sample *index*. That is right for one series
 * whose samples arrive evenly, and wrong here: a Plot card draws up to three subjects that arrive at
 * different rates against one time axis, and index-binning would stretch a 1 Hz series and a 50 Hz
 * one to the same width regardless of when either was sampled. A gap in the stream stays a gap.
 */
class TimeColumns(val mins: FloatArray, val maxs: FloatArray, val means: FloatArray) {
    val size: Int get() = means.size
}

fun timeColumns(
    times: LongArray,
    values: FloatArray,
    fromMillis: Long,
    toMillis: Long,
    columns: Int,
): TimeColumns {
    val mins = FloatArray(columns) { Float.NaN }
    val maxs = FloatArray(columns) { Float.NaN }
    val sums = DoubleArray(columns)
    val counts = IntArray(columns)
    val span = (toMillis - fromMillis).coerceAtLeast(1L)
    for (i in times.indices) {
        val t = times[i]
        val v = values[i]
        if (t < fromMillis || t > toMillis || v.isNaN()) continue
        val c = (((t - fromMillis) * columns) / span).toInt().coerceIn(0, columns - 1)
        if (counts[c] == 0 || v < mins[c]) mins[c] = v
        if (counts[c] == 0 || v > maxs[c]) maxs[c] = v
        sums[c] += v
        counts[c]++
    }
    val means = FloatArray(columns) { if (counts[it] == 0) Float.NaN else (sums[it] / counts[it]).toFloat() }
    return TimeColumns(mins, maxs, means)
}

/** The part of a window inside `[fromMillis, toMillis]`, values unwrapped first when [circular]. */
fun SampleWindow.within(fromMillis: Long, toMillis: Long): SampleWindow {
    var start = 0
    while (start < size && timesMillis[start] < fromMillis) start++
    var end = size
    while (end > start && timesMillis[end - 1] > toMillis) end--
    return SampleWindow(timesMillis.copyOfRange(start, end), values.copyOfRange(start, end))
}

/**
 * Circular mean in degrees, 0–360, or null for no samples. A heading averaged arithmetically across
 * north reads south — the reason the smoothing settings on the Heading card go through this.
 */
fun circularMeanDeg(values: FloatArray): Float? {
    if (values.isEmpty()) return null
    var s = 0.0
    var c = 0.0
    for (v in values) {
        val r = Math.toRadians(v.toDouble())
        s += kotlin.math.sin(r)
        c += kotlin.math.cos(r)
    }
    if (s == 0.0 && c == 0.0) return null
    val deg = Math.toDegrees(kotlin.math.atan2(s, c))
    return ((deg + 360.0) % 360.0).toFloat()
}

/**
 * How many empty columns a line may bridge before it is a gap: four of the series' own median sample
 * intervals, and never less than two seconds. A replay paused for a minute is then a visible break,
 * while an ordinary 1 Hz series across a 500-column axis is still one line.
 */
fun gapColumns(times: LongArray, fromMillis: Long, toMillis: Long, columns: Int): Int {
    val msPerColumn = (toMillis - fromMillis).coerceAtLeast(1L).toDouble() / columns
    val intervals = (1 until times.size).map { times[it] - times[it - 1] }.filter { it > 0 }.sorted()
    val median = intervals.getOrNull(intervals.size / 2) ?: 1_000L
    val gapMs = maxOf(4 * median, 2_000L)
    return kotlin.math.ceil(gapMs / msPerColumn).toInt().coerceAtLeast(1)
}

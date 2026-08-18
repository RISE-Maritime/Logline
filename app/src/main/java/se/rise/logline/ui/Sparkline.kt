package se.rise.logline.ui

import se.rise.logline.publish.SampleWindow
import se.rise.logline.publish.TrackPoint

/**
 * The maths behind the live view, kept pure so it can be tested on the JVM.
 *
 * Same shape as [formatAge] in `Age.kt`: everything the result depends on is a parameter, nothing is
 * read from the environment. Drawing code that computes its own bounds is drawing code that can only be
 * checked by looking at it.
 */

/** A sparkline's y-axis. Never zero-height, so a flat series still draws a line rather than dividing by zero. */
data class Bounds(val min: Float, val max: Float) {
    val span: Float get() = max - min
}

/**
 * Y-axis bounds for a window.
 *
 * A perfectly flat series — a stationary phone's magnetometer, a battery that has not moved — has zero
 * span, so it is padded to a symmetric band around the value. Without that the line would sit on the
 * top edge, or the renderer would divide by zero.
 */
internal fun boundsOf(values: FloatArray): Bounds? {
    if (values.isEmpty()) return null
    var min = values[0]
    var max = values[0]
    for (v in values) {
        if (v.isNaN()) continue
        if (v < min) min = v
        if (v > max) max = v
    }
    if (!min.isFinite() || !max.isFinite()) return null
    if (min == max) {
        val pad = if (min == 0f) 1f else kotlin.math.abs(min) * 0.05f
        return Bounds(min - pad, max + pad)
    }
    return Bounds(min, max)
}

/**
 * The range to *draw* against, which is not always the range of the data.
 *
 * A stationary barometer varies by about two pascals in a hundred kilopascals — 0.002% — and scaling
 * that to the height of a card turns a rock-steady sensor into a seismograph. The reading is stable;
 * the axis was lying about it. Anything varying by less than [MIN_RELATIVE_SPAN] of its own magnitude
 * is therefore drawn against a floor, so it renders as the flat line it is.
 *
 * Note this is deliberately *relative*. A gyroscope at rest has a mean near zero and noise several
 * times that mean, so its noise is the whole signal and stays visible; the barometer's is a rounding
 * error on a large number and does not.
 *
 * The footer keeps showing [boundsOf] — the true minimum and maximum — because that is a measurement,
 * while this is a drawing decision.
 */
internal fun plotBounds(bounds: Bounds): Bounds {
    val centre = (bounds.min + bounds.max) / 2f
    val minimumSpan = kotlin.math.abs(centre) * MIN_RELATIVE_SPAN
    if (minimumSpan <= 0f || bounds.span >= minimumSpan) return bounds
    val half = minimumSpan / 2f
    return Bounds(centre - half, centre + half)
}

/** One percent of the value: below this a series is flat for any purpose an operator has. */
private const val MIN_RELATIVE_SPAN = 0.01f

/**
 * What one pixel column of a plot contains: the extremes actually sampled there, and their mean.
 *
 * Drawn as a band with a line through it — the band is every sample, so a spike cannot hide, and the
 * line is the running average that makes the trend readable through the noise.
 */
data class Envelope(val mins: FloatArray, val maxs: FloatArray, val means: FloatArray) {
    val size: Int get() = mins.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Envelope) return false
        return mins.contentEquals(other.mins) &&
            maxs.contentEquals(other.maxs) &&
            means.contentEquals(other.means)
    }

    override fun hashCode(): Int =
        (mins.contentHashCode() * 31 + maxs.contentHashCode()) * 31 + means.contentHashCode()
}

/**
 * Reduce a series to [columns] bins, keeping every sample's contribution.
 *
 * This replaces stride sampling, and the difference matters at high rates. A two-minute window of a
 * 55 Hz sensor is 6600 samples in maybe 300 pixels: taking one sample in twenty-two throws away
 * twenty-one, and *which* one it keeps shifts as the window slides, so the line dances frame to frame
 * even when the sensor is steady. Binning is deterministic — bin boundaries depend only on the sample
 * count — and no sample is discarded, so a spike is always somebody's maximum.
 */
internal fun envelope(values: FloatArray, columns: Int): Envelope? {
    if (values.isEmpty() || columns <= 0) return null
    val n = values.size
    // Fewer samples than columns: one bin each, and the band collapses to the line itself.
    if (n <= columns) return Envelope(values.copyOf(), values.copyOf(), values.copyOf())

    val mins = FloatArray(columns)
    val maxs = FloatArray(columns)
    val means = FloatArray(columns)
    for (c in 0 until columns) {
        val start = (c.toLong() * n / columns).toInt()
        val end = ((c + 1).toLong() * n / columns).toInt().coerceAtLeast(start + 1).coerceAtMost(n)
        var lo = values[start]
        var hi = values[start]
        var sum = 0.0
        for (i in start until end) {
            val v = values[i]
            if (v < lo) lo = v
            if (v > hi) hi = v
            sum += v
        }
        mins[c] = lo
        maxs[c] = hi
        means[c] = (sum / (end - start)).toFloat()
    }
    return Envelope(mins, maxs, means)
}

/**
 * Normalise a value to 0..1 within [bounds], where 0 is the *bottom* of the plot.
 *
 * Callers flip it for screen coordinates; keeping the maths in value-space makes the expectations
 * readable in the test.
 */
internal fun normalise(value: Float, bounds: Bounds): Float =
    if (bounds.span <= 0f) 0.5f else ((value - bounds.min) / bounds.span).coerceIn(0f, 1f)

/** Whether a window has enough distinct points to draw a line at all. */
internal fun SampleWindow.isPlottable(): Boolean = size >= 2

/**
 * The bounding box of a track, or null when there is nothing to show.
 *
 * Used to frame the map on the whole track rather than only the newest fix.
 */
internal fun trackBounds(track: List<TrackPoint>): ClosedRange<Double>? {
    if (track.isEmpty()) return null
    var minLat = track[0].latitude
    var maxLat = track[0].latitude
    for (p in track) {
        if (p.latitude < minLat) minLat = p.latitude
        if (p.latitude > maxLat) maxLat = p.latitude
    }
    return minLat..maxLat
}

package se.rise.logline.ui

import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.SourceKind
import se.rise.logline.keelson.RadioSources
import se.rise.logline.keelson.Subjects
import se.rise.logline.publish.SampleWindow
import kotlin.math.roundToInt

/**
 * Angles that wrap, and therefore cannot be plotted as ordinary numbers.
 *
 * `magnetic_variation_deg` is deliberately not one: declination is a small signed offset — about +5° in
 * Swedish waters — and never crosses zero the way a heading does.
 */
fun isCircularDegrees(entry: PublishedSubject): Boolean = when (entry.subject) {
    Subjects.COURSE_OVER_GROUND_DEG,
    Subjects.HEADING_MAGNETIC_DEG,
    Subjects.HEADING_TRUE_NORTH_DEG -> true
    else -> false
}

/**
 * Make a series of compass angles continuous, so a plot shows the turn rather than the wrap.
 *
 * A heading crossing north goes 359 → 1, which as plain numbers is a fall of 358 — a full-height cliff
 * on a sparkline for a two-degree change, and it reads as a data fault rather than a boat turning.
 * Adding whole turns keeps every step within ±180°, so the same crossing draws as +2.
 *
 * The output leaves the 0–360 range on purpose: it is for drawing, never for publishing. The value
 * shown beside the plot still comes from the raw sample.
 *
 * **Run this before the plot is binned.** A slow turn survives either order, but binning first mixes
 * 359° and 1° into one bin, whose minimum and maximum then span the whole circle — the band fills the
 * card and says nothing. Unwrapping the full-rate samples first has no such ambiguity, because a real
 * compass never moves 180° between consecutive readings.
 */
fun unwrapAngles(values: FloatArray): FloatArray {
    if (values.size < 2) return values
    val out = FloatArray(values.size)
    out[0] = values[0]
    var offset = 0f
    for (i in 1 until values.size) {
        val step = values[i] - values[i - 1]
        // Round rather than truncate: a step of exactly 180 is ambiguous, and either turn is a
        // defensible reading of it.
        offset -= (step / 360f).roundToInt() * 360f
        out[i] = values[i] + offset
    }
    return out
}

/**
 * The last [seconds] of a window.
 *
 * Binary search rather than a scan: this runs for every subject on every frame, and the arrays hold
 * thousands of samples. `timesMillis` is sorted by construction — the ring is append-only — so the cut
 * point is a `binarySearch` away.
 */
fun windowedTo(window: SampleWindow, seconds: Int, nowMillis: Long): SampleWindow {
    if (window.isEmpty || seconds <= 0) return SampleWindow()
    val cutoff = nowMillis - seconds * 1_000L
    val found = window.timesMillis.binarySearch(cutoff)
    // binarySearch returns the insertion point as -(index + 1) when there is no exact match, which is
    // exactly the first sample newer than the cutoff.
    val start = if (found >= 0) found else -(found + 1)
    if (start >= window.size) return SampleWindow()
    if (start == 0) return window
    return SampleWindow(
        timesMillis = window.timesMillis.copyOfRange(start, window.size),
        values = window.values.copyOfRange(start, window.size),
    )
}

/** How fast the plotted window is actually arriving, or null when there is too little to say. */
fun windowRateHz(window: SampleWindow): Double? {
    if (window.size < 2) return null
    val spanMillis = window.timesMillis.last() - window.timesMillis.first()
    if (spanMillis <= 0L) return null
    // n samples span n-1 intervals; using n overstates a short window.
    return (window.size - 1) * 1_000.0 / spanMillis
}

/** A bearing as a person says it: `128°` is `SE`. Sixteen points is more precision than a phone has. */
fun cardinal(degrees: Float): String {
    val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val normalised = ((degrees % 360f) + 360f) % 360f
    // Each point spans 45°, centred on its own bearing — so N runs from 337.5 to 22.5.
    return points[(((normalised + 22.5f) / 45f).toInt()) % points.size]
}

/** What a GNSS accuracy figure means to someone deciding whether to trust the track. */
enum class FixQuality { Good, Fair, Poor, Unknown }

/**
 * Thresholds chosen for small-boat work: a few metres is good enough to see which side of a jetty you
 * passed, fifteen is enough to know which basin you are in, and beyond that the track is an outline.
 */
fun gnssQuality(accuracyMetres: Float?): FixQuality = when {
    accuracyMetres == null -> FixQuality.Unknown
    accuracyMetres <= 5f -> FixQuality.Good
    accuracyMetres <= 15f -> FixQuality.Fair
    else -> FixQuality.Poor
}

/**
 * The one number a collapsed section is worth showing.
 *
 * "3 plots" tells an operator nothing. What they want from a folded group is the number they would have
 * opened it for: how good the fix is, whether the IMU is keeping up, how much battery is left, how the
 * radio is doing. Rates and values are passed in as lookups rather than read here, so this stays a pure
 * function over the registry and can be tested without a running publisher.
 */
fun featuredValue(
    group: SubjectGroup,
    latest: (PublishedSubject) -> Float?,
    rateHz: (PublishedSubject) -> Double?,
    accuracyMetres: Float?,
    // Keyed on what the group *is*, not on what it is called. The titles are display strings — one
    // rename ("GNSS" to "Position", say) used to silently empty every one of these headline values,
    // with nothing failing to say so.
): String? = when {
    group.source == SourceKind.LOCATION -> accuracyMetres?.let { "±${it.roundToInt()} m" }
    // The IMU's headline is how fast it is running, taken from one representative sensor — the sum
    // across seven subjects would read as an implausible 390 Hz.
    group.source == SourceKind.IMU -> rateHz(PublishedSubject.LINEAR_ACCEL)?.let { "${formatRate(it)} Hz" }
    group.source == SourceKind.DEVICE ->
        latest(PublishedSubject.BATTERY_STATE_OF_CHARGE)?.let { "${it.roundToInt()} %" }
    group.sourceId == RadioSources.CELLULAR ->
        latest(PublishedSubject.CELLULAR_SINR)?.let { "SINR ${it.roundToInt()} dB" }
    group.sourceId == RadioSources.WIFI -> latest(PublishedSubject.WIFI_RSSI)?.let { "${it.roundToInt()} dBm" }
    // How many sensors the published geometry describes. `configuration_json` carries that count as
    // its sample value precisely so this line has something true to say.
    group.source == SourceKind.CALIBRATION ->
        latest(PublishedSubject.CONFIGURATION_JSON)?.let { "${it.roundToInt()} sensors" }
    else -> null
}

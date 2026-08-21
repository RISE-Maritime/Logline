package se.rise.logline.ui

import se.rise.logline.record.TrackFix
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * How big a track actually is, and the smallest area worth drawing it in.
 *
 * **A track's own bounding box is the wrong thing to scale a chart to**, and this is the same lesson
 * `plotBounds()` in [Sparkline] learned about the barometer: an axis fitted to the data's own range
 * turns noise into a full-height signal. Measured on three recordings off the dev phone, every one of
 * them a phone sitting indoors — figures are the longer axis, which is what this returns: a
 * **three-hour** run of 1 843 fixes spans **10.6 m**, a 47-fix run spans **14.8 m**, and a 17-fix run
 * spans **1.8 m**. Fitted to their own extents all three drew as vigorous voyages across the card,
 * when what they record is a phone that never moved.
 */

/**
 * The larger of the track's north-south and east-west extents, in metres.
 *
 * Longitude is scaled by `cos(latitude)` — 0.54 at 57°N — because a degree of longitude is that much
 * shorter than a degree of latitude. The same correction the accuracy circle makes through
 * `metersToPixels`, and the reason a track drawn on raw degrees comes out nearly twice as wide as it
 * was sailed.
 */
fun trackExtentMetres(fixes: List<TrackFix>): Double {
    if (fixes.size < 2) return 0.0
    val latitudes = fixes.map { it.latitude }
    val longitudes = fixes.map { it.longitude }
    val midLatitude = (latitudes.min() + latitudes.max()) / 2.0
    val northSouth = abs(latitudes.max() - latitudes.min()) * METRES_PER_DEGREE
    val eastWest = abs(longitudes.max() - longitudes.min()) *
        METRES_PER_DEGREE * cos(Math.toRadians(midLatitude))
    return max(northSouth, eastWest)
}

/**
 * The smallest square a track chart will draw, in metres.
 *
 * **This is a floor on the chart, never on the data** — the fixes are drawn where they were, and a
 * track smaller than this simply occupies a small part of the frame instead of being stretched across
 * it. That is the honest rendering: 13 m of scatter *is* a small thing.
 *
 * 200 m is about thirteen times the widest scatter measured here (14.8 m) and comfortably above a
 * single fix's own horizontal accuracy, which runs to ten metres or so. Anything genuinely under way
 * clears it in seconds, so a real trial still fills the frame.
 */
const val MIN_CHART_SPAN_METRES = 200.0

/** Whether a track is smaller than the chart's floor, i.e. scatter rather than travel. */
fun isStationary(extentMetres: Double) = extentMetres < MIN_CHART_SPAN_METRES

/**
 * A distance as a figure, never a verdict — `13 m`, `1.2 km`.
 *
 * The same stance the live view takes with `13 dB` over "Good": the reader is the one who knows whether
 * thirteen metres matters, and a word chosen here would be wrong for half of them.
 */
fun formatDistance(metres: Double): String = when {
    metres < 1_000.0 -> "%.0f m".fmt(metres)
    else -> "%.1f km".fmt(metres / 1_000.0)
}

/**
 * What the chart's footer says about a track.
 *
 * **The preposition carries the finding.** "1 843 positions within 13 m" is a phone that did not move;
 * "47 positions over 1.2 km" is a passage. Neither states a verdict, and the count alone — which is all
 * this used to say — cannot tell them apart: 1 843 positions sounds like a voyage either way.
 */
fun trackSummary(count: Int, extentMetres: Double): String {
    val positions = "${formatCount(count.toLong())} positions"
    return when {
        count < 2 -> positions
        isStationary(extentMetres) -> "$positions within ${formatDistance(extentMetres)}"
        else -> "$positions over ${formatDistance(extentMetres)}"
    }
}

/** Metres in a degree of latitude, which is close enough to constant for a chart this size. */
private const val METRES_PER_DEGREE = 111_320.0

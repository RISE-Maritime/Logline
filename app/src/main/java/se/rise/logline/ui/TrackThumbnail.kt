package se.rise.logline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import se.rise.logline.record.TrackFix
import kotlin.math.cos
import kotlin.math.max

/** How a row's track is coming along. Three of these would otherwise be the same blank square. */
sealed interface ThumbnailState {
    /** Not looked at yet — the row has only just come on screen, or a run is recording. */
    data object Unread : ThumbnailState

    /** Being read. The whole recording has to be decompressed to find the fixes. */
    data object Reading : ThumbnailState

    /** Read, and there is no track: no fix channel, or too few fixes to draw one. */
    data object NoTrack : ThumbnailState

    data class Ready(val fixes: List<TrackFix>) : ThumbnailState
}

/** The side of the square. Big enough for a shape, small enough to leave the row a row. */
val THUMBNAIL_SIZE = 64.dp

/**
 * A recording's track, small enough to sit on its row.
 *
 * **Deliberately not [RecordingChart].** That one owns a `MapView`, and a `MapView` owns tile threads
 * and a tile cache; sixty-seven of them in a `LazyColumn` is not a thing to do. So this shows shape and
 * scale but not place — which is what tells two runs apart in a list, and place is one tap away.
 *
 * The canvas is the one this app had before the detail screen moved to real tiles; it came back for
 * exactly the job it was originally written for.
 */
@Composable
fun TrackThumbnail(state: ThumbnailState, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val description = when (state) {
        ThumbnailState.Unread -> "Track not read yet"
        ThumbnailState.Reading -> "Reading the track"
        ThumbnailState.NoTrack -> "No positions in this recording"
        is ThumbnailState.Ready -> "Track, ${state.fixes.size} positions"
    }

    Box(modifier.size(THUMBNAIL_SIZE).semantics { contentDescription = description }) {
        Canvas(Modifier.size(THUMBNAIL_SIZE)) {
            when (state) {
                // Nothing at all: the row has not been looked at, and a placeholder that looked like a
                // result would be a claim about a recording nobody has read.
                ThumbnailState.Unread -> Unit

                ThumbnailState.Reading -> drawCircle(
                    color = quiet.copy(alpha = 0.25f),
                    radius = size.minDimension / 4f,
                    style = Stroke(width = 2f),
                )

                // **Known to have none**, which is not the same as not looked at — hence a mark rather
                // than a blank. A dash, because a dot would read as a stationary run.
                ThumbnailState.NoTrack -> drawLine(
                    color = quiet.copy(alpha = 0.35f),
                    start = Offset(size.width * 0.35f, size.height / 2f),
                    end = Offset(size.width * 0.65f, size.height / 2f),
                    strokeWidth = 2f,
                )

                is ThumbnailState.Ready -> drawTrack(state.fixes, line, quiet)
            }
        }
    }
}

/**
 * The track, scaled the way the detail chart scales it.
 *
 * Two corrections, both load-bearing and both already argued elsewhere in this app. Longitude is scaled
 * by `cos(latitude)`, or a track comes out nearly twice as wide as it was sailed. And the drawn span is
 * floored at [MIN_CHART_SPAN_METRES]: without that, every stationary recording on this phone — 10.6 m
 * over three hours, 1.8 m over two minutes — fills the square with a vigorous scribble, and a list of
 * sixty-seven of them all looks the same. With it, a run that did not move is a small knot and a run
 * that did is a shape.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrack(
    fixes: List<TrackFix>,
    line: androidx.compose.ui.graphics.Color,
    start: androidx.compose.ui.graphics.Color,
) {
    if (fixes.size < 2) return
    // **Below the floor there is no shape to draw, so say so with a mark instead of drawing noise.**
    // At 64dp a 200 m frame renders a 10 m scatter about three pixels across — the honest rendering of
    // a run that did not move, and an invisible one. The detail chart has 220dp and can afford to show
    // the knot; here the useful information is the fact itself, which a marker states and three pixels
    // do not. It is drawn in the track's own colour, not the muted one, because this *is* a track: the
    // greyed dash means the recording has no positions at all, which is a different answer.
    if (isStationary(trackExtentMetres(fixes))) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color = line, radius = size.minDimension / 5f, style = Stroke(width = 2f), center = centre)
        drawCircle(color = line, radius = 3f, center = centre)
        return
    }
    val pad = 6f
    val points = thumbnailOffsets(fixes, size.minDimension - pad * 2, pad)

    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    }
    drawPath(path, color = line, style = Stroke(width = 2f))
    // Where it began, so a there-and-back track is not ambiguous about which end is which.
    drawCircle(color = start, radius = 3f, center = points[0])
}

/**
 * The track's fixes as pixels in a square of [extent], centred and to one scale.
 *
 * Pure so the two corrections in it can be tested, since neither is visible in a 64dp square until it
 * is wrong: **longitude is scaled by `cos(latitude)`** — 0.54 at 57°N, without which a track comes out
 * nearly twice as wide as it was sailed — and **both axes share one scale**, so the shape is the shape
 * rather than stretched to fill the frame.
 *
 * The span is floored at [MIN_CHART_SPAN_METRES], the same number the detail chart's bounding box uses,
 * so a small track occupies a small part of the square instead of being blown up into a scribble.
 */
internal fun thumbnailOffsets(fixes: List<TrackFix>, extent: Float, pad: Float): List<Offset> {
    val latitudes = fixes.map { it.latitude }
    val longitudes = fixes.map { it.longitude }
    val midLatitude = (latitudes.min() + latitudes.max()) / 2.0
    val scale = cos(Math.toRadians(midLatitude)).coerceAtLeast(0.01)
    val centreX = (longitudes.min() + longitudes.max()) / 2.0 * scale
    val centreY = (latitudes.min() + latitudes.max()) / 2.0
    val span = max(trackExtentMetres(fixes), MIN_CHART_SPAN_METRES) / METRES_PER_DEGREE

    return fixes.indices.map { i ->
        Offset(
            x = pad + (((longitudes[i] * scale - centreX) / span + 0.5) * extent).toFloat(),
            // Screen y grows downwards; north is up.
            y = pad + extent - (((latitudes[i] - centreY) / span + 0.5) * extent).toFloat(),
        )
    }
}

/** Metres in a degree of latitude, close enough to constant at this scale. */
private const val METRES_PER_DEGREE = 111_320.0

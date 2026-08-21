package se.rise.logline.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import se.rise.logline.record.TrackFix
import kotlin.math.cos

/**
 * A finished recording's track, on a chart.
 *
 * Deliberately **not** [TrackMap], which exists to follow a fix that is still arriving: it owns a
 * follow mode, a heading vector, a course vector and a live position, and none of those mean anything
 * about a file that closed hours ago. What the two do share — the tile sources, the base-layer ink, the
 * attribution treatment, the osmdroid configuration — is shared as functions rather than by making one
 * component serve both.
 *
 * **The view is fitted to the track's extent, floored at [MIN_CHART_SPAN_METRES].** Without the floor
 * the chart zooms to whatever the fixes happen to span, and since a stationary phone's fixes span a
 * dozen metres of GNSS scatter, it would open at maximum zoom on a patch of ground the size of a room
 * and draw that scatter as a voyage across it. See [trackExtentMetres] for the measurements.
 */
@Composable
fun RecordingChart(
    fixes: List<TrackFix>,
    modifier: Modifier = Modifier,
    layer: MapLayer = MapLayer.Standard,
    offlineOnly: Boolean = false,
    mapTilerKey: String = "",
) {
    val line = remember { Polyline() }
    // Drawn under the track, and the reason is the same one the live chart states: a dark blue line is
    // legible on pale map tiles and nearly gone on imagery, and there is no one colour that works on
    // both. With a halo the contrast comes from the drawing rather than from the background.
    val halo = remember { Polyline() }
    val ends = remember { EndsOverlay() }
    val tileSource = remember(layer, mapTilerKey) { sourceFor(layer, mapTilerKey) }
    val box = remember(fixes) { boundsOf(fixes) }

    AndroidView(
        // osmdroid paints its background across the whole canvas and, being a plain View inside a
        // scrolling Column, will otherwise draw over its neighbours.
        modifier = modifier.clipToBounds(),
        factory = { context ->
            configureOsmdroid(context)
            MapView(context).apply {
                setTileSource(tileSource)
                setUseDataConnection(!offlineOnly)
                setMultiTouchControls(true)
                // A condition of both OSM's and Esri's terms, and osmdroid does not draw it by itself.
                overlays.add(CopyrightOverlay(context).also { it.setTextSize(ATTRIBUTION_TEXT_DP) })
                halo.outlinePaint.color = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
                halo.outlinePaint.strokeWidth = TRACK_PX + TRACK_HALO_PX
                line.outlinePaint.color = Color.rgb(0x3F, 0x6F, 0xD8)
                line.outlinePaint.strokeWidth = TRACK_PX
                // Halo first, then the line, then the ends on top of both.
                overlays.add(halo)
                overlays.add(line)
                overlays.add(ends)
                // osmdroid starts its tile threads here, and `AndroidView` does not forward lifecycle.
                // Without this the map is a blank grid, which reads as broken rather than as loading.
                onResume()
            }
        },
        update = { map ->
            map.setTileSource(tileSource)
            map.setUseDataConnection(!offlineOnly)
            map.maxZoomLevel = maxZoomFor(tileSource)
            val points = fixes.map { GeoPoint(it.latitude, it.longitude) }
            halo.setPoints(points)
            line.setPoints(points)
            ends.points = points
            ends.ink = chartInk(layer)
            map.overlays.filterIsInstance<CopyrightOverlay>()
                .forEach { it.setTextColor(attributionColour(layer)) }
            // **`zoomToBoundingBox` is a no-op before the view has been laid out**, which is exactly
            // when `update` first runs — the same trap the live chart records for `animateTo`. Asking
            // on the first layout is what makes the chart open on the track rather than on the
            // placeholder centre.
            if (box != null) {
                if (map.width > 0 && map.height > 0) {
                    map.zoomToBoundingBox(box, false, CHART_PADDING_PX)
                } else {
                    map.addOnFirstLayoutListener { _, _, _, _, _ ->
                        map.zoomToBoundingBox(box, false, CHART_PADDING_PX)
                    }
                }
            }
            map.invalidate()
        },
        // A MapView owns tile threads and a tile cache. The live chart releases them the same way, and
        // this screen is pushed and popped far more often than that one is.
        onRelease = { map ->
            map.onPause()
            map.onDetach()
        },
    )
}

/**
 * Where the track starts and where it ends.
 *
 * On a bare canvas this was the only thing telling a there-and-back track which end was which. It still
 * is — the map adds ground under the line, not a direction to it.
 */
private class EndsOverlay : Overlay() {
    var points: List<GeoPoint> = emptyList()
    var ink: Int = Color.BLACK

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        val first = points.firstOrNull() ?: return
        val last = points.last()
        // The end is drawn first so the start sits on top where a stationary track puts them together.
        dot(canvas, projection, last, filled = false)
        dot(canvas, projection, first, filled = true)
    }

    private fun dot(canvas: Canvas, projection: Projection, at: GeoPoint, filled: Boolean) {
        val point = projection.toPixels(at, null)
        val x = point.x.toFloat()
        val y = point.y.toFloat()
        canvas.drawCircle(x, y, END_RADIUS_PX, edge)
        fill.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
        fill.strokeWidth = 3f
        fill.color = ink
        canvas.drawCircle(x, y, END_RADIUS_PX, fill)
    }
}

/**
 * The square the chart opens on: the track's own extent, floored at [MIN_CHART_SPAN_METRES] and
 * centred on the track.
 *
 * Square rather than the bounding box's own shape because osmdroid fits the *whole* box into the view,
 * so a box one metre tall and two hundred wide would still zoom to the metre.
 */
private fun boundsOf(fixes: List<TrackFix>): BoundingBox? {
    if (fixes.isEmpty()) return null
    val latitudes = fixes.map { it.latitude }
    val longitudes = fixes.map { it.longitude }
    val centreLatitude = (latitudes.min() + latitudes.max()) / 2.0
    val centreLongitude = (longitudes.min() + longitudes.max()) / 2.0
    val span = maxOf(trackExtentMetres(fixes), MIN_CHART_SPAN_METRES)
    val halfLatitude = span / 2.0 / 111_320.0
    // Guarded because `cos` reaches zero at the poles, where a longitude span in metres stops meaning
    // anything. Nothing this app records goes there, and a division by zero would still be a crash.
    val halfLongitude = span / 2.0 /
        (111_320.0 * cos(Math.toRadians(centreLatitude)).coerceAtLeast(0.01))
    return BoundingBox(
        centreLatitude + halfLatitude,
        centreLongitude + halfLongitude,
        centreLatitude - halfLatitude,
        centreLongitude - halfLongitude,
    )
}

/** Keeps the track off the very edge of the frame, and clear of the attribution notice. */
private const val CHART_PADDING_PX = 24

private const val END_RADIUS_PX = 7f

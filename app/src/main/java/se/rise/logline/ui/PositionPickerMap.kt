package se.rise.logline.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Overlay
import kotlin.math.sin
import kotlin.math.hypot
import kotlin.math.cos

/**
 * A map you pan under a crosshair to put a position down.
 *
 * **The first map in this app that is read as well as written.** Every other one takes data and
 * produces pixels; this one reports where its centre is, through [onCentre], so the screen above it
 * can show the coordinates live and put them down on a tap. The crosshair itself is deliberately
 * *not* here — it is fixed in screen space, needs no projection, and is a Compose `Box` centred over
 * this composable by the screen that owns it.
 *
 * A third sibling to [TrackMap] and [RecordingChart] rather than a generalisation of either. Those
 * two already declined to merge — their overlays, their gestures and their reasons for existing are
 * different — and a picker has less in common with either than they have with each other: no track,
 * no follow mode, no live fix, and the one thing neither of them does at all.
 *
 * What it shares with them, as functions rather than by inheritance: [configureOsmdroid],
 * [sourceFor], [maxZoomFor], [attributionColour] and the `CopyrightOverlay`, which osmdroid does not
 * draw by itself and both OSM's and Esri's terms require.
 *
 * @param start where to open, or null for [HOME_CENTRE] — the same fallback the live chart uses, and
 *   for the same reason: with no centre set osmdroid opens on the Gulf of Guinea, which the
 *   satellite layer covers with a grid reading "Map data not yet available".
 * @param existing the zero point as it stands, drawn as a dot so somebody can see where they are
 *   moving it *from*. Null when there is none yet.
 * @param onCentre called as the map moves, with the centre it settled on.
 */
@Composable
fun PositionPickerMap(
    start: GeoPoint?,
    existing: GeoPoint?,
    onCentre: (latitude: Double, longitude: Double) -> Unit,
    modifier: Modifier = Modifier,
    layer: MapLayer = MapLayer.Satellite,
    offlineOnly: Boolean = false,
    mapTilerKey: String = "",
    /**
     * False for the card's preview, which sits in a scrolling column.
     *
     * Gestures off rather than a transparent view over the top: osmdroid would still receive the
     * touches and the page would still lose some of them, and a map that pans a little when you meant
     * to scroll is worse than one that plainly does not pan.
     */
    interactive: Boolean = true,
    /**
     * The point a forward axis is measured **from**, when this map is being used for one.
     *
     * Null for the zero-point picker, which is placing a point rather than an angle.
     */
    anchor: GeoPoint? = null,
    /** The heading to draw from [anchor], true degrees. Null draws no ray. */
    bearingDeg: Double? = null,
) {
    // Keyed, because `setTileSource` compares by identity — a fresh instance every recomposition
    // would swap the source and throw away its tile cache several times a second.
    val tileSource = remember(layer, mapTilerKey) { sourceFor(layer, mapTilerKey) }
    val marker = remember { ExistingZeroOverlay() }
    val axis = remember { ForwardAxisOverlay() }
    val copyright = remember { mutableRefOf<CopyrightOverlay>() }
    // Guards the opening centre. Setting it on every update would haul the map back under the
    // crosshair each time the layer changed, which is the one thing a picker must never do.
    val opened = remember { mutableRefOf<Boolean>() }
    // Whether the person has actually moved the map, as against osmdroid telling us where it put
    // itself. See [MOVED_THRESHOLD_M].
    val moved = remember { mutableRefOf<Boolean>() }

    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { context ->
            configureOsmdroid(context)
            MapView(context).apply {
                setTileSource(tileSource)
                setUseDataConnection(!offlineOnly)
                overlays.add(
                    CopyrightOverlay(context).also {
                        it.setTextSize(ATTRIBUTION_TEXT_DP)
                        copyright.value = it
                    }
                )
                setMultiTouchControls(interactive)
                isClickable = interactive
                // Under the marker, so the dot stays readable where the ray starts.
                overlays.add(axis)
                overlays.add(marker)
                controller.setZoom(if (start == null) HOME_ZOOM else PICK_ZOOM)
                controller.setCenter(start ?: HOME_CENTRE)
                // The whole point of the screen: report where the crosshair is sitting. Both events
                // are needed — a zoom moves the ground under a fixed centre just as a scroll moves
                // the centre over fixed ground, and a picker that only listened to one would show a
                // stale position after the other.
                val report = {
                    val here = GeoPoint(mapCenter.latitude, mapCenter.longitude)
                    // **Programmatic centring fires this listener too**, and what comes back is not
                    // the point that went in: osmdroid quantises its centre to its own fixed-point
                    // projection, measured on a Pixel 6 at about 11 cm at zoom 18. Harmless as a
                    // view, and not harmless as a measurement — before this latch, opening a sensor
                    // picker and confirming without touching anything moved an offset of 23.699 m to
                    // 23.81 m and recorded it as a fresh measurement.
                    //
                    // So nothing is reported until the centre has genuinely left where it was put.
                    // After that everything is, including a pan back to the start: that is a
                    // decision, where the opening report never was.
                    if (moved.value == true || here.distanceToAsDouble(start ?: HOME_CENTRE) > MOVED_THRESHOLD_M) {
                        moved.value = true
                        onCentre(here.latitude, here.longitude)
                    }
                }
                addMapListener(
                    object : MapListener {
                        override fun onScroll(event: ScrollEvent?): Boolean {
                            report()
                            return false
                        }

                        override fun onZoom(event: ZoomEvent?): Boolean {
                            report()
                            return false
                        }
                    }
                )
                // AndroidView forwards no lifecycle and osmdroid starts its tile threads here.
                onResume()
            }
        },
        update = { map ->
            if (map.tileProvider.tileSource !== tileSource) {
                map.setTileSource(tileSource)
                map.maxZoomLevel = maxZoomFor(tileSource)
            }
            map.setUseDataConnection(!offlineOnly)
            map.setMultiTouchControls(interactive)
            copyright.value?.setTextColor(attributionColour(layer))
            marker.at = existing
            marker.inkColor = chartInk(layer)
            axis.from = anchor
            axis.bearingDeg = bearingDeg
            marker.origin = anchor?.takeIf { it != existing }

            if (opened.value != true) {
                opened.value = true
                val centre = start ?: HOME_CENTRE
                // `setCenter` before layout is a silent no-op — the trap the live chart and the
                // recording chart both document — so it waits for a size if it has not got one.
                if (map.width > 0 && map.height > 0) {
                    map.controller.setCenter(centre)
                } else {
                    map.addOnFirstLayoutListener { _, _, _, _, _ -> map.controller.setCenter(centre) }
                }
                // **The opening position is deliberately not reported.** osmdroid quantises its
                // centre to its own fixed-point projection, so what it hands back after `setCenter`
                // is not the point it was given — measured on a Pixel 6 at about **11 cm** at zoom
                // 18, which is a tenth of a metre appearing from nowhere.
                //
                // That is harmless as a *view* and not harmless as a *measurement*: with it, opening
                // a picker and confirming without touching anything moved a sensor offset of 23.699
                // m to 23.81 m and recorded it as a fresh measurement. Every caller seeds its own
                // readout from what it already holds, so nothing is blank without this — and
                // "nothing has been chosen yet" is now a state the screens can actually detect.
            }
            map.invalidate()
        },
        onRelease = { map ->
            map.onPause()
            map.onDetach()
        },
    )
}

/** A tiny holder for something that outlives a recomposition without triggering one. */
private class Ref<T> {
    var value: T? = null
}

private fun <T> mutableRefOf() = Ref<T>()

/**
 * The zero point as it stands, so a pick can be judged against what it replaces.
 *
 * Drawn like the live chart's position dot and for the same reason it has a white edge: no single
 * colour is legible on both pale map tiles and dark satellite imagery, so the contrast comes from
 * the drawing rather than from the background.
 */
private class ExistingZeroOverlay : Overlay() {

    var at: GeoPoint? = null
    var origin: GeoPoint? = null
    var inkColor: Int = Color.WHITE

    private val fill = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.rgb(0xE0, 0x6C, 0x2A)
    }
    private val edge = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.WHITE
    }

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        // The point a measurement is taken *from*, when there is one and it is not the same thing.
        // A ring rather than a dot: it is a reference, not the value being chosen, and the two must
        // be tellable apart on a screen where both may be visible at once.
        origin?.let {
            val screen = map.projection.toPixels(it, null)
            canvas.drawCircle(screen.x.toFloat(), screen.y.toFloat(), MARKER_PX, edge)
        }
        val point = at ?: return
        val screen = map.projection.toPixels(point, null)
        canvas.drawCircle(screen.x.toFloat(), screen.y.toFloat(), MARKER_PX, fill)
        canvas.drawCircle(screen.x.toFloat(), screen.y.toFloat(), MARKER_PX, edge)
    }

    private companion object {
        const val MARKER_PX = 8f
    }
}

/**
 * The forward axis, drawn from the point it is measured from.
 *
 * **In screen space from the anchor's pixel**, not between two geographic points, because the ray
 * has no far end — it is a direction, and a direction drawn to a computed point would invite reading
 * the point as part of the measurement. The same reason and the same arithmetic as the live chart's
 * heading vector: bearings run clockwise from north while screen y grows downwards, hence the
 * sin/−cos pair.
 *
 * Halo first, then the line, for the reason recorded on the chart's own vectors: no single colour is
 * legible on both pale map tiles and dark satellite imagery, so the contrast comes from the drawing
 * rather than from what is underneath.
 */
private class ForwardAxisOverlay : Overlay() {

    var from: GeoPoint? = null
    var bearingDeg: Double? = null

    private val halo = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = AXIS_PX + 4f
        color = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
    }
    private val line = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = AXIS_PX
        color = Color.rgb(0xE0, 0x6C, 0x2A)
    }

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val start = from ?: return
        val bearing = bearingDeg ?: return
        val origin = map.projection.toPixels(start, null)
        // Long enough to leave the screen at any sensible zoom, so it reads as a ray rather than as a
        // line to somewhere in particular.
        val reach = hypot(canvas.width.toFloat(), canvas.height.toFloat())
        val radians = Math.toRadians(bearing)
        val x = origin.x + reach * sin(radians).toFloat()
        val y = origin.y - reach * cos(radians).toFloat()
        canvas.drawLine(origin.x.toFloat(), origin.y.toFloat(), x, y, halo)
        canvas.drawLine(origin.x.toFloat(), origin.y.toFloat(), x, y, line)
    }

    private companion object {
        const val AXIS_PX = 4f
    }
}

/**
 * Close enough to put a point down on a jetty, which is what this map is for.
 *
 * Tighter than the live chart's tracking zoom: that one is following a vessel and wants context
 * around it, where this one is aiming at a specific corner of a specific quay.
 */
private const val PICK_ZOOM = 18.0

/**
 * How far the centre has to leave its opening point before a report counts as a decision.
 *
 * Comfortably above osmdroid's own quantisation — about 11 cm at zoom 18 on a Pixel 6 — and
 * comfortably below anything somebody would place deliberately. A metre is also below the precision
 * any of these screens claims for itself: the sensor picker says a tape measure beats it at
 * decimetre scale, and the forward axis will not accept a baseline under five metres.
 */
private const val MOVED_THRESHOLD_M = 1.0

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
) {
    // Keyed, because `setTileSource` compares by identity — a fresh instance every recomposition
    // would swap the source and throw away its tile cache several times a second.
    val tileSource = remember(layer, mapTilerKey) { sourceFor(layer, mapTilerKey) }
    val marker = remember { ExistingZeroOverlay() }
    val copyright = remember { mutableRefOf<CopyrightOverlay>() }
    // Guards the opening centre. Setting it on every update would haul the map back under the
    // crosshair each time the layer changed, which is the one thing a picker must never do.
    val opened = remember { mutableRefOf<Boolean>() }

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
                overlays.add(marker)
                controller.setZoom(if (start == null) HOME_ZOOM else PICK_ZOOM)
                controller.setCenter(start ?: HOME_CENTRE)
                // The whole point of the screen: report where the crosshair is sitting. Both events
                // are needed — a zoom moves the ground under a fixed centre just as a scroll moves
                // the centre over fixed ground, and a picker that only listened to one would show a
                // stale position after the other.
                addMapListener(
                    object : MapListener {
                        override fun onScroll(event: ScrollEvent?): Boolean {
                            mapCenter.let { onCentre(it.latitude, it.longitude) }
                            return false
                        }

                        override fun onZoom(event: ZoomEvent?): Boolean {
                            mapCenter.let { onCentre(it.latitude, it.longitude) }
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
                // Report the opening position too, or the readout under the crosshair is blank until
                // the first drag — which reads as a broken map rather than as an untouched one.
                onCentre(centre.latitude, centre.longitude)
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
 * Close enough to put a point down on a jetty, which is what this map is for.
 *
 * Tighter than the live chart's tracking zoom: that one is following a vessel and wants context
 * around it, where this one is aiming at a specific corner of a specific quay.
 */
private const val PICK_ZOOM = 18.0

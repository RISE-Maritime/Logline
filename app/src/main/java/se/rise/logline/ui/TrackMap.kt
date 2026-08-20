package se.rise.logline.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.Polyline
import se.rise.logline.map.osmdroidBasePath
import se.rise.logline.publish.TrackPoint
import java.io.File

/** Which base layer the chart draws. */
enum class MapLayer(val label: String) {
    Standard("Map"),
    Satellite("Satellite"),
}

/**
 * Esri's global satellite imagery.
 *
 * osmdroid bundles no worldwide satellite source — `USGS_SAT` is the United States only and draws
 * nothing over Sweden — so this is defined here. No key, global coverage, and the resolution over the
 * Swedish coast is good enough to pick out a jetty.
 *
 * **The URL puts the row before the column**: `/tile/{z}/{y}/{x}`, not the `{z}/{x}/{y}` that
 * `XYTileSource` builds. That is the whole reason this is a custom source rather than one line — swap
 * them and every tile still loads, from the wrong place, which looks like a working map of somewhere
 * else.
 *
 * Marked `FLAG_NO_BULK` like OSM's own: Esri no more wants an app hoovering its imagery than the OSMF
 * does, and it keeps `CacheManager` from ever being pointed at it. Attribution is a condition of use
 * and is drawn by the `CopyrightOverlay` below.
 */
private val ESRI_WORLD_IMAGERY: OnlineTileSourceBase = object : OnlineTileSourceBase(
    "Esri World Imagery",
    0,
    19,
    256,
    "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "Esri, Maxar, Earthstar Geographics",
    TileSourcePolicy(
        2,
        TileSourcePolicy.FLAG_NO_BULK or
            TileSourcePolicy.FLAG_NO_PREVENTIVE or
            TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL or
            TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED,
    ),
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl +
            MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) + "/" +
            MapTileIndex.getX(pMapTileIndex)
}

private fun sourceFor(layer: MapLayer) = when (layer) {
    MapLayer.Standard -> TileSourceFactory.MAPNIK
    MapLayer.Satellite -> ESRI_WORLD_IMAGERY
}

/**
 * The GNSS track on an OpenStreetMap background.
 *
 * osmdroid is a plain Android `View`, so it lives behind `AndroidView` rather than being a Compose
 * library — which also keeps it clear of Compose version churn on this toolchain.
 */
@Composable
fun TrackMap(
    track: List<TrackPoint>,
    followFix: Boolean,
    modifier: Modifier = Modifier,
    /**
     * Render only from imported archives, never the network.
     *
     * Worth a switch rather than leaving it to the network being absent: out of coverage the tile
     * downloader still queues every missing tile and waits for each to time out, so a map that has the
     * archive it needs spends its time failing to fetch the ones it does not.
     */
    offlineOnly: Boolean = false,
    /** Which base layer to draw. */
    layer: MapLayer = MapLayer.Standard,
    /** OpenSeaMap's buoys, lights and seamarks, drawn transparently over the base layer. */
    seaMarks: Boolean = false,
    /** Where the phone points, from the compass. Null when there is no heading to draw. */
    headingDegrees: Float? = null,
) {
    val polyline = remember { Polyline() }
    // Drawn under the track for the same reason the vectors have one — see `FixOverlay.halo`. A second
    // Polyline rather than a paint list, because osmdroid gives an overlay one outline paint.
    val trackHalo = remember { Polyline() }
    // Held across recompositions: a TilesOverlay owns a tile provider and its threads, so rebuilding
    // one every time the switch is read would leak them.
    val seaMarkOverlay = remember { mutableStateOf<TilesOverlay?>(null) }
    val fixOverlay = remember { FixOverlay() }
    // Held so its colour can follow the base layer — see `attributionColour`. The overlay re-reads the
    // notice *text* from the current tile source on every draw, so only the paint needs wiring.
    val copyright = remember { mutableStateOf<CopyrightOverlay?>(null) }
    // Whether the map has ever been positioned. The first fix must `setCenter` — `animateTo` on a view
    // that has not been laid out yet is silently a no-op, which looks exactly like "tiles are broken".
    val centred = remember { mutableStateOf(false) }

    AndroidView(
        // clipToBounds because osmdroid paints its background across the whole canvas and, being a
        // plain View inside a scrolling Column, will otherwise draw over its neighbours.
        modifier = modifier.clipToBounds(),
        factory = { context ->
            configureOsmdroid(context)
            MapView(context).apply {
                setTileSource(sourceFor(layer))
                setUseDataConnection(!offlineOnly)
                // Attribution is a condition of both OSM's and Esri's terms, and osmdroid does *not*
                // draw it on its own — `CopyrightOverlay` has to be added, which this map never did.
                // It reads whatever the current source's notice is, so it follows the layer.
                //
                // Set to 9dp against the library's default of 12 (`paint.setTextSize(dm.density * 12)`
                // in its constructor; `setTextSize` takes dp). Both licences require the notice to be
                // *legible*, not prominent, and at 12dp black it was competing with the readouts under
                // the chart. This is the treatment every mobile map SDK uses.
                overlays.add(
                    CopyrightOverlay(context).also {
                        it.setTextSize(ATTRIBUTION_TEXT_DP)
                        copyright.value = it
                    }
                )
                setMultiTouchControls(true)
                // **Somewhere real until a fix arrives.** With no centre set, osmdroid opens at 0°N 0°E
                // — the Gulf of Guinea — which OSM covers with a plain blue ocean tile and Esri does
                // not cover at all: the satellite layer's first screenful was a grid reading "Map data
                // not yet available", which reads as a broken map rather than as a missing fix.
                //
                // Wide, not the tracking zoom. A street-level view of a city the phone is not in says
                // less than the region it is in, and the first fix sets both centre and zoom anyway.
                controller.setZoom(HOME_ZOOM)
                controller.setCenter(HOME_CENTRE)
                trackHalo.outlinePaint.color = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
                trackHalo.outlinePaint.strokeWidth = TRACK_PX + TRACK_HALO_PX
                polyline.outlinePaint.color = Color.rgb(0x3F, 0x6F, 0xD8)
                polyline.outlinePaint.strokeWidth = TRACK_PX
                // Halo first: it is the under-stroke, and osmdroid draws overlays in the order added.
                overlays.add(trackHalo)
                overlays.add(polyline)
                // After the polyline, so the marker and vectors draw on top of the track.
                overlays.add(fixOverlay)
                // AndroidView does not forward lifecycle, and osmdroid starts its tile-fetching
                // threads in onResume(). Without this the map renders its empty grid forever.
                onResume()
            }
        },
        update = { map ->
            // Swapping the source on a live MapView is supported and redraws; a new one is only built
            // when the composable is. The seamark overlay is created once and then added or removed,
            // because each instance carries its own tile provider and threads.
            if (map.tileProvider.tileSource != sourceFor(layer)) {
                map.setTileSource(sourceFor(layer))
            }
            // Dark on map tiles, light on imagery. The library paints it black whatever is underneath,
            // and black on a night-time satellite tile is not attribution, it is a smudge.
            copyright.value?.setTextColor(attributionColour(layer))
            map.setUseDataConnection(!offlineOnly)
            val marks = seaMarkOverlay.value ?: TilesOverlay(
                MapTileProviderBasic(map.context, TileSourceFactory.OPEN_SEAMAP),
                map.context,
            ).apply {
                // Transparent, or the overlay paints its own background over the base layer and the
                // seamarks are all anybody sees.
                loadingBackgroundColor = Color.TRANSPARENT
                loadingLineColor = Color.TRANSPARENT
            }.also { seaMarkOverlay.value = it }
            val shown = marks in map.overlays
            if (seaMarks && !shown) {
                // Beneath the track and the fix marker, above the base tiles.
                map.overlays.add(0, marks)
                map.invalidate()
            } else if (!seaMarks && shown) {
                map.overlays.remove(marks)
                map.invalidate()
            }
            // Built once and handed to both: two `map` passes over a track that runs to thousands of
            // points, every time anything on this screen recomposes, is not free.
            val points = track.map { GeoPoint(it.latitude, it.longitude) }
            trackHalo.setPoints(points)
            polyline.setPoints(points)
            fixOverlay.fix = track.lastOrNull()
            fixOverlay.headingDegrees = headingDegrees
            track.lastOrNull()?.let { fix ->
                val point = GeoPoint(fix.latitude, fix.longitude)
                when {
                    !centred.value -> {
                        // Zoom first, then centre: the first fix is what turns the placeholder view
                        // into a working one, and `setCenter` rather than `animateTo` because
                        // animating a view that has not been laid out is silently a no-op.
                        map.controller.setZoom(TRACK_ZOOM)
                        map.controller.setCenter(point)
                        centred.value = true
                    }
                    followFix -> map.controller.animateTo(point)
                }
            }
            map.invalidate()
        },
        onRelease = { map ->
            map.onPause()
            map.onDetach()
        },
    )
}

/**
 * osmdroid setup that must happen before the first tile request.
 *
 * **The user agent is not optional.** OSM's tile servers answer the library's default agent with
 * `403`, and the failure surfaces as a blank map rather than an error — so a forgotten user agent looks
 * exactly like "the map is broken". Setting it to the application id is what their usage policy asks
 * for.
 *
 * Caches live under `filesDir`, consistent with the rest of the app keeping its data app-private, and
 * it is that cache which lets a pre-loaded area keep rendering with no network.
 */
private fun configureOsmdroid(context: Context) {
    val config = Configuration.getInstance()
    // Loading from shared prefs first is what osmdroid's own docs ask for; it seeds defaults that the
    // tile downloader reads, and skipping it leaves some of them null.
    runCatching { config.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)) }
    if (config.userAgentValue.isNullOrBlank() || config.userAgentValue == "osmdroid") {
        config.userAgentValue = context.packageName
    }
    // The same directory the offline-map import writes into, and it has to be: osmdroid's
    // `MapTileFileArchiveProvider` finds archives by listing exactly this path.
    val base = osmdroidBasePath(context)
    config.osmdroidBasePath = base
    config.osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
}


/**
 * The current fix drawn on top of the track: accuracy circle, position, and where it is going against
 * where it points.
 *
 * One custom overlay rather than osmdroid's `Marker` and `Polygon`, for two reasons: `Marker` pulls in
 * the library's bundled drawables for its pin, and four separate overlays would each walk the
 * projection. Everything here is drawn in screen space from one projection lookup.
 *
 * **Course and heading are different arrows on purpose.** Course over ground is the direction of
 * travel, from the GNSS fix; heading is where the phone's +Y axis points, from the compass. On the
 * water they differ — that difference is leeway, and seeing it is the reason to draw both.
 */
private class FixOverlay : Overlay() {

    var fix: TrackPoint? = null
    var headingDegrees: Float? = null

    private val accuracyFill = Paint().apply {
        color = Color.argb(40, 0x3F, 0x6F, 0xD8)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val accuracyEdge = Paint().apply {
        color = Color.argb(120, 0x3F, 0x6F, 0xD8)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val positionPaint = Paint().apply {
        color = Color.rgb(0x2A, 0x53, 0xAE)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val positionEdge = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val coursePaint = Paint().apply {
        color = Color.rgb(0x2A, 0x53, 0xAE)
        style = Paint.Style.STROKE
        strokeWidth = COURSE_PX
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val headingPaint = Paint().apply {
        color = Color.rgb(0xC8, 0x7A, 0x1E)
        style = Paint.Style.STROKE
        strokeWidth = HEADING_PX
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    /**
     * The white under-stroke that makes both vectors readable on any base layer.
     *
     * A dark blue course line over the standard map's pale tiles is perfectly legible and over Esri's
     * imagery it is nearly gone — the satellite layer is dark green forest and darker water for most of
     * a Swedish coastline, and the one thing this app draws that somebody navigates by was the hardest
     * thing on it to see. Rather than pick a colour that works on both (there isn't one — imagery
     * covers snow and asphalt too), each line is drawn twice: a wider white stroke first, the coloured
     * one on top. Contrast then comes from the halo rather than from the background, so it holds on any
     * tile and on an imported offline archive nobody has seen yet.
     *
     * This is the same trick `positionEdge` has always used on the dot, and what every chartplotter
     * does with a course-up vector. One paint serves both lines because only the width differs, and
     * that is set per call.
     */
    private val halo = Paint().apply {
        color = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val point = android.graphics.Point()

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val current = fix ?: return
        val projection = map.projection
        projection.toPixels(GeoPoint(current.latitude, current.longitude), point)
        val x = point.x.toFloat()
        val y = point.y.toFloat()

        // The accuracy circle is a real distance, so it has to be converted through the projection's
        // scale rather than drawn at a fixed pixel radius — at wide zoom a 3 m circle is invisible, and
        // that is the honest picture.
        current.accuracyMetres?.let { metres ->
            // The latitude-aware conversion, not the equator one: Mercator's scale stretches with
            // latitude, and at 57°N the difference is nearly a factor of two.
            val radiusPx = projection.metersToPixels(metres, current.latitude, projection.zoomLevel)
            if (radiusPx > 1f) {
                canvas.drawCircle(x, y, radiusPx, accuracyFill)
                canvas.drawCircle(x, y, radiusPx, accuracyEdge)
            }
        }

        // **Both halos before either line.** The two vectors share an origin, so they always overlap
        // near the dot — drawing halo-then-line twice would have the heading's white stroke painted
        // across the course line it was meant to sit under, leaving a notch in it at exactly the point
        // the eye starts reading from.
        val course = current.bearingDegrees
        val headingLength = VECTOR_PX * 0.75f
        halo.strokeWidth = COURSE_PX + HALO_PX
        course?.let { canvas.drawVector(x, y, it, VECTOR_PX, halo) }
        halo.strokeWidth = HEADING_PX + HALO_PX
        headingDegrees?.let { canvas.drawVector(x, y, it, headingLength, halo) }

        course?.let { canvas.drawVector(x, y, it, VECTOR_PX, coursePaint) }
        headingDegrees?.let { canvas.drawVector(x, y, it, headingLength, headingPaint) }

        canvas.drawCircle(x, y, DOT_PX, positionPaint)
        canvas.drawCircle(x, y, DOT_PX, positionEdge)
    }

    /** Bearings are clockwise from north; screen y grows downwards, hence the sin/-cos pair. */
    private fun Canvas.drawVector(x: Float, y: Float, degrees: Float, length: Float, paint: Paint) {
        val radians = Math.toRadians(degrees.toDouble())
        drawLine(
            x,
            y,
            x + (length * kotlin.math.sin(radians)).toFloat(),
            y - (length * kotlin.math.cos(radians)).toFloat(),
            paint,
        )
    }

    private companion object {
        const val DOT_PX = 9f
        const val VECTOR_PX = 46f
        const val COURSE_PX = 6f
        const val HEADING_PX = 5f

        /** Added to a line's own width, so the halo shows as ~2px either side of it. */
        const val HALO_PX = 4f
    }
}

/**
 * Where the chart looks before the first fix, and how far out.
 *
 * Gothenburg and its archipelago — the home water this app is built for and tested on. It is a
 * placeholder, not a claim: nothing is drawn on it until a real position arrives, at which point the
 * chart jumps to [TRACK_ZOOM] on the actual fix.
 */
private val HOME_CENTRE = GeoPoint(57.7089, 11.9746)
private const val HOME_ZOOM = 10.0

/** Close enough to see which side of a jetty a track passed. */
private const val TRACK_ZOOM = 16.0

private const val TRACK_PX = 6f
private const val TRACK_HALO_PX = 4f

/** Small enough to retreat, large enough to read. See the note at the overlay's construction. */
private const val ATTRIBUTION_TEXT_DP = 9

/**
 * The notice's ink, per base layer.
 *
 * Both are held back to about 70% opacity — present, checkable, and not competing with the position
 * readout. Satellite imagery is dark far more often than it is light (water, shadow, night passes), so
 * it takes white; the standard map's tiles are pale everywhere and take near-black.
 */
private fun attributionColour(layer: MapLayer): Int = when (layer) {
    MapLayer.Satellite -> Color.argb(0xB3, 0xFF, 0xFF, 0xFF)
    MapLayer.Standard -> Color.argb(0xB3, 0x00, 0x00, 0x00)
}

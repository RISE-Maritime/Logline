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
import kotlin.math.hypot

/**
 * What the chart draws *over* its base layer, as one value.
 *
 * Four booleans threaded individually through the live screen, its toolbar and the layer menu would be
 * eight parameters and would grow to ten the next time somebody adds a mark. This also makes the menu's
 * job a copy: it hands back a changed [ChartMarks] and knows nothing about what each flag reaches.
 *
 * The position and its accuracy circle are deliberately absent — a chart with no "you are here" is not
 * a chart, so they are not switches.
 */
data class ChartMarks(
    /** OpenSeaMap's buoys and lights, drawn over whichever base layer is showing. */
    val seaMarks: Boolean = false,
    /** Where the phone has been. History, and the first thing to clutter a close-quarters view. */
    val track: Boolean = true,
    /** Where it points now, to twelve nautical miles. */
    val headingLine: Boolean = true,
    /** Where it is going now, from the fix's own bearing. */
    val courseVector: Boolean = true,
)

/**
 * Which base layer the chart draws.
 *
 * [needsKey] is what puts a settings gear beside a layer in the menu instead of letting it be chosen:
 * a layer that cannot fetch a tile is not a choice, it is a dead end, and selecting one would leave the
 * chart blank with nothing on screen saying why. Satellite is deliberately **not** marked — it falls
 * back to Esri, so it always draws something.
 */
enum class MapLayer(val label: String, val needsKey: Boolean = false) {
    Standard("Map"),
    Satellite("Satellite"),
    Ocean("Ocean", needsKey = true),
    Topographic("Topographic", needsKey = true),
    Outdoor("Outdoor", needsKey = true),
    Streets("Streets", needsKey = true),
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

/**
 * MapTiler's satellite imagery, which needs a key and repays it in resolution.
 *
 * Esri stops at zoom 19 and coarsens well before that outside cities; MapTiler serves 20 and its
 * coverage over Scandinavian coast is finer, which is the difference between seeing a jetty and seeing
 * where a jetty is. A plain `{z}/{x}/{y}`, unlike Esri's row-before-column URL, so `XYTileSource` would
 * almost do — except the key rides on the query string, which is what the override is for.
 *
 * **The key is never in this repo or in the APK**: it is a per-phone setting. See `Settings.mapTilerKey`.
 *
 * `FLAG_NO_BULK` like the others: a key makes bulk downloading somebody's billable problem rather than
 * merely rude, and it keeps `CacheManager` from ever being pointed at it. MapTiler's terms require the
 * notice below, which `CopyrightOverlay` draws.
 */
private fun mapTilerRaster(
    displayName: String,
    /** The part after `api.maptiler.com/`, e.g. `tiles/satellite-v2` or `maps/ocean`. */
    path: String,
    extension: String,
    key: String,
): OnlineTileSourceBase = object : OnlineTileSourceBase(
    displayName,
    0,
    MAPTILER_MAX_ZOOM,
    // 512 verified against the service, not assumed: every endpoint returns 512x512.
    512,
    extension,
    arrayOf("https://api.maptiler.com/$path/"),
    "© MapTiler © OpenStreetMap contributors",
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
            MapTileIndex.getX(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) +
            mImageFilenameEnding + "?key=" + key
}

/**
 * Which source draws a layer, given whatever key the phone holds.
 *
 * **Esri is the fallback rather than an error**, because satellite is the *default* layer: an install
 * with no key would otherwise open the Live tab on a blank grid, which reads as a broken app rather
 * than a missing setting.
 */
internal fun sourceFor(layer: MapLayer, mapTilerKey: String) = when (layer) {
    MapLayer.Standard -> TileSourceFactory.MAPNIK
    MapLayer.Satellite ->
        if (mapTilerKey.isNotBlank()) {
            mapTilerRaster("MapTiler Satellite", "tiles/satellite-v2", ".jpg", mapTilerKey)
        } else {
            ESRI_WORLD_IMAGERY
        }
    // Bathymetry, which is the one of these a boat actually wants: depth contours and soundings under
    // a plain land mask. The other layers say where the shore is; this one says what is under the hull.
    MapLayer.Ocean -> mapTilerRaster("MapTiler Ocean", "maps/ocean", ".png", mapTilerKey)
    MapLayer.Topographic -> mapTilerRaster("MapTiler Topo", "maps/topo-v2", ".png", mapTilerKey)
    // These two sit close to the OpenStreetMap layer above — outdoor adds trail and terrain rendering,
    // streets is the plainer road map. Appended rather than slotted in beside `Standard`, so the
    // positions of the layers already in the menu do not shift under anybody who knows where they are.
    MapLayer.Outdoor -> mapTilerRaster("MapTiler Outdoor", "maps/outdoor-v2", ".png", mapTilerKey)
    MapLayer.Streets -> mapTilerRaster("MapTiler Streets", "maps/streets-v2", ".png", mapTilerKey)
}

/**
 * How deep MapTiler will serve, which is deeper than its imagery actually resolves.
 *
 * Measured against the service with a real key: zoom 19, 20, 21 and 22 over Onsala all return 200 with
 * a real tile — decreasing in size, so the deep ones are their own upscaling — rather than the 404 that
 * would be needed for osmdroid to upscale them here. Letting the server do it is the better half of the
 * bargain anyway, since it can pick the best source it has.
 */
private const val MAPTILER_MAX_ZOOM = 22

/**
 * How far in the chart will go, which is **not the same answer for every source**.
 *
 * osmdroid stops dead at the source's maximum unless the view is told otherwise, and on a chart that is
 * a pinch which simply refuses at the moment somebody is trying to see which side of a pontoon they are
 * on. Where a source *fails* past its limit, osmdroid's tile approximater upscales the deepest tile it
 * has — blurry, and still the right answer, because the position, the track and the heading line stay
 * sharp and keep their true scale, and those are what is being read at that zoom.
 *
 * Three sources, three answers, each from what the service actually does:
 *
 * **OpenStreetMap 404s past 19**, so the approximater has something to do — verified on a Pixel 6,
 * zoom 21 draws building footprints upscaled from 19, soft-edged, with the position sharp on top. It is
 * the only one that gets the extra levels.
 *
 * **Esri does not fail past 19; it serves a grey "Map data not available" tile.** Verified by forcing
 * zoom 21: a 200 with a placeholder in it is a tile as far as the provider is concerned, so there is
 * nothing to approximate from and over-zooming buys a grey field rather than a blurry one.
 *
 * **MapTiler over-zooms on its own**, all the way to 22 — see [MAPTILER_MAX_ZOOM] — so it declares that
 * depth itself and needs no help.
 */
internal fun maxZoomFor(source: OnlineTileSourceBase): Double =
    if (source === TileSourceFactory.MAPNIK) {
        source.maximumZoomLevel + OVER_ZOOM_LEVELS
    } else {
        source.maximumZoomLevel.toDouble()
    }

/** Two levels of upscaling: 4x, past which the blur stops being worth the magnification. */
private const val OVER_ZOOM_LEVELS = 2.0

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
    marks: ChartMarks = ChartMarks(),
    /** Where the phone points, from the compass. Null when there is no heading to draw. */
    headingDegrees: Float? = null,
    /** Upgrades the satellite layer to MapTiler's imagery. Blank falls back to Esri — see [sourceFor]. */
    mapTilerKey: String = "",
) {
    val polyline = remember { Polyline() }
    // Drawn under the track for the same reason the vectors have one — see `FixOverlay.halo`. A second
    // Polyline rather than a paint list, because osmdroid gives an overlay one outline paint.
    val trackHalo = remember { Polyline() }
    // Held across recompositions: a TilesOverlay owns a tile provider and its threads, so rebuilding
    // one every time the switch is read would leak them.
    val seaMarkOverlay = remember { mutableStateOf<TilesOverlay?>(null) }
    val fixOverlay = remember { FixOverlay() }
    // **Remembered on (layer, key), not rebuilt per recomposition.** `setTileSource` compares by
    // identity, so a fresh instance every frame would swap the source — and throw away its tile cache —
    // several times a second. Esri is a singleton and never had this problem; MapTiler's carries a key
    // and so has to be constructed.
    val tileSource = remember(layer, mapTilerKey) { sourceFor(layer, mapTilerKey) }
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
                setTileSource(tileSource)
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
            if (map.tileProvider.tileSource != tileSource) {
                map.setTileSource(tileSource)
            }
            // In `update` rather than `factory`: it depends on the source, so it has to follow a layer
            // change. Without it a pinch stops dead at the source's own maximum — see `maxZoomFor`.
            map.maxZoomLevel = maxZoomFor(tileSource)
            // Dark on map tiles, light on imagery. The library paints it black whatever is underneath,
            // and black on a night-time satellite tile is not attribution, it is a smudge.
            copyright.value?.setTextColor(attributionColour(layer))
            // The heading line takes the same ink at full strength — see `FixOverlay.inkColor`.
            fixOverlay.inkColor = chartInk(layer)
            map.setUseDataConnection(!offlineOnly)
            val seaMarkTiles = seaMarkOverlay.value ?: TilesOverlay(
                MapTileProviderBasic(map.context, TileSourceFactory.OPEN_SEAMAP),
                map.context,
            ).apply {
                // Transparent, or the overlay paints its own background over the base layer and the
                // seamarks are all anybody sees.
                loadingBackgroundColor = Color.TRANSPARENT
                loadingLineColor = Color.TRANSPARENT
            }.also { seaMarkOverlay.value = it }
            val shown = seaMarkTiles in map.overlays
            if (marks.seaMarks && !shown) {
                // Beneath the track and the fix marker, above the base tiles.
                map.overlays.add(0, seaMarkTiles)
                map.invalidate()
            } else if (!marks.seaMarks && shown) {
                map.overlays.remove(seaMarkTiles)
                map.invalidate()
            }
            // Built once and handed to both: two `map` passes over a track that runs to thousands of
            // points, every time anything on this screen recomposes, is not free.
            val points = track.map { GeoPoint(it.latitude, it.longitude) }
            // Emptied rather than removed from the overlay list: a `Polyline` with no points draws
            // nothing, and adding and removing overlays is what the seamark tile provider above needs
            // only because *it* owns threads. A polyline owns nothing.
            val drawn = if (marks.track) points else emptyList()
            trackHalo.setPoints(drawn)
            polyline.setPoints(drawn)
            fixOverlay.fix = track.lastOrNull()
            fixOverlay.headingDegrees = headingDegrees
            fixOverlay.showHeading = marks.headingLine
            fixOverlay.showCourse = marks.courseVector
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
internal fun configureOsmdroid(context: Context) {
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

    /** Whether to draw each vector at all — see the switches on [TrackMap]. */
    var showHeading: Boolean = true
    var showCourse: Boolean = true

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
    /**
     * The heading line's ink, set from the base layer — see `chartInk`.
     *
     * White over imagery, near-black over map tiles. It carries **no halo**, unlike everything else
     * drawn here: a halo exists to give a *coloured* line contrast it cannot get from its own hue, and
     * this line has no hue to keep — it is simply the opposite of whatever is underneath. Adding one
     * would mean outlining white in white.
     */
    var inkColor: Int = Color.WHITE

    private val headingPaint = Paint().apply {
        color = Color.WHITE
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

        // The course vector keeps its halo; the heading line has none — it is the layer's own ink and
        // has nothing to be outlined against. Its halo used to be drawn *before* the course line for a
        // reason that no longer applies: the two share an origin, so a halo painted after would have
        // notched the course line at exactly the point the eye starts reading from.
        val course = current.bearingDegrees.takeIf { showCourse }
        val heading = headingDegrees?.takeIf { showHeading }
        // **A real distance, so it scales with the chart** — the same conversion the accuracy circle
        // uses, and for the same reason: a fixed pixel length means a different distance at every zoom,
        // which is the one thing a heading line is not allowed to be. At working zoom the twelve miles
        // run off the screen and it reads as a ray, which is what a chartplotter's heading line looks
        // like; zoom out far enough to see twelve miles and it ends where it should.
        //
        // Clamped to what the canvas could possibly show. At zoom 16 twelve miles is about 17 000 px
        // and at zoom 20 nearer 280 000 — all of it clipped, but handed to `drawLine` first. Cutting it
        // at the diagonal draws exactly the same picture from coordinates that stay sane.
        val headingLength = projection
            .metersToPixels(HEADING_LINE_METRES, current.latitude, projection.zoomLevel)
            .coerceAtMost(hypot(canvas.width.toFloat(), canvas.height.toFloat()))
        halo.strokeWidth = COURSE_PX + HALO_PX
        course?.let { canvas.drawVector(x, y, it, VECTOR_PX, halo) }

        headingPaint.color = inkColor
        course?.let { canvas.drawVector(x, y, it, VECTOR_PX, coursePaint) }
        heading?.let { canvas.drawVector(x, y, it, headingLength, headingPaint) }

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

        /**
         * How far ahead the heading line reaches: **twelve nautical miles**.
         *
         * The distance a chartplotter's heading line conventionally runs to, and the same figure as the
         * territorial-sea limit and a common radar range ring — so it doubles as a scale a reader
         * already knows. Written as the multiplication rather than 22 224 so the twelve stays visible;
         * a nautical mile is 1852 m exactly, by definition.
         *
         * The *course* vector below is deliberately still a fixed pixel length: it says which way the
         * boat is moving, not how far it will get, and giving it a distance would imply a prediction
         * this app does not make.
         */
        const val HEADING_LINE_METRES = 12f * 1852f

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

internal const val TRACK_PX = 6f
internal const val TRACK_HALO_PX = 4f

/** Small enough to retreat, large enough to read. See the note at the overlay's construction. */
internal const val ATTRIBUTION_TEXT_DP = 9

/**
 * Ink that reads on whatever the base layer is drawing.
 *
 * Satellite imagery is dark far more often than it is light — water, forest, shadow — so it takes
 * white; every *cartographic* layer here is pale by design, whether it is drawing streets, contours or
 * soundings, and takes black. One function because two things depend on it and they must not disagree
 * about which layer is which.
 *
 * Exhaustive rather than defaulted on purpose: a layer added later is a layer whose background nobody
 * has looked at, and the compiler asking is better than a white line vanishing into a white sea.
 */
internal fun chartInk(layer: MapLayer): Int = when (layer) {
    MapLayer.Satellite -> Color.WHITE
    MapLayer.Standard,
    MapLayer.Ocean,
    MapLayer.Topographic,
    MapLayer.Outdoor,
    MapLayer.Streets -> Color.BLACK
}

/**
 * The notice's ink: the same choice, held back to about 70% opacity.
 *
 * Present and checkable, and not competing with the position readout — attribution is a condition of
 * use rather than a design element.
 */
internal fun attributionColour(layer: MapLayer): Int {
    val ink = chartInk(layer)
    return Color.argb(0xB3, Color.red(ink), Color.green(ink), Color.blue(ink))
}

package se.rise.logline.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whether the chart is blank because the MapTiler key has stopped working.
 *
 * The layer menu could already say a layer *needs* a key. It could not say a key had **stopped** being
 * accepted, which is what an expired or over-quota one does: new tiles fail, the chart degrades to
 * upscaled mush or nothing, and the layer stays ticked with no explanation. The gear only appeared when
 * the field was empty, so the one state a person could act on was the one that never showed.
 *
 * **This asks the service rather than watching the map, and that is not the first design — it is the
 * one that works.** Counting osmdroid's tile results looked like the cheap answer, and it is worthless
 * here: measured on a Pixel 6 with a deliberately invalid key, the tile handler reported **4974
 * successes and zero failures**. `MapTileApproximater` supplies a scaled-up tile from a neighbouring
 * zoom and that counts as success, so a chart visibly degrading to blur reports itself perfectly
 * healthy. Anything inferred from tile outcomes would have to see through that.
 *
 * A probe has no such problem, and is definite where a count could only ever be statistical: MapTiler
 * answers **403** for a key it will not take, against **200** for one it will — verified against the
 * service with both, the 403 carrying "Invalid key" as its body.
 */
enum class MapTilerKeyStatus {
    /** Not asked, no network, or an answer that says nothing about the key. Never reported to anyone. */
    Unknown,
    Ok,
    /** Refused: invalid, expired, or over quota. The screen does not guess which. */
    Rejected,
}

/** Whether this layer fetches from MapTiler at all — see `sourceFor`. */
internal fun usesMapTiler(layer: MapLayer, hasKey: Boolean): Boolean =
    layer.needsKey || (layer == MapLayer.Satellite && hasKey)

/**
 * What an HTTP status says about the key. Pure, so the mapping can be pinned without a network.
 *
 * **Everything unrecognised is [MapTilerKeyStatus.Unknown], and that asymmetry is the whole safety
 * property.** A 500 from the service, a captive portal's 302, a proxy's 407 — none of them are evidence
 * about the key, and reporting one as a refusal would send somebody to replace a key that was fine. The
 * same rule keeps `LocationProvider` from calling a phone indoors a fault: only causes that are certain
 * and actionable get to be a warning.
 */
internal fun mapTilerKeyStatusFor(httpCode: Int): MapTilerKeyStatus = when (httpCode) {
    HttpURLConnection.HTTP_OK -> MapTilerKeyStatus.Ok
    // 401/403 is a refused key; 402 is billing; 429 is over quota. All four mean "this key will not
    // fetch tiles today", which is one thing as far as the person holding the phone is concerned.
    HttpURLConnection.HTTP_UNAUTHORIZED,
    HttpURLConnection.HTTP_PAYMENT_REQUIRED,
    HttpURLConnection.HTTP_FORBIDDEN,
    429,
    -> MapTilerKeyStatus.Rejected
    else -> MapTilerKeyStatus.Unknown
}

/** The smallest thing worth asking for: zoom 1, one of four tiles covering the planet. */
private const val PROBE_URL = "https://api.maptiler.com/tiles/satellite-v2/1/0/0.jpg?key="
private const val PROBE_TIMEOUT_MILLIS = 8_000

/**
 * Ask MapTiler whether it will take this key.
 *
 * A `HEAD`, because the answer wanted is the status line and nothing else — and on a metered link the
 * body of a satellite tile is not a thing to fetch to learn one number. Any throw is
 * [MapTilerKeyStatus.Unknown]: out of coverage is not evidence about a key.
 */
suspend fun probeMapTilerKey(key: String): MapTilerKeyStatus {
    if (key.isBlank()) return MapTilerKeyStatus.Unknown
    return withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(PROBE_URL + key).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = PROBE_TIMEOUT_MILLIS
                readTimeout = PROBE_TIMEOUT_MILLIS
            }
            try {
                mapTilerKeyStatusFor(connection.responseCode)
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(MapTilerKeyStatus.Unknown)
    }
}

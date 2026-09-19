package se.rise.logline.monitor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/**
 * One sample as the router's REST plugin delivers it: the full key and the raw (enveloped) payload.
 *
 * `receivedAtMillis` is the phone's wall clock at arrival, for the same reason `SubjectSink.record`
 * stamps arrival time in the live store: staleness is a question about the link, and a replay's
 * payload timestamps are from whenever the recording was made.
 */
class RemoteSample(val key: String, val receivedAtMillis: Long, val bytes: ByteArray)

/** One Server-Sent Event: its `event:` name and its `data:` lines joined by newlines. */
data class SseEvent(val event: String, val data: String)

/**
 * Line-at-a-time Server-Sent Events parser, per the WHATWG rules the REST plugin follows.
 *
 * Pure and stateful: feed it lines as they arrive and it hands back an event on each blank line.
 * `:` lines are comments (keep-alives) and are dropped; several `data:` lines join with `\n`; a single
 * space after the colon is part of the framing, not the value.
 */
class SseParser {
    private var event = "message"
    private val data = StringBuilder()
    private var hasData = false

    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) {
            val out = if (hasData) SseEvent(event, data.toString()) else null
            event = "message"
            data.setLength(0)
            hasData = false
            return out
        }
        if (line.startsWith(':')) return null
        val field = line.substringBefore(':')
        var value = if (':' in line) line.substringAfter(':') else ""
        if (value.startsWith(' ')) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }
}

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * A `PUT` event's JSON body turned into key and bytes, or null when it is not one.
 *
 * The plugin writes `{"key", "value", "encoding", "timestamp"}`, and for a binary encoding
 * (`zenoh/bytes`, which is what every keelson publisher uses) `value` is **base64** of the payload —
 * verified against the rise router while writing this, and pinned by `MonitorWireTest` with a captured
 * event. A textual encoding carries the value as-is, which no keelson subject uses; it is decoded as
 * UTF-8 bytes rather than dropped so a raw JSON publisher still shows as seen.
 */
internal fun parseRestSample(event: SseEvent, receivedAtMillis: Long): RemoteSample? {
    if (event.event != "PUT") return null
    val obj = runCatching { lenientJson.parseToJsonElement(event.data) as? JsonObject }.getOrNull()
        ?: return null
    val key = (obj["key"] as? JsonPrimitive)?.content ?: return null
    val value = (obj["value"] as? JsonPrimitive)?.content ?: return null
    val encoding = (obj["encoding"] as? JsonPrimitive)?.content.orEmpty()
    val bytes = if (encoding.startsWith("text/") || encoding.startsWith("application/json")) {
        value.toByteArray(Charsets.UTF_8)
    } else {
        runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: return null
    }
    return RemoteSample(key, receivedAtMillis, bytes)
}

/** The REST plugin's default port, and the one the rise router publishes. */
const val DEFAULT_REST_PORT = 8000

/**
 * The monitor's base URL: the configured one, or `http://{host of the first router endpoint}:8000`.
 *
 * Derived rather than asked for, because the router this phone is already configured for is the one
 * that has the data — the REST plugin and the Zenoh listener are the same process. Null when there is
 * nothing to derive from, which the screen states instead of guessing a host.
 */
fun monitorBaseUrl(configured: String, endpoints: List<String>): String? {
    configured.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.let { return it }
    val endpoint = endpoints.firstOrNull { it.isNotBlank() } ?: return null
    val hostPort = endpoint.substringAfter('/', "").substringBefore('?').substringBefore('#')
    if (hostPort.isEmpty()) return null
    val host = if (hostPort.startsWith('[')) {
        hostPort.substringBefore(']') + "]"
    } else {
        hostPort.substringBeforeLast(':')
    }
    if (host.isEmpty() || host == "[]") return null
    return "http://$host:$DEFAULT_REST_PORT"
}

/**
 * Everything one entity publishes: the entity's `pubsub` chunk followed by a double-star wildcard.
 *
 * `@v0` spelled out, because no wildcard crosses it — a realm followed directly by a double star
 * matches nothing and fails silently.
 */
fun monitorKeyExpr(realm: String, entity: String): String = "$realm/@v0/$entity/pubsub/**"

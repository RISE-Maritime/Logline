package se.rise.logline.monitor

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.rise.logline.keelson.KeelsonSession
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import kotlin.coroutines.coroutineContext

private const val TAG = "MonitorSync"

/** What the tab says about its link. */
sealed interface MonitorLink {
    data object Idle : MonitorLink
    data class Connecting(val url: String) : MonitorLink
    data class Streaming(val url: String) : MonitorLink
    data class Failed(val url: String?, val reason: String) : MonitorLink
}

/**
 * Everything a stream needs, resolved from `Settings` by the caller. Null URL means none derivable.
 *
 * [zenohEndpoints] non-null selects the Zenoh subscriber instead of the REST stream; the caller sets it
 * only where subscriptions are safe on this binding and nobody asked for a URL by hand.
 */
data class MonitorConfig(
    val baseUrl: String?,
    val realm: String,
    val entity: String,
    val zenohEndpoints: List<String>? = null,
)

/**
 * Where the monitor's samples come from.
 *
 * An interface because the transport is a workaround, not a choice. The natural source is a Zenoh
 * subscriber, and on this binding every timestamped sample aborts the process natively
 * (`ZenohBinding.SUBSCRIPTIONS_SAFE`). When that is fixed upstream, a subscriber becomes a second
 * implementation here and nothing above this line changes.
 */
fun interface MonitorSource {
    /** Stream until cancelled or failed. [onOpen] fires once the stream is established. */
    suspend fun stream(onOpen: () -> Unit, onSample: (RemoteSample) -> Unit)
}

/**
 * The router's REST plugin, read as Server-Sent Events: `GET {base}/{keyExpr}` with
 * `Accept: text/event-stream` is a subscription.
 *
 * Plain `HttpURLConnection`: one long-lived GET does not justify a dependency. Two things about it are
 * load-bearing. A blocking read does not see coroutine cancellation, so [disconnect] is how a stop
 * actually ends the read. And the plugin sends **no keep-alives** — verified, an entity with nothing
 * on the bus yields zero bytes — so the read timeout is how a dead link is noticed, and a timeout on a
 * quiet entity is reported as a reconnect rather than as a failure.
 */
class SseMonitorSource(private val url: String) : MonitorSource {

    @Volatile private var connection: HttpURLConnection? = null

    override suspend fun stream(onOpen: () -> Unit, onSample: (RemoteSample) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "text/event-stream")
        }
        connection = conn
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code from the router")
            onOpen()
            val parser = SseParser()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (coroutineContext.isActive) {
                    val line = reader.readLine() ?: throw IOException("the router closed the stream")
                    val event = parser.feed(line) ?: continue
                    parseRestSample(event, System.currentTimeMillis())?.let(onSample)
                }
            }
        } finally {
            connection = null
            conn.disconnect()
        }
    }

    fun disconnect() {
        connection?.disconnect()
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 60_000
    }
}

/**
 * A Zenoh subscriber on the entity's whole `pubsub` tree, over its own session.
 *
 * The source the interface above was written for. Its own session rather than the publisher's, for
 * the reason `MonitorSync` owns none of the run's state: watching another boat must never be what
 * restarts, or is torn down by, a run. The subscriber callback runs on Zenoh's thread, so it does
 * nothing but hand the bytes on — `MonitorStore.accept` is the same cheap append the REST path uses.
 *
 * Unlike the REST stream there is no quiet-link problem to work around: Zenoh keeps its own
 * keep-alives and reconnects the transport underneath an open subscriber, so once declared this simply
 * waits to be cancelled.
 */
class ZenohMonitorSource(
    private val endpoints: List<String>,
    private val keyExpr: String,
    private val openSession: (List<String>) -> KeelsonSession,
) : MonitorSource {

    override suspend fun stream(onOpen: () -> Unit, onSample: (RemoteSample) -> Unit) {
        val session = withContext(Dispatchers.IO) { openSession(endpoints) }
        try {
            val subscriber = session.declareSubscriber(keyExpr) { key, bytes ->
                onSample(RemoteSample(key, System.currentTimeMillis(), bytes))
            }
            try {
                onOpen()
                awaitCancellation()
            } finally {
                runCatching { subscriber.close() }
            }
        } finally {
            runCatching { session.close() }
        }
    }
}

/**
 * The Monitor tab's link to another entity.
 *
 * Shaped like `PlatformSync` and scoped the same way — process-lifetime owner, opened when the tab is
 * on screen and closed when it is not, keyed on the route in `MainActivity` — but it owns **no Zenoh
 * session** and shares nothing with the publisher. Watching another boat is not part of a run, and a
 * run must never be restarted because somebody looked at the Monitor tab.
 */
class MonitorSync(private val openSession: ((List<String>) -> KeelsonSession)? = null) {

    val store = MonitorStore()

    private val _link = MutableStateFlow<MonitorLink>(MonitorLink.Idle)
    val link: StateFlow<MonitorLink> = _link.asStateFlow()

    private var scope: CoroutineScope? = null
    private var sse: SseMonitorSource? = null
    private var current: MonitorConfig? = null

    fun start(config: MonitorConfig) {
        if (scope != null) return
        if (config != current) store.clear()
        current = config
        val keyExpr = monitorKeyExpr(config.realm, config.entity)
        val endpoints = config.zenohEndpoints
        val (source, url) = if (endpoints != null && openSession != null) {
            if (endpoints.isEmpty()) {
                _link.value = MonitorLink.Failed(null, "no router configured to subscribe through")
                return
            }
            ZenohMonitorSource(endpoints, keyExpr, openSession) to endpoints.first()
        } else {
            val base = config.baseUrl
            if (base == null) {
                _link.value = MonitorLink.Failed(null, "no router configured to read from")
                return
            }
            val url = streamUrl(base, keyExpr)
            SseMonitorSource(url).also { sse = it } to url
        }
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        newScope.launch { runLoop(source, url) }
    }

    private suspend fun runLoop(source: MonitorSource, url: String) {
        var backoff = 0
        while (coroutineContext.isActive) {
            // A quiet reconnect keeps saying Streaming; flickering to Connecting every minute on an
            // entity that simply has nothing to say would read as a flaky link.
            if (_link.value !is MonitorLink.Streaming) _link.value = MonitorLink.Connecting(url)
            try {
                source.stream(
                    onOpen = {
                        _link.value = MonitorLink.Streaming(url)
                        backoff = 0
                        Log.i(TAG, "streaming $url")
                    },
                    onSample = store::accept,
                )
            } catch (e: SocketTimeoutException) {
                // Quiet, not broken: the plugin sends nothing for an entity with nothing to say.
                if (_link.value is MonitorLink.Streaming) continue
                _link.value = MonitorLink.Failed(url, "no answer from ${hostOf(url)}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!coroutineContext.isActive) return
                Log.w(TAG, "stream failed: ${e.message}")
                _link.value = MonitorLink.Failed(url, e.message ?: e.javaClass.simpleName)
            }
            delay(BACKOFF_MS[backoff.coerceAtMost(BACKOFF_MS.lastIndex)])
            backoff++
        }
    }

    fun stop() {
        val old = scope ?: return
        scope = null
        sse?.disconnect()
        sse = null
        old.cancel()
        _link.value = MonitorLink.Idle
        Log.i(TAG, "stream closed")
    }

    companion object {
        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000)
    }
}

/**
 * `{base}/{keyExpr}`, with the key expression's `@` and `*` left literal.
 *
 * The plugin reads the path as a key expression, so it must arrive as written; `URI` would
 * percent-encode nothing here anyway, but building it by hand keeps that a fact rather than a hope.
 */
internal fun streamUrl(base: String, keyExpr: String): String = "${base.trimEnd('/')}/$keyExpr"

private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url

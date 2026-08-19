package se.rise.logline.keelson

import io.zenoh.Config
import io.zenoh.Session
import io.zenoh.Zenoh
import io.zenoh.bytes.Encoding
import io.zenoh.bytes.ZBytes
import io.zenoh.keyexpr.intoKeyExpr
import io.zenoh.handlers.Callback
import io.zenoh.pubsub.Subscriber
import io.zenoh.query.Query
import io.zenoh.query.Queryable
import io.zenoh.query.Reply
import io.zenoh.query.intoSelector
import io.zenoh.sample.Sample
import kotlinx.coroutines.channels.Channel
import java.time.Duration
import io.zenoh.liveliness.LivelinessToken
import io.zenoh.ext.CacheConfig
import io.zenoh.ext.HeartbeatMode
import io.zenoh.ext.MissDetectionConfig
import io.zenoh.pubsub.AdvancedPublisher
import io.zenoh.qos.CongestionControl
import io.zenoh.qos.Priority
import io.zenoh.qos.QoS
import java.util.concurrent.atomic.AtomicBoolean

class KeelsonSession private constructor(private val session: Session) {

    /**
     * Declares a publisher with the subject's QoS stance — upstream policy, or a user override.
     *
     * The profile is required rather than defaulted, so a publisher cannot be declared without one and
     * silently inherit whatever Zenoh happens to default to.
     */
    fun declarePublisher(key: String, qos: SubjectQos): AdvancedPublisher {
        val keyExpr = key.intoKeyExpr().getOrThrow()
        // Positional, because the parameter names are not part of the published API surface:
        // (keyExpr, qos, encoding, reliability, cacheConfig, missDetectionConfig, publisherDetection).
        return session.declareAdvancedPublisher(
            keyExpr,
            QoS(qos.congestionControl, qos.priority, qos.express),
            Encoding.ZENOH_BYTES,
            qos.reliability,
            // A queryable cache of recent samples. A subscriber that missed some — because this phone
            // was off the air — can fetch them once the link is back, with us republishing nothing.
            // 4096 samples is ~74 s at 55 Hz and over an hour for the 1 Hz subjects.
            CacheConfig(CACHE_SAMPLES, QoS(CongestionControl.DROP, Priority.DATA, false)),
            // Heartbeats carry the latest sequence number, which is what lets a subscriber notice a gap
            // when nothing new is arriving to reveal one.
            MissDetectionConfig(HeartbeatMode.PeriodicHeartbeat(HEARTBEAT_MILLIS)),
            true,
        ).getOrThrow()
    }

    /**
     * Declares a liveliness token so consumers can discover this source before its first sample, and
     * get a leave event when it goes away — including when the process dies, since Zenoh drops the
     * token with the session.
     */
    fun declareLivelinessToken(key: String): LivelinessToken {
        val keyExpr = key.intoKeyExpr().getOrThrow()
        return session.liveliness().declareToken(keyExpr).getOrThrow()
    }

    /**
     * Hands the caller the raw result — the failure policy is not the protocol layer's to decide.
     *
     * Be careful what you read into a success: a `put` on a session whose router link has dropped
     * still succeeds locally and the sample goes nowhere. Measured on a Pixel 6, ~9000 successful puts
     * landed on an empty bus during a 26 s outage. Use [isConnectedToRouter] to know whether anything
     * is listening; this result only tells you the call itself did not fail.
     */
    fun publish(publisher: AdvancedPublisher, envelopeBytes: ByteArray): Result<Unit> =
        publisher.put(ZBytes.from(envelopeBytes))

    /**
     * Subscribe to a key expression, handing every sample's key and payload bytes to [onSample].
     *
     * **[onSample] runs on a Zenoh thread**, not on a dispatcher this app owns: do nothing in it but
     * pass the bytes on — a `trySend` into a `Channel` — and decode on `Dispatchers.Default`. Blocking
     * here blocks Zenoh's own receive path. Same discipline as the sensor `callbackFlow`s, for the
     * same reason.
     *
     * Safe on Android despite the `Zenoh.scout` crash in `Scout.kt`: every `io.zenoh.jni.callbacks.*`
     * interface marshals **primitives only** — `JNISubscriberCallback.run` takes
     * `(String, byte[], int, String, int, long, boolean, byte[], boolean, int, int)` — so the `Sample`
     * is built by Kotlin on the app's own class loader, with no native `FindClass` against an app
     * class. Verified against zenoh-kotlin 1.10.0.
     */
    fun declareSubscriber(key: String, onSample: (String, ByteArray) -> Unit): Subscriber<Unit> {
        val keyExpr = key.intoKeyExpr().getOrThrow()
        return session.declareSubscriber(
            keyExpr,
            Callback<Sample> { sample -> onSample(sample.keyExpr.toString(), sample.payload.toBytes()) },
        ).getOrThrow()
    }

    /**
     * Which entities are alive, from their liveliness tokens.
     *
     * The only bus-level enumeration keelson has. There is no subject, interface or well-known key
     * that lists platforms — the protocol specification's §5 liveliness tiers are it — so discovering
     * "what is out there" means asking for tokens and reading the entity chunk out of each key.
     *
     * Returns the matching token keys. Presence only: a token says a producer is alive and says
     * nothing about its name, its dimensions or its geometry, all of which have to come from
     * elsewhere.
     *
     * Wrapped in a `Result` rather than throwing because this is the newest native call site in the
     * app and the least load-bearing: an empty answer costs a nicety on a discovery screen, and
     * nothing else in the app depends on it.
     */
    suspend fun livelinessGet(
        selector: String,
        timeout: Duration = QUERY_TIMEOUT,
    ): Result<List<String>> =
        runCatching {
            val keyExpr = selector.intoKeyExpr().getOrThrow()
            val replies = session.liveliness()
                .get(keyExpr, Channel<Reply>(Channel.UNLIMITED), timeout)
                .getOrThrow()
            val out = mutableListOf<String>()
            // Zenoh closes the channel when the query finishes or times out, so this terminates.
            for (reply in replies) {
                reply.result.getOrNull()?.let { out += it.keyExpr.toString() }
            }
            out
        }

    /**
     * Answer queries on [key] with bytes computed at reply time.
     *
     * **[reply] runs on a Zenoh thread**, like [declareSubscriber]'s callback, so it must do nothing
     * slow: it is on Zenoh's receive path and a blocked reply blocks more than this query. Reading a
     * pre-rendered string out of a field is the intended shape.
     *
     * `complete = true` declares this queryable as a complete answer for the key rather than a partial
     * one, which is what a configuration reply is — there is no second responder to merge with.
     *
     * Note this is the one place in the app that puts **unwrapped** bytes on the wire. Everything on
     * pubsub is enclosed in a `core.Envelope`; an RPC reply in keelson's `configurable` interface is
     * raw JSON (`op.reply_ok(json.dumps(...).encode())` in the Python scaffolding), and wrapping it
     * would break every consumer that already speaks it.
     */
    fun declareQueryable(key: String, reply: () -> ByteArray): Queryable<Unit> {
        val keyExpr = key.intoKeyExpr().getOrThrow()
        return session.declareQueryable(
            keyExpr,
            Callback<Query> { query ->
                runCatching {
                    query.reply(query.keyExpr, ZBytes.from(reply()), encoding = Encoding.APPLICATION_JSON)
                }
            },
            complete = true,
        ).getOrThrow()
    }

    /**
     * Put one value on a key without declaring a publisher for it.
     *
     * For the write-once case: seeding a router storage with a library of records, each on its own key.
     * A declared publisher is the right shape for a stream and the wrong one for this — declaring and
     * undeclaring one per record would put an undeclare immediately behind every put, which is a race
     * worth not having.
     */
    fun put(key: String, envelopeBytes: ByteArray, qos: SubjectQos): Result<Unit> {
        val keyExpr = key.intoKeyExpr().getOrThrow()
        return session.put(
            keyExpr,
            ZBytes.from(envelopeBytes),
            Encoding.ZENOH_BYTES,
            QoS(qos.congestionControl, qos.priority, qos.express),
            reliability = qos.reliability,
        )
    }

    /**
     * Query whatever answers a selector, and return every reply's key and payload.
     *
     * This is how a router *storage* is read: a publisher's own `put` is gone the moment it is
     * delivered, but a key covered by `plugins/storage_manager` keeps its last value and answers a
     * query with it. That is what lets a client joining late learn a checklist's definition and its
     * progress without anyone republishing.
     *
     * A query against a key nothing stores is not an error — it returns an empty list, exactly as a
     * query answered by no one does. The caller cannot tell "no storage configured" from "nothing
     * stored yet", and neither can this: both mean there is nothing to bootstrap from.
     */
    suspend fun query(
        selector: String,
        timeout: Duration = QUERY_TIMEOUT,
    ): List<Pair<String, ByteArray>> {
        val sel = selector.intoSelector().getOrThrow()
        val replies = session.get(
            sel,
            channel = Channel<Reply>(Channel.UNLIMITED),
            timeout = timeout,
        ).getOrThrow()
        val out = mutableListOf<Pair<String, ByteArray>>()
        // The channel is closed by Zenoh when the query finishes or times out, so this terminates.
        for (reply in replies) {
            val sample = reply.result.getOrNull() ?: continue
            out += sample.keyExpr.toString() to sample.payload.toBytes()
        }
        return out
    }

    /** True when the session still holds a transport to at least one router. */
    fun isConnectedToRouter(): Boolean =
        !session.isClosed() && session.info().routersZid().getOrNull()?.isNotEmpty() == true

    fun close() {
        session.close()
    }

    companion object {
        /**
         * Per-publisher cache depth, in samples. Bounded in samples rather than seconds for the same
         * reason the outbox is: `SensorRate.Max` is legal and the gyroscope was measured at 442 Hz.
         */
        private const val CACHE_SAMPLES = 4096L

        private const val HEARTBEAT_MILLIS = 1000L

        /**
         * How long a [query] waits for replies. Generous: it is answered by the router across the
         * same link the session rides, which on a phone is as likely to be cellular as not, and this
         * runs once when a screen opens rather than on any hot path.
         */
        private val QUERY_TIMEOUT: Duration = Duration.ofSeconds(5)

        fun openClient(endpoints: List<String>, tls: TlsPaths = TlsPaths()): KeelsonSession {
            initZenohLogOnce()
            val config = Config.fromJson(clientConfigJson(endpoints, tls)).getOrThrow()
            val session = Zenoh.open(config).getOrThrow()
            return KeelsonSession(session)
        }
    }
}

private val logInitialised = AtomicBoolean(false)

/**
 * Bring up Zenoh's Rust logger, at most once in this process.
 *
 * A second call aborts the whole app with `Builder::init should not be called after logger initialized`
 * — a native SIGABRT, not a catchable exception. Every entry point into Zenoh has to come through here:
 * opening a session *and* scouting, since either can be the first one in a process.
 */
internal fun initZenohLogOnce() {
    if (logInitialised.compareAndSet(false, true)) {
        Zenoh.initLogFromEnvOr("error")
    }
}

/**
 * Paths to the PEM files backing a TLS connection. Absolute paths in app-private storage — Zenoh's
 * `transport/link/tls` fields take file paths, with no inline or base64 form in 1.10.
 */
data class TlsPaths(
    val rootCa: String? = null,
    val clientCertificate: String? = null,
    val clientKey: String? = null,
)

/** True for locators that ride TLS, so the scheme alone decides — no separate toggle to drift. */
internal fun endpointNeedsTls(endpoint: String): Boolean =
    endpoint.startsWith("tls/") || endpoint.startsWith("quic/")

/** Schemes Zenoh can open a transport on. `tls` and `quic` additionally need the credentials. */
private val ENDPOINT_SCHEMES = listOf("tcp", "udp", "tls", "quic", "ws", "wss", "unixsock-stream", "serial")

/**
 * Why a typed locator will not work, or null when it will.
 *
 * Checked when it is typed rather than when a run starts: a missing scheme or a missing port used to be
 * accepted silently and only surfaced minutes later as a session that would not open, with nothing on
 * screen connecting the failure to the typo.
 *
 * Deliberately shallow — this catches shape, not reachability. Whether anything answers is what the
 * scan and the connection state are for.
 */
internal fun validateEndpoint(endpoint: String): String? {
    val text = endpoint.trim()
    if (text.isEmpty()) return "Enter a locator, e.g. tcp/192.168.1.42:7447"
    val scheme = text.substringBefore('/', missingDelimiterValue = "")
    if (scheme.isEmpty()) {
        return "Needs a scheme — try tcp/$text or tls/$text"
    }
    if (scheme.lowercase() !in ENDPOINT_SCHEMES) {
        return "\"$scheme\" is not a Zenoh scheme. Use ${ENDPOINT_SCHEMES.take(4).joinToString("/")}."
    }
    val rest = text.substringAfter('/')
    if (rest.isEmpty()) return "Needs a host and port after $scheme/"
    // serial and unixsock-stream address a device path, not a host:port, so the rest is theirs.
    if (scheme.lowercase() in setOf("serial", "unixsock-stream")) return null
    val port = rest.substringAfterLast(':', missingDelimiterValue = "")
    if (port.isEmpty() || rest.substringBeforeLast(':').isEmpty()) {
        return "Needs a port, e.g. $text:7447"
    }
    val number = port.toIntOrNull() ?: return "\"$port\" is not a port number"
    if (number !in 1..65535) return "Port must be between 1 and 65535"
    return null
}

/**
 * True for locators on the local network, which Android 17 gates behind `ACCESS_LOCAL_NETWORK`.
 *
 * Local Network Protections turn a LAN address into a runtime-permission question, so a router on the
 * boat's own network cannot be reached until the user grants it. Without the permission the connection
 * fails as `EPERM` deep inside Zenoh, with nothing on screen to explain it — hence deciding up front,
 * from the locator, whether to ask.
 *
 * Loopback is deliberately **not** local: `127.0.0.1` is exempt from the protections, and treating it
 * as local would prompt for the one address that never needs it.
 *
 * A *hostname* cannot be classified without resolving it, which this must not do on the main thread; a
 * name pointing at a LAN box is therefore read as remote, and the user has to grant the permission from
 * the scan instead. Discovered routers always arrive as literal addresses, so the common path is exact.
 */
internal fun isLocalEndpoint(endpoint: String): Boolean {
    val host = endpoint.substringAfter('/', "").substringBeforeLast(':').trim('[', ']')
    if (host.isEmpty()) return false
    val octets = host.split('.')
    if (octets.size != 4 || octets.any { it.toIntOrNull() == null }) {
        // Only IPv6 literals get this far as addresses; link-local (fe80::) and unique-local (fc00::/7)
        // are the local ones, and ::1 is loopback.
        val v6 = host.lowercase()
        return v6.startsWith("fe80:") || v6.startsWith("fc") || v6.startsWith("fd")
    }
    val (a, b) = octets[0].toInt() to octets[1].toInt()
    return when {
        a == 127 -> false
        a == 10 -> true
        a == 192 && b == 168 -> true
        a == 172 && b in 16..31 -> true
        a == 169 && b == 254 -> true
        // 100.64/10 is carrier NAT, not a local network, and 224/4 multicast is not an endpoint.
        else -> false
    }
}

/**
 * The session config.
 *
 * `client` mode with multicast scouting disabled, so the app never joins a bus it was not told about.
 * The endpoints are tried in order and the session attaches to whichever answers first — a failover
 * list, not a fan-out. A `tls/` or `quic/` endpoint additionally gets a `transport/link/tls` block;
 * mTLS is enabled only when both a client certificate and its key are present, because the cloud
 * router demands a client certificate and half a credential is not a usable state.
 *
 * @throws IllegalStateException naming the missing file, rather than letting it surface later as an
 *   opaque handshake failure.
 */
internal fun clientConfigJson(endpoints: List<String>, tls: TlsPaths): String {
    require(endpoints.isNotEmpty()) { "at least one router endpoint is required" }
    // Zenoh has one global transport/link/tls block, not one per locator, so the question is whether
    // *any* endpoint rides TLS — and the error has to name which, since a mixed list is normal.
    val needTls = endpoints.filter(::endpointNeedsTls)
    val transport = if (needTls.isEmpty()) "" else {
        val rootCa = tls.rootCa
            ?: throw IllegalStateException(
                "${needTls.joinToString(", ")} needs TLS but no root CA certificate has been " +
                    "imported — add one under Router security in Settings"
            )
        val mtls = if (tls.clientCertificate != null && tls.clientKey != null) {
            ""","enable_mtls":true,""" +
                """"connect_certificate":"${tls.clientCertificate.escaped()}",""" +
                """"connect_private_key":"${tls.clientKey.escaped()}""""
        } else {
            ""
        }
        ""","transport":{"link":{"tls":{"root_ca_certificate":"${rootCa.escaped()}"$mtls}}}"""
    }
    val locators = endpoints.joinToString(",") { """"${it.escaped()}"""" }
    // Scouting stays off for the *session*: the scan is a separate, explicit action, so the app still
    // only ever connects to endpoints someone chose. (In client mode with endpoints configured,
    // multicast autoconnect is unreachable anyway — all enabling it would add is a listener that makes
    // the phone answer other nodes' scouts forever.)
    return """{"mode":"client","connect":{"endpoints":[$locators]},""" +
        """"scouting":{"multicast":{"enabled":false}}$transport}"""
}

internal fun String.escaped(): String = replace("\\", "\\\\").replace("\"", "\\\"")

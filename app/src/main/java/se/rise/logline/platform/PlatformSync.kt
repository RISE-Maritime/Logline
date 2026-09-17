package se.rise.logline.platform

import android.content.Context
import android.util.Log
import io.zenoh.liveliness.LivelinessToken
import io.zenoh.pubsub.Subscriber
import io.zenoh.query.Queryable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import core.EnvelopeOuterClass.Envelope
import keelson.Primitives.TimestampedString
import keelson.interfaces.ErrorResponseOuterClass.ErrorResponse
import se.rise.logline.calibrate.PlatformCalibration
import se.rise.logline.calibrate.parsePlatformGeometry
import se.rise.logline.calibrate.toPlatformGeometryJson
import se.rise.logline.config.TlsCredentialStore
import se.rise.logline.keelson.KeelsonSession
import se.rise.logline.keelson.Subjects
import se.rise.logline.keelson.ZenohBinding
import se.rise.logline.keelson.entityIdFromKey
import se.rise.logline.keelson.qosForSubject
import se.rise.logline.keelson.rpcInterfaceLivelinessKey
import se.rise.logline.keelson.rpcKey

/** Everything the sync needs to open a link and know who it is. */
data class PlatformSyncConfig(
    val endpoints: List<String>,
    val realm: String,
    val calibrationSource: String,
    /** The platforms this phone holds, offered over `get_config` and shared as the library. */
    val platforms: List<PlatformCalibration>,
    /** This install's identity, so its own library echoes are dropped. */
    val origin: String,
    val registryVersion: Long,
    val shareLibrary: Boolean,
)

/** A platform seen on the bus that the operator may adopt into the library. */
data class DiscoveredPlatform(
    val entityId: String,
    /** Null until a `configuration_json` for it has been decoded — liveliness carries no name. */
    val geometry: PlatformCalibration?,
) {
    val hasGeometry: Boolean get() = geometry != null
}

enum class DiscoveryState { Idle, Scanning, Done, Failed }

data class PlatformSyncState(
    val discovery: DiscoveryState = DiscoveryState.Idle,
    val discovered: List<DiscoveredPlatform> = emptyList(),
    val failure: String? = null,
    /** A library another station published, waiting for the screen to apply it. */
    val incoming: RemotePlatformRegistry? = null,
)

/**
 * The phone as a platform peer: discoverable, askable, and sharing its library.
 *
 * **It owns its own [KeelsonSession], separate from `SensorPublisher`'s** — the same arrangement, and
 * the same argument, as `ChecklistSync`. Platforms are surveyed and edited with logging *stopped*, and the
 * publisher's session lives and dies with a run, so tying platform work to it would mean discovery
 * only working while data was already going out. Three sessions in one process are fine;
 * `initZenohLogOnce()` already guards the one thing that may only happen once.
 *
 * Three jobs, none of which the publisher should be doing:
 *
 * - **Discovery.** keelson has no wire-level list of platforms — no subject, no interface, no
 *   well-known key — so this asks two questions and merges the answers: liveliness for entity ids, and
 *   a listening window on `configuration_json` for the documents themselves.
 * - **`get_config`.** Answering the RPC crowsnest uses to enrich a platform it already knows about.
 * - **The shared library**, on a deliberately non-keelson key. See [PlatformRegistry].
 *
 * Nothing here touches the publish path, and nothing here is created by Compose.
 */
class PlatformSync(private val appContext: Context) {

    private val _state = MutableStateFlow(PlatformSyncState())
    val state: StateFlow<PlatformSyncState> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var session: KeelsonSession? = null
    private var queryables: List<Queryable<Unit>> = emptyList()

    /**
     * The `configurable/v1` interface tokens, one per platform — held apart from [queryables] only because
     * they are a different Zenoh type, not a different lifetime. Undeclared on the same path: §3.5 says
     * a source MUST NOT hold a token for an interface it does not currently serve, and this session
     * lives only while a platform screen is up.
     */
    private var interfaceTokens: List<LivelinessToken> = emptyList()

    /**
     * Long-lived subscribers, guarded because two coroutines append to it — [subscribeLibrary] on the
     * start scope and [discover] on its own. A lost update there drops a `Subscriber` reference that
     * [stop] then never closes, leaking it for the life of the process.
     */
    private val subscribers = mutableListOf<Subscriber<Unit>>()
    private val subscriberLock = Any()

    private var config: PlatformSyncConfig? = null

    private fun addSubscriber(subscriber: Subscriber<Unit>) {
        synchronized(subscriberLock) { subscribers += subscriber }
    }

    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Open a session and start serving. Idempotent — a second call while running does nothing, so a
     * screen may call it on every entry without tracking whether it is already up.
     */
    fun start(newConfig: PlatformSyncConfig) {
        if (scope != null) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope
        config = newConfig
        // Cleared synchronously, before anything is launched. A failure left standing from an earlier
        // attempt would keep the scan button disabled after the endpoint or the credential that caused
        // it had been fixed — the same trap `SensorPublisher.start()` clears its error for.
        _state.update { it.copy(failure = null, discovery = DiscoveryState.Idle) }

        newScope.launch {
            try {
                val opened = withContext(Dispatchers.IO) {
                    KeelsonSession.openClient(
                        newConfig.endpoints,
                        TlsCredentialStore(appContext).paths(),
                    )
                }
                session = opened
                declareConfigQueryables(opened, newConfig)
                if (newConfig.shareLibrary) {
                    subscribeLibrary(opened, newConfig)
                    // Published on open rather than on edit: the config carries the library and its
                    // version, and the screen restarts this session whenever either changes, so this
                    // is the one place that has to know how to share. A station joining later reads
                    // it from the router's storage — see PlatformRegistry.
                    publishLibrary(opened, newConfig)
                }
                // The control case for the same logging in `ChecklistSync`: this half of the
                // route-scoping was watched working on a device, so a run where this line appears and
                // the checklist one does not points at the checklist gate rather than at the routing.
                Log.i(TAG, "session open on ${newConfig.realm}")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // A missing TLS credential and an unparseable endpoint both land here, and both are
                // setup failures the person can act on — so the reason is kept, not just the state.
                Log.w(TAG, "platform sync failed to start", t)
                // Failed rather than left Idle: without a session `discover()` returns at its first
                // line, so a screen that only knew "not scanning" would offer a button that silently
                // does nothing. A missing TLS credential is exactly this case.
                _state.update {
                    it.copy(
                        discovery = DiscoveryState.Failed,
                        failure = t.message ?: t.javaClass.simpleName,
                    )
                }
            }
        }
    }

    /**
     * Fire-and-forget, like `ChecklistSync.stop()`: the fields are cleared synchronously and the
     * session is closed off the cancelled scope. The discovered list stays — closing the link is not
     * the same as forgetting what was found.
     */
    fun stop() {
        val runScope = scope ?: return
        val open = session
        val openQueryables = queryables
        val openTokens = interfaceTokens
        val openSubscribers = synchronized(subscriberLock) { subscribers.toList().also { subscribers.clear() } }
        scope = null
        session = null
        queryables = emptyList()
        interfaceTokens = emptyList()
        config = null
        // A scan in flight is cancelled with the scope, so its "Done" is never reached. Left as
        // Scanning, the button stays disabled and `discover()` early-returns on the same check —
        // for the life of the process, since this object outlives every screen. Any settings change
        // during a scan restarts the session and lands here, so this is the common path, not a rare
        // one.
        _state.update {
            if (it.discovery == DiscoveryState.Scanning) it.copy(discovery = DiscoveryState.Idle) else it
        }
        runScope.cancel()
        closeScope.launch {
            runCatching { openQueryables.forEach { it.close() } }
            // Before the session goes, so a consumer gets a leave event now rather than one waiting on
            // transport teardown — the same reason SensorPublisher undeclares its own tokens by hand.
            openTokens.forEach { runCatching { it.undeclare() } }
            runCatching { openSubscribers.forEach { it.close() } }
            runCatching { open?.close() }
            // After the close, for the reason given on `ChecklistSync.stop()`.
            Log.i(TAG, "session closed")
        }
    }

    fun clearIncoming() = _state.update { it.copy(incoming = null) }

    // ── discovery ───────────────────────────────────────────────────────────────────────────────

    /**
     * Find platforms on the bus.
     *
     * Two probes, because neither alone is enough. Liveliness answers "which entities are alive" and
     * nothing else — no name, no dimensions, no geometry. The documents come from listening to
     * `configuration_json` for slightly longer than a platform connector's republish interval, which
     * is what makes a passive listen sufficient: every one of them repeats every ten seconds precisely
     * so a late joiner does not have to ask.
     *
     * A router `get` is deliberately **not** used: no storage covers `configuration_json`, so a query
     * returns an empty list that looks exactly like an empty bus.
     */
    fun discover() {
        val runScope = scope ?: return
        val open = session ?: return
        val current = config ?: return
        if (_state.value.discovery == DiscoveryState.Scanning) return

        _state.update {
            it.copy(discovery = DiscoveryState.Scanning, discovered = emptyList(), failure = null)
        }

        runScope.launch {
            val found = linkedMapOf<String, DiscoveredPlatform>()
            // The callback hands raw bytes over and does nothing else. Decoding a `configuration_json`
            // means a protobuf parse and then a JSON parse of a document of somebody else's choosing,
            // and this callback runs on Zenoh's own receive path — the same reason `declareQueryable`
            // computes its reply in advance. `UNLIMITED` because the producer is that receive path and
            // must never be the thing that blocks.
            val documents = Channel<Pair<String, ByteArray>>(Channel.UNLIMITED)

            // Documents first, because they are the half that carries anything worth reading — and
            // skipped entirely where a subscription would abort the process. The `runCatching` below
            // protects the *declaration*; it cannot protect the delivery, which is a native abort on
            // Zenoh's own thread. See `ZenohBinding.SUBSCRIPTIONS_SAFE`.
            //
            // The scan still runs: the liveliness get below supplies entity ids, so discovery degrades
            // to platforms named but not described rather than to nothing. `documentsUnavailable` is
            // what makes that visible instead of reading as "these platforms have no geometry".
            val subscriber = if (ZenohBinding.SUBSCRIPTIONS_SAFE) {
                runCatching {
                    open.declareSubscriber(
                        "${current.realm}/@v0/*/pubsub/${Subjects.CONFIGURATION_JSON}/*"
                    ) { key, payload -> documents.trySend(key to payload) }
                }.onFailure { Log.w(TAG, "configuration_json subscription failed", it) }.getOrNull()
            } else {
                documents.close()
                null
            }

            val reader = launch {
                for ((key, payload) in documents) {
                    val entityId = entityIdFromKey(key) ?: continue
                    val platform = decodeConfigurationJson(payload)?.let { parsePlatformGeometry(it, entityId) }
                    // A document always wins over a bare liveliness sighting of the same entity.
                    if (platform != null || entityId !in found) {
                        found[entityId] = DiscoveredPlatform(entityId, platform)
                    }
                    publishFound(found)
                }
            }

            // Ids second. Wrapped in a Result inside the session: this is the newest native call site
            // in the app, and an empty answer here costs a nicety rather than the scan.
            open.livelinessGet("${current.realm}/@v0/*/**").onSuccess { keys ->
                keys.mapNotNull { entityIdFromKey(it) }.forEach { entityId ->
                    found.getOrPut(entityId) { DiscoveredPlatform(entityId, null) }
                }
                publishFound(found)
            }.onFailure { Log.w(TAG, "liveliness scan failed; continuing on documents alone", it) }

            try {
                delay(DISCOVERY_WINDOW_MILLIS)
            } finally {
                // Closed at the end of the window, not left to `stop()`. A subscriber is not torn
                // down by cancelling the scope that declared it, so without this every scan leaves a
                // live callback behind: the next scan clears the list and the previous scan's
                // callback then republishes its own stale map over the fresh one.
                withContext(NonCancellable) {
                    runCatching { subscriber?.close() }
                    documents.close()
                    reader.join()
                }
            }
            _state.update { it.copy(discovery = DiscoveryState.Done) }
        }
    }

    /**
     * `found` is confined to the scan coroutine — the subscriber callback only hands bytes to a
     * channel, and the reader and the liveliness continuation are both on that one coroutine — so no
     * lock is needed here and none would help.
     */
    private fun publishFound(found: Map<String, DiscoveredPlatform>) {
        val snapshot = found.values.sortedBy { it.entityId }
        _state.update { it.copy(discovered = snapshot) }
    }

    // ── get_config ──────────────────────────────────────────────────────────────────────────────

    /**
     * Answer a configuration query for every platform in the library, on both key shapes.
     *
     * The **wire** document, provenance included — the same string `configuration_json` carries, so one
     * platform cannot give two different answers depending on how it was asked.
     *
     * Rendered once here rather than in the callback: that callback runs on a Zenoh thread, on its
     * receive path, and serialising a document there would put JSON generation in front of every other
     * query the session is handling.
     */
    private fun declareConfigQueryables(open: KeelsonSession, current: PlatformSyncConfig) {
        queryables = current.platforms.flatMap { platform ->
            val document = platform.toPlatformGeometryJson(provenance = true).toByteArray(Charsets.UTF_8)
            // One key now, the specification's. The pre-interface shape crowsnest used to probe was
            // served alongside this until crowsnest moved to `configurable/v1` with a wildcard source.
            val keys = listOf(
                rpcKey(current.realm, platform.entityId, "configurable", "v1", "get_config", current.calibrationSource),
            )
            val answering = keys.mapNotNull { key ->
                runCatching { open.declareQueryable(key) { document } }
                    .onFailure { Log.w(TAG, "queryable for $key failed; continuing without it", it) }
                    .getOrNull()
            }
            // Serialised here rather than in the callback, like the document above and for the same
            // reason: that callback runs on Zenoh's receive path.
            val refusal = setConfigRefusal().toByteArray()
            val setConfigKey =
                rpcKey(current.realm, platform.entityId, "configurable", "v1", "set_config", current.calibrationSource)
            val refusing = runCatching { open.declareRefusingQueryable(setConfigKey, refusal) }
                .onFailure { Log.w(TAG, "queryable for $setConfigKey failed; continuing without it", it) }
                .getOrNull()
            answering + listOfNotNull(refusing)
        }

        // The interface token, once the procedures behind it are up — §3.5 asks for exactly that
        // ordering, and it is what stops a consumer discovering the interface a moment before anything
        // answers on it.
        interfaceTokens = current.platforms.mapNotNull { platform ->
            val key = rpcInterfaceLivelinessKey(
                current.realm,
                platform.entityId,
                "configurable",
                "v1",
                current.calibrationSource,
            )
            runCatching { open.declareLivelinessToken(key) }
                .onFailure { Log.w(TAG, "interface token for $key failed; continuing without it", it) }
                .getOrNull()
        }
    }


    // ── the shared library ──────────────────────────────────────────────────────────────────────

    private fun subscribeLibrary(open: KeelsonSession, current: PlatformSyncConfig) {
        // **The standing one, and the one that was going to bite first.** `platform_registry` is
        // storage-backed on this fleet's router, so every sample carries a timestamp and the first
        // library any station shares would abort the app — silently latent until somebody shared one.
        // See `ZenohBinding.SUBSCRIPTIONS_SAFE`. Publishing this phone's own library is unaffected.
        if (!ZenohBinding.SUBSCRIPTIONS_SAFE) return
        val key = PlatformRegistry.key(current.realm)
        val subscriber = runCatching {
            open.declareSubscriber(key) { _, payload ->
                val remote = decodePlatformRegistry(payload)
                if (shouldApplyRemote(remote, current.registryVersion, current.origin)) {
                    _state.update { it.copy(incoming = remote) }
                }
            }
        }.onFailure { Log.w(TAG, "library subscription failed", it) }.getOrNull()
        subscriber?.let { addSubscriber(it) }
    }

    /**
     * Share the library.
     *
     * A plain `put` rather than a declared publisher: this is a write-once record, and declaring a
     * publisher for it would put an undeclare immediately behind every write. The QoS comes from the
     * subject registry's default, which is what an unlisted subject inherits upstream.
     *
     * The timestamp is [System.currentTimeMillis] and that is the correct clock here: it records when
     * a person edited a library, not when a sensor observed anything.
     */
    private fun publishLibrary(open: KeelsonSession, current: PlatformSyncConfig) {
        if (current.platforms.isEmpty()) return
        val bytes = encodePlatformRegistry(
            version = current.registryVersion,
            origin = current.origin,
            updatedAtEpochMillis = System.currentTimeMillis(),
            platforms = current.platforms,
        )
        // Raw JSON, not an envelope — the whole point of the token not being a keelson subject.
        runCatching {
            open.put(PlatformRegistry.key(current.realm), bytes, qosForSubject(Subjects.CONFIGURATION_JSON))
        }.onFailure { Log.w(TAG, "publishing the library failed", it) }
    }

    companion object {
        private const val TAG = "PlatformSync"

        /**
         * How long the discovery listen runs.
         *
         * Slightly over `platform-geometry2keelson.py`'s ten-second republish interval, which is what
         * makes a passive listen enough: every platform connector repeats within it. Shorter and a
         * platform that had just published would be missed and read as absent.
         */
        private const val DISCOVERY_WINDOW_MILLIS = 12_000L
    }
}

/**
 * The document out of a `configuration_json` payload, whichever way it arrived.
 *
 * **Two shapes for one document, and both are real.** On pubsub it is an enveloped
 * `keelson.TimestampedString` — that is what this app and `make_configurable` publish. As a
 * `get_config` reply it is raw JSON bytes. Crowsnest carries the same fork (`keelsonWorker.js`, "
 * Fallback: non-enveloped raw JSON"), so anything reading these documents needs both paths or it
 * silently fails on half the sources.
 */
internal fun decodeConfigurationJson(bytes: ByteArray): String? {
    runCatching {
        val payload = Envelope.parseFrom(bytes).payload.toByteArray()
        TimestampedString.parseFrom(payload).value.takeIf { it.isNotBlank() }
    }.getOrNull()?.let { return it }
    // Not enveloped: an RPC reply, or anything else putting the document on directly.
    return bytes.toString(Charsets.UTF_8).takeIf { it.trimStart().startsWith("{") }
}

/**
 * The refusal `set_config` answers with, every time.
 *
 * **The app serves `configurable/v1` read-only, and this is what makes that legal.** §3.6's
 * full-interface rule says a source advertising an interface must answer every procedure in it —
 * with a typed response naming the limitation "never silence" — so declaring the token obliges the
 * phone to reply to `set_config` whether or not it will ever comply. It will not: a platform's geometry
 * is edited on the phone or taken from the shared library through [mergeRemotePlatforms], which
 * deliberately never deletes a platform this phone is publishing and never accepts remote *policy*. An
 * unauthenticated write from anyone on the fleet bus would go around all of that.
 *
 * **`UNSUPPORTED` is the code, and it has to be the code.** This was `PERMISSION_DENIED` until
 * `0.6.0-pre.18`, when `ErrorResponse.Code` gained a value for a permanent structural refusal — the
 * signal §3.6 had always asked for here and the enum had no room for, which is why the permanence used
 * to live only in the description below. That upgrade also made the old choice actively wrong rather
 * than merely approximate: `PERMISSION_DENIED`'s upstream comment now reads "refused under current
 * conditions… **the answer MAY change**", which is the opposite of what this refusal means, and §3.6
 * gained the matching rule in normative text — `UNSUPPORTED` pairs with `COMMAND_RESULT_UNSUPPORTED`
 * and `PERMISSION_DENIED` with `COMMAND_RESULT_DENIED`, and *"the distinction has to survive in the
 * code, not only in `error_description`, because a UI reading the enum alone decides whether to keep
 * the procedure callable."*
 *
 * The description still says "permanent" in words, and still should: the enum is what a UI branches on,
 * the sentence is what a person reads. `UNAVAILABLE` remains the wrong answer for the reason it always
 * was — it reads as "not ready yet" and invites a retry that can never succeed.
 */
internal fun setConfigRefusal(): ErrorResponse = ErrorResponse.newBuilder()
    .setCode(ErrorResponse.Code.UNSUPPORTED)
    .setErrorDescription(
        "This platform is configured on the phone and never remotely; the refusal is permanent " +
            "and by design, not a transient condition. Read it with get_config, or share a platform " +
            "library on platform_registry.",
    )
    .build()

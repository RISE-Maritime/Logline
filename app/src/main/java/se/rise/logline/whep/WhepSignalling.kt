package se.rise.logline.whep

import android.util.Log
import keelson.interfaces.ErrorResponseOuterClass.ErrorResponse
import keelson.interfaces.whep_proxy.WHEPProxyOuterClass.WHEPRequest
import keelson.interfaces.whep_proxy.WHEPProxyOuterClass.WHEPResponse
import io.zenoh.query.ReplyError
import se.rise.logline.keelson.KeelsonSession
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import se.rise.logline.keelson.rpcKey
import java.time.Duration

private const val TAG = "Whep"

/**
 * The WebRTC handshake, carried over Zenoh.
 *
 * **Zenoh carries the offer and the answer and nothing else.** keelson's `mediamtx` connector is a
 * signalling proxy: it declares a queryable, forwards the SDP offer to MediaMTX's HTTP WHEP endpoint at
 * `{whep_host}/{path}/whep`, and replies with the answer. Its own source says it "publishes no pubsub
 * subjects" — the media never touches the bus, it flows peer-to-peer over WebRTC/ICE once this
 * exchange has happened. Nothing here decodes a frame; see `ui/CameraFeed.kt` for the other half.
 *
 * This is the same exchange crowsnest performs in `src/services/whepRpc.js`, deliberately so: if the
 * two disagree about the key or the encoding, one of them is talking to nobody.
 */
object Whep {

    /** The interface and version the connector serves — `WHEPProxy` in `keelson/interfaces`. */
    const val INTERFACE = "whep_proxy"
    const val VERSION = "v1"
    const val PROCEDURE = "whep_signal"

    /**
     * How long to wait for an answer.
     *
     * Ten seconds, and not the app's usual query timeout: the query round-trips to the vessel and the
     * proxy itself then waits on MediaMTX's HTTP endpoint before it can reply. Crowsnest budgets the
     * same for the same reason.
     */
    val TIMEOUT: Duration = Duration.ofSeconds(10)

    /**
     * Offer, and return the answer.
     *
     * [path] is MediaMTX's own path name — the `<pathname>` in `MTX_PATHS_<pathname>_SOURCE` — not a
     * keelson subject. It is the stream being asked for, and nothing on the bus advertises it.
     *
     * **Both key shapes are asked at once, and the first answer wins.** They are genuinely both in the
     * wild: keelson's current source builds the specified
     * `.../@rpc/whep_proxy/v1/whep_signal/{responder}`, and the connector in
     * `ghcr.io/rise-maritime/keelson:latest` — 0.5.3, verified by running it — declares the older
     * `.../@rpc/whep_signal/{responder}` with no interface or version chunk. Crowsnest sends only the
     * modern one, so it cannot be talking to a 0.5.3 proxy.
     *
     * Concurrently rather than in sequence: a wrong guess costs the full [TIMEOUT], and ten seconds of
     * nothing before a fallback is indistinguishable from a camera that is off. Neither query disturbs
     * the other — a key nobody has declared is simply unanswered.
     */
    suspend fun signal(
        session: KeelsonSession,
        realm: String,
        entityId: String,
        responderId: String,
        path: String,
        offerSdp: String,
    ): Result<String> = coroutineScope {
        val payload = WHEPRequest.newBuilder().setPath(path).setSdp(offerSdp).build().toByteArray()
        val keys = listOf(
            modernKey(realm, entityId, responderId),
            legacyKey(realm, entityId, responderId),
        )
        val attempts = keys.map { key -> async { key to session.call(key, payload, TIMEOUT) } }
        val failures = mutableListOf<Pair<String, Throwable>>()
        attempts.forEach { attempt ->
            val (key, result) = attempt.await()
            result
                .mapCatching { WHEPResponse.parseFrom(it).sdp }
                .onSuccess { sdp ->
                    attempts.forEach { it.cancel() }
                    return@coroutineScope Result.success(sdp)
                }
                .onFailure { failures += key to it }
        }
        // The spec's key first: with nothing listening on either, both failures say the same nothing,
        // and naming the modern one is the more useful thing to put in front of somebody.
        failures.forEach { (key, cause) -> Log.i(TAG, "whep_signal failed on $key", cause) }
        Result.failure(describe(failures.first().second))
    }

    /** The shape keelson's current `construct_rpc_key` builds, and the one crowsnest queries. */
    internal fun modernKey(realm: String, entityId: String, responderId: String): String = rpcKey(
        realm = realm,
        entityId = entityId,
        interfaceName = INTERFACE,
        version = VERSION,
        procedure = PROCEDURE,
        responderId = responderId,
    )

    /**
     * The shape `keelson:0.5.3` actually declares — no interface, no version.
     *
     * Verified by running that image and reading its log: `Declaring queryable on key:
     * rise/@v0/testcam/@rpc/whep_signal/mediamtx`. The same class of drift as the platform-config
     * key this app used to dual-serve for crowsnest, and to be deleted the same way that one was:
     * once no deployed proxy serves it. Note the gate there turned out to be *deployment* rather than
     * the consumer merging a fix — the two are days apart, and a phone that stops answering in
     * between is a station that cannot read a config.
     */
    internal fun legacyKey(realm: String, entityId: String, responderId: String): String =
        "$realm/@v0/$entityId/@rpc/$PROCEDURE/$responderId"

    /**
     * A refusal in the words the responder used.
     *
     * §3.6 says an interface answers every procedure, with a typed refusal where it cannot comply — so
     * a `reply_err` here is a *message*, not a transport failure, and throwing away its payload would
     * turn "no such path" into a timeout as far as anybody reading the screen is concerned. The app
     * already serialises one of these itself in `setConfigRefusal()`; this is the same wire shape read
     * from the other end.
     */
    private fun describe(cause: Throwable): Throwable {
        val error = cause as? ReplyError ?: return cause
        return runCatching {
            val decoded = ErrorResponse.parseFrom(requireNotNull(error.payload).toBytes())
            IllegalStateException(decoded.errorDescription.ifBlank { decoded.code.name })
        }.getOrDefault(cause)
    }
}

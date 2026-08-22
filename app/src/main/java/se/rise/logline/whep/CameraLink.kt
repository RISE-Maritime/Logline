package se.rise.logline.whep

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.rise.logline.config.Settings
import se.rise.logline.config.TlsCredentialStore
import se.rise.logline.keelson.KeelsonSession

private const val TAG = "CameraLink"

/**
 * The Zenoh side of watching a camera.
 *
 * **A session per handshake, opened and closed around it.** That looks wasteful next to `ChecklistSync`
 * and `PlatformSync`, which hold one for as long as their screens are up, and it is the right shape
 * here for a reason those two do not have: once the SDP has been exchanged the media flows
 * peer-to-peer and Zenoh is not in the path at all. Holding a session open for the length of a video
 * call would be holding it open for nothing.
 *
 * Two Zenoh sessions in one process are fine — `initZenohLogOnce()` guards the one thing that may only
 * happen once — and this one exists for a few seconds at a time.
 */
class CameraLink(private val appContext: Context) {

    /**
     * Offer, and answer.
     *
     * The whole exchange, including opening and closing the session, on IO. A failure here is
     * ordinary — no camera, no proxy, wrong path — and comes back as a message the card can show
     * rather than an exception.
     */
    suspend fun signal(settings: Settings, offerSdp: String): Result<String> =
        withContext(Dispatchers.IO) {
            val session = runCatching {
                KeelsonSession.openClient(
                    settings.routerEndpoints,
                    TlsCredentialStore(appContext).paths(),
                )
            }.getOrElse { return@withContext Result.failure(it) }

            try {
                Whep.signal(
                    session = session,
                    realm = settings.realm,
                    entityId = settings.cameraEntityId,
                    responderId = settings.cameraResponderId,
                    path = settings.cameraPath,
                    offerSdp = offerSdp,
                )
            } finally {
                // Closed on every path: the media does not need it, and a session left behind by a
                // failed handshake is one nobody will ever close.
                runCatching { session.close() }.onFailure { Log.i(TAG, "closing camera session", it) }
            }
        }
}

/** Whether a camera is configured at all. Blank entity or path means the card does not appear. */
fun Settings.hasCamera(): Boolean = cameraEntityId.isNotBlank() && cameraPath.isNotBlank()

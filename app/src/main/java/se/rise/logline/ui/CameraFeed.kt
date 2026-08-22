package se.rise.logline.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.rise.logline.config.Settings
import se.rise.logline.whep.CameraLink

/**
 * A live camera, in a `WebView`.
 *
 * **The WebView is here for its WebRTC stack and nothing else.** Android gives an app no
 * `RTCPeerConnection` of its own, and bundling libwebrtc would add tens of megabytes across four ABIs
 * on top of the 66 MB of Zenoh natives the APK already carries — for a decoder the system already has.
 * The page in `assets/whep.html` is the same handful of calls crowsnest makes.
 *
 * **Signalling stays in Kotlin**, which is not a stylistic choice: the handshake is a Zenoh *query*,
 * and Zenoh's REST plugin cannot serve one — an HTTP POST there is a publish, not a queryable
 * invocation, so it never reaches the proxy. So the offer comes out over the bridge, goes to the vessel
 * through the app's own session, and the answer goes back in.
 *
 * Takes lambdas rather than a session, like every other screen here.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CameraFeed(
    /** Null while nothing should be playing; a fresh value starts a new session. */
    request: CameraRequest?,
    /** Given the local SDP offer, answer with the remote SDP — or fail. */
    onOffer: (String, (Result<String>) -> Unit) -> Unit,
    /** The peer connection's own word for how it is going: `connecting`, `connected`, `failed`. */
    onState: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                // Or the video element refuses to play without a tap it will never receive.
                settings.mediaPlaybackRequiresUserGesture = false
                setBackgroundColor(android.graphics.Color.BLACK)
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onOffer(sdp: String) {
                            // Off the JS thread: this ends in a Zenoh query that blocks for seconds.
                            post {
                                onOffer(sdp) { result ->
                                    post {
                                        result
                                            .onSuccess { answer ->
                                                evaluateJavascript("answer(${jsString(answer)})", null)
                                            }
                                            .onFailure {
                                                onState("failed: ${it.message ?: "no answer"}")
                                            }
                                    }
                                }
                            }
                        }

                        @JavascriptInterface
                        fun onState(state: String) = post { onState(state) }
                    },
                    "Android",
                )
                loadUrl("file:///android_asset/whep.html")
            }
        },
        update = { web ->
            if (request == null) {
                web.evaluateJavascript("stop()", null)
            } else {
                web.evaluateJavascript("start(${jsString(request.iceServersJson)})", null)
            }
        },
        // A live decode left running behind a screen nobody is looking at is a battery cost that shows
        // up nowhere. The map does the same on its way out.
        onRelease = { web ->
            web.evaluateJavascript("stop()", null)
            web.destroy()
        },
    )
}

/** What to play, and how to reach it. A new instance restarts the session. */
data class CameraRequest(
    val entityId: String,
    val responderId: String,
    val path: String,
    /** A JSON array of `RTCIceServer`, empty on a LAN where host candidates suffice. */
    val iceServersJson: String,
)

/**
 * ICE servers as the page wants them.
 *
 * Hand-rolled rather than `org.json`, for the reason `PlatformGeometryJson` records: the `android.jar`
 * stub is not a real implementation, so a test would be exercising something other than the shipped
 * code — and since `returnDefaultValues` is on it would now fail quietly rather than loudly. This is a
 * small fixed shape only ever written, which is exactly when hand-rolling is the right answer.
 *
 * Empty when nothing is configured: forcing a STUN server on a LAN-only setup buys nothing and delays
 * gathering. Crowsnest records the matching trap from the other side — relay-only with no TURN yields
 * zero candidates and never connects.
 */
fun iceServersJson(stunUrl: String, turnUrl: String, username: String, password: String): String {
    val servers = buildList {
        stunUrl.trim().takeIf { it.isNotEmpty() }?.let { add("""{"urls":${jsString(it)}}""") }
        turnUrl.trim().takeIf { it.isNotEmpty() }?.let {
            add(
                """{"urls":${jsString(it)},"username":${jsString(username)},""" +
                    """"credential":${jsString(password)}}"""
            )
        }
    }
    return servers.joinToString(",", "[", "]")
}

/**
 * A JavaScript string literal.
 *
 * An SDP is full of `\r\n` and the odd quote, and it crosses into the page as source text through
 * `evaluateJavascript`. Getting this wrong does not throw — it produces a syntactically valid call with
 * a truncated or mangled SDP, which fails later as a connection that never establishes.
 */
internal fun jsString(value: String): String = buildString {
    append('"')
    value.forEach { c ->
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            // Line separators are literal newlines to a JS parser and would break the literal.
            c == '\u2028' -> append("\\u2028")
            c == '\u2029' -> append("\\u2029")
            c < ' ' -> append("\\u%04x".fmt(c.code))
            else -> append(c)
        }
    }
    append('"')
}

/**
 * The camera card: a tap to connect, the feed, and what went wrong.
 *
 * **It does not autostart.** A WebRTC session costs a relay somebody pays for, a radio kept awake and a
 * decoder running, and the Live tab is opened for the chart far more often than for the camera. So it
 * waits for a tap, and it stops when this leaves composition.
 *
 * State is a word from the peer connection itself rather than one invented here — `connecting`,
 * `connected`, `failed` — because the alternative is a spinner that means "we asked" and never changes.
 */
@Composable
fun LiveCameraCard(
    settings: Settings,
    link: CameraLink,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var request by remember { mutableStateOf<CameraRequest?>(null) }
    var state by remember { mutableStateOf("") }

    Box(modifier, contentAlignment = Alignment.Center) {
        CameraFeed(
            request = request,
            onOffer = { offer, reply ->
                scope.launch { reply(link.signal(settings, offer)) }
            },
            onState = { state = it },
            modifier = Modifier.fillMaxSize(),
        )

        // **A failure has to be retryable without leaving the tab.** Found by using it: the card went
        // to "failed" and stayed there, because the connect button is only drawn while nothing has been
        // requested — so the one thing to do about a failure was to navigate away and back.
        val failed = state.startsWith("failed")
        if (request == null || failed) {
            TextButton(onClick = {
                state = "connecting"
                request = CameraRequest(
                    entityId = settings.cameraEntityId,
                    responderId = settings.cameraResponderId,
                    path = settings.cameraPath,
                    iceServersJson = iceServersJson(
                        stunUrl = settings.cameraStunUrl,
                        turnUrl = settings.cameraTurnUrl,
                        username = settings.cameraTurnUsername,
                        password = settings.cameraTurnPassword,
                    ),
                )
            }) {
                Text(if (failed) "Try ${settings.cameraPath} again" else "Show ${settings.cameraPath}")
            }
        }
        if (request != null && state != "connected") {
            // Over the black video area, so the state is legible before any frame arrives.
            Text(
                state.ifBlank { "connecting" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
            )
        }
    }
}

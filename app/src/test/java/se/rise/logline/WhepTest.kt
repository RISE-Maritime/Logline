package se.rise.logline

import keelson.interfaces.whep_proxy.WHEPProxyOuterClass.WHEPRequest
import keelson.interfaces.whep_proxy.WHEPProxyOuterClass.WHEPResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import se.rise.logline.ui.iceServersJson
import se.rise.logline.ui.jsString
import se.rise.logline.whep.Whep

/**
 * The half of a WHEP handshake that can be checked without a bus: the key and the wire shape.
 *
 * Worth pinning precisely because both failures are silent. A wrong key expression is answered by
 * nobody and looks exactly like a camera that is switched off; a wrong field number decodes cleanly
 * into the wrong field and produces an SDP that is not an SDP.
 */
class WhepTest {

    /**
     * **The same key crowsnest builds**, transcribed from `src/services/whepRpc.js`, which uses
     * `rpcKeyFor("whep_proxy/v1")`. If these two disagree, one of them is talking to nobody — and
     * neither would say so.
     */
    @Test
    fun `the modern key matches the one crowsnest queries`() {
        assertEquals(
            "rise/@v0/landkrabba/@rpc/whep_proxy/v1/whep_signal/mediamtx",
            Whep.modernKey("rise", "landkrabba", "mediamtx"),
        )
    }

    /**
     * **The key `keelson:0.5.3` actually declares**, which is not the one above.
     *
     * Read off that image's own log while it was running: `Declaring queryable on key:
     * rise/@v0/testcam/@rpc/whep_signal/mediamtx` — no interface chunk, no version. keelson's current
     * source builds the modern shape, so both are in the wild and the app asks for both at once.
     */
    @Test
    fun `the legacy key is the one the shipped image serves`() {
        assertEquals(
            "rise/@v0/testcam/@rpc/whep_signal/mediamtx",
            Whep.legacyKey("rise", "testcam", "mediamtx"),
        )
    }

    /** They must differ, or asking for both is asking twice for one thing. */
    @Test
    fun `the two shapes are not the same key`() {
        assertNotEquals(
            Whep.modernKey("rise", "e", "r"),
            Whep.legacyKey("rise", "e", "r"),
        )
    }

    /** `@rpc` is as literal as `@v0`: no wildcard crosses it, so discovery has to spell it out. */
    @Test
    fun `the key spells out every chunk`() {
        val key = Whep.modernKey("rise", "e", "r")

        assertEquals(8, key.split('/').size)
        assertEquals("@v0", key.split('/')[1])
        assertEquals("@rpc", key.split('/')[3])
    }

    /**
     * The request carries the MediaMTX **path** — the `<pathname>` in `MTX_PATHS_<pathname>_SOURCE` —
     * and the offer. Neither is a keelson subject, and nothing on the bus advertises the path.
     */
    @Test
    fun `a request round-trips its path and offer`() {
        val offer = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"

        val decoded = WHEPRequest.parseFrom(
            WHEPRequest.newBuilder().setPath("foredeck").setSdp(offer).build().toByteArray()
        )

        assertEquals("foredeck", decoded.path)
        assertEquals(offer, decoded.sdp)
    }

    @Test
    fun `a response round-trips its answer`() {
        val answer = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\na=recvonly\r\n"

        val decoded = WHEPResponse.parseFrom(
            WHEPResponse.newBuilder().setSdp(answer).build().toByteArray()
        )

        assertEquals(answer, decoded.sdp)
    }

    /**
     * Ten seconds, not the app's usual five: the query round-trips to the vessel and the proxy then
     * waits on MediaMTX's own HTTP endpoint before it can answer. Crowsnest budgets the same.
     */
    @Test
    fun `the timeout allows for a vessel round trip and mediamtx`() {
        assertEquals(10L, Whep.TIMEOUT.seconds)
    }

    /**
     * **An SDP survives the bridge.**
     *
     * It crosses into the WebView as *source text* through `evaluateJavascript`, and it is full of
     * CRLF. Getting the escaping wrong does not throw: it produces a valid-looking call carrying a
     * mangled SDP, which surfaces much later as a connection that never establishes.
     */
    @Test
    fun `an sdp survives being written into a javascript literal`() {
        val sdp = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\na=ice-ufrag:\"quoted\"\r\n"

        val literal = jsString(sdp)

        assertEquals(
            "\"v=0\\r\\no=- 0 0 IN IP4 127.0.0.1\\r\\na=ice-ufrag:\\\"quoted\\\"\\r\\n\"",
            literal,
        )
    }

    /** A backslash in a fingerprint or a candidate must not eat the character after it. */
    @Test
    fun `a backslash is escaped rather than swallowed`() {
        assertEquals("\"a\\\\b\"", jsString("a\\b"))
    }

    /** Nothing configured means no ICE servers, not a server with empty fields. */
    @Test
    fun `no stun and no turn is an empty list`() {
        assertEquals("[]", iceServersJson("", "", "", ""))
    }

    @Test
    fun `stun alone needs no credentials`() {
        assertEquals(
            """[{"urls":"stun:stun.l.google.com:19302"}]""",
            iceServersJson("stun:stun.l.google.com:19302", "", "", ""),
        )
    }

    /** TURN carries its credentials; crowsnest reuses one username and password for the relay too. */
    @Test
    fun `turn carries its credentials`() {
        assertEquals(
            """[{"urls":"turn:t.example.org:3478","username":"u","credential":"p"}]""",
            iceServersJson("", "turn:t.example.org:3478", "u", "p"),
        )
    }
}

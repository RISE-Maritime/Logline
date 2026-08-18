package se.rise.logline

import se.rise.logline.keelson.TlsPaths
import se.rise.logline.keelson.clientConfigJson
import se.rise.logline.keelson.endpointNeedsTls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val ALL = TlsPaths(
    rootCa = "/data/user/0/se.rise.logline/files/tls/root_ca.pem",
    clientCertificate = "/data/user/0/se.rise.logline/files/tls/client_cert.pem",
    clientKey = "/data/user/0/se.rise.logline/files/tls/client_key.pem",
)

/**
 * The config is a JSON string handed to Zenoh, so a wrong field name or a missing block fails at
 * runtime with an opaque handshake error. These pin the shape.
 */
class TlsConfigTest {

    @Test
    fun `the locator scheme decides whether TLS applies`() {
        assertTrue(endpointNeedsTls("tls/router.example.com:443"))
        assertTrue(endpointNeedsTls("quic/router.example.com:443"))
        assertFalse(endpointNeedsTls("tcp/127.0.0.1:7447"))
        assertFalse(endpointNeedsTls("udp/192.168.0.1:7447"))
    }

    @Test
    fun `a plain tcp endpoint gets no tls block even when credentials exist`() {
        val json = clientConfigJson(
            listOf("tcp/127.0.0.1:7447"), ALL)

        assertFalse(json.contains("transport"))
        assertFalse(json.contains("enable_mtls"))
        assertTrue(json.contains(""""endpoints":["tcp/127.0.0.1:7447"]"""))
    }

    @Test
    fun `a tls endpoint with every credential enables mutual TLS`() {
        val json = clientConfigJson(
            listOf("tls/router.example.com:443"), ALL)

        assertTrue(json.contains(""""root_ca_certificate":"${ALL.rootCa}""""))
        assertTrue(json.contains(""""enable_mtls":true"""))
        assertTrue(json.contains(""""connect_certificate":"${ALL.clientCertificate}""""))
        assertTrue(json.contains(""""connect_private_key":"${ALL.clientKey}""""))
    }

    /** Half a client credential is not a usable state — send the CA alone rather than a broken pair. */
    @Test
    fun `a root CA without a client pair does not claim mutual TLS`() {
        val json = clientConfigJson(
            listOf("tls/router.example.com:443"), TlsPaths(rootCa = ALL.rootCa))

        assertTrue(json.contains("root_ca_certificate"))
        assertFalse(json.contains("enable_mtls"))
        assertFalse(json.contains("connect_private_key"))
    }

    @Test
    fun `a client certificate without its key is ignored rather than half-configured`() {
        val json = clientConfigJson(
            listOf("tls/router.example.com:443"),
            TlsPaths(rootCa = ALL.rootCa, clientCertificate = ALL.clientCertificate),
        )

        assertFalse(json.contains("enable_mtls"))
        assertFalse(json.contains("connect_certificate"))
    }

    /** Failing here, by name, beats an unexplained handshake failure minutes later. */
    @Test
    fun `a tls endpoint without a root CA fails loudly and says what is missing`() {
        val error = runCatching { clientConfigJson(
            listOf("tls/router.example.com:443"), TlsPaths()) }
            .exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("root CA"))
        assertTrue(error.message!!.contains("tls/router.example.com:443"))
    }

    @Test
    fun `the session stays a client that never scouts`() {
        val json = clientConfigJson(
            listOf("tls/router.example.com:443"), ALL)

        assertTrue(json.contains(""""mode":"client""""))
        assertTrue(json.contains(""""multicast":{"enabled":false}"""))
    }

    @Test
    fun `the default endpoint is the cloud router over TLS`() {
        assertEquals("tls/router.example.com:443", se.rise.logline.config.Settings.DEFAULT_ENDPOINT)
        assertTrue(endpointNeedsTls(se.rise.logline.config.Settings.DEFAULT_ENDPOINT))
    }

    // ---- multiple endpoints ----

    /** Order is meaningful: Zenoh walks the list and stops at the first that answers. */
    @Test
    fun `every endpoint reaches the config, in order`() {
        val json = clientConfigJson(
            listOf("tcp/192.168.0.5:7447", "tcp/127.0.0.1:7447"),
            TlsPaths(),
        )

        assertTrue(
            json.contains(""""endpoints":["tcp/192.168.0.5:7447","tcp/127.0.0.1:7447"]"""),
        )
    }

    /**
     * Zenoh has one global `transport/link/tls` block, not one per locator, so a mixed list must
     * produce exactly one — and must still produce it, since the TLS entry genuinely needs it.
     */
    @Test
    fun `a mixed list emits exactly one tls block`() {
        val json = clientConfigJson(
            listOf("tcp/192.168.0.5:7447", "tls/router.example.com:443"),
            TlsPaths(rootCa = "/data/ca.pem"),
        )

        assertEquals(1, json.split(""""transport"""").size - 1)
        assertTrue(json.contains("/data/ca.pem"))
    }

    @Test
    fun `a list with no tls endpoint gets no tls block`() {
        val json = clientConfigJson(
            listOf("tcp/192.168.0.5:7447", "tcp/127.0.0.1:7447"),
            TlsPaths(rootCa = "/data/ca.pem"),
        )

        assertTrue(!json.contains(""""transport""""))
    }

    /** With a mixed list the message has to say *which* entry cannot work. */
    @Test
    fun `a missing root CA names the endpoints that needed it`() {
        val failure = runCatching {
            clientConfigJson(
                listOf("tcp/192.168.0.5:7447", "tls/router.example.com:443"),
                TlsPaths(),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("tls/router.example.com:443"))
        assertTrue("the tcp entry is fine and should not be blamed",
            !failure.message!!.contains("tcp/192.168.0.5:7447"))
    }

    /** A session with nowhere to connect is not a state worth building. */
    @Test
    fun `an empty endpoint list is rejected`() {
        assertTrue(
            runCatching { clientConfigJson(emptyList(), TlsPaths()) }
                .exceptionOrNull() is IllegalArgumentException
        )
    }
}

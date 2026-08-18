package se.rise.logline

import se.rise.logline.keelson.validateEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A malformed locator used to be accepted silently and only surface much later as a session that would
 * not open — with nothing on screen connecting the failure to the typo. This is shape-checking only:
 * whether anything answers is the scan's and the connection state's job.
 */
class EndpointValidationTest {

    @Test
    fun `the endpoints this app actually uses are accepted`() {
        assertNull(validateEndpoint("tls/router.example.com:443"))
        assertNull(validateEndpoint("tcp/192.168.0.156:7447"))
        assertNull(validateEndpoint("tcp/127.0.0.1:7447"))
        assertNull(validateEndpoint("quic/example.org:7447"))
        assertNull(validateEndpoint("udp/224.0.0.1:7447"))
        assertNull(validateEndpoint("  tcp/192.168.0.1:7447  "))
    }

    /** The commonest typo: pasting an address without its scheme, which Zenoh cannot open. */
    @Test
    fun `a bare host and port is rejected, and the message shows the fix`() {
        val message = validateEndpoint("192.168.1.42:7447")

        assertNotNull(message)
        assertTrue(message!!, message.contains("tcp/192.168.1.42:7447"))
    }

    @Test
    fun `a missing port is rejected`() {
        val message = validateEndpoint("tcp/192.168.1.42")

        assertNotNull(message)
        assertTrue(message!!, message.contains("7447"))
    }

    @Test
    fun `an unusable port is rejected`() {
        assertNotNull(validateEndpoint("tcp/192.168.1.42:0"))
        assertNotNull(validateEndpoint("tcp/192.168.1.42:70000"))
        assertNotNull(validateEndpoint("tcp/192.168.1.42:seven"))
    }

    @Test
    fun `an unknown scheme is named in the message`() {
        val message = validateEndpoint("http/example.org:80")

        assertNotNull(message)
        assertTrue(message!!, message.contains("http"))
    }

    @Test
    fun `an empty locator asks for one rather than accusing the user`() {
        assertEquals(
            "Enter a locator, e.g. tcp/192.168.1.42:7447",
            validateEndpoint("   "),
        )
    }

    @Test
    fun `a scheme with nothing after it is rejected`() {
        assertNotNull(validateEndpoint("tcp/"))
    }

    /** Serial and unix sockets address a device path, not a host and port. */
    @Test
    fun `path-addressed schemes are not held to host and port`() {
        assertNull(validateEndpoint("serial///dev/ttyUSB0"))
        assertNull(validateEndpoint("unixsock-stream//tmp/zenoh.sock"))
    }
}

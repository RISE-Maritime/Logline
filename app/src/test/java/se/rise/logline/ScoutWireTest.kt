package se.rise.logline

import se.rise.logline.keelson.ZENOH_PROTOCOL_VERSION
import se.rise.logline.keelson.decodeHello
import se.rise.logline.keelson.encodeScout
import se.rise.logline.keelson.parseSocketAddress
import se.rise.logline.keelson.zidString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scouting exchange is hand-rolled, because zenoh-kotlin's `Zenoh.scout` aborts the process on
 * Android the moment a router answers — its native side resolves `ZenohId` with `FindClass` from one of
 * its own threads. So these bytes are the app's own protocol surface, and every fixture here is a
 * **capture from a real Zenoh node** (zenoh-python 1.10 scouting, and an `eclipse/zenoh` router
 * answering it), not a reading of the spec. If a future zenoh changes the framing, this is what fails.
 */
class ScoutWireTest {

    private fun hex(text: String) = ByteArray(text.length / 2) {
        text.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    /** Exactly what zenoh-python put on the wire when asked to scout for routers and peers. */
    @Test
    fun `a scout request is three bytes`() {
        assertEquals("010903", encodeScout(0x03).joinToString("") { "%02x".format(it) })
        assertEquals("010901", encodeScout(0x01).joinToString("") { "%02x".format(it) })
        assertEquals(0x09, ZENOH_PROTOCOL_VERSION)
    }

    /** The reply an `eclipse/zenoh:1.9.0` router sent back to that request. */
    private val realHello =
        "2209f025e78fe90649b709fe2ceed50a4e700a01167463702f3139322e3136382e302e3135363a37343437"

    @Test
    fun `a captured router hello decodes`() {
        val hello = decodeHello(hex(realHello))!!

        assertEquals("Router", hello.role)
        assertEquals(listOf("tcp/192.168.0.156:7447"), hello.locators)
        // 16 bytes read little-endian, lowercase hex, leading zeros trimmed — the rule every Zenoh SDK
        // prints ids by, so a scan result can be matched against router logs. Cross-checked against
        // zenoh-python scouting the same live router: same id string, same locators.
        assertEquals("a704e0ad5ee2cfe09b74906e98fe725", hello.zid)
    }

    @Test
    fun `several locators are read in order`() {
        // Same header, two locators: "tcp/10.0.0.1:7447" (17) and "udp/10.0.0.1:7447" (17).
        val bytes = hex(
            "2209f025e78fe90649b709fe2ceed50a4e700a" +
                "02" +
                "11" + "7463702f31302e302e302e313a37343437" +
                "11" + "7564702f31302e302e302e313a37343437"
        )
        assertEquals(
            listOf("tcp/10.0.0.1:7447", "udp/10.0.0.1:7447"),
            decodeHello(bytes)!!.locators,
        )
    }

    /** No locator flag means a router that cannot be connected to — parsed, then dropped by the scan. */
    @Test
    fun `a hello without locators parses as empty`() {
        val hello = decodeHello(hex("0209f025e78fe90649b709fe2ceed50a4e700a"))!!
        assertTrue(hello.locators.isEmpty())
    }

    @Test
    fun `a peer identifies itself as such`() {
        assertEquals("Peer", decodeHello(hex("0209f125e78fe90649b709fe2ceed50a4e700a"))!!.role)
    }

    /**
     * A scan listens on a multicast group, so anything at all can arrive. None of it may throw into the
     * receive loop.
     */
    @Test
    fun `junk on the wire is rejected rather than thrown`() {
        assertNull("a scout request is not a hello", decodeHello(hex("010903")))
        assertNull("empty", decodeHello(ByteArray(0)))
        assertNull("truncated header", decodeHello(hex("2209")))
        assertNull("zid shorter than its length says", decodeHello(hex("2209f025e7")))
        assertNull("unrelated protocol", decodeHello(hex("ffffffffffff")))
    }

    /** A truncated locator is dropped, and what came before it survives. */
    @Test
    fun `a locator running off the end is dropped`() {
        val hello = decodeHello(
            hex("2209f025e78fe90649b709fe2ceed50a4e700a" + "02" + "11" + "7463702f31302e302e302e313a37343437" + "40" + "7463")
        )!!
        assertEquals(listOf("tcp/10.0.0.1:7447"), hello.locators)
    }

    /** Extension bytes after the locators are the protocol's, not ours — ignoring them must be safe. */
    @Test
    fun `trailing extension bytes are ignored`() {
        val hello = decodeHello(hex("a2" + realHello.substring(2) + "deadbeef"))!!
        assertEquals(listOf("tcp/192.168.0.156:7447"), hello.locators)
    }

    @Test
    fun `a zid renders like every other zenoh sdk`() {
        assertEquals("0", zidString(ByteArray(4)))
        assertEquals("1", zidString(byteArrayOf(1)))
        // Little-endian: the last byte is the most significant.
        assertEquals("201", zidString(byteArrayOf(1, 2)))
    }

    @Test
    fun `a scan address is host and port, or nothing`() {
        val parsed = parseSocketAddress(" 224.0.0.224:7446 ")!!
        assertEquals(7446, parsed.port)
        assertEquals("224.0.0.224", parsed.address.hostAddress)

        assertNull("no port", parseSocketAddress("224.0.0.224"))
        assertNull("port out of range", parseSocketAddress("224.0.0.224:70000"))
        assertNull("port not a number", parseSocketAddress("224.0.0.224:seven"))
        assertNull("empty", parseSocketAddress(""))
    }
}

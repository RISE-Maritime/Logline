package se.rise.logline

import se.rise.logline.keelson.enclose
import se.rise.logline.keelson.protoTimestamp
import core.EnvelopeOuterClass.Envelope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class EnvelopesTest {

    @Test
    fun `epoch nanos split into seconds and nanos`() {
        val timestamp = protoTimestamp(1_786_878_000_123_456_789L)

        assertEquals(1_786_878_000L, timestamp.seconds)
        assertEquals(123_456_789, timestamp.nanos)
    }

    @Test
    fun `an exact second has no nanos remainder`() {
        val timestamp = protoTimestamp(1_786_878_000_000_000_000L)

        assertEquals(1_786_878_000L, timestamp.seconds)
        assertEquals(0, timestamp.nanos)
    }

    @Test
    fun `a pre-epoch instant keeps nanos non-negative`() {
        // protobuf requires 0 <= nanos < 1e9 even when seconds is negative, which is why the
        // conversion floors rather than truncating.
        val timestamp = protoTimestamp(-500_000_000L)

        assertEquals(-1L, timestamp.seconds)
        assertEquals(500_000_000, timestamp.nanos)
    }

    @Test
    fun `enclose round-trips the payload`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val now = Instant.ofEpochSecond(1_786_878_000L, 250_000_000L)

        val parsed = Envelope.parseFrom(enclose(payload, now))

        assertArrayEquals(payload, parsed.payload.toByteArray())
        assertEquals(1_786_878_000L, parsed.enclosedAt.seconds)
        assertEquals(250_000_000, parsed.enclosedAt.nanos)
    }

    @Test
    fun `enclose accepts an empty payload`() {
        val parsed = Envelope.parseFrom(enclose(ByteArray(0)))

        assertEquals(0, parsed.payload.size())
    }
}

package se.rise.logline

import se.rise.logline.keelson.enclose
import se.rise.logline.keelson.protoTimestamp
import core.EnvelopeOuterClass.Envelope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import se.rise.logline.keelson.protoDuration
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

    /**
     * A `Duration` is not a `Timestamp` and the sign rule is the difference.
     *
     * `Timestamp` wants a non-negative `nanos` even when `seconds` is negative, so [protoTimestamp]
     * floors. `Duration` requires both parts to carry the **same** sign, so this truncates. Nothing in
     * the app produces a negative duration — an uptime cannot run backwards — but a helper that
     * silently emitted an invalid message for one would be waiting for whoever writes the next
     * subject that can.
     */
    @Test
    fun `a duration splits into seconds and nanos`() {
        val oneAndAHalf = protoDuration(1_500L)
        assertEquals(1L, oneAndAHalf.seconds)
        assertEquals(500_000_000, oneAndAHalf.nanos)

        val whole = protoDuration(3_600_000L)
        assertEquals(3_600L, whole.seconds)
        assertEquals(0, whole.nanos)

        val zero = protoDuration(0L)
        assertEquals(0L, zero.seconds)
        assertEquals(0, zero.nanos)
    }

    /** Both parts negative together, which is what protobuf requires of a negative duration. */
    @Test
    fun `a negative duration keeps one sign`() {
        val back = protoDuration(-1_500L)

        assertEquals(-1L, back.seconds)
        assertEquals(-500_000_000, back.nanos)
    }

    /** Three weeks of uptime in milliseconds is well inside a Long, and must not lose precision. */
    @Test
    fun `a long uptime survives the conversion`() {
        val threeWeeks = 21L * 24 * 3_600_000L

        assertEquals(21L * 24 * 3_600L, protoDuration(threeWeeks).seconds)
    }
}

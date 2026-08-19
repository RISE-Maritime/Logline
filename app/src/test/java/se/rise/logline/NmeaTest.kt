package se.rise.logline

import se.rise.logline.sensors.nmeaEpochNanos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OnNmeaMessageListener` documents its timestamp as milliseconds since the epoch, and this checks
 * rather than trusts it — because the cost of being wrong is the bug this project has already had
 * once. A boot-clock value published as an epoch time puts the whole stream decades in the past,
 * where it is worthless and not obviously broken: the sentences decode, the rate looks right, and
 * every consumer quietly files them under 1970.
 */
class NmeaTest {

    /** 2026-08-19T10:00:00Z, an ordinary reading from a phone whose clock is set. */
    private val wallMillis = 1_787_133_600_000L

    /** Nine days of uptime, in nanoseconds — a large but perfectly ordinary boot clock. */
    private val elapsedNanos = 9L * 24 * 3_600 * 1_000_000_000L

    @Test
    fun `an epoch timestamp is used as it is`() {
        val at = nmeaEpochNanos(
            timestampMillis = wallMillis,
            elapsedNanosNow = elapsedNanos,
            wallMillisNow = wallMillis,
        )

        assertEquals(wallMillis * 1_000_000L, at)
    }

    /**
     * The older `GpsStatus.NmeaListener` supplied a boot-clock value here. A device still doing that
     * would otherwise publish sentences dated to 1970 — this converts them instead.
     */
    @Test
    fun `a boot-clock timestamp is converted rather than published raw`() {
        // Half a second before "now" on the boot clock, which is what a fresh sentence looks like.
        val bootMillis = elapsedNanos / 1_000_000L - 500L

        val at = nmeaEpochNanos(
            timestampMillis = bootMillis,
            elapsedNanosNow = elapsedNanos,
            wallMillisNow = wallMillis,
        )

        assertEquals("should land half a second ago", (wallMillis - 500L) * 1_000_000L, at)
    }

    /** The distinction that matters: neither reading may come out anywhere near 1970. */
    @Test
    fun `neither clock base produces a timestamp in the past`() {
        val bootMillis = elapsedNanos / 1_000_000L
        val year2000Nanos = 946_684_800_000L * 1_000_000L

        listOf(wallMillis, bootMillis).forEach { timestamp ->
            val at = nmeaEpochNanos(timestamp, elapsedNanos, wallMillis)
            assertTrue("$timestamp produced $at, which is before 2000", at > year2000Nanos)
        }
    }

    /**
     * The floor is 2001-09-09, the round billion seconds. A phone would have to have been up for 31
     * years to reach it on the boot clock, and no real date falls below it — so nothing plausible sits
     * on the wrong side of the test.
     */
    @Test
    fun `the boundary sits far from both plausible ranges`() {
        val floorMillis = 1_000_000_000_000L

        // Just above: read as an epoch time, so it stays exactly what it was.
        assertEquals(floorMillis * 1_000_000L, nmeaEpochNanos(floorMillis, elapsedNanos, wallMillis))
        // Just below: read as a boot clock and converted, so it does not stay put.
        val below = nmeaEpochNanos(floorMillis - 1L, elapsedNanos, wallMillis)
        assertTrue("should have been converted, not passed through", below != (floorMillis - 1L) * 1_000_000L)
    }

    /** Zero is what an unset timestamp looks like, and it must not become 1970 either. */
    @Test
    fun `an absent timestamp still lands in the present`() {
        val at = nmeaEpochNanos(0L, elapsedNanos, wallMillis)

        // Converted as a boot clock: nine days ago rather than 1970, which is wrong but visibly so
        // rather than silently. Nothing on the bus should ever claim to predate the phone.
        assertEquals((wallMillis - elapsedNanos / 1_000_000L) * 1_000_000L, at)
    }
}

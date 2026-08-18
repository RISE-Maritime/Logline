package se.rise.logline

import se.rise.logline.sensors.SensorClock
import org.junit.Assert.assertEquals
import org.junit.Test

class SensorClockTest {

    // 2026-08-16T13:00:00Z, and a device that booted two hours earlier.
    private val wallMillis = 1_786_878_000_000L
    private val elapsedNowNanos = 2 * 60 * 60 * 1_000_000_000L

    @Test
    fun `an event happening right now maps to now`() {
        val epochNanos = SensorClock.epochNanos(
            eventElapsedNanos = elapsedNowNanos,
            wallMillis = wallMillis,
            elapsedNowNanos = elapsedNowNanos,
        )

        assertEquals(wallMillis * 1_000_000L, epochNanos)
    }

    @Test
    fun `an older event maps proportionally further back`() {
        val ageNanos = 18_000_000L // one sample period at ~55 Hz

        val epochNanos = SensorClock.epochNanos(
            eventElapsedNanos = elapsedNowNanos - ageNanos,
            wallMillis = wallMillis,
            elapsedNowNanos = elapsedNowNanos,
        )

        assertEquals(wallMillis * 1_000_000L - ageNanos, epochNanos)
    }

    @Test
    fun `sub-millisecond spacing between events survives the conversion`() {
        // Both clocks are read once per sample, so the only thing preserving fine spacing is the
        // event timestamp itself.
        val first = SensorClock.epochNanos(elapsedNowNanos - 1_000L, wallMillis, elapsedNowNanos)
        val second = SensorClock.epochNanos(elapsedNowNanos, wallMillis, elapsedNowNanos)

        assertEquals(1_000L, second - first)
    }

    @Test
    fun `does not overflow at a long device uptime`() {
        val hundredDaysNanos = 100L * 24 * 60 * 60 * 1_000_000_000L

        val epochNanos = SensorClock.epochNanos(
            eventElapsedNanos = hundredDaysNanos,
            wallMillis = wallMillis,
            elapsedNowNanos = hundredDaysNanos,
        )

        assertEquals(wallMillis * 1_000_000L, epochNanos)
    }
}

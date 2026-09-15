package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Test
import se.rise.logline.sensors.HeldClock

class HeldClockTest {

    // Returns a different answer on every call, the way epochNanosNow does as the clocks tick.
    private var calls = 0
    private val drifting: (Long) -> Long = { elapsed -> elapsed + 1_000_000L * ++calls }

    @Test
    fun `a reading held for many ticks keeps one timestamp`() {
        val clock = HeldClock(drifting)

        val stamps = List(20) { clock.epochNanos(5_000_000_000L) }

        assertEquals(1, stamps.toSet().size)
        assertEquals(1, calls)
    }

    @Test
    fun `a new reading is converted again`() {
        val clock = HeldClock(drifting)

        val first = clock.epochNanos(5_000_000_000L)
        clock.epochNanos(5_000_000_000L)
        val second = clock.epochNanos(6_000_000_000L)

        assertEquals(2, calls)
        assertEquals(6_000_000_000L + 2_000_000L, second)
        assertEquals(5_000_000_000L + 1_000_000L, first)
    }

    @Test
    fun `an elapsed time of zero is still converted on first use`() {
        val clock = HeldClock(drifting)

        assertEquals(1_000_000L, clock.epochNanos(0L))
    }
}

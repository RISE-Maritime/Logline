package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Test
import se.rise.logline.ui.ThroughputHealth
import se.rise.logline.ui.throughputHealth

/**
 * The verdict the Session screen shows, and the boundary that makes it worth having.
 *
 * The state this pins hardest is [ThroughputHealth.UnderStrain] — before it existed the screen went
 * straight from healthy to "N samples dropped", so the only warning anybody got was already a loss.
 */
class ThroughputHealthTest {

    private val capacity = 10_000

    @Test
    fun `an idle run is keeping up`() {
        assertEquals(
            ThroughputHealth.KeepingUp,
            throughputHealth(peakDepth = 0, capacity = capacity, dropped = 0, shed = 0),
        )
    }

    @Test
    fun `a queue that never got deep is keeping up`() {
        // A healthy drain sits at a handful of samples; that must not read as strain, or the indicator
        // cries wolf for the whole of every run and stops being read.
        assertEquals(
            ThroughputHealth.KeepingUp,
            throughputHealth(peakDepth = 40, capacity = capacity, dropped = 0, shed = 0),
        )
    }

    @Test
    fun `a quarter of the queue is strain, just under it is not`() {
        assertEquals(
            ThroughputHealth.UnderStrain,
            throughputHealth(peakDepth = 2_500, capacity = capacity, dropped = 0, shed = 0),
        )
        assertEquals(
            ThroughputHealth.KeepingUp,
            throughputHealth(peakDepth = 2_499, capacity = capacity, dropped = 0, shed = 0),
        )
    }

    /** Either loss outranks any depth: the queue may look fine *because* samples were thrown away. */
    @Test
    fun `any loss outranks a healthy queue`() {
        assertEquals(
            ThroughputHealth.Losing,
            throughputHealth(peakDepth = 0, capacity = capacity, dropped = 1, shed = 0),
        )
        assertEquals(
            ThroughputHealth.Losing,
            throughputHealth(peakDepth = 0, capacity = capacity, dropped = 0, shed = 1),
        )
    }

    /** A capacity of zero must not divide the verdict into nonsense. */
    @Test
    fun `no capacity reads as keeping up rather than as strain`() {
        assertEquals(
            ThroughputHealth.KeepingUp,
            throughputHealth(peakDepth = 0, capacity = 0, dropped = 0, shed = 0),
        )
    }
}

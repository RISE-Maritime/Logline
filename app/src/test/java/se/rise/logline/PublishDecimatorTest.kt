package se.rise.logline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.publish.PublishDecimator

/** What reaches the wire once the file and the bus have separate rates. */
class PublishDecimatorTest {

    private val tenHz = 100_000_000L // 100 ms

    /**
     * Waiting a full interval for the first sample would leave the row reading "Waiting for the first
     * sample" for that long — five seconds on a 0.2 Hz subject, which reads as broken.
     */
    @Test
    fun `the first sample is always due`() {
        assertTrue(PublishDecimator(tenHz).due(0L))
        assertTrue(PublishDecimator(tenHz).due(123_456_789L))
    }

    @Test
    fun `samples inside the interval are held back`() {
        val d = PublishDecimator(tenHz)
        assertTrue(d.due(0L))
        assertFalse(d.due(50_000_000L))
        assertFalse(d.due(99_999_999L))
    }

    @Test
    fun `the interval boundary is inclusive`() {
        val d = PublishDecimator(tenHz)
        assertTrue(d.due(0L))
        assertTrue(d.due(100_000_000L))
    }

    /** Asking is what claims the slot: a caller must not be able to check twice and publish twice. */
    @Test
    fun `asking twice at the same instant passes once`() {
        val d = PublishDecimator(tenHz)
        assertTrue(d.due(1_000L))
        assertFalse(d.due(1_000L))
    }

    /** An interval of zero is `SensorRate.Max`, or a publish rate clamped up to meet the record rate. */
    @Test
    fun `a zero interval lets everything through`() {
        val d = PublishDecimator(0L)
        repeat(5) { assertTrue(d.due(it.toLong())) }
    }

    /**
     * `System.nanoTime()` is monotonic so this should not arise — but the cost of being wrong is one
     * extra sample, against a subject that would otherwise never publish again for the whole run.
     */
    @Test
    fun `a clock that goes backwards publishes rather than stalling`() {
        val d = PublishDecimator(tenHz)
        assertTrue(d.due(1_000_000_000L))
        assertTrue(d.due(500_000_000L))
    }

    /** The interval is measured from the last sample *sent*, not from a fixed grid. */
    @Test
    fun `a thin stream publishes every sample`() {
        val d = PublishDecimator(tenHz)
        var now = 0L
        repeat(5) {
            assertTrue(d.due(now))
            now += 5 * tenHz
        }
    }
}

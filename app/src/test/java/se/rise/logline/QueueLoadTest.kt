package se.rise.logline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.record.QueueLoad

/**
 * The recorder's backlog, which was invisible until this class existed.
 *
 * Mirrors `OutboxBufferTest`, which pins the same idea for the publish ring — the recorder's queue
 * simply never got the same treatment, so its depth went from zero to full with nothing reported and
 * the first observable event was an already-lost sample.
 */
class QueueLoadTest {

    @Test
    fun `depth is what has been accepted and not yet written`() {
        val load = QueueLoad(capacity = 100)
        repeat(10) { load.enqueued() }
        assertEquals(10, load.depth)
        repeat(4) { load.drained() }
        assertEquals(6, load.depth)
    }

    /**
     * The number the run is actually characterised by.
     *
     * The UI polls at 1 Hz against a queue tens of seconds deep, so an instantaneous depth reads about
     * zero almost every time. A burst that has drained by the next poll is exactly the event worth
     * knowing about, and only the high-water mark survives it.
     */
    @Test
    fun `the peak outlives the burst that caused it`() {
        val load = QueueLoad(capacity = 100)
        repeat(60) { load.enqueued() }
        repeat(60) { load.drained() }

        assertEquals(0, load.depth)
        assertEquals(60, load.peak)
    }

    @Test
    fun `a reset starts the next run clean`() {
        val load = QueueLoad(capacity = 100)
        repeat(30) { load.enqueued() }
        load.reset()

        assertEquals(0, load.depth)
        // The one that matters: a peak carried across a Stop/Start would report the previous run's
        // worst moment against this one, which is the same shape as the single long-lived channel that
        // once made the second run in a process record nothing at all.
        assertEquals(0, load.peak)
        assertEquals(0, load.enqueued)
    }

    /** Reads are separate, so a drain landing between them must not surface a negative backlog. */
    @Test
    fun `depth never goes negative`() {
        val load = QueueLoad(capacity = 100)
        load.drained()
        assertEquals(0, load.depth)
    }

    /**
     * Collectors and the drain coroutine reach this concurrently, which is precisely the load it exists
     * to measure — a peak kept with a read-modify-write would lose the highest reading under it.
     */
    @Test
    fun `concurrent writers lose no count and keep the true peak`() = runBlocking {
        val load = QueueLoad(capacity = 10_000)
        val writers = 8
        val each = 2_000

        (1..writers).map {
            async(Dispatchers.Default) { repeat(each) { load.enqueued() } }
        }.awaitAll()

        assertEquals((writers * each).toLong(), load.enqueued)
        assertEquals((writers * each).toLong(), load.depth)
        assertTrue("the peak must have reached the full depth", load.peak == (writers * each).toLong())
    }
}

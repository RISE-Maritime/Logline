package se.rise.logline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Stop does with the samples already queued.
 *
 * `Recorder` needs a `Context`, so this exercises the shape of its drain rather than the class: a
 * bounded channel, a loop that writes what it receives, and the two ways a run can end. The property
 * is the whole point — **closing a channel must not lose what is in it** — and the previous
 * implementation broke it by cancelling the drain immediately after closing the queue, which threw
 * away up to `QUEUE_CAPACITY` samples from the tail of every recording and counted none of them.
 */
class RecorderStopTest {

    /** Stands in for a write: slow enough that a cancel can land mid-loop, as it did on the phone. */
    private fun CoroutineScope.drainInto(queue: Channel<Int>, written: MutableList<Int>): Job =
        launch(Dispatchers.Default) {
            for (sample in queue) {
                written += sample
            }
        }

    @Test
    fun `closing the queue lets every buffered sample be written`() = runBlocking {
        val queue = Channel<Int>(capacity = 1_000)
        val written = mutableListOf<Int>()
        val drain = drainInto(queue, written)
        repeat(500) { queue.trySend(it) }

        queue.close()
        // What `stop()` now does: wait for the loop to end on its own.
        val finished = withTimeoutOrNull(5_000) { drain.join() } != null

        assertTrue("the drain should end by itself once the queue is closed", finished)
        assertEquals("every buffered sample reaches the file", 500, written.size)
        assertEquals((0 until 500).toList(), written)
    }

    /**
     * **Cancelling does not lose the buffer, and finding that out is what redirected this fix.**
     *
     * The recorder's samples were going missing — 12 of 88 102 in a measured run — and the obvious
     * suspect was `stop()` cancelling the drain right after closing the queue. It is not: `receive()`
     * only checks for cancellation when it has to *suspend*, and on a closed channel that still holds
     * elements it never does, so the loop consumes every one of them and then ends. This test pins
     * that, because the tempting fix is guarding against something that was never happening.
     *
     * The real cause was ordering elsewhere — the recorder was stopped before the publisher's
     * collectors were, so samples emitted during teardown met a queue that had already gone. See
     * `SensorPublisher.stopInternal`.
     */
    @Test
    fun `cancelling after close still drains what was buffered`() = runBlocking {
        val queue = Channel<Int>(capacity = 1_000)
        val written = mutableListOf<Int>()
        val drain = launch(Dispatchers.Default) {
            for (sample in queue) {
                written += sample
                // A write is not free, so the cancel has every chance to land mid-loop.
                Thread.sleep(1)
            }
        }
        repeat(200) { queue.trySend(it) }

        queue.close()
        drain.cancelAndJoin()

        assertEquals("a closed channel's buffer is drained, cancel or not", 200, written.size)
    }

    /**
     * A queue nobody filled must not make Stop wait. This is the common case by far: the drain keeps
     * up, so the loop is parked in `receive()` and the close ends it immediately.
     */
    @Test
    fun `an empty queue ends at once`() = runBlocking {
        val queue = Channel<Int>(capacity = 1_000)
        val written = mutableListOf<Int>()
        val drain = drainInto(queue, written)

        queue.close()
        val finished = withTimeoutOrNull(1_000) { drain.join() } != null

        assertTrue(finished)
        assertEquals(0, written.size)
    }

    /** After the grace expires, what is left is countable — which is what turns a hole into a number. */
    @Test
    fun `whatever is left after the grace can be counted`() = runBlocking {
        val queue = Channel<Int>(capacity = 1_000)
        repeat(42) { queue.trySend(it) }
        queue.close()

        var lost = 0
        while (queue.tryReceive().isSuccess) lost++

        assertEquals(42, lost)
    }
}

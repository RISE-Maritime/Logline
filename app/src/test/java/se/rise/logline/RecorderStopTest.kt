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

    /**
     * **A rotation copy must not stall the drain.** At 512 MB a publish into Downloads is a long
     * blocking copy, and called inline on the drain coroutine it stops the loop for its whole duration
     * — the queue holds about 45 seconds of samples at the measured rate, so anything longer is counted
     * as dropped while the recording itself is perfectly healthy. `publishOrphans()` had already been
     * moved off this coroutine for exactly that reason, after it cost 20 000 samples in half a minute.
     *
     * Modelled the way the rest of this file models the recorder: a bounded queue, a loop, and a slow
     * copy at the point a file rotates. The queue is deliberately smaller than the sample count, so an
     * inline copy would overrun it and the failure is a lost sample rather than a slow test.
     */
    @Test
    fun `a slow publish at rotation does not stall the drain`() = runBlocking {
        val queue = Channel<Int>(capacity = 32)
        val written = mutableListOf<Int>()
        val published = java.util.concurrent.atomic.AtomicInteger()
        val copies = mutableListOf<Job>()

        val drain = launch(Dispatchers.Default) {
            for (sample in queue) {
                written += sample
                // Every 100th sample "fills a file". The copy goes to its own coroutine, which is the
                // whole of the fix.
                if (sample % 100 == 99) {
                    copies += launch(Dispatchers.IO) {
                        Thread.sleep(200)   // a blocking copy, like the real one
                        published.incrementAndGet()
                    }
                }
            }
        }

        // Offered faster than a stalling drain could take them. The numbers matter: a 200 ms copy
        // against a 32-slot queue filling at ~1 ms a sample overruns it several times over, so an
        // inline publish sheds samples here and this test fails. Verified by making it inline —
        // a stall merely *shorter* than the queue's cover passes, which is the trap in writing it.
        var dropped = 0
        repeat(300) {
            if (!queue.trySend(it).isSuccess) dropped++
            Thread.sleep(1)
        }
        queue.close()
        val finished = withTimeoutOrNull(5_000) { drain.join() } != null
        copies.forEach { it.join() }

        assertTrue("the drain should end by itself", finished)
        assertEquals("no sample should be shed while a file is being copied", 0, dropped)
        assertEquals("every sample reaches the file", 300, written.size)
        assertEquals("each rotation publishes exactly once", 3, published.get())
    }

    /**
     * The trap in moving that copy off the drain: `session` is reassigned on the line after the
     * rotation, so a lambda capturing the *variable* rather than the path publishes whichever file the
     * variable names by the time it runs — which is the new one, still being written.
     */
    @Test
    fun `the published path is captured at rotation, not read when the copy runs`() = runBlocking {
        var session = "file-0"
        val publishedNames = java.util.Collections.synchronizedList(mutableListOf<String>())
        val copies = mutableListOf<Job>()

        repeat(3) { rotation ->
            val finished = session          // read now — this is the fix
            copies += launch(Dispatchers.IO) {
                Thread.sleep(30)
                publishedNames += finished
            }
            session = "file-${rotation + 1}"
        }
        copies.forEach { it.join() }

        assertEquals(listOf("file-0", "file-1", "file-2"), publishedNames.sorted())
        assertTrue("the file still being written is never published", "file-3" !in publishedNames)
    }
}

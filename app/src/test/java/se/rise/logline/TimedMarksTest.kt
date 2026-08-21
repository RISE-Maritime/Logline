package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.publish.TimedMarks
import se.rise.logline.publish.formatElapsed

/**
 * The bookkeeping behind press-and-hold: which quick marks are timing something, and for how long.
 *
 * Pure, so the interesting cases — a double start, a stop that was never started, a run torn down with
 * timers open — can be exercised without a phone or a Zenoh session.
 */
class TimedMarksTest {

    @Test
    fun `a timer runs from its start until it is stopped`() {
        val timers = TimedMarks()

        assertTrue(timers.start("Engine", 1_000L))
        assertEquals(mapOf("Engine" to 1_000L), timers.running())
        assertEquals(4_000L, timers.stop("Engine", 5_000L))
        assertTrue("stopping forgets it", timers.running().isEmpty())
    }

    /**
     * **A second start must not move the origin.**
     *
     * Holding an already-running button would otherwise restart it silently, and the duration would come
     * out short by however long it had already been going — a wrong number that looks like a right one.
     * The caller reads the false as "no mark to publish", so no second `started` reaches the bus either.
     */
    @Test
    fun `starting a running timer changes nothing`() {
        val timers = TimedMarks()
        timers.start("Engine", 1_000L)

        assertFalse(timers.start("Engine", 9_000L))
        assertEquals(mapOf("Engine" to 1_000L), timers.running())
        assertEquals("measured from the first start", 8_000L, timers.stop("Engine", 9_000L))
    }

    /** Stopping something that was never started is not an error, it is simply nothing to publish. */
    @Test
    fun `stopping an unknown timer answers null`() {
        val timers = TimedMarks()

        assertNull(timers.stop("Engine", 5_000L))
        timers.start("Engine", 1_000L)
        timers.stop("Engine", 2_000L)
        assertNull("and again once it has been closed", timers.stop("Engine", 3_000L))
    }

    /** Several at once, each measured from its own start. */
    @Test
    fun `timers are independent of one another`() {
        val timers = TimedMarks()
        timers.start("Engine", 1_000L)
        timers.start("Tow", 3_000L)

        assertEquals(2_000L, timers.stop("Tow", 5_000L))
        assertEquals("Engine is untouched by Tow closing", mapOf("Engine" to 1_000L), timers.running())
        assertEquals(9_000L, timers.stop("Engine", 10_000L))
    }

    /**
     * What the run's teardown does with timers somebody left open — **oldest first**, because the marks
     * it publishes should reach the log in the order the timers were armed.
     */
    @Test
    fun `closing all reports every open timer, oldest first`() {
        val timers = TimedMarks()
        timers.start("Engine", 1_000L)
        timers.start("Tow", 3_000L)

        assertEquals(
            listOf("Engine" to 9_000L, "Tow" to 7_000L),
            timers.closeAll(10_000L),
        )
        assertTrue("and nothing survives into the next run", timers.running().isEmpty())
    }

    @Test
    fun `closing all with nothing open is empty rather than an error`() {
        assertTrue(TimedMarks().closeAll(10_000L).isEmpty())
    }

    /**
     * **A backwards clock must not produce a negative duration.**
     *
     * Not hypothetical: the boot-to-epoch offset is re-read per sample and an NTP correction mid-run can
     * move the wall clock behind where a timer started. `-00:00:03` on a log line is worse than zero,
     * which at least reads as "no measurable length".
     */
    @Test
    fun `a clock that steps backwards gives zero, not a negative`() {
        val timers = TimedMarks()
        timers.start("Engine", 10_000L)

        assertEquals(0L, timers.stop("Engine", 4_000L))

        timers.start("Tow", 10_000L)
        assertEquals(listOf("Tow" to 0L), timers.closeAll(4_000L))
    }

    /** The message a timed mark carries is built from this, so the clock has to read as one. */
    @Test
    fun `an elapsed span reads as a clock`() {
        assertEquals("00:00:00", formatElapsed(0L))
        assertEquals("00:00:04", formatElapsed(4_000L))
        assertEquals("00:35:12", formatElapsed(35 * 60_000L + 12_000L))
        assertEquals("12:00:00", formatElapsed(12 * 3_600_000L))
        // Past a day it keeps counting hours rather than wrapping — a run can be long.
        assertEquals("26:00:00", formatElapsed(26 * 3_600_000L))
    }
}

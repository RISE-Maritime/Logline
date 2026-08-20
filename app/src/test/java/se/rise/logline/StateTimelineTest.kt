package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.publish.SampleWindow
import se.rise.logline.ui.DetailKind
import se.rise.logline.ui.detailKind
import se.rise.logline.ui.stateSegments
import se.rise.logline.ui.timelineRows
import se.rise.logline.ui.timelineSpanMillis

/**
 * The timeline presentation, which is a pure function over the same window the plots use.
 *
 * What it exists to get right is *when a value changed and how long it held* — a question a line chart
 * cannot answer for an enum, because it draws a slope through states that have nothing between them.
 */
class StateTimelineTest {

    private fun window(vararg pairs: Pair<Long, Float>) = SampleWindow(
        timesMillis = pairs.map { it.first }.toLongArray(),
        values = pairs.map { it.second }.toFloatArray(),
    )

    @Test
    fun `contiguous equal values collapse into one segment`() {
        val segments = stateSegments(
            window(0L to 2f, 1000L to 2f, 2000L to 2f, 3000L to 1f, 4000L to 1f)
        )

        assertEquals(2, segments.size)
        assertEquals(2f, segments[0].value, 0f)
        assertEquals(3, segments[0].samples)
        assertEquals(1f, segments[1].value, 0f)
    }

    /**
     * **A segment ends at its own last sample, not at the next one's start.**
     *
     * Using the next segment's start would overstate every duration by one sample interval — at 0.2 Hz
     * that is five seconds added to each, which is plainly visible against a run of a few minutes.
     */
    @Test
    fun `a segment is measured to its own last sample`() {
        val segments = stateSegments(window(0L to 1f, 1000L to 1f, 5000L to 0f))

        assertEquals(1000L, segments[0].durationMillis)
        assertEquals(0L, segments[0].startMillis)
        assertEquals(1000L, segments[0].endMillis)
    }

    /** One sample says the value existed at an instant, not that it lasted. */
    @Test
    fun `a single sample has no duration`() {
        val segments = stateSegments(window(7000L to 3f))

        assertEquals(1, segments.size)
        assertEquals(0L, segments[0].durationMillis)
        assertEquals(1, segments[0].samples)
    }

    /**
     * A value returning is a *new* segment, which is the whole point: fix quality that went 3D → No fix
     * → 3D held three times, and merging the two 3D stretches would hide the outage between them.
     */
    @Test
    fun `a value that comes back gets its own segment`() {
        val segments = stateSegments(window(0L to 2f, 1000L to 0f, 2000L to 2f))

        assertEquals(3, segments.size)
        assertEquals(listOf(2f, 0f, 2f), segments.map { it.value })
        // ...but it is still one row on the bar.
        assertEquals(listOf(2f, 0f), timelineRows(segments))
    }

    /**
     * Rows are ordered by **first seen**, not numerically and not by frequency.
     *
     * Sorting would have rows jump about as a new cell id appears mid-run, and frequency would reorder
     * them as the window slides — either way the eye loses the row it was reading.
     */
    @Test
    fun `rows keep the order they first appeared in`() {
        val segments = stateSegments(
            window(0L to 9f, 1000L to 1f, 2000L to 5f, 3000L to 1f)
        )

        assertEquals(listOf(9f, 1f, 5f), timelineRows(segments))
    }

    /**
     * A zero span is not a corner case: a subject that has published once, or several times within the
     * same millisecond, has one — and dividing by it would put every segment at infinite width.
     */
    @Test
    fun `a span of zero is unanswerable rather than infinite`() {
        assertNull(timelineSpanMillis(stateSegments(window(5000L to 1f))))
        assertNull(timelineSpanMillis(stateSegments(window(5000L to 1f, 5000L to 0f))))
        assertNull(timelineSpanMillis(emptyList()))
        assertEquals(4000L, timelineSpanMillis(stateSegments(window(1000L to 1f, 5000L to 0f))))
    }

    @Test
    fun `an empty window has no segments`() {
        assertTrue(stateSegments(SampleWindow()).isEmpty())
    }

    /**
     * The classifier, which is what stops a subject being plotted in one place and tabulated in another
     * — the card and the detail screen both branch on this one function.
     */
    @Test
    fun `every subject is classified, and only the right ones leave the plot`() {
        val timeline = PublishedSubject.entries.filter { detailKind(it) == DetailKind.Timeline }
        val text = PublishedSubject.entries.filter { detailKind(it) == DetailKind.Text }

        assertEquals(
            setOf(
                PublishedSubject.FIX_QUALITY,
                PublishedSubject.BATTERY_IS_CHARGING,
                PublishedSubject.CELL_ID,
                PublishedSubject.PHYSICAL_CELL_ID,
                PublishedSubject.EARFCN,
            ),
            timeline.toSet(),
        )
        assertEquals(setOf(PublishedSubject.RAW_NMEA0183), text.toSet())

        // Everything else plots. Transcribed as a count rather than a list so adding a subject does not
        // need this test edited — but adding one that should *not* plot does.
        assertEquals(
            PublishedSubject.entries.size - timeline.size - text.size,
            PublishedSubject.entries.count { detailKind(it) == DetailKind.Plot },
        )
    }
}

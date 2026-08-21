package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.record.RecordingFacts
import se.rise.logline.ui.RecordingFilter
import se.rise.logline.ui.RecordingSort
import se.rise.logline.ui.matchesQuery
import se.rise.logline.ui.recordingsCount
import se.rise.logline.ui.visibleRecordings

/**
 * Finding one recording among a hundred and nineteen.
 *
 * The list on the dev phone is 119 files whose names differ only by a timestamp, which is why searching
 * and ordering matter here at all — and why the search has to accept a date written the way a person
 * writes one.
 */
class RecordingsQueryTest {

    /**
     * A recording as the query logic sees one.
     *
     * [RecordingFacts] rather than a [se.rise.logline.record.SavedRecording], which carries a MediaStore
     * `Uri` that does not exist off a device — the reason the interface is there.
     */
    private data class Fake(
        override val name: String,
        override val savedAtMillis: Long = 0L,
        override val sizeBytes: Long = 0L,
        override val durationMillis: Long? = 0L,
    ) : RecordingFacts {
        override val isComplete: Boolean get() = durationMillis != null
    }

    private fun recording(
        name: String,
        savedAt: Long = 0L,
        size: Long = 0L,
        durationMillis: Long? = 0L,
    ) = Fake(name, savedAt, size, durationMillis)

    private val august21 = "logline-2026-08-21T104536.mcap"

    /**
     * **A recording's name is its timestamp**, so a search that demanded the exact punctuation would be
     * a date search that rejects dates.
     */
    @Test
    fun `a date matches however it is punctuated`() {
        assertTrue(matchesQuery(august21, "2026-08-21"))
        assertTrue(matchesQuery(august21, "20260821"))
        assertTrue(matchesQuery(august21, "08-21"))
        assertTrue(matchesQuery(august21, "0821"))
        assertTrue(matchesQuery(august21, "104536"))
        assertTrue("case is ignored", matchesQuery(august21, "LOGLINE"))
    }

    @Test
    fun `a name that does not contain the query is not a match`() {
        assertFalse(matchesQuery(august21, "2026-08-22"))
        assertFalse(matchesQuery(august21, "zzz"))
    }

    /** An empty box expresses no constraint, and neither does a box holding only punctuation. */
    @Test
    fun `an empty or punctuation-only query matches everything`() {
        assertTrue(matchesQuery(august21, ""))
        assertTrue(matchesQuery(august21, "   "))
        assertTrue(matchesQuery(august21, "--"))
    }

    @Test
    fun `the newest recording comes first by default`() {
        val old = recording("a", savedAt = 1_000L)
        val new = recording("b", savedAt = 9_000L)

        val shown = visibleRecordings(listOf(old, new), "", RecordingSort.Newest, RecordingFilter.All)

        assertEquals(listOf(new, old), shown)
        assertEquals(
            listOf(old, new),
            visibleRecordings(listOf(old, new), "", RecordingSort.Oldest, RecordingFilter.All),
        )
    }

    @Test
    fun `largest first orders by size`() {
        val small = recording("a", savedAt = 9_000L, size = 1L)
        val big = recording("b", savedAt = 1_000L, size = 500L)

        assertEquals(
            listOf(big, small),
            visibleRecordings(listOf(small, big), "", RecordingSort.Largest, RecordingFilter.All),
        )
    }

    /**
     * **An unknown duration sorts last, never as zero.**
     *
     * An incomplete recording has no summary and so no duration — and it may well be the longest run
     * there is, because it is the one that was still going when the process was killed. Ranking it as a
     * zero-second run would bury exactly the file somebody is looking for while claiming to order by
     * length.
     */
    @Test
    fun `an unknown duration sorts last rather than as zero`() {
        val long = recording("long", savedAt = 3_000L, durationMillis = 9_000L)
        val short = recording("short", savedAt = 2_000L, durationMillis = 5L)
        val unknown = recording("unknown", savedAt = 1_000L, durationMillis = null)

        val shown = visibleRecordings(
            listOf(unknown, short, long), "", RecordingSort.Longest, RecordingFilter.All,
        )

        assertEquals(listOf(long, short, unknown), shown)
    }

    /** Ties break by newest, so the list does not reshuffle under the thumb between recompositions. */
    @Test
    fun `equal sizes are broken by newest`() {
        val older = recording("a", savedAt = 1_000L, size = 10L)
        val newer = recording("b", savedAt = 5_000L, size = 10L)

        assertEquals(
            listOf(newer, older),
            visibleRecordings(listOf(older, newer), "", RecordingSort.Largest, RecordingFilter.All),
        )
    }

    @Test
    fun `the filter separates the runs that closed from the ones that did not`() {
        val complete = recording("complete", durationMillis = 10L)
        val rescued = recording("rescued", durationMillis = null)
        val all = listOf(complete, rescued)

        assertEquals(all, visibleRecordings(all, "", RecordingSort.Oldest, RecordingFilter.All))
        assertEquals(
            listOf(complete),
            visibleRecordings(all, "", RecordingSort.Oldest, RecordingFilter.Complete),
        )
        assertEquals(
            listOf(rescued),
            visibleRecordings(all, "", RecordingSort.Oldest, RecordingFilter.Incomplete),
        )
    }

    /** Search and filter both apply; neither overrides the other. */
    @Test
    fun `a search and a filter narrow together`() {
        val wanted = recording("logline-2026-08-21T090000.mcap", durationMillis = null)
        val wrongDay = recording("logline-2026-08-22T090000.mcap", durationMillis = null)
        val wrongKind = recording("logline-2026-08-21T100000.mcap", durationMillis = 5L)
        val all = listOf(wanted, wrongDay, wrongKind)

        val shown = visibleRecordings(all, "08-21", RecordingSort.Oldest, RecordingFilter.Incomplete)

        assertEquals(listOf(wanted), shown)
    }

    /**
     * **The total is stated whenever something is hidden.** A search that quietly drops ninety-six
     * files looks exactly like a phone that has lost them.
     */
    @Test
    fun `the count names the total only when it differs`() {
        assertEquals("119 recordings", recordingsCount(119, 119))
        assertEquals("23 of 119 recordings", recordingsCount(23, 119))
        assertEquals("1 recording", recordingsCount(1, 1))
        assertEquals("0 of 119 recordings", recordingsCount(0, 119))
    }
}

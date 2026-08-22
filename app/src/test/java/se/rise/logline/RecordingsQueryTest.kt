package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.record.CONFIG_FOLDER
import se.rise.logline.record.DOWNLOADS_FOLDER
import se.rise.logline.record.RecordingFacts
import se.rise.logline.record.RecordingKind
import se.rise.logline.record.recordingKindOf
import se.rise.logline.ui.RecordingFilter
import se.rise.logline.ui.RecordingSort
import se.rise.logline.ui.matchesQuery
import se.rise.logline.ui.recordingSubtitle
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
        override val isComplete: Boolean? = durationMillis != null,
        private val messagesOverride: Long? = null,
        override val tags: Set<String> = emptySet(),
    ) : RecordingFacts {
        override val messages: Long? get() = messagesOverride ?: durationMillis?.let { 1L }
    }

    private fun recording(
        name: String,
        savedAt: Long = 0L,
        size: Long = 0L,
        durationMillis: Long? = 0L,
    ) = Fake(name, savedAt, size, durationMillis)

    /** A file in the folder that is not a recording at all: `isComplete` does not apply to it. */
    private fun export(name: String, size: Long = 982L) =
        Fake(name, savedAtMillis = 0L, sizeBytes = size, durationMillis = null, isComplete = null)

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
     * The four things that write into `Downloads/Logline`, by the names they actually use.
     *
     * The listing filters on the folder and not on a file type, so all four land in the Files tab; these
     * strings are copied from `Recorder`, `exportSettingsProfile`, `exportPlatformGeometry` and
     * `exportPlatformRegistry`.
     */
    @Test
    fun `a file's kind comes from its name`() {
        assertEquals(RecordingKind.Recording, recordingKindOf("logline-2026-08-21T104536.mcap"))
        assertEquals(
            RecordingKind.SettingsProfile,
            recordingKindOf("logline-settings-2026-08-19T133226.json"),
        )
        assertEquals(RecordingKind.PlatformLibrary, recordingKindOf("logline-platform-registry.json"))
        assertEquals(RecordingKind.PlatformGeometry, recordingKindOf("ssrs18-platform-geometry.json"))
        assertEquals(RecordingKind.Other, recordingKindOf("holiday-snap.jpg"))
        assertEquals("case is not the file system's promise", RecordingKind.Recording, recordingKindOf("LOGLINE.MCAP"))
    }

    /**
     * **Exports live a level below the recordings, and that is what keeps them out of this list.**
     *
     * `savedRecordings()` matches `RELATIVE_PATH` for exactly `Download/Logline/`, so a subfolder is
     * excluded by construction rather than by a filter somebody has to remember to keep working. If
     * these two ever became siblings, every settings profile would be back in the Recordings tab.
     */
    @Test
    fun `the config folder sits inside the recordings folder`() {
        assertTrue(
            "a subfolder, so the exact-match query cannot see it",
            CONFIG_FOLDER.startsWith("$DOWNLOADS_FOLDER/"),
        )
        assertNotEquals(DOWNLOADS_FOLDER, CONFIG_FOLDER)
    }

    /**
     * **The regression test for the reason any of this changed.**
     *
     * A settings profile and a platform-geometry export have no MCAP summary, and while `isComplete` was a
     * plain `Boolean` that made them *incomplete recordings* — so a bulk delete built on the Incomplete
     * set would have destroyed a platform's surveyed geometry, which cannot be recovered without going back
     * out with a tape measure. They must appear under All and under neither of the other two.
     */
    @Test
    fun `an export is neither complete nor incomplete`() {
        val run = recording("logline-2026-08-21T104536.mcap", durationMillis = 10L)
        val rescued = recording("logline-2026-08-21T110000.mcap", durationMillis = null)
        val geometry = export("ssrs18-platform-geometry.json")
        val profile = export("logline-settings-2026-08-21T120000.json")
        val all = listOf(run, rescued, geometry, profile)

        assertEquals(
            "everything is listed under All",
            all,
            visibleRecordings(all, "", RecordingSort.Oldest, RecordingFilter.All),
        )
        assertEquals(
            listOf(run),
            visibleRecordings(all, "", RecordingSort.Oldest, RecordingFilter.Complete),
        )
        assertEquals(
            "the exports are not in the set a bulk delete would take",
            listOf(rescued),
            visibleRecordings(all, "", RecordingSort.Oldest, RecordingFilter.Incomplete),
        )
    }

    /**
     * What each row says under the name.
     *
     * The export lines are the fix for a mislabel that was there from the start: every JSON in the
     * folder read `982 B · no summary`, which describes a recording that failed rather than a settings
     * profile that is exactly as it should be.
     */
    @Test
    fun `a row says what kind of file it is`() {
        assertEquals(
            "2 MB · 9605 messages over 00:01:58",
            recordingSubtitle(
                Fake(
                    "logline-2026-08-21T104536.mcap",
                    sizeBytes = 2_000_000L,
                    durationMillis = 118_000L,
                ).copy(messagesOverride = 9_605L)
            ),
        )
        assertEquals(
            "139 MB · incomplete, never closed",
            // The real size of logline-2026-08-19T210534.mcap on the dev phone.
            recordingSubtitle(recording("x.mcap", size = 145_815_242L, durationMillis = null)),
        )
        assertEquals(
            "982 bytes · Settings profile",
            recordingSubtitle(export("logline-settings-2026-08-21T120000.json")),
        )
        assertEquals(
            "982 bytes · Platform geometry export",
            recordingSubtitle(export("ssrs18-platform-geometry.json")),
        )
        assertEquals(
            "982 bytes · Platform library export",
            recordingSubtitle(export("logline-platform-registry.json")),
        )
        assertEquals("982 bytes · Not a recording", recordingSubtitle(export("holiday-snap.jpg")))
    }

    /** Under a second there is no duration worth printing, the same as the list has always done. */
    @Test
    fun `a very short recording states its messages and no duration`() {
        assertEquals(
            "2 kB · 3 messages",
            recordingSubtitle(
                Fake("short.mcap", sizeBytes = 2_048L, durationMillis = 4L)
                    .copy(messagesOverride = 3L)
            ),
        )
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

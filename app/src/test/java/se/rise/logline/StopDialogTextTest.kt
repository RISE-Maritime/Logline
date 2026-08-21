package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Test
import se.rise.logline.ui.stopFigures
import se.rise.logline.ui.stopNoteHint

/**
 * What the Stop dialog says a run did.
 *
 * A run does two independent things and either can be switched off at the start row, so the dialog has
 * four combinations to be right about. It was written when publishing was the only thing a run did, and
 * on a record-only run it claimed samples had been *published* and that a closing note would go *on the
 * bus* — two statements about a run that had deliberately done neither.
 */
class StopDialogTextTest {

    // `formatCount` groups digits with U+202F, a narrow no-break space, so a figure never wraps
    // mid-number. Spelled out in these literals rather than pasted, because an invisible character in
    // a test's expected string is a mismatch nobody can see when it fails.

    @Test
    fun `a publishing run says its samples were published`() {
        assertEquals(
            "95\u202F317 samples published over 00:01:58, 2 MB recorded.",
            stopFigures(95_317, publishing = true, elapsed = "00:01:58", recording = true, bytesWritten = 2_097_152),
        )
    }

    /**
     * **No verb rather than the wrong one.** `totalSamplesPublished` counts samples that reached
     * `SubjectSink.emit`, not puts; with publishing off it means "samples produced", which is what
     * reached the file.
     */
    @Test
    fun `a record-only run does not claim to have published`() {
        assertEquals(
            "95\u202F317 samples over 00:01:58, 2 MB recorded.",
            stopFigures(95_317, publishing = false, elapsed = "00:01:58", recording = true, bytesWritten = 2_097_152),
        )
    }

    /** Nothing was written, so there is no size to state. */
    @Test
    fun `a publish-only run states no file size`() {
        assertEquals(
            "1234 samples published over 00:00:30.",
            stopFigures(1_234, publishing = true, elapsed = "00:00:30", recording = false, bytesWritten = 0L),
        )
    }

    @Test
    fun `the note reaches the bus and the file when a run does both`() {
        assertEquals(
            "Marked against the run, on the bus and in the recording. The file keeps its name.",
            stopNoteHint(publishing = true, recording = true),
        )
    }

    /**
     * A note is a `log_message` through `SubjectSink.emit`, so with publishing off it reaches the file
     * and not the wire. Promising both was a claim about where somebody's words had gone.
     */
    @Test
    fun `a record-only note does not claim to reach the bus`() {
        assertEquals(
            "Marked against the run and in the recording. The file keeps its name.",
            stopNoteHint(publishing = false, recording = true),
        )
    }

    /** No file, so nothing could have been renamed and nothing says otherwise. */
    @Test
    fun `a publish-only note mentions neither a file nor its name`() {
        assertEquals(
            "Marked against the run and on the bus.",
            stopNoteHint(publishing = true, recording = false),
        )
    }
}

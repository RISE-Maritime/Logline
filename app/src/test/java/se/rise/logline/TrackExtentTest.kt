package se.rise.logline

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.record.TrackFix
import se.rise.logline.ui.MIN_CHART_SPAN_METRES
import se.rise.logline.ui.formatDistance
import se.rise.logline.ui.isStationary
import se.rise.logline.ui.trackExtentMetres
import se.rise.logline.ui.trackSummary
import java.util.Locale

/**
 * How big a track is, and how that is said.
 *
 * The numbers here are the ones measured off the dev phone, because the point of this code is that
 * every recording on it turned out to be a phone that never moved — and the old footer, which said only
 * how many positions there were, could not tell that from a passage.
 */
class TrackExtentTest {

    private val default = Locale.getDefault()

    @After
    fun restore() = Locale.setDefault(default)

    /** A degree of latitude is 111 320 m wherever you stand. */
    @Test
    fun `latitude spans measure in metres`() {
        val extent = trackExtentMetres(listOf(TrackFix(57.000, 12.0), TrackFix(57.001, 12.0)))

        assertEquals(111.32, extent, 0.01)
    }

    /**
     * **A degree of longitude is `cos(latitude)` as long**, 0.545 at 57°N. Without the correction a
     * track comes out nearly twice as wide as it was sailed — the same error the accuracy circle's
     * `metersToPixels` exists to avoid.
     */
    @Test
    fun `longitude spans are corrected for latitude`() {
        val extent = trackExtentMetres(listOf(TrackFix(57.0, 12.000), TrackFix(57.0, 12.001)))

        assertEquals(60.63, extent, 0.05)
        // The uncorrected figure, which is what a naive span would report.
        assertFalse("not the raw degree length", kotlin.math.abs(extent - 111.32) < 1.0)
    }

    /** The larger of the two axes, so a long thin track is measured along its length. */
    @Test
    fun `the extent is the larger axis`() {
        val fixes = listOf(TrackFix(57.000, 12.000), TrackFix(57.010, 12.001))

        assertEquals(1113.2, trackExtentMetres(fixes), 1.0)
    }

    @Test
    fun `a single fix has no extent`() {
        assertEquals(0.0, trackExtentMetres(listOf(TrackFix(57.0, 12.0))), 0.0)
        assertEquals(0.0, trackExtentMetres(emptyList()), 0.0)
    }

    /**
     * The three recordings this was measured against, all of them a phone indoors: 10.6 m over three
     * hours, 14.8 m, and 1.8 m — longer axis, which is what [trackExtentMetres] answers. Every one is
     * scatter, and every one used to draw as a voyage. The phone confirms each: the screen reads
     * "within 11 m", "within 15 m" and "within 2 m".
     */
    @Test
    fun `the measured recordings are all stationary`() {
        assertTrue(isStationary(10.6))
        assertTrue(isStationary(14.8))
        assertTrue(isStationary(1.8))
        // A hundred metres is still inside the floor: small is small.
        assertTrue(isStationary(100.0))
        assertFalse("but a passage is not", isStationary(1_200.0))
        assertFalse(isStationary(MIN_CHART_SPAN_METRES))
    }

    /**
     * **The preposition carries the finding.** "within" is scatter, "over" is travel — and the count
     * alone, which is all the footer used to say, reads as a voyage either way.
     */
    @Test
    fun `the summary says within for scatter and over for travel`() {
        assertEquals("1843 positions within 11 m", trackSummary(1843, 10.6))
        assertEquals("47 positions within 15 m", trackSummary(47, 14.8))
        assertEquals("17 positions within 2 m", trackSummary(17, 1.8))
        assertEquals("980 positions over 1.2 km", trackSummary(980, 1_200.0))
    }

    /** With nothing to span, the extent is not a fact worth stating. */
    @Test
    fun `a lone position states no distance`() {
        assertEquals("1 positions", trackSummary(1, 0.0))
    }

    @Test
    fun `distances read as metres below a kilometre and kilometres above`() {
        assertEquals("13 m", formatDistance(13.3))
        assertEquals("999 m", formatDistance(999.4))
        assertEquals("1.0 km", formatDistance(1_000.0))
        assertEquals("32.7 km", formatDistance(32_662.6))
    }

    /**
     * `Locale.ROOT`, like every other number in this app — a Swedish phone would otherwise print
     * `1,2 km`. The same rule `FormatTest` pins for `.fmt()`.
     */
    @Test
    fun `distances are not localised`() {
        Locale.setDefault(Locale.forLanguageTag("sv-SE"))

        assertEquals("1.2 km", formatDistance(1_200.0))
        assertEquals("13 m", formatDistance(13.3))
    }
}

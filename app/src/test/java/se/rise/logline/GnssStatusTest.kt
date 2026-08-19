package se.rise.logline

import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.sensors.FixKind
import se.rise.logline.sensors.fixQualityOf
import se.rise.logline.ui.formatLiveValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a fix is worth, which is the question a track gets asked afterwards.
 *
 * Two inputs, neither sufficient alone: `usedInFix` is the receiver's own statement about whether it
 * is solving, and only the `Location` knows whether the solution carried an altitude.
 */
class GnssStatusTest {

    /**
     * The state this subject exists for. Android's fused provider will hand back a position derived
     * from wifi and cell with the GNSS engine solving nothing at all — and from everywhere else in the
     * app that is indistinguishable from a good fix, because a position arrives either way.
     */
    @Test
    fun `no satellites in the solution is no fix, whatever position was published`() {
        val quality = fixQualityOf(used = 0, fixHasAltitude = true)

        assertEquals(FixKind.NoFix, quality.kind)
        assertFalse("the receiver is not solving", quality.solving)
    }

    /** Solving, but nothing to describe yet — before the first fix there is no 2D or 3D to claim. */
    @Test
    fun `satellites but no fix yet reads as no fix, and as solving`() {
        val quality = fixQualityOf(used = 7, fixHasAltitude = null)

        assertEquals(FixKind.NoFix, quality.kind)
        assertTrue("the engine is using satellites", quality.solving)
    }

    @Test
    fun `an altitude makes it three-dimensional`() {
        val quality = fixQualityOf(used = 9, fixHasAltitude = true)

        assertEquals(FixKind.ThreeD, quality.kind)
        assertTrue(quality.solving)
    }

    @Test
    fun `a fix without an altitude is two-dimensional`() {
        val quality = fixQualityOf(used = 4, fixHasAltitude = false)

        assertEquals(FixKind.TwoD, quality.kind)
        assertTrue(quality.solving)
    }

    /**
     * One satellite cannot produce a solution, and the receiver says so by not marking it used — which
     * is why the count is trusted rather than second-guessed with a threshold of our own. If a
     * receiver marks satellites used, it is solving; that is what the flag means.
     */
    @Test
    fun `the receiver's own count is what decides, not a threshold of ours`() {
        assertTrue(fixQualityOf(used = 1, fixHasAltitude = true).solving)
        assertEquals(FixKind.ThreeD, fixQualityOf(used = 1, fixHasAltitude = true).kind)
    }

    /**
     * The live row shows a word, the wire carries an enum, and the number between them is a [FixKind]
     * ordinal. Two files have to agree about that and neither can see the other, so it is pinned here:
     * publishing `kind.ordinal` and reading it back must produce the right word.
     */
    @Test
    fun `the live value round-trips through the row's label`() {
        val readings = FixKind.entries.associateWith {
            formatLiveValue(PublishedSubject.FIX_QUALITY, it.ordinal.toFloat())
        }

        assertEquals("No fix", readings[FixKind.NoFix])
        assertEquals("2D", readings[FixKind.TwoD])
        assertEquals("3D", readings[FixKind.ThreeD])
    }

    /** A value from nowhere must not crash the row or claim a fix. */
    @Test
    fun `an unrecognised live value reads as no fix`() {
        assertEquals("No fix", formatLiveValue(PublishedSubject.FIX_QUALITY, 99f))
        assertEquals("No fix", formatLiveValue(PublishedSubject.FIX_QUALITY, -1f))
    }
}

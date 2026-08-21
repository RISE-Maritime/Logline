package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.record.TrackFix
import se.rise.logline.ui.thumbnailOffsets
import kotlin.math.abs

/**
 * The geometry behind a row's 64dp track.
 *
 * Worth pinning precisely because none of it is visible until it is wrong: at this size a squashed or
 * stretched track still looks like a plausible track. Every recording on the dev phone is stationary, so
 * the moving case has no file to be seen on — these are what stand in for that.
 */
class TrackThumbnailTest {

    private val extent = 100f
    private val pad = 6f

    private fun offsets(fixes: List<TrackFix>) = thumbnailOffsets(fixes, extent, pad)

    /** A track running due north draws a vertical line: no sideways drift from the projection. */
    @Test
    fun `a north-south track draws vertically`() {
        val points = offsets(listOf(TrackFix(57.000, 12.0), TrackFix(57.010, 12.0)))

        assertEquals("same x", points[0].x, points[1].x, 0.001f)
        assertTrue("north is up", points[1].y < points[0].y)
    }

    /**
     * **A degree of longitude is `cos(latitude)` as long** — 0.545 at 57°N.
     *
     * So within one track, equal *degree* steps must draw a little over half as far across as up.
     * Without the correction a track comes out nearly twice as wide as it was sailed, which is the error
     * this whole family of charts keeps having to avoid.
     *
     * It has to be one track: each is scaled to its own extent, so two separate tracks would each fill
     * the frame and the ratio would be 1 whatever the projection did — which is exactly how this test
     * failed when it was first written.
     */
    @Test
    fun `longitude is compressed by the latitude`() {
        val diagonal = offsets(listOf(TrackFix(57.0, 12.0), TrackFix(57.01, 12.01)))

        val vertical = abs(diagonal[1].y - diagonal[0].y)
        val horizontal = abs(diagonal[1].x - diagonal[0].x)

        assertEquals(0.545, (horizontal / vertical).toDouble(), 0.01)
    }

    /**
     * **One scale for both axes**, so a long thin track stays long and thin rather than being stretched
     * to fill the square.
     */
    @Test
    fun `a long thin track is not stretched to the frame`() {
        // Ten times as far north as east, in metres.
        val points = offsets(listOf(TrackFix(57.0, 12.0), TrackFix(57.02, 12.0004)))

        val vertical = abs(points[1].y - points[0].y)
        val horizontal = abs(points[1].x - points[0].x)

        assertTrue("still ten to one, not one to one", vertical > horizontal * 8f)
    }

    /** A track larger than the floor fills the square, less its padding. */
    @Test
    fun `a track spanning kilometres fills the square`() {
        // ~2.2 km north-south, well past the 200 m floor.
        val points = offsets(listOf(TrackFix(57.00, 12.0), TrackFix(57.02, 12.0)))

        assertEquals("bottom edge", pad + extent, points[0].y, 0.5f)
        assertEquals("top edge", pad, points[1].y, 0.5f)
    }

    /**
     * **The floor keeps a small track small.** A 20 m run inside a 200 m frame occupies a tenth of it —
     * which is why anything under the floor is drawn as a marker instead, there being no shape to read
     * at three pixels.
     */
    @Test
    fun `a track below the floor occupies a fraction of the square`() {
        val points = offsets(listOf(TrackFix(57.0, 12.0), TrackFix(57.00018, 12.0)))

        val drawn = abs(points[1].y - points[0].y)
        assertEquals(extent * 0.1f, drawn, 1f)
    }

    /** Every point lands inside the square, whatever the track. */
    @Test
    fun `points stay within the frame`() {
        val points = offsets(
            listOf(
                TrackFix(57.000, 12.000),
                TrackFix(57.030, 12.050),
                TrackFix(56.990, 11.990),
            )
        )

        points.forEach {
            assertTrue("x in range: ${it.x}", it.x >= pad - 0.5f && it.x <= pad + extent + 0.5f)
            assertTrue("y in range: ${it.y}", it.y >= pad - 0.5f && it.y <= pad + extent + 0.5f)
        }
    }
}

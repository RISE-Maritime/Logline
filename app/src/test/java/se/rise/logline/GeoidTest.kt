package se.rise.logline

import se.rise.logline.sensors.undulationMetres
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TOLERANCE = 1e-9

/**
 * The sign of the geoid correction, which is the one thing here that can be silently backwards.
 *
 * `h = H + N` — ellipsoidal height is mean-sea-level height plus the undulation — so **N = h − H**.
 * Get it the other way round and every number still looks reasonable: same magnitude, same units, a
 * plausible 30-odd metres. It only shows up when somebody uses it to convert between the two
 * altitudes and lands 70 m out.
 */
class GeoidTest {

    /**
     * The worked example from the review of this subject: an ellipsoidal 43 m against an MSL 7 m is an
     * undulation of **+36 m**, not −36.
     */
    @Test
    fun `the undulation is ellipsoidal minus mean sea level`() {
        assertEquals(36.0, undulationMetres(ellipsoidalMetres = 43.0, mslMetres = 7.0), TOLERANCE)
    }

    /**
     * Positive across northern Europe, where the geoid sits *above* the ellipsoid by 30-35 m. This is
     * the case that will be seen on the phone this app is developed on, so it is the one a wrong sign
     * would be caught by — eventually.
     */
    @Test
    fun `Sweden comes out positive`() {
        val undulation = undulationMetres(ellipsoidalMetres = 60.0, mslMetres = 27.0)

        assertTrue("the geoid is above the ellipsoid here", undulation > 0)
        assertEquals(33.0, undulation, TOLERANCE)
    }

    /**
     * And negative over much of the Indian Ocean, where the geoid is *below* the ellipsoid by as much
     * as 100 m. An `abs()` would pass every test above and be wrong only on the other side of the
     * world — which is exactly the kind of bug that ships.
     */
    @Test
    fun `a geoid below the ellipsoid comes out negative`() {
        val undulation = undulationMetres(ellipsoidalMetres = 5.0, mslMetres = 105.0)

        assertTrue("the geoid is below the ellipsoid there", undulation < 0)
        assertEquals(-100.0, undulation, TOLERANCE)
    }

    /** The two altitudes have to be recoverable from each other, which is the point of publishing it. */
    @Test
    fun `a consumer can convert either way`() {
        val ellipsoidal = 43.2
        val msl = 7.4
        val n = undulationMetres(ellipsoidal, msl)

        assertEquals("H = h − N", msl, ellipsoidal - n, TOLERANCE)
        assertEquals("h = H + N", ellipsoidal, msl + n, TOLERANCE)
    }

    /** A fix at sea level on a geoid that happens to match the ellipsoid is zero, not missing. */
    @Test
    fun `a coincident geoid is zero`() {
        assertEquals(0.0, undulationMetres(0.0, 0.0), TOLERANCE)
        assertEquals(0.0, undulationMetres(12.5, 12.5), TOLERANCE)
    }
}

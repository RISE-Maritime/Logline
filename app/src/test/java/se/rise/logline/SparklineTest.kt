package se.rise.logline

import se.rise.logline.ui.Bounds
import se.rise.logline.ui.boundsOf
import se.rise.logline.ui.normalise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import se.rise.logline.ui.plotBounds
import se.rise.logline.ui.envelope
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * The plotting maths, tested on the JVM because drawing code that computes its own bounds can only be
 * checked by squinting at a screen. Same reasoning as `AgeTest` — everything is a parameter.
 */
class SparklineTest {

    @Test
    fun `bounds span the data`() {
        val bounds = boundsOf(floatArrayOf(-2f, 5f, 1f))!!
        assertEquals(-2f, bounds.min, 1e-6f)
        assertEquals(5f, bounds.max, 1e-6f)
        assertEquals(7f, bounds.span, 1e-6f)
    }

    /**
     * A flat series is the common case, not an edge case — a stationary magnetometer, a battery that
     * has not moved. Zero span would divide by zero or pin the line to the top edge.
     */
    @Test
    fun `a flat series gets a padded band rather than zero span`() {
        val bounds = boundsOf(floatArrayOf(4.2f, 4.2f, 4.2f))!!
        assertTrue("span must be positive", bounds.span > 0f)
        assertTrue("the value must sit inside the band", 4.2f > bounds.min && 4.2f < bounds.max)
        // A flat zero series still needs a band, and a proportional pad would give it none.
        val atZero = boundsOf(floatArrayOf(0f, 0f))!!
        assertTrue(atZero.span > 0f)
    }

    @Test
    fun `no samples means no bounds`() {
        assertNull(boundsOf(floatArrayOf()))
    }

    @Test
    fun `normalise maps the range onto zero to one, bottom up`() {
        val bounds = Bounds(0f, 10f)
        assertEquals(0f, normalise(0f, bounds), 1e-6f)
        assertEquals(1f, normalise(10f, bounds), 1e-6f)
        assertEquals(0.5f, normalise(5f, bounds), 1e-6f)
    }

    /** Out-of-range values are clamped rather than drawn outside the canvas. */
    @Test
    fun `normalise clamps beyond the bounds`() {
        val bounds = Bounds(0f, 10f)
        assertEquals(0f, normalise(-5f, bounds), 1e-6f)
        assertEquals(1f, normalise(50f, bounds), 1e-6f)
    }


    // -- the plotted range -----------------------------------------------------------------------

    /**
     * The measured case that prompted this: a stationary barometer reading 100 035–100 037 Pa. Scaled
     * to the data's own range that 2 Pa fills the card and the sensor looks broken; it is 0.002% of the
     * value.
     */
    @Test
    fun `a near-constant series is drawn flat, not magnified`() {
        val pressure = boundsOf(floatArrayOf(100_035.29f, 100_036.5f, 100_037.52f))!!
        val axis = plotBounds(pressure)

        assertTrue("the axis should be wider than the data", axis.span > pressure.span * 100)
        // The wiggle now occupies a sliver of the height rather than all of it.
        val top = normalise(pressure.max, axis)
        val bottom = normalise(pressure.min, axis)
        assertTrue("wiggle fills ${'$'}{(top - bottom) * 100}% of the card", top - bottom < 0.02f)
    }

    /** Real movement must still fill the plot — the floor is not a general flattening. */
    @Test
    fun `a series that genuinely varies is left alone`() {
        val heading = Bounds(55.7f, 61.4f)
        assertEquals(heading, plotBounds(heading))

        val battery = Bounds(83f, 84f)
        assertEquals(battery, plotBounds(battery))
    }

    /**
     * A gyroscope at rest has a mean near zero and noise several times larger than it, so the noise is
     * the whole signal. A relative floor leaves it visible, which is the honest picture.
     */
    @Test
    fun `noise around zero stays visible`() {
        val gyro = Bounds(0f, 0.0035f)
        assertEquals(gyro, plotBounds(gyro))
    }

    // -- binning ---------------------------------------------------------------------------------

    /** Every sample lands in some bin, so a spike is always somebody's maximum. */
    @Test
    fun `binning keeps the extremes that stride sampling would drop`() {
        val values = FloatArray(1000) { 0f }
        values[137] = 42f
        values[864] = -17f

        val bins = envelope(values, 50)!!

        assertEquals(50, bins.size)
        assertEquals(42f, bins.maxs.max(), 1e-6f)
        assertEquals(-17f, bins.mins.min(), 1e-6f)
    }

    /**
     * The frame-to-frame dance this replaces: stride sampling picks one sample in twenty-two and a
     * different one each frame. Binning depends only on the sample count, so the same window always
     * draws the same picture.
     */
    @Test
    fun `binning is deterministic for a given window`() {
        val values = FloatArray(6600) { kotlin.math.sin(it / 7.0).toFloat() }

        assertEquals(envelope(values, 300), envelope(values, 300))
    }

    @Test
    fun `the mean runs between the extremes`() {
        val values = FloatArray(400) { if (it % 2 == 0) 0f else 10f }

        val bins = envelope(values, 20)!!

        bins.means.indices.forEach { i ->
            assertTrue(bins.means[i] >= bins.mins[i] && bins.means[i] <= bins.maxs[i])
        }
        assertEquals("alternating 0 and 10 averages to 5", 5f, bins.means[0], 0.5f)
    }

    /** Sparser than the plot is wide: one bin each, and the band collapses onto the line. */
    @Test
    fun `a short series bins to itself`() {
        val values = floatArrayOf(1f, 5f, 3f)

        val bins = envelope(values, 300)!!

        assertEquals(3, bins.size)
        assertArrayEquals(values, bins.mins, 1e-6f)
        assertArrayEquals(values, bins.maxs, 1e-6f)
        assertArrayEquals(values, bins.means, 1e-6f)
    }

    @Test
    fun `nothing to bin is null rather than an empty picture`() {
        assertNull(envelope(FloatArray(0), 100))
        assertNull(envelope(floatArrayOf(1f), 0))
    }
}

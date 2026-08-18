package se.rise.logline

import se.rise.logline.keelson.diagonalEnuCovariance
import org.junit.Assert.assertEquals
import org.junit.Test

private const val DELTA = 1e-9

class CovarianceTest {

    /**
     * Android's accuracies are 1σ radii in metres, so the matrix carries their **squares**. A version
     * of this that forgot to square would be invisible on the wire and would quietly tell every
     * consumer the fix is far better than it is.
     */
    @Test
    fun `variance is the square of the accuracy`() {
        val cov = diagonalEnuCovariance(horizontalMetres = 5.0, verticalMetres = 3.0)

        assertEquals(25.0, cov[0], DELTA)
        assertEquals(25.0, cov[4], DELTA)
        assertEquals(9.0, cov[8], DELTA)
    }

    @Test
    fun `it is a nine element row-major matrix with an empty off-diagonal`() {
        val cov = diagonalEnuCovariance(4.0, 2.0)

        assertEquals(9, cov.size)
        listOf(1, 2, 3, 5, 6, 7).forEach { i ->
            assertEquals("off-diagonal index $i", 0.0, cov[i], DELTA)
        }
    }

    /** East equals north because the phone reports one radius, not an ellipse — hence APPROXIMATED. */
    @Test
    fun `east and north share the horizontal variance`() {
        val cov = diagonalEnuCovariance(7.5, 1.0)

        assertEquals(cov[0], cov[4], DELTA)
        assertEquals(56.25, cov[0], DELTA)
    }

    @Test
    fun `a zero accuracy stays zero`() {
        val cov = diagonalEnuCovariance(0.0, 0.0)

        assertEquals(List(9) { 0.0 }, cov)
    }

    @Test
    fun `a sub-metre accuracy shrinks rather than grows`() {
        // 0.5 m -> 0.25 m^2. Squaring is only obviously right for values above 1.
        val cov = diagonalEnuCovariance(0.5, 0.5)

        assertEquals(0.25, cov[0], DELTA)
        assertEquals(0.25, cov[8], DELTA)
    }
}

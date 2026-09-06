package se.rise.logline

import se.rise.logline.calibrate.AveragedFix
import se.rise.logline.calibrate.CaptureMethod
import se.rise.logline.calibrate.HeadingSource
import se.rise.logline.calibrate.LatLonAlt
import se.rise.logline.calibrate.PlatformZero
import se.rise.logline.calibrate.zeroFromFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every field of an averaged fix reaching the zero point it becomes.
 *
 * This is a field-by-field copy, which is the shape that goes wrong quietly — and it already did:
 * `verticalAccuracyM` was computed by `averageFix`, stored on `PlatformZero`, serialised into the
 * wire document, and *required* by the covariance `SensorPublisher` puts on `location_fix` — while
 * the construction never assigned it. Nothing failed. The covariance branch simply never fired, so
 * every captured zero this app has ever published went out without one.
 *
 * The construction used to live in a Compose lambda in `MainActivity`, where no test could reach it.
 * That is why it is a function now.
 */
class ZeroFromFixTest {

    private val fix = AveragedFix(
        point = LatLonAlt(57.4359, 12.0326, 14.2),
        hasAltitude = true,
        accuracyM = 3.4,
        verticalAccuracyM = 6.8,
        scatterM = 0.42,
        samples = 19,
    )

    @Test
    fun `every measured field survives the crossing`() {
        val zero = zeroFromFix(fix, previous = null, atEpochMillis = 1_700_000_000_000L)

        assertEquals(57.4359, zero.latitude, 1e-9)
        assertEquals(12.0326, zero.longitude, 1e-9)
        assertEquals(14.2, zero.altitudeM!!, 1e-9)
        assertEquals(3.4, zero.accuracyM!!, 1e-9)
        assertEquals(6.8, zero.verticalAccuracyM!!, 1e-9)
        assertEquals(0.42, zero.scatterM!!, 1e-9)
        assertEquals(19, zero.samples)
        assertEquals(1_700_000_000_000L, zero.capturedAtEpochMillis)
        assertEquals(CaptureMethod.GNSS_AVERAGE, zero.capture)
        assertTrue(zero.hasPosition)
    }

    /**
     * **The regression that prompted this file.** Vertical accuracy is roughly twice the horizontal
     * — it is the axis GNSS is worst at and the one a mast height is measured along — and
     * `SensorPublisher` states a covariance only when it holds *both*. Losing this one loses the
     * covariance entirely rather than half of it.
     */
    @Test
    fun `vertical accuracy is carried, because the covariance needs both`() {
        val zero = zeroFromFix(fix, previous = null, atEpochMillis = 0L)

        assertTrue(
            "both accuracies present is what makes a covariance publishable",
            zero.accuracyM != null && zero.verticalAccuracyM != null,
        )
    }

    /**
     * An altitude the fix did not carry must not arrive as zero metres. `LatLonAlt` defaults it, so
     * the flag is the only thing that can tell "at sea level" from "not reported".
     */
    @Test
    fun `an unreported altitude stays absent rather than becoming zero`() {
        val zero = zeroFromFix(fix.copy(hasAltitude = false), previous = null, atEpochMillis = 0L)

        assertNull(zero.altitudeM)
    }

    /**
     * A re-capture moves the position and keeps the forward axis. The heading is established in its
     * own wizard step, by baseline or compass or by hand, and standing in the right place again is
     * not a reason to make somebody do it twice.
     */
    @Test
    fun `re-capturing keeps the heading and where it came from`() {
        val previous = PlatformZero(
            latitude = 1.0,
            longitude = 2.0,
            altitudeM = null,
            accuracyM = null,
            scatterM = null,
            headingDeg = 137.5,
            headingSource = HeadingSource.BASELINE,
            capture = CaptureMethod.MANUAL,
            samples = 0,
            capturedAtEpochMillis = 0L,
        )

        val zero = zeroFromFix(fix, previous = previous, atEpochMillis = 0L)

        assertEquals(137.5, zero.headingDeg, 1e-9)
        assertEquals(HeadingSource.BASELINE, zero.headingSource)
        // ...and the position really did move.
        assertEquals(57.4359, zero.latitude, 1e-9)
    }

    /** With nothing before it there is no heading to keep, and none is invented. */
    @Test
    fun `a first capture has no heading and says the heading was typed`() {
        val zero = zeroFromFix(fix, previous = null, atEpochMillis = 0L)

        assertEquals(0.0, zero.headingDeg, 1e-9)
        assertEquals(HeadingSource.MANUAL, zero.headingSource)
    }
}

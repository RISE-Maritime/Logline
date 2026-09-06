package se.rise.logline

import se.rise.logline.calibrate.CaptureMethod
import se.rise.logline.calibrate.HeadingSource
import se.rise.logline.calibrate.PlatformCalibration
import se.rise.logline.calibrate.PlatformZero
import se.rise.logline.calibrate.parsePlatformGeometry
import se.rise.logline.calibrate.toStoredJson
import se.rise.logline.calibrate.zeroFromMap
import se.rise.logline.ui.fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * A zero point put down on a chart.
 *
 * The interesting content here is all about what a map pick *does not* know. It has a position and
 * nothing else — no accuracy the app can derive, no scatter, no altitude — and every one of those
 * absences is a decision that could plausibly have gone the other way and been wrong.
 */
class ZeroFromMapTest {

    private fun picked(
        latitude: Double = 57.4359,
        longitude: Double = 12.0326,
        accuracyM: Double? = null,
        previous: PlatformZero? = null,
    ) = zeroFromMap(latitude, longitude, accuracyM, previous, atEpochMillis = 1_700_000_000_000L)

    @Test
    fun `a picked position is a position, and says it came from a map`() {
        val zero = picked()

        assertEquals(57.4359, zero.latitude, 1e-9)
        assertEquals(12.0326, zero.longitude, 1e-9)
        assertEquals(CaptureMethod.MAP, zero.capture)
        assertTrue(zero.hasPosition)
    }

    /**
     * **No invented accuracy.** The app cannot know how well the basemap is georeferenced, and the
     * obvious substitute — the ground resolution at the zoom somebody picked at — measures how
     * precisely they pointed rather than how right the imagery is. It would read as sub-metre at high
     * zoom, which is exactly where it is least true.
     */
    @Test
    fun `nothing is invented for the accuracies or the scatter`() {
        val zero = picked()

        assertNull(zero.accuracyM)
        assertNull(zero.verticalAccuracyM)
        // A single point put down by hand has no repeatability to measure; 0 would claim perfect
        // precision, which is the opposite of true.
        assertNull(zero.scatterM)
        assertEquals(0, zero.samples)
    }

    /** A surveyor who knows their chart can say so, and only then does a number appear. */
    @Test
    fun `a stated accuracy is kept, and stays horizontal-only`() {
        val zero = picked(accuracyM = 4.0)

        assertEquals(4.0, zero.accuracyM!!, 1e-9)
        // Not halved, doubled or mirrored into the vertical. Without a measurement there is nothing
        // to derive from, and a made-up vertical accuracy would put a covariance on the wire that no
        // instrument produced.
        assertNull(zero.verticalAccuracyM)
    }

    /**
     * A chart gives a position, not a height. An altitude already surveyed by standing at the point
     * is better than the nothing a map can offer, so it survives being moved.
     */
    @Test
    fun `an altitude already surveyed survives a re-pick`() {
        val previous = PlatformZero(
            latitude = 1.0,
            longitude = 2.0,
            altitudeM = 14.2,
            accuracyM = 3.4,
            verticalAccuracyM = 6.8,
            scatterM = 0.42,
            headingDeg = 137.5,
            headingSource = HeadingSource.BASELINE,
            capture = CaptureMethod.GNSS_AVERAGE,
            samples = 19,
            capturedAtEpochMillis = 1L,
        )

        val zero = picked(previous = previous)

        assertEquals(14.2, zero.altitudeM!!, 1e-9)
        // The forward axis too — established in its own step, and moving the position is not a reason
        // to make somebody establish it again.
        assertEquals(137.5, zero.headingDeg, 1e-9)
        assertEquals(HeadingSource.BASELINE, zero.headingSource)
        // But the old fix's accuracies do not come along: they described a different measurement.
        assertNull(zero.accuracyM)
        assertNull(zero.scatterM)
    }

    /**
     * **Null Island is the absent marker**, so a pick dragged there reads as no position at all.
     *
     * Pinned as known rather than left to be discovered. `hasPosition` uses lat/lon 0 to mean "only a
     * heading was ever set", which doubles as the absent marker because nothing is calibrated in the
     * Gulf of Guinea — and a crosshair can be panned there.
     */
    @Test
    fun `a pick at zero zero reads as no position, which is the known cost of the absent marker`() {
        assertFalse(picked(latitude = 0.0, longitude = 0.0).hasPosition)
        // A hair off it is a real position again.
        assertTrue(picked(latitude = 0.0, longitude = 0.000001).hasPosition)
    }

    /** The provenance has to survive the round trip, or the wire cannot say how the zero was got. */
    @Test
    fun `the map provenance round-trips through the stored document`() {
        val platform = PlatformCalibration.forName("Sealog").copy(zero = picked(accuracyM = 4.0))

        val read = parsePlatformGeometry(platform.toStoredJson())

        assertNotNull(read)
        assertEquals(CaptureMethod.MAP, read!!.zero!!.capture)
        assertEquals(4.0, read.zero!!.accuracyM!!, 1e-9)
        assertNull(read.zero!!.scatterM)
    }

    /**
     * **The locale trap, which is one edit away at all times.**
     *
     * `SensorMountScreen` shipped this bug: `"%.1f".format(x)` follows `Locale.getDefault()`, so on a
     * Swedish phone a captured value went into a field as `4,0`, `toDoubleOrNull()` rejected it, and
     * Save stored `0.0`. For the zero point that is worse than a mis-placed sensor — `0.0` is
     * `hasPosition == false`, so it would silently unset the position rather than move it.
     *
     * The picker's readouts therefore use `.fmt()`, which is `Locale.ROOT`. This pins the round trip
     * the screen depends on.
     */
    @Test
    fun `a stated accuracy round-trips through a field on a Swedish phone`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("sv-SE"))

            // What the locale-following formatter would have produced, and what it costs.
            assertNull(String.format(Locale.getDefault(), "%.1f", 4.0).toDoubleOrNull())

            val shown = "%.1f".fmt(4.0)
            assertEquals("4.0", shown)
            assertEquals(4.0, shown.toDoubleOrNull()!!, 1e-9)
        } finally {
            Locale.setDefault(original)
        }
    }
}

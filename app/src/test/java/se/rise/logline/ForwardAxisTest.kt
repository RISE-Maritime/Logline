package se.rise.logline

import se.rise.logline.calibrate.CaptureMethod
import se.rise.logline.calibrate.HeadingSource
import se.rise.logline.calibrate.LatLonAlt
import se.rise.logline.calibrate.PlatformCalibration
import se.rise.logline.calibrate.PlatformZero
import se.rise.logline.calibrate.degreesPerMetreOfError
import se.rise.logline.calibrate.headingFromBaseline
import se.rise.logline.calibrate.parsePlatformGeometry
import se.rise.logline.calibrate.toPlatformGeometryJson
import se.rise.logline.calibrate.toStoredJson
import se.rise.logline.ui.fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The forward axis as a baseline: which way, over how far, and how much that is worth.
 *
 * The length is the content here. A baseline's angular error is position error divided by baseline
 * length, so the same metre of uncertainty is thirty degrees over two metres and under three over
 * twenty — which `docs/calibration.md` has told people to care about while nothing recorded it.
 */
class ForwardAxisTest {

    private val zero = LatLonAlt(57.4359, 12.0326, 0.0)

    @Test
    fun `a due-north baseline reads zero degrees, and a due-east one ninety`() {
        // ~111 km per degree of latitude, so a hundredth of a degree is about 1.1 km north.
        val north = headingFromBaseline(zero, zero.copy(latitude = zero.latitude + 0.01))
        assertEquals(0.0, north.bearingDegrees, 0.5)

        val east = headingFromBaseline(zero, zero.copy(longitude = zero.longitude + 0.01))
        assertEquals(90.0, east.bearingDegrees, 0.5)
    }

    /**
     * The length comes back in metres, and it is the *ground* distance — longitude degrees shrink with
     * the cosine of latitude, which at 57°N is a factor of about 0.54. A length taken off raw degrees
     * would be nearly twice what it should be.
     */
    @Test
    fun `the length is a ground distance, not a degree difference`() {
        val north = headingFromBaseline(zero, zero.copy(latitude = zero.latitude + 0.001))
        assertEquals(111.2, north.lengthM, 1.0)

        val east = headingFromBaseline(zero, zero.copy(longitude = zero.longitude + 0.001))
        // Same degree step, roughly cos(57.4°) of the distance.
        assertEquals(60.0, east.lengthM, 2.0)
    }

    /**
     * The "walk further" instruction as a number, which is what the picker states while somebody is
     * choosing where to put the far point. Adjectives lose to figures everywhere else in this app.
     */
    @Test
    fun `a metre of error is about a degree over fifty-seven metres`() {
        assertEquals(1.0, degreesPerMetreOfError(57.29), 0.02)
        // And the failure the instruction is about: a short baseline is nearly worthless.
        assertEquals(26.6, degreesPerMetreOfError(2.0), 0.2)
        assertEquals(2.9, degreesPerMetreOfError(20.0), 0.1)
    }

    /** A zero-length baseline has no bearing worth anything, and says so rather than dividing by it. */
    @Test
    fun `a degenerate baseline reports an infinite uncertainty`() {
        assertTrue(degreesPerMetreOfError(0.0).isInfinite())
    }

    // ── provenance ──────────────────────────────────────────────────────────────────────────────

    private fun zeroWith(source: HeadingSource, baselineM: Double?) = PlatformZero(
        latitude = 57.4359,
        longitude = 12.0326,
        altitudeM = null,
        accuracyM = null,
        scatterM = null,
        headingDeg = 137.5,
        headingSource = source,
        capture = CaptureMethod.MANUAL,
        samples = 0,
        capturedAtEpochMillis = 1L,
        headingBaselineM = baselineM,
    )

    @Test
    fun `a map baseline and its length round-trip through the stored document`() {
        val platform = PlatformCalibration.forName("Sealog")
            .copy(zero = zeroWith(HeadingSource.MAP_BASELINE, 47.25))

        val read = parsePlatformGeometry(platform.toStoredJson())

        assertNotNull(read)
        assertEquals(HeadingSource.MAP_BASELINE, read!!.zero!!.headingSource)
        assertEquals(47.25, read.zero!!.headingBaselineM!!, 1e-6)
    }

    /**
     * A compass heading has no baseline, and the field is omitted rather than written as zero — which
     * would read as a baseline so short the bearing means nothing.
     */
    @Test
    fun `a heading with no baseline writes no length`() {
        val platform = PlatformCalibration.forName("Sealog")
            .copy(zero = zeroWith(HeadingSource.COMPASS, null))

        val json = platform.toStoredJson()

        assertFalse(json.contains("heading_baseline_m"))
        assertNull(parsePlatformGeometry(platform.toStoredJson())!!.zero!!.headingBaselineM)
    }

    /**
     * The export is upstream's schema and `additionalProperties: false` at every level, so none of this
     * provenance may appear in it — the same assertion `PlatformGeometryParseTest` makes for the rest
     * of the calibration block.
     */
    @Test
    fun `the export carries no baseline length`() {
        val platform = PlatformCalibration.forName("Sealog")
            .copy(zero = zeroWith(HeadingSource.MAP_BASELINE, 47.25))

        val exported = platform.toPlatformGeometryJson(provenance = false)

        assertFalse(exported.contains("heading_baseline_m"))
        assertFalse(exported.contains("map_baseline"))
    }

    /**
     * **A typed bearing carries no length**, even when it was typed with a map open. Keeping the
     * length from the pan that preceded it would attach a baseline to a number that did not come from
     * one — the picker clears it for this reason and the model has to be able to say so.
     */
    @Test
    fun `a typed bearing with no baseline is representable and survives the round trip`() {
        val platform = PlatformCalibration.forName("Sealog")
            .copy(zero = zeroWith(HeadingSource.MANUAL, null))

        val read = parsePlatformGeometry(platform.toStoredJson())!!

        assertEquals(HeadingSource.MANUAL, read.zero!!.headingSource)
        assertNull(read.zero!!.headingBaselineM)
    }

    /**
     * The locale trap, in the shape `SensorMountFieldsTest` uses: the picker prints the implied
     * uncertainty with `.fmt()`, and `String.format` following the phone's locale is what shipped a
     * data-loss bug once already.
     */
    @Test
    fun `the uncertainty figure formats point-decimal on a Swedish phone`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("sv-SE"))
            assertEquals("1.0", "%.1f".fmt(degreesPerMetreOfError(57.29)))
        } finally {
            Locale.setDefault(original)
        }
    }
}

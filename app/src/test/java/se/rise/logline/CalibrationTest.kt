package se.rise.logline

import se.rise.logline.calibrate.CaptureMethod
import se.rise.logline.calibrate.HeadingSource
import se.rise.logline.calibrate.PlatformCalibration
import se.rise.logline.calibrate.PlatformZero
import se.rise.logline.calibrate.SensorMount
import se.rise.logline.calibrate.SensorType
import se.rise.logline.calibrate.Vec3M
import se.rise.logline.calibrate.defaultEntityId
import se.rise.logline.calibrate.defaultFrameId
import se.rise.logline.calibrate.defaultParentFrameId
import se.rise.logline.calibrate.slugify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The naming and the two judgements the model makes on its own. */
class CalibrationTest {

    @Test
    fun `names become hyphenated slugs, the way keelson spells entity ids`() {
        assertEquals("ssrs18", slugify("SSRS18"))
        assertEquals("ouster-os-lidar", slugify("Ouster OS lidar"))
        assertEquals("r-v-svea", slugify("R/V Svea"))
        assertEquals("platform-2", slugify("  Platform #2!  "))
        assertEquals("", slugify("!!!"))
    }

    @Test
    fun `a platform with an unusable name still gets usable ids`() {
        // Never an empty key segment: a frame id of "-frame-" or an entity id of "" would publish on a
        // malformed key rather than fail, which is the worst of both.
        assertEquals("platform", defaultEntityId("!!!"))
        assertEquals("platform-frame-ccrp", defaultParentFrameId(""))
        assertEquals("platform-frame-sensor", defaultFrameId("", ""))
    }

    @Test
    fun `frame ids read as platform then sensor`() {
        assertEquals("ssrs18-frame-ccrp", defaultParentFrameId("SSRS18"))
        assertEquals("ssrs18-frame-ouster-os-lidar", defaultFrameId("SSRS18", "Ouster OS lidar"))
    }

    @Test
    fun `a platform with no sensors has nothing to publish`() {
        val empty = PlatformCalibration.forName("SSRS18")
        assertFalse(empty.isPublishable)
        assertTrue(empty.copy(sensors = listOf(mount(Vec3M(1.0, 0.0, 0.0)))).isPublishable)
    }

    @Test
    fun `a fix worth less than the offset it produced is flagged`() {
        // The honest question about a captured offset. A 3.4 m fix that produced a 0.6 m offset has
        // measured its own noise; the same fix producing a 12 m offset has measured geometry.
        val noise = mount(Vec3M(0.6, 0.0, 0.0), CaptureMethod.GNSS_AVERAGE, accuracyM = 3.4)
        val real = mount(Vec3M(12.0, 0.0, 0.0), CaptureMethod.GNSS_AVERAGE, accuracyM = 3.4)
        assertTrue(noise.accuracyExceedsOffset)
        assertFalse(real.accuracyExceedsOffset)
    }

    @Test
    fun `a typed offset is never flagged, however small`() {
        // A tape measure has no fix accuracy, and 6 cm typed in is a measurement rather than a guess.
        assertFalse(mount(Vec3M(0.06, 0.0, 0.0), CaptureMethod.MANUAL, accuracyM = null).accuracyExceedsOffset)
    }

    @Test
    fun `a heading typed before any capture is a zero with no position`() {
        // The forward axis is useful on its own, so it is kept — but it must not read as a surveyed
        // position, or the screen draws the platform at 0N 0E and an offset capture measures the distance
        // to the Gulf of Guinea.
        val headingOnly = PlatformZero(
            latitude = 0.0,
            longitude = 0.0,
            altitudeM = null,
            accuracyM = null,
            scatterM = null,
            headingDeg = 35.0,
            headingSource = HeadingSource.MANUAL,
            capture = CaptureMethod.MANUAL,
            samples = 0,
            capturedAtEpochMillis = 0L,
        )
        assertFalse(headingOnly.hasPosition)
        assertTrue(headingOnly.copy(latitude = 57.7089, longitude = 11.9746).hasPosition)
    }

    private fun mount(
        translation: Vec3M,
        capture: CaptureMethod = CaptureMethod.MANUAL,
        accuracyM: Double? = null,
    ) = SensorMount(
        label = "Sensor",
        frameId = "platform-frame-sensor",
        sensorType = SensorType.OTHER,
        translation = translation,
        capture = capture,
        accuracyM = accuracyM,
    )
}

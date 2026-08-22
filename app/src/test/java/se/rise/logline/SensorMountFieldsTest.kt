package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import se.rise.logline.calibrate.CaptureMethod
import se.rise.logline.ui.fmt
import se.rise.logline.calibrate.EulerDeg
import se.rise.logline.calibrate.PlatformCalibration
import se.rise.logline.calibrate.SensorMount
import se.rise.logline.calibrate.SensorType
import se.rise.logline.calibrate.Vec3M
import se.rise.logline.calibrate.parsePlatformGeometry
import se.rise.logline.calibrate.toStoredJson
import java.util.Locale

/**
 * The sensor editor's numbers, and the provenance a measured rotation carries.
 *
 * The locale half of this pins a bug that shipped: a captured offset was written into the text fields
 * with `String.format`, which follows `Locale.getDefault()`. It exercises the shipped `.fmt()`
 * rather than a copy, which is the only version of this test worth having.
 */
class SensorMountFieldsTest {

    private fun <T> inSwedish(block: () -> T): T {
        val original = Locale.getDefault()
        return try {
            Locale.setDefault(Locale.forLanguageTag("sv-SE"))
            block()
        } finally {
            Locale.setDefault(original)
        }
    }

    /**
     * **A captured offset must survive the trip into a text field and back out.**
     *
     * `"%.3f".format(0.22)` on a Swedish phone is `0,220`, which `toDoubleOrNull()` rejects — so the
     * field turned red and **saving stored `0.0`**, putting the sensor exactly on the platform's
     * origin. That is the one wrong answer nothing downstream can detect, because a sensor genuinely
     * at the origin looks identical. `.fmt()` pins `Locale.ROOT`.
     */
    @Test
    fun `a captured offset round-trips through the field on a Swedish phone`() {
        inSwedish {
            val captured = Vec3M(x = 0.22, y = -1.5, z = -3.25)

            val fields = listOf(
                "%.3f".fmt(captured.x),
                "%.3f".fmt(captured.y),
                "%.3f".fmt(captured.z),
            )

            assertEquals(listOf("0.220", "-1.500", "-3.250"), fields)
            // The half that actually bit: the field's own parse, which is what Save reads.
            assertEquals(
                listOf(0.22, -1.5, -3.25),
                fields.map { it.toDoubleOrNull() },
            )
        }
    }

    /** And the rotation fields the measurement fills, which would have inherited the same bug. */
    @Test
    fun `a measured rotation round-trips through the fields on a Swedish phone`() {
        inSwedish {
            val fields = listOf(-179.4, 2.0, 0.5).map { "%.1f".fmt(it) }

            assertEquals(listOf("-179.4", "2.0", "0.5"), fields)
            assertEquals(listOf(-179.4, 2.0, 0.5), fields.map { it.toDoubleOrNull() })
        }
    }

    /** The failure the above prevents, stated so nobody restores it thinking it is equivalent. */
    @Test
    fun `the platform default formatter is what broke it`() {
        inSwedish {
            assertEquals("0,220", String.format(Locale.getDefault(), "%.3f", 0.22))
            assertNull("which is why it stored zero", "0,220".toDoubleOrNull())
        }
    }

    // ---- provenance ----

    private fun platformWith(mount: SensorMount) = PlatformCalibration(
        name = "SSRS18",
        entityId = "ssrs18",
        parentFrameId = "ssrs18-frame-ccrp",
        sensors = listOf(mount),
    )

    private val typed = SensorMount(
        label = "Ouster",
        frameId = "ssrs18-frame-lidar",
        sensorType = SensorType.LIDAR,
        translation = Vec3M(0.22, 0.0, -0.35),
        rotation = EulerDeg(yaw = 90.0, pitch = 0.0, roll = 0.0),
    )

    /**
     * A measured rotation says so, and says what the compass was worth — which is the only part of the
     * reading a steel mast can ruin.
     */
    @Test
    fun `a measured rotation carries its provenance through the document`() {
        val measured = typed.copy(
            rotationCapture = CaptureMethod.PHONE_ATTITUDE,
            rotationAccuracyDeg = 4.5,
        )

        val json = platformWith(measured).toStoredJson()
        assertEquals(true, json.contains("\"rotation_capture\": \"phone_attitude\""))
        assertEquals(true, json.contains("\"rotation_accuracy_deg\": 4.5"))

        val back = parsePlatformGeometry(json)!!.sensors.single()
        assertEquals(CaptureMethod.PHONE_ATTITUDE, back.rotationCapture)
        assertEquals(4.5, back.rotationAccuracyDeg)
        assertEquals(measured.rotation, back.rotation)
    }

    /**
     * **A typed rotation writes nothing at all.** `"rotation_capture": "manual"` on every sensor ever
     * surveyed is a field that is always the same, and its absence already means exactly that — which
     * is also what makes every document written before this parse unchanged.
     */
    @Test
    fun `a typed rotation writes no provenance and reads back as typed`() {
        val json = platformWith(typed).toStoredJson()

        assertEquals(false, json.contains("rotation_capture"))
        assertEquals(false, json.contains("rotation_accuracy_deg"))

        val back = parsePlatformGeometry(json)!!.sensors.single()
        assertEquals(CaptureMethod.MANUAL, back.rotationCapture)
        assertNull(back.rotationAccuracyDeg)
    }

    /** A device that reports no compass estimate gets absence, not a confident zero. */
    @Test
    fun `a measured rotation with no compass estimate omits the accuracy`() {
        val json = platformWith(
            typed.copy(rotationCapture = CaptureMethod.PHONE_ATTITUDE, rotationAccuracyDeg = null),
        ).toStoredJson()

        assertEquals(true, json.contains("\"rotation_capture\": \"phone_attitude\""))
        assertEquals(false, json.contains("rotation_accuracy_deg"))

        val back = parsePlatformGeometry(json)!!
        assertNotNull(back)
        assertEquals(CaptureMethod.PHONE_ATTITUDE, back.sensors.single().rotationCapture)
        assertNull(back.sensors.single().rotationAccuracyDeg)
    }

    /** The two captures are independent: the commonest survey is a walked position and a typed angle. */
    @Test
    fun `the offset's provenance and the rotation's are kept apart`() {
        val mixed = typed.copy(
            capture = CaptureMethod.GNSS_AVERAGE,
            accuracyM = 3.2,
            capturedAtEpochMillis = 1_700_000_000_000L,
        )

        val back = parsePlatformGeometry(platformWith(mixed).toStoredJson())!!.sensors.single()

        assertEquals(CaptureMethod.GNSS_AVERAGE, back.capture)
        assertEquals(3.2, back.accuracyM)
        assertEquals("the rotation was still typed", CaptureMethod.MANUAL, back.rotationCapture)
        assertNull(back.rotationAccuracyDeg)
    }
}

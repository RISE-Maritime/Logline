package se.rise.logline

import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.record.subjectSchemaNames
import se.rise.logline.keelson.Subjects
import se.rise.logline.ui.SensorFrame
import se.rise.logline.ui.deviceFrameSubjects
import se.rise.logline.ui.frameOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which frame a subject is measured in decides how its numbers are read at all — an x/y/z triple in
 * device axes and the same triple in world axes describe different motions. The classification is
 * derived from the registry rather than listed, and these tests are what keep the derivation honest.
 */
class AxisReferenceTest {

    @Test
    fun `the three vector subjects are in the phone's own axes`() {
        assertEquals(
            listOf(
                Subjects.LINEAR_ACCELERATION_MPSS,
                Subjects.ANGULAR_VELOCITY_RADPS,
                Subjects.MAGNETIC_FIELD_GAUSS,
            ).sorted(),
            deviceFrameSubjects().sorted(),
        )
    }

    /**
     * The rotation vector is the exception: `getQuaternionFromVector` yields the rotation *from* the
     * device frame *to* the world, so calling it a device-axis reading would be exactly backwards.
     */
    @Test
    fun `the quaternion is world-referenced, not device-referenced`() {
        assertEquals(SensorFrame.WorldEnu, frameOf(PublishedSubject.ORIENTATION))
    }

    /**
     * A compass reading is an angle from north, not a component along a device axis — and the accuracy
     * beside it is an uncertainty with no direction at all. "Comes off the IMU" stopped being the same
     * question as "is in device axes" when these arrived.
     */
    @Test
    fun `the headings are bearings, and the accuracy has no frame`() {
        assertEquals(SensorFrame.Bearing, frameOf(PublishedSubject.HEADING_MAGNETIC))
        assertEquals(SensorFrame.Bearing, frameOf(PublishedSubject.HEADING_TRUE_NORTH))
        assertEquals(SensorFrame.None, frameOf(PublishedSubject.HEADING_ACCURACY))
        assertEquals(SensorFrame.None, frameOf(PublishedSubject.MAGNETIC_VARIATION))
    }

    /** Every vector subject must be classified, or the axis card would quietly omit it. */
    @Test
    fun `every vector subject is in device axes`() {
        PublishedSubject.entries
            .filter { subjectSchemaNames[it.subject] == "keelson.Decomposed3DVector" }
            .forEach {
                assertEquals("${'$'}it carries an x, y, z", SensorFrame.DeviceXyz, frameOf(it))
            }
    }

    /** A scalar, a fix and a bearing have no device axes, and must not claim any. */
    @Test
    fun `nothing else claims device axes`() {
        listOf(
            PublishedSubject.AIR_PRESSURE,
            PublishedSubject.LOCATION_FIX,
            PublishedSubject.COURSE_OVER_GROUND,
            PublishedSubject.BATTERY_VOLTAGE,
            PublishedSubject.CELLULAR_RSRP,
        ).forEach {
            assertEquals("$it should be direction-free", SensorFrame.None, frameOf(it))
        }
    }
}

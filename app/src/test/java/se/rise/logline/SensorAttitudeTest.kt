package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.calibrate.EulerDeg
import se.rise.logline.calibrate.Quat
import se.rise.logline.calibrate.angleBetweenDegrees
import se.rise.logline.calibrate.averageRotation
import se.rise.logline.calibrate.eulerFromMatrix
import se.rise.logline.calibrate.matrixOf
import se.rise.logline.calibrate.quaternionFromYawPitchRollDegrees
import se.rise.logline.calibrate.sensorAttitudeFrom
import se.rise.logline.calibrate.toQuaternion
import kotlin.math.abs

/**
 * Measuring a sensor's rotation by laying the phone against it.
 *
 * Three frames compose here — device, world ENU, platform — and each one is a place two axes can be
 * swapped and still produce a number that looks entirely reasonable on screen. So these tests assert
 * **meanings** rather than matrix entries: "the phone's top edge pointing north, on a platform facing
 * east, is a sensor 90° to port". A test written against the matrix would agree with a mirror image.
 */
class SensorAttitudeTest {

    private fun assertAngle(expected: Double, actual: Double, what: String, tolerance: Double = 1e-6) {
        // Compared the short way round the circle: -180 and 180 are the same bearing and would
        // otherwise fail by 360.
        val difference = abs(((expected - actual + 540.0) % 360.0) - 180.0)
        assertTrue("$what: expected $expected but was $actual", difference < tolerance)
    }

    /**
     * The phone flat with its top edge along the world's north, on a platform whose bow also points
     * north: the sensor is aligned with the platform and every angle is zero.
     */
    private val topEdgeNorth = Quat(0.0, 0.0, 0.0, 1.0)

    /** Flat, screen up, rotated `deg` **clockwise seen from above** — the way a compass bearing runs. */
    private fun flatHeading(deg: Double): Quat {
        // A clockwise turn seen from above is negative about ENU's +Z, which points up.
        val half = Math.toRadians(-deg) / 2.0
        return Quat(0.0, 0.0, kotlin.math.sin(half), kotlin.math.cos(half))
    }

    // ---- the posture, and what it means ----

    @Test
    fun `a phone aligned with the bow reads no rotation at all`() {
        val reading = sensorAttitudeFrom(listOf(topEdgeNorth), platformHeadingDeg = 0.0)

        assertNotNull(reading)
        assertAngle(0.0, reading!!.rotation.yaw, "yaw")
        assertAngle(0.0, reading.rotation.pitch, "pitch")
        assertAngle(0.0, reading.rotation.roll, "roll")
        assertFalse(reading.gimbalLocked)
    }

    /**
     * The hand-worked case. The platform faces **east**; the phone's top edge points **north**, which
     * is ninety degrees to *port* of the bow. Yaw is positive to starboard, so this must read -90°.
     *
     * Get the device-to-sensor mapping mirrored and this comes out +90 — a number every bit as
     * plausible, and every sensor on the boat ends up on the wrong side.
     */
    @Test
    fun `a sensor ninety degrees to port of the bow reads minus ninety`() {
        val reading = sensorAttitudeFrom(listOf(topEdgeNorth), platformHeadingDeg = 90.0)!!

        assertAngle(-90.0, reading.rotation.yaw, "yaw")
        assertAngle(0.0, reading.rotation.pitch, "pitch")
        assertAngle(0.0, reading.rotation.roll, "roll")
    }

    /** And the other way: the phone turned to starboard of a north-facing bow is a positive yaw. */
    @Test
    fun `a sensor turned to starboard reads a positive yaw`() {
        val reading = sensorAttitudeFrom(listOf(flatHeading(90.0)), platformHeadingDeg = 0.0)!!

        assertAngle(90.0, reading.rotation.yaw, "yaw")
        assertAngle(0.0, reading.rotation.pitch, "pitch")
        assertAngle(0.0, reading.rotation.roll, "roll")
    }

    /**
     * Yaw is measured from the *platform's* bow, not from north, so turning the boat under a sensor
     * that has not moved changes nothing about the sensor.
     */
    @Test
    fun `the platform's heading is subtracted, not added`() {
        val phone = flatHeading(30.0)

        val onNorthbound = sensorAttitudeFrom(listOf(phone), platformHeadingDeg = 0.0)!!
        val onEastbound = sensorAttitudeFrom(listOf(phone), platformHeadingDeg = 90.0)!!

        assertAngle(30.0, onNorthbound.rotation.yaw, "yaw aboard a northbound platform")
        assertAngle(-60.0, onEastbound.rotation.yaw, "yaw aboard an eastbound platform")
    }

    /**
     * **The test that catches two axes swapped.** Standing the phone on its long edge and tipping it
     * nose-up are different motions, and a mapping with X and Y exchanged reports each as the other —
     * both perfectly plausible until somebody looks at the boat.
     */
    @Test
    fun `tipping the phone moves pitch, and rolling it moves roll`() {
        // Nose-up: the top edge lifts, which is a rotation about the device's +X, i.e. about east.
        val noseUp = aboutAxisDegrees(1.0, 0.0, 0.0, 20.0)
        val pitched = sensorAttitudeFrom(listOf(noseUp), platformHeadingDeg = 0.0)!!
        assertAngle(20.0, pitched.rotation.pitch, "pitch after tipping the top edge up")
        assertAngle(0.0, pitched.rotation.roll, "roll must not move")
        assertAngle(0.0, pitched.rotation.yaw, "yaw must not move")

        // Right edge down: a rotation about the device's +Y, i.e. about north, which turns the up
        // axis toward the east one — so a *positive* angle drops the right edge, and starboard down
        // is a positive roll.
        val rightDown = aboutAxisDegrees(0.0, 1.0, 0.0, 20.0)
        val rolled = sensorAttitudeFrom(listOf(rightDown), platformHeadingDeg = 0.0)!!
        assertAngle(20.0, rolled.rotation.roll, "roll after dropping the right edge")
        assertAngle(0.0, rolled.rotation.pitch, "pitch must not move")
        assertAngle(0.0, rolled.rotation.yaw, "yaw must not move")
    }

    /** A rotation of `deg` about a world axis, as a device-to-world quaternion. */
    private fun aboutAxisDegrees(x: Double, y: Double, z: Double, deg: Double): Quat {
        val half = Math.toRadians(deg) / 2.0
        val s = kotlin.math.sin(half)
        return Quat(x * s, y * s, z * s, kotlin.math.cos(half))
    }

    // ---- the decomposition ----

    /**
     * **The decomposition must be the exact inverse of [quaternionFromYawPitchRollDegrees]**, or a
     * captured rotation and a typed one meaning the same thing would put different transforms on the
     * wire — and only one of them would match what the operator sees on screen.
     */
    @Test
    fun `every rotation survives the trip out to a matrix and back`() {
        val angles = listOf(-179.0, -120.0, -45.0, -0.5, 0.0, 17.0, 90.0, 133.0, 180.0)
        val pitches = listOf(-89.0, -45.0, -1.0, 0.0, 30.0, 89.0)
        angles.forEach { yaw ->
            pitches.forEach { pitch ->
                angles.forEach { roll ->
                    val original = EulerDeg(yaw = yaw, pitch = pitch, roll = roll)
                    val (recovered, locked) = eulerFromMatrix(matrixOf(original.toQuaternion()))
                    assertFalse("$original should not lock", locked)
                    // Compared as rotations rather than as triples: the same orientation has more than
                    // one Euler spelling, and ±180 is the same angle twice.
                    val difference = angleBetweenDegrees(
                        original.toQuaternion(),
                        recovered.toQuaternion(),
                    )
                    assertTrue("$original came back as $recovered", difference < 1e-6)
                }
            }
        }
    }

    /**
     * **Straight down is an echo sounder, not a corner case.** At `pitch = ±90°` yaw and roll turn
     * about the same axis and only their sum is real, so roll is fixed at zero and the whole turn
     * attributed to yaw. What must hold is that the *orientation* is still right.
     */
    @Test
    fun `a sensor pointing straight down still comes back as the rotation it was`() {
        listOf(-90.0, 90.0).forEach { pitch ->
            listOf(0.0, 35.0, -125.0).forEach { yaw ->
                val original = EulerDeg(yaw = yaw, pitch = pitch, roll = 0.0)
                val (recovered, locked) = eulerFromMatrix(matrixOf(original.toQuaternion()))

                assertTrue("pitch $pitch should lock", locked)
                assertEquals("roll is not separable here", 0.0, recovered.roll, 1e-9)
                assertTrue(
                    "$original came back as $recovered",
                    angleBetweenDegrees(original.toQuaternion(), recovered.toQuaternion()) < 1e-6,
                )
            }
        }
    }

    // ---- magnetic against true ----

    /**
     * **Android's rotation vector is referenced to magnetic north; a platform's heading is recorded as
     * true.** Subtracting one from the other without correcting leaves every yaw wrong by the local
     * declination — and it is a signed quantity, so it is wrong in opposite directions either side of
     * the agonic line rather than merely imprecise.
     *
     * Found on the phone rather than reasoned about: at Onsala the app's own true heading read 327°
     * while an uncorrected capture of the same instant read 321.2°, which is that declination.
     */
    @Test
    fun `the declination is what makes a measured yaw true rather than magnetic`() {
        val phone = flatHeading(321.2)

        val uncorrected = sensorAttitudeFrom(listOf(phone), platformHeadingDeg = 0.0)!!
        val corrected = sensorAttitudeFrom(listOf(phone), platformHeadingDeg = 0.0, declinationDeg = 5.8)!!

        assertAngle(-38.8, uncorrected.rotation.yaw, "the magnetic reading", tolerance = 1e-3)
        assertAngle(-33.0, corrected.rotation.yaw, "corrected to true", tolerance = 1e-3)
    }

    /** East and west declination push opposite ways — an `abs` here would pass in Sweden and fail in Alaska. */
    @Test
    fun `declination is signed`() {
        val phone = flatHeading(0.0)

        val east = sensorAttitudeFrom(listOf(phone), 0.0, declinationDeg = 10.0)!!
        val west = sensorAttitudeFrom(listOf(phone), 0.0, declinationDeg = -10.0)!!

        assertAngle(10.0, east.rotation.yaw, "east declination")
        assertAngle(-10.0, west.rotation.yaw, "west declination")
    }

    /** Correcting the heading and turning the attitude are the same rotation about the same axis. */
    @Test
    fun `a declination only moves yaw`() {
        val tilted = sensorAttitudeFrom(
            listOf(aboutAxisDegrees(1.0, 0.0, 0.0, 15.0)),
            platformHeadingDeg = 0.0,
            declinationDeg = 7.0,
        )!!

        assertAngle(15.0, tilted.rotation.pitch, "pitch is gravity's, not the compass's")
        assertAngle(0.0, tilted.rotation.roll, "roll likewise")
        assertAngle(7.0, tilted.rotation.yaw, "only yaw moves")
    }

    /**
     * **Absent declination is reported, never absorbed.** A platform measured with a tape has a heading
     * and no position, so there is nothing to compute one from — and a reading that quietly used zero
     * would be wrong by six degrees here and twenty further north, with nothing on screen saying so.
     */
    @Test
    fun `no declination leaves the reading magnetic and says so`() {
        val without = sensorAttitudeFrom(listOf(topEdgeNorth), platformHeadingDeg = 0.0)!!
        val with = sensorAttitudeFrom(listOf(topEdgeNorth), 0.0, declinationDeg = 5.8)!!

        assertNull("magnetic, and the screen has to say it", without.declinationDegrees)
        assertEquals(5.8, with.declinationDegrees)
    }

    // ---- averaging ----

    /**
     * **Rotations average as quaternions, never as angles.** Two readings a degree either side of the
     * ±180° seam mean almost the same thing; averaged as numbers they come out pointing the opposite
     * way, which is the failure `circularMeanDegrees` exists for, here with three angles to get wrong
     * at once.
     */
    @Test
    fun `a mean across the wrap is the rotation between them, not the one opposite`() {
        val samples = listOf(
            EulerDeg(yaw = 179.0, pitch = 0.0, roll = 0.0).toQuaternion(),
            EulerDeg(yaw = -179.0, pitch = 0.0, roll = 0.0).toQuaternion(),
        )

        val mean = averageRotation(samples)!!
        val (angles, _) = eulerFromMatrix(matrixOf(mean))

        assertAngle(180.0, angles.yaw, "the mean of 179 and -179", tolerance = 1e-6)
    }

    /**
     * `q` and `-q` are the same rotation, and a sum that does not flip them into one hemisphere first
     * cancels two identical readings to nothing.
     */
    @Test
    fun `a sample and its negation are the same rotation, not opposites`() {
        val q = EulerDeg(yaw = 40.0, pitch = 10.0, roll = -5.0).toQuaternion()
        val negated = Quat(-q.x, -q.y, -q.z, -q.w)

        val mean = averageRotation(listOf(q, negated))

        assertNotNull("they must not cancel", mean)
        assertTrue(angleBetweenDegrees(q, mean!!) < 1e-6)
    }

    @Test
    fun `no samples is no reading rather than a confident zero`() {
        assertNull(averageRotation(emptyList()))
        assertNull(sensorAttitudeFrom(emptyList(), platformHeadingDeg = 0.0))
    }

    /** The spread is how still the phone was held — nothing to do with whether the answer is right. */
    @Test
    fun `the spread reports the furthest sample from the mean`() {
        val samples = listOf(
            EulerDeg(yaw = -2.0, pitch = 0.0, roll = 0.0).toQuaternion(),
            EulerDeg(yaw = 0.0, pitch = 0.0, roll = 0.0).toQuaternion(),
            EulerDeg(yaw = 2.0, pitch = 0.0, roll = 0.0).toQuaternion(),
        )

        val reading = sensorAttitudeFrom(samples, platformHeadingDeg = 0.0)!!

        assertEquals(3, reading.samples)
        assertEquals("two degrees either side of the middle", 2.0, reading.spreadDegrees, 1e-3)
        assertEquals("a phone held still", 0.0, sensorAttitudeFrom(
            listOf(topEdgeNorth), platformHeadingDeg = 0.0,
        )!!.spreadDegrees, 1e-9)
    }

    /** Absent stays absent: a device that reports no compass estimate must not get a confident zero. */
    @Test
    fun `the compass accuracy is carried through, including its absence`() {
        assertEquals(
            25.0,
            sensorAttitudeFrom(listOf(topEdgeNorth), 0.0, compassAccuracyDegrees = 25.0)!!
                .compassAccuracyDegrees,
        )
        assertNull(sensorAttitudeFrom(listOf(topEdgeNorth), 0.0)!!.compassAccuracyDegrees)
    }
}

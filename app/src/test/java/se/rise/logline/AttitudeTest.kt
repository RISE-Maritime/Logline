package se.rise.logline

import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.Subjects
import se.rise.logline.sensors.attitudeRatesOf
import se.rise.logline.ui.SensorFrame
import se.rise.logline.ui.frameOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TOLERANCE = 1e-3f

/**
 * Which gyro axis is called what, which is the one thing here that can be wrong and look right.
 *
 * A phone rolling at 12°/s and a phone pitching at 12°/s produce the same number under a swapped
 * name, and nothing downstream could tell — not the units, not the magnitude, not the plot. Only a
 * person who knew how the phone was lying would ever notice.
 */
class AttitudeTest {

    /** Android's own mapping, the same one `getOrientation` uses for the angles. */
    @Test
    fun `pitch turns about X, roll about Y, yaw about Z`() {
        val aboutX = attitudeRatesOf(gyroX = 1f, gyroY = 0f, gyroZ = 0f)
        assertTrue("X is pitch", aboutX.pitch != 0f && aboutX.roll == 0f && aboutX.yaw == 0f)

        val aboutY = attitudeRatesOf(gyroX = 0f, gyroY = 1f, gyroZ = 0f)
        assertTrue("Y is roll", aboutY.roll != 0f && aboutY.pitch == 0f && aboutY.yaw == 0f)

        val aboutZ = attitudeRatesOf(gyroX = 0f, gyroY = 0f, gyroZ = 1f)
        assertTrue("Z is yaw", aboutZ.yaw != 0f && aboutZ.roll == 0f && aboutZ.pitch == 0f)
    }

    /**
     * Against the constant rather than against the conversion helper: π radians per second is half a
     * turn a second, which is 180°/s whatever the code does.
     */
    @Test
    fun `radians per second become degrees per second`() {
        val halfTurn = attitudeRatesOf(gyroX = Math.PI.toFloat(), gyroY = 0f, gyroZ = 0f)

        assertEquals(180f, halfTurn.pitch, TOLERANCE)
    }

    /** A rate has a direction, so the sign has to survive the naming. */
    @Test
    fun `the sign is carried through`() {
        val rates = attitudeRatesOf(gyroX = -1f, gyroY = 2f, gyroZ = -3f)

        assertTrue(rates.pitch < 0f)
        assertTrue(rates.roll > 0f)
        assertTrue(rates.yaw < 0f)
    }

    /** A phone sitting still is three zeroes, not three nulls or three NaNs. */
    @Test
    fun `a still phone reads zero`() {
        val rates = attitudeRatesOf(0f, 0f, 0f)

        assertEquals(0f, rates.roll, TOLERANCE)
        assertEquals(0f, rates.pitch, TOLERANCE)
        assertEquals(0f, rates.yaw, TOLERANCE)
    }

    /**
     * These describe the *phone*, and the screen has to say so.
     *
     * Roll and pitch are the readings most likely to be taken for a vessel's — the whole point of the
     * frame note on the subject's own screen — so falling through to `None`, which means "no device
     * axes involved", would be the wrong kind of silence.
     */
    @Test
    fun `every attitude subject declares the frame it is measured in`() {
        val attitude = listOf(
            Subjects.ROLL_DEG,
            Subjects.PITCH_DEG,
            Subjects.YAW_DEG,
            Subjects.ROLL_RATE_DEGPS,
            Subjects.PITCH_RATE_DEGPS,
            Subjects.YAW_RATE_DEGPS,
        )

        attitude.forEach { subject ->
            val entry = PublishedSubject.forSubject(subject)!!
            assertEquals("$subject should name its axis", SensorFrame.DeviceAngle, frameOf(entry))
        }
    }
}

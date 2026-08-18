package se.rise.logline

import se.rise.logline.calibrate.EulerDeg
import se.rise.logline.calibrate.Vec3M
import se.rise.logline.calibrate.normaliseSignedDegrees
import se.rise.logline.calibrate.quaternionFromYawPitchRollDegrees
import se.rise.logline.calibrate.rotate
import se.rise.logline.calibrate.toQuaternion
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

/**
 * The quaternion convention, pinned.
 *
 * The numbers below come from `squaternion.Quaternion.from_euler(roll, pitch, yaw, degrees=True)` —
 * what `keelson/connectors/platform/bin/platform-geometry2keelson.py` uses — because the risk here is
 * not arithmetic but *convention*: a quaternion built in a different Euler order is a perfectly valid
 * rotation that puts the sensor somewhere else, and nothing downstream can tell that from a mounting
 * error. The last two tests assert meaning rather than numbers, which is what makes a sign error fail.
 */
class RotationsTest {

    private val half = sqrt(0.5)

    @Test
    fun `no rotation is the identity quaternion`() {
        val q = quaternionFromYawPitchRollDegrees(0.0, 0.0, 0.0)
        assertEquals(0.0, q.x, 1e-12)
        assertEquals(0.0, q.y, 1e-12)
        assertEquals(0.0, q.z, 1e-12)
        assertEquals(1.0, q.w, 1e-12)
    }

    @Test
    fun `yaw of ninety degrees is a rotation about Z alone`() {
        val q = quaternionFromYawPitchRollDegrees(yaw = 90.0, pitch = 0.0, roll = 0.0)
        assertEquals(0.0, q.x, 1e-12)
        assertEquals(0.0, q.y, 1e-12)
        assertEquals(half, q.z, 1e-12)
        assertEquals(half, q.w, 1e-12)
    }

    @Test
    fun `roll of minus 180 degrees flips about X`() {
        // The Anello IMU in upstream's example-config.json is mounted this way up.
        val q = quaternionFromYawPitchRollDegrees(yaw = 0.0, pitch = 0.0, roll = -180.0)
        assertEquals(-1.0, q.x, 1e-12)
        assertEquals(0.0, q.y, 1e-12)
        assertEquals(0.0, q.z, 1e-12)
        assertEquals(0.0, q.w, 1e-12)
    }

    @Test
    fun `pitch of ninety degrees is a rotation about Y alone`() {
        val q = quaternionFromYawPitchRollDegrees(yaw = 0.0, pitch = 90.0, roll = 0.0)
        assertEquals(0.0, q.x, 1e-12)
        assertEquals(half, q.y, 1e-12)
        assertEquals(0.0, q.z, 1e-12)
        assertEquals(half, q.w, 1e-12)
    }

    @Test
    fun `a combined rotation matches the Z-Y-X composition`() {
        // roll=10, pitch=20, yaw=30. Cross-checked by building R = Rz(yaw)·Ry(pitch)·Rx(roll) as a
        // matrix and converting that to a quaternion — the two agree to twelve digits, which is what
        // says the half-angle form below is the Z-Y-X one and not a lookalike.
        val q = quaternionFromYawPitchRollDegrees(yaw = 30.0, pitch = 20.0, roll = 10.0)
        assertEquals(0.038134576, q.x, 1e-9)
        assertEquals(0.189307857, q.y, 1e-9)
        assertEquals(0.239298338, q.z, 1e-9)
        assertEquals(0.951548525, q.w, 1e-9)
    }

    @Test
    fun `a yaw of ninety degrees takes forward to starboard`() {
        // What the numbers above actually *mean* in this frame: X forward, Y starboard, Z down is
        // right-handed, so a positive yaw swings the nose to starboard.
        val forward = Vec3M(1.0, 0.0, 0.0)
        val rotated = EulerDeg(yaw = 90.0, pitch = 0.0, roll = 0.0).toQuaternion().rotate(forward)
        assertEquals(0.0, rotated.x, 1e-9)
        assertEquals(1.0, rotated.y, 1e-9)
        assertEquals(0.0, rotated.z, 1e-9)
    }

    @Test
    fun `a pitch of ninety degrees takes forward to straight up`() {
        // Z is down, so "up" is negative Z — a nose-up pitch has to produce a negative number here.
        val rotated = EulerDeg(yaw = 0.0, pitch = 90.0, roll = 0.0).toQuaternion().rotate(Vec3M(1.0, 0.0, 0.0))
        assertEquals(0.0, rotated.x, 1e-9)
        assertEquals(0.0, rotated.y, 1e-9)
        assertEquals(-1.0, rotated.z, 1e-9)
    }

    @Test
    fun `angles fold into the range the schema allows`() {
        assertEquals(-90.0, normaliseSignedDegrees(270.0), 1e-12)
        assertEquals(180.0, normaliseSignedDegrees(180.0), 1e-12)
        assertEquals(-179.0, normaliseSignedDegrees(181.0), 1e-12)
        assertEquals(0.0, normaliseSignedDegrees(360.0), 1e-12)
        assertEquals(-45.0, normaliseSignedDegrees(-45.0), 1e-12)
        assertEquals(90.0, normaliseSignedDegrees(-270.0), 1e-12)
    }
}

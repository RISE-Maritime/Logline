package se.rise.logline.calibrate

import kotlin.math.cos
import kotlin.math.sin

/**
 * Euler angles to a quaternion, in the one convention keelson's platform geometry uses.
 *
 * `connectors/platform/config-schema.json` carries rotations as degrees applied **yaw → pitch → roll**,
 * and `platform-geometry2keelson.py` turns them into the `foxglove.Quaternion` on the wire with
 * `squaternion.Quaternion.from_euler(roll, pitch, yaw, degrees=True)` — intrinsic Z-Y-X, i.e.
 * `R = Rz(yaw) · Ry(pitch) · Rx(roll)`.
 *
 * This is a transcription of that, and `RotationsTest` pins it against values from the same formula.
 * The convention is not ours to choose: a quaternion built in any other order would place a sensor
 * somewhere the connector would not, and both would look equally plausible on screen.
 */

/** Yaw, pitch and roll in degrees, in the order they are applied. */
data class EulerDeg(val yaw: Double, val pitch: Double, val roll: Double) {
    companion object {
        val ZERO = EulerDeg(0.0, 0.0, 0.0)
    }
}

/** A rotation, in `foxglove.Quaternion`'s field order. */
data class Quat(val x: Double, val y: Double, val z: Double, val w: Double)

fun quaternionFromYawPitchRollDegrees(yaw: Double, pitch: Double, roll: Double): Quat {
    val hr = Math.toRadians(roll) / 2.0
    val hp = Math.toRadians(pitch) / 2.0
    val hy = Math.toRadians(yaw) / 2.0
    val cr = cos(hr)
    val sr = sin(hr)
    val cp = cos(hp)
    val sp = sin(hp)
    val cy = cos(hy)
    val sy = sin(hy)
    return Quat(
        x = sr * cp * cy - cr * sp * sy,
        y = cr * sp * cy + sr * cp * sy,
        z = cr * cp * sy - sr * sp * cy,
        w = cr * cp * cy + sr * sp * sy,
    )
}

fun EulerDeg.toQuaternion(): Quat = quaternionFromYawPitchRollDegrees(yaw, pitch, roll)

/**
 * Apply a rotation to a point in the rig frame.
 *
 * Only the tests use this — it is what lets them assert the *meaning* of a quaternion ("yaw 90° takes
 * forward to starboard") rather than four numbers that could be wrong together.
 */
fun Quat.rotate(v: Vec3M): Vec3M {
    // v' = v + 2w(q × v) + 2(q × (q × v)), with q the vector part. Cheaper than building a matrix and
    // harder to typo than the expanded form.
    val ux = y * v.z - z * v.y
    val uy = z * v.x - x * v.z
    val uz = x * v.y - y * v.x
    val vx = y * uz - z * uy
    val vy = z * ux - x * uz
    val vz = x * uy - y * ux
    return Vec3M(
        x = v.x + 2.0 * (w * ux + vx),
        y = v.y + 2.0 * (w * uy + vy),
        z = v.z + 2.0 * (w * uz + vz),
    )
}

/**
 * Fold an angle into `[-180, 180]`, which is the range `config-schema.json` allows.
 *
 * A typed 270° is a legal thing for someone to mean and an illegal thing to publish; rejecting it
 * would be pedantry, so it becomes -90°, which is the same rotation.
 */
fun normaliseSignedDegrees(deg: Double): Double {
    val wrapped = ((deg % 360.0) + 360.0) % 360.0
    return if (wrapped > 180.0) wrapped - 360.0 else wrapped
}

package se.rise.logline.calibrate

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turning the phone's own attitude into a sensor's rotation in the platform frame.
 *
 * The angular counterpart to [bodyOffsetMetres] in `Geodesy.kt`: that rotates an ENU *offset* into the
 * platform's body frame by the platform's heading, and this does the same job for an *orientation*.
 *
 * Deliberately pure — it takes the quaternions `ImuProvider` already produces rather than reaching for
 * `SensorManager` — because the whole feature is three frames composed, and every one of them is a
 * place two axes can be swapped and still produce a plausible number. Self-consistency is all a test
 * can prove, so `SensorAttitudeTest` asserts *meanings* ("top edge north on an east-facing platform
 * reads 90° to port") rather than matrix entries, and the device checks in the README do the rest.
 *
 * **Pitch and roll are measured against gravity, so the platform must be level.** Heel and trim at the
 * moment of measurement go straight into the number. That is an instruction for the operator, not a
 * caveat to bury: nothing here can detect it, because a heeled boat and a tilted sensor are the same
 * reading.
 */

/**
 * How the phone is held against the sensor: **flat, screen up, top edge pointing the way the sensor
 * faces**. The same posture [HeadingSource.COMPASS] documents, so there is one phone-holding
 * instruction in this app rather than two that could drift apart.
 *
 * Written as the sensor's axes in *device* coordinates, one per column:
 *
 * * sensor forward `+X` is the device's `+Y`, the top edge
 * * sensor starboard `+Y` is the device's `+X`, the right edge
 * * sensor down `+Z` is the device's `-Z`, into the screen
 *
 * Two axes swapped and one negated, which is a **rotation** — swap two and negate none and it is a
 * mirror image, which reads as a perfectly plausible rotation and puts every sensor's port side to
 * starboard. `SensorAttitudeTest` asserts the determinant for exactly that reason.
 */
private val DEVICE_FROM_SENSOR = doubleArrayOf(
    0.0, 1.0, 0.0,
    1.0, 0.0, 0.0,
    0.0, 0.0, -1.0,
)

/** Below this, `cos(pitch)` is small enough that yaw and roll stop being separable. */
private const val GIMBAL_LOCK_COSINE = 1e-6

/**
 * A rotation measured from the phone, with what it is worth kept beside it.
 *
 * Two different doubts, and merging them would hide the one that matters. [spreadDegrees] is how still
 * the phone was held — the angular counterpart of `AveragedFix.scatterM`, and it says nothing about
 * whether the answer is *correct*. [compassAccuracyDegrees] is the platform's own estimate for the
 * magnetometer, and it is the one to read: yaw is the only angle a steel mast can ruin, because pitch
 * and roll come from gravity.
 */
data class SensorAttitudeReading(
    val rotation: EulerDeg,
    val spreadDegrees: Double,
    /** Null on a device that reports no estimate — absent rather than a confident zero. */
    val compassAccuracyDegrees: Double?,
    val samples: Int,
    /**
     * The declination folded in to make yaw true rather than magnetic, or **null when there was none
     * to fold in** — in which case yaw is magnetic and the screen has to say so.
     *
     * Null happens for a platform whose zero carries a heading and no position, which is a perfectly
     * ordinary tape-measured survey. The error it leaves is the local declination: about 6° in western
     * Sweden, twenty and more at high latitudes, and a signed quantity that no `abs` would rescue.
     */
    val declinationDegrees: Double?,
    /**
     * True when the sensor points within a whisker of straight up or straight down, where yaw and roll
     * describe the same turn and only their sum is real.
     *
     * Not a corner case: an echo sounder points straight down. Roll is reported as zero and the whole
     * rotation put into yaw, which is a choice rather than a measurement, so the screen says so.
     */
    val gimbalLocked: Boolean,
)

/**
 * The sensor's rotation in the platform frame, from a run of device-to-world quaternions.
 *
 * [deviceToWorld] is what `SensorManager.getQuaternionFromVector` produces and `QuatSample` already
 * carries: the rotation taking device coordinates into the world's ENU frame. [platformHeadingDeg] is
 * the direction the platform's `+X` points, true — `PlatformZero.headingDeg`, without which yaw has
 * nothing to be relative to and this must not be called.
 *
 * **[declinationDeg] is not optional decoration.** Android's rotation vector is referenced to
 * *magnetic* north, and a platform's heading is recorded as *true* — subtracting one from the other
 * without correcting leaves every yaw wrong by the local declination. Caught on a Pixel 6 at Onsala,
 * where the app's own true heading read 327° and an uncorrected capture read 321.2°: 5.8° apart,
 * which is the declination there almost exactly. Null when no position was available to compute one,
 * and then yaw is magnetic and [SensorAttitudeReading.declinationDegrees] says so.
 *
 * The correction is applied to the *heading* rather than by turning the measured attitude, because the
 * two are the same rotation about the same vertical axis and one subtraction is harder to get wrong
 * than a fourth frame.
 */
fun sensorAttitudeFrom(
    deviceToWorld: List<Quat>,
    platformHeadingDeg: Double,
    declinationDeg: Double? = null,
    compassAccuracyDegrees: Double? = null,
): SensorAttitudeReading? {
    val mean = averageRotation(deviceToWorld) ?: return null
    // The platform's bow expressed the way the magnetometer sees the world, so both sides of the
    // subtraction are in one frame.
    val magneticPlatformHeading = platformHeadingDeg - (declinationDeg ?: 0.0)
    val platformFromSensor = multiply(
        platformFromWorld(magneticPlatformHeading),
        multiply(matrixOf(mean), DEVICE_FROM_SENSOR),
    )
    val decomposed = eulerFromMatrix(platformFromSensor)
    return SensorAttitudeReading(
        rotation = decomposed.first,
        spreadDegrees = spreadDegrees(deviceToWorld, mean),
        compassAccuracyDegrees = compassAccuracyDegrees,
        declinationDegrees = declinationDeg,
        samples = deviceToWorld.size,
        gimbalLocked = decomposed.second,
    )
}

/**
 * The mean of a set of rotations.
 *
 * **Averaged as quaternions, never as angles.** A mean of Euler triples is wrong across ±180° the same
 * way a mean of headings is wrong at north — the failure `circularMeanDegrees` exists for — and here
 * there are three angles to get it wrong in at once.
 *
 * `q` and `-q` are the same rotation, so every sample is flipped to the hemisphere of the first before
 * summing; without that, two identical readings either side of the sign can cancel to nothing. The
 * linear sum is an approximation to the true Fréchet mean and an excellent one for a phone held still
 * for four seconds, which is the only thing this is ever given.
 */
internal fun averageRotation(samples: List<Quat>): Quat? {
    if (samples.isEmpty()) return null
    val first = samples.first()
    var x = 0.0
    var y = 0.0
    var z = 0.0
    var w = 0.0
    samples.forEach { q ->
        val sign = if (dot(q, first) < 0.0) -1.0 else 1.0
        x += sign * q.x
        y += sign * q.y
        z += sign * q.z
        w += sign * q.w
    }
    val length = sqrt(x * x + y * y + z * z + w * w)
    if (length < 1e-9) return null
    return Quat(x / length, y / length, z / length, w / length)
}

/** The furthest any sample sits from the mean, in degrees — how still the phone was held. */
private fun spreadDegrees(samples: List<Quat>, mean: Quat): Double =
    samples.maxOfOrNull { angleBetweenDegrees(it, mean) } ?: 0.0

/**
 * The angle of the rotation taking one orientation to the other.
 *
 * Computed from the relative rotation `a⁻¹ ⊗ b` as `2·atan2(|vector part|, |scalar|)` rather than the
 * obvious `2·acos(a · b)`. The two agree in exact arithmetic and emphatically not in floating point:
 * `acos` has an infinite derivative at 1, so for two nearly identical rotations — a phone held still,
 * which is the case this is *for* — a rounding error of 1e-16 in the dot product comes back as a
 * spread of a couple of thousandths of a degree. A readout that says `0.002°` for a phone that did not
 * move is a small lie, and it costs nothing to not tell it.
 *
 * `abs` on the scalar because `q` and `-q` are the same rotation, which picks the shorter way round.
 */
internal fun angleBetweenDegrees(a: Quat, b: Quat): Double {
    // a⁻¹ ⊗ b, with a⁻¹ the conjugate since both are unit.
    val w = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
    val x = a.w * b.x - a.x * b.w - a.y * b.z + a.z * b.y
    val y = a.w * b.y + a.x * b.z - a.y * b.w - a.z * b.x
    val z = a.w * b.z - a.x * b.y + a.y * b.x - a.z * b.w
    return Math.toDegrees(2.0 * atan2(sqrt(x * x + y * y + z * z), abs(w)))
}

private fun dot(a: Quat, b: Quat) = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w

/**
 * Platform-from-world: the platform's axes in ENU, one per row.
 *
 * Forward lies along the heading, starboard ninety degrees clockwise of it, and **down** is the third
 * — keelson's frame is X forward, Y starboard, Z down, against ENU's Z up, which is where the sign
 * comes from and the reason this is not simply a rotation about the vertical.
 */
private fun platformFromWorld(headingDeg: Double): DoubleArray {
    val h = Math.toRadians(headingDeg)
    val s = sin(h)
    val c = cos(h)
    return doubleArrayOf(
        s, c, 0.0,
        c, -s, 0.0,
        0.0, 0.0, -1.0,
    )
}

/** A unit quaternion as a 3x3 rotation matrix, row-major. */
internal fun matrixOf(q: Quat): DoubleArray {
    val (x, y, z, w) = q
    return doubleArrayOf(
        1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
        2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
        2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y),
    )
}

private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
    val out = DoubleArray(9)
    for (row in 0..2) {
        for (col in 0..2) {
            var sum = 0.0
            for (k in 0..2) sum += a[row * 3 + k] * b[k * 3 + col]
            out[row * 3 + col] = sum
        }
    }
    return out
}

/**
 * A rotation matrix back to yaw, pitch and roll — **the exact inverse of
 * [quaternionFromYawPitchRollDegrees]**, and it has to be, or a captured rotation and a typed one
 * meaning the same thing would produce different transforms on the wire.
 *
 * That convention is `R = Rz(yaw) · Ry(pitch) · Rx(roll)`, which multiplies out to
 *
 * ```
 * [  cy·cp   cy·sp·sr - sy·cr   cy·sp·cr + sy·sr ]
 * [  sy·cp   sy·sp·sr + cy·cr   sy·sp·cr - cy·sr ]
 * [ -sp      cp·sr              cp·cr            ]
 * ```
 *
 * so pitch falls straight out of `-R20`, and yaw and roll out of the first column and the last row.
 *
 * Returns the angles and whether it hit gimbal lock. At `pitch = ±90°` the terms yaw and roll are read
 * from all vanish, and only their sum or difference survives in the matrix — so roll is fixed at zero
 * and the whole turn attributed to yaw. `atan2(R01, R11)` recovers it in both signs of pitch, which
 * looks like a coincidence and is not: at `+90°` those entries hold `sin(roll - yaw)` and
 * `cos(roll - yaw)`, at `-90°` they hold `-sin(roll + yaw)` and `cos(roll + yaw)`, and with roll held
 * at zero the sign works out the same either way.
 */
internal fun eulerFromMatrix(r: DoubleArray): Pair<EulerDeg, Boolean> {
    val pitch = asin((-r[6]).coerceIn(-1.0, 1.0))
    val cosPitch = cos(pitch)
    if (abs(cosPitch) < GIMBAL_LOCK_COSINE) {
        return EulerDeg(
            yaw = normaliseSignedDegrees(Math.toDegrees(-atan2(r[1], r[4]))),
            pitch = normaliseSignedDegrees(Math.toDegrees(pitch)),
            roll = 0.0,
        ) to true
    }
    return EulerDeg(
        yaw = normaliseSignedDegrees(Math.toDegrees(atan2(r[3], r[0]))),
        pitch = normaliseSignedDegrees(Math.toDegrees(pitch)),
        roll = normaliseSignedDegrees(Math.toDegrees(atan2(r[7], r[8]))),
    ) to false
}

package se.rise.logline.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import se.rise.logline.calibrate.normaliseSignedDegrees
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class Vec3Sample(val x: Float, val y: Float, val z: Float, val elapsedNanos: Long)

/**
 * One rotation-vector event, as both an attitude and a compass reading.
 *
 * The heading is carried here rather than fetched from a second listener because it *is* this event —
 * the same rotation expressed as one angle instead of four components. A separate flow would register
 * the sensor twice and could sample it at a different instant, which would put a heading and a
 * quaternion that disagree on the same bus.
 */
data class QuatSample(
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float,
    /** Degrees clockwise from **magnetic** north, of the phone's +Y axis. */
    val headingMagneticDegrees: Float,
    /**
     * The same attitude as Euler angles, in degrees, in **Android's own convention**.
     *
     * `getOrientation` already computes all three to produce the heading above; two of them used to be
     * discarded. They describe the *phone*, not a vessel — `yaw` is the heading in signed form, `pitch`
     * is rotation about the device's +X axis and `roll` about its +Y, and Android bounds roll to
     * ±90° while the other two run to ±180°. What the phone's attitude means for the boat it is
     * strapped to is the rig calibration's `frame_transform`, not this.
     */
    val yawDegrees: Float,
    val pitchDegrees: Float,
    val rollDegrees: Float,
    /** The platform's 1-sigma estimate in degrees, or null on a device that does not report one. */
    val headingAccuracyDegrees: Float?,
    val elapsedNanos: Long,
)

class ImuProvider(context: Context) {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    fun linearAcceleration(
        rateUs: Int = SensorManager.SENSOR_DELAY_GAME,
        onShed: () -> Unit = {},
    ): Flow<Vec3Sample> =
        vector3Flow(Sensor.TYPE_LINEAR_ACCELERATION, rateUs, onShed)

    fun angularVelocity(
        rateUs: Int = SensorManager.SENSOR_DELAY_GAME,
        onShed: () -> Unit = {},
    ): Flow<Vec3Sample> =
        vector3Flow(Sensor.TYPE_GYROSCOPE, rateUs, onShed)

    /**
     * Calibrated magnetic field, in microtesla — convert before publishing, since the subject is
     * `magnetic_field_gauss`.
     *
     * The calibrated sensor is the right one here: the uncalibrated variant omits the hard-iron
     * estimate, which is what makes a phone's own magnets cancel out.
     */
    fun magneticField(
        rateUs: Int = SensorManager.SENSOR_DELAY_GAME,
        onShed: () -> Unit = {},
    ): Flow<Vec3Sample> =
        vector3Flow(Sensor.TYPE_MAGNETIC_FIELD, rateUs, onShed)

    /**
     * The fused attitude, and with it the compass.
     *
     * `TYPE_ROTATION_VECTOR` is the right source for a heading: it fuses the magnetometer with the
     * gyroscope and accelerometer, so it is steady under motion where a bare magnetometer is not.
     * (`TYPE_GAME_ROTATION_VECTOR` deliberately excludes the magnetometer and therefore has no north at
     * all; `TYPE_ORIENTATION` has been deprecated since API 8.)
     *
     * The azimuth is the direction of the phone's **+Y axis** — its top edge — which is Android's own
     * definition and the one every compass app uses. It degenerates when +Y points at the sky, i.e. a
     * phone held upright, because the top edge then has no compass direction; a phone lying flat or
     * mounted face-up is the case this answers. `orientation_quaternion` carries the full attitude for
     * anyone who needs a different convention.
     */
    fun orientation(
        rateUs: Int = SensorManager.SENSOR_DELAY_GAME,
        onShed: () -> Unit = {},
    ): Flow<QuatSample> = callbackFlow {
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            close(); return@callbackFlow
        }
        val listener = object : SensorEventListener {
            private val q = FloatArray(4)
            private val rotation = FloatArray(9)
            private val orientation = FloatArray(3)
            // The event carries five components on this hardware; the matrix helpers want four. Trimmed
            // explicitly rather than passed whole, because some vendors' implementations have historically
            // read past the fourth element.
            private val vector = FloatArray(4)

            override fun onSensorChanged(event: SensorEvent) {
                val count = minOf(vector.size, event.values.size)
                System.arraycopy(event.values, 0, vector, 0, count)
                SensorManager.getQuaternionFromVector(q, vector)
                SensorManager.getRotationMatrixFromVector(rotation, vector)
                SensorManager.getOrientation(rotation, orientation)
                // Checked, not discarded — see the note in `vector3Flow`.
                if (trySend(
                    QuatSample(
                        x = q[1],
                        y = q[2],
                        z = q[3],
                        w = q[0],
                        headingMagneticDegrees = normaliseHeadingDegrees(radiansToDegrees(orientation[0])),
                        // Signed rather than 0-360: an Euler triple is read as ±180 either side of
                        // straight ahead, and it is the same measurement `headingMagneticDegrees`
                        // carries in compass form.
                        yawDegrees = normaliseSignedDegrees(radiansToDegrees(orientation[0]).toDouble()).toFloat(),
                        pitchDegrees = radiansToDegrees(orientation[1]),
                        rollDegrees = radiansToDegrees(orientation[2]),
                        // values[4] since API 18, but not on every device — absent stays absent rather
                        // than becoming a confident 0°.
                        headingAccuracyDegrees = event.values.getOrNull(4)?.let { radiansToDegrees(it) },
                        elapsedNanos = event.timestamp,
                    )
                ).isFailure) onShed()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        manager.registerListener(listener, sensor, rateUs)
        awaitClose { manager.unregisterListener(listener) }
    }

    private fun vector3Flow(type: Int, rateUs: Int, onShed: () -> Unit): Flow<Vec3Sample> = callbackFlow {
        val sensor = manager.getDefaultSensor(type)
        if (sensor == null) {
            close(); return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // The result is checked rather than discarded: the flow's buffer holds 64 samples, and
                // once a collector falls behind this is where the loss happens — silently, until now.
                if (trySend(
                        Vec3Sample(event.values[0], event.values[1], event.values[2], event.timestamp)
                    ).isFailure
                ) onShed()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        manager.registerListener(listener, sensor, rateUs)
        awaitClose { manager.unregisterListener(listener) }
    }
}

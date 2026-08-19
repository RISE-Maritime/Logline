package se.rise.logline.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** One reading from a single-value sensor, with the raw boot-clock timestamp of the event. */
data class ScalarSample(val value: Float, val elapsedNanos: Long)

/**
 * Single-value `SensorManager` sensors — the barometer today.
 *
 * Same contract as [ImuProvider]: the listener is registered on collection and unregistered in
 * `awaitClose`, which is what stops the sensor from staying on after publishing ends. A device without
 * the sensor closes the flow immediately rather than hanging, so the collector simply completes and the
 * subject reports no samples instead of looking stuck.
 */
class ScalarSensorProvider(context: Context) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    fun pressure(rateUs: Int = SensorManager.SENSOR_DELAY_NORMAL): Flow<ScalarSample> =
        scalarFlow(Sensor.TYPE_PRESSURE, rateUs)

    /**
     * Ambient light in lux, on a steady tick.
     *
     * Takes an interval rather than a rate hint because the sensor is **on-change**: a requested rate
     * is a ceiling it ignores, so `SENSOR_DELAY_NORMAL` here only bounds how quickly a *change* is
     * delivered, and [heldAt] is what produces the actual cadence.
     */
    fun illuminance(intervalMillis: Long): Flow<ScalarSample> =
        scalarFlow(Sensor.TYPE_LIGHT, SensorManager.SENSOR_DELAY_NORMAL).heldAt(intervalMillis)

    /**
     * The IMU's own die temperature, on a device that exposes one.
     *
     * **Found by string type, because Android has no constant for it.** `TYPE_TEMPERATURE` is
     * deprecated and `TYPE_AMBIENT_TEMPERATURE` is a different measurement — the air, not the chip —
     * and a Pixel 6 has *neither*: checked with `dumpsys sensorservice`, which lists no
     * `android.sensor.ambient_temperature` and no `android.sensor.temperature` at all. What it does
     * list is `com.google.sensor.gyro_temperature`, the LSM6DSR's own sensor, which is exactly what
     * `imu_temperature_celsius` means.
     *
     * A vendor string is not a standard, so a device without this one publishes nothing rather than
     * falling back to ambient. Publishing the air temperature under a subject that names the IMU would
     * be the kind of plausible wrong number that survives review for years — the whole reason this
     * subject is interesting is that the chip runs hotter than the air around it.
     *
     * Held on a ticker rather than published raw: the sensor is continuous and will not go below
     * 1.62 Hz, while a die temperature moves over minutes.
     */
    fun imuTemperature(intervalMillis: Long): Flow<ScalarSample> =
        sensorFlow(imuTemperatureSensor(appContext), SensorManager.SENSOR_DELAY_NORMAL)
            .heldAt(intervalMillis)

    private fun scalarFlow(type: Int, rateUs: Int): Flow<ScalarSample> =
        sensorFlow(manager.getDefaultSensor(type), rateUs)

    private fun sensorFlow(sensor: Sensor?, rateUs: Int): Flow<ScalarSample> = callbackFlow {
        if (sensor == null) {
            close(); return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(ScalarSample(event.values[0], event.timestamp))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        manager.registerListener(listener, sensor, rateUs)
        awaitClose { manager.unregisterListener(listener) }
    }
}

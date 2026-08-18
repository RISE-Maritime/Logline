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

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

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

    private fun scalarFlow(type: Int, rateUs: Int): Flow<ScalarSample> = callbackFlow {
        val sensor = manager.getDefaultSensor(type)
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

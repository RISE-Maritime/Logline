package se.rise.logline.sensors

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorManager
import se.rise.logline.keelson.PublishedSubject

/**
 * What the hardware behind a subject can actually do.
 *
 * [maxRateHz] is derived from `Sensor.getMinDelay()` — the shortest delay the sensor supports between
 * events — so it is the device's own answer, not an assumption. Null means the platform does not
 * publish a limit: either the sensor is absent, it is a trigger-only sensor reporting `minDelay == 0`,
 * or (for GNSS) there is no equivalent query at all and the achievable rate depends on the chipset and
 * the sky view.
 */
data class SensorCapabilities(
    val name: String?,
    val vendor: String?,
    val maxRateHz: Double?,
    val minDelayUs: Int?,
)

fun sensorCapabilities(context: Context, subject: String): SensorCapabilities {
    // Null covers the fused location provider (no supported-rate query exists) and the battery
    // subjects (not SensorManager sensors at all) — both fall back to "no capability info".
    val type = PublishedSubject.forSubject(subject)?.sensorType
        ?: return SensorCapabilities(null, null, null, null)
    val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val sensor = manager.getDefaultSensor(type) ?: return SensorCapabilities(null, null, null, null)
    val minDelay = sensor.minDelay
    return SensorCapabilities(
        name = sensor.name,
        vendor = sensor.vendor,
        maxRateHz = if (minDelay > 0) 1_000_000.0 / minDelay else null,
        minDelayUs = if (minDelay > 0) minDelay else null,
    )
}

/**
 * Subjects this device has no sensor for.
 *
 * A phone without a barometer still shows an `air_pressure_pa` row, and a row that never publishes is
 * indistinguishable from one that broke — so the UI needs to be able to say which it is. Only entries
 * with a `sensorType` can be answered: the fused location provider and the battery and radio subjects
 * are not `SensorManager` sensors, and their absence is not a hardware question.
 */
fun unavailableSubjects(context: Context): Set<PublishedSubject> {
    val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val bySensor = PublishedSubject.entries
        .filter { entry -> entry.sensorType?.let { manager.getDefaultSensor(it) == null } == true }
    // The camera is not a SensorManager sensor, so it needs its own question — asked here rather than
    // left out, because a device with no camera would otherwise show a row waiting forever for a frame.
    val noCamera = !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    return buildSet {
        addAll(bySensor)
        if (noCamera) add(PublishedSubject.IMAGE_COMPRESSED)
    }
}

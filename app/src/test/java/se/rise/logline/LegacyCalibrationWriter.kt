package se.rise.logline

// The single-platform writer as it stood in the previous build, lifted verbatim from git so the migration
// test runs against bytes the shipped app actually produced rather than a hand-written imitation of
// them. Do not "tidy" this: the moment it stops being a copy it stops testing anything.

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import se.rise.logline.calibrate.PlatformCalibration
import se.rise.logline.config.Keys

private val LEGACY_SENSOR_FIELDS = listOf(
    "label", "frame_id", "type", "x", "y", "z", "yaw", "pitch", "roll",
    "capture", "accuracy", "captured_at",
)

fun writeLegacyCalibration(prefs: MutablePreferences, calibration: PlatformCalibration?) {
    val previousCount = prefs[Keys.CALIB_SENSOR_COUNT]?.toIntOrNull() ?: 0

    fun set(key: Preferences.Key<String>, value: String?) {
        if (value == null) prefs.remove(key) else prefs[key] = value
    }

    set(Keys.CALIB_NAME, calibration?.name)
    set(Keys.CALIB_ENTITY_ID, calibration?.entityId)
    set(Keys.CALIB_PARENT_FRAME_ID, calibration?.parentFrameId)
    set(Keys.CALIB_PLATFORM_TYPE, calibration?.platformType?.name)
    set(Keys.CALIB_DESCRIPTION, calibration?.description?.takeIf { it.isNotBlank() })
    set(Keys.CALIB_LOA_M, calibration?.lengthOverAllM?.toString())
    set(Keys.CALIB_BOA_M, calibration?.breadthOverAllM?.toString())
    set(Keys.CALIB_CCRP_X, calibration?.ccrp?.x?.toString())
    set(Keys.CALIB_CCRP_Y, calibration?.ccrp?.y?.toString())
    set(Keys.CALIB_CCRP_Z, calibration?.ccrp?.z?.toString())
    set(Keys.CALIB_UPDATED_AT, calibration?.updatedAtEpochMillis?.toString())

    val zero = calibration?.zero
    set(Keys.CALIB_ZERO_LAT, zero?.latitude?.toString())
    set(Keys.CALIB_ZERO_LON, zero?.longitude?.toString())
    set(Keys.CALIB_ZERO_ALT, zero?.altitudeM?.toString())
    set(Keys.CALIB_ZERO_ACCURACY, zero?.accuracyM?.toString())
    set(Keys.CALIB_ZERO_V_ACCURACY, zero?.verticalAccuracyM?.toString())
    set(Keys.CALIB_ZERO_SCATTER, zero?.scatterM?.toString())
    set(Keys.CALIB_ZERO_HEADING, zero?.headingDeg?.toString())
    set(Keys.CALIB_ZERO_HEADING_SOURCE, zero?.headingSource?.name)
    set(Keys.CALIB_ZERO_CAPTURE, zero?.capture?.name)
    set(Keys.CALIB_ZERO_SAMPLES, zero?.samples?.toString())
    set(Keys.CALIB_ZERO_CAPTURED_AT, zero?.capturedAtEpochMillis?.toString())

    val sensors = calibration?.sensors.orEmpty()
    set(Keys.CALIB_SENSOR_COUNT, if (calibration == null) null else sensors.size.toString())
    sensors.forEachIndexed { i, mount ->
        fun put(field: String, value: String?) {
            val key = Keys.calibSensor(i, field)
            if (value == null) prefs.remove(key) else prefs[key] = value
        }
        put("label", mount.label)
        put("frame_id", mount.frameId)
        put("type", mount.sensorType.name)
        put("x", mount.translation.x.toString())
        put("y", mount.translation.y.toString())
        put("z", mount.translation.z.toString())
        put("yaw", mount.rotation.yaw.toString())
        put("pitch", mount.rotation.pitch.toString())
        put("roll", mount.rotation.roll.toString())
        put("capture", mount.capture.name)
        put("accuracy", mount.accuracyM?.toString())
        put("captured_at", mount.capturedAtEpochMillis.toString())
    }
    for (i in sensors.size until previousCount) {
        LEGACY_SENSOR_FIELDS.forEach { field -> prefs.remove(Keys.calibSensor(i, field)) }
    }
}

/**
 * An unrecognised or incomplete stored value is treated as "follow policy" rather than crashing —
 * a Zenoh enum could be renamed between versions while a stale preference is still on disk.
 */

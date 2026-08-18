package se.rise.logline.config

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import io.zenoh.qos.CongestionControl
import io.zenoh.qos.Priority
import io.zenoh.qos.Reliability
import se.rise.logline.calibrate.CaptureMethod
import se.rise.logline.calibrate.EulerDeg
import se.rise.logline.calibrate.HeadingSource
import se.rise.logline.calibrate.PlatformType
import se.rise.logline.calibrate.RigCalibration
import se.rise.logline.calibrate.RigZero
import se.rise.logline.calibrate.SensorMount
import se.rise.logline.calibrate.SensorType
import se.rise.logline.calibrate.Vec3M
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.SubjectQos
import se.rise.logline.sensors.SensorRate
import se.rise.logline.sensors.parseSensorRate
import se.rise.logline.sensors.serialise

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "logline_settings")

/**
 * The DataStore wrapper. All the decisions live in [readSettings] / [writeSettings] below, which are
 * free of Android and therefore testable; this class only supplies the store and the device model.
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        readSettings(prefs, defaultEntityId = slugifyModel(Build.MODEL))
    }

    suspend fun update(settings: Settings) {
        context.dataStore.edit { prefs -> writeSettings(prefs, settings) }
    }
}

internal object Keys {
    val REALM = stringPreferencesKey("realm")
    val ENTITY_ID = stringPreferencesKey("entity_id")
    val ROUTER_ENDPOINT = stringPreferencesKey("router_endpoint")
    val LOCATION_SOURCE = stringPreferencesKey("location_source")
    val IMU_SOURCE = stringPreferencesKey("imu_source")
    val DEVICE_SOURCE = stringPreferencesKey("device_source")
    val RECORDING_ENABLED = stringPreferencesKey("recording_enabled")
    val BACKFILL_ENABLED = stringPreferencesKey("backfill_enabled")
    val SCOUT_ADDRESS = stringPreferencesKey("scout_address")
    val AUDIO_ENABLED = stringPreferencesKey("audio_enabled")
    val AUDIO_SAMPLE_RATE = stringPreferencesKey("audio_sample_rate_hz")
    val AUDIO_CHANNELS = stringPreferencesKey("audio_channels")
    val CHECKLIST_ENABLED = stringPreferencesKey("checklist_enabled")
    val OPERATOR_ID = stringPreferencesKey("operator_id")
    val OPERATOR_NAME = stringPreferencesKey("operator_name")
    val OPERATOR_ROLE = stringPreferencesKey("operator_role")
    val ROC_SITE_ID = stringPreferencesKey("roc_site_id")
    val CHECKLIST_REALM = stringPreferencesKey("checklist_realm")
    val CHECKLIST_ENTITY_ID = stringPreferencesKey("checklist_entity_id")
    val CAMERA_ENABLED = stringPreferencesKey("camera_enabled")
    val CAMERA_LENS_FRONT = stringPreferencesKey("camera_lens_front")
    val CAMERA_WIDTH = stringPreferencesKey("camera_width")
    val CAMERA_HEIGHT = stringPreferencesKey("camera_height")

    /**
     * Switched-off subjects, newline-delimited registry entry names.
     *
     * One key rather than one per entry, the same shape as [ROUTER_ENDPOINT]: the natural default is
     * "nothing is off", which an absent or empty value already says, so there is no half-written state
     * for a per-entry key to protect against.
     */
    val DISABLED_SUBJECTS = stringPreferencesKey("disabled_subjects")

    /**
     * The annotation buttons, newline-delimited, one tab-separated button per line.
     *
     * Whether the key is *present* is load-bearing here, unlike every other list in this file: absent
     * means "never configured", which reads as the defaults, while a present-but-empty value means the
     * user deleted them all. Falling back to the defaults for both would resurrect three buttons every
     * time the app restarted, which is a setting that will not stay set.
     */
    val ANNOTATION_BUTTONS = stringPreferencesKey("annotation_buttons")

    // Four keys per subject, e.g. `qos_location_fix_priority`. All four absent means "follow
    // qos.yaml"; a partial set is treated the same way, so a half-written override cannot produce
    // a combination the user never chose.
    fun qosPriority(subject: String) = stringPreferencesKey("qos_${subject}_priority")
    fun qosCongestion(subject: String) = stringPreferencesKey("qos_${subject}_congestion")
    fun qosReliability(subject: String) = stringPreferencesKey("qos_${subject}_reliability")
    fun qosExpress(subject: String) = stringPreferencesKey("qos_${subject}_express")

    /** Requested sampling rate. Absent means "use the default for this subject". */
    fun rateHz(subject: String) = stringPreferencesKey("rate_$subject")

    // The rig calibration, flattened. One key per field and one group per sensor, the same shape the
    // QoS and rate overrides use — DataStore preferences is string-keyed, and spelling the structure
    // out keeps `readSettings`/`writeSettings` pure and testable with no JSON parser anywhere in the
    // app. The document this becomes is only ever *written*; see `calibrate/PlatformGeometryJson.kt`.
    val CALIBRATION_SOURCE = stringPreferencesKey("calibration_source")

    /** Absent means there is no calibration at all — the normal state. */
    val CALIB_NAME = stringPreferencesKey("calib_name")
    val CALIB_ENTITY_ID = stringPreferencesKey("calib_entity_id")
    val CALIB_PARENT_FRAME_ID = stringPreferencesKey("calib_parent_frame_id")
    val CALIB_PLATFORM_TYPE = stringPreferencesKey("calib_platform_type")
    val CALIB_DESCRIPTION = stringPreferencesKey("calib_description")
    val CALIB_LOA_M = stringPreferencesKey("calib_loa_m")
    val CALIB_BOA_M = stringPreferencesKey("calib_boa_m")
    val CALIB_CCRP_X = stringPreferencesKey("calib_ccrp_x")
    val CALIB_CCRP_Y = stringPreferencesKey("calib_ccrp_y")
    val CALIB_CCRP_Z = stringPreferencesKey("calib_ccrp_z")
    val CALIB_UPDATED_AT = stringPreferencesKey("calib_updated_at")

    val CALIB_ZERO_LAT = stringPreferencesKey("calib_zero_lat")
    val CALIB_ZERO_LON = stringPreferencesKey("calib_zero_lon")
    val CALIB_ZERO_ALT = stringPreferencesKey("calib_zero_alt")
    val CALIB_ZERO_ACCURACY = stringPreferencesKey("calib_zero_accuracy")
    val CALIB_ZERO_V_ACCURACY = stringPreferencesKey("calib_zero_v_accuracy")
    val CALIB_ZERO_SCATTER = stringPreferencesKey("calib_zero_scatter")
    val CALIB_ZERO_HEADING = stringPreferencesKey("calib_zero_heading")
    val CALIB_ZERO_HEADING_SOURCE = stringPreferencesKey("calib_zero_heading_source")
    val CALIB_ZERO_CAPTURE = stringPreferencesKey("calib_zero_capture")
    val CALIB_ZERO_SAMPLES = stringPreferencesKey("calib_zero_samples")
    val CALIB_ZERO_CAPTURED_AT = stringPreferencesKey("calib_zero_captured_at")

    val CALIB_SENSOR_COUNT = stringPreferencesKey("calib_sensor_count")
    fun calibSensor(index: Int, field: String) = stringPreferencesKey("calib_sensor_${index}_$field")
}

/**
 * The per-sensor field names, in one place so the reader and the writer cannot drift.
 *
 * They also bound the clear-out: writing a calibration with fewer sensors than last time has to
 * *remove* the higher-indexed keys, or a deleted sensor comes back at the next start.
 */
private val SENSOR_FIELDS = listOf(
    "label", "frame_id", "type", "x", "y", "z", "yaw", "pitch", "roll",
    "capture", "accuracy", "captured_at",
)

/**
 * The subjects with per-subject settings, in the order the main screen lists them.
 *
 * Derived from [PublishedSubject] rather than hand-listed: a subject missing from here persisted
 * nothing, with no compile error and no runtime error — the override simply never stuck.
 */
internal val overridableSubjects: List<String> = PublishedSubject.entries.map { it.subject }

internal fun readSettings(prefs: Preferences, defaultEntityId: String): Settings = Settings(
    realm = prefs[Keys.REALM] ?: Settings.DEFAULT_REALM,
    entityId = prefs[Keys.ENTITY_ID] ?: defaultEntityId,
    routerEndpoints = parseEndpoints(prefs[Keys.ROUTER_ENDPOINT]),
    locationSource = prefs[Keys.LOCATION_SOURCE] ?: Settings.DEFAULT_LOCATION_SOURCE,
    imuSource = prefs[Keys.IMU_SOURCE] ?: Settings.DEFAULT_IMU_SOURCE,
    deviceSource = prefs[Keys.DEVICE_SOURCE] ?: Settings.DEFAULT_DEVICE_SOURCE,
    calibrationSource = prefs[Keys.CALIBRATION_SOURCE]?.takeIf { it.isNotBlank() }
        ?: Settings.DEFAULT_CALIBRATION_SOURCE,
    calibration = readCalibration(prefs),
    recordingEnabled = prefs[Keys.RECORDING_ENABLED]?.toBooleanStrictOrNull() ?: true,
    backfillEnabled = prefs[Keys.BACKFILL_ENABLED]?.toBooleanStrictOrNull() ?: true,
    scoutAddress = prefs[Keys.SCOUT_ADDRESS]?.takeIf { it.isNotBlank() } ?: Settings.DEFAULT_SCOUT_ADDRESS,
    // Absent means off: a stored value is the only thing that turns the microphone on.
    audioEnabled = prefs[Keys.AUDIO_ENABLED]?.toBooleanStrictOrNull() ?: false,
    audioSampleRateHz = prefs[Keys.AUDIO_SAMPLE_RATE]?.toIntOrNull()?.takeIf { it > 0 }
        ?: Settings.DEFAULT_AUDIO_SAMPLE_RATE_HZ,
    audioChannels = prefs[Keys.AUDIO_CHANNELS]?.toIntOrNull()?.coerceIn(1, 2) ?: 1,
    // Absent means off, as for audio: only a stored value turns the camera on.
    cameraEnabled = prefs[Keys.CAMERA_ENABLED]?.toBooleanStrictOrNull() ?: false,
    cameraLensFront = prefs[Keys.CAMERA_LENS_FRONT]?.toBooleanStrictOrNull() ?: false,
    cameraWidth = prefs[Keys.CAMERA_WIDTH]?.toIntOrNull()?.takeIf { it > 0 }
        ?: Settings.DEFAULT_CAMERA_WIDTH,
    cameraHeight = prefs[Keys.CAMERA_HEIGHT]?.toIntOrNull()?.takeIf { it > 0 }
        ?: Settings.DEFAULT_CAMERA_HEIGHT,
    disabledSubjects = parseDisabledSubjects(prefs[Keys.DISABLED_SUBJECTS]),
    annotationButtons = readAnnotationButtons(prefs),
    qosOverrides = readQosOverrides(prefs),
    sensorRates = readSensorRates(prefs),
    // Absent means off, for the same reason audio is: a stored value is the only thing that makes
    // this phone visible to other sites.
    checklistEnabled = prefs[Keys.CHECKLIST_ENABLED]?.toBooleanStrictOrNull() ?: false,
    operatorId = prefs[Keys.OPERATOR_ID].orEmpty(),
    operatorName = prefs[Keys.OPERATOR_NAME].orEmpty(),
    operatorRole = prefs[Keys.OPERATOR_ROLE].orEmpty(),
    rocSiteId = prefs[Keys.ROC_SITE_ID].orEmpty(),
    checklistRealm = prefs[Keys.CHECKLIST_REALM]?.takeIf { it.isNotBlank() }
        ?: Settings.DEFAULT_CHECKLIST_REALM,
    checklistEntityId = prefs[Keys.CHECKLIST_ENTITY_ID]?.takeIf { it.isNotBlank() }
        ?: Settings.DEFAULT_CHECKLIST_ENTITY,
)

internal fun writeSettings(prefs: MutablePreferences, settings: Settings) {
    prefs[Keys.REALM] = settings.realm
    prefs[Keys.ENTITY_ID] = settings.entityId
    prefs[Keys.ROUTER_ENDPOINT] = settings.routerEndpoints.serialiseEndpoints()
    prefs[Keys.LOCATION_SOURCE] = settings.locationSource
    prefs[Keys.IMU_SOURCE] = settings.imuSource
    prefs[Keys.DEVICE_SOURCE] = settings.deviceSource
    prefs[Keys.CALIBRATION_SOURCE] = settings.calibrationSource
    writeCalibration(prefs, settings.calibration)
    prefs[Keys.RECORDING_ENABLED] = settings.recordingEnabled.toString()
    prefs[Keys.BACKFILL_ENABLED] = settings.backfillEnabled.toString()
    prefs[Keys.SCOUT_ADDRESS] = settings.scoutAddress
    prefs[Keys.AUDIO_ENABLED] = settings.audioEnabled.toString()
    prefs[Keys.AUDIO_SAMPLE_RATE] = settings.audioSampleRateHz.toString()
    prefs[Keys.AUDIO_CHANNELS] = settings.audioChannels.toString()
    prefs[Keys.CHECKLIST_ENABLED] = settings.checklistEnabled.toString()
    prefs[Keys.OPERATOR_ID] = settings.operatorId
    prefs[Keys.OPERATOR_NAME] = settings.operatorName
    prefs[Keys.OPERATOR_ROLE] = settings.operatorRole
    prefs[Keys.ROC_SITE_ID] = settings.rocSiteId
    prefs[Keys.CHECKLIST_REALM] = settings.checklistRealm
    prefs[Keys.CHECKLIST_ENTITY_ID] = settings.checklistEntityId
    prefs[Keys.CAMERA_ENABLED] = settings.cameraEnabled.toString()
    prefs[Keys.CAMERA_LENS_FRONT] = settings.cameraLensFront.toString()
    prefs[Keys.CAMERA_WIDTH] = settings.cameraWidth.toString()
    prefs[Keys.CAMERA_HEIGHT] = settings.cameraHeight.toString()
    prefs[Keys.DISABLED_SUBJECTS] = settings.disabledSubjects.serialiseDisabledSubjects()
    prefs[Keys.ANNOTATION_BUTTONS] = settings.annotationButtons.serialiseAnnotationButtons()
    overridableSubjects.forEach { subject ->
        val override = settings.qosOverrides[subject]
        if (override == null) {
            // Removed rather than written as a sentinel, so a subject with no opinion keeps following
            // qos.yaml even if upstream policy later changes.
            prefs.remove(Keys.qosPriority(subject))
            prefs.remove(Keys.qosCongestion(subject))
            prefs.remove(Keys.qosReliability(subject))
            prefs.remove(Keys.qosExpress(subject))
        } else {
            prefs[Keys.qosPriority(subject)] = override.priority.name
            prefs[Keys.qosCongestion(subject)] = override.congestionControl.name
            prefs[Keys.qosReliability(subject)] = override.reliability.name
            prefs[Keys.qosExpress(subject)] = override.express.toString()
        }
        val rate = settings.sensorRates[subject]
        if (rate == null) {
            prefs.remove(Keys.rateHz(subject))
        } else {
            prefs[Keys.rateHz(subject)] = rate.serialise()
        }
    }
}

/**
 * The rig calibration, or null when nothing has been calibrated.
 *
 * The name is the presence flag: no name, no calibration. Everything else has a sensible fallback, so
 * a preferences file half-written by a crash yields a usable rig rather than an exception at startup —
 * the same stance [readQosOverrides] takes.
 */
internal fun readCalibration(prefs: Preferences): RigCalibration? {
    val name = prefs[Keys.CALIB_NAME]?.takeIf { it.isNotBlank() } ?: return null
    return RigCalibration(
        name = name,
        entityId = prefs[Keys.CALIB_ENTITY_ID]?.takeIf { it.isNotBlank() }
            ?: se.rise.logline.calibrate.defaultEntityId(name),
        parentFrameId = prefs[Keys.CALIB_PARENT_FRAME_ID]?.takeIf { it.isNotBlank() }
            ?: se.rise.logline.calibrate.defaultParentFrameId(name),
        platformType = PlatformType.entries.byName(prefs[Keys.CALIB_PLATFORM_TYPE]),
        description = prefs[Keys.CALIB_DESCRIPTION].orEmpty(),
        lengthOverAllM = prefs[Keys.CALIB_LOA_M]?.toDoubleOrNull(),
        breadthOverAllM = prefs[Keys.CALIB_BOA_M]?.toDoubleOrNull(),
        ccrp = Vec3M(
            x = prefs[Keys.CALIB_CCRP_X]?.toDoubleOrNull() ?: 0.0,
            y = prefs[Keys.CALIB_CCRP_Y]?.toDoubleOrNull() ?: 0.0,
            z = prefs[Keys.CALIB_CCRP_Z]?.toDoubleOrNull() ?: 0.0,
        ),
        zero = readRigZero(prefs),
        sensors = readSensorMounts(prefs),
        updatedAtEpochMillis = prefs[Keys.CALIB_UPDATED_AT]?.toLongOrNull() ?: 0L,
    )
}

/**
 * The surveyed origin, or null.
 *
 * Latitude and longitude together are the presence flag, and both must parse: a zero with one of them
 * missing would place the rig on the equator or the Greenwich meridian, which is a real place and a
 * completely wrong answer.
 */
private fun readRigZero(prefs: Preferences): RigZero? {
    val lat = prefs[Keys.CALIB_ZERO_LAT]?.toDoubleOrNull() ?: return null
    val lon = prefs[Keys.CALIB_ZERO_LON]?.toDoubleOrNull() ?: return null
    return RigZero(
        latitude = lat,
        longitude = lon,
        altitudeM = prefs[Keys.CALIB_ZERO_ALT]?.toDoubleOrNull(),
        accuracyM = prefs[Keys.CALIB_ZERO_ACCURACY]?.toDoubleOrNull(),
        verticalAccuracyM = prefs[Keys.CALIB_ZERO_V_ACCURACY]?.toDoubleOrNull(),
        scatterM = prefs[Keys.CALIB_ZERO_SCATTER]?.toDoubleOrNull(),
        headingDeg = prefs[Keys.CALIB_ZERO_HEADING]?.toDoubleOrNull() ?: 0.0,
        headingSource = HeadingSource.entries.byName(prefs[Keys.CALIB_ZERO_HEADING_SOURCE])
            ?: HeadingSource.MANUAL,
        capture = CaptureMethod.entries.byName(prefs[Keys.CALIB_ZERO_CAPTURE]) ?: CaptureMethod.MANUAL,
        samples = prefs[Keys.CALIB_ZERO_SAMPLES]?.toIntOrNull() ?: 0,
        capturedAtEpochMillis = prefs[Keys.CALIB_ZERO_CAPTURED_AT]?.toLongOrNull() ?: 0L,
    )
}

/**
 * The sensor mounts, in stored order.
 *
 * A mount missing its frame id or any of its three translation components is **skipped**, not read as
 * zeros: a half-written entry would otherwise appear on the bus as a sensor mounted exactly at the
 * rig's origin, which is a plausible-looking lie.
 */
private fun readSensorMounts(prefs: Preferences): List<SensorMount> {
    val count = prefs[Keys.CALIB_SENSOR_COUNT]?.toIntOrNull() ?: 0
    return (0 until count).mapNotNull { i ->
        fun field(name: String) = prefs[Keys.calibSensor(i, name)]
        val frameId = field("frame_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val x = field("x")?.toDoubleOrNull() ?: return@mapNotNull null
        val y = field("y")?.toDoubleOrNull() ?: return@mapNotNull null
        val z = field("z")?.toDoubleOrNull() ?: return@mapNotNull null
        SensorMount(
            label = field("label").orEmpty(),
            frameId = frameId,
            sensorType = SensorType.entries.byName(field("type")) ?: SensorType.OTHER,
            translation = Vec3M(x, y, z),
            rotation = EulerDeg(
                yaw = field("yaw")?.toDoubleOrNull() ?: 0.0,
                pitch = field("pitch")?.toDoubleOrNull() ?: 0.0,
                roll = field("roll")?.toDoubleOrNull() ?: 0.0,
            ),
            capture = CaptureMethod.entries.byName(field("capture")) ?: CaptureMethod.MANUAL,
            accuracyM = field("accuracy")?.toDoubleOrNull(),
            capturedAtEpochMillis = field("captured_at")?.toLongOrNull() ?: 0L,
        )
    }
}

/**
 * Persist the calibration, clearing whatever the last one left behind.
 *
 * The clear-out is the part that matters: the sensor keys are indexed, so writing a four-sensor rig
 * over a six-sensor one has to remove indices four and five explicitly. Without that the two deleted
 * sensors reappear at the next start — present in the file, absent from the screen, and published.
 */
internal fun writeCalibration(prefs: MutablePreferences, calibration: RigCalibration?) {
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
        SENSOR_FIELDS.forEach { field -> prefs.remove(Keys.calibSensor(i, field)) }
    }
}

/**
 * An unrecognised or incomplete stored value is treated as "follow policy" rather than crashing —
 * a Zenoh enum could be renamed between versions while a stale preference is still on disk.
 */
internal fun readQosOverrides(prefs: Preferences): Map<String, SubjectQos> =
    overridableSubjects.mapNotNull { subject ->
        val priority = Priority.entries.byName(prefs[Keys.qosPriority(subject)])
            ?: return@mapNotNull null
        val congestion = CongestionControl.entries.byName(prefs[Keys.qosCongestion(subject)])
            ?: return@mapNotNull null
        val reliability = Reliability.entries.byName(prefs[Keys.qosReliability(subject)])
            ?: return@mapNotNull null
        val express = prefs[Keys.qosExpress(subject)]?.toBooleanStrictOrNull()
            ?: return@mapNotNull null
        subject to SubjectQos(priority, congestion, reliability, express)
    }.toMap()

internal fun readSensorRates(prefs: Preferences): Map<String, SensorRate> =
    overridableSubjects.mapNotNull { subject ->
        val rate = parseSensorRate(prefs[Keys.rateHz(subject)]) ?: return@mapNotNull null
        subject to rate
    }.toMap()

/**
 * Switched-off subjects, by entry name.
 *
 * A name that no longer resolves is dropped rather than thrown on: renaming or removing a registry
 * entry is a legal change, and a preference file written by an older build must not be able to stop the
 * app from reading its settings. Written in registry order so the stored value is stable and diffable.
 */
internal fun parseDisabledSubjects(stored: String?): Set<PublishedSubject> =
    stored.orEmpty().split('\n')
        .mapNotNull { PublishedSubject.forName(it.trim()) }
        .toSet()

/**
 * The annotation buttons, or the defaults when the key has never been written.
 *
 * A malformed line is skipped rather than failing the whole read — see [parseAnnotationButton]. That
 * means a file whose every line is corrupt reads as an empty list rather than as the defaults, which
 * is the honest answer: the key was written, so somebody did configure this.
 */
internal fun readAnnotationButtons(prefs: Preferences): List<AnnotationButton> {
    val stored = prefs[Keys.ANNOTATION_BUTTONS] ?: return Settings.DEFAULT_ANNOTATION_BUTTONS
    return stored.split('\n').mapNotNull { parseAnnotationButton(it) }
}

internal fun List<AnnotationButton>.serialiseAnnotationButtons(): String =
    joinToString("\n") { it.serialise() }

internal fun Set<PublishedSubject>.serialiseDisabledSubjects(): String =
    PublishedSubject.entries.filter { it in this }.joinToString("\n") { it.name }

/**
 * Endpoints are stored newline-delimited under the key that used to hold a single locator.
 *
 * That is the migration: a preference written by an older build is one line, which parses as a
 * one-element list, so nothing is stranded and no second key is introduced. A newline is safe because a
 * Zenoh locator cannot contain one.
 *
 * An absent or entirely blank value falls back to the default endpoint rather than an empty list — a
 * session with nowhere to connect is not a state worth persisting.
 */
internal fun parseEndpoints(stored: String?): List<String> {
    val parsed = stored.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    return parsed.ifEmpty { listOf(Settings.DEFAULT_ENDPOINT) }
}

internal fun List<String>.serialiseEndpoints(): String =
    map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

/**
 * `Build.MODEL` as an entity id: lowercase, non-alphanumerics collapsed to `_`, e.g. `Pixel 6` becomes
 * `pixel_6`. Falls back to `android` when the model is missing or has nothing usable in it.
 */
internal fun slugifyModel(model: String?): String =
    (model ?: "android").lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "android" }

private fun <T : Enum<T>> List<T>.byName(stored: String?): T? =
    stored?.let { name -> firstOrNull { it.name == name } }

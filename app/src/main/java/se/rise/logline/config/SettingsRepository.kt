package se.rise.logline.config

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
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
import se.rise.logline.calibrate.parsePlatformGeometry
import se.rise.logline.calibrate.RigCalibration
import se.rise.logline.calibrate.RigZero
import se.rise.logline.calibrate.SensorMount
import se.rise.logline.calibrate.SensorType
import se.rise.logline.calibrate.toStoredJson
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
    val BATTERY_EXEMPTION_ASKED = stringPreferencesKey("battery_exemption_asked")
    val START_ON_BOOT = stringPreferencesKey("start_on_boot")
    val OFFLINE_TILES_ONLY = stringPreferencesKey("offline_tiles_only")
    val MAPTILER_KEY = stringPreferencesKey("maptiler_key")
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
    val VIDEO_ENABLED = stringPreferencesKey("video_enabled")
    val VIDEO_WIDTH = stringPreferencesKey("video_width")
    val VIDEO_HEIGHT = stringPreferencesKey("video_height")
    val VIDEO_BITRATE_KBPS = stringPreferencesKey("video_bitrate_kbps")
    val VIDEO_KEYFRAME_SECONDS = stringPreferencesKey("video_keyframe_seconds")

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

    /**
     * Requested **publish** rate. Absent means "use the default for this subject".
     *
     * Deliberately still `rate_*`: this was the only rate before the file and the wire were split, and
     * keeping the key means every existing setting carries over as the publish rate rather than being
     * silently reset — the same reason the endpoint list kept `router_endpoint` when it became a list.
     */
    fun rateHz(subject: String) = stringPreferencesKey("rate_$subject")

    /** Requested **recording** rate. Absent means `SensorRate.Max` — the file takes everything. */
    fun recordRateHz(subject: String) = stringPreferencesKey("record_rate_$subject")

    val RECORD_ALL_MAX = booleanPreferencesKey("record_all_max")
    val PUBLISH_ALL_MAX = booleanPreferencesKey("publish_all_max")

    val CALIBRATION_SOURCE = stringPreferencesKey("calibration_source")

    // The rig library. One rig per key, each holding the whole document as JSON.
    //
    // A flat key per field, the way everything else here is stored, was what the single rig used — and
    // it does not survive becoming a list. Two nested indices (rig, then sensor) mean a clear-out that
    // has to walk both, and that clear-out is the part that has already been got wrong once: deleting a
    // sensor without removing its indices resurrects it at the next start. One string per rig turns
    // that into a single loop, and the serialiser it needs already exists.
    val RIG_COUNT = stringPreferencesKey("rig_count")
    fun rig(index: Int) = stringPreferencesKey("rig_$index")

    /** Entity id of the rig the phone is on. Absent means none is selected. */
    val RIG_ACTIVE_ENTITY = stringPreferencesKey("rig_active_entity")

    /**
     * Entity ids of the rigs opted in to publishing, newline-delimited — the same shape
     * [ROUTER_ENDPOINT] uses, for the same reason: a list in a string-keyed store.
     */
    val RIG_PUBLISHING = stringPreferencesKey("rig_publishing")
    val RIG_SHARE_LIBRARY = stringPreferencesKey("rig_share_library")
    val RIG_REGISTRY_VERSION = stringPreferencesKey("rig_registry_version")
    val RIG_REGISTRY_ORIGIN = stringPreferencesKey("rig_registry_origin")

    // The single rig, flattened — **read-only now, and kept only for the migration**. A preferences
    // file written by an older build has these and no `rig_count`; `readRigs` turns them into a
    // one-rig library and the next write replaces them. Nothing writes them any more.

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

internal fun readSettings(prefs: Preferences, defaultEntityId: String): Settings {
    // Read once and reused: the migrated selection is derived from the library itself, so the
    // two cannot be built independently.
    val rigs = readRigs(prefs)
    return Settings(
        realm = prefs[Keys.REALM] ?: Settings.DEFAULT_REALM,
        entityId = prefs[Keys.ENTITY_ID] ?: defaultEntityId,
        routerEndpoints = parseEndpoints(prefs[Keys.ROUTER_ENDPOINT]),
        locationSource = prefs[Keys.LOCATION_SOURCE] ?: Settings.DEFAULT_LOCATION_SOURCE,
        imuSource = prefs[Keys.IMU_SOURCE] ?: Settings.DEFAULT_IMU_SOURCE,
        deviceSource = prefs[Keys.DEVICE_SOURCE] ?: Settings.DEFAULT_DEVICE_SOURCE,
        calibrationSource = prefs[Keys.CALIBRATION_SOURCE]?.takeIf { it.isNotBlank() }
            ?: Settings.DEFAULT_CALIBRATION_SOURCE,
        rigs = rigs,
        // `?:` on the raw key, not `ifBlank`: an **absent** key is a file from before the library
        // and needs its one rig nominated, while a **present but empty** one is a selection somebody
        // cleared by deleting the active rig. Treating those the same re-activates a surviving rig on
        // the next launch — and since the active rig always publishes, its geometry starts going out
        // under its own entity id with nobody having asked. Same rule as the annotation buttons: an
        // absent key and an empty value mean different things.
        activeRigEntityId = prefs[Keys.RIG_ACTIVE_ENTITY] ?: migratedActiveRig(rigs),
        publishingRigEntityIds = parseRigEntityIds(prefs[Keys.RIG_PUBLISHING]),
        // Absent means off, as for the checklist: only a stored value shares the library.
        shareRigLibrary = prefs[Keys.RIG_SHARE_LIBRARY]?.toBooleanStrictOrNull() ?: false,
        rigRegistryVersion = prefs[Keys.RIG_REGISTRY_VERSION]?.toLongOrNull() ?: 0L,
        rigRegistryOrigin = prefs[Keys.RIG_REGISTRY_ORIGIN].orEmpty(),
        recordingEnabled = prefs[Keys.RECORDING_ENABLED]?.toBooleanStrictOrNull() ?: true,
        backfillEnabled = prefs[Keys.BACKFILL_ENABLED]?.toBooleanStrictOrNull() ?: true,
        batteryExemptionAsked = prefs[Keys.BATTERY_EXEMPTION_ASKED]?.toBooleanStrictOrNull() ?: false,
        startOnBoot = prefs[Keys.START_ON_BOOT]?.toBooleanStrictOrNull() ?: false,
    offlineTilesOnly = prefs[Keys.OFFLINE_TILES_ONLY]?.toBooleanStrictOrNull() ?: false,
    mapTilerKey = prefs[Keys.MAPTILER_KEY].orEmpty(),
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
        // Absent means off, the same rule as audio and the stills: recording everything the lens sees
        // is never a state a phone arrives in without somebody choosing it.
        videoEnabled = prefs[Keys.VIDEO_ENABLED]?.toBooleanStrictOrNull() ?: false,
        videoWidth = prefs[Keys.VIDEO_WIDTH]?.toIntOrNull()?.takeIf { it > 0 }
            ?: Settings.DEFAULT_VIDEO_WIDTH,
        videoHeight = prefs[Keys.VIDEO_HEIGHT]?.toIntOrNull()?.takeIf { it > 0 }
            ?: Settings.DEFAULT_VIDEO_HEIGHT,
        videoBitrateKbps = prefs[Keys.VIDEO_BITRATE_KBPS]?.toIntOrNull()?.takeIf { it > 0 }
            ?: Settings.DEFAULT_VIDEO_BITRATE_KBPS,
        videoKeyframeSeconds = prefs[Keys.VIDEO_KEYFRAME_SECONDS]?.toIntOrNull()?.takeIf { it > 0 }
            ?: Settings.DEFAULT_VIDEO_KEYFRAME_SECONDS,
        disabledSubjects = parseDisabledSubjects(prefs[Keys.DISABLED_SUBJECTS]),
        annotationButtons = readAnnotationButtons(prefs),
        qosOverrides = readQosOverrides(prefs),
        sensorRates = readSensorRates(prefs),
        recordRates = readRecordRates(prefs),
        // Absent means the shipped default, and for recording that is **maximum** — the file is what
        // analysis is run against. Reading it as false here would quietly contradict `Settings`' own
        // default, which is exactly what it did: a fresh install showed "Configured" on the Session
        // screen while the data class said otherwise.
        recordAllMax = prefs[Keys.RECORD_ALL_MAX] ?: true,
        publishAllMax = prefs[Keys.PUBLISH_ALL_MAX] ?: false,
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
}

internal fun writeSettings(prefs: MutablePreferences, settings: Settings) {
    prefs[Keys.REALM] = settings.realm
    prefs[Keys.ENTITY_ID] = settings.entityId
    prefs[Keys.ROUTER_ENDPOINT] = settings.routerEndpoints.serialiseEndpoints()
    prefs[Keys.LOCATION_SOURCE] = settings.locationSource
    prefs[Keys.IMU_SOURCE] = settings.imuSource
    prefs[Keys.DEVICE_SOURCE] = settings.deviceSource
    prefs[Keys.CALIBRATION_SOURCE] = settings.calibrationSource
    writeRigs(prefs, settings)
    prefs[Keys.RECORDING_ENABLED] = settings.recordingEnabled.toString()
    prefs[Keys.BACKFILL_ENABLED] = settings.backfillEnabled.toString()
    prefs[Keys.BATTERY_EXEMPTION_ASKED] = settings.batteryExemptionAsked.toString()
    prefs[Keys.START_ON_BOOT] = settings.startOnBoot.toString()
    prefs[Keys.OFFLINE_TILES_ONLY] = settings.offlineTilesOnly.toString()
    prefs[Keys.MAPTILER_KEY] = settings.mapTilerKey
    prefs[Keys.SCOUT_ADDRESS] = settings.scoutAddress
    prefs[Keys.AUDIO_ENABLED] = settings.audioEnabled.toString()
    prefs[Keys.AUDIO_SAMPLE_RATE] = settings.audioSampleRateHz.toString()
    prefs[Keys.AUDIO_CHANNELS] = settings.audioChannels.toString()
    prefs[Keys.CHECKLIST_ENABLED] = settings.checklistEnabled.toString()
    prefs[Keys.RECORD_ALL_MAX] = settings.recordAllMax
    prefs[Keys.PUBLISH_ALL_MAX] = settings.publishAllMax
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
    prefs[Keys.VIDEO_ENABLED] = settings.videoEnabled.toString()
    prefs[Keys.VIDEO_WIDTH] = settings.videoWidth.toString()
    prefs[Keys.VIDEO_HEIGHT] = settings.videoHeight.toString()
    prefs[Keys.VIDEO_BITRATE_KBPS] = settings.videoBitrateKbps.toString()
    prefs[Keys.VIDEO_KEYFRAME_SECONDS] = settings.videoKeyframeSeconds.toString()
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
        val recordRate = settings.recordRates[subject]
        if (recordRate == null) {
            prefs.remove(Keys.recordRateHz(subject))
        } else {
            prefs[Keys.recordRateHz(subject)] = recordRate.serialise()
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
 * Persist the rig library, clearing whatever a longer one left behind.
 *
 * The clear-out is the part that matters, and it is why a rig is one key rather than a spray of them:
 * writing a two-rig library over a five-rig one has to remove indices two, three and four, and with a
 * flat scheme that meant walking every field of every sensor of every removed rig. Miss any of it and
 * the deleted rig comes back at the next start — present in the file, absent from the screen, and on
 * the bus.
 *
 * The single-rig `calib_*` keys are removed here too, once, so a migrated file does not carry a stale
 * copy of the rig it was migrated from.
 */
internal fun writeRigs(prefs: MutablePreferences, settings: Settings) {
    val previousCount = prefs[Keys.RIG_COUNT]?.toIntOrNull() ?: 0
    settings.rigs.forEachIndexed { i, rig -> prefs[Keys.rig(i)] = rig.toStoredJson() }
    for (i in settings.rigs.size until previousCount) prefs.remove(Keys.rig(i))
    prefs[Keys.RIG_COUNT] = settings.rigs.size.toString()
    prefs[Keys.RIG_ACTIVE_ENTITY] = settings.activeRigEntityId
    prefs[Keys.RIG_PUBLISHING] = settings.publishingRigEntityIds.sorted().joinToString("\n")
    prefs[Keys.RIG_SHARE_LIBRARY] = settings.shareRigLibrary.toString()
    prefs[Keys.RIG_REGISTRY_VERSION] = settings.rigRegistryVersion.toString()
    prefs[Keys.RIG_REGISTRY_ORIGIN] = settings.rigRegistryOrigin
    if (prefs[Keys.CALIB_NAME] != null) clearLegacyCalibration(prefs)
}

/**
 * The rig library, or the migrated single rig, or nothing.
 *
 * `rig_count` present is the new scheme and is authoritative even when it says zero — that is a
 * library somebody emptied, and falling back to the legacy keys there would resurrect the rig they
 * deleted. Only its *absence* means the file predates the library, in which case today's single
 * calibration becomes a one-rig library; [readSettings] takes the active rig from
 * [Keys.RIG_ACTIVE_ENTITY], which is likewise absent, so `activeRig()` is null until the next write —
 * hence the migration also has to nominate it, which it does in [migrateActiveRig].
 *
 * A rig whose JSON will not parse is skipped rather than failing the whole read, the same stance
 * [readQosOverrides] takes: one corrupt entry should cost one rig, not every setting on the phone.
 */
internal fun readRigs(prefs: Preferences): List<RigCalibration> {
    val count = prefs[Keys.RIG_COUNT]?.toIntOrNull()
        ?: return listOfNotNull(readCalibration(prefs))
    return (0 until count).mapNotNull { i ->
        prefs[Keys.rig(i)]?.let { parsePlatformGeometry(it) }
    }
}

/**
 * Which rig a freshly migrated file should have selected.
 *
 * A file written by an older build has one rig and no stored selection, and leaving it unselected
 * would silently stop publishing geometry that was publishing before the update — the one thing a
 * migration must not do. Reached **only** when [Keys.RIG_ACTIVE_ENTITY] is absent; see the call site
 * for why a present-but-empty value must not come here.
 */
internal fun migratedActiveRig(rigs: List<RigCalibration>): String =
    rigs.singleOrNull()?.entityId.orEmpty()

private fun clearLegacyCalibration(prefs: MutablePreferences) {
    val previousSensors = prefs[Keys.CALIB_SENSOR_COUNT]?.toIntOrNull() ?: 0
    listOf(
        Keys.CALIB_NAME, Keys.CALIB_ENTITY_ID, Keys.CALIB_PARENT_FRAME_ID, Keys.CALIB_PLATFORM_TYPE,
        Keys.CALIB_DESCRIPTION, Keys.CALIB_LOA_M, Keys.CALIB_BOA_M, Keys.CALIB_CCRP_X,
        Keys.CALIB_CCRP_Y, Keys.CALIB_CCRP_Z, Keys.CALIB_UPDATED_AT, Keys.CALIB_ZERO_LAT,
        Keys.CALIB_ZERO_LON, Keys.CALIB_ZERO_ALT, Keys.CALIB_ZERO_ACCURACY,
        Keys.CALIB_ZERO_V_ACCURACY, Keys.CALIB_ZERO_SCATTER, Keys.CALIB_ZERO_HEADING,
        Keys.CALIB_ZERO_HEADING_SOURCE, Keys.CALIB_ZERO_CAPTURE, Keys.CALIB_ZERO_SAMPLES,
        Keys.CALIB_ZERO_CAPTURED_AT, Keys.CALIB_SENSOR_COUNT,
    ).forEach { prefs.remove(it) }
    for (i in 0 until previousSensors) {
        SENSOR_FIELDS.forEach { prefs.remove(Keys.calibSensor(i, it)) }
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

/** Absent stays absent, which `Settings.recordRate` reads as Max. */
internal fun readRecordRates(prefs: Preferences): Map<String, SensorRate> =
    overridableSubjects.mapNotNull { subject ->
        val rate = parseSensorRate(prefs[Keys.recordRateHz(subject)]) ?: return@mapNotNull null
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

/** Newline-delimited, like the endpoints. Blank lines dropped so a trailing newline costs nothing. */
private fun parseRigEntityIds(stored: String?): Set<String> =
    stored?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

private fun <T : Enum<T>> List<T>.byName(stored: String?): T? =
    stored?.let { name -> firstOrNull { it.name == name } }

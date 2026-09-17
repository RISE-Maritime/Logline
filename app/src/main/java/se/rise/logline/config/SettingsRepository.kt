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
import se.rise.logline.calibrate.PlatformCalibration
import se.rise.logline.calibrate.PlatformZero
import se.rise.logline.calibrate.SensorMount
import se.rise.logline.calibrate.SensorType
import se.rise.logline.calibrate.toStoredJson
import se.rise.logline.calibrate.Vec3M
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.SubjectQos
import se.rise.logline.record.encodeTags
import se.rise.logline.record.parseTags
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
    val RECORDINGS_FOLDER_URI = stringPreferencesKey("recordings_folder_uri")
    val START_ON_BOOT = stringPreferencesKey("start_on_boot")
    val OFFLINE_TILES_ONLY = stringPreferencesKey("offline_tiles_only")
    val THEME = stringPreferencesKey("theme")
    val MAPTILER_KEY = stringPreferencesKey("maptiler_key")
    val PUBLISH_ENABLED = stringPreferencesKey("publish_enabled")
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
    val CAMERA_ENTITY = stringPreferencesKey("camera_entity")
    val CAMERA_RESPONDER = stringPreferencesKey("camera_responder")
    val CAMERA_PATH = stringPreferencesKey("camera_path")
    val CAMERA_STUN = stringPreferencesKey("camera_stun")
    val CAMERA_TURN = stringPreferencesKey("camera_turn")
    val CAMERA_TURN_USER = stringPreferencesKey("camera_turn_user")
    val CAMERA_TURN_PASSWORD = stringPreferencesKey("camera_turn_password")
    val TAGS = stringPreferencesKey("tags")
    val ACTIVE_TAGS = stringPreferencesKey("active_tags")

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
    val MINIMUM_MODE = booleanPreferencesKey("minimum_mode")
    val PUBLISH_ALL_MAX = booleanPreferencesKey("publish_all_max")

    val CALIBRATION_SOURCE = stringPreferencesKey("calibration_source")

    // The platform library. One platform per key, each holding the whole document as JSON.
    //
    // A flat key per field, the way everything else here is stored, was what the single platform used — and
    // it does not survive becoming a list. Two nested indices (platform, then sensor) mean a clear-out that
    // has to walk both, and that clear-out is the part that has already been got wrong once: deleting a
    // sensor without removing its indices resurrects it at the next start. One string per platform turns
    // that into a single loop, and the serialiser it needs already exists.
    val PLATFORM_COUNT = stringPreferencesKey("platform_count")
    fun platform(index: Int) = stringPreferencesKey("platform_$index")

    /** Entity id of the platform the phone is on. Absent means none is selected. */
    val PLATFORM_ACTIVE_ENTITY = stringPreferencesKey("platform_active_entity")

    /**
     * Entity ids of the platforms opted in to publishing, newline-delimited — the same shape
     * [ROUTER_ENDPOINT] uses, for the same reason: a list in a string-keyed store.
     */
    val PLATFORM_PUBLISHING = stringPreferencesKey("platform_publishing")
    val PLATFORM_SHARE_LIBRARY = stringPreferencesKey("platform_share_library")
    val PLATFORM_REGISTRY_VERSION = stringPreferencesKey("platform_registry_version")
    val PLATFORM_REGISTRY_ORIGIN = stringPreferencesKey("platform_registry_origin")

    // The same six keys under their previous names — **read-only, and kept only for the migration**.
    //
    // The word was `rig` until a platform library made it plainly the wrong one: keelson calls the thing
    // a *platform*, `entity_id` is "normally the platform name", and one word for one thing is cheaper
    // than a translation everybody has to hold in their head. Renaming the DataStore keys with it means a
    // phone updating over an existing install would otherwise find no library at all and silently stop
    // publishing geometry that was going out before — the one thing a rename must not do. `writePlatforms`
    // clears these on the next save, so this is one-way, exactly like the `calib_*` keys below.
    val LEGACY_RIG_COUNT = stringPreferencesKey("rig_count")
    fun legacyRig(index: Int) = stringPreferencesKey("rig_$index")
    val LEGACY_RIG_ACTIVE_ENTITY = stringPreferencesKey("rig_active_entity")
    val LEGACY_RIG_PUBLISHING = stringPreferencesKey("rig_publishing")
    val LEGACY_RIG_SHARE_LIBRARY = stringPreferencesKey("rig_share_library")
    val LEGACY_RIG_REGISTRY_VERSION = stringPreferencesKey("rig_registry_version")
    val LEGACY_RIG_REGISTRY_ORIGIN = stringPreferencesKey("rig_registry_origin")

    // The single platform, flattened — **read-only now, and kept only for the migration**. A preferences
    // file written by an older build has these and no `platform_count`; `readPlatforms` turns them into a
    // one-platform library and the next write replaces them. Nothing writes them any more.

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
    val platforms = readPlatforms(prefs)
    return Settings(
        realm = prefs[Keys.REALM] ?: Settings.DEFAULT_REALM,
        entityId = prefs[Keys.ENTITY_ID] ?: defaultEntityId,
        routerEndpoints = parseEndpoints(prefs[Keys.ROUTER_ENDPOINT]),
        locationSource = prefs[Keys.LOCATION_SOURCE] ?: Settings.DEFAULT_LOCATION_SOURCE,
        imuSource = prefs[Keys.IMU_SOURCE] ?: Settings.DEFAULT_IMU_SOURCE,
        deviceSource = prefs[Keys.DEVICE_SOURCE] ?: Settings.DEFAULT_DEVICE_SOURCE,
        calibrationSource = prefs[Keys.CALIBRATION_SOURCE]?.takeIf { it.isNotBlank() }
            ?: Settings.DEFAULT_CALIBRATION_SOURCE,
        platforms = platforms,
        // `?:` on the raw key, not `ifBlank`: an **absent** key is a file from before the library
        // and needs its one platform nominated, while a **present but empty** one is a selection somebody
        // cleared by deleting the active platform. Treating those the same re-activates a surviving platform on
        // the next launch — and since the active platform always publishes, its geometry starts going out
        // under its own entity id with nobody having asked. Same rule as the annotation buttons: an
        // absent key and an empty value mean different things.
        activePlatformEntityId = prefs[Keys.PLATFORM_ACTIVE_ENTITY]
            ?: prefs[Keys.LEGACY_RIG_ACTIVE_ENTITY]
            ?: migratedActivePlatform(platforms),
        publishingPlatformEntityIds =
            parsePlatformEntityIds(prefs[Keys.PLATFORM_PUBLISHING] ?: prefs[Keys.LEGACY_RIG_PUBLISHING]),
        // Absent means off, as for the checklist: only a stored value shares the library.
        sharePlatformLibrary = (prefs[Keys.PLATFORM_SHARE_LIBRARY]
            ?: prefs[Keys.LEGACY_RIG_SHARE_LIBRARY])?.toBooleanStrictOrNull() ?: false,
        platformRegistryVersion = (prefs[Keys.PLATFORM_REGISTRY_VERSION]
            ?: prefs[Keys.LEGACY_RIG_REGISTRY_VERSION])?.toLongOrNull() ?: 0L,
        platformRegistryOrigin = (prefs[Keys.PLATFORM_REGISTRY_ORIGIN]
            ?: prefs[Keys.LEGACY_RIG_REGISTRY_ORIGIN]).orEmpty(),
        recordingEnabled = prefs[Keys.RECORDING_ENABLED]?.toBooleanStrictOrNull() ?: true,
        backfillEnabled = prefs[Keys.BACKFILL_ENABLED]?.toBooleanStrictOrNull() ?: true,
        batteryExemptionAsked = prefs[Keys.BATTERY_EXEMPTION_ASKED]?.toBooleanStrictOrNull() ?: false,
        recordingsFolderUri = prefs[Keys.RECORDINGS_FOLDER_URI].orEmpty(),
        startOnBoot = prefs[Keys.START_ON_BOOT]?.toBooleanStrictOrNull() ?: false,
    offlineTilesOnly = prefs[Keys.OFFLINE_TILES_ONLY]?.toBooleanStrictOrNull() ?: false,
        // An unrecognised value follows the phone rather than throwing — the same stance
        // `readQosOverrides` takes, since a stale preference should not cost every other setting.
        theme = ThemeChoice.entries.byName(prefs[Keys.THEME]) ?: ThemeChoice.System,
    mapTilerKey = prefs[Keys.MAPTILER_KEY].orEmpty(),
    publishEnabled = prefs[Keys.PUBLISH_ENABLED]?.toBooleanStrictOrNull() ?: true,
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
        // Absent and empty mean the same for tags, unlike the annotation buttons: there is no default
        // vocabulary to fall back to, so nothing has to tell them apart.
        cameraEntityId = prefs[Keys.CAMERA_ENTITY].orEmpty(),
        cameraResponderId = prefs[Keys.CAMERA_RESPONDER] ?: Settings.DEFAULT_CAMERA_RESPONDER,
        cameraPath = prefs[Keys.CAMERA_PATH].orEmpty(),
        // Absent means the shipped STUN server; empty means somebody cleared it on purpose, which is
        // right on a LAN where host candidates are enough and a STUN round trip only delays gathering.
        cameraStunUrl = prefs[Keys.CAMERA_STUN] ?: Settings.DEFAULT_STUN_URL,
        cameraTurnUrl = prefs[Keys.CAMERA_TURN].orEmpty(),
        cameraTurnUsername = prefs[Keys.CAMERA_TURN_USER].orEmpty(),
        cameraTurnPassword = prefs[Keys.CAMERA_TURN_PASSWORD].orEmpty(),
        tags = prefs[Keys.TAGS]?.let(::parseTags)?.toList().orEmpty(),
        activeTags = prefs[Keys.ACTIVE_TAGS]?.let(::parseTags).orEmpty(),
        qosOverrides = readQosOverrides(prefs),
        sensorRates = readSensorRates(prefs),
        recordRates = readRecordRates(prefs),
        // Absent means the shipped default, and for recording that is **maximum** — the file is what
        // analysis is run against. Reading it as false here would quietly contradict `Settings`' own
        // default, which is exactly what it did: a fresh install showed "Configured" on the Session
        // screen while the data class said otherwise.
        recordAllMax = prefs[Keys.RECORD_ALL_MAX] ?: true,
        minimumMode = prefs[Keys.MINIMUM_MODE] ?: false,
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
    writePlatforms(prefs, settings)
    prefs[Keys.RECORDING_ENABLED] = settings.recordingEnabled.toString()
    prefs[Keys.BACKFILL_ENABLED] = settings.backfillEnabled.toString()
    prefs[Keys.BATTERY_EXEMPTION_ASKED] = settings.batteryExemptionAsked.toString()
    prefs[Keys.RECORDINGS_FOLDER_URI] = settings.recordingsFolderUri
    prefs[Keys.START_ON_BOOT] = settings.startOnBoot.toString()
    prefs[Keys.OFFLINE_TILES_ONLY] = settings.offlineTilesOnly.toString()
    prefs[Keys.THEME] = settings.theme.name
    prefs[Keys.MAPTILER_KEY] = settings.mapTilerKey
    prefs[Keys.PUBLISH_ENABLED] = settings.publishEnabled.toString()
    prefs[Keys.SCOUT_ADDRESS] = settings.scoutAddress
    prefs[Keys.AUDIO_ENABLED] = settings.audioEnabled.toString()
    prefs[Keys.AUDIO_SAMPLE_RATE] = settings.audioSampleRateHz.toString()
    prefs[Keys.AUDIO_CHANNELS] = settings.audioChannels.toString()
    prefs[Keys.CHECKLIST_ENABLED] = settings.checklistEnabled.toString()
    prefs[Keys.RECORD_ALL_MAX] = settings.recordAllMax
    prefs[Keys.MINIMUM_MODE] = settings.minimumMode
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
    prefs[Keys.CAMERA_ENTITY] = settings.cameraEntityId
    prefs[Keys.CAMERA_RESPONDER] = settings.cameraResponderId
    prefs[Keys.CAMERA_PATH] = settings.cameraPath
    prefs[Keys.CAMERA_STUN] = settings.cameraStunUrl
    prefs[Keys.CAMERA_TURN] = settings.cameraTurnUrl
    prefs[Keys.CAMERA_TURN_USER] = settings.cameraTurnUsername
    prefs[Keys.CAMERA_TURN_PASSWORD] = settings.cameraTurnPassword
    prefs[Keys.TAGS] = encodeTags(settings.tags.toSet())
    // Only tags that still exist can be active, or removing one would leave it switched on for ever.
    prefs[Keys.ACTIVE_TAGS] = encodeTags(settings.activeTags.filter { it in settings.tags }.toSet())
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
 * The platform calibration, or null when nothing has been calibrated.
 *
 * The name is the presence flag: no name, no calibration. Everything else has a sensible fallback, so
 * a preferences file half-written by a crash yields a usable platform rather than an exception at startup —
 * the same stance [readQosOverrides] takes.
 */
internal fun readCalibration(prefs: Preferences): PlatformCalibration? {
    val name = prefs[Keys.CALIB_NAME]?.takeIf { it.isNotBlank() } ?: return null
    return PlatformCalibration(
        name = name,
        entityId = prefs[Keys.CALIB_ENTITY_ID]?.takeIf { it.isNotBlank() }
            ?: se.rise.logline.calibrate.defaultEntityId(name),
        parentFrameId = prefs[Keys.CALIB_PARENT_FRAME_ID]?.takeIf { it.isNotBlank() }
            ?: se.rise.logline.calibrate.defaultParentFrameId(name),
        // `fromStoredName` rather than `byName`: this key holds the *enum constant* name, and two of
        // them were renamed with the wire values in `0.6.0-pre.18`. A phone still on the `calib_*`
        // keys stored `LANDKRABBA`, and this is the one read that will ever see it.
        platformType = PlatformType.fromStoredName(prefs[Keys.CALIB_PLATFORM_TYPE]),
        description = prefs[Keys.CALIB_DESCRIPTION].orEmpty(),
        lengthOverAllM = prefs[Keys.CALIB_LOA_M]?.toDoubleOrNull(),
        breadthOverAllM = prefs[Keys.CALIB_BOA_M]?.toDoubleOrNull(),
        ccrp = Vec3M(
            x = prefs[Keys.CALIB_CCRP_X]?.toDoubleOrNull() ?: 0.0,
            y = prefs[Keys.CALIB_CCRP_Y]?.toDoubleOrNull() ?: 0.0,
            z = prefs[Keys.CALIB_CCRP_Z]?.toDoubleOrNull() ?: 0.0,
        ),
        zero = readPlatformZero(prefs),
        sensors = readSensorMounts(prefs),
        updatedAtEpochMillis = prefs[Keys.CALIB_UPDATED_AT]?.toLongOrNull() ?: 0L,
    )
}

/**
 * The surveyed origin, or null.
 *
 * Latitude and longitude together are the presence flag, and both must parse: a zero with one of them
 * missing would place the platform on the equator or the Greenwich meridian, which is a real place and a
 * completely wrong answer.
 */
private fun readPlatformZero(prefs: Preferences): PlatformZero? {
    val lat = prefs[Keys.CALIB_ZERO_LAT]?.toDoubleOrNull() ?: return null
    val lon = prefs[Keys.CALIB_ZERO_LON]?.toDoubleOrNull() ?: return null
    return PlatformZero(
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
 * platform's origin, which is a plausible-looking lie.
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
 * Persist the platform library, clearing whatever a longer one left behind.
 *
 * The clear-out is the part that matters, and it is why a platform is one key rather than a spray of them:
 * writing a two-platform library over a five-platform one has to remove indices two, three and four, and with a
 * flat scheme that meant walking every field of every sensor of every removed platform. Miss any of it and
 * the deleted platform comes back at the next start — present in the file, absent from the screen, and on
 * the bus.
 *
 * The single-platform `calib_*` keys are removed here too, once, so a migrated file does not carry a stale
 * copy of the platform it was migrated from.
 */
internal fun writePlatforms(prefs: MutablePreferences, settings: Settings) {
    val previousCount = prefs[Keys.PLATFORM_COUNT]?.toIntOrNull() ?: 0
    settings.platforms.forEachIndexed { i, platform -> prefs[Keys.platform(i)] = platform.toStoredJson() }
    for (i in settings.platforms.size until previousCount) prefs.remove(Keys.platform(i))
    prefs[Keys.PLATFORM_COUNT] = settings.platforms.size.toString()
    prefs[Keys.PLATFORM_ACTIVE_ENTITY] = settings.activePlatformEntityId
    prefs[Keys.PLATFORM_PUBLISHING] = settings.publishingPlatformEntityIds.sorted().joinToString("\n")
    prefs[Keys.PLATFORM_SHARE_LIBRARY] = settings.sharePlatformLibrary.toString()
    prefs[Keys.PLATFORM_REGISTRY_VERSION] = settings.platformRegistryVersion.toString()
    prefs[Keys.PLATFORM_REGISTRY_ORIGIN] = settings.platformRegistryOrigin
    if (prefs[Keys.CALIB_NAME] != null) clearLegacyCalibration(prefs)
    if (prefs[Keys.LEGACY_RIG_COUNT] != null) clearLegacyPlatformKeys(prefs)
}

/**
 * The platform library, or the migrated single platform, or nothing.
 *
 * Three schemes, newest first, and **a count present is authoritative even when it says zero** — that is
 * a library somebody emptied, and falling back to an older scheme there would resurrect the platforms
 * they deleted. Only a count's *absence* means the file predates that scheme: no `platform_count` falls
 * through to `rig_count`, the same library under the name it had before platforms were called platforms,
 * and no `rig_count` either means the file predates the library entirely, in which case today's single
 * calibration becomes a one-platform library; [readSettings] takes the active platform from
 * [Keys.PLATFORM_ACTIVE_ENTITY], which is likewise absent, so `activePlatform()` is null until the next write —
 * hence the migration also has to nominate it, which it does in [migrateActivePlatform].
 *
 * A platform whose JSON will not parse is skipped rather than failing the whole read, the same stance
 * [readQosOverrides] takes: one corrupt entry should cost one platform, not every setting on the phone.
 */
internal fun readPlatforms(prefs: Preferences): List<PlatformCalibration> {
    prefs[Keys.PLATFORM_COUNT]?.toIntOrNull()?.let { count ->
        return (0 until count).mapNotNull { i ->
            prefs[Keys.platform(i)]?.let { parsePlatformGeometry(it) }
        }
    }
    prefs[Keys.LEGACY_RIG_COUNT]?.toIntOrNull()?.let { count ->
        return (0 until count).mapNotNull { i ->
            prefs[Keys.legacyRig(i)]?.let { parsePlatformGeometry(it) }
        }
    }
    return listOfNotNull(readCalibration(prefs))
}

/**
 * Which platform a freshly migrated file should have selected.
 *
 * A file written by an older build has one platform and no stored selection, and leaving it unselected
 * would silently stop publishing geometry that was publishing before the update — the one thing a
 * migration must not do. Reached **only** when [Keys.PLATFORM_ACTIVE_ENTITY] is absent; see the call site
 * for why a present-but-empty value must not come here.
 */
internal fun migratedActivePlatform(platforms: List<PlatformCalibration>): String =
    platforms.singleOrNull()?.entityId.orEmpty()

/**
 * Drops the `rig_*` keys once the library has been written under its own names.
 *
 * The indexed entries are counted from `rig_count` rather than from the library that was just saved:
 * merging a shared library can leave fewer platforms than the file held, and the leftovers would sit
 * there forever otherwise. One-way, like [clearLegacyCalibration] — an older APK installed over this
 * one sees no platforms.
 */
private fun clearLegacyPlatformKeys(prefs: MutablePreferences) {
    val previousCount = prefs[Keys.LEGACY_RIG_COUNT]?.toIntOrNull() ?: 0
    for (i in 0 until previousCount) prefs.remove(Keys.legacyRig(i))
    listOf(
        Keys.LEGACY_RIG_COUNT, Keys.LEGACY_RIG_ACTIVE_ENTITY, Keys.LEGACY_RIG_PUBLISHING,
        Keys.LEGACY_RIG_SHARE_LIBRARY, Keys.LEGACY_RIG_REGISTRY_VERSION,
        Keys.LEGACY_RIG_REGISTRY_ORIGIN,
    ).forEach { prefs.remove(it) }
}

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
private fun parsePlatformEntityIds(stored: String?): Set<String> =
    stored?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

private fun <T : Enum<T>> List<T>.byName(stored: String?): T? =
    stored?.let { name -> firstOrNull { it.name == name } }

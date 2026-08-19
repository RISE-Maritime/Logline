package se.rise.logline.config

import io.zenoh.qos.CongestionControl
import io.zenoh.qos.Priority
import io.zenoh.qos.Reliability
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import se.rise.logline.keelson.SubjectQos
import se.rise.logline.sensors.parseSensorRate
import se.rise.logline.sensors.serialise

/** Bumped when a field's *meaning* changes; adding one does not need it — see [PROFILE_JSON]. */
const val SETTINGS_PROFILE_VERSION = 1

/**
 * Settings that can be copied from one phone to another.
 *
 * **A profile is a fleet configuration, not a device clone**, and the whole point of this class is the
 * short list of things it refuses to carry. Five fields stay behind:
 *
 * * [Settings.entityId] names *this hardware* as a source of data. Two phones sharing one publish on
 *   byte-identical keys, and their samples interleave on the bus with nothing to tell them apart.
 * * [Settings.operatorId] is what de-duplicates this phone's own presence heartbeat coming back on the
 *   wildcard subscription. Two phones sharing one each read the other's heartbeat as their own.
 * * [Settings.rigRegistryOrigin] does the same job for the rig library: without a distinct origin a
 *   phone applies its own library back over itself on every reconnect and the version ratchets.
 * * [Settings.rigRegistryVersion] is sync bookkeeping. An imported version would claim a place in the
 *   last-writer-wins ordering that this phone has not earned.
 * * [Settings.batteryExemptionAsked] records that *this* device was asked a question. A new phone
 *   should still be asked it.
 *
 * All three identities are documented in `Settings` as "generated once and never changed", which is
 * exactly the property that makes copying them a bug rather than a convenience.
 *
 * The rig library is out too, for a different reason: it already has two sharing paths — over the bus
 * via `shareRigLibrary`, and as a platform-geometry document — and a third would bring its own
 * version-ordering questions.
 *
 * Composite fields carry **the same strings DataStore stores**, produced by the same serialisers, so
 * the file and the stored form cannot drift and both round-trip through one set of parsers.
 */
data class SettingsProfile(
    val version: Int = SETTINGS_PROFILE_VERSION,

    // ---- the connection: what a QR carries -------------------------------------------------------
    val realm: String? = null,
    /** Newline-delimited, exactly as stored — see `serialiseEndpoints`. */
    val routerEndpoints: String? = null,
    val scoutAddress: String? = null,
    val locationSource: String? = null,
    val imuSource: String? = null,
    val deviceSource: String? = null,
    val calibrationSource: String? = null,

    // ---- what the phone records, and how ----------------------------------------------------------
    val recordingEnabled: Boolean? = null,
    val backfillEnabled: Boolean? = null,
    val startOnBoot: Boolean? = null,
    val offlineTilesOnly: Boolean? = null,
    val audioEnabled: Boolean? = null,
    val audioSampleRateHz: Int? = null,
    val audioChannels: Int? = null,
    val cameraEnabled: Boolean? = null,
    val cameraLensFront: Boolean? = null,
    val cameraWidth: Int? = null,
    val cameraHeight: Int? = null,
    val videoEnabled: Boolean? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoBitrateKbps: Int? = null,
    val videoKeyframeSeconds: Int? = null,

    // ---- per-subject configuration ----------------------------------------------------------------
    /** Registry entry names, newline-delimited, as `serialiseDisabledSubjects` writes them. */
    val disabledSubjects: String? = null,
    /** Subject name to the stored rate string (`"MAX"` or a number). */
    val sensorRates: Map<String, String>? = null,
    val qosOverrides: Map<String, QosProfileEntry>? = null,
    /** One serialised button per line, as `serialiseAnnotationButtons` writes them. */
    val annotationButtons: String? = null,

    // ---- the operator, minus the id ---------------------------------------------------------------
    val checklistEnabled: Boolean? = null,
    val checklistRealm: String? = null,
    val checklistEntityId: String? = null,
    val operatorName: String? = null,
    val operatorRole: String? = null,
    val rocSiteId: String? = null,
) {
    /** Whether this profile says anything about who is operating the phone. */
    val hasOperator: Boolean
        get() = !operatorName.isNullOrBlank() || !operatorRole.isNullOrBlank() || !rocSiteId.isNullOrBlank()
}

/** A QoS override, as four stored strings — the enums are Zenoh's and have no serialiser here. */
data class QosProfileEntry(
    val priority: String,
    val congestion: String,
    val reliability: String,
    val express: Boolean,
)

/**
 * Built and read with the **runtime** JSON API, not `@Serializable`.
 *
 * There is no kotlinx-serialization compiler plugin on this project — deliberately, as
 * `app/build.gradle.kts` says: the dependency is there because zenoh-kotlin already pulls it in at
 * runtime, and declaring it only made an existing transitive honest. Annotating a class would compile
 * perfectly and then throw `SerializationException` on the first export, because the plugin is what
 * generates the serialiser. So the object is assembled by hand, the way `PlatformGeometryParse` reads
 * one.
 *
 * A field that is absent stays absent rather than being written as null, which is what lets one reader
 * serve both a full profile and the QR's connection subset: what is not there is what the importer
 * leaves alone.
 */
private val json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/** The profile as a JSON document, ready to write to a file or squeeze into a QR. */
fun SettingsProfile.encode(pretty: Boolean = true): String {
    val obj = buildJsonObject {
        put("version", JsonPrimitive(version))
        putIfPresent("realm", realm)
        putIfPresent("router_endpoints", routerEndpoints)
        putIfPresent("scout_address", scoutAddress)
        putIfPresent("location_source", locationSource)
        putIfPresent("imu_source", imuSource)
        putIfPresent("device_source", deviceSource)
        putIfPresent("calibration_source", calibrationSource)
        putIfPresent("recording_enabled", recordingEnabled)
        putIfPresent("backfill_enabled", backfillEnabled)
        putIfPresent("start_on_boot", startOnBoot)
        putIfPresent("offline_tiles_only", offlineTilesOnly)
        putIfPresent("audio_enabled", audioEnabled)
        putIfPresent("audio_sample_rate_hz", audioSampleRateHz)
        putIfPresent("audio_channels", audioChannels)
        putIfPresent("camera_enabled", cameraEnabled)
        putIfPresent("camera_lens_front", cameraLensFront)
        putIfPresent("camera_width", cameraWidth)
        putIfPresent("camera_height", cameraHeight)
        putIfPresent("video_enabled", videoEnabled)
        putIfPresent("video_width", videoWidth)
        putIfPresent("video_height", videoHeight)
        putIfPresent("video_bitrate_kbps", videoBitrateKbps)
        putIfPresent("video_keyframe_seconds", videoKeyframeSeconds)
        putIfPresent("disabled_subjects", disabledSubjects)
        sensorRates?.takeIf { it.isNotEmpty() }?.let { rates ->
            put(
                "sensor_rates",
                buildJsonObject { rates.toSortedMap().forEach { (k, v) -> put(k, JsonPrimitive(v)) } },
            )
        }
        qosOverrides?.takeIf { it.isNotEmpty() }?.let { overrides ->
            put(
                "qos_overrides",
                buildJsonObject {
                    overrides.toSortedMap().forEach { (subject, entry) ->
                        put(
                            subject,
                            buildJsonObject {
                                put("priority", JsonPrimitive(entry.priority))
                                put("congestion", JsonPrimitive(entry.congestion))
                                put("reliability", JsonPrimitive(entry.reliability))
                                put("express", JsonPrimitive(entry.express))
                            },
                        )
                    }
                },
            )
        }
        putIfPresent("annotation_buttons", annotationButtons)
        putIfPresent("checklist_enabled", checklistEnabled)
        putIfPresent("checklist_realm", checklistRealm)
        putIfPresent("checklist_entity_id", checklistEntityId)
        putIfPresent("operator_name", operatorName)
        putIfPresent("operator_role", operatorRole)
        putIfPresent("roc_site_id", rocSiteId)
    }
    // Compact for a QR, where every byte is a module: the same document, no whitespace.
    return if (pretty) json.encodeToString(JsonObject.serializer(), obj) else obj.toString()
}

/**
 * Read a profile, or null when the text is not one.
 *
 * Unknown keys are ignored by construction — nothing asks for them — so a file written by a later
 * build applies minus what this one does not know. An unreadable value is treated as absent rather
 * than failing the import: one bad rate should cost that subject's rate, not the whole profile.
 */
fun parseSettingsProfile(text: String): SettingsProfile? {
    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    return SettingsProfile(
        version = root.int("version") ?: SETTINGS_PROFILE_VERSION,
        realm = root.string("realm"),
        routerEndpoints = root.string("router_endpoints"),
        scoutAddress = root.string("scout_address"),
        locationSource = root.string("location_source"),
        imuSource = root.string("imu_source"),
        deviceSource = root.string("device_source"),
        calibrationSource = root.string("calibration_source"),
        recordingEnabled = root.bool("recording_enabled"),
        backfillEnabled = root.bool("backfill_enabled"),
        startOnBoot = root.bool("start_on_boot"),
        offlineTilesOnly = root.bool("offline_tiles_only"),
        audioEnabled = root.bool("audio_enabled"),
        audioSampleRateHz = root.int("audio_sample_rate_hz"),
        audioChannels = root.int("audio_channels"),
        cameraEnabled = root.bool("camera_enabled"),
        cameraLensFront = root.bool("camera_lens_front"),
        cameraWidth = root.int("camera_width"),
        cameraHeight = root.int("camera_height"),
        videoEnabled = root.bool("video_enabled"),
        videoWidth = root.int("video_width"),
        videoHeight = root.int("video_height"),
        videoBitrateKbps = root.int("video_bitrate_kbps"),
        videoKeyframeSeconds = root.int("video_keyframe_seconds"),
        disabledSubjects = root.string("disabled_subjects"),
        sensorRates = (root["sensor_rates"] as? JsonObject)
            ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
            ?.toMap(),
        qosOverrides = (root["qos_overrides"] as? JsonObject)
            ?.mapNotNull { (subject, value) ->
                val entry = (value as? JsonObject) ?: return@mapNotNull null
                val priority = entry.string("priority") ?: return@mapNotNull null
                val congestion = entry.string("congestion") ?: return@mapNotNull null
                val reliability = entry.string("reliability") ?: return@mapNotNull null
                val express = entry.bool("express") ?: return@mapNotNull null
                subject to QosProfileEntry(priority, congestion, reliability, express)
            }
            ?.toMap(),
        annotationButtons = root.string("annotation_buttons"),
        checklistEnabled = root.bool("checklist_enabled"),
        checklistRealm = root.string("checklist_realm"),
        checklistEntityId = root.string("checklist_entity_id"),
        operatorName = root.string("operator_name"),
        operatorRole = root.string("operator_role"),
        rocSiteId = root.string("roc_site_id"),
    )
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun kotlinx.serialization.json.JsonObjectBuilder.putIfPresent(key: String, value: String?) {
    if (!value.isNullOrEmpty()) put(key, JsonPrimitive(value))
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putIfPresent(key: String, value: Boolean?) {
    if (value != null) put(key, JsonPrimitive(value))
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putIfPresent(key: String, value: Int?) {
    if (value != null) put(key, JsonPrimitive(value))
}

/** Everything this phone can pass on. */
fun Settings.toProfile(): SettingsProfile = SettingsProfile(
    realm = realm,
    routerEndpoints = routerEndpoints.serialiseEndpoints(),
    scoutAddress = scoutAddress,
    locationSource = locationSource,
    imuSource = imuSource,
    deviceSource = deviceSource,
    calibrationSource = calibrationSource,
    recordingEnabled = recordingEnabled,
    backfillEnabled = backfillEnabled,
    startOnBoot = startOnBoot,
    offlineTilesOnly = offlineTilesOnly,
    audioEnabled = audioEnabled,
    audioSampleRateHz = audioSampleRateHz,
    audioChannels = audioChannels,
    cameraEnabled = cameraEnabled,
    cameraLensFront = cameraLensFront,
    cameraWidth = cameraWidth,
    cameraHeight = cameraHeight,
    videoEnabled = videoEnabled,
    videoWidth = videoWidth,
    videoHeight = videoHeight,
    videoBitrateKbps = videoBitrateKbps,
    videoKeyframeSeconds = videoKeyframeSeconds,
    disabledSubjects = disabledSubjects.serialiseDisabledSubjects(),
    sensorRates = sensorRates.mapValues { (_, rate) -> rate.serialise() }.ifEmpty { null },
    qosOverrides = qosOverrides.mapValues { (_, qos) ->
        QosProfileEntry(
            priority = qos.priority.name,
            congestion = qos.congestionControl.name,
            reliability = qos.reliability.name,
            express = qos.express,
        )
    }.ifEmpty { null },
    annotationButtons = annotationButtons.serialiseAnnotationButtons(),
    checklistEnabled = checklistEnabled,
    checklistRealm = checklistRealm,
    checklistEntityId = checklistEntityId,
    operatorName = operatorName,
    operatorRole = operatorRole,
    rocSiteId = rocSiteId,
)

/**
 * Just the part that fits in a QR: how to reach the bus and what to call the sources.
 *
 * The half that is identical across a fleet and tedious to type. Switches, rates, QoS overrides and
 * annotation buttons are file-only — a QR holds a few hundred bytes and the full profile is kilobytes,
 * so trying to squeeze them in would produce a code that will not scan rather than one that carries
 * less.
 */
fun Settings.toConnectionProfile(): SettingsProfile = SettingsProfile(
    realm = realm,
    routerEndpoints = routerEndpoints.serialiseEndpoints(),
    scoutAddress = scoutAddress,
    locationSource = locationSource,
    imuSource = imuSource,
    deviceSource = deviceSource,
    calibrationSource = calibrationSource,
)

/**
 * Apply a profile to this phone's settings.
 *
 * **Every field is optional and an absent one is left alone**, which is what lets the QR's connection
 * subset and a full file go through the same path. The five excluded fields are not represented in
 * [SettingsProfile] at all, so this cannot overwrite them even by mistake — the absence is structural
 * rather than a rule to remember here.
 *
 * @param withOperator false to drop the operator's name, role and site — one export provisioning a
 *   fleet should not make five phones claim the same person.
 */
fun Settings.applyProfile(profile: SettingsProfile, withOperator: Boolean = true): Settings = copy(
    realm = profile.realm?.takeIf { it.isNotBlank() } ?: realm,
    routerEndpoints = profile.routerEndpoints?.let { parseEndpoints(it) } ?: routerEndpoints,
    scoutAddress = profile.scoutAddress?.takeIf { it.isNotBlank() } ?: scoutAddress,
    locationSource = profile.locationSource?.takeIf { it.isNotBlank() } ?: locationSource,
    imuSource = profile.imuSource?.takeIf { it.isNotBlank() } ?: imuSource,
    deviceSource = profile.deviceSource?.takeIf { it.isNotBlank() } ?: deviceSource,
    calibrationSource = profile.calibrationSource?.takeIf { it.isNotBlank() } ?: calibrationSource,
    recordingEnabled = profile.recordingEnabled ?: recordingEnabled,
    backfillEnabled = profile.backfillEnabled ?: backfillEnabled,
    startOnBoot = profile.startOnBoot ?: startOnBoot,
    offlineTilesOnly = profile.offlineTilesOnly ?: offlineTilesOnly,
    audioEnabled = profile.audioEnabled ?: audioEnabled,
    audioSampleRateHz = profile.audioSampleRateHz ?: audioSampleRateHz,
    audioChannels = profile.audioChannels ?: audioChannels,
    cameraEnabled = profile.cameraEnabled ?: cameraEnabled,
    cameraLensFront = profile.cameraLensFront ?: cameraLensFront,
    cameraWidth = profile.cameraWidth ?: cameraWidth,
    cameraHeight = profile.cameraHeight ?: cameraHeight,
    videoEnabled = profile.videoEnabled ?: videoEnabled,
    videoWidth = profile.videoWidth ?: videoWidth,
    videoHeight = profile.videoHeight ?: videoHeight,
    videoBitrateKbps = profile.videoBitrateKbps ?: videoBitrateKbps,
    videoKeyframeSeconds = profile.videoKeyframeSeconds ?: videoKeyframeSeconds,
    disabledSubjects = profile.disabledSubjects?.let { parseDisabledSubjects(it) } ?: disabledSubjects,
    sensorRates = profile.sensorRates
        ?.mapNotNull { (subject, stored) -> parseSensorRate(stored)?.let { subject to it } }
        ?.toMap()
        ?: sensorRates,
    qosOverrides = profile.qosOverrides
        ?.mapNotNull { (subject, entry) -> entry.toSubjectQos()?.let { subject to it } }
        ?.toMap()
        ?: qosOverrides,
    annotationButtons = profile.annotationButtons
        ?.split('\n')
        ?.mapNotNull { parseAnnotationButton(it) }
        ?: annotationButtons,
    checklistEnabled = profile.checklistEnabled ?: checklistEnabled,
    checklistRealm = profile.checklistRealm?.takeIf { it.isNotBlank() } ?: checklistRealm,
    checklistEntityId = profile.checklistEntityId?.takeIf { it.isNotBlank() } ?: checklistEntityId,
    operatorName = if (withOperator) profile.operatorName ?: operatorName else operatorName,
    operatorRole = if (withOperator) profile.operatorRole ?: operatorRole else operatorRole,
    rocSiteId = if (withOperator) profile.rocSiteId ?: rocSiteId else rocSiteId,
)

/**
 * A stored QoS override, or null if any part of it is unreadable.
 *
 * All four or none: a half-applied override would be a combination nobody chose, and the subject is
 * better off following upstream policy than following three-quarters of an intention.
 */
private fun QosProfileEntry.toSubjectQos(): SubjectQos? {
    val priority = Priority.entries.firstOrNull { it.name == this.priority } ?: return null
    val congestion = CongestionControl.entries.firstOrNull { it.name == this.congestion } ?: return null
    val reliability = Reliability.entries.firstOrNull { it.name == this.reliability } ?: return null
    return SubjectQos(priority, congestion, reliability, express)
}

/**
 * Write this phone's profile to `Downloads/Logline`, and return the file name.
 *
 * Through the same `saveToDownloads` the recorder and the calibration export use — one place holds the
 * pending flag, the folder name and the failure mode, and a second copy would eventually disagree
 * about all three.
 */
fun exportSettingsProfile(context: android.content.Context, settings: Settings): String {
    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HHmmss", java.util.Locale.US)
        .format(java.util.Date())
    val name = "logline-settings-$stamp.json"
    val text = settings.toProfile().encode()
    se.rise.logline.record.saveToDownloads(context, name, "application/json") { out ->
        out.write(text.toByteArray())
    }
    return name
}

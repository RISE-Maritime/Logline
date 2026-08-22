package se.rise.logline.calibrate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Reading a platform-geometry document back into a [PlatformCalibration].
 *
 * The counterpart to `PlatformGeometryJson.kt`, and the first thing in this app that parses JSON at
 * all. Three separate paths need it: importing a file somebody exported, adopting a platform seen on
 * the bus, and reading the phone's own platform library back out of DataStore.
 *
 * **Why a library here when the writer is hand-rolled.** The writer emits a small fixed shape that is
 * only ever written, so a dependency would earn nothing — that argument still holds and it stays as it
 * is. This reads *foreign* input: escapes, surrogate pairs, exponents, arbitrary nesting, a document
 * somebody else's tool produced. That is a grammar rather than a frame, and the place to be strict is
 * a tested parser rather than one written here. `kotlinx-serialization-json` is already on the runtime
 * classpath — `zenoh-kotlin-android` pulls it in — so declaring it costs nothing that ships, and the
 * JVM unit tests exercise the same implementation the phone runs. Only the runtime API is used; there
 * is no compiler plugin and no `@Serializable` class.
 *
 * **Tolerant in exactly the way `readCalibration` is tolerant.** A transform missing its frame id or
 * any translation component is skipped rather than read as zeros, because a half-written entry would
 * otherwise appear on the bus as a sensor mounted exactly at the platform's origin — a plausible-looking
 * lie. Unknown keys are ignored, so a crowsnest registry entry (which carries `realm`, `queryables`,
 * `data_streams`, `camera_calibrations`) parses to the geometry it has in common with upstream.
 *
 * What is ignored is also **lost on a re-export**: this app models geometry, not a fleet's stream
 * inventory. The import screen says so rather than letting somebody discover it afterwards.
 */
private val json = Json {
    ignoreUnknownKeys = true
    isLenient = false
}

/**
 * Parse one platform-geometry document.
 *
 * Accepts upstream's strict shape, the wire variant with its `calibration` provenance block, the
 * stored variant with `entity_id`/`parent_frame_id`, and a crowsnest registry entry. Returns null when
 * the text is not an object, or when there is no way to name the platform — a document with no transforms
 * is a legitimate half-finished platform, not a parse failure.
 *
 * @param fallbackEntityId used when the document carries no `entity_id`: a registry keys its entries
 *   by entity id rather than storing it inside, and a strict export has nowhere to put it either.
 */
fun parsePlatformGeometry(text: String, fallbackEntityId: String? = null): PlatformCalibration? =
    text.asJsonObject()?.toPlatformCalibration(fallbackEntityId)

/**
 * Parse a document that may be one platform or an object of several keyed by entity id.
 *
 * Both are things a person will hand this app: a file exported per platform, and a crowsnest
 * `platform_registry.json`. Telling them apart is a question about the *values*, not the keys — a
 * registry's values are all objects, while a platform has `name`, `frame_transforms` and the rest at
 * the top level — so a document carrying none of a platform's own fields and nothing but objects is
 * read as a registry of them.
 */
fun parsePlatformDocument(text: String): List<PlatformCalibration> {
    val root = text.asJsonObject() ?: return emptyList()
    if (!root.looksLikeRegistry()) return listOfNotNull(root.toPlatformCalibration(null))
    return root.entries.mapNotNull { (key, value) ->
        (value as? JsonObject)?.toPlatformCalibration(key)
    }
}

/** Fields only a platform document has. Any of them present means this is one, not a registry. */
private val PLATFORM_FIELDS = listOf(
    "name", "frame_transforms", "platform_type", "ccrp_m", "entity_id",
    "length_over_all_m", "breadth_over_all_m",
)

private fun JsonObject.looksLikeRegistry(): Boolean {
    if (PLATFORM_FIELDS.any { it in this }) return false
    return isNotEmpty() && values.all { it is JsonObject }
}

private fun String.asJsonObject(): JsonObject? =
    runCatching { json.parseToJsonElement(this) as? JsonObject }.getOrNull()

private fun JsonObject.toPlatformCalibration(fallbackEntityId: String?): PlatformCalibration? {
    val entityId = (
        string("entity_id")
            ?: fallbackEntityId?.takeIf { it.isNotBlank() }
            ?: string("name")?.let { defaultEntityId(it) }
        )
        // Rejected rather than repaired. The id is interpolated straight into every key the platform
        // publishes on, so a `/` in it would silently add a chunk; slugifying instead would produce a
        // platform whose key no longer matches the one the station that sent it uses, which is worse than
        // not importing it. Crowsnest enforces the same shape, so a well-formed registry always
        // passes.
        ?.takeIf { isValidEntityId(it) }
        ?: return null
    val name = string("name") ?: entityId
    val transforms = this["frame_transforms"] as? JsonArray
    val parentFrameId = string("parent_frame_id")
        ?: transforms.firstParentFrameId()
        ?: defaultParentFrameId(name)
    val provenance = obj("calibration")

    return PlatformCalibration(
        name = name,
        entityId = entityId,
        parentFrameId = parentFrameId,
        platformType = PlatformType.entries.firstOrNull { it.wire == string("platform_type") },
        description = string("description").orEmpty(),
        lengthOverAllM = double("length_over_all_m"),
        breadthOverAllM = double("breadth_over_all_m"),
        ccrp = obj("ccrp_m")?.toVec3M() ?: Vec3M.ZERO,
        zero = provenance?.obj("zero")?.toPlatformZero(),
        sensors = transforms.toSensorMounts(provenance),
        updatedAtEpochMillis = provenance?.long("updated_at_ms") ?: 0L,
    )
}

private fun JsonArray?.firstParentFrameId(): String? =
    this?.firstNotNullOfOrNull { (it as? JsonObject)?.string("parent_frame_id") }

/**
 * The transforms, with their provenance grafted back on.
 *
 * The `calibration` block indexes its per-sensor entries by `child_frame_id` rather than by position,
 * which is what lets the two halves be read independently — and what makes a document carrying one and
 * not the other still parse. A sensor with no provenance entry reads as typed with no accuracy, which
 * is the honest default: it is exactly what the strict export, which carries no provenance at all,
 * means.
 */
private fun JsonArray?.toSensorMounts(provenance: JsonObject?): List<SensorMount> {
    val array = this ?: return emptyList()
    val captures = provenance.sensorCaptures()
    return array.mapNotNull { element ->
        val t = element as? JsonObject ?: return@mapNotNull null
        val frameId = t.string("child_frame_id") ?: return@mapNotNull null
        val translation = t.translationVec() ?: return@mapNotNull null
        val capture = captures[frameId]
        SensorMount(
            label = t.string("sensor_description").orEmpty(),
            frameId = frameId,
            sensorType = SensorType.entries.firstOrNull { it.wire == t.string("sensor_type") }
                ?: SensorType.OTHER,
            translation = translation,
            rotation = t.rotationEuler(),
            capture = capture?.method ?: CaptureMethod.MANUAL,
            accuracyM = capture?.accuracyM,
            capturedAtEpochMillis = capture?.atEpochMillis ?: 0L,
            rotationCapture = capture?.rotationMethod ?: CaptureMethod.MANUAL,
            rotationAccuracyDeg = capture?.rotationAccuracyDeg,
        )
    }
}

private class SensorProvenance(
    val method: CaptureMethod,
    val accuracyM: Double?,
    val atEpochMillis: Long,
    val rotationMethod: CaptureMethod,
    val rotationAccuracyDeg: Double?,
)

private fun JsonObject?.sensorCaptures(): Map<String, SensorProvenance> {
    val entries = this?.get("sensors") as? JsonArray ?: return emptyMap()
    return entries.mapNotNull { element ->
        val e = element as? JsonObject ?: return@mapNotNull null
        val frameId = e.string("child_frame_id") ?: return@mapNotNull null
        frameId to SensorProvenance(
            method = CaptureMethod.entries.byWireName(e.string("capture")) ?: CaptureMethod.MANUAL,
            accuracyM = e.double("accuracy_m"),
            atEpochMillis = e.long("captured_at_ms") ?: 0L,
            // Absent means typed, which is what every document written before rotations could be
            // measured says, and what the strict export means for every sensor in it.
            rotationMethod = CaptureMethod.entries.byWireName(e.string("rotation_capture"))
                ?: CaptureMethod.MANUAL,
            rotationAccuracyDeg = e.double("rotation_accuracy_deg"),
        )
    }.toMap()
}

/**
 * A translation, in either shape that exists in the wild.
 *
 * `translation_m: {x, y, z}` is the current schema. `translation: [x, y, z]` is what the older
 * documents in `keelson-platforms` use — `platforms/sf18/config.json` is one — and those are real
 * files somebody will try to import, so refusing them would be refusing the data this feature is for.
 */
private fun JsonObject.translationVec(): Vec3M? {
    obj("translation_m")?.let { return it.toVec3M() }
    obj("translation")?.let { return it.toVec3M() }
    (this["translation"] as? JsonArray)?.let { a ->
        if (a.size < 3) return null
        return Vec3M(a.numberAt(0), a.numberAt(1), a.numberAt(2))
    }
    return null
}

/** Rotation is optional everywhere — an unrotated sensor is the common case and reads as zero. */
private fun JsonObject.rotationEuler(): EulerDeg {
    obj("rotation_deg")?.let { return it.toEulerDeg() }
    obj("rotation")?.let { return it.toEulerDeg() }
    (this["rotation"] as? JsonArray)?.let { a ->
        // The legacy array form is [roll, pitch, yaw] — squaternion's own argument order, which is
        // what produced those files. Reading it yaw-first would silently roll a platform onto its side.
        if (a.size < 3) return EulerDeg.ZERO
        return EulerDeg(yaw = a.numberAt(2), pitch = a.numberAt(1), roll = a.numberAt(0))
    }
    return EulerDeg.ZERO
}

private fun JsonArray.numberAt(index: Int): Double =
    (getOrNull(index) as? JsonPrimitive)?.doubleOrNull ?: 0.0

private fun JsonObject.toVec3M(): Vec3M? {
    val x = double("x") ?: return null
    val y = double("y") ?: return null
    val z = double("z") ?: return null
    return Vec3M(x, y, z)
}

private fun JsonObject.toEulerDeg(): EulerDeg = EulerDeg(
    yaw = double("yaw") ?: 0.0,
    pitch = double("pitch") ?: 0.0,
    roll = double("roll") ?: 0.0,
)

/**
 * The surveyed zero.
 *
 * Latitude and longitude are both required, exactly as in `readPlatformZero`: a zero with one of them
 * missing would place the platform on the equator or the Greenwich meridian, which is a real place and a
 * completely wrong answer.
 */
private fun JsonObject.toPlatformZero(): PlatformZero? {
    val lat = double("latitude") ?: return null
    val lon = double("longitude") ?: return null
    return PlatformZero(
        latitude = lat,
        longitude = lon,
        altitudeM = double("altitude_m"),
        accuracyM = double("accuracy_m"),
        verticalAccuracyM = double("vertical_accuracy_m"),
        scatterM = double("scatter_m"),
        headingDeg = double("heading_deg") ?: 0.0,
        headingSource = HeadingSource.entries.byWireName(string("heading_source"))
            ?: HeadingSource.MANUAL,
        capture = CaptureMethod.entries.byWireName(string("capture")) ?: CaptureMethod.MANUAL,
        samples = int("samples") ?: 0,
        capturedAtEpochMillis = long("captured_at_ms") ?: 0L,
    )
}

/** The provenance block writes enum names lowercased; this is the inverse. */
private fun <T : Enum<T>> List<T>.byWireName(stored: String?): T? =
    stored?.let { s -> firstOrNull { it.name.equals(s, ignoreCase = true) } }

// A JSON null is a present key with no value, and must read the same as an absent one — every
// accessor here goes through `primitive`, which folds the two together.
private fun JsonObject.primitive(name: String): JsonPrimitive? =
    (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

/** Only a genuine JSON string, so a number is never silently read as a name. */
private fun JsonObject.string(name: String): String? =
    primitive(name)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.double(name: String): Double? = primitive(name)?.doubleOrNull

private fun JsonObject.long(name: String): Long? = primitive(name)?.longOrNull

private fun JsonObject.int(name: String): Int? = primitive(name)?.intOrNull

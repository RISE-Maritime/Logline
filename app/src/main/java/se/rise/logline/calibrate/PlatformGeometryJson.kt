package se.rise.logline.calibrate

import se.rise.logline.keelson.escaped
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The calibration as keelson's platform-geometry document.
 *
 * Hand-written, the way `clientConfigJson()` hand-writes Zenoh's config in `keelson/KeelsonSession.kt`,
 * and for the same reason: this is a small fixed shape that is only ever *written*, so a JSON library
 * would be a dependency earning nothing. Nothing in the app parses JSON — the calibration is persisted
 * as DataStore keys, not as this document.
 *
 * **Two variants, and the difference is deliberate.**
 *
 * - `provenance = false` — exactly `keelson/connectors/platform/config-schema.json`, which is
 *   `additionalProperties: false` throughout. This is what the export writes, so the file can be passed
 *   straight to `platform-geometry2keelson.py --config`.
 * - `provenance = true` — the same document plus a `calibration` block carrying how each number was
 *   arrived at: the surveyed zero, the heading and its source, and per-sensor capture method, accuracy
 *   and time. This is what goes out on `configuration_json`, where the JSON is free-form.
 *
 * That split exists because the upstream schema has nowhere to put uncertainty, and a calibration
 * without a stated uncertainty is half a measurement — see `docs/calibration.md`, which proposes the
 * block upstream.
 */
fun RigCalibration.toPlatformGeometryJson(provenance: Boolean = false): String =
    platformGeometryJson(provenance = provenance)

/**
 * The form the phone stores and shares: the wire document plus the two fields that identify the rig.
 *
 * `entity_id` and `parent_frame_id` are **not** in upstream's schema, which is why they cannot go in
 * the export — but they are the rig's identity here, and a library of rigs keyed on entity id cannot
 * round-trip without them. `parent_frame_id` is recoverable from any frame transform and is written
 * anyway, because a rig with no sensors yet has none to recover it from.
 */
fun RigCalibration.toStoredJson(): String =
    platformGeometryJson(provenance = true, identity = true)

/**
 * One entry of a crowsnest platform registry: upstream's strict shape plus `realm`.
 *
 * The entity id is the *key* of the object this goes into, not a field, which is why [identity] is off
 * here and on for [toStoredJson]. Deliberately no `queryables` or `data_streams`: those are crowsnest's
 * own bookkeeping, and inventing key expressions the phone has not verified would put wrong ones in
 * front of an operator. Crowsnest discovers streams from the wire itself.
 */
fun RigCalibration.toRegistryEntryJson(realm: String): String =
    platformGeometryJson(provenance = false, realm = realm)

private fun RigCalibration.platformGeometryJson(
    provenance: Boolean,
    identity: Boolean = false,
    realm: String? = null,
): String {
    val out = StringBuilder()
    out.append("{\n")
    val fields = mutableListOf<String>()

    if (identity) {
        fields += "  \"entity_id\": \"${entityId.escaped()}\""
        fields += "  \"parent_frame_id\": \"${parentFrameId.escaped()}\""
    }
    realm?.let { fields += "  \"realm\": \"${it.escaped()}\"" }
    platformType?.let { fields += "  \"platform_type\": \"${it.wire.escaped()}\"" }
    if (description.isNotBlank()) fields += "  \"description\": \"${description.escaped()}\""
    fields += "  \"name\": \"${name.escaped()}\""
    lengthOverAllM?.let { fields += "  \"length_over_all_m\": ${it.json()}" }
    breadthOverAllM?.let { fields += "  \"breadth_over_all_m\": ${it.json()}" }
    fields += "  \"ccrp_m\": ${ccrp.translationJson()}"
    fields += "  \"frame_transforms\": ${frameTransformsJson()}"
    if (provenance) fields += "  \"calibration\": ${calibrationJson()}"

    out.append(fields.joinToString(",\n"))
    out.append("\n}\n")
    return out.toString()
}

private fun RigCalibration.frameTransformsJson(): String {
    if (sensors.isEmpty()) return "[]"
    return sensors.joinToString(
        separator = ",\n",
        prefix = "[\n",
        postfix = "\n  ]",
    ) { mount ->
        val body = mutableListOf(
            "      \"parent_frame_id\": \"${parentFrameId.escaped()}\"",
            "      \"child_frame_id\": \"${mount.frameId.escaped()}\"",
            "      \"sensor_type\": \"${mount.sensorType.wire}\"",
        )
        if (mount.label.isNotBlank()) {
            body += "      \"sensor_description\": \"${mount.label.escaped()}\""
        }
        body += "      \"translation_m\": ${mount.translation.translationJson()}"
        body += "      \"rotation_deg\": ${mount.rotation.rotationJson()}"
        "    {\n" + body.joinToString(",\n") + "\n    }"
    }
}

/**
 * The provenance block. Not in upstream's schema — see the note on [toPlatformGeometryJson].
 *
 * Absent fields are omitted rather than written as `null` or `0`: a typed offset has no accuracy, and
 * saying `"accuracy_m": 0` would claim a perfect one.
 */
private fun RigCalibration.calibrationJson(): String {
    val parts = mutableListOf<String>()
    parts += "    \"frame\": \"x-forward, y-starboard, z-down; rotations yaw-pitch-roll\""
    zero?.let { z ->
        val zeroFields = mutableListOf(
            "        \"latitude\": ${z.latitude.json(9)}",
            "        \"longitude\": ${z.longitude.json(9)}",
        )
        z.altitudeM?.let { zeroFields += "        \"altitude_m\": ${it.json()}" }
        z.accuracyM?.let { zeroFields += "        \"accuracy_m\": ${it.json()}" }
        z.verticalAccuracyM?.let { zeroFields += "        \"vertical_accuracy_m\": ${it.json()}" }
        z.scatterM?.let { zeroFields += "        \"scatter_m\": ${it.json()}" }
        zeroFields += "        \"heading_deg\": ${z.headingDeg.json()}"
        zeroFields += "        \"heading_source\": \"${z.headingSource.name.lowercase()}\""
        zeroFields += "        \"capture\": \"${z.capture.name.lowercase()}\""
        zeroFields += "        \"samples\": ${z.samples}"
        zeroFields += "        \"captured_at_ms\": ${z.capturedAtEpochMillis}"
        parts += "    \"zero\": {\n" + zeroFields.joinToString(",\n") + "\n    }"
    }
    if (sensors.isNotEmpty()) {
        val entries = sensors.joinToString(",\n") { mount ->
            val f = mutableListOf(
                "        \"child_frame_id\": \"${mount.frameId.escaped()}\"",
                "        \"capture\": \"${mount.capture.name.lowercase()}\"",
            )
            mount.accuracyM?.let { f += "        \"accuracy_m\": ${it.json()}" }
            if (mount.capturedAtEpochMillis > 0) {
                f += "        \"captured_at_ms\": ${mount.capturedAtEpochMillis}"
            }
            "      {\n" + f.joinToString(",\n") + "\n      }"
        }
        parts += "    \"sensors\": [\n$entries\n    ]"
    }
    parts += "    \"updated_at_ms\": $updatedAtEpochMillis"
    return "{\n" + parts.joinToString(",\n") + "\n  }"
}

private fun Vec3M.translationJson(): String =
    "{ \"x\": ${x.json()}, \"y\": ${y.json()}, \"z\": ${z.json()} }"

/**
 * Rotations are folded into `[-180, 180]` on the way out, which is the range the schema allows.
 *
 * Yaw first, matching the order they are applied and the order upstream's example writes them.
 */
private fun EulerDeg.rotationJson(): String =
    "{ \"yaw\": ${normaliseSignedDegrees(yaw).json()}, " +
        "\"pitch\": ${normaliseSignedDegrees(pitch).json()}, " +
        "\"roll\": ${normaliseSignedDegrees(roll).json()} }"

/**
 * A JSON number with no exponent, no locale and no trailing zeros.
 *
 * `toString()` would emit `1.0E-4` for a tenth of a millimetre, which is legal JSON and unreadable;
 * `String.format` would emit a comma in a Swedish locale, which is neither. Six decimals is a
 * micrometre — well past what any of this is measured to.
 */
private fun Double.json(scale: Int = 6): String {
    if (!isFinite()) return "0"
    val decimal = BigDecimal(this).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros()
    // `stripTrailingZeros` turns 10 into 1E+1; toPlainString puts it back.
    return decimal.toPlainString()
}

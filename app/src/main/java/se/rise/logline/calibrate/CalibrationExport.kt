package se.rise.logline.calibrate

import android.content.Context
import android.net.Uri
import se.rise.logline.keelson.escaped
import se.rise.logline.record.CONFIG_FOLDER
import se.rise.logline.record.saveToDownloads

/**
 * Write one rig to `Downloads/Logline/config` as a platform-geometry file.
 *
 * The **strict** variant: no provenance block, so the file validates against
 * `keelson/connectors/platform/config-schema.json` and can be handed straight to
 * `platform-geometry2keelson.py --config`. That is the point of exporting at all — the phone surveys
 * the rig once, and a connector on the vessel publishes the result from then on.
 *
 * Returns the file name it wrote, for the screen to show. Throws whatever the MediaStore write throws;
 * the caller reports it rather than this pretending it succeeded.
 */
fun exportCalibration(context: Context, calibration: RigCalibration): String {
    val name = "${defaultEntityId(calibration.name)}-platform-geometry.json"
    saveToDownloads(context, name, "application/json", CONFIG_FOLDER) { out ->
        out.write(calibration.toPlatformGeometryJson().toByteArray(Charsets.UTF_8))
    }
    return name
}

/**
 * Write the whole library as a crowsnest platform registry.
 *
 * A different shape from [exportCalibration] and deliberately a second button rather than a
 * replacement: that one writes the *bare strict* document a keelson connector eats, this one writes
 * the object-of-platforms keyed by entity id that crowsnest's `src/DB/platform_registry.json` is, so
 * an operator can add the rigs surveyed here to a station's platform list.
 *
 * `realm` is the one field crowsnest needs that upstream's schema has no room for. `queryables` and
 * `data_streams` are deliberately absent: they are that station's own bookkeeping, and inventing key
 * expressions this phone has not verified would put wrong ones in front of somebody. Crowsnest
 * discovers streams from the wire itself.
 */
fun exportPlatformRegistry(context: Context, rigs: List<RigCalibration>, realm: String): String {
    val name = "logline-platform-registry.json"
    saveToDownloads(context, name, "application/json", CONFIG_FOLDER) { out ->
        out.write(platformRegistryJson(rigs, realm).toByteArray(Charsets.UTF_8))
    }
    return name
}

/** Split out from the file write so it can be tested without a `Context`. */
internal fun platformRegistryJson(rigs: List<RigCalibration>, realm: String): String =
    rigs.joinToString(",\n", prefix = "{\n", postfix = "\n}\n") { rig ->
        // The entity id is the key, which is why the entry itself does not carry one.
        val body = rig.toRegistryEntryJson(realm).trim().lines().joinToString("\n") { "  $it" }
        "  \"${rig.entityId.escaped()}\": ${body.trimStart()}"
    }

/**
 * Read a file the operator picked: one platform document, or a registry of several.
 *
 * Returns what parsed, in file order. An empty list means nothing in the file was a platform — which
 * the caller reports as such rather than as a silent success, because "imported 0 rigs" and "imported"
 * look identical otherwise.
 */
fun importPlatforms(context: Context, uri: Uri): List<RigCalibration> {
    val text = context.contentResolver.openInputStream(uri)?.use { input ->
        input.readBytes().toString(Charsets.UTF_8)
    } ?: return emptyList()
    return parsePlatformDocument(text)
}

/**
 * How an imported rig relates to the library it is landing in.
 *
 * Kept as data rather than resolved on the spot: an import that silently replaced a rig would
 * overwrite a surveyed zero somebody spent twenty minutes standing still for, and one that silently
 * skipped would look like it had worked. The screen asks.
 */
enum class ImportDisposition { NEW, REPLACES }

data class ImportCandidate(
    val rig: RigCalibration,
    val disposition: ImportDisposition,
    /** True when the operator has chosen to apply this one. New rigs default in, replacements out. */
    val selected: Boolean,
)

fun importCandidates(imported: List<RigCalibration>, existing: List<RigCalibration>): List<ImportCandidate> {
    val known = existing.map { it.entityId }.toSet()
    return imported.map { rig ->
        val replaces = rig.entityId in known
        ImportCandidate(
            rig = rig,
            disposition = if (replaces) ImportDisposition.REPLACES else ImportDisposition.NEW,
            // A replacement is opt-in: the destructive half of an import should take a deliberate tap.
            selected = !replaces,
        )
    }
}

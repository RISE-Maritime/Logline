package se.rise.logline.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import se.rise.logline.calibrate.RigCalibration
import se.rise.logline.calibrate.toStoredJson
import se.rise.logline.keelson.escaped

/**
 * The shared rig library on the bus: keys, encoding, and who wins when two phones disagree.
 *
 * Pure — no Android, no Zenoh — so the conflict rules are testable, which is the half of this that can
 * silently do the wrong thing. `PlatformSync` is the part with a session in it.
 *
 * **The subject token is deliberately not a keelson subject.** `platform_registry` is nowhere in
 * `messages/subjects.yaml` and must never be added to `Subjects` or `PublishedSubject`: a consumer's
 * decode path falls through to raw JSON precisely because the token is unknown, and a payload that
 * looked like a keelson subject would be unwrapped as an envelope and fail. That is the same trick
 * crowsnest already plays for `dataflow_config`, `route` and `voyage`, and this is deliberately the
 * same shape so the two agree — see `../crowsnest-dev/src/services/dataflowConfigSync.js`.
 */
object PlatformRegistry {

    /** Bumped only if the envelope's own shape changes, never for a library edit. */
    const val FORMAT_VERSION = 1

    /**
     * The entity the shared library lives under.
     *
     * A *config* entity, not a platform. Filing a library of platforms under one platform's entity is
     * a category error, and filing it under the phone's would make each phone's library private —
     * which is the opposite of sharing it.
     */
    const val DEFAULT_ENTITY = "platforms"

    /** `{realm}/@v0/{entity}/pubsub/platform_registry/library/latest` */
    fun key(realm: String, entityId: String = DEFAULT_ENTITY): String =
        "$realm/@v0/$entityId/pubsub/platform_registry/library/latest"
}

/**
 * A library somebody else published.
 *
 * [version] is the ordering, [origin] is who wrote it. Both are the conflict resolution: a phone drops
 * its own echo by origin and ignores anything not strictly newer than what it already has.
 */
data class RemotePlatformRegistry(
    val version: Long,
    val origin: String,
    val updatedAtEpochMillis: Long,
    val rigs: List<RigCalibration>,
)

/**
 * Encode the library for the bus: raw JSON, no protobuf and no envelope.
 *
 * Hand-written like the geometry writer beside it, and for the same reason — a small fixed shape that
 * is only ever written here. The rigs go in as the **stored** form, provenance and identity included:
 * a library that dropped how each offset was measured would turn every share into a set of numbers
 * with no stated uncertainty.
 */
fun encodePlatformRegistry(
    version: Long,
    origin: String,
    updatedAtEpochMillis: Long,
    rigs: List<RigCalibration>,
): ByteArray {
    val entries = rigs.joinToString(",\n", prefix = "{\n", postfix = "\n  }") { rig ->
        val body = rig.toStoredJson().trim()
        "    \"${rig.entityId.escaped()}\": $body"
    }
    val json = """
        {
          "v": ${PlatformRegistry.FORMAT_VERSION},
          "version": $version,
          "origin": "${origin.escaped()}",
          "updatedAtEpochMillis": $updatedAtEpochMillis,
          "rigs": $entries
        }
    """.trimIndent()
    return json.toByteArray(Charsets.UTF_8)
}

/** Decode a library off the bus, or null when the bytes are not one. */
fun decodePlatformRegistry(bytes: ByteArray): RemotePlatformRegistry? {
    val root = runCatching {
        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
    }.getOrNull() ?: return null
    val rigsObject = root["rigs"] as? JsonObject ?: return null
    return RemotePlatformRegistry(
        version = (root["version"] as? JsonPrimitive)?.longOrNull ?: 0L,
        origin = (root["origin"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
        updatedAtEpochMillis = (root["updatedAtEpochMillis"] as? JsonPrimitive)?.longOrNull ?: 0L,
        rigs = rigsObject.entries.mapNotNull { (entityId, value) ->
            se.rise.logline.calibrate.parsePlatformGeometry(value.toString(), entityId)
        },
    )
}

/**
 * Whether a library that arrived should be applied.
 *
 * Last-writer-wins by version, with an origin guard — a straight transcription of crowsnest's
 * `shouldApplyRemote`, so the two sides resolve a conflict the same way rather than each being
 * self-consistent and mutually wrong.
 *
 * The origin check is not an optimisation: a publisher's own sample cache re-delivers, so without it
 * a phone applies its own library back over itself on every reconnect and the version ratchets for no
 * reason.
 */
fun shouldApplyRemote(remote: RemotePlatformRegistry?, localVersion: Long, ownOrigin: String): Boolean {
    if (remote == null) return false
    if (remote.origin.isNotEmpty() && remote.origin == ownOrigin) return false
    return remote.version > localVersion
}

/**
 * Merge a remote library into the local one.
 *
 * **Documents only.** Which rig is active and which rigs publish are this phone's local policy and are
 * never moved by something that arrived over the air — one operator saving a library must not silently
 * start every phone in the fleet publishing geometry under entity ids nobody told them about. That is
 * the single most important rule in this file.
 *
 * A rig this phone is publishing is also **never removed** by a remote update, which is a knowing
 * deviation from crowsnest's whole-map replace: taking a rig out from under a live publisher is the
 * one case where last-writer-wins is not acceptable. It comes back as soon as it is switched off.
 */
fun mergeRemoteRigs(
    local: List<RigCalibration>,
    remote: List<RigCalibration>,
    /** Entity ids this phone must keep whatever the remote says — the active rig and any opted in. */
    protectedEntityIds: Set<String>,
): List<RigCalibration> {
    val incoming = remote.associateBy { it.entityId }
    val kept = local.filter { it.entityId !in incoming && it.entityId in protectedEntityIds }
    return remote + kept
}

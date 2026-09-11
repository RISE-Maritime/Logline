package se.rise.logline.checklist

import android.content.Context
import android.util.Log
import se.rise.logline.safeFileStem
import java.io.File

/**
 * Where this phone keeps the checklist photographs it has taken.
 *
 * `filesDir/checklist-evidence/{evidence_id}.jpg`, so the file name **is** the key — no preference,
 * no stored path, nothing to leave dangling. The same shape `PlatformPhotos` uses, and for the same
 * reason: an evidence id never changes, unlike a platform's entity id, so this one does not even
 * need the rename and delete obligations that one carries.
 *
 * **Excluded from backup**, unlike `platforms/`. That directory is configuration nothing can
 * rebuild, which is why it earns a place in the 25 MB quota; this is a local copy of bytes the
 * router already holds durably on `checklist_evidence/{evidence_id}`, and a run's worth of
 * photographs would push a backup over the quota and fail *the whole thing*, taking the settings
 * with it.
 *
 * Note what this is not: a cache of *other* stations' evidence. Fetching that needs a Zenoh `get`,
 * and a query's reply aborts the process on this binding — see [ChecklistAvailability]. A remote
 * photo is rendered from its metadata alone until that is fixed.
 */
class ChecklistEvidenceStore(private val context: Context) {

    private fun directory(): File =
        File(context.filesDir, DIRECTORY).apply { if (!exists()) mkdirs() }

    /**
     * The id is sanitised before it becomes a path, even though every caller today generates it
     * locally through `checklistId("ev")`.
     *
     * That is not belt and braces. `filesDir` also holds `tls/client_key.pem`, and this class sits one
     * short step from the bus: the moment somebody wires up fetching *other* stations' evidence, the id
     * naming the file is foreign input, and `../tls/client_key.pem` would be an arbitrary overwrite of
     * the phone's own identity. [safeFileStem] is the same encoding `photoFileName` uses, so a
     * remote-supplied id cannot leave this directory whichever of the two doors it arrives through.
     */
    fun file(evidenceId: String): File = File(directory(), safeFileStem(evidenceId) + ".jpg")

    fun save(evidenceId: String, jpeg: ByteArray): Boolean = runCatching {
        file(evidenceId).writeBytes(jpeg)
        true
    }.onFailure { Log.w(TAG, "could not store evidence $evidenceId", it) }.getOrDefault(false)

    fun read(evidenceId: String): ByteArray? =
        file(evidenceId).takeIf { it.exists() }?.let { runCatching { it.readBytes() }.getOrNull() }

    private companion object {
        const val DIRECTORY = "checklist-evidence"
        const val TAG = "ChecklistEvidence"
    }
}

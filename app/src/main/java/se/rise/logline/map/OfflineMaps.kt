package se.rise.logline.map

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.util.Locale

private const val TAG = "OfflineMaps"

/**
 * The tile archive formats osmdroid can read, as `ArchiveFileFactory` registers them.
 *
 * Transcribed rather than queried so the check can run in a unit test, and because a picked file has to
 * be rejected *before* it is copied — `ArchiveFileFactory` is still the authority at render time. If a
 * future osmdroid registers another format this list is what stops it being offered.
 */
internal val ARCHIVE_EXTENSIONS = setOf("mbtiles", "gemf", "sqlite", "zip")

/** One imported archive. */
data class OfflineMap(val file: File, val sizeBytes: Long) {
    val name: String get() = file.name
}

/**
 * Where osmdroid keeps its base directory, and therefore where an archive has to land.
 *
 * **One definition, used by the import and by `configureOsmdroid`.** `MapTileFileArchiveProvider`
 * discovers archives by listing exactly this directory, so two spellings of the path would put imports
 * somewhere the map never looks — and the failure would be a blank map with a file sitting right there,
 * which is the hardest kind to diagnose.
 */
fun osmdroidBasePath(context: Context): File =
    File(context.filesDir, "osmdroid").apply { mkdirs() }

/**
 * The archives currently imported, largest first.
 *
 * Only files whose extension osmdroid recognises: the same directory holds the tile cache in a `tiles`
 * subdirectory and osmdroid's own bookkeeping, none of which is a map anybody imported.
 */
fun importedMaps(context: Context): List<OfflineMap> =
    osmdroidBasePath(context)
        .listFiles { f -> f.isFile && f.extension.lowercase(Locale.ROOT) in ARCHIVE_EXTENSIONS }
        ?.map { OfflineMap(it, it.length()) }
        ?.sortedByDescending { it.sizeBytes }
        .orEmpty()

/**
 * The file name an archive should be stored under, or null when it is not one.
 *
 * **The extension is load-bearing and must survive the copy.** `ArchiveFileFactory` dispatches on it
 * alone, so an archive saved without one — or renamed to something friendlier — is silently never
 * read: the map stays blank and the file sits there looking imported. Rejecting up front turns that
 * into a sentence on screen.
 */
internal fun archiveFileNameOrNull(displayName: String): String? {
    val name = displayName.trim().substringAfterLast('/')
    if (!name.contains('.')) return null
    val extension = name.substringAfterLast('.').lowercase(Locale.ROOT)
    if (extension !in ARCHIVE_EXTENSIONS) return null
    return name
}

/**
 * Copy a picked archive into the map directory.
 *
 * Copied rather than referenced: a `content://` URI's permission does not survive a reboot, and the
 * tile provider reads the file directly from disk on a background thread with no `ContentResolver` in
 * sight. It goes into `filesDir`, which the backup rules already exclude — an archive is hundreds of
 * megabytes against a 25 MB backup quota.
 *
 * The map picks it up the next time a `MapView` is built: `findArchiveFiles()` runs when the provider
 * is constructed, not continuously. Importing happens on the settings screen and the map is elsewhere,
 * so in practice it is there on arrival.
 */
fun importOfflineMap(context: Context, uri: Uri, displayName: String): Result<OfflineMap> {
    val name = archiveFileNameOrNull(displayName)
        ?: return Result.failure(
            IllegalArgumentException(
                "Not a tile archive. Expected one of ${ARCHIVE_EXTENSIONS.sorted().joinToString(", ") { ".$it" }}"
            )
        )
    val target = File(osmdroidBasePath(context), name)
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: return Result.failure(java.io.IOException("Could not read $displayName"))
        Log.i(TAG, "imported $name (${target.length()} bytes)")
        Result.success(OfflineMap(target, target.length()))
    } catch (t: Throwable) {
        // A part-written archive is worse than none: osmdroid would open it and serve a few tiles.
        target.delete()
        Log.w(TAG, "could not import $displayName", t)
        Result.failure(t)
    }
}

fun deleteOfflineMap(map: OfflineMap): Boolean = map.file.delete()

/**
 * The name a picked document calls itself.
 *
 * Asked of the provider rather than taken from the URI, because a `content://` URI's last segment is
 * usually a document id with no extension in it — and the extension is the whole basis on which
 * osmdroid decides what kind of archive this is. The last segment is only the fallback.
 */
fun displayNameOf(context: Context, uri: Uri): String {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    return runCatching {
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
}

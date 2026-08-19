package se.rise.logline.record

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.FileInputStream

private const val TAG = "RecordingsLibrary"

/**
 * One file this app has put in `Downloads/Logline`.
 *
 * [summary] is null for anything that is not a readable MCAP — the rig calibration's platform-geometry
 * export shares this folder, and so does a recording rescued from a killed process, which has no
 * statistics section. Both are listed; neither claims a message count.
 */
data class SavedRecording(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val savedAtMillis: Long,
    val summary: McapSummary?,
)

/**
 * Everything this app has saved, newest first.
 *
 * **No storage permission, and none should be added.** Under scoped storage a `MediaStore` query
 * returns the querying app's *own* entries, which is exactly the wanted set — this app's recordings and
 * exports, not the user's photos. Asking for `READ_EXTERNAL_STORAGE` would widen that to everything
 * and gain nothing.
 *
 * The consequence to know about: MediaStore attributes entries to the package that wrote them, and
 * Android's documented behaviour is that an app loses access to its own entries once it is uninstalled.
 * If that bites, the list goes empty while the files sit untouched in `Downloads/Logline`, visible to
 * any file manager — which looks like data loss and is not, so the empty state says so. **Not verified
 * here**: an install carrying files from an earlier one still listed them, so the boundary is
 * uninstall rather than update, and exactly where it falls has not been tested.
 */
fun savedRecordings(context: Context): List<SavedRecording> {
    val columns = arrayOf(
        MediaStore.Downloads._ID,
        MediaStore.Downloads.DISPLAY_NAME,
        MediaStore.Downloads.SIZE,
        MediaStore.Downloads.DATE_ADDED,
    )
    // The stored value has a trailing slash, which is why this matches a prefix rather than equality.
    val folder = "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOADS_FOLDER/"
    return try {
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            columns,
            "${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(folder),
            "${MediaStore.Downloads.DATE_ADDED} DESC",
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val size = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val added = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
            buildList {
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(id),
                    )
                    add(
                        SavedRecording(
                            uri = uri,
                            name = cursor.getString(name),
                            sizeBytes = cursor.getLong(size),
                            // MediaStore keeps this one in seconds, unlike every other time in the app.
                            savedAtMillis = cursor.getLong(added) * 1_000L,
                            summary = summaryOf(context, uri),
                        )
                    )
                }
            }
        }.orEmpty()
    } catch (t: Throwable) {
        Log.w(TAG, "could not list saved recordings", t)
        emptyList()
    }
}

/**
 * The file's own statistics, read through a seekable descriptor.
 *
 * `openFileDescriptor` rather than `openInputStream` because [readMcapSummary] seeks to the footer:
 * a stream would have to be read to the end, which for a 512 MB recording is the whole point of not
 * doing it this way.
 */
private fun summaryOf(context: Context, uri: Uri): McapSummary? = try {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        FileInputStream(pfd.fileDescriptor).use { readMcapSummary(it.channel) }
    }
} catch (t: Throwable) {
    Log.i(TAG, "no summary for $uri", t)
    null
}

/**
 * Hand files to another app — mail, Drive, a laptop over a cable.
 *
 * `MediaStore` URIs are shareable as they are, so there is no `FileProvider` here and no
 * `file://` URI to fall foul of `StrictMode`. The read grant is per share and expires with it.
 */
fun shareIntent(files: List<SavedRecording>): Intent {
    val uris = ArrayList(files.map { it.uri })
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    return Intent.createChooser(
        send.setType("application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        if (uris.size == 1) files.first().name else "${uris.size} files",
    )
}

/**
 * Delete one file, reporting whether it went.
 *
 * Succeeds for files this app owns, which is all of them until it is reinstalled. A `SecurityException`
 * means the entry outlived the install that wrote it; it is reported rather than thrown, because the
 * screen can say "use a file manager" and a crash cannot.
 */
fun deleteSavedRecording(context: Context, file: SavedRecording): Boolean = try {
    context.contentResolver.delete(file.uri, null, null) > 0
} catch (t: Throwable) {
    Log.w(TAG, "could not delete ${file.name}", t)
    false
}

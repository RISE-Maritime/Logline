package se.rise.logline.record

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream

/** Where everything this app hands back to the user turns up. */
const val DOWNLOADS_FOLDER = "Logline"

/**
 * Write a file into `Downloads/Logline`, where the user can actually get at it.
 *
 * App-private storage is right for anything the app is still using — recordings in progress, TLS
 * credentials, map tiles — and useless for something a person is meant to email to a colleague.
 * `MediaStore` is the only route to the shared Downloads collection that needs no storage permission
 * on any supported API level.
 *
 * **The file is hidden until it is complete.** `IS_PENDING` is set for the duration of the write, so a
 * file manager scanning mid-copy never shows a half-written recording as if it were a whole one.
 *
 * Shared by the MCAP recorder and the calibration export rather than written twice: this is the piece
 * with the pending flag, the folder name and the failure mode in it, and two copies would eventually
 * disagree about all three.
 *
 * @throws IOException if the entry could not be created or opened.
 */
internal fun saveToDownloads(
    context: Context,
    fileName: String,
    mimeType: String,
    write: (OutputStream) -> Unit,
) {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, mimeType)
        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOADS_FOLDER")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("Downloads is not accepting new files")
    resolver.openOutputStream(uri)?.use(write)
        ?: throw IOException("could not open $fileName for writing")
    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
}

package se.rise.logline.record

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream

/** Where this app's recordings turn up. Recordings and nothing else — see [CONFIG_FOLDER]. */
const val DOWNLOADS_FOLDER = "Logline"

/**
 * Where the documents this app exports turn up: settings profiles, a rig's geometry, the rig library.
 *
 * **A subfolder, and that is what keeps them out of the Recordings tab.** `savedRecordings()` matches
 * `RELATIVE_PATH` for exactly `Download/Logline/`, so anything a level down is not a row in that list
 * by construction rather than by a filter somebody has to remember to keep working.
 *
 * They shared a folder until now, which put a settings profile in a list called Recordings reading
 * "982 B · no summary" — a description of a recording that failed rather than of a file that is exactly
 * as it should be. Naming the kinds fixed the label; this fixes the filing.
 */
const val CONFIG_FOLDER = "$DOWNLOADS_FOLDER/config"

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
 * Shared by the MCAP recorder and the exports rather than written twice: this is the piece with the
 * pending flag, the folder names and the failure mode in it, and two copies would eventually disagree
 * about all three.
 *
 * @throws IOException if the entry could not be created or opened.
 */
internal fun saveToDownloads(
    context: Context,
    fileName: String,
    mimeType: String,
    /** [DOWNLOADS_FOLDER] for a recording, [CONFIG_FOLDER] for anything exported. */
    folder: String = DOWNLOADS_FOLDER,
    write: (OutputStream) -> Unit,
) {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, mimeType)
        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folder")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("Downloads is not accepting new files")
    resolver.openOutputStream(uri)?.use(write)
        ?: throw IOException("could not open $fileName for writing")
    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
}

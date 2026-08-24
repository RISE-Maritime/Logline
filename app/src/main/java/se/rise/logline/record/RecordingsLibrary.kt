package se.rise.logline.record

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.net.toUri
import android.provider.MediaStore
import android.util.Log
import java.io.FileInputStream

private const val TAG = "RecordingsLibrary"

/**
 * One file this app has put in `Downloads/Logline`.
 *
 * [summary] is null for anything that is not a readable MCAP — the platform calibration's platform-geometry
 * export shares this folder, and so does a recording rescued from a killed process, which has no
 * statistics section. Both are listed; neither claims a message count.
 */
/**
 * The facts that ordering and searching a list of recordings need, and nothing else.
 *
 * Narrow deliberately. A [SavedRecording] carries a MediaStore [Uri], which has no implementation off a
 * device, and this project's unit tests keep strictly to code that runs on a plain JVM — there is no
 * Robolectric and no mocking framework on the test classpath, which is the same stance that had
 * `PlatformGeometryParse` choose `kotlinx-serialization` over `org.json`. Naming what the query logic
 * actually reads is what lets it stay testable without dragging a device in behind it.
 */
interface RecordingFacts {
    val name: String
    val sizeBytes: Long
    val savedAtMillis: Long

    /** Null for anything that is not a recording, or a recording with no summary section. */
    val durationMillis: Long?

    /** Null for anything that is not a recording — see [isComplete]. */
    val messages: Long?

    /** The words the operator had switched on when the file closed. Read from the file itself. */
    val tags: Set<String>

    /**
     * Whether the recording closed properly, or **null when the question does not apply**.
     *
     * False is what `McapRecovery.finalise` leaves behind for a run a killed process interrupted: every
     * message present, the figures never written.
     *
     * Null is the load-bearing one. `Downloads/Logline` holds settings profiles and platform-geometry exports
     * as well as recordings, and every one of them lacks an MCAP summary — so while this was a plain
     * `Boolean`, the Incomplete filter counted a hand-surveyed platform geometry document as a broken
     * recording, and a bulk delete built on that set would have destroyed it. Nullable here means those
     * files fall out of both Complete and Incomplete without the query logic knowing kinds exist.
     */
    val isComplete: Boolean?
}

/**
 * What a file in `Downloads/Logline` actually is.
 *
 * Four things write into that folder — the recorder, the settings-profile export, a platform's geometry
 * export and the platform library export — and [savedRecordings] lists the folder rather than a file type,
 * so all four appear in the Files tab. Naming them is what stops an export being read as a recording
 * that failed.
 */
enum class RecordingKind {
    Recording,
    SettingsProfile,
    PlatformGeometry,
    PlatformLibrary,

    /** Something else somebody put in the folder. Shown, never assumed to be ours. */
    Other,
}

/**
 * A file's kind from its name.
 *
 * By name because that is all the listing has — MediaStore's MIME type is whatever the writer declared,
 * and the recorder declares `application/octet-stream`. The patterns are the ones the four writers
 * actually use; `RecordingsQueryTest` pins each against the code that produces it.
 */
fun recordingKindOf(name: String): RecordingKind {
    val lower = name.lowercase()
    return when {
        lower.endsWith(".mcap") -> RecordingKind.Recording
        lower.startsWith("logline-settings-") && lower.endsWith(".json") ->
            RecordingKind.SettingsProfile
        lower == "logline-platform-registry.json" -> RecordingKind.PlatformLibrary
        lower.endsWith("-platform-geometry.json") -> RecordingKind.PlatformGeometry
        else -> RecordingKind.Other
    }
}

data class SavedRecording(
    val uri: Uri,
    /**
     * A stable key for the track cache, which needs a number and cannot use the [uri].
     *
     * MediaStore rows use their own id. A row that came from the granted folder instead has no numeric
     * id at all — a document id is a string — so it takes a hash of that, made **negative** so it can
     * never collide with a MediaStore id, which is always positive. `ContentUris.parseId(uri)` was what
     * this replaced, and it throws outright on a document Uri.
     */
    val cacheId: Long,
    override val name: String,
    override val sizeBytes: Long,
    override val savedAtMillis: Long,
    val summary: McapSummary?,
    /**
     * Which channel carries the phone's own fixes, or null when the recording has none — and also null
     * when there is no summary to ask, which is a different thing. See [McapTrack.read], which
     * discovers the channel itself when handed null.
     */
    val fixChannelId: Int? = null,
    override val tags: Set<String> = emptySet(),
) : RecordingFacts {
    val kind: RecordingKind get() = recordingKindOf(name)
    override val durationMillis: Long? get() = summary?.durationMillis
    override val messages: Long? get() = summary?.messages

    // Only a recording can be complete or not. An export is neither, and saying so here is what keeps
    // it out of the Incomplete filter and therefore out of the bulk delete.
    override val isComplete: Boolean?
        get() = if (kind == RecordingKind.Recording) summary != null else null
}

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
fun savedRecordings(context: Context, folderUri: String = ""): List<SavedRecording> {
    val owned = ownedRecordings(context)
    // Nothing granted, or nothing the grant adds: the MediaStore listing is the whole answer.
    val fromFolder = recordingsInGrantedFolder(
        context,
        folderUri,
        skip = owned.mapTo(mutableSetOf()) { recording -> recording.name },
    )
    // Newest first, the order the query already asked MediaStore for and the one every
    // ordering in `RecordingsQuery` breaks ties by.
    return (owned + fromFolder).sortedByDescending { it.savedAtMillis }
}

/** The MediaStore half: everything this install wrote. */
private fun ownedRecordings(context: Context): List<SavedRecording> {
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
                    val displayName = cursor.getString(name)
                    // Not asked of an export: there is no MCAP footer to find, and this opened and
                    // failed on every JSON in the folder on the way past.
                    val details = if (recordingKindOf(displayName) == RecordingKind.Recording) {
                        detailsOf(context, uri)
                    } else {
                        null
                    }
                    add(
                        SavedRecording(
                            uri = uri,
                            cacheId = cursor.getLong(id),
                            name = displayName,
                            sizeBytes = cursor.getLong(size),
                            // MediaStore keeps this one in seconds, unlike every other time in the app.
                            savedAtMillis = cursor.getLong(added) * 1_000L,
                            // Not asked of an export: there is no MCAP footer to find, and this opened
                            // and failed on every JSON in the folder on the way past.
                            summary = details?.summary,
                            // Free with the summary, and the most valuable thing the listing can know:
                            // a recording with no fix channel never starts a track scan at all.
                            fixChannelId = details?.topics
                                ?.let(McapTrack::fixChannel)?.channelId,
                            // From the file, so they travel with it: a recording copied to a laptop
                            // still says what it was.
                            tags = details?.tags.orEmpty(),
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
private fun detailsOf(context: Context, uri: Uri): McapDetails? = try {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        FileInputStream(pfd.fileDescriptor).use { readMcapDetails(it.channel) }
    }
} catch (t: Throwable) {
    Log.i(TAG, "no summary for $uri", t)
    null
}

/**
 * One file's name, size and date, without listing the folder.
 *
 * The detail screen is reached by URI so it works after a process death, when the listing has not been
 * read — and asking `MediaStore` about the one row is cheaper than rebuilding the whole list, which
 * would read a summary per recording on the way past.
 *
 * No `summary`: the caller reads that itself through [recordingDetails], which returns the topics too.
 */
fun recordingEntry(context: Context, uri: Uri): SavedRecording? = try {
    context.contentResolver.query(
        uri,
        arrayOf(
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_ADDED,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        SavedRecording(
            uri = uri,
            cacheId = ContentUris.parseId(uri),
            name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)),
            sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)),
            // Seconds here, unlike every other time in the app.
            savedAtMillis = cursor.getLong(
                cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
            ) * 1_000L,
            summary = null,
        )
    }
} catch (t: Throwable) {
    Log.i(TAG, "no entry for $uri", t)
    null
}

/**
 * Everything the detail screen needs, in the two costs it comes in.
 *
 * [details] is a few seeks off the footer, whatever the file's size. [track] is a full decompress of
 * the data section — see [McapTrack] — so the caller loads them separately and shows the first while
 * the second is still reading.
 */
fun recordingDetails(context: Context, uri: Uri): McapDetails? = try {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        FileInputStream(pfd.fileDescriptor).use { readMcapDetails(it.channel) }
    }
} catch (t: Throwable) {
    Log.i(TAG, "no details for $uri", t)
    null
}

/**
 * The recording's GNSS track, or empty when it holds none.
 *
 * **Streamed rather than seeked**, unlike everything else here: this reads the file forwards from the
 * start and there is nothing to seek to — the writer emits no chunk index on purpose.
 */
fun recordingTrack(context: Context, uri: Uri, channelId: Int?): TrackScan = try {
    context.contentResolver.openInputStream(uri)?.use { McapTrack.read(it, channelId) }
        // The file could not be opened at all, which is not the same as having no fix channel in it.
        ?: TrackScan(channelFound = false, fixes = emptyList(), stoppedEarly = true)
} catch (t: Throwable) {
    Log.i(TAG, "no track for $uri", t)
    TrackScan(channelFound = false, fixes = emptyList(), stoppedEarly = true)
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
    // **Two kinds of row, two ways to delete.** A MediaStore entry goes through `delete`; a document
    // from the granted folder does not — `ContentResolver.delete` on a tree document Uri is not what
    // that provider implements, and the file would survive while the call reported nothing wrong.
    if (DocumentsContract.isDocumentUri(context, file.uri)) {
        DocumentsContract.deleteDocument(context.contentResolver, file.uri)
    } else {
        context.contentResolver.delete(file.uri, null, null) > 0
    }
} catch (t: Throwable) {
    Log.w(TAG, "could not delete ${file.name}", t)
    false
}

/**
 * Delete several, reporting how many actually went.
 *
 * A count rather than a boolean because a bulk delete can partly fail — an entry that outlived the
 * install that wrote it refuses, and the screen has to be able to say so rather than claim a clean
 * sweep. Each file is deleted on its own terms, so one refusal does not abandon the rest.
 */
fun deleteSavedRecordings(context: Context, files: List<SavedRecording>): Int =
    files.count { deleteSavedRecording(context, it) }

/**
 * The other half: everything else in the folder, once the user has granted access to it.
 *
 * **MediaStore attributes a file to the install that wrote it**, and an app loses its claim on those
 * entries when it is uninstalled — so after a reinstall the recordings sit untouched in
 * `Downloads/Logline`, visible to every file manager and invisible here. Measured rather than inferred:
 * a file planted in that folder under another package was absent from a listing that returned all
 * fifteen of this install's own.
 *
 * A persisted tree grant is the only way back to them. Android 11 forbids
 * `ACTION_OPEN_DOCUMENT_TREE` on the Download *root*, which is why the grant is for `Download/Logline`
 * — a subdirectory, and allowed.
 *
 * **Names already listed are skipped, and MediaStore wins.** The two sources overlap almost entirely,
 * and a MediaStore row is the more capable of the two: it deletes with a plain `delete` and it carries
 * the numeric id the track cache is keyed on. A document row is the fallback for files nothing else can
 * reach.
 */
private fun recordingsInGrantedFolder(
    context: Context,
    folderUri: String,
    skip: Set<String>,
): List<SavedRecording> {
    if (folderUri.isBlank()) return emptyList()
    return try {
        val tree = folderUri.toUri()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val documentId = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val name = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val size = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modified = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val mime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            buildList {
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(name) ?: continue
                    // `config` is a directory, and the exports inside it are deliberately not
                    // recordings — the same exclusion the MediaStore query gets for free by matching
                    // the folder exactly rather than as a prefix.
                    if (cursor.getString(mime) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    if (displayName in skip) continue
                    val id = cursor.getString(documentId) ?: continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    val details = if (recordingKindOf(displayName) == RecordingKind.Recording) {
                        detailsOf(context, uri)
                    } else {
                        null
                    }
                    add(
                        SavedRecording(
                            uri = uri,
                            cacheId = documentCacheId(id),
                            name = displayName,
                            sizeBytes = cursor.getLong(size),
                            // Already millis here, unlike MediaStore's DATE_ADDED.
                            savedAtMillis = cursor.getLong(modified),
                            summary = details?.summary,
                            fixChannelId = details?.topics?.let(McapTrack::fixChannel)?.channelId,
                            tags = details?.tags.orEmpty(),
                        )
                    )
                }
            }
        }.orEmpty()
    } catch (t: Throwable) {
        // A grant can be revoked in system settings, or the folder deleted, and neither is worth
        // failing the whole listing over — this half simply contributes nothing.
        Log.w(TAG, "could not list the granted folder", t)
        emptyList()
    }
}

/**
 * A track-cache key for a document id, which is a string where the cache wants a number.
 *
 * **Always negative**, so it can never collide with a MediaStore id — those are always positive, and
 * the two kinds of row share one cache directory.
 */
internal fun documentCacheId(documentId: String): Long = -(documentId.hashCode().toLong() and 0xFFFFFFFFL) - 1

/**
 * Whether the stored folder grant is one the system still honours.
 *
 * **The stored string is not the permission.** A grant can be taken back in Android's settings at any
 * time, and the preference knows nothing about it — so trusting the string would leave the Files list
 * quietly short of half the folder with the offer to fix it hidden, which is the exact failure the
 * grant exists to end. `persistedUriPermissions` is the system's own answer and the only one worth
 * asking.
 */
fun recordingsFolderGranted(context: Context, folderUri: String): Boolean {
    if (folderUri.isBlank()) return false
    return context.contentResolver.persistedUriPermissions.any {
        it.isReadPermission && it.uri.toString() == folderUri
    }
}

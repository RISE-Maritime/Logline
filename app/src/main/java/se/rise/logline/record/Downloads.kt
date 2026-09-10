package se.rise.logline.record

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import java.io.IOException
import java.io.OutputStream

/** Where this app's recordings turn up when no folder has been chosen. */
const val DOWNLOADS_FOLDER = "Logline"

/**
 * Where the documents this app exports turn up: settings profiles, a platform's geometry, the platform library.
 *
 * **A subfolder, and that is what keeps them out of the Recordings tab.** `savedRecordings()` matches
 * `RELATIVE_PATH` for exactly `Download/Logline/`, so anything a level down is not a row in that list
 * by construction rather than by a filter somebody has to remember to keep working. The listing of a
 * *chosen* folder skips directories for the same reason, so the arrangement holds in both places.
 *
 * They shared a folder until now, which put a settings profile in a list called Recordings reading
 * "982 B · no summary" — a description of a recording that failed rather than of a file that is exactly
 * as it should be. Naming the kinds fixed the label; this fixes the filing.
 */
const val CONFIG_FOLDER_NAME = "config"

const val CONFIG_FOLDER = "$DOWNLOADS_FOLDER/$CONFIG_FOLDER_NAME"

/** What is being written, which is the whole of what decides the subfolder. */
enum class OutputKind {
    /** An MCAP file. Goes to the folder itself. */
    Recording,

    /** A settings profile, a platform's geometry, the platform library. Goes into `config`. */
    Export,
}

/**
 * The suffix a file wears while it is still being copied.
 *
 * `MediaStore` has `IS_PENDING` for this and the Storage Access Framework has nothing, so a chosen
 * folder gets the same guarantee by construction: the bytes land under a name no reader will take for
 * a recording, and the file is renamed once the copy has finished. A file manager scanning mid-copy
 * must never show a half-written 512 MB recording as if it were a whole one.
 */
internal const val PARTIAL_SUFFIX = ".part"

/** Whether a name belongs to a copy that is still in flight, and so to nothing worth listing. */
fun isPartialName(name: String): Boolean = name.endsWith(PARTIAL_SUFFIX)

/**
 * Write a file where the user can actually get at it.
 *
 * App-private storage is right for anything the app is still using — recordings in progress, TLS
 * credentials, map tiles — and useless for something a person is meant to email to a colleague.
 *
 * Two destinations, one entry point. With no folder chosen this is `Downloads/Logline` through
 * `MediaStore`, which is the only route to the shared Downloads collection that needs no storage
 * permission on any supported API level. With a folder chosen it is that folder, through the tree
 * grant `Settings.recordingsFolderUri` holds — the same grant the Files tab lists from, so the app
 * cannot end up filling one place and listing another.
 *
 * Shared by the MCAP recorder and the exports rather than written twice: this is the piece with the
 * pending flag, the folder names and the failure mode in it, and two copies would eventually disagree
 * about all three.
 *
 * **Nothing falls back.** A grant taken back in Android's settings, or a card pulled out, throws —
 * and `Recorder.publish` then keeps the recording in app-private storage and says so, leaving the
 * launch sweep to publish it once the folder is there again. Quietly writing somewhere the operator
 * did not choose would be the worse failure, and losing the run would be worse still.
 *
 * @throws IOException if the entry could not be created or opened.
 */
internal fun saveOutput(
    context: Context,
    fileName: String,
    mimeType: String,
    kind: OutputKind,
    /** `Settings.recordingsFolderUri`. Blank is the default, `Downloads/Logline`. */
    treeUri: String,
    write: (OutputStream) -> Unit,
) {
    if (treeUri.isBlank()) {
        saveToDownloads(context, fileName, mimeType, kind, write)
    } else {
        saveToTree(context, fileName, mimeType, kind, treeUri.toUri(), write)
    }
}

/** The default destination: `Downloads/Logline`, or `Downloads/Logline/config` for an export. */
private fun saveToDownloads(
    context: Context,
    fileName: String,
    mimeType: String,
    kind: OutputKind,
    write: (OutputStream) -> Unit,
) {
    val folder = if (kind == OutputKind.Export) CONFIG_FOLDER else DOWNLOADS_FOLDER
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, mimeType)
        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folder")
        // Hidden until it is complete, so a file manager scanning mid-copy never shows a half-written
        // recording as if it were a whole one.
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("Downloads is not accepting new files")
    resolver.openOutputStream(uri)?.use(write)
        ?: throw IOException("could not open $fileName for writing")
    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
}

/**
 * The chosen destination, through the Storage Access Framework.
 *
 * `DocumentsContract` directly rather than `androidx.documentfile`: the listing beside this already
 * speaks it, and a wrapper for four calls is a dependency that ships.
 *
 * The name is taken from the document the provider actually created rather than the one asked for.
 * Providers deduplicate a collision by appending to the name and some append an extension of their
 * own, so the rename at the end has to work from what is there.
 */
private fun saveToTree(
    context: Context,
    fileName: String,
    mimeType: String,
    kind: OutputKind,
    tree: Uri,
    write: (OutputStream) -> Unit,
) {
    val resolver = context.contentResolver
    val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    val parent = if (kind == OutputKind.Export) configFolderIn(context, tree, root) else root

    val partial = DocumentsContract.createDocument(resolver, parent, mimeType, fileName + PARTIAL_SUFFIX)
        ?: throw IOException("could not create $fileName in the chosen folder")
    try {
        resolver.openOutputStream(partial)?.use(write)
            ?: throw IOException("could not open $fileName for writing")
        DocumentsContract.renameDocument(resolver, partial, fileName)
            ?: throw IOException("could not name $fileName once it was written")
    } catch (t: Throwable) {
        // A half-written file left under a name nothing lists is still litter in somebody's folder.
        runCatching { DocumentsContract.deleteDocument(resolver, partial) }
        throw t
    }
}

/**
 * The `config` directory inside the chosen folder, created the first time something is exported.
 *
 * Found rather than assumed: `createDocument` on a name that already exists makes a *second*
 * directory called `config (1)`, so a fleet phone exporting a profile a week would end up with a
 * folder full of them.
 */
private fun configFolderIn(context: Context, tree: Uri, root: Uri): Uri {
    val resolver = context.contentResolver
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(
        tree,
        DocumentsContract.getDocumentId(root),
    )
    resolver.query(
        children,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        val id = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val name = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
        while (cursor.moveToNext()) {
            if (cursor.getString(name) == CONFIG_FOLDER_NAME &&
                cursor.getString(mime) == DocumentsContract.Document.MIME_TYPE_DIR
            ) {
                return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(id))
            }
        }
    }
    return DocumentsContract.createDocument(
        resolver,
        root,
        DocumentsContract.Document.MIME_TYPE_DIR,
        CONFIG_FOLDER_NAME,
    ) ?: throw IOException("could not create a config folder in the chosen folder")
}

/**
 * A chosen folder as a person would name it: `Download/Logline`, `Recordings`, `SD card/trips`.
 *
 * A tree document id is `volume:path` — `primary:Download/Logline` for internal storage, a
 * `1234-5678:` prefix for a card — and the volume half is an identifier rather than a name. The path
 * is what somebody recognises, so that is what is shown, with the volume kept only when it is not the
 * built-in one and there would otherwise be nothing to show.
 *
 * Pure, and pinned by `OutputFolderTest`, because the alternative is reading it off a phone that
 * happens to have one card in it.
 */
fun folderLabelOf(documentId: String): String {
    val colon = documentId.indexOf(':')
    if (colon < 0) return documentId
    val volume = documentId.substring(0, colon)
    // **Only split an id that is actually `volume:path`.** A cloud provider's document id can carry a
    // colon and mean nothing by it, and cutting one of those in half produces a label that looks like
    // a path and is not. Shown whole instead, which is at least true.
    if (!volume.matches(STORAGE_VOLUME)) return documentId
    val path = documentId.substring(colon + 1).trim('/')
    return when {
        path.isNotEmpty() && volume == PRIMARY_VOLUME -> path
        // A card or a stick keeps its serial: two `trips` folders on two volumes are not the same
        // folder, and the operator is the one who has to tell them apart.
        path.isNotEmpty() -> "$volume/$path"
        // The root of a volume, where the path is empty and the identifier is all there is.
        else -> volume
    }
}

/** What the framework calls built-in storage; a card or a stick is a serial number instead. */
private const val PRIMARY_VOLUME = "primary"

/** `primary`, or the four-and-four serial the framework gives a removable volume. */
private val STORAGE_VOLUME = Regex("$PRIMARY_VOLUME|[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")

/** What the default destination is called on screen, so the two places that say it cannot differ. */
const val DEFAULT_FOLDER_LABEL = "Downloads/$DOWNLOADS_FOLDER"

/**
 * The chosen folder as it should appear on screen, or the default when there is none.
 *
 * **A document id is only a path on a storage volume.** Every other provider spells its ids however
 * it likes: a Drive folder granted on the dev phone came back as
 * `acc=1;doc=encoded=E0ykFiymkwJqjorOU52y7snEhi7…`, which [folderLabelOf] can only show verbatim and
 * which is not a name anybody would recognise. So the provider is asked what the folder is called,
 * and its answer wins wherever there is no path to show.
 *
 * The `Context` half is here rather than in the caller so the fallback order lives with the parsing
 * it falls back to. Resolved once in `App()` and handed down, like every other value a screen needs a
 * `Context` for.
 */
fun folderLabel(context: Context, treeUri: String): String {
    if (treeUri.isBlank()) return DEFAULT_FOLDER_LABEL
    return try {
        val tree = treeUri.toUri()
        val documentId = DocumentsContract.getTreeDocumentId(tree)
        val path = folderLabelOf(documentId)
        // A path came back changed, which is what says it *was* one.
        if (path != documentId) path else displayNameOf(context, tree) ?: documentId
    } catch (t: Throwable) {
        // A grant that has gone, or an id this cannot parse. The screen says where files go by
        // default, which is where they will go once the folder is chosen again.
        DEFAULT_FOLDER_LABEL
    }
}

private fun displayNameOf(context: Context, tree: Uri): String? {
    val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    return context.contentResolver.query(
        document,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
    }
}

package se.rise.logline.calibrate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.util.Log
import se.rise.logline.sensors.ScaledJpeg
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

private const val TAG = "PlatformPhotos"

/**
 * How big a stored photo is kept, and at what quality.
 *
 * The cap is the **long edge**, not the width: capping width alone leaves a portrait photograph taller
 * than the number says, and this figure exists to bound a file rather than to frame a picture.
 *
 * Measured on a Pixel 6: a 3072x4080 camera photograph of 1.98 MB stored as 1280x964 and **120 kB**,
 * a factor of sixteen. That is what makes keeping these in cloud backup defensible — the quota is
 * **25 MB for the whole app** and a single file over it fails the entire backup, settings included, so
 * a library would need on the order of two hundred platforms to threaten one. Raising either number is
 * therefore a decision about `data_extraction_rules.xml` as much as about picture quality.
 */
const val PHOTO_MAX_EDGE = 1280
const val PHOTO_QUALITY = 80

/**
 * A picture of each platform, stored on this phone and nowhere else.
 *
 * **It is deliberately not part of [PlatformCalibration].** That struct is the platform *document* —
 * what the exporter writes, what `configuration_json` carries, what `get_config` replies with — and
 * upstream's schema is `additionalProperties: false` at every level, so a photo field would fork the
 * format. It could not go on the bus either: `configuration_json` is republished every ten seconds,
 * and a 250 kB base64 photo on that loop is ~90 MB/h to say something that has not changed since the
 * boat was built. The same argument the publish flag makes for living outside the document.
 *
 * So the file name *is* the key — no preference, no path stored anywhere, nothing to leave dangling.
 * Two consequences follow and both are handled at the call site in `MainActivity`, because they are
 * the two moments an entity id stops naming what it named: a **rename** has to move the file, and a
 * **delete** has to remove it, or the next platform to take that id inherits a stranger's boat.
 */
/**
 * The one spelling of where the photographs live.
 *
 * `filesDir`, like the TLS credentials and for a weaker version of the same reason — this is not a
 * secret, but it is not something another app has any business reading either. One function rather
 * than two string literals, the same argument `osmdroidBasePath()` makes: two spellings would leave a
 * folder of pictures sitting next to a library that shows none of them.
 */
fun platformPhotos(context: Context) = PlatformPhotos(File(context.filesDir, PHOTO_DIRECTORY))

/** Also named in `backup_rules.xml` and `data_extraction_rules.xml`, which is why it is a constant. */
const val PHOTO_DIRECTORY = "platforms"

class PlatformPhotos(private val root: File) {

    /** The stored photo, or null — including for a blank entity id, which names no platform. */
    fun photo(entityId: String): File? = fileFor(entityId)?.takeIf { it.isFile }

    fun write(entityId: String, jpeg: ByteArray) {
        val file = fileFor(entityId) ?: return
        root.mkdirs()
        file.writeBytes(jpeg)
    }

    fun remove(entityId: String) {
        fileFor(entityId)?.delete()
    }

    /**
     * Follow a rename. The destination is overwritten, since the id is the identity and a file already
     * sitting there belongs to a platform that no longer exists under that name.
     */
    fun move(fromEntityId: String, toEntityId: String) {
        if (fromEntityId == toEntityId) return
        val from = fileFor(fromEntityId)?.takeIf { it.isFile } ?: return
        val to = fileFor(toEntityId) ?: return
        root.mkdirs()
        if (!from.renameTo(to)) {
            // Same directory, so a rename only fails for something worth knowing about.
            Log.w(TAG, "could not move ${from.name} to ${to.name}")
        }
    }

    private fun fileFor(entityId: String): File? =
        entityId.takeIf { it.isNotBlank() }?.let { File(root, photoFileName(it)) }
}

/**
 * Somewhere for the camera app to write, as a `content://` URI it is allowed to reach.
 *
 * One fixed name rather than a unique one per capture: only one capture can be in flight, the file is
 * consumed within seconds, and a temp directory that accumulates photographs of boats is a worse
 * outcome than an overwrite. `mkdirs()` because `FileProvider` will not vend a URI under a directory
 * that does not exist, and the failure is an opaque `IllegalArgumentException` rather than anything
 * naming the path.
 */
fun platformPhotoCaptureFile(context: Context): File {
    val directory = File(context.cacheDir, PHOTO_CAPTURE_DIRECTORY).apply { mkdirs() }
    return File(directory, "capture.jpg")
}

/** Matches `res/xml/file_paths.xml`, which is why it is a constant rather than a literal in two places. */
const val PHOTO_CAPTURE_DIRECTORY = "photo-capture"

/**
 * A file name that survives whatever somebody typed into the entity id field.
 *
 * Entity ids are slugs by default and free text in fact, so anything outside `[A-Za-z0-9._-]` is
 * percent-encoded — reversible, collision-free, and it cannot produce `.`, `..` or a path separator.
 * Uppercase hex through `Locale.ROOT`, because `%X` on a Turkish phone is its own small adventure.
 *
 * The extension is honest: [importPlatformPhoto] re-encodes everything it stores as JPEG, whatever
 * arrived. It did not always, which is how a PNG screenshot came to be sitting in a `.jpg` — see the
 * note there for why passing an already-small picture through was the wrong economy here.
 */
fun photoFileName(entityId: String): String = buildString {
    entityId.forEach { c ->
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == '_') {
            append(c)
        } else {
            append(String.format(Locale.ROOT, "%%%02X", c.code))
        }
    }
    append(".jpg")
}

/**
 * How far to turn a picture that carries an EXIF orientation.
 *
 * Written against the tag's own numbers rather than `ExifInterface`'s constants so it can be tested:
 * those are `android.media` statics, which a JVM unit test reads as zero and would have every case
 * agree with every other. 6 is `ROTATE_90`, 3 is `ROTATE_180`, 8 is `ROTATE_270`.
 *
 * The four mirrored orientations (2, 4, 5, 7) are left alone deliberately. They come from a flipped
 * front camera and essentially never from a photograph of a boat, and a wrong flip is worse than none
 * — it silently puts the port side to starboard in a picture somebody is using to place sensors.
 */
fun photoRotationDegrees(exifOrientation: Int): Int = when (exifOrientation) {
    6 -> 90
    3 -> 180
    8 -> 270
    else -> 0
}

/**
 * The decode's downsample factor: the largest power of two that still leaves the long edge at or above
 * [PHOTO_MAX_EDGE], so the full-size bitmap never has to exist.
 *
 * Pure, and tested, because getting it one power out is invisible — the picture still appears, just
 * softer or having briefly held four times the memory.
 */
fun photoSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= maxEdge) sample *= 2
    return sample
}

/**
 * Read a picked image, shrink it, turn it the right way up, and **always re-encode it as JPEG**.
 *
 * Deliberately not `scaleJpeg`, which the camera uses: that returns its input untouched when it is
 * already small enough, which is right on a publish path where the input is known to be a JPEG and an
 * extra encode costs frames. Here the input is whatever somebody picked, so passing it through stores
 * a PNG under a `.jpg` name — measured: a 1080x2400 screenshot went in and came straight back out —
 * and, worse, leaves a lossless multi-megabyte file where the backup arithmetic assumes a few hundred
 * kilobytes. Re-encoding once, on an image picked by hand, costs nothing anybody can perceive.
 *
 * The rotation is not optional either: `BitmapFactory` ignores EXIF and `Bitmap.compress` does not
 * write it back, so a phone photograph stored as-is would be sideways *permanently* — the tag saying
 * which way up it goes is gone by then. Folded into the same matrix as the scale, so there is one
 * decode and one encode however far it has to turn.
 *
 * Returns null rather than throwing: a picker can hand back a file that has been deleted, a format
 * this device cannot decode, or a panorama that will not fit in memory, and none of those is worth
 * more than a line on the screen.
 */
fun importPlatformPhoto(context: Context, uri: Uri): ByteArray? = importPhoto(context, uri)?.jpeg

/**
 * [importPlatformPhoto]'s working half, which also says how large the result came out.
 *
 * A platform photograph never needed the dimensions — it is displayed from the file. Checklist
 * evidence does: the metadata travels on a different subject from the bytes, so a station that has
 * not fetched a photo still has to lay out a tile for it, and `ChecklistItemEvidence` carries
 * `width`/`height` for exactly that. One scale-rotate-re-encode path either way, which is the rule
 * this file already states.
 */
fun importPhoto(context: Context, uri: Uri): ScaledJpeg? = runCatching {
    val source = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val rotation = context.contentResolver.openInputStream(uri)?.use { stream ->
        photoRotationDegrees(ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1))
    } ?: 0

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = photoSampleSize(bounds.outWidth, bounds.outHeight, PHOTO_MAX_EDGE)
    }
    val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, options) ?: return null
    try {
        val edge = maxOf(decoded.width, decoded.height)
        val factor = if (edge > PHOTO_MAX_EDGE) PHOTO_MAX_EDGE.toFloat() / edge else 1f
        val matrix = Matrix().apply {
            postScale(factor, factor)
            postRotate(rotation.toFloat())
        }
        val out = if (factor == 1f && rotation == 0) {
            decoded
        } else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }
        try {
            ScaledJpeg(
                jpeg = ByteArrayOutputStream()
                    .also { out.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, it) }
                    .toByteArray(),
                width = out.width,
                height = out.height,
            )
        } finally {
            if (out !== decoded) out.recycle()
        }
    } finally {
        decoded.recycle()
    }
}.onFailure { Log.w(TAG, "could not read the picked image", it) }.getOrNull()

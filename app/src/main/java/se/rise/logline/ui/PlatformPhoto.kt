package se.rise.logline.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A platform's picture, either the stored one or one just picked and not yet saved.
 *
 * Two sources rather than one because the editor is transactional: a picked photo has to be visible
 * before Save, and must not exist on disk until Save — otherwise Cancel would leave it behind, on a
 * screen where every other change can be taken back.
 */
sealed interface PlatformPhotoSource {
    data class Stored(val file: File) : PlatformPhotoSource

    /**
     * Held in memory rather than as a temporary file. At [se.rise.logline.calibrate.PHOTO_MAX_EDGE]
     * that is a few hundred kilobytes, which buys away a temp file nobody has to remember to clean up
     * on cancel, on a crash, or when a second pick replaces the first.
     */
    class Picked(val jpeg: ByteArray) : PlatformPhotoSource
}

/**
 * What to re-decode on.
 *
 * A path alone is not enough: committing a pick writes the *same* path, so a `produceState` keyed on
 * it would go on showing the previous picture. `Picked` keys on array identity, so replacing a pick
 * with another of the same length still redraws.
 */
private val PlatformPhotoSource.cacheKey: String
    get() = when (this) {
        is PlatformPhotoSource.Stored -> "${file.path}@${file.lastModified()}"
        is PlatformPhotoSource.Picked -> "picked@${System.identityHashCode(jpeg)}"
    }

/**
 * Decoded off the main thread, the same shape a recording's track uses — a full-size JPEG decode is
 * milliseconds rather than microseconds, and this draws in a list.
 */
@Composable
fun PlatformPhoto(
    photo: PlatformPhotoSource?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(null, photo?.cacheKey) {
        value = photo?.let { source ->
            withContext(Dispatchers.IO) {
                when (source) {
                    is PlatformPhotoSource.Stored ->
                        BitmapFactory.decodeFile(source.file.path)
                    is PlatformPhotoSource.Picked ->
                        BitmapFactory.decodeByteArray(source.jpeg, 0, source.jpeg.size)
                }?.asImageBitmap()
            }
        }
    }

    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                image,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                // Cropped rather than fitted: every photo is a different shape and a letterboxed row
                // is mostly empty box. What a boat is recognised by is in the middle of the frame.
                contentScale = ContentScale.Crop,
            )
        } else if (photo == null) {
            // An empty frame reads as "no picture"; a spinner here would say "still loading" about a
            // platform nobody has photographed, which is a different and wrong statement.
            Icon(
                IconImage,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

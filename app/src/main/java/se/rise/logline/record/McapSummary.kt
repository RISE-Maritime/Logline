package se.rise.logline.record

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

private const val TAG = "McapSummary"

/** Eight bytes, opening and closing the file. */
private const val MAGIC_SIZE = 8

/** Every record is an opcode byte and a little-endian uint64 length. */
private const val RECORD_HEADER_SIZE = 9

/** `summary_start` + `summary_offset_start` + `summary_crc`. */
private const val FOOTER_BODY_SIZE = 20

/**
 * What a finished recording says about itself, without reading a byte of its messages.
 *
 * @param startNanos the earliest message's log time; [endNanos] the latest.
 */
data class McapSummary(
    val messages: Long,
    val channels: Int,
    val startNanos: Long,
    val endNanos: Long,
) {
    /** Zero for a file with one message, which is a real answer rather than a missing one. */
    val durationMillis: Long get() = ((endNanos - startNanos) / 1_000_000L).coerceAtLeast(0L)
}

/**
 * Read a recording's `Statistics` record — message count and time range — from its summary section.
 *
 * The deliberate mirror of [McapWriter.finish], and it has to stay one: the field order below is that
 * function's `writeStatistics()` read backwards, and nothing but a test comparing the two would catch
 * them drifting apart. It uses `McapWriter`'s own opcodes rather than respelling them for the same
 * reason.
 *
 * Cheap by construction. The footer sits at a known offset from the end and points at the summary, so
 * this is two seeks and about forty bytes — a 512 MB recording costs what a small one does.
 *
 * Takes a [FileChannel] because that is the one seekable thing both callers can produce: the screen
 * has a `content://` URI and gets there through `openFileDescriptor`, the tests have a `File`.
 *
 * **Null means the file does not say, and that is a real state.** [McapRecovery.finalise] writes
 * `summary_start = 0` — the spec's "no summary" — for a recording rescued from a killed process: every
 * message is present and the statistics are gone. Returning zeroes there would report a good recording
 * as an empty one.
 */
fun readMcapSummary(channel: FileChannel): McapSummary? {
    val size = channel.size()
    if (size < MAGIC_SIZE * 2L + RECORD_HEADER_SIZE + FOOTER_BODY_SIZE) return null

    return try {
        // The closing magic is what says the file was finished. Without it there is no footer to trust.
        if (!channel.read(MAGIC_SIZE, size - MAGIC_SIZE).matchesMagic()) return null

        val footer = channel.read(RECORD_HEADER_SIZE + FOOTER_BODY_SIZE, size - MAGIC_SIZE - FOOTER_BODY_SIZE - RECORD_HEADER_SIZE)
        if (footer.get().toInt() != McapWriter.OP_FOOTER) return null
        footer.long // the footer's own length, already known
        val summaryStart = footer.long
        // 0 is the spec's "no summary section" — see the note above.
        if (summaryStart <= 0L || summaryStart >= size) return null

        statisticsIn(channel, summaryStart, size)
    } catch (t: Throwable) {
        // A truncated or foreign file is not an error worth surfacing: the caller shows size and date
        // and says nothing about messages, which is the honest answer for a file it cannot read.
        Log.i(TAG, "no readable summary", t)
        null
    }
}

/** Walk the summary section's records looking for `Statistics`. */
private fun statisticsIn(channel: FileChannel, from: Long, size: Long): McapSummary? {
    var offset = from
    while (offset + RECORD_HEADER_SIZE <= size) {
        val header = channel.read(RECORD_HEADER_SIZE, offset)
        val opcode = header.get().toInt() and 0xFF
        val length = header.long
        if (length < 0 || offset + RECORD_HEADER_SIZE + length > size) return null
        if (opcode == McapWriter.OP_STATISTICS) {
            return channel.read(STATISTICS_PREFIX_SIZE, offset + RECORD_HEADER_SIZE).readStatistics()
        }
        offset += RECORD_HEADER_SIZE + length
    }
    return null
}

/** Up to and including the two timestamps; the channel-count map after them is not read. */
private const val STATISTICS_PREFIX_SIZE = 8 + 2 + 4 + 4 + 4 + 4 + 8 + 8

/**
 * The body of a `Statistics` record, field for field as `McapWriter.writeStatistics()` writes it.
 *
 * Four of the eight fields are wanted and every one before them still has to be stepped over, in the
 * right width — which is exactly why this is pinned by a test that writes a file and reads it back
 * rather than by anyone eyeballing the offsets.
 */
private fun ByteBuffer.readStatistics(): McapSummary {
    val messages = long
    short // schema count
    val channels = int
    int // attachment count
    int // metadata count
    int // chunk count
    val earliest = long
    val latest = long
    return McapSummary(
        messages = messages,
        channels = channels,
        startNanos = earliest,
        endNanos = latest,
    )
}

/**
 * [count] bytes at [at], little-endian and ready to read.
 *
 * Little-endian on the buffer rather than byte-swapping every field by hand: the format is
 * little-endian throughout, and saying so once is harder to get wrong than saying it eight times.
 */
private fun FileChannel.read(count: Int, at: Long): ByteBuffer {
    val buffer = ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN)
    var position = at
    while (buffer.hasRemaining()) {
        val read = read(buffer, position)
        if (read <= 0) break
        position += read
    }
    buffer.flip()
    return buffer
}

private fun ByteBuffer.matchesMagic(): Boolean {
    val bytes = ByteArray(remaining())
    get(bytes)
    return bytes.contentEquals(McapWriter.MAGIC)
}

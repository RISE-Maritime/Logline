package se.rise.logline.record

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

private const val TAG = "McapSummary"

/** Eight bytes, opening and closing the file. */
internal const val MAGIC_SIZE = 8

/** Every record is an opcode byte and a little-endian uint64 length. */
internal const val RECORD_HEADER_SIZE = 9

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
fun readMcapSummary(channel: FileChannel): McapSummary? = try {
    summaryStartOf(channel)?.let { statisticsIn(channel, it, channel.size()) }
} catch (t: Throwable) {
    // A truncated or foreign file is not an error worth surfacing: the caller shows size and date
    // and says nothing about messages, which is the honest answer for a file it cannot read.
    Log.i(TAG, "no readable summary", t)
    null
}

/**
 * Where the summary section begins, or null when the file does not have one.
 *
 * Shared by [readMcapSummary] and [readMcapDetails] rather than written twice: the footer walk is four
 * exact offsets and a magic check, and two copies of it is two things to keep in step with the writer.
 */
private fun summaryStartOf(channel: FileChannel): Long? {
    val size = channel.size()
    if (size < MAGIC_SIZE * 2L + RECORD_HEADER_SIZE + FOOTER_BODY_SIZE) return null

    // The closing magic is what says the file was finished. Without it there is no footer to trust.
    if (!channel.read(MAGIC_SIZE, size - MAGIC_SIZE).matchesMagic()) return null

    val footer = channel.read(
        RECORD_HEADER_SIZE + FOOTER_BODY_SIZE,
        size - MAGIC_SIZE - FOOTER_BODY_SIZE - RECORD_HEADER_SIZE,
    )
    if (footer.get().toInt() != McapWriter.OP_FOOTER) return null
    footer.long // the footer's own length, already known
    val summaryStart = footer.long
    // 0 is the spec's "no summary section" — see the note on [readMcapSummary].
    return summaryStart.takeIf { it > 0L && it < size }
}

/** One topic in a recording, and how many messages it holds. */
data class TopicCount(val channelId: Int, val topic: String, val messages: Long)

/**
 * Everything the summary section says: the statistics, and every topic with its own message count.
 *
 * **As cheap as [readMcapSummary]** — a few seeks and a few hundred bytes, whatever the file's size —
 * because `McapWriter.finish` puts the Channel records in the summary alongside Statistics, and
 * `writeStatistics()` already emits `channelMessageCounts`. Nothing here touches the data section.
 *
 * A second reader of the same records, and the same warning applies: the field widths below are
 * `writeChannel()` and `writeStatistics()` read backwards, and only a test that writes a file and reads
 * it back will catch them drifting apart.
 */
data class McapDetails(
    val summary: McapSummary,
    val topics: List<TopicCount>,
    /** What the operator had switched on when the file closed. Empty when it carries none. */
    val tags: Set<String> = emptySet(),
)

fun readMcapDetails(channel: FileChannel): McapDetails? = try {
    val size = channel.size()
    summaryStartOf(channel)?.let { start -> detailsIn(channel, start, size) }
} catch (t: Throwable) {
    Log.i(TAG, "no readable details", t)
    null
}

private fun detailsIn(channel: FileChannel, from: Long, size: Long): McapDetails? {
    val topics = LinkedHashMap<Int, String>()
    var counts: Map<Int, Long> = emptyMap()
    var summary: McapSummary? = null
    var tags: Set<String> = emptySet()

    var offset = from
    while (offset + RECORD_HEADER_SIZE <= size) {
        val header = channel.read(RECORD_HEADER_SIZE, offset)
        val opcode = header.get().toInt() and 0xFF
        val length = header.long
        if (length < 0 || offset + RECORD_HEADER_SIZE + length > size) break
        val body = offset + RECORD_HEADER_SIZE
        when (opcode) {
            McapWriter.OP_CHANNEL -> channel.read(length.toInt(), body).readChannel()
                ?.let { (id, topic) -> topics[id] = topic }
            // The summary holds only an *index* to the metadata; the record itself lives in the data
            // section, one seek back. Cheap, and it keeps the tags out of the scan the track needs.
            McapWriter.OP_METADATA_INDEX -> {
                channel.read(length.toInt(), body).readMetadataIndex()?.let { at ->
                    tags = readTagsAt(channel, at, size)
                }
            }
            McapWriter.OP_STATISTICS -> {
                val bytes = channel.read(length.toInt(), body)
                summary = bytes.readStatistics()
                counts = bytes.readChannelCounts()
            }
        }
        offset += RECORD_HEADER_SIZE + length
    }

    val stats = summary ?: return null
    return McapDetails(
        summary = stats,
        // Busiest first: on a run with forty subjects the question is which of them dominates the file.
        topics = topics.map { (id, topic) -> TopicCount(id, topic, counts[id] ?: 0L) }
            .sortedByDescending { it.messages },
        tags = tags,
    )
}

/** `writeChannel()` read backwards: id, schema id, topic, encoding, empty metadata map. */
/** A Channel record's id and topic. Shared with [McapTrack], which reads the same record inline. */
internal fun ByteBuffer.readChannel(): Pair<Int, String>? {
    if (remaining() < 2 + 2 + 4) return null
    val id = short.toInt() and 0xFFFF
    short // schema id
    val topicLength = int
    if (topicLength < 0 || topicLength > remaining()) return null
    val topic = ByteArray(topicLength).also { get(it) }.toString(Charsets.UTF_8)
    return id to topic
}

/**
 * The `channelMessageCounts` map, which sits after everything [readStatistics] reads.
 *
 * Its own function because the buffer has to be positioned past the prefix first — reading it inside
 * `readStatistics` would make that function's contract "and also leaves the cursor somewhere", which is
 * how the next person breaks it.
 */
private fun ByteBuffer.readChannelCounts(): Map<Int, Long> {
    position(STATISTICS_PREFIX_SIZE)
    if (remaining() < 4) return emptyMap()
    val bytes = int
    if (bytes < 0 || bytes > remaining()) return emptyMap()
    val out = LinkedHashMap<Int, Long>()
    var read = 0
    while (read + 10 <= bytes) {
        out[short.toInt() and 0xFFFF] = long
        read += 10
    }
    return out
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

/** A MetadataIndex's offset, if it names the record this app writes. */
private fun ByteBuffer.readMetadataIndex(): Long? {
    if (remaining() < 8 + 8 + 4) return null
    val offset = long
    long // record length, which is not needed: the record states its own
    val nameLength = int
    if (nameLength < 0 || nameLength > remaining()) return null
    val name = ByteArray(nameLength).also { get(it) }.toString(Charsets.UTF_8)
    return offset.takeIf { name == McapWriter.METADATA_TAGS && it >= 0 }
}

/**
 * The tags out of a Metadata record at a known offset.
 *
 * Tolerant throughout: a file whose index points somewhere unhelpful loses its tags and keeps
 * everything else, which is the right trade for a decoration on a recording.
 */
private fun readTagsAt(channel: FileChannel, at: Long, size: Long): Set<String> = try {
    if (at + RECORD_HEADER_SIZE > size) emptySet() else {
        val header = channel.read(RECORD_HEADER_SIZE, at)
        val opcode = header.get().toInt() and 0xFF
        val length = header.long
        if (opcode != McapWriter.OP_METADATA || length <= 0 || at + RECORD_HEADER_SIZE + length > size) {
            emptySet()
        } else {
            channel.read(length.toInt(), at + RECORD_HEADER_SIZE).readMetadataTags()
        }
    }
} catch (t: Throwable) {
    emptySet()
}

/** `name`, then a length-prefixed `map<string, string>` in which `tags` is the entry wanted. */
private fun ByteBuffer.readMetadataTags(): Set<String> {
    if (remaining() < 4) return emptySet()
    val nameLength = int
    if (nameLength < 0 || nameLength > remaining()) return emptySet()
    position(position() + nameLength)
    if (remaining() < 4) return emptySet()
    val mapBytes = int
    if (mapBytes < 0 || mapBytes > remaining()) return emptySet()
    val end = position() + mapBytes
    while (position() + 4 <= end) {
        val keyLength = int
        if (keyLength < 0 || position() + keyLength > end) return emptySet()
        val key = ByteArray(keyLength).also { get(it) }.toString(Charsets.UTF_8)
        if (position() + 4 > end) return emptySet()
        val valueLength = int
        if (valueLength < 0 || position() + valueLength > end) return emptySet()
        val value = ByteArray(valueLength).also { get(it) }.toString(Charsets.UTF_8)
        if (key == McapWriter.METADATA_TAGS) return parseTags(value)
    }
    return emptySet()
}

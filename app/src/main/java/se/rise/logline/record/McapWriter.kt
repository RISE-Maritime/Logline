package se.rise.logline.record

import java.io.ByteArrayOutputStream
import com.github.luben.zstd.Zstd
import java.io.OutputStream

/**
 * A minimal MCAP writer.
 *
 * Hand-written because **there is no MCAP library for the JVM** — Foxglove ships C++, Go, Python, Rust,
 * Swift and TypeScript, and `dev.foxglove` does not exist on Maven Central. The write path is small
 * though: MCAP is a sequence of little-endian, length-prefixed records.
 *
 * Messages are buffered into **zstd-compressed Chunk records**; schemas, channels and the whole summary
 * section stay outside them, which is what keeps the summary readable without decompressing anything.
 *
 * It used to write neither chunks nor compression — the least dense form the spec allows — and the cost
 * was measured rather than guessed: a 38.4 MB capture held 17.4 MB of payload across 668 039 messages,
 * so **55% of the file was framing**. Thirty-one fixed bytes per message, against a ~26 byte average
 * payload: an eight-byte length whose top five bytes are always zero, two absolute epoch timestamps
 * differing only in their low bytes, a dense sequence counter. Close to a worst case stored raw and
 * close to a best case for an entropy coder — the same file compresses about 3×.
 *
 * zstd rather than lz4 because MCAP standardises only those two and zstd measured the better ratio;
 * Java's built-in `Deflater` is not a legal MCAP compression and would produce files Foxglove and
 * `mcap-python` refuse. Keelson's own `keelson2mcap.py` writes zstd chunks by default, so this brings
 * the app *towards* the reference rather than away from it.
 *
 * Deliberately free of Android types so the format can be tested on the JVM, where the assertions can
 * be about bytes rather than about what a screenshot looks like.
 */
class McapWriter(private val sink: OutputStream) {

    private var bytes = 0L
    private var messages = 0L

    /** Messages accumulate here until the chunk is flushed. Schemas and channels never do. */
    private val chunk = Buffer()
    private var chunkMessages = 0
    private var chunkStartNanos = 0L
    private var chunkEarliest = Long.MAX_VALUE
    private var chunkLatest = Long.MIN_VALUE
    private var chunks = 0L

    /**
     * One entry per flushed chunk, for the `ChunkIndex` group in the summary.
     *
     * Accumulated rather than written as we go because a `ChunkIndex` lives in the *summary*, which
     * does not exist until [finish]. Fifty-odd bytes per chunk held in memory — about 100 kB across a
     * 512 MB recording, against the chunk buffer's own 256 kB.
     */
    private val chunkIndex = mutableListOf<ChunkIndexEntry>()

    /**
     * Where each message sits *inside the current uncompressed chunk*, per channel.
     *
     * The offsets are relative to the chunk's decompressed records, not to the file — that is what a
     * `MessageIndex` means, and it is the only way an offset can be written before the chunk it points
     * into has been compressed. Cleared with the chunk.
     */
    private val chunkMessageIndex = mutableMapOf<Int, MutableList<Pair<Long, Long>>>()
    private var nextSchemaId = 1
    private var nextChannelId = 0
    private val schemas = mutableListOf<SchemaRecord>()
    private val channels = mutableListOf<ChannelRecord>()
    private val messageCounts = mutableMapOf<Int, Long>()
    private var earliest = Long.MAX_VALUE
    private var latest = Long.MIN_VALUE
    private var finished = false

    /** Bytes written so far, which is what the rotation cap is measured against. */
    val bytesWritten: Long get() = bytes

    val messageCount: Long get() = messages

    private data class SchemaRecord(val id: Int, val name: String, val encoding: String, val data: ByteArray)
    private data class ChannelRecord(val id: Int, val schemaId: Int, val topic: String, val encoding: String)

    fun start(profile: String = "", library: String = LIBRARY) {
        write(MAGIC)
        writeRecord(OP_HEADER) {
            putString(profile)
            putString(library)
        }
    }

    /**
     * Register a schema and return its id.
     *
     * `data` is an opaque serialised `FileDescriptorSet`; nothing here parses it, which is what lets the
     * app keep the lite protobuf runtime (that runtime strips descriptors entirely).
     */
    fun addSchema(name: String, encoding: String, data: ByteArray): Int {
        val id = nextSchemaId++
        val record = SchemaRecord(id, name, encoding, data)
        schemas += record
        writeSchema(record)
        return id
    }

    fun addChannel(topic: String, schemaId: Int, messageEncoding: String): Int {
        val id = nextChannelId++
        val record = ChannelRecord(id, schemaId, topic, messageEncoding)
        channels += record
        writeChannel(record)
        return id
    }

    /**
     * @param logTime local time at write, in nanoseconds. Readers pace playback on this, so it must not
     *   run backwards across channels.
     * @param publishTime the producer's own timestamp — for Keelson, the envelope's `enclosed_at`.
     */
    fun writeMessage(
        channelId: Int,
        sequence: Int,
        logTime: Long,
        publishTime: Long,
        data: ByteArray,
        /** Wall clock, for the time-based flush. Injectable so a test does not have to sleep. */
        nowNanos: Long = System.nanoTime(),
    ) {
        if (chunkMessages == 0) chunkStartNanos = nowNanos
        // Taken before the record is appended, so it addresses the record's own opcode rather than its
        // body — a reader seeks here and parses from the top.
        chunkMessageIndex.getOrPut(channelId) { mutableListOf() } += logTime to chunk.size.toLong()
        recordInto(chunk, OP_MESSAGE) {
            putUInt16(channelId)
            putUInt32(sequence.toLong())
            putUInt64(logTime)
            putUInt64(publishTime)
            // NOT putBytes: a Message's data is the remainder of the record, with no length prefix of
            // its own — unlike a Schema's data, which is a length-prefixed bytes field. Prefixing here
            // produces a file that parses cleanly and whose payloads all fail to decode.
            putRaw(data)
        }
        chunkMessages++
        if (logTime < chunkEarliest) chunkEarliest = logTime
        if (logTime > chunkLatest) chunkLatest = logTime
        // Size **or** time, whichever comes first. The time bound is the one that matters: a killed
        // process loses whatever is still buffered, and without it the worst case would depend on how
        // fast the sensors happen to be running rather than on the clock.
        if (chunk.size >= CHUNK_TARGET_BYTES ||
            nowNanos - chunkStartNanos >= CHUNK_MAX_AGE_NANOS
        ) {
            flushChunk()
        }
        messages++
        messageCounts[channelId] = (messageCounts[channelId] ?: 0L) + 1L
        if (logTime < earliest) earliest = logTime
        if (logTime > latest) latest = logTime
    }

    /**
     * Close the file: data end, then the summary section, then the footer.
     *
     * The summary repeats every schema and channel so each rotated file stands alone, and carries
     * `Statistics` so a reader knows the message count and time range without scanning.
     */
    fun finish() {
        if (finished) return
        finished = true

        // Before DataEnd: anything still buffered belongs in the data section.
        flushChunk()

        // **The tags, written at the last possible moment.** They are what the operator had switched on
        // when this file closed, which is why they cannot be written at `start()` — and why a run that
        // rotates gives each file the tags that were active as *it* closed rather than the run's final
        // set. A Metadata record lives in the data section by the spec; the summary gets a
        // MetadataIndex pointing at it, so a reader finds it in one seek rather than a scan.
        val metadataOffset = bytes
        val metadataLength = if (tags.isEmpty()) 0L else {
            writeMetadata(tags)
            bytes - metadataOffset
        }

        writeRecord(OP_DATA_END) { putUInt32(0) } // 0 = CRC not computed

        // Each group is sized from where the *next* one starts, so the boundaries are a chain and
        // inserting a group means threading one more link through it. A mis-sized group is not an error
        // a reader reports — it simply finds nothing there — so the sizes are taken from the recorded
        // offsets rather than written out by hand.
        val summaryStart = bytes
        val schemaOffset = bytes
        schemas.forEach { writeSchema(it) }
        val channelOffset = bytes
        channels.forEach { writeChannel(it) }
        val chunkIndexOffset = bytes
        chunkIndex.forEach { writeChunkIndex(it) }
        val statisticsOffset = bytes
        writeStatistics()
        val metadataIndexOffset = bytes
        if (metadataLength > 0L) writeMetadataIndex(metadataOffset, metadataLength)

        val summaryOffsetStart = bytes
        writeSummaryOffset(OP_SCHEMA, schemaOffset, channelOffset - schemaOffset)
        writeSummaryOffset(OP_CHANNEL, channelOffset, chunkIndexOffset - channelOffset)
        // Omitted entirely when there are no chunks — a run that recorded nothing — the same way the
        // metadata index is omitted when nothing tagged the file. A group offset with a zero length is
        // a claim that something is there.
        if (chunkIndex.isNotEmpty()) {
            writeSummaryOffset(
                OP_CHUNK_INDEX,
                chunkIndexOffset,
                statisticsOffset - chunkIndexOffset,
            )
        }
        writeSummaryOffset(OP_STATISTICS, statisticsOffset, metadataIndexOffset - statisticsOffset)
        if (metadataLength > 0L) {
            writeSummaryOffset(
                OP_METADATA_INDEX,
                metadataIndexOffset,
                summaryOffsetStart - metadataIndexOffset,
            )
        }

        writeRecord(OP_FOOTER) {
            putUInt64(summaryStart)
            putUInt64(summaryOffsetStart)
            putUInt32(0) // summary CRC, 0 = not computed
        }
        write(MAGIC)
        sink.flush()
    }

    /**
     * Compress and emit what has accumulated, if anything.
     *
     * A Chunk carries the *uncompressed* size so a reader can size its buffer, and the compressed bytes
     * as a length-prefixed field. `message_start_time`/`message_end_time` are the chunk's own range, not
     * the file's — a reader uses them to skip a chunk without decompressing it.
     */
    private fun flushChunk() {
        if (chunkMessages == 0) return
        val raw = chunk.toByteArray()
        val compressed = Zstd.compress(raw, ZSTD_LEVEL)
        // Before the record, not after its header: `chunk_start_offset` addresses the Chunk *record*,
        // opcode and length prefix included, so a reader can re-read it from that offset alone. Point
        // it at the body and every seek lands nine bytes into a record it then cannot parse.
        val startOffset = bytes
        val startTime = if (chunkEarliest == Long.MAX_VALUE) 0L else chunkEarliest
        val endTime = if (chunkLatest == Long.MIN_VALUE) 0L else chunkLatest
        writeRecord(OP_CHUNK) {
            putUInt64(if (chunkEarliest == Long.MAX_VALUE) 0L else chunkEarliest)
            putUInt64(if (chunkLatest == Long.MIN_VALUE) 0L else chunkLatest)
            putUInt64(raw.size.toLong())
            putUInt32(0) // uncompressed CRC, 0 = not computed
            putString(COMPRESSION_ZSTD)
            // The records themselves: length-prefixed, so a truncated file leaves a chunk a reader can
            // recognise as incomplete rather than one it tries to decompress.
            putUInt64(compressed.size.toLong())
            putRaw(compressed)
        }
        val chunkLength = bytes - startOffset

        // One MessageIndex per channel, in the data section immediately after the chunk they index —
        // the spec's placement, and what makes the offsets in the ChunkIndex resolvable.
        val messageIndexStart = bytes
        val messageIndexOffsets = chunkMessageIndex.toSortedMap().mapValues { (channelId, entries) ->
            val at = bytes
            writeMessageIndex(channelId, entries)
            at
        }

        chunkIndex += ChunkIndexEntry(
            startTime = startTime,
            endTime = endTime,
            startOffset = startOffset,
            length = chunkLength,
            messageIndexOffsets = messageIndexOffsets,
            messageIndexLength = bytes - messageIndexStart,
            compressedSize = compressed.size.toLong(),
            uncompressedSize = raw.size.toLong(),
        )
        chunks++
        chunk.reset()
        chunkMessages = 0
        chunkMessageIndex.clear()
        chunkEarliest = Long.MAX_VALUE
        chunkLatest = Long.MIN_VALUE
    }

    /**
     * Where every message of one channel sits inside the chunk just written.
     *
     * `records` is an array, so it carries its own `uint32` byte length ahead of the pairs; each pair is
     * a `uint64` log time and a `uint64` offset into the *uncompressed* chunk.
     *
     * These are not optional in practice, whatever the spec allows. A `ChunkIndex` whose
     * `message_index_offsets` is empty sends a seeking reader down the index path with nothing to
     * follow: measured against `mcap` 1.2.2, such a file returns **zero** messages from the default
     * reader while a non-seeking reader still reads all of them. That is worse than writing no index at
     * all, which at least leaves the linear scan working — so the two records ship together or not at
     * all.
     */
    private fun writeMessageIndex(channelId: Int, entries: List<Pair<Long, Long>>) =
        writeRecord(OP_MESSAGE_INDEX) {
            putUInt16(channelId)
            putUInt32(entries.size.toLong() * 16L)
            entries.forEach { (logTime, offset) ->
                putUInt64(logTime)
                putUInt64(offset)
            }
        }

    /**
     * What the operator had switched on, written into the file when it closes.
     *
     * Set rather than passed to [finish] because a rotation closes a file without anybody asking it to,
     * and the recorder pushes the current set in as it changes — see `Recorder.setTags`.
     */
    @Volatile
    var tags: Set<String> = emptySet()

    /**
     * `metadata` with one entry, `tags`, holding them newline-separated.
     *
     * One entry rather than one per tag because the value is a plain string either way and a reader
     * that knows nothing about this app still sees something legible. The separator is the same one
     * `RecordingTags` uses, and `normaliseTag` guarantees no tag contains it.
     */
    private fun writeMetadata(tags: Set<String>) = writeRecord(OP_METADATA) {
        putString(METADATA_TAGS)
        // map<string, string>: a byte length, then the pairs.
        val entries = Buffer()
        entries.putString(METADATA_TAGS)
        entries.putString(tags.joinToString("\n"))
        // `putBytes` writes the uint32 length and then the bytes, which *is* the map's encoding — a
        // separate `putUInt32` here wrote the length twice and the reader found nothing.
        putBytes(entries.toByteArray())
    }

    /** Offset and length of the Metadata record, so it is one seek from the summary. */
    private fun writeMetadataIndex(offset: Long, length: Long) = writeRecord(OP_METADATA_INDEX) {
        putUInt64(offset)
        // The spec counts the opcode and the length prefix in this, not just the body.
        putUInt64(length)
        putString(METADATA_TAGS)
    }

    private fun writeSchema(record: SchemaRecord) = writeRecord(OP_SCHEMA) {
        putUInt16(record.id)
        putString(record.name)
        putString(record.encoding)
        putBytes(record.data)
    }

    private fun writeChannel(record: ChannelRecord) = writeRecord(OP_CHANNEL) {
        putUInt16(record.id)
        putUInt16(record.schemaId)
        putString(record.topic)
        putString(record.encoding)
        putUInt32(0) // metadata: empty map
    }

    private fun writeStatistics() = writeRecord(OP_STATISTICS) {
        putUInt64(messages)
        putUInt16(schemas.size)
        putUInt32(channels.size.toLong())
        putUInt32(0) // attachment count
        putUInt32(if (tags.isEmpty()) 0L else 1L) // metadata count
        putUInt32(chunks)
        putUInt64(if (messages == 0L) 0L else earliest)
        putUInt64(if (messages == 0L) 0L else latest)
        // channelMessageCounts: a map of channel_id -> count, length-prefixed in bytes.
        putUInt32((messageCounts.size * 10).toLong())
        messageCounts.forEach { (channel, count) ->
            putUInt16(channel)
            putUInt64(count)
        }
    }

    /**
     * Where one chunk sits and what it spans, so a reader can find a moment without decompressing the
     * file to look for it.
     */
    private class ChunkIndexEntry(
        val startTime: Long,
        val endTime: Long,
        val startOffset: Long,
        val length: Long,
        /** Channel id to the file offset of that channel's `MessageIndex` for this chunk. */
        val messageIndexOffsets: Map<Int, Long>,
        val messageIndexLength: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
    )

    /**
     * A `ChunkIndex`, §Summary — the record that turns opening a recording into two seeks.
     *
     * **`message_index_offsets` is a map, so it carries its own `uint32` byte length** ahead of the
     * pairs — ten bytes each, a `uint16` channel id and a `uint64` file offset. Omitting that length
     * produces a record that parses "successfully" and shifts every field after it, the same failure
     * the doubled length prefix once caused with the tags: a file that reads fine and means something
     * else.
     *
     * It must not be *empty* either, which is a mistake worth recording because it looks like a saving.
     * A `ChunkIndex` with no message indexes to point at sends a seeking reader down the index path
     * with nothing to follow — measured against `mcap` 1.2.2, such a file returns **zero** messages
     * from the default reader while a non-seeking reader still reads all of them. Writing no index at
     * all is strictly better than that, since it leaves the linear scan working.
     */
    private fun writeChunkIndex(entry: ChunkIndexEntry) = writeRecord(OP_CHUNK_INDEX) {
        putUInt64(entry.startTime)
        putUInt64(entry.endTime)
        putUInt64(entry.startOffset)
        putUInt64(entry.length)
        putUInt32(entry.messageIndexOffsets.size.toLong() * 10L)
        entry.messageIndexOffsets.forEach { (channelId, offset) ->
            putUInt16(channelId)
            putUInt64(offset)
        }
        putUInt64(entry.messageIndexLength)
        putString(COMPRESSION_ZSTD)
        putUInt64(entry.compressedSize)
        putUInt64(entry.uncompressedSize)
    }

    private fun writeSummaryOffset(opcode: Int, offset: Long, length: Long) =
        writeRecord(OP_SUMMARY_OFFSET) {
            putUInt8(opcode)
            putUInt64(offset)
            putUInt64(length)
        }

    private inline fun writeRecord(opcode: Int, body: Buffer.() -> Unit) {
        val buffer = Buffer()
        buffer.body()
        val payload = buffer.toByteArray()
        val head = Buffer()
        head.putUInt8(opcode)
        head.putUInt64(payload.size.toLong())
        write(head.toByteArray())
        write(payload)
    }

    /** The same framing, into a buffer rather than the sink — a chunk holds whole records. */
    private inline fun recordInto(target: Buffer, opcode: Int, body: Buffer.() -> Unit) {
        val buffer = Buffer()
        buffer.body()
        val payload = buffer.toByteArray()
        target.putUInt8(opcode)
        target.putUInt64(payload.size.toLong())
        target.putRaw(payload)
    }

    private fun write(data: ByteArray) {
        sink.write(data)
        bytes += data.size
    }

    /** Little-endian primitives, per the MCAP spec. */
    class Buffer {
        private val out = ByteArrayOutputStream()

        fun putUInt8(value: Int) {
            out.write(value and 0xFF)
        }

        fun putUInt16(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
        }

        fun putUInt32(value: Long) {
            for (i in 0 until 4) out.write(((value ushr (8 * i)) and 0xFF).toInt())
        }

        fun putUInt64(value: Long) {
            for (i in 0 until 8) out.write(((value ushr (8 * i)) and 0xFF).toInt())
        }

        /** A string is a uint32 byte-length followed by UTF-8. */
        fun putString(value: String) {
            val encoded = value.toByteArray(Charsets.UTF_8)
            putUInt32(encoded.size.toLong())
            out.write(encoded)
        }

        /** Raw bytes, no length prefix — for fields that run to the end of their record. */
        fun putRaw(value: ByteArray) {
            out.write(value)
        }

        /** Bytes are a uint32 length followed by the content. */
        fun putBytes(value: ByteArray) {
            putUInt32(value.size.toLong())
            out.write(value)
        }

        fun toByteArray(): ByteArray = out.toByteArray()

        val size: Int get() = out.size()

        fun reset() = out.reset()
    }

    companion object {
        /** `\x89 M C A P 0 \r \n` — the same eight bytes open and close the file. */
        val MAGIC = byteArrayOf(0x89.toByte(), 0x4D, 0x43, 0x41, 0x50, 0x30, 0x0D, 0x0A)

        const val LIBRARY = "logline-android"

        const val OP_HEADER = 0x01
        const val OP_FOOTER = 0x02
        const val OP_SCHEMA = 0x03
        const val OP_CHANNEL = 0x04
        const val OP_MESSAGE = 0x05
        const val OP_CHUNK = 0x06
        const val OP_MESSAGE_INDEX = 0x07
        const val OP_CHUNK_INDEX = 0x08
        const val OP_METADATA = 0x0C
        const val OP_METADATA_INDEX = 0x0D
        const val OP_DATA_END = 0x0F
        const val OP_STATISTICS = 0x0B
        const val OP_SUMMARY_OFFSET = 0x0E

        /** What keelson's tooling expects on every channel and protobuf schema. */
        const val ENCODING_PROTOBUF = "protobuf"

        /** The Metadata record's name, and the key inside it. */
        const val METADATA_TAGS = "tags"

        /** One of MCAP's two well-known compressions. The other is lz4; `Deflater` is not legal here. */
        const val COMPRESSION_ZSTD = "zstd"

        /**
         * Level 3, zstd's own default.
         *
         * This runs on the recorder's drain coroutine — the one thing in the app that must not fall
         * behind — so the trade is deliberately towards speed. Most of what is being squeezed is
         * repetitive framing, which even a low level flattens.
         */
        const val ZSTD_LEVEL = 3

        /**
         * Flush at a quarter of a megabyte, or after two seconds, whichever lands first.
         *
         * Small against the Python reference's 1 MB, and on purpose: a killed process loses whatever is
         * still buffered. Recovery used to lose a single partial message, and the time bound is what
         * keeps the new worst case a property of the clock rather than of the sample rate — at max rates
         * a megabyte is seconds of everything, at idle it could be minutes.
         */
        const val CHUNK_TARGET_BYTES = 256 * 1024

        const val CHUNK_MAX_AGE_NANOS = 2_000_000_000L
    }
}

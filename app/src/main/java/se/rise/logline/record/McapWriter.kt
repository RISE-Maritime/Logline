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
class McapWriter(
    private val sink: OutputStream,
    /**
     * Called once a chunk record has been written to [sink], for a caller that wants the bytes
     * committed rather than merely handed over.
     *
     * A hook rather than the writer doing it, because this class deliberately knows nothing about
     * files — it takes an `OutputStream` and its tests write to a buffer. What "commit" means belongs
     * to whoever owns the file; see `RecordingSession`.
     *
     * Fires from [finish]'s flush too, which costs one extra commit on a file that is about to be
     * closed and is not worth a second code path to avoid.
     */
    private val onChunkWritten: () -> Unit = {},
    /**
     * Where [sink] already sits in the file, so the summary's offsets are absolute.
     *
     * Zero for a file being written from the start, which is every ordinary run. `McapRecovery` opens
     * one of these over an *existing* file to finish it, and every offset the summary states — the
     * schema group, the statistics, the footer's two pointers — is measured from the beginning of the
     * file rather than from where this writer started, so it has to be told.
     */
    startOffset: Long = 0L,
) {

    private var bytes = startOffset
    private var messages = 0L

    /** Messages accumulate here until the chunk is flushed. Schemas and channels never do. */
    private val chunk = Buffer()
    private var chunkMessages = 0
    private var chunkStartNanos = 0L
    private var chunkEarliest = Long.MAX_VALUE
    private var chunkLatest = Long.MIN_VALUE
    private var chunks = 0L
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

    /** A Metadata record's name and where it sits, which is all a MetadataIndex states. */
    internal data class MetadataAt(val name: String, val offset: Long, val length: Long)

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
        val metadata = mutableListOf<MetadataAt>()
        if (tags.isNotEmpty()) {
            metadata += writeMetadata(METADATA_TAGS, mapOf(METADATA_TAGS to tags.joinToString("\n")))
        }

        writeSummary(
            schemaRecords = schemas.map { schemaRecord(it) },
            channelRecords = channels.map { channelRecord(it) },
            messageCounts = messageCounts,
            messages = messages,
            chunks = chunks,
            earliest = earliest,
            latest = latest,
            metadata = metadata,
        )
        sink.flush()
    }

    /**
     * Finish a file somebody else wrote the data section of.
     *
     * `McapRecovery` walks an interrupted recording, trims it at the last complete record and then has
     * everything a summary needs — the Schema and Channel records verbatim, the message counts, the
     * range — but no writer to state it with. This is that writer: no header, no messages, just the
     * closing half.
     *
     * It exists so the *layout* has one implementation. A rescued file and a closed one differ in what
     * they can say, never in how they say it, and a second transcription of the summary offsets and
     * the footer's two pointers is exactly the thing that drifts and fails silently.
     */
    internal fun finishRescued(
        schemaRecords: List<ByteArray>,
        channelRecords: List<ByteArray>,
        messageCounts: Map<Int, Long>,
        messages: Long,
        chunks: Long,
        earliest: Long,
        latest: Long,
        /** Metadata records already in the data section, found by the walk. */
        existingMetadata: List<MetadataAt>,
        /** Metadata to add before DataEnd — the rescue's own note. */
        newMetadata: List<Pair<String, Map<String, String>>>,
        /**
         * False when the walk could not read the whole data section, and the figures above therefore
         * describe less than the file holds.
         *
         * The file still gets its data end, footer and magic — it opens, and every message in it is
         * reachable — but the footer declares `summary_start = 0`, the spec's "no summary", which is
         * what recovery produced for every file before this. Stating nothing is a poor outcome;
         * stating a message count somebody plans against and which is short is a worse one.
         */
        summarise: Boolean = true,
    ) {
        if (finished) return
        finished = true
        if (!summarise) {
            writeRecord(OP_DATA_END) { putUInt32(0) }
            writeRecord(OP_FOOTER) {
                putUInt64(0) // summary_start = 0: no summary section
                putUInt64(0) // summary_offset_start
                putUInt32(0) // summary CRC
            }
            write(MAGIC)
            sink.flush()
            return
        }
        val metadata = existingMetadata + newMetadata.map { (name, entries) -> writeMetadata(name, entries) }
        writeSummary(
            schemaRecords = schemaRecords,
            channelRecords = channelRecords,
            messageCounts = messageCounts,
            messages = messages,
            chunks = chunks,
            earliest = earliest,
            latest = latest,
            metadata = metadata,
        )
        sink.flush()
    }

    /**
     * DataEnd, the summary section, the summary offsets, the footer and the closing magic.
     *
     * Everything after the data section, in one place, so [finish] and [finishRescued] cannot disagree
     * about it. The arguments are what the two paths know differently; the order and the arithmetic are
     * what they share.
     */
    private fun writeSummary(
        schemaRecords: List<ByteArray>,
        channelRecords: List<ByteArray>,
        messageCounts: Map<Int, Long>,
        messages: Long,
        chunks: Long,
        earliest: Long,
        latest: Long,
        metadata: List<MetadataAt>,
    ) {
        writeRecord(OP_DATA_END) { putUInt32(0) } // 0 = CRC not computed

        val summaryStart = bytes
        val schemaOffset = bytes
        schemaRecords.forEach { write(it) }
        val channelOffset = bytes
        channelRecords.forEach { write(it) }
        val statisticsOffset = bytes
        writeStatistics(
            messages = messages,
            schemaCount = schemaRecords.size,
            channelCount = channelRecords.size,
            chunks = chunks,
            metadataCount = metadata.size,
            earliest = earliest,
            latest = latest,
            messageCounts = messageCounts,
        )
        val metadataIndexOffset = bytes
        metadata.forEach { writeMetadataIndex(it) }

        val summaryOffsetStart = bytes
        writeSummaryOffset(OP_SCHEMA, schemaOffset, channelOffset - schemaOffset)
        writeSummaryOffset(OP_CHANNEL, channelOffset, statisticsOffset - channelOffset)
        writeSummaryOffset(OP_STATISTICS, statisticsOffset, metadataIndexOffset - statisticsOffset)
        if (metadata.isNotEmpty()) {
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
        chunks++
        onChunkWritten()
        chunk.reset()
        chunkMessages = 0
        chunkEarliest = Long.MAX_VALUE
        chunkLatest = Long.MIN_VALUE
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
     * A Metadata record, and where it landed.
     *
     * `tags` is written as one entry rather than one per tag because the value is a plain string
     * either way and a reader that knows nothing about this app still sees something legible. The
     * separator is the same one `RecordingTags` uses, and `normaliseTag` guarantees no tag contains it.
     */
    private fun writeMetadata(name: String, entries: Map<String, String>): MetadataAt {
        val at = bytes
        writeRecord(OP_METADATA) {
            putString(name)
            // map<string, string>: a byte length, then the pairs.
            val map = Buffer()
            entries.forEach { (key, value) ->
                map.putString(key)
                map.putString(value)
            }
            // `putBytes` writes the uint32 length and then the bytes, which *is* the map's encoding — a
            // separate `putUInt32` here wrote the length twice and the reader found nothing.
            putBytes(map.toByteArray())
        }
        return MetadataAt(name, at, bytes - at)
    }

    /** Offset and length of a Metadata record, so it is one seek from the summary. */
    private fun writeMetadataIndex(at: MetadataAt) = writeRecord(OP_METADATA_INDEX) {
        putUInt64(at.offset)
        // The spec counts the opcode and the length prefix in this, not just the body.
        putUInt64(at.length)
        putString(at.name)
    }

    private fun writeSchema(record: SchemaRecord) = write(schemaRecord(record))

    private fun writeChannel(record: ChannelRecord) = write(channelRecord(record))

    /**
     * A whole Schema record — opcode, length and body.
     *
     * Serialised rather than written, because the same bytes go into the data section when the schema
     * is declared and into the summary when the file closes. `McapRecovery` re-emits the records it
     * walked past in this same form, which is what lets it rebuild a summary without parsing a single
     * one of them.
     */
    private fun schemaRecord(record: SchemaRecord): ByteArray = recordBytes(OP_SCHEMA) {
        putUInt16(record.id)
        putString(record.name)
        putString(record.encoding)
        putBytes(record.data)
    }

    private fun channelRecord(record: ChannelRecord): ByteArray = recordBytes(OP_CHANNEL) {
        putUInt16(record.id)
        putUInt16(record.schemaId)
        putString(record.topic)
        putString(record.encoding)
        putUInt32(0) // metadata: empty map
    }

    private fun writeStatistics(
        messages: Long,
        schemaCount: Int,
        channelCount: Int,
        chunks: Long,
        metadataCount: Int,
        earliest: Long,
        latest: Long,
        messageCounts: Map<Int, Long>,
    ) = writeRecord(OP_STATISTICS) {
        putUInt64(messages)
        putUInt16(schemaCount)
        putUInt32(channelCount.toLong())
        putUInt32(0) // attachment count
        putUInt32(metadataCount.toLong())
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

    private fun writeSummaryOffset(opcode: Int, offset: Long, length: Long) =
        writeRecord(OP_SUMMARY_OFFSET) {
            putUInt8(opcode)
            putUInt64(offset)
            putUInt64(length)
        }

    private inline fun writeRecord(opcode: Int, body: Buffer.() -> Unit) = write(recordBytes(opcode, body))

    /** One framed record as bytes, for a caller that wants to hold it rather than emit it. */
    private inline fun recordBytes(opcode: Int, body: Buffer.() -> Unit): ByteArray {
        val buffer = Buffer()
        buffer.body()
        val payload = buffer.toByteArray()
        val out = Buffer()
        out.putUInt8(opcode)
        out.putUInt64(payload.size.toLong())
        out.putRaw(payload)
        return out.toByteArray()
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
        const val OP_METADATA = 0x0C
        const val OP_METADATA_INDEX = 0x0D
        const val OP_DATA_END = 0x0F
        const val OP_STATISTICS = 0x0B
        const val OP_SUMMARY_OFFSET = 0x0E

        /** What keelson's tooling expects on every channel and protobuf schema. */
        const val ENCODING_PROTOBUF = "protobuf"

        /** The Metadata record's name, and the key inside it. */
        const val METADATA_TAGS = "tags"

        /**
         * The Metadata record `McapRecovery` writes, saying the run was interrupted.
         *
         * It has to be in the *file*, not only in this app: a rescued recording is finished properly
         * now, so nothing about its bytes says the run ended the way it did — and the first thing
         * anybody does with a recording is copy it off the phone. It is also what keeps the Files tab
         * able to say `incomplete, never closed`, which used to be carried by the missing summary.
         */
        const val METADATA_RECOVERY = "recovery"

        /** Its keys: how many bytes the trim dropped, and the same fact in a word. */
        const val METADATA_RESCUED = "rescued"
        const val METADATA_TRIMMED_BYTES = "trimmed_bytes"

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

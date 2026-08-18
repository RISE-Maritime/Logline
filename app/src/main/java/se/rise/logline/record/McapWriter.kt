package se.rise.logline.record

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * A minimal MCAP writer.
 *
 * Hand-written because **there is no MCAP library for the JVM** — Foxglove ships C++, Go, Python, Rust,
 * Swift and TypeScript, and `dev.foxglove` does not exist on Maven Central. The write path is small
 * though: MCAP is a sequence of little-endian, length-prefixed records.
 *
 * This produces an **unchunked, uncompressed** file with a summary section. Both are legal
 * ([spec](https://mcap.dev/spec)), and the thing that actually matters for a reader's load time is the
 * `Statistics` record — keelson's replayer falls back to scanning the whole file only when statistics
 * are *missing*, not when chunks are. Chunking and compression can be added later without changing the
 * public shape of this class.
 *
 * Deliberately free of Android types so the format can be tested on the JVM, where the assertions can
 * be about bytes rather than about what a screenshot looks like.
 */
class McapWriter(private val sink: OutputStream) {

    private var bytes = 0L
    private var messages = 0L
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
    fun writeMessage(channelId: Int, sequence: Int, logTime: Long, publishTime: Long, data: ByteArray) {
        writeRecord(OP_MESSAGE) {
            putUInt16(channelId)
            putUInt32(sequence.toLong())
            putUInt64(logTime)
            putUInt64(publishTime)
            // NOT putBytes: a Message's data is the remainder of the record, with no length prefix of
            // its own — unlike a Schema's data, which is a length-prefixed bytes field. Prefixing here
            // produces a file that parses cleanly and whose payloads all fail to decode.
            putRaw(data)
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

        writeRecord(OP_DATA_END) { putUInt32(0) } // 0 = CRC not computed

        val summaryStart = bytes
        val schemaOffset = bytes
        schemas.forEach { writeSchema(it) }
        val channelOffset = bytes
        channels.forEach { writeChannel(it) }
        val statisticsOffset = bytes
        writeStatistics()

        val summaryOffsetStart = bytes
        writeSummaryOffset(OP_SCHEMA, schemaOffset, channelOffset - schemaOffset)
        writeSummaryOffset(OP_CHANNEL, channelOffset, statisticsOffset - channelOffset)
        writeSummaryOffset(OP_STATISTICS, statisticsOffset, summaryOffsetStart - statisticsOffset)

        writeRecord(OP_FOOTER) {
            putUInt64(summaryStart)
            putUInt64(summaryOffsetStart)
            putUInt32(0) // summary CRC, 0 = not computed
        }
        write(MAGIC)
        sink.flush()
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
        putUInt32(0) // metadata count
        putUInt32(0) // chunk count — unchunked file
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
        const val OP_DATA_END = 0x0F
        const val OP_STATISTICS = 0x0B
        const val OP_SUMMARY_OFFSET = 0x0E

        /** What keelson's tooling expects on every channel and protobuf schema. */
        const val ENCODING_PROTOBUF = "protobuf"
    }
}

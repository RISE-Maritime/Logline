package se.rise.logline.record

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream

/** One sample on its way to disk. */
data class RecordSample(
    /** The full Zenoh key, which becomes the MCAP channel topic. */
    val key: String,
    val subject: String,
    /** The **unwrapped payload** bytes — not the envelope. See the note in [RecordingSession]. */
    val payload: ByteArray,
    /** The producer's timestamp, i.e. what went into the envelope's `enclosed_at`. */
    val publishTimeNanos: Long,
)

/**
 * One MCAP file's lifetime.
 *
 * **Records the unwrapped payload, not the envelope.** That is not a shortcut — it is the contract
 * keelson's own tooling depends on. `mcap2keelson.py` replays a file with
 * `keelson.enclose(payload=message.data, enclosed_at=message.publish_time)`, so writing envelopes here
 * would produce doubly-wrapped messages that decode to garbage on every consumer. It is also the only
 * reading consistent with the MCAP schema being the *payload* type.
 *
 * Schemas are deduplicated by protobuf type rather than by subject — keelson's recorder writes one
 * schema record per subject, which for 24 subjects means 24 near-identical records. Readers resolve
 * schemas by name, so per-type is equivalent and smaller; a byte-level diff against a Python-written
 * file will show this and it is not a bug.
 */
class RecordingSession(
    private val file: File,
    private val descriptorSet: ByteArray,
    private val maxBytes: Long,
) {
    private val stream: OutputStream = BufferedOutputStream(file.outputStream(), BUFFER_BYTES)
    private val writer = McapWriter(stream)
    private val schemaIds = mutableMapOf<String, Int>()
    private val channelIds = mutableMapOf<String, Int>()
    private val sequences = mutableMapOf<Int, Int>()

    init {
        writer.start()
    }

    val path: File get() = file
    val messageCount: Long get() = writer.messageCount
    val bytesWritten: Long get() = writer.bytesWritten

    /** True once the file has grown past its cap and should be rotated. */
    fun shouldRotate(): Boolean = writer.bytesWritten >= maxBytes

    fun write(sample: RecordSample, logTimeNanos: Long) {
        val channelId = channelIds.getOrPut(sample.key) { declareChannel(sample) }
        val sequence = (sequences[channelId] ?: 0) + 1
        sequences[channelId] = sequence
        writer.writeMessage(channelId, sequence, logTimeNanos, sample.publishTimeNanos, sample.payload)
    }

    private fun declareChannel(sample: RecordSample): Int {
        val typeName = subjectSchemaNames[sample.subject]
        val schemaId = if (typeName == null) {
            // An unknown subject still gets recorded, with the self-describing (empty) schema keelson's
            // recorder uses for the same case. Losing the data would be worse than losing the schema.
            schemaIds.getOrPut("") { writer.addSchema(sample.subject, "", ByteArray(0)) }
        } else {
            schemaIds.getOrPut(typeName) {
                writer.addSchema(typeName, McapWriter.ENCODING_PROTOBUF, descriptorSet)
            }
        }
        return writer.addChannel(sample.key, schemaId, McapWriter.ENCODING_PROTOBUF)
    }

    /**
     * Finish the file, stamping it with the tags that were switched on at this moment. Safe to call
     * twice.
     *
     * Taken here rather than set when the file was opened, because that is what "the configuration at
     * the end of the run" means — and a rotation closes a file without anybody asking it to, so each
     * file gets what was on as *it* closed.
     */
    fun close(tags: Set<String> = emptySet()) {
        writer.tags = tags
        writer.finish()
        stream.close()
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}

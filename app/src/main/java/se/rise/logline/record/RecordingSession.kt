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
    private val fileStream = file.outputStream()
    private val stream: OutputStream = BufferedOutputStream(fileStream, BUFFER_BYTES)
    private val writer = McapWriter(stream, onChunkWritten = ::commit)

    private val schemaIds = mutableMapOf<String, Int>()
    private val channelIds = mutableMapOf<String, Int>()
    private val sequences = mutableMapOf<Int, Int>()

    /**
     * Get the chunk that was just written all the way onto the disk.
     *
     * Two steps, and both are load-bearing. `flush()` drains the 64 kB [BUFFER_BYTES] buffer, which
     * is app memory and dies with the process — about a second of file at the measured 241 MB/h, on
     * top of whatever the open chunk holds. `sync()` then commits what the OS has, which otherwise
     * sits in the page cache on the kernel's own 5-30 s writeback schedule.
     *
     * **The two failures are different and only one of them was already covered.** A process kill —
     * Android's low-battery shutdown, an LMK, a crash — loses app memory and keeps the page cache,
     * because an orderly shutdown flushes it: that is why killing the app 25 s into a run recovered
     * 24.7 s. A *hard* cut loses the page cache too, and without this the bound on that was the
     * kernel's schedule rather than anything this app controls. With it, both failures cost the same
     * thing: the open chunk, which `CHUNK_MAX_AGE_NANOS` already bounds at two seconds.
     *
     * Done per chunk rather than per message on purpose, and the cost was measured rather than
     * assumed, because this runs on the drain — the one coroutine in this app that must not fall
     * behind. On a Pixel 6 at 336 samples/s across 50 streams: **1.65 ms mean** over 71 commits
     * (1.29-1.99 ms), one every **~1.4 s**, so **about 0.12% of the drain's wall time**. Nothing was
     * dropped and the run wrote 39 016 samples in 1:56.
     *
     * Note the interval is set by the 256 kB size bound rather than the 2 s time bound at these
     * rates — a chunk compresses to about 84 kB, so the file grows at roughly 61 kB/s and fills a
     * chunk in well under two seconds. A slower run syncs less often, not more, and the 2 s bound is
     * what keeps the worst case bounded when almost nothing is being recorded.
     *
     * A sync *per message* would be a different proposition entirely: at 336 samples/s the same
     * 1.65 ms would be more than half the drain's time, and at the 800/s this app has been measured
     * at it would not keep up at all.
     */
    private fun commit() {
        stream.flush()
        fileStream.fd.sync()
    }

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

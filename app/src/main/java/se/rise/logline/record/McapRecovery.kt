package se.rise.logline.record

import com.github.luben.zstd.Zstd
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Makes an interrupted recording readable again.
 *
 * A process killed mid-run leaves a file with valid records but no footer. Readers seek to the end for
 * the footer first, find whatever the truncation left, and fail before reading a single message — so
 * the data is all there and none of it is reachable. That is the exact situation the orphan sweep
 * exists to rescue, and handing back an unopenable file would not be rescuing much.
 *
 * The fix is to walk the records from the start, cut the file at the last **complete** one, and finish
 * it the way [McapWriter.finish] would have: a data end, a real summary section and a footer that
 * points at it. The walk has to read the whole data section to do that — it counts the messages in
 * every chunk and takes their time range — and [McapWriter.finishRescued] states it.
 *
 * **It used to declare `summary_start = 0` instead**, the spec's "no summary", on the argument that the
 * statistics were lost and the messages were not. They are not lost, only unstated: everything a
 * summary says is derivable from the data section, which is where it came from in the first place.
 * What that cost was measured on a real file — a reader with no statistics has no time range to show,
 * so Foxglove opened a rescued recording on a timeline running from **1970 to the afternoon it was
 * made**, 46 years of nothing, while every message in it was minutes old. `mcap info` was blunter and
 * answered `channels: unknown`. Neither is a reader being unhelpful; a file that does not say when it
 * starts leaves them nothing else to do.
 *
 * **"Complete" means the opcode is one too, not just that the bytes fit.** A truncation leaves a
 * partial record that does not fit the file, and the length check alone catches that. A *power cut*
 * on a filesystem with delayed allocation leaves something else: the file's length was journalled
 * while its last blocks never reached the disk, so the tail comes back as **zeros rather than
 * short** — and zeros parse. Opcode `0x00`, length `0`, nine bytes consumed, repeat. Measured on a
 * 4 kB zero tail before this check existed: **one byte trimmed instead of 4096**, the walk having
 * marched through 455 nine-byte phantom records and stamped a footer after all of them.
 *
 * The range is the spec's own — `0x01` to `0x0F` — rather than the narrower set [McapWriter]
 * actually emits. Both would fix the case above, and the wider one cannot reject a record this app
 * wrote today or grows the ability to write tomorrow, which a hand-listed set would eventually do
 * silently and destructively.
 */
internal object McapRecovery {

    /**
     * @return the number of bytes trimmed, or null when the file was already complete or is not MCAP.
     */
    fun finalise(file: File): Long? {
        if (file.length() < MAGIC_SIZE * 2) return null

        val scan: Scan
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(MAGIC_SIZE)
            raf.readFully(magic)
            if (!magic.contentEquals(McapWriter.MAGIC)) return null

            // Already closed properly? Then the last eight bytes are the magic too.
            raf.seek(file.length() - MAGIC_SIZE)
            val tail = ByteArray(MAGIC_SIZE)
            raf.readFully(tail)
            if (tail.contentEquals(McapWriter.MAGIC)) return null

            scan = walk(raf, file.length())
        }

        val trimmed = file.length() - scan.lastComplete
        RandomAccessFile(file, "rw").use { it.setLength(scan.lastComplete) }

        // Append mode puts the stream exactly at the trim point, and the writer is told where that is
        // so every offset the summary states is measured from the start of the file.
        FileOutputStream(file, true).use { out ->
            val writer = McapWriter(out, startOffset = scan.lastComplete)
            if (scan.readable) {
                writer.finishRescued(
                    schemaRecords = scan.schemaRecords,
                    channelRecords = scan.channelRecords,
                    messageCounts = scan.messageCounts,
                    messages = scan.messages,
                    chunks = scan.chunks,
                    earliest = scan.earliest,
                    latest = scan.latest,
                    existingMetadata = scan.metadata,
                    newMetadata = listOf(
                        McapWriter.METADATA_RECOVERY to mapOf(
                            McapWriter.METADATA_RESCUED to "true",
                            McapWriter.METADATA_TRIMMED_BYTES to trimmed.toString(),
                        ),
                    ),
                )
            } else {
                // **A summary that undercounts is worse than none.** A chunk this app cannot
                // decompress means the walk does not know what is in the file, and figures somebody
                // plans against must not be guesses — the same argument `readMcapSummary` makes for
                // returning null rather than zeroes. So this falls back to what recovery did before
                // there was anything better: every message present, nothing claimed about them.
                writer.finishRescued(
                    schemaRecords = emptyList(),
                    channelRecords = emptyList(),
                    messageCounts = emptyMap(),
                    messages = 0,
                    chunks = 0,
                    earliest = 0,
                    latest = 0,
                    existingMetadata = emptyList(),
                    newMetadata = emptyList(),
                    summarise = false,
                )
            }
        }
        return trimmed
    }

    /** Everything the walk learned, which is everything a summary needs. */
    private class Scan {
        var lastComplete = MAGIC_SIZE.toLong()
        val schemaRecords = mutableListOf<ByteArray>()
        val channelRecords = mutableListOf<ByteArray>()
        val messageCounts = LinkedHashMap<Int, Long>()
        val metadata = mutableListOf<McapWriter.MetadataAt>()
        var messages = 0L
        var chunks = 0L
        var earliest = Long.MAX_VALUE
        var latest = Long.MIN_VALUE

        /** False once a chunk refused to decompress: the counts no longer describe the file. */
        var readable = true
    }

    /**
     * Walk the data section, finding both the trim point and what the summary will say.
     *
     * Schemas and channels are kept as **whole records** and never parsed — this writer puts them
     * outside the chunks and ahead of the messages, so the summary's copies are the bytes already
     * walked past. Messages are counted in both places they can live: inside a Chunk, which is
     * everything written since compression arrived, and at the top level, which is everything written
     * before it and a hundred of the files that were on the dev phone.
     */
    private fun walk(raf: RandomAccessFile, size: Long): Scan {
        val scan = Scan()
        var offset = MAGIC_SIZE.toLong()
        while (offset + RECORD_HEADER_SIZE <= size) {
            raf.seek(offset)
            val opcode = raf.read()
            val length = java.lang.Long.reverseBytes(raf.readLong())
            // The opcode is checked before the length is trusted, because a byte that is not one
            // ends the real data whatever the eight bytes after it happen to say.
            if (opcode !in OP_MIN..OP_MAX) break
            if (length < 0 || offset + RECORD_HEADER_SIZE + length > size) break

            if (scan.readable) {
                when (opcode) {
                    McapWriter.OP_SCHEMA ->
                        scan.schemaRecords += wholeRecord(raf, offset, opcode, length)
                    McapWriter.OP_CHANNEL ->
                        scan.channelRecords += wholeRecord(raf, offset, opcode, length)
                    McapWriter.OP_METADATA -> scan.metadata += metadataAt(raf, offset, length)
                    McapWriter.OP_MESSAGE -> countMessage(body(raf, offset, length), scan)
                    McapWriter.OP_CHUNK -> {
                        scan.chunks++
                        if (!countChunk(body(raf, offset, length), scan)) scan.readable = false
                    }
                }
            }

            offset += RECORD_HEADER_SIZE + length
            scan.lastComplete = offset
        }
        return scan
    }

    private fun body(raf: RandomAccessFile, offset: Long, length: Long): ByteBuffer {
        raf.seek(offset + RECORD_HEADER_SIZE)
        val bytes = ByteArray(length.toInt())
        raf.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun wholeRecord(raf: RandomAccessFile, offset: Long, opcode: Int, length: Long): ByteArray {
        raf.seek(offset)
        val bytes = ByteArray((RECORD_HEADER_SIZE + length).toInt())
        raf.readFully(bytes)
        // The opcode was already read to get here; re-reading the record from its own start is what
        // makes these bytes re-emittable verbatim.
        check(bytes[0].toInt() and 0xFF == opcode)
        return bytes
    }

    /** A Metadata record's name and extent, which is all a MetadataIndex needs. */
    private fun metadataAt(raf: RandomAccessFile, offset: Long, length: Long): McapWriter.MetadataAt {
        val buffer = body(raf, offset, length)
        val nameLength = if (buffer.remaining() >= 4) buffer.int else 0
        val name = if (nameLength in 0..buffer.remaining()) {
            ByteArray(nameLength).also { buffer.get(it) }.toString(Charsets.UTF_8)
        } else {
            ""
        }
        return McapWriter.MetadataAt(name, offset, RECORD_HEADER_SIZE + length)
    }

    /** One message's channel and log time, from a record body positioned at its start. */
    private fun countMessage(record: ByteBuffer, scan: Scan) {
        if (record.remaining() < MESSAGE_PREFIX_SIZE) return
        val channel = record.short.toInt() and 0xFFFF
        record.int // sequence
        val logTime = record.long
        scan.messages++
        scan.messageCounts[channel] = (scan.messageCounts[channel] ?: 0L) + 1L
        if (logTime < scan.earliest) scan.earliest = logTime
        if (logTime > scan.latest) scan.latest = logTime
    }

    /**
     * Decompress one chunk and count what is in it.
     *
     * @return false when the chunk cannot be read, which takes the whole summary down with it rather
     *   than silently losing that chunk's messages from the count.
     */
    private fun countChunk(chunk: ByteBuffer, scan: Scan): Boolean = try {
        countChunkOrThrow(chunk, scan)
    } catch (t: Throwable) {
        false
    }

    private fun countChunkOrThrow(chunk: ByteBuffer, scan: Scan): Boolean {
        chunk.long // message start time
        chunk.long // message end time
        val uncompressed = chunk.long
        chunk.int // uncompressed CRC
        val compressionLength = chunk.int
        if (compressionLength < 0 || compressionLength > chunk.remaining()) return false
        val compression = ByteArray(compressionLength).also { chunk.get(it) }.toString(Charsets.UTF_8)
        if (chunk.remaining() < 8) return false
        val payloadLength = chunk.long
        if (payloadLength < 0 || payloadLength > chunk.remaining()) return false
        val payload = ByteArray(payloadLength.toInt()).also { chunk.get(it) }

        val raw = when (compression) {
            McapWriter.COMPRESSION_ZSTD -> {
                if (uncompressed <= 0L || uncompressed > MAX_CHUNK_BYTES) return false
                Zstd.decompress(payload, uncompressed.toInt())
            }
            // The spec allows an uncompressed chunk and this app has written them before.
            "" -> payload
            else -> return false
        }

        val records = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        while (records.remaining() >= RECORD_HEADER_SIZE) {
            val opcode = records.get().toInt() and 0xFF
            val length = records.long
            if (length < 0 || length > records.remaining()) return false
            val end = records.position() + length.toInt()
            if (opcode == McapWriter.OP_MESSAGE) countMessage(records, scan)
            records.position(end)
        }
        return true
    }

    private const val MAGIC_SIZE = 8

    /**
     * The opcodes MCAP defines. `0x00` is not one, which is the whole point — see the class comment.
     */
    private const val OP_MIN = 0x01
    private const val OP_MAX = 0x0F

    /** opcode (1) + length (8). */
    private const val RECORD_HEADER_SIZE = 9

    /** channel id (2) + sequence (4) + log time (8). */
    private const val MESSAGE_PREFIX_SIZE = 14

    /** A sanity bound on a declared uncompressed size, so a corrupt field cannot ask for a gigabyte. */
    private const val MAX_CHUNK_BYTES = 64L * 1024 * 1024
}

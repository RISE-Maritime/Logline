package se.rise.logline.record

import java.io.File
import java.io.RandomAccessFile

/**
 * Makes an interrupted recording readable again.
 *
 * A process killed mid-run leaves a file with valid records but no footer. Readers seek to the end for
 * the footer first, find whatever the truncation left, and fail before reading a single message — so
 * the data is all there and none of it is reachable. That is the exact situation the orphan sweep
 * exists to rescue, and handing back an unopenable file would not be rescuing much.
 *
 * The fix is to walk the records from the start, cut the file at the last **complete** one, and append
 * a data-end and a footer with `summary_start = 0`, which the spec defines as "no summary". Readers
 * then open it normally and fall back to scanning — keelson's replayer has that path already.
 * Statistics are lost, the messages are not.
 */
internal object McapRecovery {

    /**
     * @return the number of bytes trimmed, or null when the file was already complete or is not MCAP.
     */
    fun finalise(file: File): Long? {
        if (file.length() < MAGIC_SIZE * 2) return null

        RandomAccessFile(file, "rw").use { raf ->
            val magic = ByteArray(MAGIC_SIZE)
            raf.readFully(magic)
            if (!magic.contentEquals(McapWriter.MAGIC)) return null

            // Already closed properly? Then the last eight bytes are the magic too.
            raf.seek(file.length() - MAGIC_SIZE)
            val tail = ByteArray(MAGIC_SIZE)
            raf.readFully(tail)
            if (tail.contentEquals(McapWriter.MAGIC)) return null

            var offset = MAGIC_SIZE.toLong()
            var lastComplete = offset
            while (offset + RECORD_HEADER_SIZE <= file.length()) {
                raf.seek(offset)
                raf.read() // opcode
                val length = java.lang.Long.reverseBytes(raf.readLong())
                if (length < 0 || offset + RECORD_HEADER_SIZE + length > file.length()) break
                offset += RECORD_HEADER_SIZE + length
                lastComplete = offset
            }

            val trimmed = file.length() - lastComplete
            raf.setLength(lastComplete)
            raf.seek(lastComplete)

            // DataEnd, then a footer declaring no summary, then the closing magic.
            raf.write(record(McapWriter.OP_DATA_END) { putUInt32(0) })
            raf.write(
                record(McapWriter.OP_FOOTER) {
                    putUInt64(0) // summary_start = 0: no summary section
                    putUInt64(0) // summary_offset_start
                    putUInt32(0) // summary CRC
                }
            )
            raf.write(McapWriter.MAGIC)
            return trimmed
        }
    }

    private inline fun record(opcode: Int, body: McapWriter.Buffer.() -> Unit): ByteArray {
        val payload = McapWriter.Buffer().apply(body).toByteArray()
        val out = McapWriter.Buffer()
        out.putUInt8(opcode)
        out.putUInt64(payload.size.toLong())
        out.putRaw(payload)
        return out.toByteArray()
    }

    private const val MAGIC_SIZE = 8

    /** opcode (1) + length (8). */
    private const val RECORD_HEADER_SIZE = 9
}

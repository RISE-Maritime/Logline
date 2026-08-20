package se.rise.logline

import se.rise.logline.record.McapRecovery
import se.rise.logline.record.McapWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The MCAP format, tested on the JVM.
 *
 * Worth the effort because there is no MCAP library for Java — this writer is ours, so nothing else
 * checks it, and a malformed file is only discovered when someone tries to open a recording of
 * something that cannot be recorded again.
 */
class McapWriterTest {

    /** One message as a reader recovers it. */
    private data class ReadMessage(
        val channelId: Int,
        val sequence: Int,
        val logTime: Long,
        val publishTime: Long,
        val data: ByteArray,
    )

    /**
     * Walk a written file and return its messages, decompressing chunks on the way.
     *
     * There is no MCAP library for the JVM — which is why `McapWriter` is hand-written — so the only
     * way to assert the file is *readable* rather than merely well-sized is to read it here. This
     * replaced a set of byte-exact assertions: those pinned the framing to the byte and still could not
     * have caught the bug they were written for, where every payload decoded to garbage.
     */
    private fun readMessages(bytes: ByteArray): List<ReadMessage> {
        val out = mutableListOf<ReadMessage>()

        fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(b: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 0 until 4) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i))
            return v
        }
        fun u64(b: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i))
            return v
        }

        fun walk(b: ByteArray, from: Int, to: Int) {
            var o = from
            while (o + 9 <= to) {
                val op = b[o].toInt() and 0xFF
                val len = u64(b, o + 1)
                val body = o + 9
                if (body + len > to) break // truncated tail
                when (op) {
                    McapWriter.OP_MESSAGE -> out += ReadMessage(
                        channelId = u16(b, body),
                        sequence = u32(b, body + 2).toInt(),
                        logTime = u64(b, body + 6),
                        publishTime = u64(b, body + 14),
                        // The remainder of the record, with no length of its own.
                        data = b.copyOfRange(body + 22, (body + len).toInt()),
                    )
                    McapWriter.OP_CHUNK -> {
                        var p = body + 8 + 8 // start/end time
                        val uncompressed = u64(b, p); p += 8
                        p += 4 // uncompressed CRC
                        val nameLen = u32(b, p).toInt(); p += 4
                        val name = String(b, p, nameLen, Charsets.UTF_8); p += nameLen
                        val compressedLen = u64(b, p).toInt(); p += 8
                        assertEquals("zstd", name)
                        val raw = com.github.luben.zstd.Zstd.decompress(
                            b.copyOfRange(p, p + compressedLen),
                            uncompressed.toInt(),
                        )
                        walk(raw, 0, raw.size)
                    }
                }
                o = (body + len).toInt()
            }
        }

        walk(bytes, McapWriter.MAGIC.size, bytes.size)
        return out
    }


    private fun write(block: McapWriter.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = McapWriter(out)
        writer.start()
        writer.block()
        writer.finish()
        return out.toByteArray()
    }

    /** `\x89MCAP0\r\n` opens and closes every file. A reader checks both. */
    @Test
    fun `the file opens and closes with the magic bytes`() {
        val bytes = write { }

        assertArrayEquals(McapWriter.MAGIC, bytes.copyOfRange(0, 8))
        assertArrayEquals(McapWriter.MAGIC, bytes.copyOfRange(bytes.size - 8, bytes.size))
    }

    /** A run that produced nothing must still be a valid file, not a truncated one. */
    @Test
    fun `an empty recording is still a well-formed file`() {
        val bytes = write { }

        // magic + header + data end + statistics + 3 summary offsets + footer + magic
        assertTrue("suspiciously short: ${bytes.size}", bytes.size > 40)
        assertEquals(McapWriter.OP_HEADER, bytes[8].toInt() and 0xFF)
    }

    @Test
    fun `records carry their opcode and a little-endian length`() {
        val bytes = write { }

        // The header record follows the opening magic: opcode, then a uint64 length.
        assertEquals(McapWriter.OP_HEADER, bytes[8].toInt() and 0xFF)
        var length = 0L
        for (i in 0 until 8) length = length or ((bytes[9 + i].toLong() and 0xFF) shl (8 * i))
        // profile "" (4 bytes) + library string (4 + n)
        assertEquals((4 + 4 + McapWriter.LIBRARY.length).toLong(), length)
    }

    @Test
    fun `messages are counted and timestamps survive`() {
        val out = ByteArrayOutputStream()
        val writer = McapWriter(out)
        writer.start()
        val schema = writer.addSchema("keelson.TimestampedFloat", "protobuf", byteArrayOf(1, 2, 3))
        val channel = writer.addChannel("rise/@v0/pixel_6/pubsub/air_pressure_pa/phone", schema, "protobuf")
        writer.writeMessage(channel, 1, 1_000L, 900L, byteArrayOf(9, 9))
        writer.writeMessage(channel, 2, 2_000L, 1_900L, byteArrayOf(8))
        writer.finish()

        assertEquals(2L, writer.messageCount)
        val bytes = out.toByteArray()
        // The topic and schema name are written as UTF-8 and must appear verbatim.
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("rise/@v0/pixel_6/pubsub/air_pressure_pa/phone"))
        assertTrue(text.contains("keelson.TimestampedFloat"))
        assertTrue(text.contains("protobuf"))
    }

    /**
     * `@v0` is a verbatim chunk in Keelson keys and the replayer republishes the channel topic as-is,
     * so a mangled topic silently produces unroutable keys.
     */
    @Test
    fun `the channel topic is stored verbatim including the version chunk`() {
        val topic = "rise/@v0/pixel_6/pubsub/radio_rssi_dbm/cellular"
        val out = ByteArrayOutputStream()
        val writer = McapWriter(out)
        writer.start()
        val schema = writer.addSchema("keelson.TimestampedFloat", "protobuf", ByteArray(0))
        writer.addChannel(topic, schema, "protobuf")
        writer.finish()

        assertTrue(String(out.toByteArray(), Charsets.ISO_8859_1).contains(topic))
    }

    /**
     * `bytesWritten` drives rotation, so it must track the file — but only once chunks have flushed.
     *
     * It no longer equals the file size *mid-run*: a message contributes nothing until its chunk is
     * compressed and emitted. That is what makes the 512 MB cap approximate, and the assertion here is
     * deliberately about `finish()`, where the two must agree exactly.
     */
    @Test
    fun `bytes written matches the finished file`() {
        val out = ByteArrayOutputStream()
        val writer = McapWriter(out)
        writer.start()
        val schema = writer.addSchema("keelson.TimestampedFloat", "protobuf", ByteArray(0))
        val channel = writer.addChannel("t", schema, "protobuf")
        repeat(100) { writer.writeMessage(channel, it, it.toLong(), it.toLong(), ByteArray(64)) }
        writer.finish()

        assertEquals(writer.bytesWritten, out.toByteArray().size.toLong())
    }

    /** Compression is the point of chunking: repetitive framing should collapse. */
    @Test
    fun `a run of similar messages compresses`() {
        val out = ByteArrayOutputStream()
        val writer = McapWriter(out)
        writer.start()
        val schema = writer.addSchema("keelson.TimestampedFloat", "protobuf", ByteArray(0))
        val channel = writer.addChannel("t", schema, "protobuf")
        val payload = ByteArray(64) { it.toByte() }
        repeat(2_000) { writer.writeMessage(channel, it, it.toLong(), it.toLong(), payload) }
        writer.finish()

        val raw = 2_000L * (31 + payload.size)
        assertTrue(
            "expected the framing to compress; got ${out.size()} against $raw raw",
            out.size() < raw / 2,
        )
    }

    /** Two schemas with the same name are two records; dedup is the caller's job, not the writer's. */
    @Test
    fun `schema and channel ids increment independently`() {
        val out = ByteArrayOutputStream()
        val writer = McapWriter(out)
        writer.start()
        val a = writer.addSchema("A", "protobuf", ByteArray(0))
        val b = writer.addSchema("B", "protobuf", ByteArray(0))
        val c0 = writer.addChannel("t0", a, "protobuf")
        val c1 = writer.addChannel("t1", b, "protobuf")

        assertEquals(1, a)
        assertEquals(2, b)
        // Channel ids are uint16 starting at 0; schema id 0 means "no schema" in MCAP, so schemas
        // start at 1.
        assertEquals(0, c0)
        assertEquals(1, c1)
    }

    /**
     * The regression test for a real bug: `data` was written with a uint32 length prefix, like a
     * Schema's. The file still parsed — readers happily returned the bytes — but every payload then
     * failed to decode, because `data` had four extra bytes on the front. A Message's data is the
     * remainder of the record.
     */
    @Test
    fun `message data has no length prefix of its own`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        val out = ByteArrayOutputStream()
        val writer = McapWriter(out)
        writer.start()
        val schema = writer.addSchema("s", "protobuf", ByteArray(0))
        val channel = writer.addChannel("t", schema, "protobuf")
        writer.writeMessage(channel, 1, 10L, 11L, payload)
        writer.finish()

        // Read back rather than counted: four extra bytes on the front is exactly what the original bug
        // produced, and a file with them parses perfectly — only the payloads are wrong.
        val read = readMessages(out.toByteArray())
        assertEquals(1, read.size)
        assertArrayEquals(
            "an extra 4 bytes here means data was length-prefixed",
            payload,
            read[0].data,
        )
        assertEquals(channel, read[0].channelId)
        assertEquals(10L, read[0].logTime)
        assertEquals(11L, read[0].publishTime)
    }

    /** Every message survives a compressed round trip, in order, with its channel and times intact. */
    @Test
    fun `messages survive a compressed round trip`() {
        val out = ByteArrayOutputStream()
        val writer = McapWriter(out)
        writer.start()
        val schema = writer.addSchema("s", "protobuf", ByteArray(0))
        val a = writer.addChannel("t0", schema, "protobuf")
        val b = writer.addChannel("t1", schema, "protobuf")
        val payloads = (0 until 500).map { i -> ByteArray(1 + i % 40) { (i + it).toByte() } }
        payloads.forEachIndexed { i, p ->
            writer.writeMessage(if (i % 2 == 0) a else b, i, 1_000L + i, 2_000L + i, p)
        }
        writer.finish()

        val read = readMessages(out.toByteArray())
        assertEquals(payloads.size, read.size)
        read.forEachIndexed { i, m ->
            assertArrayEquals("payload $i", payloads[i], m.data)
            assertEquals(if (i % 2 == 0) a else b, m.channelId)
            assertEquals(1_000L + i, m.logTime)
            assertEquals(2_000L + i, m.publishTime)
        }
    }

    /**
     * A kill mid-chunk loses that chunk and **nothing before it**.
     *
     * This is the cost of compressing: recovery used to lose a single partial message, because messages
     * went straight to the file. Now whatever is still buffered goes with the process. `finalise` needs
     * no special case for it — a Chunk is a length-prefixed record like any other, so a truncated one is
     * already the incomplete-record branch — but the *size* of the loss is a design decision, and this
     * pins that the earlier chunks come back rather than the whole file being lost.
     */
    @Test
    fun `a kill mid-chunk keeps the chunks already flushed`() {
        val out = ByteArrayOutputStream()
        val writer = McapWriter(out)
        writer.start()
        val schema = writer.addSchema("s", "protobuf", ByteArray(0))
        val channel = writer.addChannel("t", schema, "protobuf")
        // Enough to force several flushes at the 256 kB target.
        val payload = ByteArray(512) { it.toByte() }
        repeat(3_000) { writer.writeMessage(channel, it, 1_000L + it, 2_000L + it, payload) }
        // No finish(): the process died. Whatever is still buffered was never written.
        val survived = readMessages(out.toByteArray()).size

        assertTrue("earlier chunks should have reached the file", survived > 0)
        assertTrue("the in-flight chunk cannot survive", survived < 3_000)

        val file = File.createTempFile("logline-kill", ".mcap")
        file.writeBytes(out.toByteArray())
        assertNotNull("a killed file needs rescuing", McapRecovery.finalise(file))

        // Everything that was flushed is still readable after the rescue, in order.
        val rescued = readMessages(file.readBytes())
        assertEquals(survived, rescued.size)
        rescued.forEachIndexed { i, m ->
            assertArrayEquals(payload, m.data)
            assertEquals(1_000L + i, m.logTime)
        }
        file.delete()
    }

    /**
     * A recording killed mid-write has valid records but no footer, and readers seek to the footer
     * first — so without this the data is all present and completely unreachable.
     */
    @Test
    fun `an interrupted file is finalised into a readable one`() {
        val file = File.createTempFile("logline", ".mcap")
        // Build a normal file, then chop the tail off to simulate the kill.
        val out = java.io.ByteArrayOutputStream()
        val w = McapWriter(out)
        w.start()
        val schema = w.addSchema("keelson.TimestampedFloat", "protobuf", ByteArray(0))
        val channel = w.addChannel("rise/@v0/pixel_6/pubsub/air_pressure_pa/phone", schema, "protobuf")
        repeat(5) { w.writeMessage(channel, it, it.toLong(), it.toLong(), ByteArray(16)) }
        val complete = out.toByteArray()
        // Truncate part-way through what would have been the next record.
        file.writeBytes(complete.copyOfRange(0, complete.size - 7))

        val trimmed = McapRecovery.finalise(file)

        assertTrue("should have trimmed a partial record", trimmed != null && trimmed >= 0)
        val recovered = file.readBytes()
        assertArrayEquals(
            "must close with the magic so a reader can find the footer",
            McapWriter.MAGIC,
            recovered.copyOfRange(recovered.size - 8, recovered.size),
        )
        file.delete()
    }

    /** A file that already closed cleanly must be left exactly as it is. */
    @Test
    fun `finalising a complete file is a no-op`() {
        val file = File.createTempFile("logline", ".mcap")
        file.writeBytes(write { })
        val before = file.readBytes()

        assertEquals(null, McapRecovery.finalise(file))
        assertArrayEquals(before, file.readBytes())
        file.delete()
    }

    @Test
    fun `finish is idempotent`() {
        val out = ByteArrayOutputStream()
        val writer = McapWriter(out)
        writer.start()
        writer.finish()
        val size = out.toByteArray().size
        writer.finish()

        assertEquals("a second finish must not append a second footer", size, out.toByteArray().size)
    }
}

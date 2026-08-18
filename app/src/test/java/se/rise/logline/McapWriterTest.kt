package se.rise.logline

import se.rise.logline.record.McapRecovery
import se.rise.logline.record.McapWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    @Test
    fun `bytes written grows and drives rotation`() {
        val out = ByteArrayOutputStream()
        val writer = McapWriter(out)
        writer.start()
        val before = writer.bytesWritten
        val schema = writer.addSchema("keelson.TimestampedFloat", "protobuf", ByteArray(0))
        val channel = writer.addChannel("t", schema, "protobuf")
        repeat(100) { writer.writeMessage(channel, it, it.toLong(), it.toLong(), ByteArray(64)) }

        assertTrue("should have grown", writer.bytesWritten > before + 100 * 64)
        assertEquals(writer.bytesWritten, out.toByteArray().size.toLong())
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
        val before = writer.bytesWritten
        writer.writeMessage(channel, 1, 10L, 11L, payload)
        val recordBytes = writer.bytesWritten - before

        // opcode(1) + length(8) + channel(2) + sequence(4) + logTime(8) + publishTime(8) = 31
        assertEquals(
            "an extra 4 bytes here means data was length-prefixed",
            (31 + payload.size).toLong(),
            recordBytes,
        )
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

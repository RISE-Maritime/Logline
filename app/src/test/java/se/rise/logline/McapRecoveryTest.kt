package se.rise.logline

import se.rise.logline.record.McapRecovery
import se.rise.logline.record.McapWriter
import se.rise.logline.record.readMcapSummary
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the orphan sweep does to a file, and — the reason this file exists — what it would do to one
 * that is still being written.
 *
 * The sweep skips any file the drain owns. That guard is not testable from here without a `Context`,
 * so what is pinned instead is the thing it is guarding *against*: `finalise()` on a file with an
 * open writer behind it is destructive, so "skip it" has to be a rule rather than a courtesy.
 */
class McapRecoveryTest {

    private fun write(into: File, messages: Int, close: Boolean): McapWriter {
        val out = into.outputStream()
        val writer = McapWriter(out)
        writer.start()
        val schema = writer.addSchema("keelson.TimestampedFloat", "protobuf", byteArrayOf(1, 2, 3))
        val channel = writer.addChannel("rise/@v0/pixel_6/pubsub/air_pressure_pa/phone", schema, "protobuf")
        repeat(messages) { i ->
            writer.writeMessage(channel, i + 1, 1_000L + i, 1_000L + i, byteArrayOf(9, 9, 9, 9))
        }
        if (close) {
            writer.finish()
            out.close()
        }
        return writer
    }

    private fun summaryOf(file: File) =
        java.io.RandomAccessFile(file, "r").use { readMcapSummary(it.channel) }

    private fun temp(name: String): File =
        File.createTempFile(name, ".mcap").apply { deleteOnExit() }

    /**
     * A properly closed recording is left completely alone — the tail is already the closing magic,
     * so `finalise()` returns null before it walks anything.
     *
     * This is what lets the sweep run over a folder containing files that were published perfectly
     * well and simply have not been deleted yet, without a guard of its own.
     */
    @Test
    fun `a closed recording is not touched`() {
        val file = temp("closed")
        write(file, messages = 20, close = true)
        val before = file.readBytes()

        assertNull(McapRecovery.finalise(file))
        assertArrayEquals(before, file.readBytes())
    }

    /**
     * An interrupted one is repaired: trimmed to the last complete record, then given a DataEnd and
     * a footer declaring no summary. `readMcapSummary` returns null for it, which is the signal the
     * Files tab reads as "incomplete, never closed".
     */
    @Test
    fun `an interrupted recording is made readable`() {
        val file = temp("interrupted")
        val out = file.outputStream()
        val writer = McapWriter(out)
        writer.start()
        val schema = writer.addSchema("keelson.TimestampedFloat", "protobuf", byteArrayOf(1, 2, 3))
        val channel = writer.addChannel("rise/@v0/pixel_6/pubsub/air_pressure_pa/phone", schema, "protobuf")
        repeat(50) { i -> writer.writeMessage(channel, i + 1, 1_000L + i, 1_000L + i, byteArrayOf(9, 9, 9, 9)) }
        // No `finish()`: the process died here. Flushed so the bytes reach the file, which is what
        // an orderly shutdown does for the page cache.
        out.flush()
        out.close()

        assertNull("unreadable before repair", summaryOf(file))
        assertNotNull("repair should report what it trimmed", McapRecovery.finalise(file))
        // Readable now, and openly summary-less rather than broken.
        assertTrue(file.readBytes().takeLast(8).toByteArray().contentEquals(McapWriter.MAGIC))
        assertNull("a repaired file has no statistics", summaryOf(file))
    }

    /** Repairing twice is a no-op the second time, since the first pass leaves a closed file. */
    @Test
    fun `repairing an already repaired recording changes nothing`() {
        val file = temp("twice")
        val out = file.outputStream()
        val writer = McapWriter(out)
        writer.start()
        writer.addSchema("keelson.TimestampedFloat", "protobuf", byteArrayOf(1, 2, 3))
        out.flush()
        out.close()

        McapRecovery.finalise(file)
        val once = file.readBytes()
        assertNull(McapRecovery.finalise(file))
        assertArrayEquals(once, file.readBytes())
    }

    /**
     * **Why the sweep must skip a file the drain owns.**
     *
     * `finalise()` truncates to the last complete record and appends a footer. Run against a file a
     * `RecordingSession` still has open, that leaves the writer holding a stream positioned past the
     * new end — so everything it writes afterwards lands beyond the footer, and the result is a file
     * whose declared ending sits in the middle of it. Reading it back gets the messages written
     * before the sweep and nothing after, silently.
     *
     * The recorder's guard is what prevents this; the point of pinning it here is that the guard is
     * load-bearing rather than defensive, and anybody tempted to simplify it should see the shape of
     * what it stops first.
     */
    @Test
    fun `finalising a file that is still being written truncates it under the writer`() {
        val file = temp("live")
        val out = file.outputStream()
        val writer = McapWriter(out)
        writer.start()
        val schema = writer.addSchema("keelson.TimestampedFloat", "protobuf", byteArrayOf(1, 2, 3))
        val channel = writer.addChannel("rise/@v0/pixel_6/pubsub/air_pressure_pa/phone", schema, "protobuf")
        repeat(30) { i -> writer.writeMessage(channel, i + 1, 1_000L + i, 1_000L + i, byteArrayOf(9, 9, 9, 9)) }
        out.flush()

        val lengthWhenSwept = file.length()
        McapRecovery.finalise(file)
        val afterRepair = file.length()

        // The sweep has decided this file is finished and stamped an ending on it...
        assertTrue("a footer was appended", afterRepair > lengthWhenSwept)
        assertTrue(file.readBytes().takeLast(8).toByteArray().contentEquals(McapWriter.MAGIC))

        // ...while the writer carries on, appending past the ending somebody else just wrote.
        repeat(30) { i -> writer.writeMessage(channel, 31 + i, 2_000L + i, 2_000L + i, byteArrayOf(9, 9, 9, 9)) }
        writer.finish()
        out.close()

        // The file now has a footer buried in its middle. Everything written after the sweep is on
        // the far side of an ending that claims the file stops there.
        assertTrue("the run kept writing past the footer", file.length() > afterRepair)
        out.close()
    }

    /**
     * **A tail of zeros is not data, and the walk must stop at it.**
     *
     * The failure this guards against is not a truncation — that case is covered above and the walk
     * handles it, because a partial record does not fit the file and the loop breaks. It is what a
     * power cut can leave on a filesystem with delayed allocation: the file's *length* was journalled
     * but its last blocks were never written, so the tail comes back as zeros rather than short.
     *
     * Those zeros parse. Opcode `0x00`, length `0`, nine bytes consumed, repeat — so the walk marches
     * to the end of the padding and stamps a footer after it, and the file's declared data section
     * now contains a stretch of records this app never wrote. The messages before it survive, which
     * is why this is a wrongness at the edge rather than a loss, but the trim has landed in the wrong
     * place and `0x00` is not an MCAP opcode at all.
     */
    @Test
    fun `a tail of zeros is trimmed rather than kept as records`() {
        val file = temp("zerotail")
        val out = file.outputStream()
        val writer = McapWriter(out)
        writer.start()
        val schema = writer.addSchema("keelson.TimestampedFloat", "protobuf", byteArrayOf(1, 2, 3))
        val channel = writer.addChannel("rise/@v0/pixel_6/pubsub/air_pressure_pa/phone", schema, "protobuf")
        repeat(40) { i -> writer.writeMessage(channel, i + 1, 1_000L + i, 1_000L + i, byteArrayOf(9, 9, 9, 9)) }
        out.flush()
        out.close()
        val realBytes = file.length()

        // The blocks the kernel never got round to writing.
        file.appendBytes(ByteArray(4096))
        assertEquals(realBytes + 4096, file.length())

        val trimmed = McapRecovery.finalise(file)

        assertEquals("the whole zero tail should go", 4096L, trimmed)
        // And what is left is the real data plus an ending, with nothing in between.
        assertEquals(realBytes + FOOTER_BYTES, file.length())
        assertTrue(file.readBytes().takeLast(8).toByteArray().contentEquals(McapWriter.MAGIC))
        assertArrayEquals(
            "every real byte kept",
            file.readBytes().copyOfRange(0, realBytes.toInt()),
            file.readBytes().copyOfRange(0, realBytes.toInt()),
        )
    }

    /**
     * The same rule from the other side: a byte that is not an opcode this writer emits ends the
     * walk, whatever it is. Garbage in the tail is garbage whether it happens to be zeros or not.
     */
    @Test
    fun `an unrecognised opcode ends the walk`() {
        val file = temp("garbage")
        val out = file.outputStream()
        val writer = McapWriter(out)
        writer.start()
        writer.addSchema("keelson.TimestampedFloat", "protobuf", byteArrayOf(1, 2, 3))
        out.flush()
        out.close()
        val realBytes = file.length()

        // A record header claiming an opcode MCAP has never defined, with a plausible length.
        file.appendBytes(byteArrayOf(0x7F) + ByteArray(8) + ByteArray(16))

        assertEquals(25L, McapRecovery.finalise(file))
        assertEquals(realBytes + FOOTER_BYTES, file.length())
    }

    private companion object {
        /** DataEnd (9 + 4) + Footer (9 + 20) + the closing magic (8). */
        const val FOOTER_BYTES = 50L
    }
}

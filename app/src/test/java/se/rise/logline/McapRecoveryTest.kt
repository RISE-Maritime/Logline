package se.rise.logline

import se.rise.logline.record.McapRecovery
import se.rise.logline.record.McapWriter
import se.rise.logline.record.readMcapDetails
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

    private fun detailsOf(file: File) =
        java.io.RandomAccessFile(file, "r").use { readMcapDetails(it.channel) }

    /** A topic and its count, so a list of them compares by value in a failure message. */
    private data class TopicAndCount(val topic: String, val messages: Long)

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
     * An interrupted one is repaired: trimmed to the last complete record, then finished the way a
     * proper close would have finished it.
     *
     * Nothing here reached a chunk — 50 tiny messages are far short of the 256 kB flush and the
     * writer never got to `finish()` — so the summary honestly reports **no messages**. That the
     * summary exists at all is the point: a file that states nothing is what left Foxglove opening a
     * rescued recording on a timeline back to 1970.
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
        assertTrue(file.readBytes().takeLast(8).toByteArray().contentEquals(McapWriter.MAGIC))
        val summary = summaryOf(file)
        assertNotNull("a repaired file states a summary", summary)
        assertEquals("nothing was flushed, so nothing survived", 0L, summary!!.messages)
    }

    /**
     * **The bug this rebuild exists for: a rescued recording used to state no time range at all.**
     *
     * Recovery wrote `summary_start = 0`, the spec's "no summary", so nothing in the file said when
     * it began — and a reader with no range to show has to invent one. Measured on a real file:
     * Foxglove opened a rescued recording on a timeline running from **1970 to the afternoon it was
     * made**, 46 years of nothing, while every message in it was minutes old; `mcap info` answered
     * `channels: unknown`. Neither is a reader misbehaving.
     *
     * So the start must be the first surviving message's own log time, and never zero.
     */
    @Test
    fun `a repaired recording states the range of the messages it kept`() {
        val file = temp("range")
        val out = file.outputStream()
        val writer = McapWriter(out)
        writer.start()
        val schema = writer.addSchema("keelson.TimestampedFloat", "protobuf", byteArrayOf(1, 2, 3))
        val channel = writer.addChannel(TOPIC, schema, "protobuf")
        // Wall clock is injectable precisely so a test need not sleep: three seconds per message
        // passes the two-second bound, so every pair of messages lands in a flushed chunk rather
        // than in the one the kill would take with it.
        repeat(10) { i ->
            writer.writeMessage(
                channel,
                i + 1,
                logTime = FIRST_LOG_TIME + i,
                publishTime = FIRST_LOG_TIME + i,
                data = byteArrayOf(9, 9, 9, 9),
                nowNanos = i * 3_000_000_000L,
            )
        }
        out.flush()
        out.close()

        assertNotNull(McapRecovery.finalise(file))

        val details = detailsOf(file)
        assertNotNull("a repaired file states a summary", details)
        assertEquals(10L, details!!.summary.messages)
        assertEquals("the first message's own time, not zero", FIRST_LOG_TIME, details.summary.startNanos)
        assertEquals(FIRST_LOG_TIME + 9, details.summary.endNanos)
        assertEquals(listOf(TopicAndCount(TOPIC, 10L)), details.topics.map { TopicAndCount(it.topic, it.messages) })
    }

    /**
     * Counts describe what survived the trim, not what the run wrote.
     *
     * A killed process leaves a partial record, so the last chunk goes — with the messages in it. A
     * summary that reported the writer's own total would be a file claiming messages a reader cannot
     * find, which is worse than the missing statistics this replaced.
     */
    @Test
    fun `the figures describe what survived the trim`() {
        val file = temp("trimmed")
        val out = file.outputStream()
        val writer = McapWriter(out)
        writer.start()
        val schema = writer.addSchema("keelson.TimestampedFloat", "protobuf", byteArrayOf(1, 2, 3))
        val channel = writer.addChannel(TOPIC, schema, "protobuf")
        repeat(10) { i ->
            writer.writeMessage(
                channel,
                i + 1,
                logTime = FIRST_LOG_TIME + i,
                publishTime = FIRST_LOG_TIME + i,
                data = byteArrayOf(9, 9, 9, 9),
                nowNanos = i * 3_000_000_000L,
            )
        }
        out.flush()
        out.close()
        // The kill lands mid-record: the last chunk no longer fits the file, so the walk stops before
        // it and its two messages go with it.
        java.io.RandomAccessFile(file, "rw").use { it.setLength(file.length() - 5) }

        assertNotNull(McapRecovery.finalise(file))

        val details = detailsOf(file)!!
        assertEquals(8L, details.summary.messages)
        assertEquals(FIRST_LOG_TIME, details.summary.startNanos)
        assertEquals("the last surviving message, not the last written", FIRST_LOG_TIME + 7, details.summary.endNanos)
    }

    /**
     * **A rescued file says so, in the file.**
     *
     * It is a proper MCAP now — summary, footer and all — so nothing else about its bytes tells a
     * reader that the run was interrupted, and the Files tab used to learn that from the missing
     * summary. `SavedRecording.isComplete` reads this, so without it every interrupted run would
     * quietly become complete and drop out of the bulk delete.
     */
    @Test
    fun `a repaired recording is marked as rescued`() {
        val file = temp("marked")
        val out = file.outputStream()
        val writer = McapWriter(out)
        writer.start()
        writer.addSchema("keelson.TimestampedFloat", "protobuf", byteArrayOf(1, 2, 3))
        out.flush()
        out.close()

        McapRecovery.finalise(file)

        assertTrue("the rescue is recorded in the file", detailsOf(file)!!.rescued)
    }

    /**
     * A closed recording is not rescued, and its tags still come back.
     *
     * The rescue note is a second Metadata record, and the index that finds the tags used to filter
     * on the one name it knew. A reader that could not tell the two apart would read the rescue note
     * as a tag list.
     */
    @Test
    fun `a closed recording keeps its tags and is not marked rescued`() {
        val file = temp("tagged")
        val out = file.outputStream()
        val writer = McapWriter(out)
        writer.start()
        writer.addSchema("keelson.TimestampedFloat", "protobuf", byteArrayOf(1, 2, 3))
        writer.tags = setOf("quay trial", "engine run")
        writer.finish()
        out.close()

        val details = detailsOf(file)!!
        assertEquals(setOf("quay trial", "engine run"), details.tags)
        assertTrue("a run somebody stopped was not rescued", !details.rescued)
    }

    /**
     * **A summary that undercounts is worse than none.**
     *
     * A chunk this app cannot decompress means the walk does not know what the file holds, and
     * figures somebody plans against must not be guesses. So the file still gets an ending it can be
     * opened with, and states nothing about its contents — which is exactly what recovery produced
     * for every file before it learned to rebuild a summary.
     */
    @Test
    fun `an unreadable chunk falls back to declaring no summary`() {
        val file = temp("badchunk")
        val out = file.outputStream()
        val writer = McapWriter(out)
        writer.start()
        writer.addSchema("keelson.TimestampedFloat", "protobuf", byteArrayOf(1, 2, 3))
        out.flush()
        out.close()

        // A chunk in a compression this app does not read. Legal MCAP, and unreadable here.
        val body = McapWriter.Buffer()
        body.putUInt64(1_000L) // message start time
        body.putUInt64(2_000L) // message end time
        body.putUInt64(4L) // uncompressed size
        body.putUInt32(0) // uncompressed CRC
        body.putString("lz4")
        body.putUInt64(4L)
        body.putRaw(byteArrayOf(1, 2, 3, 4))
        val payload = body.toByteArray()
        val record = McapWriter.Buffer()
        record.putUInt8(McapWriter.OP_CHUNK)
        record.putUInt64(payload.size.toLong())
        record.putRaw(payload)
        file.appendBytes(record.toByteArray())

        assertNotNull(McapRecovery.finalise(file))

        assertTrue("still openable", file.readBytes().takeLast(8).toByteArray().contentEquals(McapWriter.MAGIC))
        assertNull("and claims nothing about what it holds", summaryOf(file))
    }

    private companion object {
        const val TOPIC = "rise/@v0/pixel_6/pubsub/air_pressure_pa/phone"

        /** An ordinary epoch-nanosecond time, i.e. one that is nothing like zero. */
        const val FIRST_LOG_TIME = 1_788_969_803_033_425_000L
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
        val before = file.readBytes()

        // The blocks the kernel never got round to writing.
        file.appendBytes(ByteArray(4096))
        assertEquals(realBytes + 4096, file.length())

        val trimmed = McapRecovery.finalise(file)

        assertEquals("the whole zero tail should go", 4096L, trimmed)
        // And what is left is the real data with an ending after it, and nothing in between. The
        // ending is a whole summary section now, so its size is not a constant worth pinning — that
        // the real bytes are untouched is the claim.
        assertTrue("an ending was appended", file.length() > realBytes)
        assertTrue(file.readBytes().takeLast(8).toByteArray().contentEquals(McapWriter.MAGIC))
        assertArrayEquals(
            "every real byte kept",
            before,
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
        assertTrue("the real bytes kept, with an ending after them", file.length() > realBytes)
    }
}

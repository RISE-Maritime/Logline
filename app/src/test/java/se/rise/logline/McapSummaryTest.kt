package se.rise.logline

import se.rise.logline.record.McapRecovery
import se.rise.logline.record.McapWriter
import se.rise.logline.record.readMcapSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * The reader against the writer, which is the only thing that can check either.
 *
 * There is no MCAP library for Java, so both halves of this are ours and neither is checked by anything
 * else. The reader steps over four fields it does not want to reach four it does — a wrong width
 * anywhere produces a number that is merely wrong rather than an error, which is what a test comparing
 * it against a file of known contents is for.
 */
class McapSummaryTest {

    private fun recording(
        into: File,
        messages: Int,
        firstLogTime: Long,
        step: Long,
    ) {
        into.outputStream().use { out ->
            val writer = McapWriter(out)
            writer.start()
            val schema = writer.addSchema("keelson.TimestampedFloat", "protobuf", byteArrayOf(1, 2, 3))
            val channel = writer.addChannel(
                "rise/@v0/pixel_6/pubsub/air_pressure_pa/phone",
                schema,
                "protobuf",
            )
            repeat(messages) { i ->
                val at = firstLogTime + i * step
                writer.writeMessage(channel, i, at, at, byteArrayOf(9, 9))
            }
            writer.finish()
        }
    }

    private fun summaryOf(file: File) =
        RandomAccessFile(file, "r").use { readMcapSummary(it.channel) }

    @Test
    fun `a finished recording reports its own count and time range`() {
        val file = File.createTempFile("logline-", ".mcap").apply { deleteOnExit() }
        // 100 messages a second apart, starting at a plausible epoch nanosecond.
        val start = 1_787_000_000_000_000_000L
        recording(file, messages = 100, firstLogTime = start, step = 1_000_000_000L)

        val summary = requireNotNull(summaryOf(file)) { "the file should carry statistics" }

        assertEquals(100L, summary.messages)
        assertEquals(1, summary.channels)
        assertEquals(start, summary.startNanos)
        assertEquals(start + 99 * 1_000_000_000L, summary.endNanos)
        assertEquals(99_000L, summary.durationMillis)
    }

    /** One message is a real recording with no duration, not a broken one. */
    @Test
    fun `a single message spans nothing`() {
        val file = File.createTempFile("logline-", ".mcap").apply { deleteOnExit() }
        recording(file, messages = 1, firstLogTime = 5_000_000_000L, step = 0L)

        val summary = requireNotNull(summaryOf(file))

        assertEquals(1L, summary.messages)
        assertEquals(0L, summary.durationMillis)
    }

    /** A run that produced nothing still closes properly, and says zero rather than failing to open. */
    @Test
    fun `an empty recording reads as empty`() {
        val file = File.createTempFile("logline-", ".mcap").apply { deleteOnExit() }
        recording(file, messages = 0, firstLogTime = 0L, step = 0L)

        val summary = requireNotNull(summaryOf(file))

        assertEquals(0L, summary.messages)
        assertEquals(0L, summary.durationMillis)
    }

    /**
     * A recording rescued from a killed process reports the messages it kept.
     *
     * **This used to assert the opposite** — `McapRecovery.finalise` rebuilt the footer with
     * `summary_start = 0`, the spec's "no summary", on the argument that the statistics had never
     * been written. They had not, and they were still derivable: everything a summary states comes
     * out of the data section, which is where it came from in the first place. What the missing
     * summary cost was a reader with no time range to show, and Foxglove opening a rescued file on a
     * timeline back to 1970.
     *
     * Nullability is still load-bearing — an export in `Downloads/Logline` has no summary and neither
     * has a file this app cannot walk — which the two tests either side of this one cover.
     */
    @Test
    fun `a recovered file reports what survived`() {
        val file = File.createTempFile("logline-", ".mcap").apply { deleteOnExit() }
        recording(file, messages = 50, firstLogTime = 1_000_000_000L, step = 1_000_000L)
        // Cut the summary and footer off, as a kill would, then rescue it.
        RandomAccessFile(file, "rw").use { it.setLength(it.length() / 2) }
        assertNotNull("the fixture should need rescuing", McapRecovery.finalise(file))

        val summary = requireNotNull(summaryOf(file)) { "a rescued file states a summary" }
        // Half a file of unflushed messages leaves nothing behind, which is the honest answer and
        // not the same claim as saying nothing at all.
        assertEquals(0L, summary.messages)
    }

    /** Anything that is not one of our files reads as "no summary" rather than throwing. */
    @Test
    fun `a foreign or truncated file is not an error`() {
        val empty = File.createTempFile("logline-", ".mcap").apply { deleteOnExit() }
        assertNull(summaryOf(empty))

        val garbage = File.createTempFile("logline-", ".mcap").apply { deleteOnExit() }
        garbage.writeBytes(ByteArray(512) { it.toByte() })
        assertNull(summaryOf(garbage))
    }

    /** The point of reading the footer rather than scanning: cost must not follow file size. */
    @Test
    fun `a long recording costs no more to summarise than a short one`() {
        val short = File.createTempFile("logline-", ".mcap").apply { deleteOnExit() }
        val long = File.createTempFile("logline-", ".mcap").apply { deleteOnExit() }
        recording(short, messages = 10, firstLogTime = 1_000L, step = 10L)
        recording(long, messages = 20_000, firstLogTime = 1_000L, step = 10L)

        assertTrue("the fixture should be much bigger", long.length() > short.length() * 100)
        assertEquals(20_000L, requireNotNull(summaryOf(long)).messages)
    }
}

package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.record.McapWriter
import se.rise.logline.record.readMcapDetails
import java.io.File
import java.io.RandomAccessFile

/**
 * Tags written into the recording itself, so they travel with the file.
 *
 * Written and read by this app's own code at both ends, which is exactly why it is pinned here and
 * cross-checked against the real `mcap` library on a device file: a wrong field width in a record
 * nobody else writes produces a file that parses and quietly carries nothing.
 */
class McapTagsTest {

    private fun write(into: File, tags: Set<String>, messages: Int = 3) {
        into.outputStream().use { out ->
            val writer = McapWriter(out)
            writer.start()
            val schema = writer.addSchema("keelson.TimestampedFloat", "protobuf", byteArrayOf(1))
            val channel = writer.addChannel("rise/@v0/p/pubsub/air_pressure_pa/phone", schema, "protobuf")
            repeat(messages) { i ->
                writer.writeMessage(channel, i, 1_000L + i, 1_000L + i, byteArrayOf(1, 2, 3))
            }
            writer.tags = tags
            writer.finish()
        }
    }

    private fun readTags(file: File): Set<String> =
        RandomAccessFile(file, "r").use { readMcapDetails(it.channel) }!!.tags

    @Test
    fun `tags survive a round trip through the file`() {
        val file = File.createTempFile("tags", ".mcap")
        try {
            write(file, setOf("quay trial", "engine run"))

            assertEquals(setOf("quay trial", "engine run"), readTags(file))
        } finally {
            file.delete()
        }
    }

    /** A recording nobody tagged carries no metadata record at all, and reads back as none. */
    @Test
    fun `no tags is not an empty tag`() {
        val file = File.createTempFile("tags", ".mcap")
        try {
            write(file, emptySet())

            assertTrue(readTags(file).isEmpty())
        } finally {
            file.delete()
        }
    }

    /**
     * **The figures must be untouched by this.** The tags ride in the summary section beside them, and
     * a wrong length in the new records would push everything after them out of place.
     */
    @Test
    fun `the statistics still read correctly with tags present`() {
        val tagged = File.createTempFile("tagged", ".mcap")
        val plain = File.createTempFile("plain", ".mcap")
        try {
            write(tagged, setOf("quay trial"), messages = 7)
            write(plain, emptySet(), messages = 7)

            val a = RandomAccessFile(tagged, "r").use { readMcapDetails(it.channel) }!!
            val b = RandomAccessFile(plain, "r").use { readMcapDetails(it.channel) }!!

            assertEquals(7L, a.summary.messages)
            assertEquals(b.summary.messages, a.summary.messages)
            assertEquals(b.topics.map { it.topic }, a.topics.map { it.topic })
            assertEquals(b.topics.map { it.messages }, a.topics.map { it.messages })
        } finally {
            tagged.delete()
            plain.delete()
        }
    }

    /** Whatever was set last is what the file carries — the state at close, not at start. */
    @Test
    fun `the tags at close are the ones written`() {
        val file = File.createTempFile("tags", ".mcap")
        try {
            file.outputStream().use { out ->
                val writer = McapWriter(out)
                writer.start()
                writer.tags = setOf("first")
                val schema = writer.addSchema("s", "protobuf", byteArrayOf(1))
                val channel = writer.addChannel("t", schema, "protobuf")
                writer.writeMessage(channel, 0, 1L, 1L, byteArrayOf(1))
                writer.tags = setOf("second", "third")
                writer.finish()
            }

            assertEquals(setOf("second", "third"), readTags(file))
        } finally {
            file.delete()
        }
    }
}

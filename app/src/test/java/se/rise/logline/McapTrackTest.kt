package se.rise.logline

import foxglove.LocationFixOuterClass.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.record.McapTrack
import se.rise.logline.record.McapWriter
import se.rise.logline.record.TopicCount
import se.rise.logline.record.TrackFix
import se.rise.logline.record.readMcapDetails
import java.io.File
import java.io.RandomAccessFile

/**
 * The track reader against the writer, in the same shape as [McapSummaryTest] and for the same reason:
 * both halves are ours, there is no MCAP library for Java to check either against, and a wrong field
 * width produces plausible data rather than an error.
 *
 * These write real recordings — chunked, zstd-compressed, with real `foxglove.LocationFix` payloads —
 * and read them back.
 */
class McapTrackTest {

    private val phoneFix = "rise/@v0/pixel_6/pubsub/location_fix/phone"
    private val rigZero = "rise/@v0/ssrs18/pubsub/location_fix/calibration"
    private val pressure = "rise/@v0/pixel_6/pubsub/air_pressure_pa/phone"

    private fun fix(lat: Double, lon: Double): ByteArray =
        LocationFix.newBuilder().setLatitude(lat).setLongitude(lon).build().toByteArray()

    /** A recording with the given topics, each carrying the payloads listed for it. */
    private fun recording(into: File, topics: List<Pair<String, List<ByteArray>>>) {
        into.outputStream().use { out ->
            val writer = McapWriter(out)
            writer.start()
            var sequence = 0
            val channels = topics.map { (topic, _) ->
                val schema = writer.addSchema("foxglove.LocationFix", "protobuf", byteArrayOf(1))
                topic to writer.addChannel(topic, schema, "protobuf")
            }.toMap()
            // Interleaved across topics the way a real run produces them, so the reader has to filter
            // rather than merely find a contiguous block.
            val most = topics.maxOf { it.second.size }
            repeat(most) { i ->
                topics.forEach { (topic, payloads) ->
                    payloads.getOrNull(i)?.let { data ->
                        val at = 1_000_000_000L + sequence * 1_000_000L
                        writer.writeMessage(channels.getValue(topic), sequence++, at, at, data)
                    }
                }
            }
            writer.finish()
        }
    }

    private fun trackOf(file: File): List<TrackFix> {
        val details = RandomAccessFile(file, "r").use { readMcapDetails(it.channel) }
        assertNotNull("the file should have a readable summary", details)
        val channel = McapTrack.fixChannel(details!!.topics) ?: return emptyList()
        return file.inputStream().use { McapTrack.read(it, channel.channelId) }
    }

    @Test
    fun `the fixes come back in the order they were written`() {
        val file = File.createTempFile("track", ".mcap")
        try {
            recording(
                file,
                listOf(
                    phoneFix to listOf(fix(57.10, 12.10), fix(57.20, 12.20), fix(57.30, 12.30)),
                    pressure to listOf(byteArrayOf(9), byteArrayOf(9), byteArrayOf(9)),
                ),
            )

            assertEquals(
                listOf(
                    TrackFix(57.10, 12.10),
                    TrackFix(57.20, 12.20),
                    TrackFix(57.30, 12.30),
                ),
                trackOf(file),
            )
        } finally {
            file.delete()
        }
    }

    /**
     * **A rig's surveyed zero point is not a track.**
     *
     * Two registry entries publish `location_fix`: the phone's live position and a rig's zero point,
     * which is a jetty somebody stood on with a tape measure. Reading both would draw a line from the
     * boat to the shore and call it a run.
     */
    @Test
    fun `a calibration zero point is not mistaken for the track`() {
        val file = File.createTempFile("track", ".mcap")
        try {
            recording(
                file,
                listOf(
                    rigZero to listOf(fix(57.99, 11.99)),
                    phoneFix to listOf(fix(57.10, 12.10), fix(57.20, 12.20)),
                ),
            )

            val details = RandomAccessFile(file, "r").use { readMcapDetails(it.channel) }!!
            assertEquals(phoneFix, McapTrack.fixChannel(details.topics)?.topic)
            assertEquals(listOf(TrackFix(57.10, 12.10), TrackFix(57.20, 12.20)), trackOf(file))
        } finally {
            file.delete()
        }
    }

    /** A rig zero point on its own is no track at all, rather than a one-point one. */
    @Test
    fun `a recording with only a calibration fix has no track channel`() {
        val file = File.createTempFile("track", ".mcap")
        try {
            recording(file, listOf(rigZero to listOf(fix(57.99, 11.99))))

            val details = RandomAccessFile(file, "r").use { readMcapDetails(it.channel) }!!
            assertNull(McapTrack.fixChannel(details.topics))
        } finally {
            file.delete()
        }
    }

    /**
     * An IMU-only run has no fix channel, which is what lets the caller skip the scan entirely — the
     * expensive part of this reader is never entered.
     */
    @Test
    fun `a recording with no fixes has no track channel`() {
        val file = File.createTempFile("track", ".mcap")
        try {
            recording(file, listOf(pressure to listOf(byteArrayOf(9), byteArrayOf(9))))

            val details = RandomAccessFile(file, "r").use { readMcapDetails(it.channel) }!!
            assertNull(McapTrack.fixChannel(details.topics))
            assertEquals(2L, details.topics.single().messages)
        } finally {
            file.delete()
        }
    }

    /**
     * **`0, 0` is dropped**, because proto3 cannot tell an absent double from a zero one — a fix that
     * carried no position decodes as the Gulf of Guinea, and one stray point there rescales the whole
     * chart to the Atlantic.
     */
    @Test
    fun `a fix with no position is not a point in the Atlantic`() {
        val file = File.createTempFile("track", ".mcap")
        try {
            recording(
                file,
                listOf(phoneFix to listOf(fix(57.10, 12.10), fix(0.0, 0.0), fix(57.20, 12.20))),
            )

            assertEquals(listOf(TrackFix(57.10, 12.10), TrackFix(57.20, 12.20)), trackOf(file))
        } finally {
            file.delete()
        }
    }

    /** Enough messages to span several chunks, so the walk has to decompress more than one. */
    @Test
    fun `a track spanning many chunks is read whole`() {
        val file = File.createTempFile("track", ".mcap")
        try {
            val fixes = (0 until 5_000).map { fix(57.0 + it / 100_000.0, 12.0) }
            recording(file, listOf(phoneFix to fixes))

            val track = trackOf(file)
            assertTrue("thinned to the cap", track.size <= McapTrack.MAX_POINTS)
            assertEquals("the first fix survives", 57.0, track.first().latitude, 1e-9)
            assertEquals("and the last", 57.0 + 4_999 / 100_000.0, track.last().latitude, 1e-9)
        } finally {
            file.delete()
        }
    }

    /**
     * Downsampling keeps the ends, which is most of what makes a track recognisable — a plain stride
     * would drop whichever of them the arithmetic happened to miss.
     */
    @Test
    fun `downsampling keeps the first and last fix`() {
        val points = (0 until 1_000).map { TrackFix(it.toDouble(), 0.0) }

        val thinned = McapTrack.downsample(points, 10)

        assertEquals(10, thinned.size)
        assertEquals(0.0, thinned.first().latitude, 0.0)
        assertEquals(999.0, thinned.last().latitude, 0.0)
        // Order is preserved, which a set-based thinning would not guarantee.
        assertEquals(thinned.sortedBy { it.latitude }, thinned)
    }

    @Test
    fun `a track shorter than the cap is left alone`() {
        val points = listOf(TrackFix(1.0, 1.0), TrackFix(2.0, 2.0))

        assertEquals(points, McapTrack.downsample(points, 10))
    }

    /** Every topic and its own count, straight off the footer — no data section read at all. */
    @Test
    fun `the summary names every topic with its message count`() {
        val file = File.createTempFile("track", ".mcap")
        try {
            recording(
                file,
                listOf(
                    phoneFix to List(3) { fix(57.0, 12.0) },
                    pressure to List(7) { byteArrayOf(9) },
                ),
            )

            val details = RandomAccessFile(file, "r").use { readMcapDetails(it.channel) }!!

            // Busiest first, which is the question somebody has of a forty-subject run.
            assertEquals(
                listOf(TopicCount(details.topics[0].channelId, pressure, 7L),
                       TopicCount(details.topics[1].channelId, phoneFix, 3L)),
                details.topics,
            )
            assertEquals(10L, details.summary.messages)
        } finally {
            file.delete()
        }
    }
}

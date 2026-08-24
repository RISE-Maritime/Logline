package se.rise.logline

import foxglove.LocationFixOuterClass.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.record.McapTrack
import se.rise.logline.record.McapWriter
import se.rise.logline.record.TopicCount
import se.rise.logline.record.TrackFix
import se.rise.logline.record.readMcapDetails
import com.github.luben.zstd.Zstd
import se.rise.logline.record.MAGIC_SIZE
import se.rise.logline.record.RECORD_HEADER_SIZE
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    private val platformZero = "rise/@v0/ssrs18/pubsub/location_fix/calibration"
    private val pressure = "rise/@v0/pixel_6/pubsub/air_pressure_pa/phone"

    private fun fix(lat: Double, lon: Double): ByteArray =
        LocationFix.newBuilder().setLatitude(lat).setLongitude(lon).build().toByteArray()

    /** A recording with the given topics, each carrying the payloads listed for it. Answers their ids. */
    private fun recording(into: File, topics: List<Pair<String, List<ByteArray>>>): Map<String, Int> =
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
            channels
        }

    private fun trackOf(file: File): List<TrackFix> {
        val details = RandomAccessFile(file, "r").use { readMcapDetails(it.channel) }
        assertNotNull("the file should have a readable summary", details)
        val channel = McapTrack.fixChannel(details!!.topics) ?: return emptyList()
        return file.inputStream().use { McapTrack.read(it, channel.channelId) }.fixes
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
     * **A platform's surveyed zero point is not a track.**
     *
     * Two registry entries publish `location_fix`: the phone's live position and a platform's zero point,
     * which is a jetty somebody stood on with a tape measure. Reading both would draw a line from the
     * boat to the shore and call it a run.
     */
    /**
     * **Four channels carry `location_fix` and only one of them is the track.**
     *
     * Beside the phone's fused position and the platform's surveyed zero, a recording now holds the
     * two unfused solutions — `gnss` and `network` — published so what each source was worth can be
     * read off afterwards. Drawing the network one would put a track interpolated from cell towers on
     * the row and call it the boat's, which is the same failure as the zero point by another route.
     *
     * The fused source is deliberately **not** matched by name: it is `Settings.locationSource` and is
     * configurable, so the reader cannot know it. It knows the three fixed ids that are not it.
     *
     * **The decoys are given more fixes than the real track**, which is what makes this bite:
     * `readMcapDetails` returns topics busiest-first, so a version of this test where the fused
     * channel simply had the most messages passed against the unfixed reader and proved nothing. It is
     * also the realistic case — indoors the network solution keeps producing while GNSS is silent, so
     * the wrong channel being the busiest is the normal state rather than a contrivance.
     */
    @Test
    fun `the unfused solutions are not mistaken for the track`() {
        val file = File.createTempFile("track", ".mcap")
        try {
            // Nested under the configured source, which is the shape they actually publish on.
            val gnssOnly = "rise/@v0/pixel_6/pubsub/location_fix/phone/gnss"
            val network = "rise/@v0/pixel_6/pubsub/location_fix/phone/network"
            recording(
                file,
                listOf(
                    network to listOf(
                        fix(58.90, 11.10),
                        fix(58.91, 11.11),
                        fix(58.92, 11.12),
                        fix(58.93, 11.13),
                    ),
                    gnssOnly to listOf(fix(58.80, 11.20), fix(58.81, 11.21), fix(58.82, 11.22)),
                    platformZero to listOf(fix(57.99, 11.99)),
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

    @Test
    fun `a calibration zero point is not mistaken for the track`() {
        val file = File.createTempFile("track", ".mcap")
        try {
            recording(
                file,
                listOf(
                    platformZero to listOf(fix(57.99, 11.99)),
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

    /** A platform zero point on its own is no track at all, rather than a one-point one. */
    @Test
    fun `a recording with only a calibration fix has no track channel`() {
        val file = File.createTempFile("track", ".mcap")
        try {
            recording(file, listOf(platformZero to listOf(fix(57.99, 11.99))))

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

    /**
     * **The shape this app wrote before it chunked: every Message a top-level record.**
     *
     * Not a hypothetical. Measured on the phone: a 479 MB, three-hour recording holding **1 843**
     * `location_fix` messages drew no track at all and said "this recording holds 0", because the walk
     * descended into Chunks and skipped everything else — so a hundred older files on that phone were
     * each reported as a run that never got a fix. The file format's shape was being read as a fact
     * about the day.
     */
    @Test
    fun `a recording written before chunking still yields its track`() {
        val chunked = File.createTempFile("track", ".mcap")
        val flat = File.createTempFile("track-flat", ".mcap")
        try {
            val ids = recording(
                chunked,
                listOf(
                    phoneFix to listOf(fix(57.10, 12.10), fix(57.20, 12.20), fix(57.30, 12.30)),
                    pressure to List(50) { byteArrayOf(9) },
                ),
            )
            unchunk(chunked, flat)

            // The rewrite really did remove them, or this would pass on the chunked path.
            assertEquals(0, chunkCount(flat))
            assertTrue("and the chunked original had some", chunkCount(chunked) > 0)

            val track = flat.inputStream().use { McapTrack.read(it, ids.getValue(phoneFix)) }.fixes
            assertEquals(
                listOf(
                    TrackFix(57.10, 12.10),
                    TrackFix(57.20, 12.20),
                    TrackFix(57.30, 12.30),
                ),
                track,
            )
        } finally {
            chunked.delete()
            flat.delete()
        }
    }

    /** A message on another channel must be stepped over, not misread as a fix. */
    @Test
    fun `an unchunked recording ignores the other channels`() {
        val chunked = File.createTempFile("track", ".mcap")
        val flat = File.createTempFile("track-flat", ".mcap")
        try {
            val ids = recording(
                chunked,
                listOf(
                    pressure to List(200) { byteArrayOf(9, 9, 9) },
                    phoneFix to listOf(fix(57.10, 12.10)),
                    platformZero to listOf(fix(57.99, 11.99)),
                ),
            )
            unchunk(chunked, flat)

            val track = flat.inputStream().use { McapTrack.read(it, ids.getValue(phoneFix)) }.fixes
            assertEquals(listOf(TrackFix(57.10, 12.10)), track)
        } finally {
            chunked.delete()
            flat.delete()
        }
    }

    /**
     * The same recording in the pre-chunking shape: each Chunk replaced by the records it held, every
     * other record copied across untouched.
     *
     * The summary's offsets are left pointing where they did, which is wrong afterwards and does not
     * matter — these tests hand [McapTrack.read] the channel id directly, and that walk streams from the
     * start of the file rather than seeking from the footer.
     */
    private fun unchunk(source: File, into: File) {
        val src = source.readBytes()
        val out = ByteArrayOutputStream()
        out.write(src, 0, MAGIC_SIZE)
        var at = MAGIC_SIZE
        while (at + RECORD_HEADER_SIZE <= src.size) {
            val opcode = src[at].toInt() and 0xFF
            val length = header(src, at).toInt()
            val body = at + RECORD_HEADER_SIZE
            if (opcode == McapWriter.OP_CHUNK) {
                val chunk = ByteBuffer.wrap(src, body, length).order(ByteOrder.LITTLE_ENDIAN)
                chunk.long // message start time
                chunk.long // message end time
                val uncompressed = chunk.long
                chunk.int // uncompressed CRC
                val compression = ByteArray(chunk.int).also { chunk.get(it) }.toString(Charsets.UTF_8)
                val payload = ByteArray(chunk.long.toInt()).also { chunk.get(it) }
                out.write(
                    if (compression == McapWriter.COMPRESSION_ZSTD) {
                        Zstd.decompress(payload, uncompressed.toInt())
                    } else {
                        payload
                    }
                )
            } else {
                out.write(src, at, RECORD_HEADER_SIZE + length)
            }
            at = body + length
        }
        into.writeBytes(out.toByteArray())
    }

    /** How many top-level Chunk records a file holds. */
    private fun chunkCount(file: File): Int {
        val src = file.readBytes()
        var at = MAGIC_SIZE
        var chunks = 0
        while (at + RECORD_HEADER_SIZE <= src.size) {
            if ((src[at].toInt() and 0xFF) == McapWriter.OP_CHUNK) chunks++
            at += RECORD_HEADER_SIZE + header(src, at).toInt()
        }
        return chunks
    }

    /**
     * **A file with no summary still declares its channels**, so the scan can find the fix channel
     * itself rather than the screen inventing an answer.
     *
     * `McapRecovery.finalise` leaves `summary_start = 0` on every recording a killed process
     * interrupted, and there were plenty on the phone this was written against. With no channel list to
     * consult, the caller passes null and the walk watches for the Channel record instead. Before this,
     * a 3 MB rescued recording said "GNSS was not publishing while this ran" — a statement about
     * somebody's day drawn from nothing but a missing footer.
     */
    @Test
    fun `a scan with no channel list discovers the fix channel itself`() {
        val file = File.createTempFile("track", ".mcap")
        try {
            recording(
                file,
                listOf(
                    pressure to List(20) { byteArrayOf(9) },
                    phoneFix to listOf(fix(57.10, 12.10), fix(57.20, 12.20)),
                ),
            )

            val scan = file.inputStream().use { McapTrack.read(it, null) }

            assertTrue("the channel was found without a summary", scan.channelFound)
            assertFalse(scan.stoppedEarly)
            assertEquals(listOf(TrackFix(57.10, 12.10), TrackFix(57.20, 12.20)), scan.fixes)
        } finally {
            file.delete()
        }
    }

    /** Discovery applies the same rule as the summary path: a platform's zero point is not the track. */
    @Test
    fun `discovery skips the calibration channel`() {
        val file = File.createTempFile("track", ".mcap")
        try {
            recording(file, listOf(platformZero to listOf(fix(57.99, 11.99))))

            val scan = file.inputStream().use { McapTrack.read(it, null) }

            assertFalse("a zero point is not a fix channel", scan.channelFound)
            assertTrue(scan.fixes.isEmpty())
        } finally {
            file.delete()
        }
    }

    /**
     * **"No fix channel" and "could not tell" must stay apart.** A run without GNSS reports
     * `channelFound = false` having read the whole file; a truncated one reports `stoppedEarly` as well,
     * and the screen says it does not know rather than making a claim about the run.
     */
    @Test
    fun `a truncated file is not reported as a run without GNSS`() {
        val file = File.createTempFile("track", ".mcap")
        val cut = File.createTempFile("track-cut", ".mcap")
        try {
            recording(file, listOf(pressure to List(20) { byteArrayOf(9) }))
            val whole = file.readBytes()

            val complete = file.inputStream().use { McapTrack.read(it, null) }
            assertFalse(complete.channelFound)
            assertFalse("a whole file finishes", complete.stoppedEarly)

            // Cut mid-record, which is what a killed process leaves.
            cut.writeBytes(whole.copyOfRange(0, whole.size / 2 + 3))
            val partial = cut.inputStream().use { McapTrack.read(it, null) }
            assertTrue("a cut file admits it stopped", partial.stoppedEarly)
        } finally {
            file.delete()
            cut.delete()
        }
    }

    private fun header(src: ByteArray, at: Int): Long =
        ByteBuffer.wrap(src, at + 1, 8).order(ByteOrder.LITTLE_ENDIAN).long

    // ---- the reader's own cap ---------------------------------------------------------------------

    /**
     * **A track longer than the reader will read says so.**
     *
     * `McapTrack` stops at ten times the points it keeps rather than decompressing half a gigabyte to
     * throw most of it away, which is the right trade — but a twelve-hour passage drawn as its first
     * five hours with nothing saying so is a chart that lies about where the boat went. The scan now
     * records that it stopped at its own limit, and the detail screen's footer says "the start of a
     * longer run" instead of nothing.
     *
     * Driven through `maxPoints` rather than by writing twenty thousand fixes: the cap is
     * `maxPoints * OVERSAMPLE`, so a small limit exercises the same branch for the price of a few
     * dozen messages. Reaching the real one would mean a fixture the size of a real voyage.
     */
    @Test
    fun `a track past the reader's cap is marked truncated`() {
        val file = File.createTempFile("capped", ".mcap")
        val fixes = (1..60).map { fix(57.0 + it * 0.001, 12.0) }
        val channels = recording(file, listOf(phoneFix to fixes))

        // maxPoints 2 caps the walk at 20 fixes, well short of the 60 in the file.
        val scan = file.inputStream().use {
            McapTrack.read(it, channels.getValue(phoneFix), maxPoints = 2)
        }

        assertTrue("it stopped at its own limit", scan.truncated)
        // **Not the failure flag**, which is the whole point of keeping them apart: nothing went wrong
        // here, and a screen saying "reading stopped early" would report a fault where there was a
        // budget.
        assertFalse("nothing went wrong", scan.stoppedEarly)
        assertTrue("and it found the channel", scan.channelFound)
        file.delete()
    }

    /** A track that fits says nothing, or every recording on the phone would claim to be a fragment. */
    @Test
    fun `a track inside the cap is not marked truncated`() {
        val file = File.createTempFile("whole", ".mcap")
        val fixes = (1..10).map { fix(57.0 + it * 0.001, 12.0) }
        val channels = recording(file, listOf(phoneFix to fixes))

        val scan = file.inputStream().use {
            McapTrack.read(it, channels.getValue(phoneFix), maxPoints = 2_000)
        }

        assertFalse(scan.truncated)
        assertFalse(scan.stoppedEarly)
        assertEquals(10, scan.fixes.size)
        file.delete()
    }
}

package se.rise.logline.record

import android.util.Log
import com.github.luben.zstd.Zstd
import foxglove.LocationFixOuterClass.LocationFix
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "McapTrack"

/** One position out of a recording. Degrees, and nothing else — this draws a shape, not a chart. */
data class TrackFix(val latitude: Double, val longitude: Double)

/**
 * The GNSS track of a finished recording, for the picture that makes a run recognisable.
 *
 * **This is the expensive read in this package, and it is expensive by design of the file.**
 * `McapWriter` writes no ChunkIndex — "readers scan rather than seek", stated where that decision was
 * taken — so the only way to reach the fixes is to decompress every chunk. That is why nothing calls
 * this for a list row: it runs once, when somebody opens a recording.
 *
 * Everything else about a recording comes off the footer for the price of a few seeks; see
 * [readMcapDetails].
 */
object McapTrack {

    /**
     * How many points survive. The chart is a few hundred pixels wide, and an hour at 1 Hz is 3 600
     * fixes — far more on a run recorded at full rate.
     */
    const val MAX_POINTS = 2_000

    /**
     * Which channel holds the phone's own fixes, out of a recording's topics.
     *
     * **Two registry entries publish `location_fix`** — the phone's live position on
     * `{realm}/@v0/{entity}/pubsub/location_fix/{source}` and a rig's *surveyed zero point* on
     * `.../location_fix/calibration`. They are different things at different places: the zero point is
     * a jetty somebody stood on with a tape measure, and including it would draw a line from the boat
     * to the shore and call it a track.
     *
     * The source is the key's last chunk, which is what this matches on. Returns null when the
     * recording has no fix channel at all — an IMU-only run — and that answer is what saves the scan.
     */
    fun fixChannel(topics: List<TopicCount>): TopicCount? = topics.firstOrNull {
        val parts = it.topic.split('/')
        parts.size >= 2 &&
            parts[parts.size - 2] == "location_fix" &&
            parts.last() != CALIBRATION_SOURCE
    }

    /**
     * Read the track, or an empty list when the recording holds no fixes.
     *
     * The stream is read once, forwards — a `content://` URI gives an `InputStream` and nothing here
     * needs to seek. Records that cannot be parsed end the walk rather than being skipped: a malformed
     * length means the position in the file is no longer trustworthy, and guessing past it is how a
     * reader invents data.
     */
    fun read(stream: InputStream, channelId: Int, maxPoints: Int = MAX_POINTS): List<TrackFix> {
        val out = mutableListOf<TrackFix>()
        try {
            stream.buffered().use { input ->
                // The opening magic, which nothing here checks beyond stepping over it: a file that got
                // this far came from the library's own listing.
                if (input.skipFully(MAGIC_SIZE.toLong()) < MAGIC_SIZE.toLong()) return emptyList()
                while (true) {
                    val header = input.readOrNull(RECORD_HEADER_SIZE) ?: break
                    val opcode = header.get().toInt() and 0xFF
                    val length = header.long
                    if (length < 0) break
                    if (opcode == McapWriter.OP_CHUNK) {
                        val body = input.readOrNull(length.toInt()) ?: break
                        readChunk(body, channelId, out)
                    } else {
                        if (input.skipFully(length) < length) break
                    }
                    // Stop early rather than reading a 512 MB file to throw most of it away. The cap is
                    // generous enough that this is the rare case, and it bounds the worst one.
                    if (out.size >= maxPoints * OVERSAMPLE) break
                }
            }
        } catch (t: Throwable) {
            // A partial track is better than none: what has been read is real, and the caller says how
            // many points it got.
            Log.i(TAG, "track read stopped early", t)
        }
        return downsample(out, maxPoints)
    }

    /** Decompress one chunk and take the fixes out of it. */
    private fun readChunk(chunk: ByteBuffer, channelId: Int, out: MutableList<TrackFix>) {
        chunk.long // message start time
        chunk.long // message end time
        val uncompressed = chunk.long
        chunk.int // uncompressed CRC
        val compressionLength = chunk.int
        if (compressionLength < 0 || compressionLength > chunk.remaining()) return
        val compression = ByteArray(compressionLength).also { chunk.get(it) }.toString(Charsets.UTF_8)
        if (chunk.remaining() < 8) return
        val payloadLength = chunk.long
        if (payloadLength < 0 || payloadLength > chunk.remaining()) return
        val payload = ByteArray(payloadLength.toInt()).also { chunk.get(it) }

        val raw = when (compression) {
            McapWriter.COMPRESSION_ZSTD -> {
                if (uncompressed <= 0L || uncompressed > MAX_CHUNK_BYTES) return
                Zstd.decompress(payload, uncompressed.toInt())
            }
            // The spec allows an uncompressed chunk and this app has written them before.
            "" -> payload
            else -> return
        }

        val records = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        while (records.remaining() >= RECORD_HEADER_SIZE) {
            val opcode = records.get().toInt() and 0xFF
            val length = records.long
            if (length < 0 || length > records.remaining()) return
            val end = records.position() + length.toInt()
            if (opcode == McapWriter.OP_MESSAGE) {
                val id = records.short.toInt() and 0xFFFF
                if (id == channelId) {
                    records.int // sequence
                    records.long // log time
                    records.long // publish time
                    // The rest of the record *is* the payload — a Message's data carries no length of
                    // its own, unlike a Schema's. Reading a length here is the bug that produces a file
                    // which parses and whose payloads all fail to decode.
                    val data = ByteArray(end - records.position()).also { records.get(it) }
                    fixOf(data)?.let { out.add(it) }
                }
            }
            records.position(end)
        }
    }

    /**
     * A `foxglove.LocationFix` out of one message's bytes.
     *
     * Through the generated lite binding rather than by hand: the app publishes these with the same
     * class, so a field-number slip cannot make the reader and the writer disagree.
     *
     * `0, 0` is dropped. proto3 cannot tell an absent double from zero, so a fix that carried no
     * position decodes as the Gulf of Guinea — and one stray point there rescales the whole chart to the
     * Atlantic.
     */
    private fun fixOf(data: ByteArray): TrackFix? = try {
        val fix = LocationFix.parseFrom(data)
        if (fix.latitude == 0.0 && fix.longitude == 0.0) null
        else TrackFix(fix.latitude, fix.longitude)
    } catch (t: Throwable) {
        null
    }

    /**
     * Thin to at most [maxPoints], **keeping the first and last**.
     *
     * The ends are where the run began and finished, which is most of what makes a track recognisable —
     * a plain stride would drop whichever of them the arithmetic happened to miss.
     */
    internal fun downsample(points: List<TrackFix>, maxPoints: Int): List<TrackFix> {
        if (maxPoints < 2 || points.size <= maxPoints) return points
        val step = (points.size - 1).toDouble() / (maxPoints - 1)
        return (0 until maxPoints).map { points[Math.round(it * step).toInt().coerceIn(points.indices)] }
    }

    /**
     * How far past the cap to read before giving up.
     *
     * Downsampling wants more points than it keeps or the thinning has nothing to thin, but reading
     * every fix of a twelve-hour run to discard 99% of them is work nobody sees. Ten times the cap is
     * twenty thousand fixes — five hours at 1 Hz — and past that the shape is not going to change.
     */
    private const val OVERSAMPLE = 10

    /** A guard against a corrupt length turning into a huge allocation. 256 MB of one chunk is absurd. */
    private const val MAX_CHUNK_BYTES = 256L * 1024 * 1024
}

/** The source id a rig's surveyed zero point publishes under. See [McapTrack.fixChannel]. */
private const val CALIBRATION_SOURCE = "calibration"

private fun InputStream.readOrNull(count: Int): ByteBuffer? {
    if (count < 0) return null
    val bytes = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = read(bytes, read, count - read)
        if (n < 0) return null
        read += n
    }
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}

/** `InputStream.skip` may stop short for reasons of its own; this keeps asking. */
private fun InputStream.skipFully(count: Long): Long {
    var left = count
    while (left > 0) {
        val n = skip(left)
        if (n <= 0) {
            // A stream that will not skip still reads: fall back rather than spinning.
            if (read() < 0) break
            left--
        } else {
            left -= n
        }
    }
    return count - left
}

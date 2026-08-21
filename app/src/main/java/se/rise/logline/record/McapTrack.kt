package se.rise.logline.record

import android.util.Log
import com.github.luben.zstd.Zstd
import foxglove.LocationFixOuterClass.LocationFix
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "McapTrack"

/** One position out of a recording. Degrees, and nothing else — this draws a shape, not a chart. */
data class TrackFix(val latitude: Double, val longitude: Double)

/**
 * What a scan established, rather than only what it found.
 *
 * An empty list has three quite different causes and the screen must not merge them: the recording has
 * no fix channel (an IMU-only run), it has one that yielded nothing, or the read failed before it could
 * say. Reporting either of the last two as the first puts a claim about somebody's day on screen that
 * nothing in the file supports.
 */
data class TrackScan(
    /** Whether a fix channel was ever identified. False with [stoppedEarly] means "cannot tell". */
    val channelFound: Boolean,
    val fixes: List<TrackFix>,
    /** The walk ended on a malformed record or an exception, so [fixes] may not be the whole track. */
    val stoppedEarly: Boolean = false,
)

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
    fun fixChannel(topics: List<TopicCount>): TopicCount? = topics.firstOrNull { isFixTopic(it.topic) }

    /** The same judgement against a bare topic, for a scan with no channel list to consult. */
    internal fun isFixTopic(topic: String): Boolean {
        val parts = topic.split('/')
        return parts.size >= 2 &&
            parts[parts.size - 2] == "location_fix" &&
            parts.last() != CALIBRATION_SOURCE
    }

    /**
     * Read the track, or an empty list when the recording holds no fixes.
     *
     * **Both file shapes are read**: messages inside zstd Chunks, which is what this app writes now, and
     * messages as top-level records, which is what it wrote before chunking landed. `readChunk` already
     * carried the same courtesy for an uncompressed chunk; the unchunked *file* was the case missed.
     *
     * The stream is read once, forwards — a `content://` URI gives an `InputStream` and nothing here
     * needs to seek. Records that cannot be parsed end the walk rather than being skipped: a malformed
     * length means the position in the file is no longer trustworthy, and guessing past it is how a
     * reader invents data.
     */
    fun read(stream: InputStream, channelId: Int?, maxPoints: Int = MAX_POINTS): TrackScan {
        val out = mutableListOf<TrackFix>()
        // Null means the caller had no channel list to consult — a recording with no summary section,
        // which `McapRecovery.finalise` leaves behind for every run a killed process interrupted. The
        // file still declares its channels inline, ahead of the messages, so the scan finds the fix
        // channel itself. Treating a missing summary as evidence about GNSS is how "no summary" came to
        // read as "GNSS was not publishing while this ran".
        var target = channelId
        var stoppedEarly = false
        try {
            stream.buffered().use { input ->
                // The opening magic, which nothing here checks beyond stepping over it: a file that got
                // this far came from the library's own listing.
                input.skipExactly(MAGIC_SIZE.toLong())
                while (true) {
                    val header = input.readExactly(RECORD_HEADER_SIZE)
                    val opcode = header.get().toInt() and 0xFF
                    val length = header.long
                    if (length < 0) throw EOFException("record length $length")
                    // **Everything past the data section is summary** — schemas, channels, statistics,
                    // the footer and the closing magic. No message lives there, so this is the end of
                    // the walk. It is also what makes a short read below unambiguous: a well-formed
                    // file always reaches this record, so running out of bytes before it means the
                    // recording was cut off mid-record, which is what a killed process leaves.
                    if (opcode == McapWriter.OP_DATA_END || opcode == McapWriter.OP_FOOTER) break

                    if (opcode == McapWriter.OP_CHANNEL && target == null) {
                        // Channels are declared outside any chunk and ahead of the messages that use
                        // them, in both file shapes — in `McapWriter` only messages accumulate into a
                        // chunk. So by the first message the target is known.
                        input.readExactly(length.toInt()).readChannel()
                            ?.let { (id, topic) -> if (isFixTopic(topic)) target = id }
                    } else if (opcode == McapWriter.OP_CHUNK) {
                        readChunk(input.readExactly(length.toInt()), target ?: NO_CHANNEL, out)
                    } else if (opcode == McapWriter.OP_MESSAGE) {
                        // **A Message at the top level, outside any Chunk.** Everything this app writes
                        // today is chunked, and everything it wrote before that change is not — a
                        // hundred such files were still on the phone this was measured on. Skipping
                        // them here had a three-hour recording holding 1 843 fixes draw no track and
                        // report "this recording holds 0", which is the file's shape being read as a
                        // fact about the run.
                        if (length < MESSAGE_PREFIX_SIZE) {
                            input.skipExactly(length)
                        } else {
                            // The channel id leads the record, so a message on any other channel costs
                            // the prefix and a skip rather than a copy of its payload — and on these
                            // files all but one channel in forty-six is another channel.
                            val head = input.readExactly(MESSAGE_PREFIX_SIZE)
                            val id = head.short.toInt() and 0xFFFF
                            val rest = length - MESSAGE_PREFIX_SIZE
                            if (id == target) {
                                val body = input.readExactly(rest.toInt())
                                val data = ByteArray(body.remaining()).also { body.get(it) }
                                fixOf(data)?.let { out.add(it) }
                            } else {
                                input.skipExactly(rest)
                            }
                        }
                    } else {
                        input.skipExactly(length)
                    }
                    // Stop early rather than reading a 512 MB file to throw most of it away. The cap is
                    // generous enough that this is the rare case, and it bounds the worst one.
                    if (out.size >= maxPoints * OVERSAMPLE) break
                }
            }
        } catch (t: Throwable) {
            // A partial track is better than none: what has been read is real. It is reported as
            // partial rather than as the whole, because "1 843 positions" and "1 843 positions so far"
            // are different statements and only one of them would be true here.
            Log.i(TAG, "track read stopped early", t)
            stoppedEarly = true
        }
        return TrackScan(
            channelFound = target != null,
            fixes = downsample(out, maxPoints),
            stoppedEarly = stoppedEarly,
        )
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

    /**
     * What a Message record carries before its payload: channel id (2), sequence (4), log time (8) and
     * publish time (8). The payload is simply the rest of the record — it carries no length of its own,
     * unlike a Schema's, which is the first bug this writer and reader ever had between them.
     */
    private const val MESSAGE_PREFIX_SIZE = 22

    /** No uint16 channel id can be this, so nothing matches while the target is still unknown. */
    private const val NO_CHANNEL = -1
}

/** The source id a rig's surveyed zero point publishes under. See [McapTrack.fixChannel]. */
private const val CALIBRATION_SOURCE = "calibration"

/**
 * Reads exactly [count] bytes.
 *
 * Throws rather than answering null, because past the opening magic and before DataEnd there is no
 * such thing as a clean end: a stream that stops there was cut off mid-record. The walk's one catch
 * then records it as a partial read, which is the only honest thing to call it.
 */
private fun InputStream.readExactly(count: Int): ByteBuffer {
    if (count < 0) throw EOFException("negative record length $count")
    val bytes = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = read(bytes, read, count - read)
        if (n < 0) throw EOFException("wanted $count bytes, got $read")
        read += n
    }
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}

/** `InputStream.skip` may stop short for reasons of its own; this keeps asking, and throws at the end. */
private fun InputStream.skipExactly(count: Long) {
    var left = count
    while (left > 0) {
        val n = skip(left)
        if (n <= 0) {
            // A stream that will not skip still reads: fall back rather than spinning.
            if (read() < 0) throw EOFException("wanted to skip $count bytes, $left short")
            left--
        } else {
            left -= n
        }
    }
}

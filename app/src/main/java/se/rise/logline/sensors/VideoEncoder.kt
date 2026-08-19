package se.rise.logline.sensors

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

private const val TAG = "VideoEncoder"

/** H.264 Annex B start code. Every NAL unit MediaCodec emits is preceded by one. */
private val START_CODE = byteArrayOf(0, 0, 0, 1)

/**
 * One encoded frame, ready to become a `foxglove.CompressedVideo`.
 *
 * [presentationTimeUs] is the encoder's own clock for the frame, carried through from the camera
 * surface — see [VideoEncoder] for why it is checked rather than trusted.
 */
data class EncodedFrame(
    val bytes: ByteArray,
    val keyframe: Boolean,
    val presentationTimeUs: Long,
) {
    // A data class holding a ByteArray needs these by hand, or equality compares references.
    override fun equals(other: Any?): Boolean =
        this === other || (other is EncodedFrame &&
            bytes.contentEquals(other.bytes) &&
            keyframe == other.keyframe &&
            presentationTimeUs == other.presentationTimeUs)

    override fun hashCode(): Int =
        (bytes.contentHashCode() * 31 + keyframe.hashCode()) * 31 + presentationTimeUs.hashCode()
}

/**
 * Put the codec configuration in front of a keyframe, and leave every other frame alone.
 *
 * **This is the one rule that makes the stream decodable by anybody who was not listening at the
 * start.** `foxglove.CompressedVideo` requires that "each message containing a key frame (IDR) must
 * also include a SPS NAL unit", and `MediaCodec` does the opposite by default: it emits SPS and PPS
 * exactly once, in a `BUFFER_FLAG_CODEC_CONFIG` buffer ahead of the first frame, and never again. A
 * subscriber joining a minute later — or a recording that rotated at 512 MB — would then hold a
 * bitstream no decoder can start on, which looks like a black panel rather than an error.
 *
 * `MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES` asks the encoder to do this instead, and is
 * deliberately not used: the platform documentation says an encoder that does not support it **fails
 * to configure**, so it trades a few bytes per keyframe for a device-dependent crash at start-up.
 * Prepending here is one path that behaves the same on every phone.
 *
 * Pure, so the rule is testable without a codec.
 */
fun withCodecConfig(config: ByteArray?, frame: EncodedFrame): EncodedFrame =
    if (!frame.keyframe || config == null || config.isEmpty()) {
        frame
    } else {
        frame.copy(bytes = config + frame.bytes)
    }

/** True when [bytes] starts with an Annex B start code — what the proto means by "Annex B format". */
fun isAnnexB(bytes: ByteArray): Boolean =
    bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
        ((bytes[2] == 0.toByte() && bytes[3] == 1.toByte()) || bytes[2] == 1.toByte())

/**
 * The NAL unit types present in an Annex B buffer, in order.
 *
 * Only used by tests and by the once-per-run sanity log: 7 is SPS, 8 is PPS, 5 is an IDR slice. A
 * keyframe message that carries 5 without a preceding 7 is exactly the bug [withCodecConfig] exists to
 * prevent, and it is invisible on the phone.
 */
fun nalUnitTypes(bytes: ByteArray): List<Int> = buildList {
    var i = 0
    while (i + 3 < bytes.size) {
        val three = bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte()
        val four = three.not() && bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() &&
            bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()
        if (three || four) {
            val header = i + if (three) 3 else 4
            if (header < bytes.size) add(bytes[header].toInt() and 0x1F)
            i = header + 1
        } else {
            i++
        }
    }
}

/**
 * H.264 out of `MediaCodec`, fed by a camera through a `Surface`.
 *
 * Surface input rather than byte buffers: the frames go camera → encoder without being copied through
 * the CPU, which is what makes continuous video affordable on a phone that is also publishing ~217
 * samples a second.
 *
 * Not a `callbackFlow` like the other sensors, because it is not a source on its own — it is half of
 * the camera collector, and `CameraProvider` drives its lifetime.
 */
class VideoEncoder(
    val width: Int,
    val height: Int,
    private val bitrateKbps: Int,
    private val frameRate: Int,
    private val keyframeSeconds: Int,
) {
    private var codec: MediaCodec? = null
    private var codecConfig: ByteArray? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var checkedFraming = false

    /** The surface the camera writes into. Valid only between [start] and [stop]. */
    var inputSurface: Surface? = null
        private set

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeSeconds)
            // Foxglove cannot decode streams with B frames — they need lookahead — and the proto says
            // so outright. The platform default is 0; setting it makes that a decision rather than a
            // default somebody could change underneath us.
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()
        codec = encoder
        Log.i(TAG, "encoding ${width}x$height @ ${frameRate}fps, ${bitrateKbps} kbps, keyframe every ${keyframeSeconds}s")
    }

    /**
     * Drain whatever the encoder has ready, oldest first.
     *
     * Returns an empty list when nothing is available — the caller polls rather than blocking, so a
     * quiet encoder never holds the collector's coroutine.
     */
    fun drain(timeoutUs: Long = 0L): List<EncodedFrame> {
        val encoder = codec ?: return emptyList()
        val out = mutableListOf<EncodedFrame>()
        while (true) {
            val index = try {
                encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            } catch (t: Throwable) {
                Log.w(TAG, "dequeue failed", t)
                return out
            }
            if (index < 0) return out
            val buffer: ByteBuffer? = encoder.getOutputBuffer(index)
            if (buffer != null && bufferInfo.size > 0) {
                buffer.position(bufferInfo.offset)
                buffer.limit(bufferInfo.offset + bufferInfo.size)
                val bytes = ByteArray(bufferInfo.size).also { buffer.get(it) }
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    // SPS and PPS, delivered once. Kept, not published: on its own it decodes to
                    // nothing, and it belongs in front of every keyframe instead.
                    codecConfig = bytes
                    Log.i(TAG, "codec config ${bytes.size} bytes, NAL types ${nalUnitTypes(bytes)}")
                } else {
                    val keyframe = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    val frame = withCodecConfig(
                        codecConfig,
                        EncodedFrame(bytes, keyframe, bufferInfo.presentationTimeUs),
                    )
                    if (!checkedFraming && keyframe) {
                        checkedFraming = true
                        // Once per run, because the cost of getting this wrong is a stream that looks
                        // fine on the phone and decodes nowhere else.
                        Log.i(
                            TAG,
                            "first keyframe: ${frame.bytes.size} bytes, annexB=${isAnnexB(frame.bytes)}, " +
                                "NAL types ${nalUnitTypes(frame.bytes)}",
                        )
                    }
                    out += frame
                }
            }
            encoder.releaseOutputBuffer(index, false)
        }
    }

    /**
     * Idempotent, because two owners can reach it: the surface-release callback CameraX invokes, and
     * the collector's own teardown. Stopping a released codec throws, and a half-released encoder
     * handed back to the camera on the next run is how a stale surface reaches the HAL.
     */
    @Synchronized
    fun stop() {
        val encoder = codec ?: return
        val surface = inputSurface
        codec = null
        inputSurface = null
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        // After the codec, not before: the surface belongs to it, and releasing it first leaves the
        // codec holding a dead buffer queue.
        runCatching { surface?.release() }
    }
}

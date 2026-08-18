package se.rise.logline.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

private const val TAG = "AudioProvider"

/** One chunk of captured sound, and the boot-clock instant its first sample was taken. */
data class AudioChunk(
    val pcm: ByteArray,
    val startElapsedNanos: Long,
    val sampleRateHz: Int,
    val channels: Int,
) {
    // Data classes compare arrays by reference; content equality is what a test would want to assert.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false
        return startElapsedNanos == other.startElapsedNanos &&
            sampleRateHz == other.sampleRateHz &&
            channels == other.channels &&
            pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int =
        ((pcm.contentHashCode() * 31 + startElapsedNanos.hashCode()) * 31 + sampleRateHz) * 31 + channels
}

/**
 * The microphone, as a flow of fixed-length PCM chunks.
 *
 * Same contract as the other providers — the hardware is acquired on collection and released in the
 * `finally`, which is what stops the microphone (and its privacy indicator) from staying live after a
 * run ends.
 */
class AudioProvider(private val context: Context) {

    /**
     * True when this device can actually capture at these settings.
     *
     * Only 44.1 kHz is guaranteed by the platform; everything else is per-device, and the way to find
     * out is to ask rather than to try and fail at `start()`.
     */
    fun supports(sampleRateHz: Int, channels: Int): Boolean =
        AudioRecord.getMinBufferSize(sampleRateHz, channelMask(channels), AudioFormat.ENCODING_PCM_16BIT)
            .let { it != AudioRecord.ERROR && it != AudioRecord.ERROR_BAD_VALUE && it > 0 }

    /**
     * @param chunkMillis how much sound each emission carries. It is the publish period: at the default
     *   one second, `audio` publishes at 1 Hz.
     *
     * Requires `RECORD_AUDIO`; the caller checks that, as `runLocation` does for its own permission.
     */
    @SuppressLint("MissingPermission")
    fun chunks(sampleRateHz: Int, channels: Int, chunkMillis: Int): Flow<AudioChunk> = flow {
        val mask = channelMask(channels)
        val minBuffer = AudioRecord.getMinBufferSize(sampleRateHz, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            Log.w(TAG, "no capture at ${sampleRateHz}Hz x$channels on this device; audio will not publish")
            return@flow
        }

        val bytesPerFrame = channels * 2
        val chunkBytes = (sampleRateHz * chunkMillis / 1000) * bytesPerFrame
        val source = preferredSource()
        // Several chunks of slack so a scheduling hiccup drops nothing: the hardware keeps filling this
        // while the collector is busy publishing the previous chunk.
        val bufferBytes = maxOf(minBuffer * 2, chunkBytes * 3)

        val record = AudioRecord(source, sampleRateHz, mask, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord would not initialise (source $source); audio will not publish")
            record.release()
            return@flow
        }

        Log.i(TAG, "capturing ${sampleRateHz}Hz x$channels from source $source, ${chunkMillis}ms chunks")
        try {
            record.startRecording()
            val chunk = ByteArray(chunkBytes)
            var framesEmitted = 0L
            var anchorNanos = 0L

            while (currentCoroutineContext().isActive) {
                var filled = 0
                while (filled < chunkBytes) {
                    val read = record.read(chunk, filled, chunkBytes - filled)
                    if (read <= 0) {
                        Log.w(TAG, "capture read returned $read; stopping")
                        return@flow
                    }
                    if (anchorNanos == 0L) {
                        // Anchor on the first bytes to arrive, backdated by their own duration, so the
                        // very first sample gets the instant it was taken rather than the instant the
                        // buffer came back.
                        val framesRead = read / bytesPerFrame
                        anchorNanos = android.os.SystemClock.elapsedRealtimeNanos() -
                            framesRead * 1_000_000_000L / sampleRateHz
                    }
                    filled += read
                }

                // Timed by the sample clock, not by when this loop happened to run. Scheduling jitter
                // would otherwise show up as chunks that claim to be 940 ms or 1.1 s apart, and the
                // samples themselves are the only honest clock a recording has.
                val startNanos = anchorNanos + framesEmitted * 1_000_000_000L / sampleRateHz
                framesEmitted += chunkBytes / bytesPerFrame
                emit(AudioChunk(chunk.copyOf(), startNanos, sampleRateHz, channels))
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * The least-processed source this device offers.
     *
     * `MIC` runs automatic gain control and noise suppression, which is right for a voice memo and
     * wrong for a log: it rewrites exactly the machinery noise someone is logging in order to hear.
     * `UNPROCESSED` is raw where the device advertises it, and `VOICE_RECOGNITION` has AGC and noise
     * suppression off on most devices.
     */
    private fun preferredSource(): Int {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val unprocessed = manager
            ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            ?.equals("true", ignoreCase = true) == true
        return when {
            unprocessed -> MediaRecorder.AudioSource.UNPROCESSED
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    private fun channelMask(channels: Int) =
        if (channels >= 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
}

package se.rise.logline.sensors

/**
 * Wrap raw PCM in a canonical 44-byte RIFF/WAVE header.
 *
 * `keelson.Audio` carries `bytes data` and an encoding of MP3 or WAV, and **nothing else about the
 * format** — no sample rate, no channel count, no bit depth. WAV covers that gap exactly: the header in
 * front of the samples states all three, so every chunk on the bus is self-describing and a consumer
 * can play one without knowing how the publisher was configured.
 *
 * Each chunk is a complete little file rather than a fragment of a stream. That costs 44 bytes per
 * chunk — 0.14% at one second of 16 kHz mono — and buys a consumer that can decode any single message
 * it happens to receive, which matters on a `transient` subject where messages are droppable.
 *
 * Little-endian throughout, because RIFF is; the one big-endian value in the format is the `RIFF` tag
 * itself, which is ASCII.
 */
object WavChunk {

    /** Header length for canonical PCM WAV: 12-byte RIFF chunk, 24-byte `fmt `, 8-byte `data` header. */
    const val HEADER_BYTES = 44

    private const val PCM_FORMAT = 1.toShort()
    private const val BITS_PER_SAMPLE = 16

    /**
     * @param pcm 16-bit signed little-endian samples, interleaved when [channels] is more than one —
     *   which is exactly what `AudioRecord` writes with `ENCODING_PCM_16BIT`, so no conversion happens
     *   here and none should.
     */
    fun wrap(pcm: ByteArray, sampleRateHz: Int, channels: Int): ByteArray {
        require(sampleRateHz > 0) { "sample rate must be positive, was $sampleRateHz" }
        require(channels > 0) { "channel count must be positive, was $channels" }

        val bytesPerFrame = channels * BITS_PER_SAMPLE / 8
        val out = ByteArray(HEADER_BYTES + pcm.size)
        var at = 0

        fun ascii(tag: String) {
            tag.forEach { out[at++] = it.code.toByte() }
        }
        fun int32(value: Int) {
            out[at++] = value.toByte()
            out[at++] = (value ushr 8).toByte()
            out[at++] = (value ushr 16).toByte()
            out[at++] = (value ushr 24).toByte()
        }
        fun int16(value: Short) {
            val v = value.toInt()
            out[at++] = v.toByte()
            out[at++] = (v ushr 8).toByte()
        }

        ascii("RIFF")
        // Everything after this field: the 4-byte "WAVE" tag, the 24-byte fmt chunk, the 8-byte data
        // header, and the samples.
        int32(36 + pcm.size)
        ascii("WAVE")

        ascii("fmt ")
        int32(16)                                   // PCM fmt chunks are 16 bytes
        int16(PCM_FORMAT)
        int16(channels.toShort())
        int32(sampleRateHz)
        int32(sampleRateHz * bytesPerFrame)         // byte rate
        int16(bytesPerFrame.toShort())              // block align
        int16(BITS_PER_SAMPLE.toShort())

        ascii("data")
        int32(pcm.size)
        pcm.copyInto(out, at)
        return out
    }

    /**
     * How loud a chunk is, in dBFS, or null when there is nothing to measure.
     *
     * For the live view's level meter only — it is never published. Full scale is 0 dB and silence is
     * floored rather than allowed to reach negative infinity, because a level meter that occasionally
     * reports `-Inf` cannot be plotted.
     */
    fun levelDbfs(pcm: ByteArray, floorDb: Float = -96f): Float? {
        if (pcm.size < 2) return null
        var sumSquares = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < pcm.size) {
            // Signed 16-bit little-endian, sign-extended through Short.
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            sumSquares += sample.toDouble() * sample
            samples++
            i += 2
        }
        if (samples == 0) return null
        val rms = kotlin.math.sqrt(sumSquares / samples)
        if (rms <= 0.0) return floorDb
        val db = (20.0 * kotlin.math.log10(rms / Short.MAX_VALUE.toDouble())).toFloat()
        return db.coerceAtLeast(floorDb)
    }
}

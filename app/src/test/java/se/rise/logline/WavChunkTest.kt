package se.rise.logline

import se.rise.logline.sensors.WavChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `keelson.Audio` says only "these bytes are WAV" — no rate, no channels, no depth — so the header is
 * the only thing telling a consumer how to play a chunk. A field at the wrong offset produces bytes
 * that look like audio and decode to noise, which is the failure this pins down.
 *
 * Offsets are taken from the canonical PCM layout, not from the implementation.
 */
class WavChunkTest {

    private fun le32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun le16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun ascii(bytes: ByteArray, at: Int, length: Int) =
        String(bytes, at, length, Charsets.US_ASCII)

    @Test
    fun `the header is the canonical 44 bytes and the samples follow it untouched`() {
        val pcm = ByteArray(320) { (it % 251).toByte() }

        val wav = WavChunk.wrap(pcm, sampleRateHz = 16_000, channels = 1)

        assertEquals(WavChunk.HEADER_BYTES + pcm.size, wav.size)
        assertTrue(
            "the PCM must be copied through byte for byte",
            wav.copyOfRange(WavChunk.HEADER_BYTES, wav.size).contentEquals(pcm),
        )
    }

    @Test
    fun `the chunk tags sit where a reader looks for them`() {
        val wav = WavChunk.wrap(ByteArray(64), 16_000, 1)

        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals("WAVE", ascii(wav, 8, 4))
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals("data", ascii(wav, 36, 4))
    }

    @Test
    fun `the format block describes what was actually captured`() {
        val wav = WavChunk.wrap(ByteArray(400), sampleRateHz = 44_100, channels = 2)

        assertEquals("fmt chunk size", 16, le32(wav, 16))
        assertEquals("PCM", 1, le16(wav, 20))
        assertEquals("channels", 2, le16(wav, 22))
        assertEquals("sample rate", 44_100, le32(wav, 24))
        // Stereo 16-bit: four bytes per frame.
        assertEquals("byte rate", 44_100 * 4, le32(wav, 28))
        assertEquals("block align", 4, le16(wav, 32))
        assertEquals("bits per sample", 16, le16(wav, 34))
    }

    /** Both size fields are relative to different points, and getting either wrong truncates playback. */
    @Test
    fun `the two size fields agree with the payload`() {
        val pcm = ByteArray(1_000)

        val wav = WavChunk.wrap(pcm, 16_000, 1)

        assertEquals("data size is the PCM length", pcm.size, le32(wav, 40))
        assertEquals("RIFF size counts everything after itself", 36 + pcm.size, le32(wav, 4))
    }

    @Test
    fun `mono at 16 kHz has the byte rate a second of audio implies`() {
        val wav = WavChunk.wrap(ByteArray(0), 16_000, 1)

        // 32 000 B/s is the number the settings screen quotes as 115 MB/h.
        assertEquals(32_000, le32(wav, 28))
        assertEquals(2, le16(wav, 32))
    }

    @Test
    fun `an empty chunk is still a valid file`() {
        val wav = WavChunk.wrap(ByteArray(0), 8_000, 1)

        assertEquals(WavChunk.HEADER_BYTES, wav.size)
        assertEquals(0, le32(wav, 40))
        assertEquals(36, le32(wav, 4))
    }

    @Test
    fun `a nonsensical format is refused rather than written`() {
        listOf(0, -1).forEach { bad ->
            runCatching { WavChunk.wrap(ByteArray(2), bad, 1) }
                .onSuccess { throw AssertionError("sample rate $bad was accepted") }
            runCatching { WavChunk.wrap(ByteArray(2), 16_000, bad) }
                .onSuccess { throw AssertionError("channel count $bad was accepted") }
        }
    }

    // -- the level meter -------------------------------------------------------------------------

    /** Full-scale square wave is 0 dBFS by definition; the meter must agree or the scale is arbitrary. */
    @Test
    fun `full scale reads about zero dBFS`() {
        val pcm = ByteArray(400)
        for (i in pcm.indices step 2) {
            pcm[i] = 0xFF.toByte()      // 32767, little-endian
            pcm[i + 1] = 0x7F
        }

        assertEquals(0f, WavChunk.levelDbfs(pcm)!!, 0.01f)
    }

    /** Halving the amplitude is 6 dB down, which is the check that the log is base-10 and scaled by 20. */
    @Test
    fun `half scale is six decibels down`() {
        val pcm = ByteArray(400)
        for (i in pcm.indices step 2) {
            pcm[i] = 0xFF.toByte()      // 16383
            pcm[i + 1] = 0x3F
        }

        assertEquals(-6f, WavChunk.levelDbfs(pcm)!!, 0.1f)
    }

    @Test
    fun `silence is floored rather than minus infinity`() {
        val level = WavChunk.levelDbfs(ByteArray(400))!!

        assertEquals(-96f, level, 0.01f)
        assertTrue("a level meter cannot plot negative infinity", level.isFinite())
    }

    @Test
    fun `too little to measure reports nothing rather than zero`() {
        assertNull(WavChunk.levelDbfs(ByteArray(0)))
        assertNull(WavChunk.levelDbfs(ByteArray(1)))
    }
}

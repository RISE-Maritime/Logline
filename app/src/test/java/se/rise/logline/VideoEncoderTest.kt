package se.rise.logline

import se.rise.logline.sensors.EncodedFrame
import se.rise.logline.sensors.isAnnexB
import se.rise.logline.sensors.nalUnitTypes
import se.rise.logline.sensors.withCodecConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule that decides whether anybody but this phone can decode the stream.
 *
 * `foxglove.CompressedVideo` requires that every message carrying a keyframe also carries a SPS NAL
 * unit. `MediaCodec` does the opposite: SPS and PPS arrive once, ahead of the first frame, and never
 * again. Get this wrong and the recording plays perfectly from the beginning and not at all from
 * anywhere else — which is precisely how a subscriber joining mid-run, or the second file after a
 * rotation, sees it. No amount of watching the phone would show it.
 */
class VideoEncoderTest {

    /** SPS (type 7) then PPS (type 8), as MediaCodec delivers them in one codec-config buffer. */
    private val config = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 0, 1, 0x68, 0xCE.toByte())

    /** An IDR slice: start code then a NAL header whose low five bits are 5. */
    private fun keyframe() = EncodedFrame(byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22), true, 1_000)

    /** A non-IDR slice, type 1. */
    private fun delta() = EncodedFrame(byteArrayOf(0, 0, 0, 1, 0x41, 0x33), false, 2_000)

    @Test
    fun `a keyframe carries the codec config in front of it`() {
        val out = withCodecConfig(config, keyframe())

        assertArrayEquals(config + keyframe().bytes, out.bytes)
        assertTrue("still a keyframe", out.keyframe)
        assertEquals("the timestamp is untouched", 1_000L, out.presentationTimeUs)
    }

    /**
     * The SPS has to come *before* the IDR, not merely be present — a decoder reads the stream in
     * order and cannot apply parameters it has not reached yet.
     */
    @Test
    fun `the SPS precedes the IDR slice in the emitted unit`() {
        val types = nalUnitTypes(withCodecConfig(config, keyframe()).bytes)

        assertEquals(listOf(7, 8, 5), types)
        assertTrue("SPS before IDR", types.indexOf(7) < types.indexOf(5))
    }

    /**
     * Delta frames must be left alone. Prepending to every frame would work and would waste the
     * parameter sets on 90% of the stream — at 10 fps with a 2 s keyframe interval, nineteen frames in
     * twenty.
     */
    @Test
    fun `a delta frame is passed through untouched`() {
        val original = delta()

        val out = withCodecConfig(config, original)

        assertArrayEquals(original.bytes, out.bytes)
        assertEquals(original, out)
    }

    /**
     * Before the codec-config buffer has arrived there is nothing to prepend. The first frame out of
     * the encoder is a keyframe and the config precedes it, so this is a defensive case rather than a
     * live one — but returning the frame unchanged is the only answer that does not corrupt it.
     */
    @Test
    fun `no config yet means the frame goes out as it came`() {
        assertArrayEquals(keyframe().bytes, withCodecConfig(null, keyframe()).bytes)
        assertArrayEquals(keyframe().bytes, withCodecConfig(byteArrayOf(), keyframe()).bytes)
    }

    /** Annex B, not AVCC — the proto is explicit, and the two are indistinguishable at a glance. */
    @Test
    fun `annex B framing is recognised, length-prefixed is not`() {
        assertTrue(isAnnexB(byteArrayOf(0, 0, 0, 1, 0x65)))
        assertTrue("three-byte start codes are legal too", isAnnexB(byteArrayOf(0, 0, 1, 0x65)))
        // AVCC: a four-byte big-endian length where the start code would be. This is what an encoder
        // configured for MP4 muxing emits, and it decodes to nothing in a Foxglove panel.
        assertFalse(isAnnexB(byteArrayOf(0, 0, 0, 3, 0x65, 0x11, 0x22)))
    }

    @Test
    fun `nal types are read out of a multi-unit buffer`() {
        assertEquals(listOf(7, 8), nalUnitTypes(config))
        assertEquals(emptyList<Int>(), nalUnitTypes(byteArrayOf(1, 2, 3)))
    }
}

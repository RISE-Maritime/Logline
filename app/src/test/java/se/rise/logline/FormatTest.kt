package se.rise.logline

import se.rise.logline.ui.formatBearing
import se.rise.logline.ui.formatCount
import se.rise.logline.ui.formatCounted
import se.rise.logline.publish.formatElapsed
import se.rise.logline.ui.formatRate
import org.junit.Assert.assertEquals
import se.rise.logline.ui.audioMegabytesPerHour
import se.rise.logline.ui.audioRateLabel
import se.rise.logline.ui.cameraMegabytesPerHour
import se.rise.logline.ui.formatFrameRate
import se.rise.logline.ui.formatRuntimeLeft
import org.junit.Test
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.ui.formatLiveValue
import java.util.Locale

/**
 * Sample counts reach seven figures in an hour's run, and a rate is an average of a noisy thing. Both
 * are read at a glance on a phone held at arm's length, which is what these two functions are for.
 */
class FormatTest {

    @Test
    fun `counts are grouped, but only once they need it`() {
        assertEquals("0", formatCount(0))
        assertEquals("999", formatCount(999))
        // Four digits stay unbroken: "1000" is not easier to read as "1 000".
        assertEquals("1000", formatCount(1_000))
        assertEquals("12${NARROW_SPACE}345", formatCount(12_345))
        assertEquals("681${NARROW_SPACE}204", formatCount(681_204))
        assertEquals("1${NARROW_SPACE}234${NARROW_SPACE}567", formatCount(1_234_567))
    }

    /** A no-break space, so a number never wraps across two lines. */
    @Test
    fun `the separator cannot break a line`() {
        assertEquals(NARROW_SPACE, formatCount(12_345)[2])
    }

    @Test
    fun `rates carry as much precision as they mean`() {
        assertEquals("217", formatRate(217.0233))
        assertEquals("55.3", formatRate(55.294))
        assertEquals("1.0", formatRate(1.0))
        assertEquals("0.2", formatRate(0.2))
        // Below a tenth, one decimal would round every slow subject to a flat zero.
        assertEquals("0.02", formatRate(0.0166))
        assertEquals("0.00", formatRate(0.0))
    }

    /** "1 samples" makes a careful reader distrust every other number on the screen. */
    @Test
    fun `a counted noun is singular when it should be`() {
        assertEquals("1 sample", formatCounted(1, "sample"))
        assertEquals("0 samples", formatCounted(0, "sample"))
        assertEquals("12${NARROW_SPACE}345 samples", formatCounted(12_345, "sample"))
        assertEquals("1 file", formatCounted(1, "file"))
    }

    /** 44100 is 44.1 kHz. Integer division called it "44 kHz", which is not a sample rate anyone uses. */
    @Test
    fun `audio rates are named the way audio people name them`() {
        assertEquals("8 kHz", audioRateLabel(8_000))
        assertEquals("16 kHz", audioRateLabel(16_000))
        assertEquals("44.1 kHz", audioRateLabel(44_100))
        assertEquals("48 kHz", audioRateLabel(48_000))
        assertEquals("500 Hz", audioRateLabel(500))
    }

    /**
     * The cost of a capture setting, which is the whole reason the choice is in front of the user:
     * 16-bit PCM is rate x channels x 2 bytes a second, and an hour of it adds up fast.
     */
    @Test
    fun `audio data rate is the arithmetic, not a guess`() {
        assertEquals(109, audioMegabytesPerHour(16_000, 1))
        assertEquals(219, audioMegabytesPerHour(16_000, 2))
        assertEquals(302, audioMegabytesPerHour(44_100, 1))
        assertEquals(329, audioMegabytesPerHour(48_000, 1))
    }

    /**
     * The camera's per-hour figure, which is the number that should stop someone leaving 1080p running
     * on a metered link for a day. An estimate by construction — a JPEG's size depends on the scene —
     * but the arithmetic behind it has to stay put, because the settings screen states it as fact.
     */
    @Test
    fun `camera data rate is the arithmetic, not a guess`() {
        // 1280x720 at 0.5 Hz: the default, and about 90 kB a frame at the assumed density.
        assertEquals(158, cameraMegabytesPerHour(1280, 720, 0.5))
        // Same frame size, a fifth of the rate.
        assertEquals(31, cameraMegabytesPerHour(1280, 720, 0.1))
        // 1080p is 2.25x the pixels, and costs it.
        assertEquals(355, cameraMegabytesPerHour(1920, 1080, 0.5))
        assertEquals(52, cameraMegabytesPerHour(640, 480, 0.5))
    }

    /** Sub-1 Hz is an interval to a person, not a rate: "one frame every 2 s", never "0.5 frames/s". */
    @Test
    fun `frame rates below one hertz read as intervals`() {
        assertEquals("one frame every 2 s", formatFrameRate(0.5))
        assertEquals("one frame every 10 s", formatFrameRate(0.1))
        assertEquals("one frame a second", formatFrameRate(1.0))
        assertEquals("2 frames/s", formatFrameRate(2.0))
    }

    /**
     * A time-left estimate should never look more certain than it is. It comes off a fuel gauge that
     * moves in steps, so seconds would be theatre and a decimal hour would be worse.
     */
    @Test
    fun `time left is rounded to what the estimate can support`() {
        assertEquals("under a minute", formatRuntimeLeft(20_000))
        assertEquals("7 min", formatRuntimeLeft(7 * 60_000L))
        assertEquals("1 h", formatRuntimeLeft(3_600_000))
        assertEquals("3 h 40 min", formatRuntimeLeft(3 * 3_600_000L + 40 * 60_000L))
        // Past a day the minutes are noise, so they go.
        assertEquals("31 h", formatRuntimeLeft(31 * 3_600_000L + 12 * 60_000L))
    }

    /**
     * A Swedish phone renders `"%.1f".format(55.3)` as `55,3`, and a comma is a thousands separator
     * elsewhere — so every reading in the app is point-decimal regardless of the phone's locale, the
     * same way keelson's own tooling prints it. This test fails against a plain `.format()`.
     */
    @Test
    fun `numbers keep a decimal point on a comma-decimal phone`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("sv-SE"))
            assertEquals("55.3", formatRate(55.294))
            assertEquals("0.02", formatRate(0.0166))
            assertEquals("44.1 kHz", audioRateLabel(44_100))
            assertEquals("3.85", formatLiveValue(PublishedSubject.BATTERY_VOLTAGE, 3.85f))
        } finally {
            Locale.setDefault(original)
        }
    }

    /**
     * A finished run's length reads like the clock that was ticking while it ran.
     *
     * Deliberately not [formatRuntimeLeft]'s rounding: that one refuses to claim seconds because it is
     * an estimate off a quantised fuel gauge, and this one is a measurement. A run that showed
     * `00:12:34` while going should not become "13 min" the instant it stops.
     */
    @Test
    fun `a finished run keeps its seconds`() {
        assertEquals("00:00:00", formatElapsed(0))
        assertEquals("00:00:59", formatElapsed(59_999))
        assertEquals("00:12:34", formatElapsed(12 * 60_000L + 34_000L))
        assertEquals("01:00:00", formatElapsed(3_600_000))
        // Past a day it keeps counting hours rather than wrapping — 26 hours is a plausible run.
        assertEquals("26:03:00", formatElapsed(26 * 3_600_000L + 3 * 60_000L))
        // A clock that ran backwards is a bug somewhere else; it must not print a negative time here.
        assertEquals("00:00:00", formatElapsed(-5_000))
    }

    companion object {
        private const val NARROW_SPACE = ' '
    }

    /**
     * Three digits, always. Three readings sit side by side on the live dashboard, each centred on its
     * own column, so a course stepping 9 → 10 → 100 would shove its neighbours about as it went — and a
     * readout that twitches while the phone turns reads as unreliable whatever the numbers say.
     */
    @Test
    fun `a bearing is always three digits`() {
        assertEquals("000", formatBearing(0f))
        assertEquals("047", formatBearing(47.4f))
        assertEquals("315", formatBearing(315f))
        assertEquals("009", formatBearing(9f))
    }

    @Test
    fun `a bearing wraps rather than reading 360`() {
        // 360 is a bearing nobody writes, and a platform is free to hand one back.
        assertEquals("000", formatBearing(360f))
        // And the rounding has to happen before the wrap, or 359.7 comes out as "360".
        assertEquals("000", formatBearing(359.7f))
        assertEquals("359", formatBearing(359.4f))
        assertEquals("270", formatBearing(-90f))
        assertEquals("090", formatBearing(450f))
    }
}

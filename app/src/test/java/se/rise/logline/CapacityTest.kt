package se.rise.logline

import se.rise.logline.config.Settings
import se.rise.logline.keelson.Subjects
import se.rise.logline.record.MIN_FREE_BYTES
import se.rise.logline.sensors.SensorRate
import se.rise.logline.ui.CONFIGURED_MEGABYTES_PER_HOUR
import se.rise.logline.ui.MAX_MEGABYTES_PER_HOUR
import se.rise.logline.ui.MINIMUM_MEGABYTES_PER_HOUR
import se.rise.logline.ui.baseMegabytesPerHour
import se.rise.logline.ui.formatBytes
import se.rise.logline.ui.formatCapacity
import se.rise.logline.ui.megabytesPerHour
import se.rise.logline.ui.videoMegabytesPerHour
import se.rise.logline.ui.recordingCapacityMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GB = 1024L * 1024 * 1024

/**
 * "Can I record the whole passage?" — asked before a run, when there is no measured fill rate to
 * answer it with, so it is answered by arithmetic instead.
 */
class CapacityTest {

    private fun settings() = Settings(
        realm = "rise",
        entityId = "pixel_6",
        routerEndpoints = listOf("tls/router.example.com:443"),
        locationSource = "phone",
        imuSource = "phone",
        // Explicit rather than inherited: the shipped default records at maximum, and most of these
        // are about the configured rates.
        recordAllMax = false,
    )

    @Test
    fun `a default run costs the documented rate`() {
        assertEquals(baseMegabytesPerHour(settings()), megabytesPerHour(settings()), 0.0)
    }

    /**
     * The two optional subjects dominate when they are on — audio is about 109 MB/h at the default and
     * the camera about 158 — so an estimate that ignored them would be out by a factor of four on
     * exactly the settings that most need the warning.
     */
    @Test
    fun `audio and the camera are counted when they are switched on`() {
        val withAudio = megabytesPerHour(settings().copy(audioEnabled = true))
        val withCamera = megabytesPerHour(settings().copy(cameraEnabled = true))
        val withBoth = megabytesPerHour(settings().copy(audioEnabled = true, cameraEnabled = true))

        assertEquals(baseMegabytesPerHour(settings()) + 109, withAudio, 0.0)
        assertEquals(baseMegabytesPerHour(settings()) + 158, withCamera, 0.0)
        assertEquals(withAudio + withCamera - baseMegabytesPerHour(settings()), withBoth, 0.0)
    }

    /** A faster time-lapse costs more, and the estimate has to follow the rate that will actually run. */
    @Test
    fun `a faster time-lapse shortens the capacity`() {
        val slow = settings().copy(
            cameraEnabled = true,
            sensorRates = mapOf(Subjects.IMAGE_COMPRESSED to SensorRate.Hz(0.1)),
        )
        val fast = slow.copy(sensorRates = mapOf(Subjects.IMAGE_COMPRESSED to SensorRate.Hz(1.0)))

        assertTrue(megabytesPerHour(fast) > megabytesPerHour(slow))
    }

    /**
     * The fuel is the space **above** the floor the recorder stops at, not the whole volume — that
     * floor is the moment being predicted.
     */
    @Test
    fun `the floor is not part of the fuel`() {
        val hour = recordingCapacityMillis(MIN_FREE_BYTES + 77L * 1_048_576L, 77.0)

        assertEquals(3_600_000L, hour)
    }

    @Test
    fun `a volume already at the floor has no recording time`() {
        assertEquals(0L, recordingCapacityMillis(MIN_FREE_BYTES, 77.0))
        assertEquals(0L, recordingCapacityMillis(0L, 77.0))
        // Below it too — a fuller disk cannot be more optimistic than an empty one.
        assertEquals(0L, recordingCapacityMillis(MIN_FREE_BYTES - 1, 77.0))
    }

    /** Nothing to divide by is unanswerable, not infinite. */
    @Test
    fun `no data rate gives no estimate`() {
        assertNull(recordingCapacityMillis(64L * GB, 0.0))
        assertNull(recordingCapacityMillis(64L * GB, -1.0))
    }

    /**
     * A capacity is an average over a set of subjects, so the third significant figure is noise —
     * `formatRuntimeLeft` would read a three-week volume as "528 h", which is a number nobody can
     * picture.
     */
    @Test
    fun `a capacity reads in the units a person pictures`() {
        // "about" is part of the answer: two of the four arms are hedges in their own right, so the
        // card cannot prefix one without producing "about under an hour" or "about over a year".
        assertEquals("under an hour", formatCapacity(59 * 60_000L))
        assertEquals("about 3 h", formatCapacity(3 * 3_600_000L))
        assertEquals("about 47 h", formatCapacity(47 * 3_600_000L))
        assertEquals("about 2 days", formatCapacity(48 * 3_600_000L))
        assertEquals("about 22 days", formatCapacity(22 * 24 * 3_600_000L))
        // Note "1 day" is unreachable, and deliberately: hours run to 48, so a day and a half reads
        // "36 h", which is more use than "1 day" to somebody deciding whether a passage fits.
        assertEquals("about 36 h", formatCapacity(36 * 3_600_000L))
    }

    @Test
    fun `bytes read the way a person says them`() {
        assertEquals("42.1 GB", formatBytes((42.1 * 1024 * 1024 * 1024).toLong()))
        assertEquals("512 MB", formatBytes(512L * 1024 * 1024))
        assertEquals("4 kB", formatBytes(4096))
        assertEquals("512 bytes", formatBytes(512))
        assertEquals("1 byte", formatBytes(1))
        // Never a negative size — that is a failed statvfs, not a measurement.
        assertEquals("unknown", formatBytes(-1))
    }

    /**
     * The two recording modes differ by an order of magnitude, which is the whole reason the Session
     * screen states the figure rather than calling one of them "larger".
     */
    @Test
    fun `recording at maximum costs an order of magnitude more`() {
        val configured = megabytesPerHour(settings())
        val maximum = megabytesPerHour(settings().copy(recordAllMax = true))

        assertEquals(CONFIGURED_MEGABYTES_PER_HOUR.toDouble(), configured, 0.0)
        assertEquals(MAX_MEGABYTES_PER_HOUR.toDouble(), maximum, 0.0)
        assertTrue("maximum should dominate", maximum > configured * 5)
    }

    /** The headline figure: an idle 64 GB phone should promise weeks, not hours. */
    @Test
    fun `a roomy phone at the default rate promises weeks`() {
        val millis = recordingCapacityMillis(64L * GB, megabytesPerHour(settings()))!!

        // (64 GB − the 256 MB floor) ÷ 23 MB/h at the configured rates.
        assertEquals("about 118 days", formatCapacity(millis))
    }

    /**
     * A Minimum run costs its own measured figure, and the mode has to be read **before** the rate.
     *
     * `recordAllMax` defaults to true, so a Minimum run left at the shipped default used to quote
     * 241 MB/h for a file that fills six hundred times slower — the mode forces every other subject
     * off and caps position at 0.2 Hz whatever the rate chips say. Same ordering as
     * `Settings.recordRate`, same reason.
     */
    @Test
    fun `minimum costs its own rate, whatever the rate mode says`() {
        val minimum = megabytesPerHour(settings().copy(minimumMode = true, recordAllMax = true))

        assertEquals(MINIMUM_MEGABYTES_PER_HOUR, minimum, 0.0)
        assertTrue(
            "minimum should be orders below maximum",
            minimum < MAX_MEGABYTES_PER_HOUR / 100.0,
        )
    }

    /**
     * The camera cannot add 158 MB/h to a run that will not write a frame.
     *
     * `offSubjects()` forces audio and the camera off under Minimum while their enable flags stand
     * untouched — that is what makes it a mode rather than an edit — so an estimate reading the flags
     * described a run nobody had asked for. It reads `offSubjects()` now, which is the same set
     * `SubjectSink.emit` gates on.
     */
    @Test
    fun `a switch minimum overrides costs nothing`() {
        val settings = settings().copy(minimumMode = true, cameraEnabled = true, audioEnabled = true)

        assertEquals(MINIMUM_MEGABYTES_PER_HOUR, megabytesPerHour(settings), 0.0)
    }

    /**
     * Minimum is where the disk stops being the constraint, and a capacity has to stop with it.
     *
     * 64 GB at 0.4 MB/h is 6 826 days. A card stating that reads as arithmetic nobody checked; what is
     * actually going to end the run is the battery, which `timeLeft()` names once one is going.
     */
    @Test
    fun `a capacity past a year says so rather than counting days`() {
        val millis = recordingCapacityMillis(64L * GB, megabytesPerHour(settings().copy(minimumMode = true)))!!

        assertEquals("over a year", formatCapacity(millis))
        // The day before the horizon still counts days, so the arm is a ceiling and not a takeover.
        assertEquals("about 364 days", formatCapacity(364 * 24 * 3_600_000L))
    }

    /**
     * Exact, unlike the JPEG figure beside it: a bitrate is what the encoder was told to make, not a
     * guess about how well a scene compresses.
     *
     * 128 MB/h at the default is the number the whole choice of default rests on — it is *below* the
     * time-lapse's 158 MB/h while carrying twenty times the frames, so switching video on cannot make
     * a run shorter than it already was. If this figure ever rises above 158 the defaults need
     * revisiting, not the test.
     */
    @Test
    fun `video costs what its bitrate says`() {
        assertEquals(128, videoMegabytesPerHour(300))
        assertEquals(429, videoMegabytesPerHour(1_000))
        assertEquals(858, videoMegabytesPerHour(2_000))
        assertEquals(0, videoMegabytesPerHour(0))
    }
}

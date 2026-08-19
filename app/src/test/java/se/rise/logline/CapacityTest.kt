package se.rise.logline

import se.rise.logline.config.Settings
import se.rise.logline.keelson.Subjects
import se.rise.logline.record.MIN_FREE_BYTES
import se.rise.logline.sensors.SensorRate
import se.rise.logline.ui.BASE_MEGABYTES_PER_HOUR
import se.rise.logline.ui.formatBytes
import se.rise.logline.ui.formatCapacity
import se.rise.logline.ui.megabytesPerHour
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
    )

    @Test
    fun `a default run costs the documented rate`() {
        assertEquals(BASE_MEGABYTES_PER_HOUR, megabytesPerHour(settings()))
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

        assertEquals(BASE_MEGABYTES_PER_HOUR + 109, withAudio)
        assertEquals(BASE_MEGABYTES_PER_HOUR + 158, withCamera)
        assertEquals(withAudio + withCamera - BASE_MEGABYTES_PER_HOUR, withBoth)
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
        val hour = recordingCapacityMillis(MIN_FREE_BYTES + 77L * 1_048_576L, 77)

        assertEquals(3_600_000L, hour)
    }

    @Test
    fun `a volume already at the floor has no recording time`() {
        assertEquals(0L, recordingCapacityMillis(MIN_FREE_BYTES, 77))
        assertEquals(0L, recordingCapacityMillis(0L, 77))
        // Below it too — a fuller disk cannot be more optimistic than an empty one.
        assertEquals(0L, recordingCapacityMillis(MIN_FREE_BYTES - 1, 77))
    }

    /** Nothing to divide by is unanswerable, not infinite. */
    @Test
    fun `no data rate gives no estimate`() {
        assertNull(recordingCapacityMillis(64L * GB, 0))
        assertNull(recordingCapacityMillis(64L * GB, -1))
    }

    /**
     * A capacity is an average over a set of subjects, so the third significant figure is noise —
     * `formatRuntimeLeft` would read a three-week volume as "528 h", which is a number nobody can
     * picture.
     */
    @Test
    fun `a capacity reads in the units a person pictures`() {
        assertEquals("under an hour", formatCapacity(59 * 60_000L))
        assertEquals("3 h", formatCapacity(3 * 3_600_000L))
        assertEquals("47 h", formatCapacity(47 * 3_600_000L))
        assertEquals("2 days", formatCapacity(48 * 3_600_000L))
        assertEquals("22 days", formatCapacity(22 * 24 * 3_600_000L))
        // Note "1 day" is unreachable, and deliberately: hours run to 48, so a day and a half reads
        // "36 h", which is more use than "1 day" to somebody deciding whether a passage fits.
        assertEquals("36 h", formatCapacity(36 * 3_600_000L))
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

    /** The headline figure: an idle 64 GB phone should promise weeks, not hours. */
    @Test
    fun `a roomy phone at the default rate promises weeks`() {
        val millis = recordingCapacityMillis(64L * GB, megabytesPerHour(settings()))!!

        // (64 GB − the 256 MB floor) ÷ 77 MB/h ≈ 848 h.
        assertEquals("35 days", formatCapacity(millis))
    }
}

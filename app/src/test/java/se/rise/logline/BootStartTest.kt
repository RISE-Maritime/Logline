package se.rise.logline

import se.rise.logline.config.Settings
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.publish.forBootStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A run that starts itself after a reboot is narrower than one somebody starts by hand, and not by
 * choice: Android 15+ refuses a `microphone` or `camera` foreground service started from a
 * `BOOT_COMPLETED` broadcast, and `startForeground` throws rather than dropping the offending type.
 * A boot start that carried them would therefore lose the *whole run*, not one subject.
 */
class BootStartTest {

    private fun configured() = Settings(
        realm = "rise",
        entityId = "boat_1",
        routerEndpoints = listOf("tls/router.example.com:443"),
        locationSource = "phone",
        imuSource = "phone",
        audioEnabled = true,
        cameraEnabled = true,
        startOnBoot = true,
    )

    @Test
    fun `a boot start never carries audio or the camera`() {
        val booted = forBootStart(configured())

        assertFalse("audio cannot be started from BOOT_COMPLETED", booted.audioEnabled)
        assertFalse("the camera cannot be started from BOOT_COMPLETED", booted.cameraEnabled)
    }

    /**
     * Everything else has to survive, and the two the run drops are dropped in *settings* rather than
     * anywhere later — that is what keeps the service's foreground type mask and the collectors the
     * publisher launches from disagreeing about what this run contains.
     */
    @Test
    fun `nothing else about the run changes`() {
        val original = configured()

        val booted = forBootStart(original)

        assertEquals(original.copy(audioEnabled = false, cameraEnabled = false), booted)
    }

    /** The common case: nothing to drop, and the settings come through untouched. */
    @Test
    fun `a run with neither switched on is unchanged`() {
        val original = configured().copy(audioEnabled = false, cameraEnabled = false)

        assertEquals(original, forBootStart(original))
    }

    /**
     * The switches are stored as a set of *disabled* subjects, and audio and the camera are off unless
     * switched on — so dropping them has to leave the rest of that set alone. A boot start that also
     * lost somebody's other switches would publish subjects they had turned off.
     */
    @Test
    fun `the per-subject switches survive a boot start`() {
        val original = configured().copy(
            disabledSubjects = setOf(PublishedSubject.ILLUMINANCE, PublishedSubject.WIFI_RSSI),
        )

        val booted = forBootStart(original)

        assertEquals(original.disabledSubjects, booted.disabledSubjects)
        assertEquals(true, PublishedSubject.AUDIO in booted.offSubjects())
        assertEquals(true, PublishedSubject.IMAGE_COMPRESSED in booted.offSubjects())
    }
}

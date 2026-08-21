package se.rise.logline

import org.junit.Assert.assertEquals
import org.junit.Test
import se.rise.logline.config.Settings
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.publish.START_TIME_SUBJECTS

/**
 * The three subjects the Session page's Audio & video section owns.
 *
 * `MainScreen` filters `START_TIME_SUBJECTS` out of the per-subject groups so each control exists in
 * one place. That is only correct while the set is *exactly* the media three — if a fourth subject
 * ever needed a start-time decision it would silently vanish from the Device group with no switch
 * anywhere, which is the failure this pins.
 */
class MediaSubjectsTest {

    private fun settings() = Settings(
        realm = "rise",
        entityId = "pixel_6",
        routerEndpoints = listOf("tls/router.example.com:443"),
        locationSource = "phone",
        imuSource = "phone",
    )

    @Test
    fun `the start-time subjects are exactly audio, the time-lapse and video`() {
        assertEquals(
            setOf(
                PublishedSubject.AUDIO,
                PublishedSubject.IMAGE_COMPRESSED,
                PublishedSubject.VIDEO_COMPRESSED,
            ),
            START_TIME_SUBJECTS,
        )
    }

    /**
     * **Video replaces the time-lapse rather than joining it**, enforced in `offSubjects()` rather than
     * in the UI so an imported profile with both set cannot reach a state the camera refuses.
     */
    @Test
    fun `video wins when both camera subjects are switched on`() {
        val both = settings().copy(cameraEnabled = true, videoEnabled = true)

        val off = both.offSubjects()

        assertEquals(true, PublishedSubject.IMAGE_COMPRESSED in off)
        assertEquals(false, PublishedSubject.VIDEO_COMPRESSED in off)
    }

    /** With neither on, both are off and nothing binds the camera. */
    @Test
    fun `both camera subjects are off by default`() {
        val off = settings().offSubjects()

        assertEquals(true, PublishedSubject.AUDIO in off)
        assertEquals(true, PublishedSubject.IMAGE_COMPRESSED in off)
        assertEquals(true, PublishedSubject.VIDEO_COMPRESSED in off)
    }
}

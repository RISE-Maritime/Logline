package se.rise.logline

import io.zenoh.qos.CongestionControl
import io.zenoh.qos.Priority
import io.zenoh.qos.Reliability
import se.rise.logline.keelson.QosProfile
import se.rise.logline.keelson.SubjectQos
import se.rise.logline.keelson.matchingProfile
import se.rise.logline.keelson.toSubjectQos
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.Subjects
import se.rise.logline.keelson.policyQosForSubject
import se.rise.logline.keelson.qosForSubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * These profiles are a transcription of `keelson/messages/qos.yaml`, so drift is the risk this file
 * exists to catch — either someone "tidying" a value here, or upstream changing and the copy going
 * stale.
 */
class QosTest {

    /**
     * `qos.yaml` groups these under `elevated` as live conning state: where the vessel is, how fast it
     * is going, where it is going, and where it points.
     */
    @Test
    fun `the navigation subjects are elevated`() {
        assertSame(QosProfile.ELEVATED, policyQosForSubject(Subjects.LOCATION_FIX))
        assertSame(QosProfile.ELEVATED, policyQosForSubject(Subjects.SPEED_OVER_GROUND_KNOTS))
        assertSame(QosProfile.ELEVATED, policyQosForSubject(Subjects.COURSE_OVER_GROUND_DEG))
        assertSame(QosProfile.ELEVATED, policyQosForSubject(Subjects.HEADING_MAGNETIC_DEG))
        assertSame(QosProfile.ELEVATED, policyQosForSubject(Subjects.HEADING_TRUE_NORTH_DEG))
    }

    /**
     * The compass subjects that upstream does *not* elevate. `heading_accuracy_deg` and
     * `magnetic_variation_deg` are unlisted, so promoting them alongside the headings they accompany
     * would be exactly the local drift this file guards against.
     */
    @Test
    fun `the compass metadata is not elevated with the headings`() {
        assertSame(QosProfile.DEFAULT, policyQosForSubject(Subjects.HEADING_ACCURACY_DEG))
        assertSame(QosProfile.DEFAULT, policyQosForSubject(Subjects.MAGNETIC_VARIATION_DEG))
    }

    /**
     * `audio` is listed under `transient` upstream — best-effort, and droppable. Worth stating rather
     * than lumping in with the unlisted subjects, because it is the one profile here that makes a
     * dropped sample a hole in something continuous: a lost chunk is a gap in the sound, and nothing
     * retransmits it. The MCAP file is the complete copy.
     */
    @Test
    fun `audio is transient, as upstream lists it`() {
        assertSame(QosProfile.TRANSIENT, policyQosForSubject(Subjects.AUDIO))
    }

    /** `image_compressed: transient` upstream — the profile's own worked example of a camera frame. */
    @Test
    fun `image_compressed is transient, as upstream lists it`() {
        assertSame(QosProfile.TRANSIENT, policyQosForSubject(Subjects.IMAGE_COMPRESSED))
    }

    /**
     * `video_compressed: transient` upstream, under the same heading.
     *
     * The strongest case of the three: a frame that will not fit down a congested link is superseded
     * within a tenth of a second, and the next keyframe re-synchronises the decoder regardless.
     */
    @Test
    fun `video_compressed is transient, as upstream lists it`() {
        assertSame(QosProfile.TRANSIENT, policyQosForSubject(Subjects.VIDEO_COMPRESSED))
    }

    /**
     * `log_message: background` upstream — the lowest priority there is, so an annotation can never
     * delay live navigation data. Reliable all the same: it is an audit trail, and losing one is
     * losing the thing somebody deliberately recorded.
     */
    @Test
    fun `log_message is background, as upstream lists it`() {
        assertSame(QosProfile.BACKGROUND, policyQosForSubject(Subjects.LOG_MESSAGE))
    }

    /**
     * Everything else this app publishes is unlisted in `qos.yaml` and inherits `default`. Promoting
     * one locally would make the same subject travel differently from this phone than from any other
     * connector, which is the whole thing the policy file exists to prevent.
     */
    @Test
    fun `every other published subject inherits default`() {
        val listedUpstream = setOf(
            Subjects.AUDIO,
            Subjects.IMAGE_COMPRESSED,
            Subjects.VIDEO_COMPRESSED,
            Subjects.LOCATION_FIX,
            Subjects.SPEED_OVER_GROUND_KNOTS,
            Subjects.COURSE_OVER_GROUND_DEG,
            Subjects.HEADING_MAGNETIC_DEG,
            Subjects.HEADING_TRUE_NORTH_DEG,
            Subjects.LOG_MESSAGE,
            Subjects.RAW_NMEA0183,
        )
        PublishedSubject.entries
            .filterNot { it.subject in listedUpstream }
            .forEach {
                assertSame("$it should inherit default", QosProfile.DEFAULT, policyQosForSubject(it.subject))
            }
    }

    @Test
    fun `an unlisted subject falls back to default`() {
        assertSame(QosProfile.DEFAULT, policyQosForSubject("some_subject_added_upstream_later"))
    }

    @Test
    fun `profiles match qos-yaml`() {
        assertProfile(QosProfile.REALTIME, Priority.REALTIME, Reliability.RELIABLE, express = true)
        assertProfile(
            QosProfile.TRANSIENT, Priority.INTERACTIVE_HIGH, Reliability.BEST_EFFORT, express = false
        )
        assertProfile(QosProfile.ELEVATED, Priority.DATA_HIGH, Reliability.RELIABLE, express = false)
        assertProfile(QosProfile.DEFAULT, Priority.DATA, Reliability.RELIABLE, express = false)
        assertProfile(QosProfile.BACKGROUND, Priority.DATA_LOW, Reliability.RELIABLE, express = false)
    }

    /** Every profile in qos.yaml drops rather than blocks — blocking would stall a sensor thread. */
    @Test
    fun `no profile blocks the producer`() {
        QosProfile.entries.forEach {
            assertEquals(CongestionControl.DROP, it.congestionControl)
        }
    }

    /** The whole on-the-wire change for this app: location_fix outranks the routine telemetry. */
    @Test
    fun `elevated differs from default only in priority`() {
        val elevated = QosProfile.ELEVATED
        val default = QosProfile.DEFAULT
        assertNotEquals(default.priority, elevated.priority)
        assertEquals(default.congestionControl, elevated.congestionControl)
        assertEquals(default.reliability, elevated.reliability)
        assertEquals(default.express, elevated.express)
    }

    // ---- per-subject overrides (qos.yaml: "a connector may still override per-publisher") ----

    @Test
    fun `an override wins over policy`() {
        val custom = SubjectQos(Priority.REALTIME, CongestionControl.DROP, Reliability.BEST_EFFORT, true)
        val overrides = mapOf(Subjects.ANGULAR_VELOCITY_RADPS to custom)

        assertEquals(custom, qosForSubject(Subjects.ANGULAR_VELOCITY_RADPS, overrides))
    }

    @Test
    fun `subjects without an override still follow policy`() {
        val custom = SubjectQos(Priority.REALTIME, CongestionControl.DROP, Reliability.BEST_EFFORT, true)
        val overrides = mapOf(Subjects.ANGULAR_VELOCITY_RADPS to custom)

        // Overriding one subject must not disturb the others.
        assertEquals(QosProfile.ELEVATED.toSubjectQos(), qosForSubject(Subjects.LOCATION_FIX, overrides))
        assertEquals(
            QosProfile.DEFAULT.toSubjectQos(),
            qosForSubject(Subjects.LINEAR_ACCELERATION_MPSS, overrides),
        )
    }

    @Test
    fun `no overrides is identical to policy`() {
        PublishedSubject.entries.map { it.subject }.forEach { subject ->
            assertEquals(policyQosForSubject(subject).toSubjectQos(), qosForSubject(subject, emptyMap()))
        }
    }

    /** Any combination is now reachable, including ones qos.yaml never defines. */
    @Test
    fun `a free-form combination is preserved exactly`() {
        val odd = SubjectQos(Priority.BACKGROUND, CongestionControl.BLOCK, Reliability.BEST_EFFORT, true)

        val effective = qosForSubject(Subjects.LOCATION_FIX, mapOf(Subjects.LOCATION_FIX to odd))

        assertEquals(odd, effective)
        assertEquals(null, odd.matchingProfile())
    }

    @Test
    fun `a combination equal to a named profile is recognised as one`() {
        assertSame(QosProfile.TRANSIENT, QosProfile.TRANSIENT.toSubjectQos().matchingProfile())
        assertSame(QosProfile.ELEVATED, QosProfile.ELEVATED.toSubjectQos().matchingProfile())
    }

    private fun assertProfile(
        profile: QosProfile,
        priority: Priority,
        reliability: Reliability,
        express: Boolean,
    ) {
        assertEquals(priority, profile.priority)
        assertEquals(reliability, profile.reliability)
        assertEquals(express, profile.express)
    }
}

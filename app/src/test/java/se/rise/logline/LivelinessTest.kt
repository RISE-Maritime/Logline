package se.rise.logline

import se.rise.logline.calibrate.RigCalibration
import se.rise.logline.config.Settings
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.SourceKind
import se.rise.logline.keelson.Subjects
import se.rise.logline.keelson.legacyLivelinessKey
import se.rise.logline.keelson.pubsubKey
import se.rise.logline.keelson.sourceLivelinessKey
import se.rise.logline.keelson.subjectLivelinessKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this phone *claims* to publish — protocol specification §5.2.
 *
 * The set is the part worth testing: the tokens themselves need a Zenoh session, but which keys go in
 * needs only the registry, and getting that wrong is invisible on the device. A subject missing from
 * here is read by upstream's `entity_health` as `NOT_ADVERTISED` — a fault in the *monitor's* config —
 * so the subject silently leaves the vessel's coverage rather than showing up as a problem.
 */
class LivelinessTest {

    private val settings = Settings(
        realm = "rise",
        entityId = "pixel_6",
        routerEndpoints = listOf("tcp/127.0.0.1:7447"),
        locationSource = "gnss",
        imuSource = "imu",
        deviceSource = "box",
        calibrationSource = "survey",
    )

    /** The same map `SensorPublisher.start()` builds for the phone's publishers. */
    private fun phoneKeys(s: Settings = settings) = PublishedSubject.entries
        .filter { it.source != SourceKind.CALIBRATION }
        .associateWith { pubsubKey(s.realm, s.entityFor(it), it.subject, s.sourceFor(it)) }

    @Test
    fun `every subject the phone publishes is claimed, on its publisher's own key`() {
        val keys = phoneKeys()

        val claimed = subjectLivelinessKeys(listOf(keys), emptySet(), emptySet())

        assertEquals(keys.size, claimed.size)
        assertEquals(keys.values.toSet(), claimed)
        assertTrue("rise/@v0/pixel_6/pubsub/location_fix/gnss" in claimed)
    }

    /**
     * A switch is configuration, not silence. §5.2 forbids retracting a token because data is
     * momentarily absent — but a subject somebody switched off is not momentarily absent, it is one
     * this phone is no longer wired to publish, and leaving the claim standing would have a monitor
     * waiting for samples that are never coming.
     */
    @Test
    fun `a switched-off subject is not claimed`() {
        val keys = phoneKeys()

        val claimed = subjectLivelinessKeys(listOf(keys), setOf(PublishedSubject.AUDIO), emptySet())

        assertEquals(keys.size - 1, claimed.size)
        assertFalse(claimed.any { it.contains("/${Subjects.AUDIO}/") })
    }

    /** No sensor, no capability — the other half of what `unavailableSubjects()` is for. */
    @Test
    fun `absent hardware is not claimed`() {
        val claimed = subjectLivelinessKeys(
            listOf(phoneKeys()),
            emptySet(),
            setOf(PublishedSubject.IMU_TEMPERATURE),
        )

        assertFalse(claimed.any { it.contains("/${Subjects.IMU_TEMPERATURE_CELSIUS}/") })
    }

    /**
     * The case §5.2 is most explicit about, and the one an eager implementation gets wrong: a subject
     * that is wired and silent keeps its token. `log_message` publishes only when somebody presses a
     * button, and `heading_true_north_deg` publishes nothing at all before the first fix.
     */
    @Test
    fun `a wired but silent subject keeps its claim`() {
        val claimed = subjectLivelinessKeys(listOf(phoneKeys()), emptySet(), emptySet())

        assertTrue(claimed.any { it.contains("/${Subjects.LOG_MESSAGE}/") })
        assertTrue(claimed.any { it.contains("/${Subjects.HEADING_TRUE_NORTH_DEG}/") })
    }

    /**
     * Three rigs publish `frame_transform` under three entity ids, so one registry entry becomes three
     * claims. Collapsing them to one — the shape `entityFor()` used to encourage — would leave two
     * rigs' geometry on the bus under keys nothing claims.
     */
    @Test
    fun `each rig claims its own entity's calibration keys`() {
        val rigs = listOf("rig_a", "rig_b", "rig_c").map { entityId ->
            settings.rigKeys(RigCalibration(name = entityId, entityId = entityId, parentFrameId = "$entityId-frame-ccrp"))
        }

        val claimed = subjectLivelinessKeys(listOf(phoneKeys()) + rigs, emptySet(), emptySet())

        val transforms = claimed.filter { it.contains("/${Subjects.FRAME_TRANSFORM}/") }
        assertEquals(3, transforms.size)
        assertEquals(
            setOf(
                "rise/@v0/rig_a/pubsub/frame_transform/survey",
                "rise/@v0/rig_b/pubsub/frame_transform/survey",
                "rise/@v0/rig_c/pubsub/frame_transform/survey",
            ),
            transforms.toSet(),
        )
    }

    /**
     * The source tier is per *identity*, not per subject — 52 subjects come off a handful of
     * `(entity, source)` pairs, and a token per subject here would be the wrong tier entirely.
     */
    @Test
    fun `the source tier is one pair per identity, with the legacy shape beside it`() {
        val pairs = PublishedSubject.entries
            .filter { it.source != SourceKind.CALIBRATION }
            .map { settings.entityFor(it) to settings.sourceFor(it) }
            .toSet()

        // gnss, imu, box, plus the two fixed radio ids — all under the phone's entity.
        assertEquals(setOf("gnss", "imu", "box", "cellular", "wifi"), pairs.map { it.second }.toSet())
        assertEquals(setOf("pixel_6"), pairs.map { it.first }.toSet())

        val tokens = pairs.flatMap { (entity, source) ->
            listOf(
                sourceLivelinessKey(settings.realm, entity, source),
                legacyLivelinessKey(settings.realm, entity, source),
            )
        }
        assertEquals("no duplicates across the two shapes", tokens.size, tokens.toSet().size)
        assertTrue("rise/@v0/pixel_6/*/gnss" in tokens)
        assertTrue("rise/@v0/pixel_6/pubsub/*/gnss" in tokens)
    }
}

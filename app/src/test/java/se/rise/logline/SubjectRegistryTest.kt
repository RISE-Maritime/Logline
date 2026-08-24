package se.rise.logline

import se.rise.logline.config.Settings
import se.rise.logline.calibrate.PlatformCalibration
import se.rise.logline.calibrate.SensorMount
import se.rise.logline.calibrate.SensorType
import se.rise.logline.calibrate.Vec3M
import se.rise.logline.calibrate.defaultEntityId
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.SourceKind
import se.rise.logline.keelson.RadioSources
import se.rise.logline.keelson.Subjects
import se.rise.logline.keelson.pubsubKey
import se.rise.logline.sensors.SensorRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry is the single source of truth for the subject set, so its invariants are worth pinning:
 * everything that used to fail silently in a hand-maintained list now fails here instead.
 */
class SubjectRegistryTest {

    /**
     * The unique key is the (subject, source) pair, not the subject name.
     *
     * `radio_rssi_dbm` is deliberately published twice — once per radio link — which is how upstream
     * models it ("use source_id to distinguish links"). What must never collide is two publishers of the
     * same subject from the same source, since that would be two publishers on one key expression.
     */
    @Test
    fun `no two entries publish on the same key`() {
        // Asserted on the key each entry actually resolves to, rather than on (subject, fixedSourceId):
        // `location_fix` is now published twice — the phone's live position under the phone's entity,
        // and a platform's surveyed zero under the platform's — and neither carries a fixed source id. What has
        // to stay true is that no two entries can ever land on one key and overwrite each other in
        // Zenoh's latest-value store.
        val platform = PlatformCalibration.forName("SSRS18")
        val settings = settings().withPlatform(platform)
        val keys = settings.allKeys()
        assertEquals("two registry entries publish on the same key", keys.size, keys.toSet().size)

        // ...and with no platform calibrated, where the three calibration entries publish nothing at all.
        val bare = settings().allKeys()
        assertEquals("two registry entries collide when no platform is calibrated", bare.size, bare.toSet().size)
    }

    /**
     * Several platforms is the case the single-entity model could not express.
     *
     * Three platforms publishing means nine calibration keys, all distinct — one platform's transforms landing on
     * another's key would have them overwrite each other in Zenoh's latest-value store, and a consumer
     * would read one platform's geometry as another's.
     */
    @Test
    fun `every publishing platform gets its own keys and none of them collide`() {
        val settings = settings().copy(
            platforms = listOf("SSRS18", "Stora Krabban", "Manatee").map {
                PlatformCalibration.forName(it).copy(
                    sensors = listOf(
                        SensorMount(
                            label = "Lidar",
                            frameId = "${defaultEntityId(it)}-frame-lidar",
                            sensorType = SensorType.LIDAR,
                            translation = Vec3M(0.1, 0.0, 0.0),
                        ),
                    ),
                )
            },
            activePlatformEntityId = "ssrs18",
            publishingPlatformEntityIds = setOf("stora-krabban", "manatee"),
        )

        val keys = settings.allKeys()
        assertEquals(3, settings.publishingPlatforms().size)
        assertEquals("a platform's keys collide with another's", keys.size, keys.toSet().size)
        assertTrue("rise/@v0/manatee/pubsub/frame_transform/calibration" in keys)
        assertTrue("rise/@v0/stora-krabban/pubsub/configuration_json/calibration" in keys)
    }

    /**
     * Every key a run would declare: the phone's entries plus three per publishing platform.
     *
     * Built the way `SensorPublisher.start()` builds them — the calibration entries deliberately left
     * out of the per-entry pass, because their entity comes from a platform rather than from the registry.
     */
    private fun Settings.allKeys(): List<String> =
        PublishedSubject.entries
            .filter { it.source != SourceKind.CALIBRATION }
            .map { pubsubKey(realm, entityFor(it), it.subject, sourceFor(it)) } +
            publishingPlatforms().flatMap { platformKeys(it).values }

    /** One platform, active, with a sensor so it is publishable. */
    private fun Settings.withPlatform(platform: PlatformCalibration) = copy(
        platforms = listOf(
            platform.copy(
                sensors = platform.sensors.ifEmpty {
                    listOf(
                        SensorMount(
                            label = "Lidar",
                            frameId = "${platform.entityId}-frame-lidar",
                            sensorType = SensorType.LIDAR,
                            translation = Vec3M(0.1, 0.0, 0.0),
                        ),
                    )
                },
            ),
        ),
        activePlatformEntityId = platform.entityId,
    )

    /** The two `location_fix` entries are told apart by entity *and* source, not by the subject. */
    @Test
    fun `the platform's surveyed zero does not collide with the phone's live fix`() {
        val settings = settings().withPlatform(PlatformCalibration.forName("SSRS18"))

        assertEquals(
            "rise/@v0/pixel_6/pubsub/location_fix/phone",
            pubsubKey(
                settings.realm,
                settings.entityFor(PublishedSubject.LOCATION_FIX),
                PublishedSubject.LOCATION_FIX.subject,
                settings.sourceFor(PublishedSubject.LOCATION_FIX),
            ),
        )
        assertEquals(
            "rise/@v0/ssrs18/pubsub/location_fix/calibration",
            settings.platformKeys(settings.platforms.single()).getValue(PublishedSubject.CALIBRATION_ZERO),
        )
    }

    @Test
    fun `one subject may be published from two links`() {
        val rssi = PublishedSubject.entries.filter { it.subject == Subjects.RADIO_RSSI_DBM }
        assertEquals(2, rssi.size)
        assertEquals(
            setOf(RadioSources.CELLULAR, RadioSources.WIFI),
            rssi.map { it.fixedSourceId }.toSet(),
        )
    }

    /** Entry names stay unique — they are what navigation and status are keyed on now. */
    @Test
    fun `lookup by entry name is unambiguous`() {
        PublishedSubject.entries.forEach {
            assertEquals(it, PublishedSubject.forName(it.name))
        }
        assertNull(PublishedSubject.forName("NOT_AN_ENTRY"))
    }

    /**
     * Subject names are protocol — they must match `messages/subjects.yaml` upstream exactly. This
     * transcribes the expected set so a typo shows up here rather than as a subject no consumer
     * subscribes to, which is invisible from the publishing side.
     */
    @Test
    fun `subject names match the upstream registry`() {
        assertEquals(
            setOf(
                "location_fix",
                "speed_over_ground_knots",
                "course_over_ground_deg",
                "raw_nmea0183",
                "location_fix_satellites_used",
                "location_fix_satellites_visible",
                "location_fix_quality",
                "location_fix_accuracy_horizontal_m",
                "location_fix_accuracy_vertical_m",
                "altitude_above_msl_m",
                "location_fix_undulation_m",
                "roll_deg",
                "pitch_deg",
                "yaw_deg",
                "roll_rate_degps",
                "pitch_rate_degps",
                "yaw_rate_degps",
                "device_uptime_duration",
                "imu_temperature_celsius",
                "linear_acceleration_mpss",
                "angular_velocity_radps",
                "orientation_quaternion",
                "magnetic_field_gauss",
                "heading_magnetic_deg",
                "heading_true_north_deg",
                "heading_accuracy_deg",
                "magnetic_variation_deg",
                "air_pressure_pa",
                "illuminance_lux",
                "battery_state_of_charge_pct",
                "battery_voltage_v",
                "battery_current_a",
                "battery_temperature_celsius",
                "battery_is_charging",
                "radio_rsrp_dbm",
                "radio_rsrq_db",
                "radio_sinr_db",
                "radio_rssi_dbm",
                "radio_access_technology",
                "radio_downlink_bitrate_bps",
                "radio_uplink_bitrate_bps",
                "radio_cell_id",
                "radio_physical_cell_id",
                "radio_earfcn",
                "radio_band",
                "radio_downlink_bandwidth_mhz",
                "audio",
                "image_compressed",
                "video_compressed",
                "log_message",
                "frame_transform",
                "configuration_json",
            ),
            PublishedSubject.entries.map { it.subject }.toSet(),
        )
    }

    @Test
    fun `every rate owner is a real subject that owns its own rate`() {
        PublishedSubject.entries.mapNotNull { it.rateOwner }.forEach { owner ->
            val entry = PublishedSubject.forSubject(owner)
            assertNotNull("rateOwner $owner is not a published subject", entry)
            // A chain would mean resolving a rate needs a loop; keep it one hop.
            assertNull("rateOwner $owner must not itself follow another", entry!!.rateOwner)
        }
    }

    @Test
    fun `lookup by subject resolves to an entry publishing it`() {
        PublishedSubject.entries.forEach {
            assertEquals(it.subject, PublishedSubject.forSubject(it.subject)?.subject)
        }
        assertNull(PublishedSubject.forSubject("not_a_subject"))
    }

    /**
     * The old `defaultRate` was an if/else that handed 50 Hz to everything that was not `location_fix`.
     * A barometer or a battery on 50 Hz is wrong, and it was wrong in silence.
     */
    @Test
    fun `defaults are per subject, not inherited from the IMU`() {
        assertEquals(SensorRate.Hz(1.0), Settings.defaultRate(Subjects.LOCATION_FIX))
        assertEquals(SensorRate.Hz(50.0), Settings.defaultRate(Subjects.ANGULAR_VELOCITY_RADPS))
        assertEquals(SensorRate.Hz(50.0), Settings.defaultRate(Subjects.MAGNETIC_FIELD_GAUSS))
        assertEquals(SensorRate.Hz(1.0), Settings.defaultRate(Subjects.AIR_PRESSURE_PA))
        assertEquals(SensorRate.Hz(0.2), Settings.defaultRate(Subjects.BATTERY_STATE_OF_CHARGE_PCT))
    }

    /** A follower reports the owner's rate, including when the owner has been overridden. */
    @Test
    fun `a following subject reports the rate of the subject it follows`() {
        val settings = settings(sensorRates = mapOf(Subjects.LOCATION_FIX to SensorRate.Hz(0.2)))

        assertEquals(SensorRate.Hz(0.2), settings.rate(Subjects.LOCATION_FIX))
        assertEquals(SensorRate.Hz(0.2), settings.rate(Subjects.SPEED_OVER_GROUND_KNOTS))
        assertEquals(SensorRate.Hz(0.2), settings.rate(Subjects.COURSE_OVER_GROUND_DEG))
        // ...and is unaffected by a rate stored against its own name, which nothing should write.
        val stray = settings(
            sensorRates = mapOf(Subjects.SPEED_OVER_GROUND_KNOTS to SensorRate.Hz(99.0)),
        )
        assertEquals(SensorRate.Hz(1.0), stray.rate(Subjects.SPEED_OVER_GROUND_KNOTS))
    }

    @Test
    fun `the battery scalars follow one poll`() {
        val settings = settings(
            sensorRates = mapOf(Subjects.BATTERY_STATE_OF_CHARGE_PCT to SensorRate.Hz(0.5)),
        )
        listOf(
            Subjects.BATTERY_VOLTAGE_V,
            Subjects.BATTERY_CURRENT_A,
            Subjects.BATTERY_TEMPERATURE_CELSIUS,
            Subjects.BATTERY_IS_CHARGING,
        ).forEach { assertEquals("$it should follow the poll rate", SensorRate.Hz(0.5), settings.rate(it)) }
    }

    /** All three source ids default to `phone`, which is what keeps liveliness at one token. */
    @Test
    fun `distinct source ids resolve per subject`() {
        val settings = settings().copy(
            locationSource = "fix",
            imuSource = "imu",
            deviceSource = "device",
        )
        assertEquals("fix", settings.sourceFor(PublishedSubject.LOCATION_FIX))
        assertEquals("fix", settings.sourceFor(PublishedSubject.SPEED_OVER_GROUND))
        // The unfused solutions sit *beneath* the configured id rather than replacing it, which is
        // what stops a locationSource of "gnss" colliding with the GNSS-only stream.
        assertEquals("fix/gnss", settings.sourceFor(PublishedSubject.LOCATION_FIX_GNSS))
        assertEquals("fix/network", settings.sourceFor(PublishedSubject.LOCATION_FIX_NETWORK))
        assertEquals("imu", settings.sourceFor(PublishedSubject.MAGNETIC_FIELD))
        assertEquals("device", settings.sourceFor(PublishedSubject.AIR_PRESSURE))
        assertEquals("device", settings.sourceFor(PublishedSubject.BATTERY_VOLTAGE))

        assertEquals("cellular", settings.sourceFor(PublishedSubject.CELLULAR_RSRP))
        assertEquals("wifi", settings.sourceFor(PublishedSubject.WIFI_RSSI))
        assertEquals("calibration", settings.sourceFor(PublishedSubject.FRAME_TRANSFORM))

        // The radio links carry fixed ids — they name which radio measured the value, so they are not
        // the user's to rename. Everything else still collapses onto one source, and therefore one
        // liveliness token, plus one per radio link.
        val defaults = settings()
        assertTrue(
            "the configurable sources all default to phone",
            PublishedSubject.entries
                // Suffixed entries are excluded too: they *are* on the configured source, one level
                // down, so `phone/gnss` is the right answer rather than a counter-example.
                .filter {
                    it.fixedSourceId == null &&
                        it.sourceSuffix == null &&
                        it.source != SourceKind.CALIBRATION
                }
                .all { defaults.sourceFor(it) == "phone" },
        )
        assertEquals(
            setOf("phone", "phone/gnss", "phone/network", "cellular", "wifi", "calibration"),
            PublishedSubject.entries.map { defaults.sourceFor(it) }.toSet(),
        )
    }

    /**
     * What the live view shows under **Basic**, transcribed so adding a subject is a decision.
     *
     * `featured` defaults to false, which is the safe direction — a new subject appears under All and
     * nowhere else. This pins the set anyway, because the failure it guards against is the opposite
     * one: somebody adding a genuinely operational subject and never noticing it is missing from the
     * screen people actually watch during a run. A curated list that nothing checks stops being
     * curated within a release or two.
     *
     * If this fails, decide which side the new subject belongs on and update the set here — do not
     * simply widen it to make the test pass.
     */
    @Test
    fun `the Basic set of the live view is the operational one`() {
        assertEquals(
            setOf(
                "location_fix",
                "speed_over_ground_knots",
                "course_over_ground_deg",
                "location_fix_quality",
                "location_fix_accuracy_horizontal_m",
                "heading_true_north_deg",
                "air_pressure_pa",
                "battery_state_of_charge_pct",
            ),
            PublishedSubject.entries.filter { it.featured }.map { it.subject }.toSet(),
        )
    }

    /**
     * The entity is the phone for everything it measures, and the *platform* for what it surveyed.
     *
     * This is the one place a key's entity varies, and getting it wrong files a vessel's geometry under
     * the phone that happened to measure it — where nobody looking for that vessel would ever find it.
     */
    @Test
    fun `the calibration publishes under the platform's entity, everything else under the phone's`() {
        val settings = settings().withPlatform(PlatformCalibration.forName("SSRS18"))
        val platformKeys = settings.platformKeys(settings.platforms.single())

        assertEquals(
            "rise/@v0/ssrs18/pubsub/frame_transform/calibration",
            platformKeys.getValue(PublishedSubject.FRAME_TRANSFORM),
        )
        assertEquals(
            "rise/@v0/ssrs18/pubsub/configuration_json/calibration",
            platformKeys.getValue(PublishedSubject.CONFIGURATION_JSON),
        )
        assertEquals("pixel_6", settings.entityFor(PublishedSubject.LOCATION_FIX))
        assertEquals("pixel_6", settings.entityFor(PublishedSubject.AUDIO))
    }

    /**
     * `entityFor` answers for the phone and only for the phone, now that a platform's keys come from the
     * platform itself.
     *
     * It used to return the platform's entity for the three calibration entries, which was the right answer
     * while there could only be one platform. Keeping that behaviour with a library would mean silently
     * picking one of several — so it returns the phone's entity for every entry, and the calibration
     * entries never reach it: `SensorPublisher` leaves them out of the per-entry pass entirely.
     */
    @Test
    fun `the entity is the phone's for every registry entry`() {
        val settings = settings().withPlatform(PlatformCalibration.forName("SSRS18"))
        assertTrue(
            "a registry entry resolved to something other than the phone",
            PublishedSubject.entries.all { settings.entityFor(it) == "pixel_6" },
        )
    }

    /** With nothing calibrated there are no platform keys at all, rather than malformed ones. */
    @Test
    fun `an empty library publishes no calibration keys`() {
        val settings = settings()
        assertEquals(emptyList<PlatformCalibration>(), settings.publishingPlatforms())
        assertTrue(settings.allKeys().none { it.contains("/calibration") })
    }

    /**
     * The link from a derived subject to the one whose rate governs it has to reach an entry, not a
     * subject, because that is what navigation and identity are keyed on.
     */
    @Test
    fun `every subject that rides another resolves to a real entry`() {
        PublishedSubject.entries.filter { it.rateOwner != null }.forEach { entry ->
            assertNotNull("${entry.name} names an owner that is not an entry", entry.rateOwnerEntry())
        }
    }

    /**
     * One hop reaches the head, which is what lets the UI show a single link rather than walk a chain.
     *
     * A chain would also mean `Settings.rate()` reading the wrong subject's rate, since it does the
     * same single hop.
     */
    @Test
    fun `a rate owner owns its own rate`() {
        PublishedSubject.entries.mapNotNull { it.rateOwnerEntry() }.forEach { owner ->
            assertNull("${owner.name} is an owner and has an owner itself", owner.rateOwner)
        }
    }

    /**
     * The ambiguity this exists for: **four** entries publish `location_fix`.
     *
     * The phone's fused fix, the two unfused solutions beside it, and the platform's surveyed zero
     * point all share a subject and differ in everything else, so resolving an owner by subject alone
     * answers with whichever comes first in the enum. Matching on `SourceKind` too is what keeps the
     * platform's zero pointed at `frame_transform` — the geometry loop it is actually published from —
     * rather than at the phone's GNSS.
     *
     * **The order is the assertion.** `forSubject()` answers with the earliest entry, and the fused fix
     * is the one that should answer for `location_fix`: it is what the derived subjects ride, what the
     * live map draws, and what a recording's track is read from. Moving `LOCATION_FIX_GNSS` above it
     * would silently repoint all three at a stream that is empty indoors.
     */
    @Test
    fun `four entries publish location_fix, and the fused one answers first`() {
        assertEquals(
            listOf(
                PublishedSubject.LOCATION_FIX,
                PublishedSubject.LOCATION_FIX_GNSS,
                PublishedSubject.LOCATION_FIX_NETWORK,
                PublishedSubject.CALIBRATION_ZERO,
            ),
            PublishedSubject.entries.filter { it.subject == Subjects.LOCATION_FIX },
        )
        assertEquals(
            "the fused fix answers for the bare subject",
            PublishedSubject.LOCATION_FIX,
            PublishedSubject.forSubject(Subjects.LOCATION_FIX),
        )
        assertEquals(
            PublishedSubject.LOCATION_FIX,
            PublishedSubject.SPEED_OVER_GROUND.rateOwnerEntry(),
        )
        assertEquals(
            PublishedSubject.FRAME_TRANSFORM,
            PublishedSubject.CALIBRATION_ZERO.rateOwnerEntry(),
        )
    }

    private fun settings(sensorRates: Map<String, SensorRate> = emptyMap()) = Settings(
        realm = Settings.DEFAULT_REALM,
        entityId = "pixel_6",
        routerEndpoints = listOf(Settings.DEFAULT_ENDPOINT),
        locationSource = Settings.DEFAULT_LOCATION_SOURCE,
        imuSource = Settings.DEFAULT_IMU_SOURCE,
        sensorRates = sensorRates,
    )

    /**
     * **Exactly two subjects are never thinned, and the set is transcribed rather than derived.**
     *
     * `neverThinned` is read in two places — `SensorPublisher.publishIntervals`, which refuses them a
     * decimator, and the subject page, which tells the operator every recorded sample goes on the wire.
     * A third subject gaining the flag without somebody writing its sentence would put a page-wide
     * claim on a subject nobody had thought about; a subject losing it would leave the page saying
     * something the publisher no longer does.
     *
     * Both are here for reasons the flag's own documentation gives: a chunk is not a sample, and a
     * dropped H.264 frame does not decode.
     */
    @Test
    fun `only audio and video are exempt from thinning`() {
        assertEquals(
            setOf("audio", "video_compressed"),
            PublishedSubject.entries.filter { it.neverThinned }.map { it.subject }.toSet(),
        )
    }

    /**
     * And they are exempt in the *other* direction too: a subject that can be thinned must be able to
     * record and publish at different rates, or the exemption would be unreachable rather than
     * unnecessary.
     */
    @Test
    fun `a subject that is never thinned has one rate, not two`() {
        val settings = settings()
        PublishedSubject.entries.filter { it.neverThinned }.forEach {
            assertFalse(
                "${it.subject} is exempt from thinning, so it must not offer two rates",
                settings.hasSeparateRecordRate(it.subject),
            )
        }
    }
}

package se.rise.logline

import se.rise.logline.config.Settings
import se.rise.logline.calibrate.RigCalibration
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.SourceKind
import se.rise.logline.keelson.RadioSources
import se.rise.logline.keelson.Subjects
import se.rise.logline.keelson.pubsubKey
import se.rise.logline.sensors.SensorRate
import org.junit.Assert.assertEquals
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
        // and a rig's surveyed zero under the rig's — and neither carries a fixed source id. What has
        // to stay true is that no two entries can ever land on one key and overwrite each other in
        // Zenoh's latest-value store.
        val settings = settings().copy(calibration = RigCalibration.forName("SSRS18"))
        val keys = PublishedSubject.entries.map {
            pubsubKey(settings.realm, settings.entityFor(it), it.subject, settings.sourceFor(it))
        }
        assertEquals("two registry entries publish on the same key", keys.size, keys.toSet().size)

        // ...and with no rig calibrated, where every entity falls back to the phone's.
        val bare = PublishedSubject.entries.map {
            pubsubKey(
                Settings.DEFAULT_REALM,
                settings().entityFor(it),
                it.subject,
                settings().sourceFor(it),
            )
        }
        assertEquals("two registry entries collide when no rig is calibrated", bare.size, bare.toSet().size)
    }

    /** The two `location_fix` entries are told apart by entity *and* source, not by the subject. */
    @Test
    fun `the rig's surveyed zero does not collide with the phone's live fix`() {
        val settings = settings().copy(calibration = RigCalibration.forName("SSRS18"))

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
            pubsubKey(
                settings.realm,
                settings.entityFor(PublishedSubject.CALIBRATION_ZERO),
                PublishedSubject.CALIBRATION_ZERO.subject,
                settings.sourceFor(PublishedSubject.CALIBRATION_ZERO),
            ),
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
                "audio",
                "image_compressed",
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
            locationSource = "gnss",
            imuSource = "imu",
            deviceSource = "device",
        )
        assertEquals("gnss", settings.sourceFor(PublishedSubject.LOCATION_FIX))
        assertEquals("gnss", settings.sourceFor(PublishedSubject.SPEED_OVER_GROUND))
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
                .filter { it.fixedSourceId == null && it.source != SourceKind.CALIBRATION }
                .all { defaults.sourceFor(it) == "phone" },
        )
        assertEquals(
            setOf("phone", "cellular", "wifi", "calibration"),
            PublishedSubject.entries.map { defaults.sourceFor(it) }.toSet(),
        )
    }

    /**
     * The entity is the phone for everything it measures, and the *rig* for what it surveyed.
     *
     * This is the one place a key's entity varies, and getting it wrong files a vessel's geometry under
     * the phone that happened to measure it — where nobody looking for that vessel would ever find it.
     */
    @Test
    fun `the calibration publishes under the rig's entity, everything else under the phone's`() {
        val rig = RigCalibration.forName("SSRS18")
        val settings = settings().copy(calibration = rig)

        assertEquals("ssrs18", settings.entityFor(PublishedSubject.FRAME_TRANSFORM))
        assertEquals("ssrs18", settings.entityFor(PublishedSubject.CONFIGURATION_JSON))
        assertEquals("pixel_6", settings.entityFor(PublishedSubject.LOCATION_FIX))
        assertEquals("pixel_6", settings.entityFor(PublishedSubject.AUDIO))
    }

    @Test
    fun `with no rig calibrated the entity falls back to the phone's rather than being empty`() {
        // A blank entity id would produce `rise/@v0//pubsub/...` — a malformed key that publishes
        // perfectly happily and matches nothing anybody subscribes to.
        val settings = settings()
        assertEquals("pixel_6", settings.entityFor(PublishedSubject.FRAME_TRANSFORM))
        assertEquals(
            "pixel_6",
            settings.copy(calibration = RigCalibration.forName("SSRS18").copy(entityId = " "))
                .entityFor(PublishedSubject.FRAME_TRANSFORM),
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
}

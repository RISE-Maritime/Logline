package se.rise.logline

import io.zenoh.qos.CongestionControl
import io.zenoh.qos.Priority
import io.zenoh.qos.Reliability
import se.rise.logline.config.AnnotationButton
import se.rise.logline.config.AnnotationSeverity
import se.rise.logline.config.Settings
import se.rise.logline.config.ThemeChoice
import se.rise.logline.config.applyProfile
import se.rise.logline.config.encode
import se.rise.logline.config.parseSettingsProfile
import se.rise.logline.config.toConnectionProfile
import se.rise.logline.config.toProfile
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.SubjectQos
import se.rise.logline.keelson.Subjects
import se.rise.logline.sensors.SensorRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A profile is a **fleet configuration, not a device clone**, and most of this file is about the
 * difference.
 *
 * Three fields are generated once per install and each breaks something different when copied: the
 * entity id makes two phones publish on identical keys, the operator id makes each read the other's
 * presence heartbeat as its own, and the platform registry origin makes a phone apply its own library back
 * over itself. None of them is in the profile at all, which is stronger than remembering not to write
 * them — but only a test says so out loud.
 */
class SettingsProfileTest {

    /** A phone with an opinion about everything, so nothing round-trips by being at its default. */
    private fun configured() = Settings(
        realm = "rise",
        entityId = "pixel_6",
        routerEndpoints = listOf("tls/router.example.com:443", "tcp/192.168.0.10:7447"),
        locationSource = "gnss",
        imuSource = "imu",
        deviceSource = "box",
        calibrationSource = "survey",
        recordingEnabled = false,
        backfillEnabled = false,
        startOnBoot = true,
        offlineTilesOnly = true,
        mapTilerKey = "test-key-abc123",
        publishEnabled = false,
        audioEnabled = true,
        audioSampleRateHz = 44_100,
        audioChannels = 2,
        cameraEnabled = true,
        cameraLensFront = true,
        cameraWidth = 1920,
        cameraHeight = 1080,
        videoEnabled = true,
        videoWidth = 640,
        videoHeight = 480,
        videoBitrateKbps = 1_000,
        videoKeyframeSeconds = 4,
        disabledSubjects = setOf(PublishedSubject.ILLUMINANCE, PublishedSubject.WIFI_RSSI),
        annotationButtons = listOf(
            AnnotationButton("Man overboard", AnnotationSeverity.Error, "incident"),
        ),
        tags = listOf("quay trial", "engine run"),
        activeTags = setOf("quay trial"),
        scoutAddress = "224.0.0.224:7446",
        qosOverrides = mapOf(
            Subjects.ANGULAR_VELOCITY_RADPS to
                SubjectQos(Priority.REALTIME, CongestionControl.BLOCK, Reliability.BEST_EFFORT, true),
        ),
        sensorRates = mapOf(
            Subjects.LOCATION_FIX to SensorRate.Hz(0.2),
            Subjects.LINEAR_ACCELERATION_MPSS to SensorRate.Max,
        ),
        recordRates = mapOf(
            Subjects.AIR_PRESSURE_PA to SensorRate.Hz(5.0),
            Subjects.ANGULAR_VELOCITY_RADPS to SensorRate.Max,
        ),
        recordAllMax = true,
        publishAllMax = true,
        checklistEnabled = true,
        operatorId = "3f2b0c7e-0000-4000-8000-000000000001",
        operatorName = "Ted",
        operatorRole = "master",
        rocSiteId = "deck",
        checklistRealm = "rise",
        checklistEntityId = "roc1",
        platformRegistryVersion = 42L,
        platformRegistryOrigin = "b7c1-origin-of-this-install",
        batteryExemptionAsked = true,
    )

    /**
     * **The vocabulary travels; which tags are switched on does not.**
     *
     * The words are a configuration, like the annotation buttons, and provisioning a fleet by hand is
     * how two phones end up filing the same trial under different names. *Active* is the state of one
     * run on one phone — importing somebody else's would quietly label this phone's next recording with
     * their afternoon, and that label is written into the file.
     */
    @Test
    fun `a profile carries the tag vocabulary but not the selection`() {
        val thisPhone = configured().copy(tags = listOf("mine"), activeTags = setOf("mine"))
        val fromElsewhere = requireNotNull(
            parseSettingsProfile(
                configured().copy(tags = listOf("theirs", "mine"), activeTags = setOf("theirs"))
                    .toProfile().encode()
            )
        )

        val out = thisPhone.applyProfile(fromElsewhere)

        assertEquals(listOf("theirs", "mine"), out.tags)
        assertEquals("their selection did not come with it", setOf("mine"), out.activeTags)
    }

    /** A tag switched on here but dropped from an imported vocabulary cannot stay on. */
    @Test
    fun `an active tag the imported vocabulary does not have is dropped`() {
        val thisPhone = configured().copy(tags = listOf("gone"), activeTags = setOf("gone"))
        val fromElsewhere = requireNotNull(
            parseSettingsProfile(configured().copy(tags = listOf("kept")).toProfile().encode())
        )

        val out = thisPhone.applyProfile(fromElsewhere)

        assertEquals(listOf("kept"), out.tags)
        assertEquals(emptySet<String>(), out.activeTags)
    }

    private fun roundTrip(settings: Settings): Settings {
        val text = settings.toProfile(withSecrets = true).encode()
        val parsed = requireNotNull(parseSettingsProfile(text)) { "should parse what it just wrote" }
        return settings.applyProfile(parsed)
    }

    /**
     * Field by field rather than comparing whole objects, and deliberately so: a field added to
     * `Settings` later fails this test until somebody decides which side of the line it belongs on.
     * A `copy(...)` comparison would silently carry it either way.
     */
    @Test
    fun `everything shareable survives the round trip`() {
        val original = configured()

        val out = roundTrip(original)

        assertEquals(original.realm, out.realm)
        assertEquals(original.routerEndpoints, out.routerEndpoints)
        assertEquals(original.scoutAddress, out.scoutAddress)
        assertEquals(original.locationSource, out.locationSource)
        assertEquals(original.imuSource, out.imuSource)
        assertEquals(original.deviceSource, out.deviceSource)
        assertEquals(original.calibrationSource, out.calibrationSource)
        assertEquals(original.recordingEnabled, out.recordingEnabled)
        assertEquals(original.backfillEnabled, out.backfillEnabled)
        assertEquals(original.startOnBoot, out.startOnBoot)
        assertEquals(original.offlineTilesOnly, out.offlineTilesOnly)
        // **Carried, but only when asked for.** A tile key is a shared credential rather than an
        // identity, so provisioning a fleet from one export is legitimate — but the file lands in
        // shared storage, so it travels only behind `withSecrets`, which [roundTrip] sets. The default
        // is pinned separately by `a default export carries no credential and nobody's name`.
        assertEquals(original.mapTilerKey, out.mapTilerKey)
        // Added by hand, because this test does not catch a new field on its own — the assertions are
        // a list, not a reflection over the data class. See the note in TODO.md.
        assertEquals(original.publishEnabled, out.publishEnabled)
        assertEquals(original.audioEnabled, out.audioEnabled)
        assertEquals(original.audioSampleRateHz, out.audioSampleRateHz)
        assertEquals(original.audioChannels, out.audioChannels)
        assertEquals(original.cameraEnabled, out.cameraEnabled)
        assertEquals(original.cameraLensFront, out.cameraLensFront)
        assertEquals(original.cameraWidth, out.cameraWidth)
        assertEquals(original.cameraHeight, out.cameraHeight)
        assertEquals(original.videoEnabled, out.videoEnabled)
        assertEquals(original.videoWidth, out.videoWidth)
        assertEquals(original.videoHeight, out.videoHeight)
        assertEquals(original.videoBitrateKbps, out.videoBitrateKbps)
        assertEquals(original.videoKeyframeSeconds, out.videoKeyframeSeconds)
        assertEquals(original.disabledSubjects, out.disabledSubjects)
        assertEquals(original.sensorRates, out.sensorRates)
        // The recording half of the rate split, and the two full-rate modes. A profile that carried the
        // publish rates and silently dropped these would restore a phone that looked configured and
        // recorded the wrong thing.
        assertEquals(original.recordRates, out.recordRates)
        assertEquals(original.recordAllMax, out.recordAllMax)
        assertEquals(original.publishAllMax, out.publishAllMax)
        assertEquals(original.qosOverrides, out.qosOverrides)
        assertEquals(original.annotationButtons, out.annotationButtons)
        assertEquals("the vocabulary travels, like the annotation buttons", original.tags, out.tags)
        assertEquals("and a phone's own selection is left alone", original.activeTags, out.activeTags)
        assertEquals(original.checklistEnabled, out.checklistEnabled)
        assertEquals(original.checklistRealm, out.checklistRealm)
        assertEquals(original.checklistEntityId, out.checklistEntityId)
        assertEquals(original.operatorName, out.operatorName)
        assertEquals(original.operatorRole, out.operatorRole)
        assertEquals(original.rocSiteId, out.rocSiteId)
    }

    /**
     * The test that matters most: a second phone keeps its own identity.
     *
     * Importing a colleague's profile must leave this phone's entity id, operator id and platform registry
     * origin exactly as they were — the three things that tell one install from another on the bus.
     */
    @Test
    fun `importing never touches this phone's own identity`() {
        val thisPhone = Settings(
            realm = "rise",
            entityId = "pixel_9",
            routerEndpoints = listOf("tcp/127.0.0.1:7447"),
            locationSource = "phone",
            imuSource = "phone",
            operatorId = "aaaa-this-phone-only",
            platformRegistryOrigin = "cccc-this-install-only",
            platformRegistryVersion = 7L,
            batteryExemptionAsked = true,
            theme = ThemeChoice.Dark,
        )
        val fromElsewhere = requireNotNull(parseSettingsProfile(configured().toProfile().encode()))

        val out = thisPhone.applyProfile(fromElsewhere)

        assertEquals("pixel_9", out.entityId)
        assertEquals("aaaa-this-phone-only", out.operatorId)
        assertEquals("cccc-this-install-only", out.platformRegistryOrigin)
        assertEquals(7L, out.platformRegistryVersion)
        assertEquals(true, out.batteryExemptionAsked)
        // **The theme is not fleet configuration.** It is a personal display preference like
        // `batteryExemptionAsked` is a device fact, so importing somebody else's profile must not
        // repaint this phone. It survives because `applyProfile` is a `copy` naming only what travels —
        // which is exactly why a new field has to be thought about rather than assumed either way.
        assertEquals(ThemeChoice.Dark, out.theme)
        // …while the shareable half did arrive.
        assertEquals(listOf("tls/router.example.com:443", "tcp/192.168.0.10:7447"), out.routerEndpoints)
    }

    /**
     * The default export carries no credential and nobody's name.
     *
     * An exported profile is written to `Downloads/Logline/config` — shared storage that other apps can
     * read and a cloud client will sync — and it is the file people forward. So the MapTiler key, which
     * somebody is billed for, and the operator identity are both absent unless asked for at the export
     * dialog. This is the assertion that fails if that default is ever flipped.
     */
    @Test
    fun `a default export carries no credential and nobody's name`() {
        val text = configured().toProfile().encode()

        assertFalse("map key leaked", text.contains("test-key-abc123"))
        assertFalse("map key field present", text.contains("maptiler_key"))
        assertFalse("operator name leaked", text.contains("Ted"))
        assertFalse("operator role leaked", text.contains("operator_role"))
        assertFalse("roc site leaked", text.contains("roc_site_id"))

        // …and the shareable half is still there, or this would pass by exporting nothing.
        val parsed = requireNotNull(parseSettingsProfile(text))
        assertEquals("rise", parsed.realm)
        assertNull(parsed.mapTilerKey)
        assertNull(parsed.operatorName)
        assertFalse(parsed.hasOperator)
    }

    /** Belt and braces: the identities must not even appear in the text. */
    @Test
    fun `the exported document contains no identity at all`() {
        val text = configured().toProfile().encode()

        assertFalse("entity id leaked", text.contains("pixel_6"))
        assertFalse("operator id leaked", text.contains("3f2b0c7e-0000-4000-8000-000000000001"))
        assertFalse("platform origin leaked", text.contains("b7c1-origin-of-this-install"))
        assertFalse("registry version leaked", text.contains("\"42\"") || text.contains(": 42"))
    }

    /** One export provisioning several phones should not make them all claim the same person. */
    @Test
    fun `the operator can be left behind on import`() {
        val profile = requireNotNull(
            parseSettingsProfile(configured().toProfile(withSecrets = true).encode())
        )
        val blank = configured().copy(operatorName = "", operatorRole = "", rocSiteId = "")

        val without = blank.applyProfile(profile, withOperator = false)
        assertEquals("", without.operatorName)
        assertEquals("", without.operatorRole)
        assertEquals("", without.rocSiteId)

        val with = blank.applyProfile(profile, withOperator = true)
        assertEquals("Ted", with.operatorName)
        assertTrue(profile.hasOperator)
    }

    /** A file from a later build applies minus what this one does not know. */
    @Test
    fun `an unknown key does not stop the import`() {
        val text = """
            {
              "version": 99,
              "realm": "from-the-future",
              "something_invented_later": { "nested": true }
            }
        """.trimIndent()

        val profile = requireNotNull(parseSettingsProfile(text))

        assertEquals("from-the-future", profile.realm)
        assertEquals("from-the-future", configured().applyProfile(profile).realm)
    }

    /** An absent field leaves the phone's own value alone — the property the QR subset relies on. */
    @Test
    fun `a partial profile changes only what it carries`() {
        val original = configured()
        val connection = requireNotNull(parseSettingsProfile(original.toConnectionProfile().encode()))

        val out = original.copy(realm = "other", audioEnabled = false).applyProfile(connection)

        assertEquals("the realm was carried", "rise", out.realm)
        assertEquals("audio was not", false, out.audioEnabled)
    }

    /**
     * The connection profile has to stay small enough to be a QR that scans.
     *
     * A QR holds a few hundred bytes at a readable module size; past that it becomes a dense square
     * nobody's camera resolves. This fails long before it gets there, so a field added to the
     * connection subset has to be a deliberate decision rather than a surprise at the quayside.
     */
    @Test
    fun `the connection profile fits in a QR`() {
        val compact = configured().toConnectionProfile().encode(pretty = false)

        assertTrue("no pretty-printing in a QR", !compact.contains("\n  "))
        assertTrue("unexpectedly large: ${compact.length} bytes", compact.length < 300)
    }

    /** Nonsense in, null out — not an exception on a screen. */
    @Test
    fun `text that is not a profile reads as none`() {
        assertNull(parseSettingsProfile(""))
        assertNull(parseSettingsProfile("not json at all"))
        assertNull(parseSettingsProfile("[1, 2, 3]"))
        // Valid JSON, no fields: a profile that carries nothing, and so changes nothing.
        val empty = requireNotNull(parseSettingsProfile("{}"))
        assertEquals(configured(), configured().applyProfile(empty))
    }

    /** One unreadable entry costs that subject, not the import. */
    @Test
    fun `a corrupt rate or override is skipped`() {
        val text = """
            {
              "version": 1,
              "realm": "rise",
              "sensor_rates": { "location_fix": "not-a-rate", "air_pressure_pa": "2.0" },
              "qos_overrides": { "audio": { "priority": "NONSENSE", "congestion": "DROP",
                                            "reliability": "RELIABLE", "express": true } }
            }
        """.trimIndent()

        val out = Settings(
            realm = "x",
            entityId = "p",
            routerEndpoints = listOf("tcp/127.0.0.1:7447"),
            locationSource = "phone",
            imuSource = "phone",
        ).applyProfile(requireNotNull(parseSettingsProfile(text)))

        assertEquals(mapOf(Subjects.AIR_PRESSURE_PA to SensorRate.Hz(2.0)), out.sensorRates)
        assertTrue("an unreadable priority drops the whole override", out.qosOverrides.isEmpty())
    }
}

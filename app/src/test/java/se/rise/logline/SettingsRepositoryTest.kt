package se.rise.logline

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import io.zenoh.qos.CongestionControl
import io.zenoh.qos.Priority
import io.zenoh.qos.Reliability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.rise.logline.calibrate.CaptureMethod
import se.rise.logline.calibrate.EulerDeg
import se.rise.logline.calibrate.HeadingSource
import se.rise.logline.calibrate.PlatformCalibration
import se.rise.logline.calibrate.PlatformType
import se.rise.logline.calibrate.PlatformZero
import se.rise.logline.calibrate.SensorMount
import se.rise.logline.calibrate.SensorType
import se.rise.logline.calibrate.Vec3M
import se.rise.logline.calibrate.toStoredJson
import se.rise.logline.config.AnnotationButton
import se.rise.logline.config.AnnotationSeverity
import se.rise.logline.config.Keys
import se.rise.logline.config.Settings
import se.rise.logline.config.ThemeChoice
import se.rise.logline.config.parseDisabledSubjects
import se.rise.logline.config.parseEndpoints
import se.rise.logline.config.readSettings
import se.rise.logline.config.serialiseEndpoints
import se.rise.logline.config.slugifyModel
import se.rise.logline.config.writeSettings
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.SubjectQos
import se.rise.logline.keelson.Subjects
import se.rise.logline.sensors.SensorRate

/**
 * The settings *codec* — the fallbacks around DataStore, not DataStore's own file I/O.
 *
 * Every case here exists because getting it wrong silently changes what a logging run records or, in
 * the stale-preference cases, stops the app from starting at all.
 */
class SettingsRepositoryTest {

    @Test
    fun `empty preferences give the documented defaults`() {
        val settings = readSettings(mutablePreferencesOf(), defaultEntityId = "pixel_6")

        assertEquals("rise", settings.realm)
        assertEquals("pixel_6", settings.entityId)
        assertEquals(listOf("tls/router.example.com:443"), settings.routerEndpoints)
        assertEquals("phone", settings.locationSource)
        assertEquals("phone", settings.imuSource)
        assertTrue(settings.qosOverrides.isEmpty())
        assertTrue(settings.sensorRates.isEmpty())
        // Absent means every subject is on. A new registry entry has to arrive publishing, not silently
        // switched off for everyone who already has a preferences file.
        assertTrue(settings.disabledSubjects.isEmpty())
        // The two subjects that record people rather than measurements. Absent means off, and a
        // regression here would have a phone recording sound and pictures nobody asked it to.
        assertEquals(false, settings.audioEnabled)
        assertEquals(false, settings.cameraEnabled)
        assertEquals(1280, settings.cameraWidth)
        assertEquals(720, settings.cameraHeight)
        // Absent means off, and off means the phone is not announcing itself by name to other sites.
        assertEquals(false, settings.checklistEnabled)
        assertEquals("", settings.operatorId)
        assertEquals("", settings.operatorName)
        // Where crowsnest keeps them — not the logger's own realm and entity, which are `rise` and the
        // device slug. A build that confused the two would publish where nothing is listening.
        assertEquals("crowsnest", settings.checklistRealm)
        assertEquals("checklist", settings.checklistEntityId)
        // Absent means the battery-optimisation question has not been put yet, so the first run asks
        // it. Defaulting the other way would mean a fresh install silently never asks — and the phones
        // that need the exemption are exactly the ones nobody is watching.
        assertEquals(false, settings.batteryExemptionAsked)
        // Off unless somebody asked for it. A phone that begins publishing on its own after a restart
        // is a surprise to anyone who did not set it up that way.
        assertEquals(false, settings.startOnBoot)
        // Online tiles are right wherever there is coverage; offline-only is for the boat.
        assertEquals(false, settings.offlineTilesOnly)
    }

    // ---- checklist identity ----

    /** Nothing is published until there is a person behind it — the id alone is not a person. */
    @Test
    fun `a checklist identity needs both an id and a name`() {
        val base = readSettings(mutablePreferencesOf(), defaultEntityId = "pixel_6")

        assertFalse(base.hasChecklistIdentity())
        assertFalse(base.copy(operatorId = "op-7").hasChecklistIdentity())
        assertFalse(base.copy(operatorName = "Ted").hasChecklistIdentity())
        assertTrue(base.copy(operatorId = "op-7", operatorName = "Ted").hasChecklistIdentity())
    }

    /**
     * The site defaults to the entity id rather than to one of crowsnest's `ROC-*` names: crowsnest
     * discards an incoming event whose site *and* operator both match its own, so a phone sharing a
     * station's name would have its ticks silently ignored there.
     */
    @Test
    fun `the checklist site falls back to the entity id`() {
        val settings = readSettings(mutablePreferencesOf(), defaultEntityId = "pixel_6")

        assertEquals("pixel_6", settings.rocSite())
        assertEquals("deck", settings.copy(rocSiteId = "deck").rocSite())
    }

    /** A blank stored realm must not build `/@v0/...` — it would be a malformed key expression. */
    @Test
    fun `a blank stored checklist realm falls back to the default`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, readSettings(prefs, "pixel_6").copy(checklistRealm = "", checklistEntityId = ""))

        val settings = readSettings(prefs, defaultEntityId = "pixel_6")
        assertEquals("crowsnest", settings.checklistRealm)
        assertEquals("checklist", settings.checklistEntityId)
    }

    // ---- Build.MODEL slug ----

    @Test
    fun `the model becomes a key-safe entity id`() {
        assertEquals("pixel_6", slugifyModel("Pixel 6"))
        assertEquals("sm_g991b", slugifyModel("SM-G991B"))
        assertEquals("pixel_6_pro", slugifyModel("Pixel 6 Pro"))
    }

    @Test
    fun `separators do not leak to the ends of the slug`() {
        // A leading or trailing "_" would produce an empty chunk in the key expression.
        assertEquals("nexus_5", slugifyModel("  Nexus 5!"))
        assertEquals("a1", slugifyModel("-a1-"))
    }

    @Test
    fun `an unusable model falls back to android`() {
        assertEquals("android", slugifyModel(null))
        assertEquals("android", slugifyModel(""))
        assertEquals("android", slugifyModel("???"))
    }

    // ---- round-trip ----

    @Test
    fun `a fully populated settings survives a write and read`() {
        val original = Settings(
            realm = "rise",
            entityId = "boat_1",
            routerEndpoints = listOf("tcp/192.168.0.156:7447"),
            locationSource = "gnss",
            imuSource = "imu",
            qosOverrides = mapOf(
                Subjects.ANGULAR_VELOCITY_RADPS to
                    SubjectQos(Priority.REALTIME, CongestionControl.BLOCK, Reliability.BEST_EFFORT, true),
            ),
            sensorRates = mapOf(
                Subjects.LOCATION_FIX to SensorRate.Hz(0.2),
                Subjects.LINEAR_ACCELERATION_MPSS to SensorRate.Max,
            ),
            cameraEnabled = true,
            cameraLensFront = true,
            cameraWidth = 1920,
            cameraHeight = 1080,
            disabledSubjects = setOf(
                PublishedSubject.WIFI_RSSI,
                PublishedSubject.ILLUMINANCE,
            ),
            checklistEnabled = true,
            operatorId = "3f2b0c7e-0000-4000-8000-000000000001",
            operatorName = "Ted",
            operatorRole = "master",
            rocSiteId = "deck",
            checklistRealm = "crowsnest",
            checklistEntityId = "checklist",
            // Set to the non-default here on purpose: it defaults to false, so a version of this test
            // that left it alone would pass just as well against a write path that never stored it.
            batteryExemptionAsked = true,
            startOnBoot = true,
            offlineTilesOnly = true,
        )

        val prefs = mutablePreferencesOf()
        writeSettings(prefs, original)

        assertEquals(original, readSettings(prefs, defaultEntityId = "ignored"))
    }

    @Test
    fun `clearing an override removes its keys rather than storing a sentinel`() {
        val prefs = mutablePreferencesOf()
        writeSettings(
            prefs,
            settingsWith(
                qos = mapOf(Subjects.LOCATION_FIX to SubjectQos(Priority.DATA_HIGH, CongestionControl.DROP, Reliability.RELIABLE, false)),
                rates = mapOf(Subjects.LOCATION_FIX to SensorRate.Max),
            ),
        )

        writeSettings(prefs, settingsWith())

        assertNull(prefs[Keys.qosPriority(Subjects.LOCATION_FIX)])
        assertNull(prefs[Keys.rateHz(Subjects.LOCATION_FIX)])
        assertTrue(readSettings(prefs, "pixel_6").qosOverrides.isEmpty())
    }

    // ---- stale or half-written preferences ----

    /** The four-key layout exists precisely so a partial set cannot become a real override. */
    @Test
    fun `a partial qos override is ignored`() {
        val prefs = mutablePreferencesOf()
        prefs[Keys.qosPriority(Subjects.LOCATION_FIX)] = "DATA_HIGH"
        prefs[Keys.qosCongestion(Subjects.LOCATION_FIX)] = "DROP"
        prefs[Keys.qosReliability(Subjects.LOCATION_FIX)] = "RELIABLE"
        // express deliberately missing

        assertTrue(readSettings(prefs, "pixel_6").qosOverrides.isEmpty())
    }

    @Test
    fun `an unrecognised enum name falls back to policy instead of throwing`() {
        val prefs = mutablePreferencesOf()
        prefs[Keys.qosPriority(Subjects.LOCATION_FIX)] = "SUPER_HIGH"   // renamed upstream, say
        prefs[Keys.qosCongestion(Subjects.LOCATION_FIX)] = "DROP"
        prefs[Keys.qosReliability(Subjects.LOCATION_FIX)] = "RELIABLE"
        prefs[Keys.qosExpress(Subjects.LOCATION_FIX)] = "false"

        assertTrue(readSettings(prefs, "pixel_6").qosOverrides.isEmpty())
    }

    @Test
    fun `an unusable stored rate falls back to the default`() {
        listOf("abc", "0", "-5", "").forEach { stored ->
            val prefs = mutablePreferencesOf()
            prefs[Keys.rateHz(Subjects.ANGULAR_VELOCITY_RADPS)] = stored

            val settings = readSettings(prefs, "pixel_6")

            assertTrue("stored=$stored", settings.sensorRates.isEmpty())
            assertEquals(SensorRate.Hz(50.0), settings.rate(Subjects.ANGULAR_VELOCITY_RADPS))
        }
    }

    @Test
    fun `one subject's override does not disturb another's`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, settingsWith(rates = mapOf(Subjects.ANGULAR_VELOCITY_RADPS to SensorRate.Hz(10.0))))

        val settings = readSettings(prefs, "pixel_6")

        assertEquals(SensorRate.Hz(10.0), settings.rate(Subjects.ANGULAR_VELOCITY_RADPS))
        assertEquals(SensorRate.Hz(50.0), settings.rate(Subjects.LINEAR_ACCELERATION_MPSS))
        assertEquals(SensorRate.Hz(1.0), settings.rate(Subjects.LOCATION_FIX))
    }

    // ---- annotation buttons ----

    /**
     * Absent and empty must not mean the same thing here, unlike every other list in the file.
     *
     * If both fell back to the defaults, deleting the last button would put three back on the next
     * launch — a setting that will not stay set, and one whose "fix" is to keep deleting them.
     */
    @Test
    fun `absent annotation buttons give the defaults`() {
        val settings = readSettings(mutablePreferencesOf(), defaultEntityId = "pixel_6")

        assertEquals(Settings.DEFAULT_ANNOTATION_BUTTONS, settings.annotationButtons)
    }

    @Test
    fun `an emptied list of annotation buttons stays empty`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, settingsWith(annotationButtons = emptyList()))

        assertTrue(readSettings(prefs, defaultEntityId = "pixel_6").annotationButtons.isEmpty())
    }

    @Test
    fun `annotation buttons survive a write and read`() {
        val buttons = listOf(
            AnnotationButton("Passing buoy 4", AnnotationSeverity.Info, "navigation"),
            AnnotationButton("Wake", AnnotationSeverity.Warning, "incident"),
            AnnotationButton("Engine alarm", AnnotationSeverity.Error, "machinery"),
        )
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, settingsWith(annotationButtons = buttons))

        assertEquals(buttons, readSettings(prefs, defaultEntityId = "pixel_6").annotationButtons)
    }

    /**
     * One corrupt line loses one button, not the whole list and not the app's ability to read its
     * settings — the same stance an unrecognised QoS enum takes.
     */
    @Test
    fun `a corrupt line drops only that button`() {
        val prefs = mutablePreferencesOf()
        prefs[Keys.ANNOTATION_BUTTONS] =
            "Info\tnavigation\tWaypoint\nnonsense\nError\tmachinery\tEngine alarm"

        assertEquals(
            listOf(
                AnnotationButton("Waypoint", AnnotationSeverity.Info, "navigation"),
                AnnotationButton("Engine alarm", AnnotationSeverity.Error, "machinery"),
            ),
            readSettings(prefs, defaultEntityId = "pixel_6").annotationButtons,
        )
    }

    private fun settingsWith(
        qos: Map<String, SubjectQos> = emptyMap(),
        rates: Map<String, SensorRate> = emptyMap(),
        disabled: Set<PublishedSubject> = emptySet(),
        annotationButtons: List<AnnotationButton> = Settings.DEFAULT_ANNOTATION_BUTTONS,
    ) = Settings(
        realm = "rise",
        entityId = "pixel_6",
        routerEndpoints = listOf("tcp/127.0.0.1:7447"),
        locationSource = "phone",
        imuSource = "phone",
        qosOverrides = qos,
        sensorRates = rates,
        disabledSubjects = disabled,
        annotationButtons = annotationButtons,
    )

    // ---- switched-off subjects ----

    /**
     * Keyed by *entry*, not by subject name — which is the whole reason this is a separate set rather
     * than another subject-keyed map. `radio_rssi_dbm` is published from both links, and switching the
     * WiFi one off has to leave the cellular one publishing.
     */
    @Test
    fun `one link can be switched off while the other keeps publishing`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, settingsWith(disabled = setOf(PublishedSubject.WIFI_RSSI)))

        val settings = readSettings(prefs, "pixel_6")

        assertEquals(setOf(PublishedSubject.WIFI_RSSI), settings.disabledSubjects)
        assertEquals(Subjects.RADIO_RSSI_DBM, PublishedSubject.CELLULAR_RSSI.subject)
        assertTrue(PublishedSubject.CELLULAR_RSSI !in settings.disabledSubjects)
    }

    /**
     * Renaming or removing a registry entry is legal, and a preferences file written by an older build
     * must not be able to stop the app reading its settings.
     */
    @Test
    fun `an unknown entry name is dropped rather than thrown on`() {
        assertEquals(
            setOf(PublishedSubject.ILLUMINANCE),
            parseDisabledSubjects("ILLUMINANCE\nWHAT_IS_THIS\n"),
        )
        assertTrue(parseDisabledSubjects(null).isEmpty())
        assertTrue(parseDisabledSubjects("").isEmpty())
    }

    @Test
    fun `switching a subject back on removes it from the stored set`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, settingsWith(disabled = setOf(PublishedSubject.AIR_PRESSURE)))

        writeSettings(prefs, settingsWith())

        assertTrue(readSettings(prefs, "pixel_6").disabledSubjects.isEmpty())
    }

    // ---- what the publisher and the UI both read ----

    /**
     * Audio and the camera carry their own enable flag because they also decide a foreground-service
     * type. `offSubjects()` is the one place the two kinds are folded together, so the publisher's gate
     * and the UI's "Off" rows cannot disagree.
     */
    @Test
    fun `audio, the camera and an uncalibrated platform count as off without being in the set`() {
        val settings = settingsWith()

        assertEquals(
            setOf(
                PublishedSubject.AUDIO,
                PublishedSubject.IMAGE_COMPRESSED,
                // Video is off by default for the same reason as the two above it.
                PublishedSubject.VIDEO_COMPRESSED,
                // Nothing calibrated: the two platform subjects have nothing to say, so they read as off
                // rather than going stale on a screen while publishing nothing.
                PublishedSubject.FRAME_TRANSFORM,
                PublishedSubject.CONFIGURATION_JSON,
                PublishedSubject.CALIBRATION_ZERO,
            ),
            settings.offSubjects(),
        )
    }

    @Test
    fun `a calibrated platform takes its two subjects out of the off set`() {
        val settings = settingsWith().withLibrary(
            PlatformCalibration.forName("Sealog").copy(
                sensors = listOf(
                    SensorMount(
                        label = "Lidar",
                        frameId = "sealog-frame-lidar",
                        sensorType = SensorType.LIDAR,
                        translation = Vec3M(0.22, 0.0, 0.0),
                    )
                ),
            ),
        )

        assertFalse(PublishedSubject.FRAME_TRANSFORM in settings.offSubjects())
        assertFalse(PublishedSubject.CONFIGURATION_JSON in settings.offSubjects())
        // ...but a platform measured with a tape has no surveyed position to anchor them with, and must not
        // publish 0N 0E as if it had one.
        assertTrue(PublishedSubject.CALIBRATION_ZERO in settings.offSubjects())
    }

    @Test
    fun `a surveyed zero is what puts the platform position on the bus`() {
        val settings = settingsWith().withLibrary(platform())

        assertFalse(PublishedSubject.CALIBRATION_ZERO in settings.offSubjects())
    }

    /** A platform named but never populated publishes nothing — there are no sensors to describe. */
    @Test
    fun `a platform with no sensors stays off`() {
        val settings = settingsWith().withLibrary(PlatformCalibration.forName("Sealog"))

        assertTrue(PublishedSubject.FRAME_TRANSFORM in settings.offSubjects())
    }

    @Test
    fun `switching audio on takes it out of the off set and leaves the rest`() {
        val settings = settingsWith(disabled = setOf(PublishedSubject.ANGULAR_VEL))
            .copy(audioEnabled = true, cameraEnabled = true)

        assertEquals(
            setOf(
                PublishedSubject.ANGULAR_VEL,
                PublishedSubject.VIDEO_COMPRESSED,
                PublishedSubject.FRAME_TRANSFORM,
                PublishedSubject.CONFIGURATION_JSON,
                PublishedSubject.CALIBRATION_ZERO,
            ),
            settings.offSubjects(),
        )
    }

    /** Belt and braces: a set is a set, so the two ways of being off cannot double up. */
    @Test
    fun `audio switched off in both places is off once`() {
        val settings = settingsWith(disabled = setOf(PublishedSubject.AUDIO))

        assertEquals(
            setOf(
                PublishedSubject.AUDIO,
                PublishedSubject.IMAGE_COMPRESSED,
                PublishedSubject.VIDEO_COMPRESSED,
                PublishedSubject.FRAME_TRANSFORM,
                PublishedSubject.CONFIGURATION_JSON,
                PublishedSubject.CALIBRATION_ZERO,
            ),
            settings.offSubjects(),
        )
    }

    // ---- platform calibration ----

    private fun platform() = PlatformCalibration(
        name = "Sealog",
        entityId = "sealog",
        parentFrameId = "sealog-frame-ccrp",
        platformType = PlatformType.VESSEL,
        description = "Small USV test platform",
        lengthOverAllM = 1.8,
        breadthOverAllM = 0.45,
        ccrp = Vec3M(0.1, 0.0, -0.2),
        zero = PlatformZero(
            latitude = 57.708912,
            longitude = 11.974560,
            altitudeM = 12.5,
            accuracyM = 3.4,
            scatterM = 0.42,
            headingDeg = 35.0,
            headingSource = HeadingSource.BASELINE,
            capture = CaptureMethod.GNSS_AVERAGE,
            samples = 60,
            capturedAtEpochMillis = 1_700_000_000_000L,
        ),
        sensors = listOf(
            SensorMount(
                label = "Ouster OS lidar",
                frameId = "sealog-frame-lidar",
                sensorType = SensorType.LIDAR,
                translation = Vec3M(0.22, 0.0, -0.35),
                rotation = EulerDeg(yaw = 90.0, pitch = 0.0, roll = -175.0),
                capture = CaptureMethod.GNSS_AVERAGE,
                accuracyM = 3.2,
                capturedAtEpochMillis = 1_700_000_000_500L,
            ),
            SensorMount(
                label = "Rutx GNSS antenna",
                frameId = "sealog-frame-gnss",
                sensorType = SensorType.GNSS,
                translation = Vec3M(0.27, 0.0, 0.0),
            ),
        ),
        updatedAtEpochMillis = 1_700_000_001_000L,
    )

    /** A second platform, so the tests that matter are about a *library* rather than about one platform. */
    private fun otherPlatform() = PlatformCalibration.forName("Stora Krabban", atEpochMillis = 1_700_000_002_000L)
        .copy(
            sensors = listOf(
                SensorMount(
                    label = "Radar",
                    frameId = "stora-krabban-frame-radar",
                    sensorType = SensorType.RADAR,
                    translation = Vec3M(0.08, 0.0, 0.105),
                ),
            ),
        )

    private fun Settings.withLibrary(vararg platforms: PlatformCalibration) = copy(
        platforms = platforms.toList(),
        activePlatformEntityId = platforms.firstOrNull()?.entityId.orEmpty(),
    )

    @Test
    fun `a platform survives a write and a read unchanged`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, settingsWith().withLibrary(platform()))

        assertEquals(listOf(platform()), readSettings(prefs, defaultEntityId = "pixel_6").platforms)
    }

    /**
     * The whole point of the change, and the thing a single-platform store could not express.
     *
     * The provenance travels too — capture method, accuracy and time per sensor — because the stored
     * form is the *wire* document plus identity, not the strict export. Losing it would turn every
     * reload into a platform whose offsets all claim to have been typed.
     */
    @Test
    fun `several platforms survive a write and a read, provenance and all`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, settingsWith().withLibrary(platform(), otherPlatform()))

        val read = readSettings(prefs, defaultEntityId = "pixel_6").platforms
        assertEquals(listOf(platform(), otherPlatform()), read)
        assertEquals(CaptureMethod.GNSS_AVERAGE, read.first().sensors.first().capture)
        assertEquals(3.2, read.first().sensors.first().accuracyM!!, 1e-9)
        assertEquals(HeadingSource.BASELINE, read.first().zero!!.headingSource)
    }

    @Test
    fun `the active platform and the publishing set survive a round trip`() {
        val prefs = mutablePreferencesOf()
        writeSettings(
            prefs,
            settingsWith().copy(
                platforms = listOf(platform(), otherPlatform()),
                activePlatformEntityId = "stora-krabban",
                publishingPlatformEntityIds = setOf("sealog"),
            ),
        )

        val read = readSettings(prefs, defaultEntityId = "pixel_6")
        assertEquals("stora-krabban", read.activePlatformEntityId)
        assertEquals(setOf("sealog"), read.publishingPlatformEntityIds)
        // Both, because the active platform is included whether or not it is in the set.
        assertEquals(listOf("sealog", "stora-krabban"), read.publishingPlatforms().map { it.entityId })
    }

    @Test
    fun `no platforms reads as an empty library, not as one empty platform`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, settingsWith())

        assertEquals(emptyList<PlatformCalibration>(), readSettings(prefs, defaultEntityId = "pixel_6").platforms)
    }

    /**
     * The one that bites: platforms are stored under indexed keys, so a library that loses one has to have
     * the higher index *removed*. Left behind, the deleted platform is absent from the screen, present in
     * the file, and publishing geometry under its own entity id at the next start.
     */
    @Test
    fun `deleting a platform does not leave it behind in storage`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, settingsWith().withLibrary(platform(), otherPlatform()))
        writeSettings(prefs, settingsWith().withLibrary(platform()))

        assertEquals(listOf(platform()), readSettings(prefs, defaultEntityId = "pixel_6").platforms)
        assertNull(prefs[Keys.platform(1)])
    }

    /** An emptied library is a decision, not a missing file — it must not fall back to the old keys. */
    @Test
    fun `emptying the library empties it`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, settingsWith().withLibrary(platform()))
        writeSettings(prefs, settingsWith())

        assertEquals(emptyList<PlatformCalibration>(), readSettings(prefs, defaultEntityId = "pixel_6").platforms)
    }

    /**
     * One unreadable platform costs one platform.
     *
     * The same stance `readQosOverrides` takes: a preferences file half-written by a crash should not
     * take every other setting on the phone with it.
     */
    @Test
    fun `a corrupt platform is skipped and the rest of the library survives`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, settingsWith().withLibrary(platform(), otherPlatform()))
        prefs[Keys.platform(0)] = "{ not json"

        assertEquals(listOf(otherPlatform()), readSettings(prefs, defaultEntityId = "pixel_6").platforms)
    }

    /**
     * Storage folds rotations into `[-180, 180]`, because what it stores is the wire document.
     *
     * A typed 270° is a legal thing to mean and an illegal thing to publish, so the writer normalises
     * it — and the stored form is that same document, which makes the fold happen on save rather than
     * only on the way to the bus. Worth pinning: it is the one field that does not come back byte for
     * byte, and `-180` and `180` being the same rotation is why that is acceptable.
     */
    @Test
    fun `a rotation outside the schema's range is folded on the way into storage`() {
        val prefs = mutablePreferencesOf()
        val turned = platform().copy(
            sensors = platform().sensors.take(1).map {
                it.copy(rotation = EulerDeg(yaw = 270.0, pitch = 0.0, roll = -180.0))
            },
        )
        writeSettings(prefs, settingsWith().withLibrary(turned))

        val read = readSettings(prefs, defaultEntityId = "pixel_6").platforms.single()
        assertEquals(EulerDeg(yaw = -90.0, pitch = 0.0, roll = 180.0), read.sensors.single().rotation)
    }

    /**
     * The colour scheme round-trips, and an unreadable one follows the phone.
     *
     * `System` rather than a fixed scheme for the fallback: a stale or misspelled preference should
     * leave the phone doing what it was doing, not force a scheme somebody did not choose. The same
     * stance `readQosOverrides` takes for a Zenoh enum renamed between versions.
     */
    @Test
    fun `the theme round-trips and an unknown value follows the phone`() {
        val prefs = mutablePreferencesOf()

        assertEquals("absent means follow the phone", ThemeChoice.System, readSettings(prefs, "pixel_6").theme)

        writeSettings(prefs, settingsWith().copy(theme = ThemeChoice.Dark))
        assertEquals(ThemeChoice.Dark, readSettings(prefs, "pixel_6").theme)

        prefs[Keys.THEME] = "Sepia"
        assertEquals(ThemeChoice.System, readSettings(prefs, "pixel_6").theme)
    }

    // ---- migration from the single-platform keys ----

    /** A preferences file as an older build left it: the flat `calib_*` keys and no `platform_count`. */
    private fun writeLegacyPlatform(prefs: MutablePreferences) {
        prefs[Keys.CALIB_NAME] = "Sealog"
        prefs[Keys.CALIB_ENTITY_ID] = "sealog"
        prefs[Keys.CALIB_PARENT_FRAME_ID] = "sealog-frame-ccrp"
        prefs[Keys.CALIB_PLATFORM_TYPE] = "VESSEL"
        prefs[Keys.CALIB_ZERO_LAT] = "57.708912"
        prefs[Keys.CALIB_ZERO_LON] = "11.974560"
        prefs[Keys.CALIB_ZERO_HEADING] = "35.0"
        prefs[Keys.CALIB_ZERO_HEADING_SOURCE] = "BASELINE"
        prefs[Keys.CALIB_ZERO_CAPTURE] = "GNSS_AVERAGE"
        prefs[Keys.CALIB_SENSOR_COUNT] = "1"
        prefs[Keys.calibSensor(0, "label")] = "Ouster OS lidar"
        prefs[Keys.calibSensor(0, "frame_id")] = "sealog-frame-lidar"
        prefs[Keys.calibSensor(0, "type")] = "LIDAR"
        prefs[Keys.calibSensor(0, "x")] = "0.22"
        prefs[Keys.calibSensor(0, "y")] = "0.0"
        prefs[Keys.calibSensor(0, "z")] = "-0.35"
    }

    /**
     * The upgrade, against bytes the **previous build actually wrote**.
     *
     * `writeLegacyCalibration` is that build's writer lifted verbatim out of git, so this exercises the
     * real key names and the real value formats rather than a hand-written imitation that could agree
     * with the migration while both disagreed with the field.
     */
    @Test
    fun `a platform written by the previous build survives the upgrade whole`() {
        val prefs = mutablePreferencesOf()
        writeLegacyCalibration(prefs, platform())

        val read = readSettings(prefs, defaultEntityId = "pixel_6")

        assertEquals(listOf(platform()), read.platforms)
        assertEquals("sealog", read.activePlatformEntityId)
        assertEquals(listOf("sealog"), read.publishingPlatforms().map { it.entityId })
    }

    /**
     * The upgrade. A phone that had a platform configured must still have it, still selected, and still
     * publishing — an update that silently stops geometry going out is the one outcome a migration
     * must not produce.
     */
    @Test
    fun `a single platform from an older build migrates into the library and stays active`() {
        val prefs = mutablePreferencesOf()
        writeLegacyPlatform(prefs)

        val read = readSettings(prefs, defaultEntityId = "pixel_6")
        assertEquals(listOf("sealog"), read.platforms.map { it.entityId })
        assertEquals("sealog", read.activePlatformEntityId)
        assertEquals(listOf("sealog"), read.publishingPlatforms().map { it.entityId })
        assertEquals("sealog-frame-lidar", read.platforms.single().sensors.single().frameId)
    }

    /** Migrated once and then gone: two sources of truth for one platform is how the two drift apart. */
    @Test
    fun `the first save after a migration clears the old keys`() {
        val prefs = mutablePreferencesOf()
        writeLegacyPlatform(prefs)
        val migrated = readSettings(prefs, defaultEntityId = "pixel_6")

        writeSettings(prefs, migrated)

        assertNull(prefs[Keys.CALIB_NAME])
        assertNull(prefs[Keys.CALIB_SENSOR_COUNT])
        assertNull(prefs[Keys.calibSensor(0, "frame_id")])
        assertEquals(listOf("sealog"), readSettings(prefs, defaultEntityId = "pixel_6").platforms.map { it.entityId })
    }

    /**
     * A cleared selection stays cleared across a restart.
     *
     * Deleting the active platform leaves the selection empty, and the migration fallback must not read
     * that as "never configured" and nominate whatever platform survived — the active platform always
     * publishes, so a surviving platform would start putting its geometry on the bus under its own entity
     * id with nobody having asked for it.
     */
    @Test
    fun `deleting the active platform does not re-activate another one on the next read`() {
        val prefs = mutablePreferencesOf()
        val two = settingsWith().copy(
            platforms = listOf(platform(), otherPlatform()),
            activePlatformEntityId = "sealog",
        )
        writeSettings(prefs, two)
        writeSettings(prefs, two.removePlatform("sealog"))

        val read = readSettings(prefs, defaultEntityId = "pixel_6")
        assertEquals(listOf("stora-krabban"), read.platforms.map { it.entityId })
        assertEquals("", read.activePlatformEntityId)
        assertEquals(emptyList<PlatformCalibration>(), read.publishingPlatforms())
    }

    /** No calibration in the old file means an empty library, not a platform called nothing. */
    @Test
    fun `an older build with no calibration migrates to an empty library`() {
        val prefs = mutablePreferencesOf()

        val read = readSettings(prefs, defaultEntityId = "pixel_6")
        assertEquals(emptyList<PlatformCalibration>(), read.platforms)
        assertEquals("", read.activePlatformEntityId)
    }

    // ---- migration from the `rig_*` key names ----

    /**
     * A preferences file as the build before the rename left it: the library under `rig_*`, no
     * `platform_*` anywhere. Written by hand rather than by lifting the old writer, because the only
     * thing that changed is the six names — the stored JSON is `toStoredJson()` either way, and
     * `a platform written by the previous build survives the upgrade whole` already pins that format.
     */
    private fun writeLegacyRigLibrary(prefs: MutablePreferences, settings: Settings) {
        settings.platforms.forEachIndexed { i, p -> prefs[Keys.legacyRig(i)] = p.toStoredJson() }
        prefs[Keys.LEGACY_RIG_COUNT] = settings.platforms.size.toString()
        prefs[Keys.LEGACY_RIG_ACTIVE_ENTITY] = settings.activePlatformEntityId
        prefs[Keys.LEGACY_RIG_PUBLISHING] =
            settings.publishingPlatformEntityIds.sorted().joinToString("\n")
        prefs[Keys.LEGACY_RIG_SHARE_LIBRARY] = settings.sharePlatformLibrary.toString()
        prefs[Keys.LEGACY_RIG_REGISTRY_VERSION] = settings.platformRegistryVersion.toString()
        prefs[Keys.LEGACY_RIG_REGISTRY_ORIGIN] = settings.platformRegistryOrigin
    }

    /**
     * The rename must not cost anybody their library.
     *
     * `rig` became `platform` because that is what keelson calls the thing, and the DataStore keys moved
     * with the word — so without this fallback a phone updating over an existing install would read no
     * library at all, and the active platform's geometry would silently stop going out. Same rule as the
     * `calib_*` migration beside it: an update that stops publishing what was publishing before is the
     * one outcome a migration must not produce.
     */
    @Test
    fun `a library stored under the old rig keys survives the rename whole`() {
        val prefs = mutablePreferencesOf()
        writeLegacyRigLibrary(
            prefs,
            settingsWith().copy(
                platforms = listOf(platform(), otherPlatform()),
                activePlatformEntityId = "sealog",
                publishingPlatformEntityIds = setOf("stora-krabban"),
                sharePlatformLibrary = true,
                platformRegistryVersion = 7L,
                platformRegistryOrigin = "abc123",
            ),
        )

        val read = readSettings(prefs, defaultEntityId = "pixel_6")

        assertEquals(listOf(platform(), otherPlatform()), read.platforms)
        assertEquals("sealog", read.activePlatformEntityId)
        assertEquals(setOf("stora-krabban"), read.publishingPlatformEntityIds)
        // The active platform always publishes, whether or not it is in the opted-in set.
        assertEquals(
            listOf("sealog", "stora-krabban"),
            read.publishingPlatforms().map { it.entityId }.sorted(),
        )
        assertTrue(read.sharePlatformLibrary)
        assertEquals(7L, read.platformRegistryVersion)
        assertEquals("abc123", read.platformRegistryOrigin)
    }

    /**
     * **`rig_count` present and zero is an emptied library, not an absent one.**
     *
     * The same trap the `calib_*` migration has: falling through to an older scheme when a count says
     * zero resurrects platforms somebody deleted. Here the older scheme is the flat single calibration,
     * which a phone that had used the platform library may well still carry.
     */
    @Test
    fun `an emptied library under the old rig keys does not resurrect the single calibration`() {
        val prefs = mutablePreferencesOf()
        writeLegacyPlatform(prefs)
        prefs[Keys.LEGACY_RIG_COUNT] = "0"

        val read = readSettings(prefs, defaultEntityId = "pixel_6")

        assertEquals(emptyList<PlatformCalibration>(), read.platforms)
    }

    /** Migrated once and then gone, the same one-way move the `calib_*` keys make. */
    @Test
    fun `the first save after the rename clears the old rig keys`() {
        val prefs = mutablePreferencesOf()
        writeLegacyRigLibrary(
            prefs,
            settingsWith().copy(
                platforms = listOf(platform(), otherPlatform()),
                activePlatformEntityId = "sealog",
            ),
        )
        val migrated = readSettings(prefs, defaultEntityId = "pixel_6")

        writeSettings(prefs, migrated)

        assertNull(prefs[Keys.LEGACY_RIG_COUNT])
        assertNull(prefs[Keys.legacyRig(0)])
        assertNull(prefs[Keys.legacyRig(1)])
        assertNull(prefs[Keys.LEGACY_RIG_ACTIVE_ENTITY])
        assertNull(prefs[Keys.LEGACY_RIG_PUBLISHING])
        assertNull(prefs[Keys.LEGACY_RIG_SHARE_LIBRARY])
        assertNull(prefs[Keys.LEGACY_RIG_REGISTRY_VERSION])
        assertNull(prefs[Keys.LEGACY_RIG_REGISTRY_ORIGIN])
        assertEquals(
            listOf("sealog", "stora-krabban"),
            readSettings(prefs, defaultEntityId = "pixel_6").platforms.map { it.entityId },
        )
    }

    /**
     * A leftover `rig_N` past the end of the new library is cleared too.
     *
     * Merging a shared library can leave fewer platforms than the file held, so the count to walk is the
     * **old** one — clearing only as far as the saved library reaches would leave an orphan sitting there
     * forever, and it would come back the day `platform_count` was ever absent again.
     */
    @Test
    fun `a shrunken library clears the old indices past its end`() {
        val prefs = mutablePreferencesOf()
        writeLegacyRigLibrary(
            prefs,
            settingsWith().copy(platforms = listOf(platform(), otherPlatform())),
        )

        writeSettings(prefs, settingsWith().copy(platforms = listOf(platform())))

        assertNull(prefs[Keys.legacyRig(0)])
        assertNull(prefs[Keys.legacyRig(1)])
    }

    /**
     * A selection somebody cleared stays cleared across the rename.
     *
     * `rig_active_entity` present and empty means the active platform was deleted; only its *absence*
     * means the file predates the library. Reading the fallback with `ifBlank` rather than `?:` would
     * re-activate a surviving platform and start its geometry going out unasked — the same distinction
     * the `calib_*` path makes, now with one more link in the chain to get it wrong in.
     */
    @Test
    fun `an empty active entity under the old rig keys is not re-nominated`() {
        val prefs = mutablePreferencesOf()
        writeLegacyRigLibrary(
            prefs,
            settingsWith().copy(platforms = listOf(platform()), activePlatformEntityId = ""),
        )

        val read = readSettings(prefs, defaultEntityId = "pixel_6")

        assertEquals(listOf("sealog"), read.platforms.map { it.entityId })
        assertEquals("", read.activePlatformEntityId)
        assertEquals(emptyList<PlatformCalibration>(), read.publishingPlatforms())
    }

    // ---- endpoint list ----

    /**
     * The migration. A preference written by a build that stored one locator must come back as a
     * one-element list, not an empty one — otherwise upgrading strands the user's configured router.
     */
    @Test
    fun `a single stored endpoint migrates to a one-element list`() {
        assertEquals(listOf("tcp/192.168.0.156:7447"), parseEndpoints("tcp/192.168.0.156:7447"))
    }

    @Test
    fun `several endpoints round-trip in order`() {
        val endpoints = listOf("tcp/192.168.0.5:7447", "tls/router.example.com:443")

        assertEquals(endpoints, parseEndpoints(endpoints.serialiseEndpoints()))
    }

    /** Nothing stored, or only whitespace, falls back to the cloud router rather than an empty list. */
    @Test
    fun `an absent or blank endpoint setting falls back to the default`() {
        assertEquals(listOf(Settings.DEFAULT_ENDPOINT), parseEndpoints(null))
        assertEquals(listOf(Settings.DEFAULT_ENDPOINT), parseEndpoints(""))
        assertEquals(listOf(Settings.DEFAULT_ENDPOINT), parseEndpoints("   \n  \n"))
    }

    @Test
    fun `blank lines and stray whitespace are dropped`() {
        assertEquals(
            listOf("tcp/a:1", "tcp/b:2"),
            parseEndpoints("  tcp/a:1  \n\n tcp/b:2\n"),
        )
    }

    /**
     * The regression for a real bug: the settings screen rebuilt `Settings` from scratch instead of
     * copying, so saving any general setting silently wiped every per-sensor rate. This asserts the
     * shape of what the screen now emits — a copy that carries the untouched fields through.
     */
    @Test
    fun `editing general settings preserves per-sensor rates`() {
        val original = settingsWith().copy(
            sensorRates = mapOf(Subjects.ANGULAR_VELOCITY_RADPS to SensorRate.Hz(100.0)),
            deviceSource = "device",
        )

        // What SettingsScreen's Save does now.
        val edited = original.copy(realm = "other", routerEndpoints = listOf("tcp/a:1"))

        assertEquals(original.sensorRates, edited.sensorRates)
        assertEquals("device", edited.deviceSource)
    }
}

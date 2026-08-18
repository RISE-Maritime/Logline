package se.rise.logline

import androidx.datastore.preferences.core.mutablePreferencesOf
import io.zenoh.qos.CongestionControl
import io.zenoh.qos.Priority
import io.zenoh.qos.Reliability
import se.rise.logline.calibrate.CaptureMethod
import se.rise.logline.calibrate.EulerDeg
import se.rise.logline.calibrate.HeadingSource
import se.rise.logline.calibrate.PlatformType
import se.rise.logline.calibrate.RigCalibration
import se.rise.logline.calibrate.RigZero
import se.rise.logline.calibrate.SensorMount
import se.rise.logline.calibrate.SensorType
import se.rise.logline.calibrate.Vec3M
import se.rise.logline.config.Keys
import se.rise.logline.config.AnnotationButton
import se.rise.logline.config.AnnotationSeverity
import se.rise.logline.config.Settings
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `audio, the camera and an uncalibrated rig count as off without being in the set`() {
        val settings = settingsWith()

        assertEquals(
            setOf(
                PublishedSubject.AUDIO,
                PublishedSubject.IMAGE_COMPRESSED,
                // Nothing calibrated: the two rig subjects have nothing to say, so they read as off
                // rather than going stale on a screen while publishing nothing.
                PublishedSubject.FRAME_TRANSFORM,
                PublishedSubject.CONFIGURATION_JSON,
                PublishedSubject.CALIBRATION_ZERO,
            ),
            settings.offSubjects(),
        )
    }

    @Test
    fun `a calibrated rig takes its two subjects out of the off set`() {
        val settings = settingsWith().copy(
            calibration = RigCalibration.forName("SSRS18").copy(
                sensors = listOf(
                    SensorMount(
                        label = "Lidar",
                        frameId = "ssrs18-frame-lidar",
                        sensorType = SensorType.LIDAR,
                        translation = Vec3M(0.22, 0.0, 0.0),
                    )
                ),
            ),
        )

        assertFalse(PublishedSubject.FRAME_TRANSFORM in settings.offSubjects())
        assertFalse(PublishedSubject.CONFIGURATION_JSON in settings.offSubjects())
        // ...but a rig measured with a tape has no surveyed position to anchor them with, and must not
        // publish 0N 0E as if it had one.
        assertTrue(PublishedSubject.CALIBRATION_ZERO in settings.offSubjects())
    }

    @Test
    fun `a surveyed zero is what puts the rig position on the bus`() {
        val settings = settingsWith().copy(calibration = rig())

        assertFalse(PublishedSubject.CALIBRATION_ZERO in settings.offSubjects())
    }

    /** A rig named but never populated publishes nothing — there are no sensors to describe. */
    @Test
    fun `a rig with no sensors stays off`() {
        val settings = settingsWith().copy(calibration = RigCalibration.forName("SSRS18"))

        assertTrue(PublishedSubject.FRAME_TRANSFORM in settings.offSubjects())
    }

    @Test
    fun `switching audio on takes it out of the off set and leaves the rest`() {
        val settings = settingsWith(disabled = setOf(PublishedSubject.ANGULAR_VEL))
            .copy(audioEnabled = true, cameraEnabled = true)

        assertEquals(
            setOf(
                PublishedSubject.ANGULAR_VEL,
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
                PublishedSubject.FRAME_TRANSFORM,
                PublishedSubject.CONFIGURATION_JSON,
                PublishedSubject.CALIBRATION_ZERO,
            ),
            settings.offSubjects(),
        )
    }

    // ---- rig calibration ----

    private fun rig() = RigCalibration(
        name = "SSRS18",
        entityId = "ssrs18",
        parentFrameId = "ssrs18-frame-ccrp",
        platformType = PlatformType.VESSEL,
        description = "Small USV test platform",
        lengthOverAllM = 1.8,
        breadthOverAllM = 0.45,
        ccrp = Vec3M(0.1, 0.0, -0.2),
        zero = RigZero(
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
                frameId = "ssrs18-frame-lidar",
                sensorType = SensorType.LIDAR,
                translation = Vec3M(0.22, 0.0, -0.35),
                rotation = EulerDeg(yaw = 90.0, pitch = 0.0, roll = -180.0),
                capture = CaptureMethod.GNSS_AVERAGE,
                accuracyM = 3.2,
                capturedAtEpochMillis = 1_700_000_000_500L,
            ),
            SensorMount(
                label = "Rutx GNSS antenna",
                frameId = "ssrs18-frame-gnss",
                sensorType = SensorType.GNSS,
                translation = Vec3M(0.27, 0.0, 0.0),
            ),
        ),
        updatedAtEpochMillis = 1_700_000_001_000L,
    )

    @Test
    fun `a calibration survives a write and a read unchanged`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, settingsWith().copy(calibration = rig()))

        assertEquals(rig(), readSettings(prefs, defaultEntityId = "pixel_6").calibration)
    }

    @Test
    fun `no calibration reads as none, not as an empty rig`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, settingsWith())

        assertNull(readSettings(prefs, defaultEntityId = "pixel_6").calibration)
    }

    @Test
    fun `clearing a calibration removes it rather than leaving a husk`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, settingsWith().copy(calibration = rig()))
        writeSettings(prefs, settingsWith().copy(calibration = null))

        assertNull(readSettings(prefs, defaultEntityId = "pixel_6").calibration)
    }

    /**
     * The one that bites: the sensor keys are indexed, so a rig that loses a sensor has to have the
     * higher indices *removed*. Left behind, the deleted sensor is absent from the screen, present in
     * the file, and published on the bus at the next start.
     */
    @Test
    fun `deleting a sensor does not leave it behind in storage`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, settingsWith().copy(calibration = rig()))

        val trimmed = rig().copy(sensors = rig().sensors.take(1))
        writeSettings(prefs, settingsWith().copy(calibration = trimmed))

        val read = readSettings(prefs, defaultEntityId = "pixel_6").calibration!!
        assertEquals(1, read.sensors.size)
        assertEquals("ssrs18-frame-lidar", read.sensors.single().frameId)
        assertNull(prefs[Keys.calibSensor(1, "frame_id")])
    }

    /**
     * A mount missing a translation component is skipped, not read as zeros.
     *
     * Zeros would put the sensor exactly at the rig's origin — a plausible-looking position that
     * nothing downstream could tell from a real measurement.
     */
    @Test
    fun `a half-written sensor is skipped rather than read as sitting at the origin`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, settingsWith().copy(calibration = rig()))
        prefs.remove(Keys.calibSensor(0, "z"))

        val read = readSettings(prefs, defaultEntityId = "pixel_6").calibration!!
        assertEquals(listOf("ssrs18-frame-gnss"), read.sensors.map { it.frameId })
    }

    @Test
    fun `a calibration with no name at all is no calibration`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, settingsWith().copy(calibration = rig()))
        prefs.remove(Keys.CALIB_NAME)

        assertNull(readSettings(prefs, defaultEntityId = "pixel_6").calibration)
    }

    /** Half a position is no position: a lone latitude would put the rig on the Greenwich meridian. */
    @Test
    fun `a zero missing its longitude is dropped, and the rig survives without it`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, settingsWith().copy(calibration = rig()))
        prefs.remove(Keys.CALIB_ZERO_LON)

        val read = readSettings(prefs, defaultEntityId = "pixel_6").calibration!!
        assertNull(read.zero)
        assertEquals(2, read.sensors.size)
    }

    @Test
    fun `a stale enum from an older build falls back rather than crashing`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, settingsWith().copy(calibration = rig()))
        prefs[Keys.calibSensor(0, "type")] = "SONAR"
        prefs[Keys.CALIB_PLATFORM_TYPE] = "SUBMARINE"

        val read = readSettings(prefs, defaultEntityId = "pixel_6").calibration!!
        assertEquals(SensorType.OTHER, read.sensors.first().sensorType)
        assertNull(read.platformType)
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

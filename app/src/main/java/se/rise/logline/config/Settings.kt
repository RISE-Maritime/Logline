package se.rise.logline.config

import se.rise.logline.calibrate.RigCalibration
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.SourceKind
import se.rise.logline.keelson.SubjectQos
import se.rise.logline.sensors.SensorRate

data class Settings(
    val realm: String,
    val entityId: String,
    /**
     * Router locators, tried in order. Zenoh's client mode attaches to whichever answers first, so
     * this is failover, not fan-out — the session ends up on exactly one router.
     *
     * Keep the list short and put the likeliest first: with the client default `connect.timeout_ms: 0`
     * there is no retry, and an endpoint that silently blackholes costs up to the 10 s transport open
     * timeout before the next is tried. A *refused* connection fails instantly; a dropped one does not.
     */
    val routerEndpoints: List<String>,
    val locationSource: String,
    val imuSource: String,
    /** Source id for what is neither GNSS nor IMU — the barometer and the battery. */
    val deviceSource: String = DEFAULT_DEVICE_SOURCE,
    /** Source id for the rig geometry. Names the survey, not a piece of hardware. */
    val calibrationSource: String = DEFAULT_CALIBRATION_SOURCE,
    /**
     * The rig this phone has calibrated, if any.
     *
     * Null is the normal state — most runs are a phone logging itself, with no rig to describe. When
     * it is set, `frame_transform` and `configuration_json` publish it under the *rig's* entity id;
     * see [entityFor] and `docs/calibration.md`.
     */
    val calibration: RigCalibration? = null,
    /**
     * Write every published sample to an MCAP file as well as the bus.
     *
     * On by default: a `put` succeeds even with no router, so the local file is the only complete
     * record of a run.
     */
    val recordingEnabled: Boolean = true,
    /**
     * Hold recent samples and replay them when a dropped router link comes back.
     *
     * On by default. Backfilled messages carry their original `enclosed_at` but arrive after live
     * traffic, and the replay window deliberately overlaps, so a consumer may see a couple of seconds
     * of duplicates — see the README.
     */
    val backfillEnabled: Boolean = true,
    /**
     * Capture the microphone and publish it as `audio`.
     *
     * **Off by default, and that is a deliberate choice rather than a cautious one.** A phone logging
     * continuously in a wheelhouse records every conversation held near it; nobody should discover that
     * after the fact because a default was convenient.
     */
    val audioEnabled: Boolean = false,
    /** Capture rate. Only 44.1 kHz is guaranteed by the platform; the rest are offered if the device has them. */
    val audioSampleRateHz: Int = DEFAULT_AUDIO_SAMPLE_RATE_HZ,
    /** 1 or 2. Stereo doubles the data for detail most logging does not use. */
    val audioChannels: Int = 1,
    /**
     * Capture the camera at the `image_compressed` rate and publish each frame as a JPEG.
     *
     * **Off by default, for the same reason [audioEnabled] is.** A phone logging continuously
     * photographs whoever is in front of it; that has to be a choice somebody made, not a default they
     * inherited. It is also the most expensive subject in the app by a wide margin — see
     * `ui/SettingsScreen.kt`, which prints the per-hour figure next to the switch.
     */
    val cameraEnabled: Boolean = false,
    /** Which way the camera looks. Rear by default; the front camera is the cabin-facing option. */
    val cameraLensFront: Boolean = false,
    /** Requested frame size. The device picks the closest it actually supports. */
    val cameraWidth: Int = DEFAULT_CAMERA_WIDTH,
    val cameraHeight: Int = DEFAULT_CAMERA_HEIGHT,
    /**
     * Subjects the user has switched off, by registry *entry*.
     *
     * Entries rather than subject names, unlike [qosOverrides] and [sensorRates]: `radio_rssi_dbm` is
     * published from both `cellular` and `wifi`, and switching off the WiFi one has to leave the
     * cellular one alone. Absent means on, so a subject added to the registry later arrives enabled
     * rather than silently off.
     *
     * [audioEnabled] and [cameraEnabled] are deliberately *not* folded in here — those two also decide
     * a foreground-service type and a runtime permission, which is a start-time decision. Read the two
     * together through [offSubjects].
     */
    val disabledSubjects: Set<PublishedSubject> = emptySet(),
    /**
     * The marker buttons on the annotation screen, in the order they are shown.
     *
     * Not part of what a run is started with — the publisher declares `log_message` from the registry
     * regardless — so editing these does **not** restart the publisher. See `MainActivity`.
     */
    val annotationButtons: List<AnnotationButton> = DEFAULT_ANNOTATION_BUTTONS,
    /** Multicast socket used by the router scan. Only ever used for scanning, never for the session. */
    val scoutAddress: String = DEFAULT_SCOUT_ADDRESS,
    /**
     * Per-subject QoS overrides. A subject absent from the map follows `qos.yaml` — that is the
     * intended state, and the UI calls it "Auto".
     */
    val qosOverrides: Map<String, SubjectQos> = emptyMap(),
    /**
     * Requested sampling rate per subject, in Hz. A subject absent from the map uses its default.
     *
     * A request, not a promise: Android treats the derived delay as a hint and the hardware delivers
     * what it can, which is why the UI shows the achieved rate too.
     */
    val sensorRates: Map<String, SensorRate> = emptyMap(),
    /**
     * Join the shared checklist on the bus.
     *
     * Off by default because it opens a **second** Zenoh session and announces this phone, by name, to
     * every site watching — neither of which should happen because somebody installed a logger.
     */
    val checklistEnabled: Boolean = false,
    /**
     * Who this phone is on a checklist. Distinct from [entityId], which names the hardware as a source
     * of sensor data; a checklist is worked by a person, and the audit trail says who.
     *
     * [operatorId] is generated once, when the name is first entered, and then never changes — it is
     * what de-duplicates this phone's own presence heartbeat coming back on the wildcard subscription.
     * Empty means the identity has not been set up yet, which is what gates the feature.
     */
    val operatorId: String = "",
    val operatorName: String = "",
    val operatorRole: String = "",
    /**
     * Which site this phone counts as. Defaults to the entity id rather than to one of crowsnest's
     * `ROC-*` names on purpose: crowsnest drops an incoming event whose site *and* operator both match
     * its own, so a phone that borrowed a station's name would have its ticks silently ignored there.
     */
    val rocSiteId: String = "",
    /**
     * Where the checklist lives, which is **not** where this phone's sensor data lives.
     *
     * Defaults match crowsnest (`crowsnest/@v0/checklist/pubsub/...`). Configurable because a second
     * deployment may put it elsewhere, and hard-coding it would mean a rebuild to find out.
     */
    val checklistRealm: String = DEFAULT_CHECKLIST_REALM,
    val checklistEntityId: String = DEFAULT_CHECKLIST_ENTITY,
) {
    companion object {
        const val DEFAULT_REALM = "rise"

        /** Crowsnest's checklist tree. See `checklist/ChecklistKeys.kt`. */
        const val DEFAULT_CHECKLIST_REALM = "crowsnest"
        const val DEFAULT_CHECKLIST_ENTITY = "checklist"
        const val DEFAULT_ENDPOINT = "tls/router.example.com:443"

        /** Zenoh's own default scout socket. Deployments may move it — coswim uses :7448. */
        const val DEFAULT_SCOUT_ADDRESS = "224.0.0.224:7446"

        /**
         * 16 kHz mono: speech is fully intelligible and machinery noise is well represented to 8 kHz,
         * at 32 kB/s. Higher rates buy high-frequency detail — bearing squeal, cavitation harmonics —
         * for proportionally more data.
         */
        const val DEFAULT_AUDIO_SAMPLE_RATE_HZ = 16_000

        /** What the settings screen offers, cheapest first. Availability is checked per device. */
        val AUDIO_SAMPLE_RATES = listOf(8_000, 16_000, 44_100, 48_000)
        /**
         * 1280x720: enough to read a horizon, a deck or an instrument panel, at roughly 150 kB a frame.
         * 1080p is about double that for detail a time-lapse rarely needs.
         */
        const val DEFAULT_CAMERA_WIDTH = 1280
        const val DEFAULT_CAMERA_HEIGHT = 720

        /**
         * JPEG quality, fixed rather than exposed.
         *
         * 80 is the knee of the curve — above it the file grows faster than the picture improves, below
         * it compression artefacts start looking like sea state. The dials that matter for data volume
         * are the frame size and the rate, and both are already in the UI.
         */
        const val CAMERA_JPEG_QUALITY = 80

        /** What the settings screen offers, cheapest first. */
        val CAMERA_RESOLUTIONS = listOf(640 to 480, 1280 to 720, 1920 to 1080)

        /**
         * What the annotation screen offers before anybody has configured it.
         *
         * Three, generic, and meant to be replaced: an empty page teaches nothing about what a button
         * is for, and a long opinionated list is somebody else's voyage. The categories are the part
         * worth copying — they are what Foxglove groups the marks by.
         */
        val DEFAULT_ANNOTATION_BUTTONS = listOf(
            AnnotationButton("Waypoint", AnnotationSeverity.Info, "navigation"),
            AnnotationButton("Observation", AnnotationSeverity.Info, "observation"),
            AnnotationButton("Incident", AnnotationSeverity.Warning, "incident"),
        )

        const val DEFAULT_LOCATION_SOURCE = "phone"
        const val DEFAULT_IMU_SOURCE = "phone"
        const val DEFAULT_DEVICE_SOURCE = "phone"

        /**
         * Not `phone`: this source produces a survey of a rig, and naming it after the instrument
         * would put the rig's geometry under the same source id as the phone's own barometer.
         */
        const val DEFAULT_CALIBRATION_SOURCE = "calibration"

        /**
         * Each subject's default comes from [PublishedSubject], so a new one cannot silently inherit a
         * rate meant for something else — the previous if/else handed 50 Hz to anything that was not
         * `location_fix`, which would be wrong for a battery or a barometer and wrong in silence.
         */
        fun defaultRate(subject: String): SensorRate =
            PublishedSubject.forSubject(subject)?.defaultRate ?: SensorRate.Hz(1.0)
    }

    /**
     * The configured rate for a subject, or its default.
     *
     * A subject that follows another's stream reports the *owner's* rate, since that is the one that
     * actually governs how often it publishes.
     */
    fun rate(subject: String): SensorRate {
        val owner = PublishedSubject.forSubject(subject)?.rateOwner ?: subject
        return sensorRates[owner] ?: defaultRate(owner)
    }

    /**
     * Everything not meant to be publishing right now.
     *
     * The user's [disabledSubjects] plus the two subjects that carry their own enable flag, so the
     * publisher's gate and the UI's "Off" rows read one set and cannot disagree about audio or the
     * camera. Both of those are off unless switched on, which is why they are added rather than
     * removed here.
     */
    fun offSubjects(): Set<PublishedSubject> = buildSet {
        addAll(disabledSubjects)
        if (!audioEnabled) add(PublishedSubject.AUDIO)
        if (!cameraEnabled) add(PublishedSubject.IMAGE_COMPRESSED)
        // Nothing calibrated means nothing to say. Treated as off rather than as a subject that
        // merely never publishes, so the row reads "Off" instead of going stale and the collector is
        // never started in the first place.
        if (calibration?.isPublishable != true) {
            add(PublishedSubject.FRAME_TRANSFORM)
            add(PublishedSubject.CONFIGURATION_JSON)
        }
        // The surveyed zero is gated separately: a rig measured entirely with a tape has sensors worth
        // publishing and no position at all, and a heading typed before any capture is stored as a zero
        // with no position (see RigZero.hasPosition). Either way there is nothing to put on
        // `location_fix`, and publishing 0°N 0°E would be the most confident possible way of lying.
        if (calibration?.zero?.hasPosition != true) add(PublishedSubject.CALIBRATION_ZERO)
    }

    /**
     * Which source id a subject's key is built from.
     *
     * A fixed source id wins: `cellular` and `wifi` describe which radio produced the measurement, so
     * they are not the user's to rename the way the GNSS or IMU source is.
     */
    /** The site id actually used — the entity id when nothing has been chosen. */
    fun rocSite(): String = rocSiteId.ifBlank { entityId }

    /** True once there is a person behind the checklist actions. Nothing publishes before that. */
    fun hasChecklistIdentity(): Boolean = operatorId.isNotBlank() && operatorName.isNotBlank()

    fun sourceFor(entry: PublishedSubject): String = entry.fixedSourceId ?: when (entry.source) {
        SourceKind.LOCATION -> locationSource
        SourceKind.IMU -> imuSource
        SourceKind.DEVICE, SourceKind.RADIO -> deviceSource
        SourceKind.CALIBRATION -> calibrationSource
    }

    /**
     * Which entity id a subject's key is built from — the counterpart to [sourceFor], and the only
     * place a key's entity is decided.
     *
     * Everything the phone measures is about the phone, so it publishes under [entityId]. The rig
     * calibration is about the **rig**: a consumer looking for a vessel's geometry looks under that
     * vessel's entity, and publishing it under `pixel_6` would file it beside the phone's battery.
     * Falls back to [entityId] when there is no calibration, so a key is never malformed.
     */
    fun entityFor(entry: PublishedSubject): String = when (entry.source) {
        SourceKind.CALIBRATION -> calibration?.entityId?.takeIf { it.isNotBlank() } ?: entityId
        else -> entityId
    }
}

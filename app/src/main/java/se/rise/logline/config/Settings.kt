package se.rise.logline.config

import se.rise.logline.calibrate.RigCalibration
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.SourceKind
import se.rise.logline.keelson.SubjectQos
import se.rise.logline.keelson.pubsubKey
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
     * The rigs this phone knows about — its platform library.
     *
     * Empty is the normal state: most runs are a phone logging itself, with no rig to describe. A rig
     * is a keelson **platform**, and `entityId` is both its identity here and the `entity_id` chunk of
     * every key its geometry travels on, so the list is keyed on that and it must be unique.
     *
     * Several rigs are held at once because several are in play during a campaign; which of them
     * actually publish is [publishingRigEntityIds], and which one the phone counts as being *on* is
     * [activeRigEntityId]. See `docs/calibration.md`.
     */
    val rigs: List<RigCalibration> = emptyList(),
    /**
     * The rig this phone is on — the counterpart to crowsnest's own-ship selector.
     *
     * Matched on [RigCalibration.entityId]; empty, or naming a rig that is no longer in [rigs], means
     * nothing is selected. It does **not** change where the phone's own sensor data goes: that stays
     * under [entityId], because a battery reading is about the phone whichever rig it is bolted to.
     */
    val activeRigEntityId: String = "",
    /**
     * Which rigs put their geometry on the bus this run, by entity id.
     *
     * The active rig is included whether or not it is listed — see [publishingRigs] — so the one rig
     * the phone says it is on can never be silently absent from the bus. The set exists for the
     * others: a run may legitimately carry the geometry of every rig in the water, not just the one
     * the phone is sitting on.
     */
    val publishingRigEntityIds: Set<String> = emptySet(),
    /**
     * Share the rig library with other stations over the bus.
     *
     * Off by default, like the checklist and for the same reason: it opens a second Zenoh session and
     * puts this phone's library where every station can read it, neither of which should happen
     * because somebody installed a logger. The discovery and `get_config` halves of `PlatformSync`
     * need no such consent — they publish nothing about the operator — but sharing does.
     */
    val shareRigLibrary: Boolean = false,
    /**
     * Monotonic version of this phone's library, for last-writer-wins on the shared key.
     *
     * Bumped on every local edit and set to whatever a remote library carried when one is applied, so
     * the next local edit is newer than the thing it was edited from.
     */
    val rigRegistryVersion: Long = 0L,
    /**
     * This install's identity on the shared key, generated once and never changed.
     *
     * The same shape and the same purpose as [operatorId]: a publisher's own sample cache re-delivers,
     * so without an origin to compare a phone applies its own library back over itself on every
     * reconnect and the version ratchets for no reason.
     */
    val rigRegistryOrigin: String = "",
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
     * Whether the battery-optimisation exemption has been asked for, so it is asked **once**.
     *
     * Not whether it was granted — that is the system's answer and is read from `PowerManager`, which
     * is the only thing that can be trusted after a user has been through Android settings. This only
     * remembers that the question was put, because a prompt on every Start is how a person learns to
     * dismiss prompts without reading them.
     */
    val batteryExemptionAsked: Boolean = false,
    /**
     * Start a run again after the phone restarts. Off by default, and deliberately.
     *
     * A phone that begins publishing on its own is a surprise to anyone who does not know it was set
     * up that way, and the two subjects that record people — audio and the camera — are never part of
     * a boot start regardless, because Android 15+ refuses those foreground service types from a
     * `BOOT_COMPLETED` broadcast.
     */
    val startOnBoot: Boolean = false,
    /**
     * Draw the map only from imported tile archives, never the network.
     *
     * Off by default because online tiles are right everywhere there is coverage. On, it stops the
     * tile downloader queueing and timing out every tile an archive does not cover — which out of
     * coverage is most of them, and is the difference between a map that draws and one that grinds.
     */
    val offlineTilesOnly: Boolean = false,
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
        val publishing = publishingRigs()
        if (publishing.isEmpty()) {
            add(PublishedSubject.FRAME_TRANSFORM)
            add(PublishedSubject.CONFIGURATION_JSON)
        }
        // The surveyed zero is gated separately: a rig measured entirely with a tape has sensors worth
        // publishing and no position at all, and a heading typed before any capture is stored as a zero
        // with no position (see RigZero.hasPosition). Either way there is nothing to put on
        // `location_fix`, and publishing 0°N 0°E would be the most confident possible way of lying.
        //
        // With several rigs the test is "any of them", not "all": one tape-measured rig among three
        // surveyed ones must not take the other two zeros off the bus.
        if (publishing.none { it.zero?.hasPosition == true }) add(PublishedSubject.CALIBRATION_ZERO)
    }

    /** The rig this phone is on, or null when the selection is empty or names a rig that is gone. */
    fun activeRig(): RigCalibration? = rigs.firstOrNull { it.entityId == activeRigEntityId }

    fun rigFor(entityId: String): RigCalibration? = rigs.firstOrNull { it.entityId == entityId }

    /**
     * The rigs whose geometry goes on the bus, in library order.
     *
     * The active rig is always included — saying "the phone is on this rig" and then not publishing
     * its geometry would be a contradiction a switch should not be able to express — and a rig with no
     * sensors is always excluded, because [RigCalibration.isPublishable] is the test for having
     * anything to say at all.
     */
    fun publishingRigs(): List<RigCalibration> = rigs.filter {
        it.isPublishable && (it.entityId == activeRigEntityId || it.entityId in publishingRigEntityIds)
    }

    /**
     * Insert or replace one rig, carrying its selection across a rename.
     *
     * A rig's entity id is its identity here *and* the `{entity_id}` chunk of every key its geometry
     * travels on, so editing it is a re-key rather than a field edit — and the active selection and
     * the publish set both name the old id. Doing that anywhere but here is how a rename silently
     * deselects the rig somebody just renamed.
     *
     * [previousEntityId] is null for a rig being added. A rig replacing one that is gone is appended,
     * which is what makes an import that renames something behave like an add rather than a no-op.
     */
    fun upsertRig(previousEntityId: String?, rig: RigCalibration): Settings {
        val index = previousEntityId?.let { id -> rigs.indexOfFirst { it.entityId == id } } ?: -1
        val next = if (index >= 0) {
            rigs.toMutableList().also { it[index] = rig }
        } else {
            rigs + rig
        }
        val renamed = previousEntityId != null && previousEntityId != rig.entityId
        return copy(
            rigs = next,
            activeRigEntityId = if (renamed && activeRigEntityId == previousEntityId) {
                rig.entityId
            } else {
                activeRigEntityId
            },
            publishingRigEntityIds = if (renamed && previousEntityId in publishingRigEntityIds) {
                publishingRigEntityIds - previousEntityId + rig.entityId
            } else {
                publishingRigEntityIds
            },
        )
    }

    /** Remove a rig, and with it every reference to it. A dangling selection publishes nothing. */
    fun removeRig(entityId: String): Settings = copy(
        rigs = rigs.filterNot { it.entityId == entityId },
        activeRigEntityId = activeRigEntityId.takeIf { it != entityId }.orEmpty(),
        publishingRigEntityIds = publishingRigEntityIds - entityId,
    )

    fun setActiveRig(entityId: String): Settings = copy(activeRigEntityId = entityId)

    /**
     * Opt a rig in or out of publishing.
     *
     * Switching the *active* rig off does nothing, deliberately: [publishingRigs] includes it either
     * way, so honouring the switch would produce a control that visibly does nothing. The UI renders
     * that switch on and disabled rather than letting it be pressed.
     */
    fun setRigPublishing(entityId: String, publishing: Boolean): Settings = copy(
        publishingRigEntityIds = if (publishing) {
            publishingRigEntityIds + entityId
        } else {
            publishingRigEntityIds - entityId
        },
    )

    /**
     * The three keys one rig's geometry travels on.
     *
     * The counterpart to [entityFor] for the calibration subjects, and the only place a rig's entity
     * reaches a key. It is a function of the *rig* rather than of a registry entry because that is the
     * shape of the thing: one entry, several rigs, one key each.
     */
    fun rigKeys(rig: RigCalibration): Map<PublishedSubject, String> =
        PublishedSubject.entries
            .filter { it.source == SourceKind.CALIBRATION }
            .associateWith { pubsubKey(realm, rig.entityId, it.subject, sourceFor(it)) }

    /**
     * Mark the library as changed here, so a share is ordered against other stations'.
     *
     * Bumped on every local edit rather than derived from the rigs, because two libraries can differ
     * without either being newer and last-writer-wins needs an ordering somebody actually asserted.
     */
    fun bumpRigRegistry(): Settings = copy(rigRegistryVersion = rigRegistryVersion + 1)

    /** True when another rig already holds this entity id — the one thing a rename must not do. */
    fun entityIdTaken(entityId: String, exceptEntityId: String?): Boolean =
        rigs.any { it.entityId == entityId && it.entityId != exceptEntityId }

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
     * Which entity id a subject's key is built from — the counterpart to [sourceFor].
     *
     * Everything the phone measures is about the phone, so it publishes under [entityId], and that is
     * now the only answer this function has. The rig calibration is about the **rig** and still goes
     * out under the rig's entity, but a `PublishedSubject` names one registry entry and several rigs
     * publish through the same three entries — so there is no single entity to return. Those keys are
     * built per rig in `SensorPublisher`, from [publishingRigs]; see `docs/calibration.md`.
     */
    fun entityFor(entry: PublishedSubject): String = entityId
}

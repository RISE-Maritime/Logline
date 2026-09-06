package se.rise.logline.config

import se.rise.logline.calibrate.PlatformCalibration
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.Subjects
import se.rise.logline.keelson.SourceKind
import se.rise.logline.keelson.SubjectQos
import se.rise.logline.keelson.pubsubKey
import se.rise.logline.sensors.SensorRate
import se.rise.logline.sensors.slowerOf

/**
 * A colour scheme, or deference to the phone's own.
 *
 * Deliberately not a `Boolean`: `System` is the default and is a different thing from either fixed
 * choice, and a phone that switches at dusk should keep doing so unless somebody says otherwise.
 * `label` is what the chips read.
 */
enum class ThemeChoice(val label: String) {
    System("Follow phone"),
    Light("Light"),
    Dark("Dark"),
}

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
    /** Source id for the platform geometry. Names the survey, not a piece of hardware. */
    val calibrationSource: String = DEFAULT_CALIBRATION_SOURCE,
    /**
     * The platforms this phone knows about — its platform library.
     *
     * Empty is the normal state: most runs are a phone logging itself, with no platform to describe. A platform
     * is a keelson **platform**, and `entityId` is both its identity here and the `entity_id` chunk of
     * every key its geometry travels on, so the list is keyed on that and it must be unique.
     *
     * Several platforms are held at once because several are in play during a campaign; which of them
     * actually publish is [publishingPlatformEntityIds], and which one the phone counts as being *on* is
     * [activePlatformEntityId]. See `docs/calibration.md`.
     */
    val platforms: List<PlatformCalibration> = emptyList(),
    /**
     * The platform this phone is on — the counterpart to crowsnest's own-ship selector.
     *
     * Matched on [PlatformCalibration.entityId]; empty, or naming a platform that is no longer in [platforms], means
     * nothing is selected. It does **not** change where the phone's own sensor data goes: that stays
     * under [entityId], because a battery reading is about the phone whichever platform it is bolted to.
     */
    val activePlatformEntityId: String = "",
    /**
     * Which platforms put their geometry on the bus this run, by entity id.
     *
     * The active platform is included whether or not it is listed — see [publishingPlatforms] — so the one platform
     * the phone says it is on can never be silently absent from the bus. The set exists for the
     * others: a run may legitimately carry the geometry of every platform in the water, not just the one
     * the phone is sitting on.
     */
    val publishingPlatformEntityIds: Set<String> = emptySet(),
    /**
     * Share the platform library with other stations over the bus.
     *
     * Off by default, like the checklist and for the same reason: it opens a second Zenoh session and
     * puts this phone's library where every station can read it, neither of which should happen
     * because somebody installed a logger. The discovery and `get_config` halves of `PlatformSync`
     * need no such consent — they publish nothing about the operator — but sharing does.
     */
    val sharePlatformLibrary: Boolean = false,
    /**
     * Monotonic version of this phone's library, for last-writer-wins on the shared key.
     *
     * Bumped on every local edit and set to whatever a remote library carried when one is applied, so
     * the next local edit is newer than the thing it was edited from.
     */
    val platformRegistryVersion: Long = 0L,
    /**
     * This install's identity on the shared key, generated once and never changed.
     *
     * The same shape and the same purpose as [operatorId]: a publisher's own sample cache re-delivers,
     * so without an origin to compare a phone applies its own library back over itself on every
     * reconnect and the version ratchets for no reason.
     */
    val platformRegistryOrigin: String = "",
    /**
     * Write every published sample to an MCAP file as well as the bus.
     *
     * On by default: a `put` succeeds even with no router, so the local file is the only complete
     * record of a run.
     */
    val recordingEnabled: Boolean = true,
    /**
     * Whether a run puts anything on the wire.
     *
     * On by default: publishing is what this app is for, and the pair of switches on the start screen
     * defaults to both. Off gives a record-only run — the session is still opened and the phone is
     * still visible on the bus, it simply says nothing. That is a deliberate choice over going dark:
     * a phone that vanishes from a fleet's liveliness while it is in fact running is worse to diagnose
     * than one that is present and quiet.
     *
     * Note the counters mean "samples produced" on such a run rather than "samples published" — see
     * `SubjectSink.emit`, which is where the distinction is made and explained.
     */
    val publishEnabled: Boolean = true,
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
     * A persisted grant on `Downloads/Logline`, or blank.
     *
     * **What it buys**: MediaStore attributes a file to the install that wrote it, so after a reinstall
     * this app's own recordings sit in that folder untouched and invisible — measured, a file written
     * under another package was absent from a listing that returned all fifteen of this install's. With
     * the grant the Files list is built from both sources and shows everything there.
     *
     * A device fact and an install fact, like [batteryExemptionAsked]: the grant belongs to *this*
     * installation on *this* phone and is dropped when it goes. So it is deliberately **not** in
     * `SettingsProfile` — a profile shared round a fleet carrying one phone's storage grant would be
     * carrying something the receiving phone cannot use and did not ask for.
     */
    val recordingsFolderUri: String = "",
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
     * Which colour scheme to draw, or to follow the phone.
     *
     * **Applied the moment it is chosen, never on Save.** Every other field on the settings screen
     * goes through `saveSettings()`, which stops and restarts the service so publishers are redeclared
     * — and tearing down the Zenoh session and the open MCAP file to change a colour would end the run
     * somebody is watching. The same rule the tags and the per-subject switches follow, and the reason
     * this one is written with `update()`.
     *
     * Three states rather than a switch, for the reason the sampling-rate selector is a pair rather
     * than a toggle: the thing being chosen is *which* scheme, and "follow the phone" is a real answer
     * that a two-way switch cannot express.
     */
    val theme: ThemeChoice = ThemeChoice.System,
    /**
     * A MapTiler key, which upgrades the satellite layer to their imagery.
     *
     * Blank by default, and the chart falls back to Esri's world imagery when it is — satellite is the
     * *default* layer, so an install with no key must still draw something rather than opening on a
     * blank grid, which reads as a broken app rather than a missing key.
     *
     * **Typed in per phone and never in the repo or the APK**, the same stance the mTLS credentials
     * take: a debug build of this app gets passed around, and a tile key is billable. Note it *is*
     * carried by a settings profile, unlike the five install-identity fields — that is deliberate, so a
     * fleet can be provisioned from one QR, and it does mean a shared profile carries the key.
     */
    val mapTilerKey: String = "",
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
     * Publish continuous H.264 on `video_compressed`, alongside or instead of the time-lapse.
     *
     * Off by default, for exactly the reason [cameraEnabled] is — more so, since this is not a frame
     * every two seconds but everything the lens sees.
     *
     * The defaults are chosen so that switching it on cannot shorten a run: 640x480 at 300 kbps is
     * **128 MB/h**, against the time-lapse's 158 MB/h for a twentieth of the frames. Video is only
     * cheap *per frame* — at 720p and 2 Mbps it is 858 MB/h, five times the stills — so
     * [videoBitrateKbps] is the setting that decides whether a long run fits on the phone, and
     * `ui/SettingsScreen.kt` prints the figure beside it.
     */
    val videoEnabled: Boolean = false,
    val videoWidth: Int = DEFAULT_VIDEO_WIDTH,
    val videoHeight: Int = DEFAULT_VIDEO_HEIGHT,
    /** What the encoder is *asked* to produce. Unlike the JPEG estimate, the per-hour cost follows. */
    val videoBitrateKbps: Int = DEFAULT_VIDEO_BITRATE_KBPS,
    /**
     * Seconds between keyframes.
     *
     * A live subscriber can decode nothing until one arrives, so this is how long a joiner waits — and
     * because a keyframe costs many times a delta frame, at 10 fps it is also most of the bitrate. The
     * knob trades join latency against nearly all of the cost.
     */
    val videoKeyframeSeconds: Int = DEFAULT_VIDEO_KEYFRAME_SECONDS,
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
    /**
     * The tag vocabulary, in the order it is shown on the Events screen.
     *
     * A *configuration*, like [annotationButtons] — the words this phone or this fleet uses — which is
     * why it lives here and travels in a settings profile, rather than beside the recordings it ends up
     * describing.
     */
    /**
     * A live camera to watch, served by keelson's `mediamtx` WHEP proxy on some other entity.
     *
     * Blank means the feature is off, which is the default: this points at somebody else's vessel and
     * there is no sensible guess. [cameraPath] is MediaMTX's own path name — the `<pathname>` in
     * `MTX_PATHS_<pathname>_SOURCE` — not a keelson subject, and nothing on the bus advertises it.
     */
    val cameraEntityId: String = "",
    val cameraResponderId: String = DEFAULT_CAMERA_RESPONDER,
    val cameraPath: String = "",
    /**
     * ICE for the camera. STUN alone will not cross most vessel networks, which is why TURN is here at
     * all; crowsnest carries the same three fields for the same reason.
     */
    val cameraStunUrl: String = DEFAULT_STUN_URL,
    val cameraTurnUrl: String = "",
    val cameraTurnUsername: String = "",
    val cameraTurnPassword: String = "",
    val tags: List<String> = emptyList(),
    /**
     * Which of [tags] are switched on.
     *
     * Whatever is on when a file closes is written into it, so this is a live control rather than a
     * preference: toggling one goes through `update()` and is pushed into the running recorder, never
     * through `saveSettings()`, which would tear down the run to record a word. It persists between
     * runs on purpose — a platform that is always "harbour" should not have to be told twice.
     */
    val activeTags: Set<String> = emptySet(),
    /** Multicast socket used by the router scan. Only ever used for scanning, never for the session. */
    val scoutAddress: String = DEFAULT_SCOUT_ADDRESS,
    /**
     * Per-subject QoS overrides. A subject absent from the map follows `qos.yaml` — that is the
     * intended state, and the UI calls it "Auto".
     */
    val qosOverrides: Map<String, SubjectQos> = emptyMap(),
    /**
     * Requested **publish** rate per subject, in Hz — what goes on the bus. Absent means its default.
     *
     * A request, not a promise: Android treats the derived delay as a hint and the hardware delivers
     * what it can, which is why the UI shows the achieved rate too.
     *
     * This used to be the only rate and drove the sensor as well. It is now the *thinner* of the two:
     * the sensor runs at [recordRates] and the wire is decimated down to this, because the bus is for
     * watching a trial while the file is what analysis is run against. It keeps its `rate_*` DataStore
     * keys so every existing setting carries over as the publish rate, unchanged.
     */
    val sensorRates: Map<String, SensorRate> = emptyMap(),
    /**
     * Requested **recording** rate per subject — what the sensor is asked for and what reaches the file.
     *
     * **Absent means [SensorRate.Max]**, not a per-subject default: the file is the complete record, so
     * it takes everything the hardware will give unless somebody turns a subject down. That is a
     * deliberate cost — measured on a Pixel 6, ordinary rates produce ~743 samples/s and ~72 MB/h, and
     * the gyroscope alone at Max takes that to ~2295 samples/s and ~460 MB/h.
     */
    val recordRates: Map<String, SensorRate> = emptyMap(),
    /**
     * Record every subject at its hardware maximum, whatever [recordRates] says.
     *
     * A **mode layered over** the map rather than a rewrite of it, which is the whole point: flipping
     * to full rate for a trial and back must return the tuned profile intact. Writing Max into
     * `recordRates` would destroy the tuning it exists to preserve.
     *
     * **Defaults to true**, which is what makes a fresh install record everything the hardware gives.
     * It lives here rather than as an absent-means-Max fallback inside [recordRates] so that the
     * Session screen's *Configured* really is each subject's own rate — a fallback made that label and
     * the megabytes-per-hour beside it disagree with what the phone actually did.
     */
    val recordAllMax: Boolean = true,
    /** The same, for the wire: publish everything that is recorded, with no thinning. */
    val publishAllMax: Boolean = false,
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
     * Defaults match crowsnest (`rise/@v0/roc1/pubsub/...`). The entity names the ROC's tree, not
     * this phone and not the station an operator sits at — that is already the source id.
     * Configurable because a second deployment may put it elsewhere, and hard-coding it would mean
     * a rebuild to find out.
     *
     * These were `crowsnest` / `checklist` until 2026-08-26; a phone that is upgraded but keeps a
     * saved profile carries the old values forward and will silently talk to the old tree.
     */
    val checklistRealm: String = DEFAULT_CHECKLIST_REALM,
    val checklistEntityId: String = DEFAULT_CHECKLIST_ENTITY,
) {
    companion object {
        const val DEFAULT_REALM = "rise"

        /** Crowsnest's checklist tree. See `checklist/ChecklistKeys.kt`. */
        const val DEFAULT_CHECKLIST_REALM = "rise"
        const val DEFAULT_CHECKLIST_ENTITY = "roc1"
        const val DEFAULT_ENDPOINT = "tls/router.example.com:443"

        /** Zenoh's own default scout socket. Deployments may move it — coswim uses :7448. */
        const val DEFAULT_SCOUT_ADDRESS = "224.0.0.224:7446"

        /** What keelson's own `mediamtx` README calls the responder. */
        const val DEFAULT_CAMERA_RESPONDER = "mediamtx"

        /** The STUN server keelson's README puts in its MediaMTX example. */
        const val DEFAULT_STUN_URL = "stun:stun.l.google.com:19302"

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
         * 640x480 at 300 kbps and 10 fps — **128 MB/h**, which is the whole point of the choice.
         *
         * That is less per hour than the time-lapse's 158 MB/h while carrying twenty times the frames,
         * so switching video on can never make a run shorter than it already was. 720p at 2 Mbps is
         * 858 MB/h and turns ten days of recording into under two; it is available in Settings, as a
         * decision rather than a default.
         */
        const val DEFAULT_VIDEO_WIDTH = 640
        const val DEFAULT_VIDEO_HEIGHT = 480
        const val DEFAULT_VIDEO_BITRATE_KBPS = 300

        /** Two seconds: a live joiner waits at most that long for a decodable frame. */
        const val DEFAULT_VIDEO_KEYFRAME_SECONDS = 2

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

        /** The same three sizes; 1080p video at a sensible bitrate is a different order of cost. */
        val VIDEO_RESOLUTIONS = listOf(640 to 480, 1280 to 720, 1920 to 1080)

        /**
         * 128, 429, 858 and 1 717 MB/h respectively — the reason the list is short and starts low.
         *
         * Only the first is cheaper per hour than the time-lapse it sits beside; the rest are a
         * deliberate trade of endurance for detail, which is why the screen prints the figure.
         */
        val VIDEO_BITRATES_KBPS = listOf(300, 1_000, 2_000, 4_000)

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
         * Not `phone`: this source produces a survey of a platform, and naming it after the instrument
         * would put the platform's geometry under the same source id as the phone's own barometer.
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
    fun rate(subject: String): SensorRate = publishRate(subject)

    /**
     * What the sensor is asked for, and what reaches the file.
     *
     * Absent means [SensorRate.Max] — see [recordRates]. This is the rate the listener is registered
     * at, because it is the higher of the two: registering at the publish rate would make the
     * recording rate unachievable.
     */
    fun recordRate(subject: String): SensorRate {
        val owner = PublishedSubject.forSubject(subject)?.rateOwner ?: subject
        // For these the two rates are one thing: a poll produces exactly one sample and publishes it,
        // and a chunk or a capture interval is not a rate a file could hold more of. Falling back to the
        // registry default here instead would clamp a *raised* publish rate back down to it.
        if (!recordsContinuously(owner)) {
            return recordRates[owner] ?: sensorRates[owner] ?: defaultRate(owner)
        }
        // The switch wins over a tuned value without erasing it — that is what makes it a mode rather
        // than a bulk edit, and what lets flipping back restore the profile.
        if (recordAllMax) return SensorRate.Max
        // **The subject's own rate, not Max.** "Configured" on the Session screen has to mean
        // configured, or the label and the storage estimate beside it are both wrong — which they were:
        // the chip read Configured while an absent entry still resolved to Max, so the phone recorded
        // at ten times the figure on screen. Recording at max out of the box is preserved by
        // [recordAllMax] defaulting to true, which is a mode rather than a hidden fallback.
        return recordRates[owner] ?: defaultRate(owner)
    }

    /**
     * Whether this subject is offered a **record rate of its own**, separate from its publish rate —
     * which is the same question as whether "as fast as the hardware will give" means anything for it.
     *
     * **It was called `ratesCanDiffer`, and that name was not merely vague — it was backwards.** When
     * this is false the editor forces the record rate to equal the publish rate (`!hasSeparateRecordRate
     * -> editedRate` in `SubjectQosScreen`), so "the rates cannot differ" described a consequence of the
     * answer rather than a fact about the subject. They could perfectly well differ: a derived subject
     * riding a poll takes the owner's rate for the file and keeps its own for the wire, which is two
     * different numbers. What is actually being decided is whether there is a second rate worth putting
     * a control under, and the name now says so.
     *
     * **It resolves through [PublishedSubject.rateOwner], and that is not about reading the owner's
     * rate.** Every other function that hops to the owner does so to take a *value* from it. This one
     * hops to ask what kind of thing is doing the sampling: a derived subject has no listener, so
     * whether anything is running on its own clock is a fact about the owner and never about the
     * subject itself.
     *
     * Only a continuously sampling `SensorManager` sensor, and the fused location provider, produce
     * events on their own clock — for those, [SensorRate.Max] is a real request and the honest default
     * for a file meant to hold everything.
     *
     * For the rest it is meaningless or harmful, and each is excluded for its own reason. The battery
     * and radio subjects are *polled*, so Max would mean an interval of zero and hammer the telephony
     * and power APIs in a loop. `audio` is a chunk length and `image_compressed` / `video_compressed`
     * are capture intervals, not sample rates. `frame_transform` and the other calibration subjects are
     * a republish loop with no sensor to wait on. And `illuminance_lux` / `imu_temperature_celsius` are
     * *on-change* — held on a ticker by `heldAt`, so Max there would repeat one unchanged reading ten
     * times a second and call it data.
     */
    fun hasSeparateRecordRate(subject: String): Boolean {
        val owner = PublishedSubject.forSubject(subject)?.rateOwner ?: subject
        return recordsContinuously(owner)
    }

    private fun recordsContinuously(subject: String): Boolean {
        val entry = PublishedSubject.forSubject(subject) ?: return false
        if (entry.eventDriven) return false
        if (entry.subject == Subjects.ILLUMINANCE_LUX ||
            entry.subject == Subjects.IMU_TEMPERATURE_CELSIUS
        ) {
            return false
        }
        return entry.sensorType != null || entry.source == SourceKind.LOCATION
    }

    /**
     * The fastest this subject may be published, and why.
     *
     * Two different limits wearing one name, which is the whole reason it is a function rather than an
     * expression inside [publishRate].
     *
     * For a subject with its own listener it is the **record** rate: no sample exists to send faster
     * than it is sampled, so this is physics rather than policy.
     *
     * For a subject that rides another's samples it is the owner's **publish** rate — policy, and a
     * deliberate choice over the physical limit. The samples are there (the listener runs at the
     * owner's *record* rate, so a derived subject could technically go faster than its owner does on
     * the wire), but a group whose members can each exceed the one they are derived from is a group
     * nobody can reason about from the Session screen: the owner's row is meant to be readable as the
     * ceiling for everything under it. Raising a derived subject past the cap therefore means raising
     * the owner first, which is what the page's link to it is for.
     *
     * **One hop, never recursion.** No owner has an owner and `SubjectRegistryTest` pins that, but a
     * hop that cannot repeat is safe even if somebody later writes a cycle, where recursion would take
     * the process out with a stack overflow.
     */
    fun publishCeiling(subject: String): SensorRate {
        val owner = PublishedSubject.forSubject(subject)?.rateOwner
            ?: return recordRate(subject)
        return headPublishRate(owner)
    }

    /**
     * Whether another subject's rate is holding this one below what it asked for.
     *
     * True only for a subject that **rides** another and would publish faster if the owner let it. The
     * subject row uses it to name the owner in place of the sensor's own limit, which in that state is
     * the least useful of the three numbers on the row — it is not deciding anything, and printed
     * beside a rate it cannot explain it invites raising a figure that will not move.
     *
     * Three cases are deliberately *not* capped, and each would be a false alarm:
     *
     * - **Following.** A derived subject with no stored rate takes the owner's through
     *   [requestedPublishRate], so requested and ceiling are equal by construction. The figure shown
     *   is the owner's and contradicts nothing — there is no request being denied.
     * - **A head subject clamped to its own record rate.** [publishCeiling] falls back to
     *   [recordRate] where there is no owner, so this would fire on a subject with no owner to name.
     *   The row already carries the `rec` figure a few characters to the left.
     * - **[publishAllMax].** There [publishRate] returns the ceiling whatever was asked, so comparing
     *   the two would flag a subject the mode had *raised* as one being held down.
     */
    fun publishRateIsCapped(subject: String): Boolean {
        if (publishAllMax) return false
        if (PublishedSubject.forSubject(subject)?.rateOwner == null) return false
        return publishRate(subject) != requestedPublishRate(subject)
    }

    /**
     * What was *asked for*, before any clamping — which is what an editor has to show.
     *
     * [publishRate] answers "what will go out", and a text field showing that cannot be edited: typing
     * 10 Hz against a 1 Hz ceiling would redraw as 1.0, and saving would then store the clamp, so the
     * request would be destroyed by the act of looking at it. That round trip was already wrong for
     * subjects clamped to their own record rate; it is only now that it is easy to hit.
     *
     * **Absent means "follow the owner" for a derived subject**, not "use my own registry default".
     * Every derived entry does carry a default equal to its owner's, so a fresh install reads the same
     * either way — but an install that had tuned `location_fix` down to 0.2 Hz would find speed and
     * course jumping back to 1.0 Hz on upgrade, which is a silent change to what a phone puts on the
     * bus. Following keeps this addition to exactly what it says: nothing moves until a derived subject
     * is set explicitly.
     */
    fun requestedPublishRate(subject: String): SensorRate {
        sensorRates[subject]?.let { return it }
        val owner = PublishedSubject.forSubject(subject)?.rateOwner ?: return defaultRate(subject)
        return headPublishRate(owner)
    }

    /**
     * What goes on the bus.
     *
     * **Clamped rather than validated.** A publish rate faster than the ceiling is not an error anyone
     * can see or fix — for a head subject no sample exists to send — so asking for 10 Hz where 1 Hz is
     * possible simply publishes every sample there is.
     *
     * The stored value is left alone by that clamp, deliberately: an owner lowered for one trial and
     * raised again must bring the whole group's tuning back with it. Same argument [recordAllMax] makes
     * about being a mode layered over the maps rather than a bulk edit — and clamping on save would
     * additionally mean walking every derived subject each time an owner moved.
     */
    fun publishRate(subject: String): SensorRate {
        val ceiling = publishCeiling(subject)
        if (publishAllMax) return ceiling
        return slowerOf(requestedPublishRate(subject), ceiling)
    }

    /**
     * [publishRate] for a subject that owns its own rate — the half that must not hop.
     *
     * Private because calling it on a derived subject would silently read that subject's own entry
     * against its own record rate, skipping the cap entirely.
     */
    private fun headPublishRate(subject: String): SensorRate {
        val record = recordRate(subject)
        if (publishAllMax) return record
        return slowerOf(sensorRates[subject] ?: defaultRate(subject), record)
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
        // **The two camera subjects cannot both run**, and this is where that is enforced rather
        // than in the UI, so an imported profile with both set cannot reach a state the hardware
        // refuses. Measured on a Pixel 6: binding CameraX's `Preview` (feeding the H.264 encoder)
        // alongside `ImageCapture` kills the camera HAL within a second — `ERROR_CAMERA_DEVICE`, the
        // provider process dies and restarts in a loop — at matching resolutions as well as
        // mismatched ones. Video wins because it is the strictly more informative of the two.
        if (!cameraEnabled || videoEnabled) add(PublishedSubject.IMAGE_COMPRESSED)
        if (!videoEnabled) add(PublishedSubject.VIDEO_COMPRESSED)
        // Nothing calibrated means nothing to say. Treated as off rather than as a subject that
        // merely never publishes, so the row reads "Off" instead of going stale and the collector is
        // never started in the first place.
        val publishing = publishingPlatforms()
        if (publishing.isEmpty()) {
            add(PublishedSubject.FRAME_TRANSFORM)
            add(PublishedSubject.CONFIGURATION_JSON)
        }
        // The surveyed zero is gated separately: a platform measured entirely with a tape has sensors worth
        // publishing and no position at all, and a heading typed before any capture is stored as a zero
        // with no position (see PlatformZero.hasPosition). Either way there is nothing to put on
        // `location_fix`, and publishing 0°N 0°E would be the most confident possible way of lying.
        //
        // With several platforms the test is "any of them", not "all": one tape-measured platform among three
        // surveyed ones must not take the other two zeros off the bus.
        if (publishing.none { it.zero?.hasPosition == true }) add(PublishedSubject.CALIBRATION_ZERO)
    }

    /** The platform this phone is on, or null when the selection is empty or names a platform that is gone. */
    fun activePlatform(): PlatformCalibration? = platforms.firstOrNull { it.entityId == activePlatformEntityId }

    fun platformFor(entityId: String): PlatformCalibration? = platforms.firstOrNull { it.entityId == entityId }

    /**
     * The platforms whose geometry goes on the bus, in library order.
     *
     * The active platform is always included — saying "the phone is on this platform" and then not publishing
     * its geometry would be a contradiction a switch should not be able to express — and a platform with no
     * sensors is always excluded, because [PlatformCalibration.isPublishable] is the test for having
     * anything to say at all.
     */
    fun publishingPlatforms(): List<PlatformCalibration> = platforms.filter {
        it.isPublishable && (it.entityId == activePlatformEntityId || it.entityId in publishingPlatformEntityIds)
    }

    /**
     * Insert or replace one platform, carrying its selection across a rename.
     *
     * A platform's entity id is its identity here *and* the `{entity_id}` chunk of every key its geometry
     * travels on, so editing it is a re-key rather than a field edit — and the active selection and
     * the publish set both name the old id. Doing that anywhere but here is how a rename silently
     * deselects the platform somebody just renamed.
     *
     * [previousEntityId] is null for a platform being added. A platform replacing one that is gone is appended,
     * which is what makes an import that renames something behave like an add rather than a no-op.
     */
    fun upsertPlatform(previousEntityId: String?, platform: PlatformCalibration): Settings {
        val index = previousEntityId?.let { id -> platforms.indexOfFirst { it.entityId == id } } ?: -1
        val next = if (index >= 0) {
            platforms.toMutableList().also { it[index] = platform }
        } else {
            platforms + platform
        }
        val renamed = previousEntityId != null && previousEntityId != platform.entityId
        return copy(
            platforms = next,
            activePlatformEntityId = if (renamed && activePlatformEntityId == previousEntityId) {
                platform.entityId
            } else {
                activePlatformEntityId
            },
            publishingPlatformEntityIds = if (renamed && previousEntityId in publishingPlatformEntityIds) {
                publishingPlatformEntityIds - previousEntityId + platform.entityId
            } else {
                publishingPlatformEntityIds
            },
        )
    }

    /** Remove a platform, and with it every reference to it. A dangling selection publishes nothing. */
    fun removePlatform(entityId: String): Settings = copy(
        platforms = platforms.filterNot { it.entityId == entityId },
        activePlatformEntityId = activePlatformEntityId.takeIf { it != entityId }.orEmpty(),
        publishingPlatformEntityIds = publishingPlatformEntityIds - entityId,
    )

    fun setActivePlatform(entityId: String): Settings = copy(activePlatformEntityId = entityId)

    /**
     * Opt a platform in or out of publishing.
     *
     * Switching the *active* platform off does nothing, deliberately: [publishingPlatforms] includes it either
     * way, so honouring the switch would produce a control that visibly does nothing. The UI renders
     * that switch on and disabled rather than letting it be pressed.
     */
    fun setPlatformPublishing(entityId: String, publishing: Boolean): Settings = copy(
        publishingPlatformEntityIds = if (publishing) {
            publishingPlatformEntityIds + entityId
        } else {
            publishingPlatformEntityIds - entityId
        },
    )

    /**
     * The three keys one platform's geometry travels on.
     *
     * The counterpart to [entityFor] for the calibration subjects, and the only place a platform's entity
     * reaches a key. It is a function of the *platform* rather than of a registry entry because that is the
     * shape of the thing: one entry, several platforms, one key each.
     */
    fun platformKeys(platform: PlatformCalibration): Map<PublishedSubject, String> =
        PublishedSubject.entries
            .filter { it.source == SourceKind.CALIBRATION }
            .associateWith { pubsubKey(realm, platform.entityId, it.subject, sourceFor(it)) }

    /**
     * Mark the library as changed here, so a share is ordered against other stations'.
     *
     * Bumped on every local edit rather than derived from the platforms, because two libraries can differ
     * without either being newer and last-writer-wins needs an ordering somebody actually asserted.
     */
    fun bumpPlatformRegistry(): Settings = copy(platformRegistryVersion = platformRegistryVersion + 1)

    /** True when another platform already holds this entity id — the one thing a rename must not do. */
    fun entityIdTaken(entityId: String, exceptEntityId: String?): Boolean =
        platforms.any { it.entityId == entityId && it.entityId != exceptEntityId }

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

    fun sourceFor(entry: PublishedSubject): String {
        val base = entry.fixedSourceId ?: when (entry.source) {
            SourceKind.LOCATION -> locationSource
            SourceKind.IMU -> imuSource
            SourceKind.DEVICE, SourceKind.RADIO -> deviceSource
            SourceKind.CALIBRATION -> calibrationSource
        }
        // A further level beneath the configured id, which the specification allows and the unfused
        // position solutions use. Nesting rather than replacing is what stops a `locationSource` of
        // `gnss` colliding with the GNSS-only stream.
        return entry.sourceSuffix?.let { "$base/$it" } ?: base
    }

    /**
     * Which entity id a subject's key is built from — the counterpart to [sourceFor].
     *
     * Everything the phone measures is about the phone, so it publishes under [entityId], and that is
     * now the only answer this function has. The platform calibration is about the **platform** and still goes
     * out under the platform's entity, but a `PublishedSubject` names one registry entry and several platforms
     * publish through the same three entries — so there is no single entity to return. Those keys are
     * built per platform in `SensorPublisher`, from [publishingPlatforms]; see `docs/calibration.md`.
     */
    fun entityFor(entry: PublishedSubject): String = entityId
}

package se.rise.logline.publish

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.os.SystemClock
import android.util.Size
import android.util.Log
import androidx.core.content.ContextCompat
import se.rise.logline.config.AnnotationSeverity
import se.rise.logline.config.SYSTEM_CATEGORY
import se.rise.logline.calibrate.RigCalibration
import se.rise.logline.calibrate.normaliseSignedDegrees
import se.rise.logline.calibrate.toPlatformGeometryJson
import se.rise.logline.calibrate.toQuaternion
import se.rise.logline.config.Settings
import se.rise.logline.config.TlsCredentialStore
import se.rise.logline.keelson.KeelsonSession
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.SourceKind
import se.rise.logline.keelson.Subjects
import se.rise.logline.keelson.diagonalEnuCovariance
import se.rise.logline.keelson.enclose
import se.rise.logline.keelson.legacyLivelinessKey
import se.rise.logline.keelson.protoDuration
import se.rise.logline.keelson.protoTimestamp
import se.rise.logline.keelson.pubsubKey
import se.rise.logline.keelson.qosForSubject
import se.rise.logline.keelson.sourceLivelinessKey
import se.rise.logline.keelson.subjectLivelinessKeys
import se.rise.logline.record.RecordSample
import se.rise.logline.record.Recorder
import se.rise.logline.record.QueueLoad
import se.rise.logline.record.RecordingStatus
import se.rise.logline.sensors.AudioProvider
import se.rise.logline.sensors.BatteryProvider
import se.rise.logline.sensors.CameraProvider
import se.rise.logline.sensors.CameraOutput
import se.rise.logline.sensors.StillsConfig
import se.rise.logline.sensors.VideoConfig
import se.rise.logline.sensors.ImuProvider
import se.rise.logline.sensors.LocationProvider
import se.rise.logline.sensors.MslAltitudeResolver
import se.rise.logline.sensors.undulationMetres
import se.rise.logline.sensors.FixKind
import se.rise.logline.sensors.GnssStatusProvider
import se.rise.logline.sensors.NmeaProvider
import se.rise.logline.sensors.fixQualityOf
import se.rise.logline.sensors.nmeaEpochNanos
import se.rise.logline.sensors.LocationUpdate
import se.rise.logline.sensors.RadioProvider
import se.rise.logline.sensors.ScalarSensorProvider
import se.rise.logline.sensors.SensorClock
import se.rise.logline.sensors.WavChunk
import se.rise.logline.sensors.thumbnail
import se.rise.logline.sensors.SensorRate
import se.rise.logline.sensors.hectopascalToPascal
import se.rise.logline.sensors.kilohertzToMegahertz
import se.rise.logline.sensors.metresPerSecondToKnots
import se.rise.logline.sensors.attitudeRatesOf
import se.rise.logline.sensors.normaliseHeadingDegrees
import se.rise.logline.sensors.microteslaToGauss
import se.rise.logline.sensors.toIntervalMillis
import se.rise.logline.sensors.toRateUs
import se.rise.logline.sensors.unavailableSubjects
import com.google.protobuf.ByteString
import com.google.protobuf.Timestamp
import foxglove.CompressedImageOuterClass.CompressedImage
import foxglove.LogOuterClass.Log as FoxgloveLog
import foxglove.FrameTransformOuterClass.FrameTransform
import foxglove.LocationFixOuterClass.LocationFix
import foxglove.CompressedVideoOuterClass.CompressedVideo
import foxglove.QuaternionOuterClass.Quaternion
import foxglove.Vector3OuterClass.Vector3
import io.zenoh.liveliness.LivelinessToken
import io.zenoh.pubsub.AdvancedPublisher
import keelson.AudioOuterClass.Audio
import keelson.Decomposed3DVectorOuterClass.Decomposed3DVector
import keelson.Primitives.TimestampedBool
import keelson.Primitives.TimestampedDuration
import keelson.Primitives.TimestampedFloat
import keelson.Primitives.TimestampedInt
import keelson.Primitives.TimestampedInt64
import keelson.Primitives.TimestampedQuaternion
import keelson.LocationFixQualityOuterClass.LocationFixQuality
import keelson.Primitives.TimestampedString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SensorPublisher"
private const val CONNECTION_POLL_MILLIS = 2_000L

/** Chunk bounds: below a fifth of a second the overhead dominates, above ten seconds a drop hurts. */
internal const val MIN_AUDIO_CHUNK_MILLIS = 200L
private const val MAX_AUDIO_CHUNK_MILLIS = 10_000L

/**
 * Frame interval bounds: below half a second this is video rather than a time-lapse, and the ten-minute
 * ceiling keeps a mis-set rate from binding the camera for an hour between frames.
 */
private const val MIN_FRAME_INTERVAL_MILLIS = 500L
private const val MAX_FRAME_INTERVAL_MILLIS = 600_000L

/** Falls back to the settings default when a rate cannot be read as an interval. */
private const val DEFAULT_VIDEO_FRAME_RATE = 10

/**
 * 30 fps, and it is a data-rate guard rather than a hardware one.
 *
 * The encoder holds the bitrate roughly constant whatever the frame rate, so a higher rate buys
 * smoothness rather than bytes — but it also multiplies the message count, and every frame is a
 * separate `put`, an MCAP record and a live-store append.
 */
internal const val MAX_VIDEO_FRAME_RATE = 30

/** What `CompressedImage.frame_id` says the picture was taken with. */
private const val FRAME_ID_REAR = "camera_rear"
private const val FRAME_ID_FRONT = "camera_front"

/**
 * Replay pacing: 40 samples then a 100 ms pause, i.e. ~400/s — roughly twice the production rate, so a
 * two-minute backlog clears in about a minute while live traffic keeps flowing.
 */
/** Ten seconds is the default; this is only the floor a "Max" rate setting is held to. */
internal const val MIN_CALIBRATION_INTERVAL_MILLIS = 1_000L

/**
 * The LSM6DSR's own operating range, used only to notice a vendor sensor reporting something that is
 * not degrees. Deliberately the *chip's* range rather than a comfortable one: −40 °C is a real reading
 * on a foredeck in winter, and this must not cry wolf about it.
 */
private const val MIN_PLAUSIBLE_DIE_CELSIUS = -40f
private const val MAX_PLAUSIBLE_DIE_CELSIUS = 125f

private const val REPLAY_BATCH = 40
private const val REPLAY_BATCH_PAUSE_MILLIS = 100L

class SensorPublisher(private val appContext: Context) {

    private val statusStore = PublisherStatusStore()
    val status: StateFlow<PublisherStatus> get() = statusStore.status

    /**
     * Recent values, for the live view. Pulled by the UI on its own ticker rather than pushed, so the
     * publish path never drives recomposition — see [LiveSampleStore].
     */
    private val liveStore = LiveSampleStore()
    fun liveSnapshot(): LiveSnapshot = liveStore.snapshot()

    /** Just the newest reading per subject — what the main screen shows in each row. */
    fun liveLatest(): LiveLatest = liveStore.latest()

    /** Local MCAP recording. Independent of publish success — see [Recorder]. */
    private val recorder = Recorder(appContext)
    val recording: StateFlow<RecordingStatus> get() = recorder.status

    /**
     * How far behind the recorder's writer is — **pulled on a ticker, never pushed.**
     *
     * The same rule `liveLatest()` above follows, and for a sharper reason here: the drain loop already
     * updates its status flow once per written sample, so routing a depth through that flow would add
     * an allocation at the sample rate to the one coroutine whose falling behind is the thing being
     * reported. Reading two atomics from the UI's existing 1 Hz poll costs nothing on the hot path.
     */
    fun recordingLoad(): QueueLoad = recorder.queueLoad

    private var scope: CoroutineScope? = null
    private var session: KeelsonSession? = null
    /** The key each entry publishes on, so the recorder can use it as the MCAP channel topic. */
    private var keys: Map<PublishedSubject, String> = emptyMap()
    /** Hoisted out of `start()` so the reconnect flusher can reach a publisher for a buffered entry. */
    private var publishers: Map<PublishedSubject, AdvancedPublisher> = emptyMap()

    /**
     * One rig's geometry publishers.
     *
     * The calibration subjects are the one place the "one publisher per registry entry" rule breaks,
     * and it breaks for a reason that is not going away: a `PublishedSubject` names an entry, an entry
     * names a subject, and several *rigs* publish the same three subjects under different entity ids.
     * There is no entity for `Settings.entityFor` to return, so these three are left out of the global
     * [keys] and [publishers] maps entirely and built per rig here — which keeps exactly one publisher
     * per key, as before.
     */
    private class RigPublishers(
        val rig: RigCalibration,
        val keys: Map<PublishedSubject, String>,
        val publishers: Map<PublishedSubject, AdvancedPublisher>,
    )

    private var rigPublishers: List<RigPublishers> = emptyList()

    /**
     * Recent samples, for filling in a dropped link. Filled unconditionally — see [OutboxBuffer].
     */
    private val outbox = OutboxBuffer()
    private var backfillEnabled = true
    private var livelinessTokens: List<LivelinessToken> = emptyList()

    /**
     * Subjects the user has switched off.
     *
     * A flow rather than a `@Volatile var` because one field serves two readers with different needs:
     * [SubjectSink.emit] reads `.value` on the publish path, and [supervise] *collects* it so a
     * collector whose subjects have all gone off can be cancelled and its sensor listener released.
     *
     * Updated live — see [PublisherService] — so a switch takes effect on a run in progress without
     * tearing down the Zenoh session.
     */
    private val offSubjects = MutableStateFlow<Set<PublishedSubject>>(emptySet())

    /** Apply the switches. Safe before, during and after a run; a run in progress picks them up at once. */
    fun setOffSubjects(subjects: Set<PublishedSubject>) {
        offSubjects.value = subjects
    }

    /** The marks made this run, for the annotation screen. Pulled on a ticker, never pushed. */
    private val annotations = AnnotationLog()

    fun recentAnnotations(): List<Annotation> = annotations.recent()

    fun annotationCount(): Int = annotations.count()

    /**
     * Publish one operator annotation — the only publish in this class that no collector produces.
     *
     * Returns false when there is nothing for it to land in: no run, or `log_message` switched off. A
     * mark that quietly went nowhere is worse than one that says it did not happen, because the whole
     * point of pressing the button is believing the moment was captured.
     *
     * The instant is taken **here**, synchronously, rather than inside the coroutine: the press is the
     * observation, and dispatching first would stamp the payload with whenever `Dispatchers.Default`
     * got round to it. This is the one subject for which `Instant.now()` is the correct clock — the
     * rule against it elsewhere is about `SensorEvent.timestamp`, which is a boot clock and needs
     * converting, not about events that genuinely happen when they are recorded.
     */
    fun mark(message: String, severity: AnnotationSeverity, category: String): Boolean {
        val text = message.trim()
        if (text.isEmpty()) return false
        val open = session ?: return false
        val publisher = publishers[PublishedSubject.LOG_MESSAGE] ?: return false
        val runScope = scope ?: return false
        if (PublishedSubject.LOG_MESSAGE in offSubjects.value) return false
        val at = java.time.Instant.now()
        // On the run scope, so it is cancelled with the run and — more to the point — off the main
        // thread: session.publish() reaches through JNI into the Rust runtime.
        runScope.launch {
            val sink = SubjectSink(PublishedSubject.LOG_MESSAGE, open)
            val payload = FoxgloveLog.newBuilder()
                .setTimestamp(protoTimestamp(at))
                .setLevel(severity.toLogLevel())
                .setMessage(text)
                // The Log panel's namespace filter reads this. `file` and `line` are left empty:
                // they mean a source location, and there is not one.
                .setName(category)
                .build()
            val result = sink.emit(publisher, payload.toByteArray())
            // Recorded only if it actually went out, so the list cannot show a mark the file has not
            // got. `emit` returns null when the subject is switched off, which the guard above has
            // already ruled out — but it is checked rather than assumed, since the switch can move
            // between the two.
            if (result?.isSuccess == true) {
                annotations.add(
                    Annotation(
                        atEpochMillis = at.toEpochMilli(),
                        message = text,
                        severity = severity,
                        category = category,
                    )
                )
            }
        }
        return true
    }

    /** Outlives a run, because tearing one down cannot be hosted by the scope being cancelled. */
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(settings: Settings) {
        if (status.value.running) return
        // Synchronously, before anything is launched: the previous run's setup failure belongs to that
        // run, and `PublisherService` attaches a collector to this field to decide when to tear a run
        // down. Leaving it set meant the new run's watcher read the old error as its own and stopped
        // the service before the session had opened. See [PublisherStatusStore.clearError].
        statusStore.clearError()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope

        newScope.launch {
            try {
                val opened = withContext(Dispatchers.IO) {
                    KeelsonSession.openClient(
                        settings.routerEndpoints,
                        TlsCredentialStore(appContext).paths(),
                    )
                }
                session = opened
                statusStore.started()
                liveStore.clear()
                outbox.clear()
                annotations.clear()
                backfillEnabled = settings.backfillEnabled
                // Before any collector is supervised, so a subject that starts switched off never
                // registers its listener in the first place.
                setOffSubjects(settings.offSubjects())
                if (settings.recordingEnabled) recorder.start()

                // One publisher per registry entry, so adding a subject to PublishedSubject is all it
                // takes to get it declared with the right key and the right QoS.
                //
                // Every entry except the three calibration ones: those publish under a *rig's* entity
                // and there may be several rigs, so one entry no longer means one key. They are
                // declared per rig below — see [RigPublishers].
                val phoneEntries = PublishedSubject.entries.filter { it.source != SourceKind.CALIBRATION }
                keys = phoneEntries.associateWith { entry ->
                    pubsubKey(
                        settings.realm,
                        settings.entityFor(entry),
                        entry.subject,
                        settings.sourceFor(entry),
                    )
                }

                publishers = phoneEntries.associateWith { entry ->
                    opened.declarePublisher(
                        keys.getValue(entry),
                        qosForSubject(entry.subject, settings.qosOverrides),
                    )
                }
                val publishers = publishers

                rigPublishers = settings.publishingRigs().map { rig ->
                    val rigKeys = settings.rigKeys(rig)
                    RigPublishers(
                        rig = rig,
                        keys = rigKeys,
                        publishers = rigKeys.mapValues { (entry, key) ->
                            opened.declarePublisher(
                                key,
                                qosForSubject(entry.subject, settings.qosOverrides),
                            )
                        },
                    )
                }
                val rigs = rigPublishers

                declareLiveliness(opened, settings)
                // The subject tier, on the run's scope: it tracks the per-subject switches, which
                // change without restarting the run. The rig maps come along because a rig's three
                // calibration subjects are claimed under the *rig's* entity id, not the phone's.
                launch { runSubjectLiveliness(opened, listOf(keys) + rigs.map { it.keys }) }

                // One mark at the head of every run, and not only for tidiness: MCAP channels are
                // registered lazily on the first sample, so a run nobody annotates would have no
                // `log_message` channel at all and a saved Foxglove layout pointing at that topic
                // would find nothing there. Its own category, so a reader wanting only the operator's
                // marks switches this one namespace off.
                mark("Recording started", AnnotationSeverity.Info, SYSTEM_CATEGORY)

                // Not a subject collector — the connection watchdog runs whatever is switched on.
                launch { watchConnection(opened) }

                // Each collector runs only while at least one of its subjects is switched on, so
                // switching a whole sensor off releases its listener rather than merely dropping its
                // samples at the sink. The names are matched against COLLECTOR_GROUPS, which a test
                // holds to covering every registry entry exactly once.
                // Two flows, one collector. They are separate Android listeners but a single
                // lifecycle: see LOCATION_SUBJECTS for why the sentences cannot outlive the fused
                // request that produces them.
                supervised("location", LOCATION_SUBJECTS) {
                    coroutineScope {
                        launch { runNmea(opened, publishers.of(PublishedSubject.RAW_NMEA0183)) }
                        launch { runGnssStatus(opened, publishers) }
                        runLocation(opened, publishers, settings)
                    }
                }
                supervised("accel", setOf(PublishedSubject.LINEAR_ACCEL)) {
                    runAccel(opened, publishers.of(PublishedSubject.LINEAR_ACCEL), settings.imuSource, settings.rate(Subjects.LINEAR_ACCELERATION_MPSS))
                }
                supervised("gyro", setOf(PublishedSubject.ANGULAR_VEL)) {
                    runGyro(opened, publishers.of(PublishedSubject.ANGULAR_VEL), settings.imuSource, settings.rate(Subjects.ANGULAR_VELOCITY_RADPS))
                }
                supervised("orientation", ORIENTATION_SUBJECTS) {
                    runOrientation(opened, publishers, settings.imuSource, settings.rate(Subjects.ORIENTATION_QUATERNION))
                }
                supervised("imuTemperature", IMU_TEMPERATURE_SUBJECTS) {
                    runImuTemperature(opened, publishers.of(PublishedSubject.IMU_TEMPERATURE),
                        settings.rate(Subjects.IMU_TEMPERATURE_CELSIUS))
                }
                supervised("attitude", ATTITUDE_SUBJECTS) {
                    runAttitude(opened, publishers, settings.imuSource, settings.rate(Subjects.ROLL_DEG))
                }
                supervised("attitudeRates", ATTITUDE_RATE_SUBJECTS) {
                    runAttitudeRates(opened, publishers, settings.rate(Subjects.ROLL_RATE_DEGPS))
                }
                supervised("magnetometer", setOf(PublishedSubject.MAGNETIC_FIELD)) {
                    runMagnetometer(opened, publishers.of(PublishedSubject.MAGNETIC_FIELD), settings.imuSource, settings.rate(Subjects.MAGNETIC_FIELD_GAUSS))
                }
                supervised("pressure", setOf(PublishedSubject.AIR_PRESSURE)) {
                    runPressure(opened, publishers.of(PublishedSubject.AIR_PRESSURE), settings.rate(Subjects.AIR_PRESSURE_PA))
                }
                supervised("illuminance", setOf(PublishedSubject.ILLUMINANCE)) {
                    runIlluminance(opened, publishers.of(PublishedSubject.ILLUMINANCE), settings.rate(Subjects.ILLUMINANCE_LUX))
                }
                // Audio and the camera stay behind their start-time flags as well as the switch: both
                // decide a foreground-service type and a runtime permission at `startForeground`, which
                // a mid-run switch cannot change. Their rows say so.
                if (settings.audioEnabled) {
                    supervised("audio", setOf(PublishedSubject.AUDIO)) {
                        runAudio(opened, publishers.of(PublishedSubject.AUDIO), settings)
                    }
                }
                // Either camera subject keeps the collector alive; `runCamera` decides which use
                // cases to bind from the switches.
                if (settings.cameraEnabled || settings.videoEnabled) {
                    supervised("camera", CAMERA_SUBJECTS) {
                        runCamera(opened, publishers.of(PublishedSubject.IMAGE_COMPRESSED), settings)
                    }
                }
                supervised("battery", BATTERY_SUBJECTS) {
                    runBattery(opened, publishers, settings.rate(Subjects.BATTERY_STATE_OF_CHARGE_PCT))
                }
                supervised("radio", RADIO_SUBJECTS) {
                    runRadio(opened, publishers, settings.rate(Subjects.RADIO_RSRP_DBM))
                }
                supervised("calibration", CALIBRATION_SUBJECTS) {
                    runCalibration(opened, rigs, settings)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                statusStore.setupFailed(t.message ?: t.javaClass.simpleName)
                stopInternal()
            }
        }
    }

    fun stop() {
        stopInternal()
    }

    /**
     * Returns immediately; the teardown happens on [closeScope].
     *
     * Callers are `Service` lifecycle callbacks on the main thread, and `Session.close()` reaches
     * through JNI into the Rust runtime to undeclare publishers and tear down transports. Measured on
     * a Pixel 6 it returns in about a millisecond, connected or not — but it is still an unbounded
     * I/O call, and the main thread is not the place for it.
     */
    private fun stopInternal() {
        val runScope = scope
        val openSession = session
        val tokens = livelinessTokens
        // Null all three before anything slow happens, so a following start() cannot see a
        // half-torn-down run and a second stop() cannot close the same session twice.
        scope = null
        session = null
        publishers = emptyMap()
        rigPublishers = emptyList()
        livelinessTokens = emptyList()
        statusStore.stopped()
        // **Not stopped here.** The collectors keep publishing for as long as it takes the cancel
        // below to land — tens of milliseconds, and at ~217 samples/s that is a dozen samples — and a
        // recorder stopped first drops every one of them on the floor without counting them. Measured:
        // a run that published 88 102 samples wrote 88 090 messages. So the recorder is stopped after
        // the collectors are, with a token so a Stop immediately followed by a Start cannot have this
        // one close the *new* run's file.
        val recorderToken = recorder.runToken()
        if (runScope == null && openSession == null) {
            recorder.stop(recorderToken)
            return
        }

        closeScope.launch {
            val startedAt = SystemClock.uptimeMillis()
            // Cancellation is asynchronous. Joining is what guarantees no publish is still in flight
            // inside JNI when the session is closed underneath it. Safe from here — joining the run
            // scope from inside itself would deadlock, which is why this runs on a separate scope.
            runScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            // Now that nothing can publish another sample, the queue can be closed and drained.
            recorder.stop(recorderToken)
            // Before the session goes: closing it would drop the tokens anyway, but undeclaring gives
            // consumers a leave event now rather than one that waits on transport teardown.
            tokens.forEach {
                try {
                    it.undeclare()
                } catch (t: Throwable) {
                    Log.w(TAG, "liveliness undeclare failed", t)
                }
            }
            try {
                openSession?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "session close failed", t)
            }
            Log.i(TAG, "session closed in ${SystemClock.uptimeMillis() - startedAt} ms")
        }
    }

    /**
     * The source tier — one token per distinct (entity, source) pair, specification §5.1.
     *
     * The protocol models presence per *source* while this app publishes under several. The three
     * configurable ids all default to `phone`, and the two radio links add fixed `cellular` and `wifi`
     * ids, so a default run declares three source tokens no matter how many subjects there are. A
     * calibrated rig adds one more under the rig's own entity: the pair is what a token identifies, and
     * publishing a rig's geometry under a token that only ever named the phone would leave a consumer
     * watching the rig with nothing to see.
     *
     * Each pair also gets the [legacyLivelinessKey] shape, for one release — see that function.
     *
     * The **subject** tier is not here: it changes with the per-subject switches while a run is going,
     * so it lives in [runSubjectLiveliness] on the run's own scope. This half is fixed for the run.
     *
     * Declared for the configured sources even in IMU-only mode: the token says the process is alive,
     * not that GNSS is flowing. Failing to declare is not fatal — liveliness is discovery, not the data
     * path, and a logging run should survive losing it.
     */
    private fun declareLiveliness(session: KeelsonSession, settings: Settings) {
        // Every source this run actually publishes under, taken from the registry rather than listed by
        // hand — otherwise the radio links would publish on keys no liveliness token covers, and a
        // consumer watching for the source would never see it join.
        val sources = PublishedSubject.entries
            .filter { it.source != SourceKind.CALIBRATION }
            .map { settings.entityFor(it) to settings.sourceFor(it) }
            .toMutableSet()
        // One more pair per publishing rig. The rig entities are not reachable through `entityFor`
        // any more — several rigs share the three calibration entries — so without this a consumer
        // watching for a rig would never see it join, even while its geometry was on the bus.
        settings.publishingRigs().forEach {
            sources += it.entityId to settings.calibrationSource
        }
        val startedAt = SystemClock.uptimeMillis()
        livelinessTokens = sources
            .flatMap { (entityId, sourceId) ->
                listOf(
                    sourceLivelinessKey(settings.realm, entityId, sourceId),
                    legacyLivelinessKey(settings.realm, entityId, sourceId),
                )
            }
            .mapNotNull { key -> declareToken(session, key) }
        Log.i(
            TAG,
            "declared ${livelinessTokens.size} source tokens in " +
                "${SystemClock.uptimeMillis() - startedAt} ms",
        )
    }

    /**
     * One token or none — a failure here costs discovery of one key, never the run.
     *
     * Shared by both tiers because the subject tier declares ~52 of these: a throw on the fiftieth
     * would otherwise take the run down over a key nothing publishes to yet.
     */
    private fun declareToken(session: KeelsonSession, key: String): LivelinessToken? = try {
        session.declareLivelinessToken(key)
    } catch (t: Throwable) {
        Log.w(TAG, "liveliness token for $key failed; continuing without it", t)
        null
    }

    /**
     * The subject tier — one token per subject this phone claims, specification §5.2, kept in step with
     * the per-subject switches for as long as the run lasts.
     *
     * **This is what makes the phone legible to a health monitor.** Upstream's `entity_health` connector
     * reads a source that declares only the coarse token as advertising *nothing*, and drops every
     * subject it was watching as `NOT_ADVERTISED` — a statement about the monitor's own config — so the
     * phone contributes nothing to a vessel's score however well it is running. These tokens are what
     * let it tell "advertised but silent" from "never claimed".
     *
     * Collected off [offSubjects] rather than declared once, because the per-subject switches are the
     * one setting that does not restart the run: a switch that changed nothing on the wire would leave
     * the phone claiming a subject it has been told to stop publishing. The diff against `held` is what
     * keeps one switch to one token — rebuilding the set would undeclare and redeclare all fifty-two,
     * and every consumer would see the whole surface leave and rejoin.
     *
     * Note this is the first time a per-subject switch does anything on the wire; see the README.
     *
     * Owns its tokens, and `stopInternal()` deliberately does not: the `finally` runs when the run's
     * scope is cancelled, which is the same moment. Two owners would undeclare the same token twice.
     */
    private suspend fun runSubjectLiveliness(
        session: KeelsonSession,
        keyMaps: List<Map<PublishedSubject, String>>,
    ) {
        val held = mutableMapOf<String, LivelinessToken>()
        // Fixed for the run: absent hardware has no capability to declare, and a phone does not grow a
        // barometer mid-run.
        val unavailable = unavailableSubjects(appContext)
        try {
            offSubjects.collect { off ->
                val wanted = subjectLivelinessKeys(keyMaps, off, unavailable)
                (held.keys - wanted).forEach { key ->
                    try {
                        held.remove(key)?.undeclare()
                    } catch (t: Throwable) {
                        Log.w(TAG, "liveliness undeclare failed for $key", t)
                    }
                }
                val startedAt = SystemClock.uptimeMillis()
                val added = (wanted - held.keys).count { key ->
                    declareToken(session, key)?.also { held[key] = it } != null
                }
                if (added > 0) {
                    Log.i(
                        TAG,
                        "declared $added subject tokens in ${SystemClock.uptimeMillis() - startedAt} " +
                            "ms; ${held.size} claimed",
                    )
                }
            }
        } finally {
            held.values.forEach {
                try {
                    it.undeclare()
                } catch (t: Throwable) {
                    Log.w(TAG, "liveliness undeclare failed", t)
                }
            }
        }
    }

    /** Launches [supervise] for one collector. Called from `start()`'s scope, one per group. */
    private fun CoroutineScope.supervised(
        name: String,
        subjects: Set<PublishedSubject>,
        block: suspend () -> Unit,
    ): Job = launch { supervise(name, subjects, block) }

    /**
     * Run [block] for as long as any of [subjects] is switched on, and not a moment longer.
     *
     * The sink's gate already keeps a switched-off subject off the bus and out of the file, but it
     * cannot release the hardware: the Android listener is registered inside the collector's
     * `callbackFlow` and only `awaitClose` unregisters it. Cancelling the collector is therefore what
     * turns a switch into an actual saving — which for a 50 Hz IMU stream is the whole point.
     *
     * A collector is only stopped once **every** subject riding it is off. Four subjects come off one
     * `Location` callback and twelve off one radio poll, so switching one of them off must not take the
     * others' data with it.
     */
    private suspend fun supervise(
        name: String,
        subjects: Set<PublishedSubject>,
        block: suspend () -> Unit,
    ): Unit = coroutineScope {
        var job: Job? = null
        // Never completes — a StateFlow collection ends only when the run's scope is cancelled.
        offSubjects.collect { off ->
            val wanted = subjects.any { it !in off }
            when {
                wanted && job == null -> {
                    job = launch { block() }
                }
                !wanted && job != null -> {
                    Log.i(TAG, "$name switched off; releasing its sensor")
                    // Joined, not just cancelled: the next enable must not race a listener that is
                    // still unregistering, and awaitClose runs during the cancellation.
                    job?.cancelAndJoin()
                    job = null
                }
            }
        }
    }

    /**
     * The only reliable way to know whether anything is receiving.
     *
     * A `put` on a session that has lost its router still succeeds — measured on a Pixel 6, ~9000
     * successful puts landed on an empty bus during a 26 s outage — so publish results cannot detect
     * a dropped link. The router list can.
     */
    private suspend fun watchConnection(session: KeelsonSession) {
        var wasConnected = false
        // The last poll that saw a router. Replay starts here rather than from when the drop was
        // *noticed*, which is up to a poll interval later — plus however long Zenoh took to tear the
        // transport down. Overlapping is deliberate: a couple of seconds of duplicates beats a hole.
        var lastConnectedNanos = System.currentTimeMillis() * 1_000_000L
        // The outbox's add count at that same poll, and half of the same anchor: the timestamp says
        // where the replay window starts, this says how many samples have been buffered into it since,
        // which is the only way to tell whether the ring has overflowed the window.
        var addedWhenConnected = outbox.added
        // Gaps already closed this run. The status field is a run total, so the open gap's loss is
        // added to this rather than replacing it.
        var lostInClosedGaps = 0L

        // delay() is cancellable, so stopping the scope ends this loop.
        while (true) {
            val connected = session.isConnectedToRouter()
            statusStore.connectionChanged(connected)

            if (backfillEnabled) {
                // Recomputed every poll, open gap included: an outage that has already outrun the
                // buffer should say so while it is still happening, not turn up afterwards as a
                // "Replayed N samples" that quietly means fewer than were lost.
                val lost = outbox.lostSince(addedWhenConnected)
                statusStore.replayLostChanged(lostInClosedGaps + lost)
                if (connected && !wasConnected) {
                    if (lost > 0) {
                        Log.w(TAG, "outage outran the outbox; $lost samples cannot be replayed")
                        lostInClosedGaps += lost
                    }
                    // Reconnected. Flush on this coroutine rather than a collector's, so pacing delays
                    // never stall a sensor stream.
                    replay(session, lastConnectedNanos)
                }
            }
            if (connected) {
                lastConnectedNanos = System.currentTimeMillis() * 1_000_000L
                addedWhenConnected = outbox.added
            }
            wasConnected = connected
            delay(CONNECTION_POLL_MILLIS)
        }
    }

    /**
     * Republish everything buffered since the link was last known good, oldest first.
     *
     * **Paced, and that is not optional.** Every QoS profile is `DROP` and `put` reports success
     * regardless, so an unpaced burst is shed by the egress queue with no signal at all — the buffer
     * would empty, the counters would climb, and the data would be gone. `BLOCK` is not the answer
     * either: it stalls the producer, and the sensor `callbackFlow` channels hold only 64 samples, so
     * about a second of stall starts dropping *live* data.
     *
     * Oldest first because the router keeps a latest-value store per key — replaying newest first would
     * leave a stale value behind.
     */
    private suspend fun replay(session: KeelsonSession, sinceNanos: Long) {
        val pending = outbox.since(sinceNanos)
        if (pending.isEmpty()) return
        Log.i(TAG, "reconnected; replaying ${pending.size} buffered samples")
        statusStore.replayStarted(pending.size)

        var sent = 0
        pending.forEach { entry ->
            // A subject switched off since these were buffered stays off. The samples are real and are
            // already in the MCAP file, but the switch says "nothing of this on the bus" — replaying it
            // minutes later would put those keys back in front of a subscriber who watched them stop.
            if (entry.subject in offSubjects.value) return@forEach
            val publisher = publishers[entry.subject] ?: return@forEach
            val instant = java.time.Instant.ofEpochSecond(
                Math.floorDiv(entry.enclosedAtNanos, 1_000_000_000L),
                Math.floorMod(entry.enclosedAtNanos, 1_000_000_000L).toLong(),
            )
            // Re-enclose with the *original* instant, so a consumer sees when the sample was taken
            // rather than when it was resent.
            session.publish(publisher, enclose(entry.payload, instant))
            sent++
            if (sent % REPLAY_BATCH == 0) delay(REPLAY_BATCH_PAUSE_MILLIS)
        }
        statusStore.replayFinished(sent)
        Log.i(TAG, "replayed $sent buffered samples")
    }

    /**
     * The GNSS stream: `location_fix`, plus speed and course off the *same* `Location` object.
     *
     * They share one callback because they are one observation — subscribing to the provider three
     * times would triple the GNSS work for no extra information, and would let the three drift apart in
     * time. That is also why they have no independent rate ([PublishedSubject.rateOwner]).
     */
    /**
     * The local declination, from the last fix, for the compass collector to turn a magnetic heading
     * into a true one.
     *
     * Volatile and nullable: it is written by the location collector and read by the orientation one,
     * both on `Dispatchers.Default`, and null means "no fix yet", which is a state true north has to
     * survive rather than paper over.
     */
    @Volatile
    private var declinationDegrees: Float? = null

    /**
     * Whether the last fix carried an altitude, for the fix-quality collector to tell 2D from 3D.
     *
     * The same shape as [declinationDegrees] and for the same reason: written by the location
     * collector, read by another on `Dispatchers.Default`, and null means "no fix yet" — a state the
     * quality reading has to survive rather than paper over, since before the first fix there is
     * genuinely nothing to describe.
     */
    @Volatile
    private var lastFixHadAltitude: Boolean? = null

    private suspend fun runLocation(
        session: KeelsonSession,
        publishers: Map<PublishedSubject, AdvancedPublisher>,
        settings: Settings,
    ) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "ACCESS_FINE_LOCATION not granted; skipping location publisher")
            // Said on the rows rather than only in the log. Returning quietly left the four GNSS
            // subjects reading "Waiting for the first sample" for the whole of an IMU-only run, which
            // is the one state that looks like the app is about to work and never will.
            LOCATION_SUBJECTS.forEach {
                statusStore.failed(it, "Location permission was not granted for this run")
            }
            return
        }
        val frameId = settings.locationSource
        val rate = settings.rate(Subjects.LOCATION_FIX)
        val publisher = publishers.of(PublishedSubject.LOCATION_FIX)
        val speedPub = publishers.of(PublishedSubject.SPEED_OVER_GROUND)
        val coursePub = publishers.of(PublishedSubject.COURSE_OVER_GROUND)
        val variationPub = publishers.of(PublishedSubject.MAGNETIC_VARIATION)
        val speedSink = SubjectSink(PublishedSubject.SPEED_OVER_GROUND, session)
        val courseSink = SubjectSink(PublishedSubject.COURSE_OVER_GROUND, session)
        val variationSink = SubjectSink(PublishedSubject.MAGNETIC_VARIATION, session)
        val horizontalPub = publishers.of(PublishedSubject.ACCURACY_HORIZONTAL)
        val verticalPub = publishers.of(PublishedSubject.ACCURACY_VERTICAL)
        val horizontalSink = SubjectSink(PublishedSubject.ACCURACY_HORIZONTAL, session)
        val verticalSink = SubjectSink(PublishedSubject.ACCURACY_VERTICAL, session)
        val mslPub = publishers.of(PublishedSubject.ALTITUDE_ABOVE_MSL)
        val undulationPub = publishers.of(PublishedSubject.FIX_UNDULATION)
        val mslSink = SubjectSink(PublishedSubject.ALTITUDE_ABOVE_MSL, session)
        val undulationSink = SubjectSink(PublishedSubject.FIX_UNDULATION, session)
        // One per run: the converter caches the geoid model, so the first fix pays for loading it and
        // the rest are cheap. See MslAltitudeResolver for why it is asked at all.
        val msl = MslAltitudeResolver(appContext)
        val sink = SubjectSink(PublishedSubject.LOCATION_FIX, session)
        sink.guard {
            LocationProvider(appContext)
                .updates(
                    intervalMillis = rate.toIntervalMillis(),
                    onShed = { statusStore.shed(PublishedSubject.LOCATION_FIX) },
                )
                .collect { update ->
                // Why there is no fix, when that is knowable, on every subject that rides this
                // callback — all four go silent together, so all four have to account for it.
                val loc = when (update) {
                    is LocationUpdate.Unavailable -> {
                        LOCATION_SUBJECTS.forEach { statusStore.failed(it, update.reason) }
                        return@collect
                    }
                    // Cleared without waiting for a fix: the time to first fix after location is
                    // switched back on is tens of seconds, and the old reason sitting there through
                    // all of it reads as the setting not having taken.
                    LocationUpdate.Available -> {
                        LOCATION_SUBJECTS.forEach { statusStore.recovered(it) }
                        return@collect
                    }
                    is LocationUpdate.Fix -> update.location
                }
                // The provider's own UTC fix time. Mock and some network fixes leave it at 0, in which
                // case there is nothing better than now.
                val observedAt = if (loc.time > 0L) protoTimestamp(loc.time * 1_000_000L) else protoTimestamp()
                val observedAtMillis = if (loc.time > 0L) loc.time else System.currentTimeMillis()
                val fix = LocationFix.newBuilder()
                    .setTimestamp(observedAt)
                    .setFrameId(frameId)
                    .setLatitude(loc.latitude)
                    .setLongitude(loc.longitude)
                    // No presence on a proto3 double: an unset altitude and a 0.0 altitude are
                    // byte-identical, so "unknown" and "sea level" cannot be told apart here.
                    .setAltitude(if (loc.hasAltitude()) loc.altitude else 0.0)
                    .apply {
                        // Both or nothing: a 0.0 in an unknown slot would read as a perfectly known
                        // axis, which is worse than declaring the whole matrix unknown.
                        if (loc.hasAccuracy() && loc.hasVerticalAccuracy()) {
                            addAllPositionCovariance(
                                diagonalEnuCovariance(
                                    horizontalMetres = loc.accuracy.toDouble(),
                                    verticalMetres = loc.verticalAccuracyMeters.toDouble(),
                                )
                            )
                            positionCovarianceType = LocationFix.PositionCovarianceType.APPROXIMATED
                        }
                    }
                    .build()
                val fixEmitted = sink.emit(publisher, fix.toByteArray()) != null
                // The raw platform values, not the zero-defaulted ones published below: a map that
                // trusted the wire bearing would draw a heading arrow due north on a stationary phone.
                // Skipped entirely when the fix is switched off: the track is what that subject looks
                // like on screen, so it must not keep drawing after the samples stop.
                if (fixEmitted) {
                    liveStore.recordFix(
                        TrackPoint(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            accuracyMetres = if (loc.hasAccuracy()) loc.accuracy else null,
                            bearingDegrees = if (loc.hasBearing()) loc.bearing else null,
                            timeMillis = if (loc.time > 0L) loc.time else System.currentTimeMillis(),
                        )
                    )
                }

                // Published on every fix, defaulting to 0.0 when the platform reports no value.
                //
                // A deliberate choice, and it has a cost worth knowing: proto3 cannot distinguish an
                // absent float from a measured one, so a consumer cannot tell "no bearing available"
                // from "heading due north" — both arrive as 0.0. Accepted because an unbroken series is
                // worth more here than the distinction, and because a stationary phone genuinely is
                // doing zero knots. `hasSpeed()`/`hasBearing()` remain the honest signal if this is ever
                // revisited; on a stationary Pixel 6 speed was present on 35 of 36 fixes and bearing on
                // only 2, so this mostly changes course.
                // Skipped when absent, which is the opposite of the two below — and the difference is
                // not a taste one. A missing speed published as `0.0` says the phone is stationary,
                // which is usually true; a missing accuracy published as `0.0` says the fix is exact,
                // which is never true and is the most dangerous thing this app could put on the bus.
                //
                // Each is checked on its own, unlike the covariance matrix these also feed, which
                // needs both or neither: a zero in one slot of that matrix would read as a perfectly
                // known axis. As separate subjects they simply stand or fall separately.
                if (loc.hasAccuracy()) {
                    horizontalSink.emit(
                        horizontalPub,
                        timestampedFloat(observedAt, loc.accuracy).toByteArray(),
                        loc.accuracy,
                    )
                }
                if (loc.hasVerticalAccuracy()) {
                    verticalSink.emit(
                        verticalPub,
                        timestampedFloat(observedAt, loc.verticalAccuracyMeters).toByteArray(),
                        loc.verticalAccuracyMeters,
                    )
                }

                // Altitude above mean sea level, and the geoid correction that explains why it is
                // tens of metres from the ellipsoidal one in `location_fix`. Reported when the fix
                // carries it and derived from the same ellipsoidal height when it does not — see
                // MslAltitudeResolver, including what that costs in provenance.
                //
                // Skipped rather than zeroed when there is none, like the accuracies above and unlike
                // speed and course. Sharper here than anywhere else in this app: on a vessel `0.0 m`
                // above sea level is *plausible*, so a defaulted zero would not look wrong to anyone.
                val mslMetres = msl.metresFor(loc)
                if (mslMetres != null) {
                    mslSink.emit(
                        mslPub,
                        timestampedFloat(observedAt, mslMetres.toFloat()).toByteArray(),
                        mslMetres.toFloat(),
                    )
                    // The undulation is the difference of two knowns, so it needs the ellipsoidal
                    // height as well — which a fix can be missing even when the MSL value was derived
                    // from something else the platform had.
                    if (loc.hasAltitude()) {
                        val undulation = undulationMetres(loc.altitude, mslMetres).toFloat()
                        undulationSink.emit(
                            undulationPub,
                            timestampedFloat(observedAt, undulation).toByteArray(),
                            undulation,
                        )
                    }
                }

                val knots = if (loc.hasSpeed()) metresPerSecondToKnots(loc.speed) else 0f
                speedSink.emit(speedPub, timestampedFloat(observedAt, knots).toByteArray(), knots)
                val bearing = if (loc.hasBearing()) loc.bearing else 0f
                courseSink.emit(coursePub, timestampedFloat(observedAt, bearing).toByteArray(), bearing)

                // Declination, from the world magnetic model built into the platform. Computed here
                // because it is a function of where you are, and handed to the compass collector, which
                // cannot ask for a position of its own.
                val declination = GeomagneticField(
                    loc.latitude.toFloat(),
                    loc.longitude.toFloat(),
                    if (loc.hasAltitude()) loc.altitude.toFloat() else 0f,
                    observedAtMillis,
                ).declination
                declinationDegrees = declination
                lastFixHadAltitude = loc.hasAltitude()
                variationSink.emit(
                    variationPub,
                    timestampedFloat(observedAt, declination).toByteArray(),
                    declination,
                )
            }
        }
    }

    /**
     * The GNSS chip's own sentences, passed through untouched.
     *
     * Nothing here starts the GNSS engine — [NmeaProvider] only listens to one that is already
     * running, which on this app means the location collector's fused request. So this subject can be
     * switched on with every other GNSS subject off and go quiet a few seconds later, which is honest:
     * there is no receiver talking.
     *
     * The value retained for the live view is the sentence's **length**, because a sentence has no
     * single number in it and a row that plotted, say, the fix quality would be plotting one sentence
     * type and ignoring the rest. Length at least moves with what the receiver is saying.
     */
    private suspend fun runNmea(session: KeelsonSession, publisher: AdvancedPublisher) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "ACCESS_FINE_LOCATION not granted; skipping the NMEA publisher")
            statusStore.failed(
                PublishedSubject.RAW_NMEA0183,
                "Location permission was not granted for this run",
            )
            return
        }
        val sink = SubjectSink(PublishedSubject.RAW_NMEA0183, session)
        sink.guard {
            NmeaProvider(appContext)
                .sentences(onShed = { statusStore.shed(PublishedSubject.RAW_NMEA0183) })
                .collect { nmea ->
                // The callback's own instant, not now: sentences arrive in bursts once per fix, and
                // stamping them at publish time would spread one fix's worth across the gaps between
                // them. See `nmeaEpochNanos` for why the value is checked rather than trusted.
                val observedAtNanos = nmeaEpochNanos(
                    timestampMillis = nmea.timestampMillis,
                    elapsedNanosNow = SystemClock.elapsedRealtimeNanos(),
                    wallMillisNow = System.currentTimeMillis(),
                )
                val payload = TimestampedString.newBuilder()
                    .setTimestamp(protoTimestamp(observedAtNanos))
                    .setValue(nmea.sentence)
                    .build()
                sink.emit(publisher, payload.toByteArray(), nmea.sentence.length.toFloat())
            }
        }
    }

    /**
     * The receiver reporting on itself: how much sky it can hear, how much it is using, and what kind
     * of solution that adds up to.
     *
     * Three subjects off one callback, which is why they share a rate. Like the sentences, nothing here
     * starts the GNSS engine — `GnssStatus` reports on one that is running, so this lives on the
     * location collector and goes quiet when that stops.
     *
     * The quality payload leaves `rtk_status` and `integrity` unset. Android reports neither, and
     * their zero values already mean "not reported", which is the truth — see [fixQualityOf] for the
     * rest of the mapping, including why `FIX_NO` alongside a published position is a real state
     * rather than a contradiction.
     */
    private suspend fun runGnssStatus(
        session: KeelsonSession,
        publishers: Map<PublishedSubject, AdvancedPublisher>,
    ) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "ACCESS_FINE_LOCATION not granted; skipping the GNSS status publisher")
            GNSS_STATUS_SUBJECTS.forEach {
                statusStore.failed(it, "Location permission was not granted for this run")
            }
            return
        }
        val visibleSink = SubjectSink(PublishedSubject.SATELLITES_VISIBLE, session)
        val usedSink = SubjectSink(PublishedSubject.SATELLITES_USED, session)
        val qualitySink = SubjectSink(PublishedSubject.FIX_QUALITY, session)
        val visiblePub = publishers.of(PublishedSubject.SATELLITES_VISIBLE)
        val usedPub = publishers.of(PublishedSubject.SATELLITES_USED)
        val qualityPub = publishers.of(PublishedSubject.FIX_QUALITY)

        qualitySink.guard {
            GnssStatusProvider(appContext)
                .status(onShed = { statusStore.shed(PublishedSubject.SATELLITES_USED) })
                .collect { sample ->
                // The callback carries no timestamp of its own and describes the receiver's state
                // right now, so this is one of the few places the observation time genuinely is now.
                val at = protoTimestamp()
                visibleSink.emit(
                    visiblePub,
                    timestampedInt(at, sample.visible).toByteArray(),
                    sample.visible.toFloat(),
                )
                usedSink.emit(
                    usedPub,
                    timestampedInt(at, sample.used).toByteArray(),
                    sample.used.toFloat(),
                )

                val quality = fixQualityOf(sample.used, lastFixHadAltitude)
                val payload = LocationFixQuality.newBuilder()
                    .setTimestamp(at)
                    .setFixType(
                        when (quality.kind) {
                            FixKind.NoFix -> LocationFixQuality.FixType.FIX_NO
                            FixKind.TwoD -> LocationFixQuality.FixType.FIX_2D
                            FixKind.ThreeD -> LocationFixQuality.FixType.FIX_3D
                        }
                    )
                    .setPosType(
                        // A phone does single-point positioning and nothing else it can prove: no
                        // SBAS, RTK or PPP is exposed through any Android API, so claiming one would
                        // be inventing precision. Not solving at all is its own value.
                        if (quality.solving) {
                            LocationFixQuality.PosType.POS_TYPE_SINGLE
                        } else {
                            LocationFixQuality.PosType.POS_TYPE_NO_SOLUTION
                        }
                    )
                    .build()
                qualitySink.emit(qualityPub, payload.toByteArray(), quality.kind.ordinal.toFloat())
            }
        }
    }

    private suspend fun runAccel(
        session: KeelsonSession,
        publisher: AdvancedPublisher,
        frameId: String,
        rate: SensorRate,
    ) {
        val sink = SubjectSink(PublishedSubject.LINEAR_ACCEL, session)
        sink.guard {
            var checked = false
            ImuProvider(appContext)
                .linearAcceleration(rate.toRateUs(), onShed = { statusStore.shed(PublishedSubject.LINEAR_ACCEL) })
                .collect { s ->
                val observedAtNanos = SensorClock.epochNanosNow(s.elapsedNanos)
                if (!checked) {
                    checked = true
                    warnIfClockBaseLooksWrong(Subjects.LINEAR_ACCELERATION_MPSS, observedAtNanos)
                }
                val msg = Decomposed3DVector.newBuilder()
                    .setTimestamp(protoTimestamp(observedAtNanos))
                    .setFrameId(frameId)
                    .setVector(vec3(s.x, s.y, s.z))
                    .build()
                sink.emit(publisher, msg.toByteArray(), magnitude(s.x, s.y, s.z))
            }
        }
    }

    private suspend fun runGyro(
        session: KeelsonSession,
        publisher: AdvancedPublisher,
        frameId: String,
        rate: SensorRate,
    ) {
        val sink = SubjectSink(PublishedSubject.ANGULAR_VEL, session)
        sink.guard {
            var checked = false
            ImuProvider(appContext)
                .angularVelocity(rate.toRateUs(), onShed = { statusStore.shed(PublishedSubject.ANGULAR_VEL) })
                .collect { s ->
                val observedAtNanos = SensorClock.epochNanosNow(s.elapsedNanos)
                if (!checked) {
                    checked = true
                    warnIfClockBaseLooksWrong(Subjects.ANGULAR_VELOCITY_RADPS, observedAtNanos)
                }
                val msg = Decomposed3DVector.newBuilder()
                    .setTimestamp(protoTimestamp(observedAtNanos))
                    .setFrameId(frameId)
                    .setVector(vec3(s.x, s.y, s.z))
                    .build()
                sink.emit(publisher, msg.toByteArray(), magnitude(s.x, s.y, s.z))
            }
        }
    }

    private suspend fun runOrientation(
        session: KeelsonSession,
        publishers: Map<PublishedSubject, AdvancedPublisher>,
        frameId: String,
        rate: SensorRate,
    ) {
        val publisher = publishers.of(PublishedSubject.ORIENTATION)
        val magneticPub = publishers.of(PublishedSubject.HEADING_MAGNETIC)
        val truePub = publishers.of(PublishedSubject.HEADING_TRUE_NORTH)
        val accuracyPub = publishers.of(PublishedSubject.HEADING_ACCURACY)
        val sink = SubjectSink(PublishedSubject.ORIENTATION, session)
        val magneticSink = SubjectSink(PublishedSubject.HEADING_MAGNETIC, session)
        val trueSink = SubjectSink(PublishedSubject.HEADING_TRUE_NORTH, session)
        val accuracySink = SubjectSink(PublishedSubject.HEADING_ACCURACY, session)
        sink.guard {
            var checked = false
            ImuProvider(appContext)
                .orientation(rate.toRateUs(), onShed = { statusStore.shed(PublishedSubject.ORIENTATION) })
                .collect { q ->
                val observedAtNanos = SensorClock.epochNanosNow(q.elapsedNanos)
                if (!checked) {
                    checked = true
                    warnIfClockBaseLooksWrong(Subjects.ORIENTATION_QUATERNION, observedAtNanos)
                }
                val at = protoTimestamp(observedAtNanos)
                val msg = TimestampedQuaternion.newBuilder()
                    .setTimestamp(at)
                    .setValue(
                        Quaternion.newBuilder()
                            .setX(q.x.toDouble()).setY(q.y.toDouble()).setZ(q.z.toDouble()).setW(q.w.toDouble())
                            .build()
                    )
                    .build()
                sink.emit(publisher, msg.toByteArray())

                magneticSink.emit(
                    magneticPub,
                    timestampedFloat(at, q.headingMagneticDegrees).toByteArray(),
                    q.headingMagneticDegrees,
                )
                // Absent rather than zero on a device that reports no estimate: "0°" would read as a
                // perfect heading, which is the opposite of what not knowing means.
                q.headingAccuracyDegrees?.let { accuracy ->
                    accuracySink.emit(
                        accuracyPub,
                        timestampedFloat(at, accuracy).toByteArray(),
                        accuracy,
                    )
                }
                // True north needs the local declination, which needs a position. Until a fix arrives
                // this subject simply does not publish — a heading silently referenced to the wrong
                // north is worse than one that is missing, and the magnetic heading is there throughout.
                declinationDegrees?.let { declination ->
                    val trueHeading = normaliseHeadingDegrees(q.headingMagneticDegrees + declination)
                    trueSink.emit(
                        truePub,
                        timestampedFloat(at, trueHeading).toByteArray(),
                        trueHeading,
                    )
                }
            }
        }
    }

    /**
     * The attitude as three readable angles.
     *
     * Its own rotation-vector registration rather than a share of the orientation collector's, so the
     * rate dial on these subjects is real — see the registry. Nothing is computed here that
     * `getOrientation` was not already computing; two of the three were simply being discarded.
     *
     * The `frameId` is the IMU's, because these describe the phone: what its attitude means for the
     * vessel is the rig calibration's business, not this collector's.
     */
    private suspend fun runAttitude(
        session: KeelsonSession,
        publishers: Map<PublishedSubject, AdvancedPublisher>,
        frameId: String,
        rate: SensorRate,
    ) {
        val rollPub = publishers.of(PublishedSubject.ROLL)
        val pitchPub = publishers.of(PublishedSubject.PITCH)
        val yawPub = publishers.of(PublishedSubject.YAW)
        val rollSink = SubjectSink(PublishedSubject.ROLL, session)
        val pitchSink = SubjectSink(PublishedSubject.PITCH, session)
        val yawSink = SubjectSink(PublishedSubject.YAW, session)
        rollSink.guard {
            ImuProvider(appContext)
                .orientation(rate.toRateUs(), onShed = { statusStore.shed(PublishedSubject.ROLL) })
                .collect { q ->
                val at = protoTimestamp(SensorClock.epochNanosNow(q.elapsedNanos))
                rollSink.emit(rollPub, timestampedFloat(at, q.rollDegrees).toByteArray(), q.rollDegrees)
                pitchSink.emit(pitchPub, timestampedFloat(at, q.pitchDegrees).toByteArray(), q.pitchDegrees)
                yawSink.emit(yawPub, timestampedFloat(at, q.yawDegrees).toByteArray(), q.yawDegrees)
            }
        }
    }

    /**
     * The gyro's three axes, named and in degrees per second.
     *
     * **Body rates about the device axes**, which is what the gyro reports and what every marine system
     * means by "roll rate" — not the time derivatives of the angles above. The two coincide only near
     * level. Android's mapping is the one used throughout: pitch turns about +X, roll about +Y, yaw
     * about +Z.
     */
    private suspend fun runAttitudeRates(
        session: KeelsonSession,
        publishers: Map<PublishedSubject, AdvancedPublisher>,
        rate: SensorRate,
    ) {
        val rollPub = publishers.of(PublishedSubject.ROLL_RATE)
        val pitchPub = publishers.of(PublishedSubject.PITCH_RATE)
        val yawPub = publishers.of(PublishedSubject.YAW_RATE)
        val rollSink = SubjectSink(PublishedSubject.ROLL_RATE, session)
        val pitchSink = SubjectSink(PublishedSubject.PITCH_RATE, session)
        val yawSink = SubjectSink(PublishedSubject.YAW_RATE, session)
        rollSink.guard {
            ImuProvider(appContext)
                .angularVelocity(rate.toRateUs(), onShed = { statusStore.shed(PublishedSubject.ROLL_RATE) })
                .collect { s ->
                val at = protoTimestamp(SensorClock.epochNanosNow(s.elapsedNanos))
                val rates = attitudeRatesOf(s.x, s.y, s.z)
                rollSink.emit(rollPub, timestampedFloat(at, rates.roll).toByteArray(), rates.roll)
                pitchSink.emit(pitchPub, timestampedFloat(at, rates.pitch).toByteArray(), rates.pitch)
                yawSink.emit(yawPub, timestampedFloat(at, rates.yaw).toByteArray(), rates.yaw)
            }
        }
    }

    /**
     * The IMU's own die temperature, where the device has such a sensor.
     *
     * A vendor sensor found by string type — see [ScalarSensorProvider.imuTemperature] for why there
     * is no constant to use, and why nothing falls back to the ambient sensor.
     *
     * The first reading is sanity-checked against the chip's own operating range rather than trusted.
     * A vendor sensor's scaling is not guaranteed by any contract, and a raw LSB count would arrive as
     * a number that looks like a temperature to every consumer and to this screen — the same shape of
     * failure `warnIfClockBaseLooksWrong` exists for.
     */
    private suspend fun runImuTemperature(
        session: KeelsonSession,
        publisher: AdvancedPublisher,
        rate: SensorRate,
    ) {
        val sink = SubjectSink(PublishedSubject.IMU_TEMPERATURE, session)
        sink.guard {
            var checked = false
            ScalarSensorProvider(appContext)
                .imuTemperature(rate.toIntervalMillis(), onShed = { statusStore.shed(PublishedSubject.IMU_TEMPERATURE) })
                .collect { s ->
                if (!checked) {
                    checked = true
                    if (s.value < MIN_PLAUSIBLE_DIE_CELSIUS || s.value > MAX_PLAUSIBLE_DIE_CELSIUS) {
                        Log.w(
                            TAG,
                            "imu_temperature_celsius: first reading is ${s.value}, outside the chip's " +
                                "own operating range — the vendor sensor may not report degrees",
                        )
                    }
                }
                val at = protoTimestamp(SensorClock.epochNanosNow(s.elapsedNanos))
                sink.emit(publisher, timestampedFloat(at, s.value).toByteArray(), s.value)
            }
        }
    }

    private suspend fun runMagnetometer(
        session: KeelsonSession,
        publisher: AdvancedPublisher,
        frameId: String,
        rate: SensorRate,
    ) {
        val sink = SubjectSink(PublishedSubject.MAGNETIC_FIELD, session)
        sink.guard {
            var checked = false
            ImuProvider(appContext)
                .magneticField(rate.toRateUs(), onShed = { statusStore.shed(PublishedSubject.MAGNETIC_FIELD) })
                .collect { s ->
                val observedAtNanos = SensorClock.epochNanosNow(s.elapsedNanos)
                if (!checked) {
                    checked = true
                    warnIfClockBaseLooksWrong(Subjects.MAGNETIC_FIELD_GAUSS, observedAtNanos)
                }
                // Android reports microtesla; the subject is gauss.
                val msg = Decomposed3DVector.newBuilder()
                    .setTimestamp(protoTimestamp(observedAtNanos))
                    .setFrameId(frameId)
                    .setVector(
                        vec3(
                            microteslaToGauss(s.x),
                            microteslaToGauss(s.y),
                            microteslaToGauss(s.z),
                        )
                    )
                    .build()
                sink.emit(
                    publisher,
                    msg.toByteArray(),
                    magnitude(
                        microteslaToGauss(s.x),
                        microteslaToGauss(s.y),
                        microteslaToGauss(s.z),
                    ),
                )
            }
        }
    }

    /**
     * Ambient light, held between the sensor's own reports.
     *
     * The only collector that publishes a reading it did not just receive — see `sensors/SampleHold.kt`
     * for why. The timestamp is still the *observation* time from the original event, so a repeated
     * sample says so rather than claiming to be fresh.
     */
    private suspend fun runIlluminance(
        session: KeelsonSession,
        publisher: AdvancedPublisher,
        rate: SensorRate,
    ) {
        val sink = SubjectSink(PublishedSubject.ILLUMINANCE, session)
        sink.guard {
            var checked = false
            // Converted once per *real* reading, not once per publish. `epochNanosNow` re-reads the
            // boot-to-epoch offset each call, so converting a held sample repeatedly moved its
            // observation time by a millisecond between otherwise identical messages — which would
            // defeat the whole point of repeating the timestamp, and any consumer deduplicating on it.
            var lastElapsedNanos = 0L
            var lastObservedAtNanos = 0L
            ScalarSensorProvider(appContext)
                .illuminance(rate.toIntervalMillis(), onShed = { statusStore.shed(PublishedSubject.ILLUMINANCE) })
                .collect { s ->
                if (s.elapsedNanos != lastElapsedNanos) {
                    lastElapsedNanos = s.elapsedNanos
                    lastObservedAtNanos = SensorClock.epochNanosNow(s.elapsedNanos)
                }
                val observedAtNanos = lastObservedAtNanos
                if (!checked) {
                    checked = true
                    warnIfClockBaseLooksWrong(Subjects.ILLUMINANCE_LUX, observedAtNanos)
                }
                // No conversion: Android reports TYPE_LIGHT in lux and the subject is lux. Unusual
                // here — nearly every other sensor needs one — so the absence is deliberate, not missed.
                val msg = timestampedFloat(protoTimestamp(observedAtNanos), s.value)
                sink.emit(publisher, msg.toByteArray(), s.value)
            }
        }
    }

    /**
     * The microphone, one WAV chunk per publish.
     *
     * Only runs when audio is enabled *and* the permission is granted — the same shape as
     * [runLocation], and the reason a denied microphone leaves the rest of a run untouched.
     *
     * The chunk is stamped with the instant its **first sample** was taken, derived from the sample
     * clock rather than from when the read returned, so consecutive chunks are exactly one chunk apart
     * however the thread was scheduled.
     */
    private suspend fun runAudio(
        session: KeelsonSession,
        publisher: AdvancedPublisher,
        settings: Settings,
    ) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted; skipping audio publisher")
            return
        }
        val chunkMillis = settings.rate(Subjects.AUDIO).toIntervalMillis()
            // A "maximum rate" audio stream is meaningless — it would be one chunk per sample. The
            // floor keeps a mis-set rate from turning into a message storm.
            .coerceIn(MIN_AUDIO_CHUNK_MILLIS, MAX_AUDIO_CHUNK_MILLIS)
            .toInt()

        val sink = SubjectSink(PublishedSubject.AUDIO, session)
        sink.guard {
            var checked = false
            AudioProvider(appContext)
                .chunks(settings.audioSampleRateHz, settings.audioChannels, chunkMillis)
                .collect { chunk ->
                    val observedAtNanos = SensorClock.epochNanosNow(chunk.startElapsedNanos)
                    if (!checked) {
                        checked = true
                        warnIfClockBaseLooksWrong(Subjects.AUDIO, observedAtNanos)
                    }
                    // WAV rather than raw PCM: `keelson.Audio` carries no rate, channel or depth field,
                    // so the RIFF header is what makes each chunk playable on its own.
                    val wav = WavChunk.wrap(chunk.pcm, chunk.sampleRateHz, chunk.channels)
                    val msg = Audio.newBuilder()
                        .setTimestamp(protoTimestamp(observedAtNanos))
                        .setEncoding(Audio.Encoding.WAV)
                        .setData(ByteString.copyFrom(wav))
                        .build()
                    // The live view gets the chunk's level, never the audio: a waveform is not a
                    // sparkline, and a level meter is what tells you the microphone is alive.
                    val level = WavChunk.levelDbfs(chunk.pcm)
                    sink.emit(publisher, msg.toByteArray(), level)
                }
        }
    }

    /**
     * The camera, one JPEG per publish — a time-lapse for the length of the run.
     *
     * Same gate as [runAudio]: enabled in settings *and* permitted, so a denied camera leaves the rest
     * of the run untouched. The heaviest subject here by a wide margin — about 270 MB/h at the default
     * two-second interval — which is why it is off unless someone asked for it.
     */
    /**
     * The camera collector: a time-lapse, continuous H.264, or both.
     *
     * One binding serves both subjects, so this is a single collector with two sinks rather than two
     * collectors fighting over one camera — see `COLLECTOR_GROUPS`. Either subject alone keeps the
     * camera open; both off releases it, which `supervise()` handles.
     */
    private suspend fun runCamera(
        session: KeelsonSession,
        publisher: AdvancedPublisher,
        settings: Settings,
    ) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "CAMERA not granted; skipping camera publisher")
            return
        }
        val camera = CameraProvider(appContext)
        if (!camera.available()) {
            Log.w(TAG, "no camera on this device; skipping camera publisher")
            return
        }
        val off = offSubjects.value
        val wantStills = PublishedSubject.IMAGE_COMPRESSED !in off
        val wantVideo = PublishedSubject.VIDEO_COMPRESSED !in off
        if (!wantStills && !wantVideo) return

        val frameId = if (settings.cameraLensFront) FRAME_ID_FRONT else FRAME_ID_REAR
        val stills = if (!wantStills) null else StillsConfig(
            // `SensorRate.Max` is meaningless for a camera — it would ask for frames as fast as the
            // shutter will go, which is video at a hundred times the data rate. That is what
            // `video_compressed` is for.
            intervalMillis = settings.rate(Subjects.IMAGE_COMPRESSED).toIntervalMillis()
                .coerceIn(MIN_FRAME_INTERVAL_MILLIS, MAX_FRAME_INTERVAL_MILLIS),
            size = Size(settings.cameraWidth, settings.cameraHeight),
            quality = Settings.CAMERA_JPEG_QUALITY,
        )
        val video = if (!wantVideo) null else VideoConfig(
            size = Size(settings.videoWidth, settings.videoHeight),
            bitrateKbps = settings.videoBitrateKbps,
            frameRate = settings.rate(Subjects.VIDEO_COMPRESSED).toIntervalMillis()
                .let { if (it > 0) (1000L / it).toInt() else DEFAULT_VIDEO_FRAME_RATE }
                .coerceIn(1, MAX_VIDEO_FRAME_RATE),
            keyframeSeconds = settings.videoKeyframeSeconds,
        )

        val stillSink = SubjectSink(PublishedSubject.IMAGE_COMPRESSED, session)
        val videoSink = SubjectSink(PublishedSubject.VIDEO_COMPRESSED, session)
        val videoPublisher = publishers[PublishedSubject.VIDEO_COMPRESSED]
        stillSink.guard {
            var checkedStill = false
            var checkedVideo = false
            camera.stream(stills = stills, video = video, front = settings.cameraLensFront)
                .collect { out ->
                    when (out) {
                        is CameraOutput.Still -> {
                            val frame = out.frame
                            // The camera's exposure timestamp, not the instant the JPEG arrived here —
                            // the same observation-time rule every other subject follows.
                            val observedAtNanos = SensorClock.epochNanosNow(frame.elapsedNanos)
                            if (!checkedStill) {
                                checkedStill = true
                                warnIfClockBaseLooksWrong(Subjects.IMAGE_COMPRESSED, observedAtNanos)
                            }
                            val msg = CompressedImage.newBuilder()
                                .setTimestamp(protoTimestamp(observedAtNanos))
                                .setFrameId(frameId)
                                // foxglove's field takes a media type a browser knows, not an enum.
                                .setFormat("jpeg")
                                .setData(ByteString.copyFrom(frame.jpeg))
                                .build()
                            // What the sparkline plots is the frame size: a picture is not a scalar, and
                            // kB per frame is the number that says the exposure changed or the stream is
                            // degrading.
                            val result = stillSink.emit(publisher, msg.toByteArray(), frame.jpeg.size / 1024f)
                            // Only what actually went out, as for every other live value — and
                            // downscaled here rather than in the UI, so Compose never holds a 150 kB
                            // frame.
                            if (result?.isSuccess == true) {
                                thumbnail(frame.jpeg)?.let {
                                    liveStore.recordFrame(
                                        FramePreview(it.jpeg, it.width, it.height, System.currentTimeMillis())
                                    )
                                }
                            }
                        }

                        is CameraOutput.Video -> {
                            if (videoPublisher == null) return@collect
                            val frame = out.frame
                            // The encoder carries the camera's own presentation time through, so this is
                            // the exposure clock rather than the moment the access unit was drained —
                            // and `warnIfClockBaseLooksWrong` says so out loud if the device disagrees.
                            val observedAtNanos = SensorClock.epochNanosNow(frame.presentationTimeUs * 1_000)
                            if (!checkedVideo) {
                                checkedVideo = true
                                warnIfClockBaseLooksWrong(Subjects.VIDEO_COMPRESSED, observedAtNanos)
                            }
                            val msg = CompressedVideo.newBuilder()
                                .setTimestamp(protoTimestamp(observedAtNanos))
                                .setFrameId(frameId)
                                .setFormat("h264")
                                .setData(ByteString.copyFrom(frame.bytes))
                                .build()
                            videoSink.emit(videoPublisher, msg.toByteArray(), frame.bytes.size / 1024f)
                        }
                    }
                }
        }
    }

    private suspend fun runPressure(
        session: KeelsonSession,
        publisher: AdvancedPublisher,
        rate: SensorRate,
    ) {
        val sink = SubjectSink(PublishedSubject.AIR_PRESSURE, session)
        sink.guard {
            var checked = false
            ScalarSensorProvider(appContext)
                .pressure(rate.toRateUs(), onShed = { statusStore.shed(PublishedSubject.AIR_PRESSURE) })
                .collect { s ->
                val observedAtNanos = SensorClock.epochNanosNow(s.elapsedNanos)
                if (!checked) {
                    checked = true
                    warnIfClockBaseLooksWrong(Subjects.AIR_PRESSURE_PA, observedAtNanos)
                }
                // Android reports hectopascals; the subject is pascals.
                val pascals = hectopascalToPascal(s.value)
                val msg = timestampedFloat(protoTimestamp(observedAtNanos), pascals)
                sink.emit(publisher, msg.toByteArray(), pascals)
            }
        }
    }

    /**
     * Five subjects from one poll.
     *
     * Each has its own sink, so a field the device does not report shows as a subject with no samples
     * rather than dragging the others down — and each nullable field is skipped rather than published
     * as a confident zero.
     */
    private suspend fun runBattery(
        session: KeelsonSession,
        publishers: Map<PublishedSubject, AdvancedPublisher>,
        rate: SensorRate,
    ) {
        val sinks = BATTERY_SUBJECTS.associateWith { SubjectSink(it, session) }
        val runtime = RuntimeEstimator()
        // Whichever gauge the first reading offered, kept for the rest of the run: microamp-hours and
        // percent are both "fuel" to the estimator and it cannot tell them apart, so switching between
        // them mid-run would look like the battery falling off a cliff.
        var useChargeCounter: Boolean? = null
        // The whole poll loop dying is reported against state-of-charge, the subject that owns the rate.
        sinks.getValue(PublishedSubject.BATTERY_STATE_OF_CHARGE).guard {
            BatteryProvider(appContext).samples(rate.toIntervalMillis()).collect { s ->
                // Battery has no sensor event of its own, so the observation time is the read.
                val now = protoTimestamp()

                if (useChargeCounter == null && (s.chargeMicroAmpHours != null || s.stateOfChargePct != null)) {
                    useChargeCounter = s.chargeMicroAmpHours != null
                }
                val fuel = when (useChargeCounter) {
                    true -> s.chargeMicroAmpHours?.toDouble()
                    false -> s.stateOfChargePct?.toDouble()
                    // Nothing to go on yet: no gauge has reported at all.
                    null -> null
                }
                // A tick where the chosen gauge said nothing is skipped rather than filled in — the
                // estimator reads a trend, and an invented point is a trend it never measured.
                if (fuel != null) {
                    // Charging is decided here rather than inside the estimator, which is shared with
                    // the recorder's free-space tank and has no business knowing about mains power. The
                    // window is cleared with it: a drain measured before the plug went in says nothing
                    // about the run after it comes out.
                    val estimate = if (s.isCharging == true) {
                        runtime.reset()
                        RuntimeEstimate.Charging
                    } else {
                        runtime.record(System.currentTimeMillis(), fuel)
                    }
                    statusStore.batteryRuntime(estimate)
                }
                fun emit(subject: PublishedSubject, value: Float?) {
                    if (value == null) return
                    val msg = timestampedFloat(now, value)
                    sinks.getValue(subject).emit(publishers.of(subject), msg.toByteArray(), value)
                }
                // Not a battery reading, but the same poll: how long the phone has been up, which is
                // what tells a reboot from an app restart when a gap in a recording is read months
                // later. `elapsedRealtime` rather than `uptimeMillis`, so deep sleep counts — the phone
                // was up, it was only asleep.
                val uptimeMillis = SystemClock.elapsedRealtime()
                sinks.getValue(PublishedSubject.DEVICE_UPTIME).emit(
                    publishers.of(PublishedSubject.DEVICE_UPTIME),
                    TimestampedDuration.newBuilder()
                        .setTimestamp(now)
                        .setValue(protoDuration(uptimeMillis))
                        .build()
                        .toByteArray(),
                    uptimeMillis / 3_600_000f,
                )

                emit(PublishedSubject.BATTERY_STATE_OF_CHARGE, s.stateOfChargePct)
                emit(PublishedSubject.BATTERY_VOLTAGE, s.voltageV)
                emit(PublishedSubject.BATTERY_CURRENT, s.currentA)
                emit(PublishedSubject.BATTERY_TEMPERATURE, s.temperatureCelsius)
                s.isCharging?.let { charging ->
                    val msg = TimestampedBool.newBuilder().setTimestamp(now).setValue(charging).build()
                    sinks.getValue(PublishedSubject.BATTERY_IS_CHARGING)
                        .emit(publishers.of(PublishedSubject.BATTERY_IS_CHARGING), msg.toByteArray())
                }
            }
        }
    }

    /**
     * Both radio links from one poll.
     *
     * Absent values are skipped rather than published: unlike a stationary phone's zero speed, a
     * missing RSRP has no sensible default — `0 dBm` would be a physically implausible reading rather
     * than a neutral one, and the platform's own sentinel is `Integer.MAX_VALUE`.
     */
    private suspend fun runRadio(
        session: KeelsonSession,
        publishers: Map<PublishedSubject, AdvancedPublisher>,
        rate: SensorRate,
    ) {
        val sinks = RADIO_SUBJECTS.associateWith { SubjectSink(it, session) }
        sinks.getValue(PublishedSubject.CELLULAR_RSRP).guard {
            RadioProvider(appContext).samples(rate.toIntervalMillis()).collect { s ->
                // The modem's own report time where it gave us one; otherwise the read time. Polling
                // republishes the same measurement across ticks, so this is what makes a held value
                // distinguishable from a fresh one.
                val cellularAt = s.cellular?.measuredAtElapsedNanos
                    ?.let { protoTimestamp(SensorClock.epochNanosNow(it)) }
                    ?: protoTimestamp()
                val now = protoTimestamp()

                fun emit(subject: PublishedSubject, at: Timestamp, value: Float?) {
                    if (value == null) return
                    sinks.getValue(subject).emit(
                        publishers.of(subject),
                        timestampedFloat(at, value).toByteArray(),
                        value,
                    )
                }

                s.cellular?.let { c ->
                    emit(PublishedSubject.CELLULAR_RSRP, cellularAt, c.rsrpDbm)
                    emit(PublishedSubject.CELLULAR_RSRQ, cellularAt, c.rsrqDb)
                    emit(PublishedSubject.CELLULAR_SINR, cellularAt, c.sinrDb)
                    emit(PublishedSubject.CELLULAR_RSSI, cellularAt, c.rssiDbm)
                    c.accessTechnology?.let { tech ->
                        val msg = TimestampedString.newBuilder()
                            .setTimestamp(cellularAt).setValue(tech).build()
                        sinks.getValue(PublishedSubject.CELLULAR_ACCESS_TECHNOLOGY).emit(
                            publishers.of(PublishedSubject.CELLULAR_ACCESS_TECHNOLOGY),
                            msg.toByteArray(),
                        )
                    }
                }
                s.wifi?.let { w ->
                    emit(PublishedSubject.WIFI_RSSI, now, w.rssiDbm)
                    emit(PublishedSubject.WIFI_DOWNLINK_BITRATE, now, w.downlinkBitsPerSecond)
                    emit(PublishedSubject.WIFI_UPLINK_BITRATE, now, w.uplinkBitsPerSecond)
                }

                // Identity is stamped with the cell list's own report time rather than the poll time —
                // `getAllCellInfo()` is a cache, and identity is exactly what a consumer uses to decide
                // whether two measurements straddle a handover.
                s.identity?.let { id ->
                    val at = id.measuredAtElapsedNanos
                        ?.let { protoTimestamp(SensorClock.epochNanosNow(it)) }
                        ?: now
                    fun emitOne(subject: PublishedSubject, message: com.google.protobuf.MessageLite) {
                        sinks.getValue(subject).emit(publishers.of(subject), message.toByteArray())
                    }
                    id.cellId?.let { emitOne(PublishedSubject.CELL_ID, timestampedInt64(at, it)) }
                    id.physicalCellId?.let {
                        emitOne(PublishedSubject.PHYSICAL_CELL_ID, timestampedInt(at, it))
                    }
                    id.earfcn?.let { emitOne(PublishedSubject.EARFCN, timestampedInt(at, it)) }
                    id.downlinkBandwidthKhz?.let {
                        emitOne(
                            PublishedSubject.CELL_BANDWIDTH_DOWNLINK,
                            timestampedFloat(at, kilohertzToMegahertz(it)),
                        )
                    }
                    id.band?.let {
                        emitOne(
                            PublishedSubject.BAND,
                            TimestampedString.newBuilder().setTimestamp(at).setValue(it).build(),
                        )
                    }
                }
            }
        }
    }

    /**
     * The rig calibration: one `frame_transform` per sensor and one `configuration_json`, on a loop.
     *
     * Nothing is sampled here — the geometry was measured on the calibration screen and has not
     * changed. The loop exists because Zenoh's latest-value store keeps only the last sample per
     * *key*, and every sensor's transform shares one key (upstream names the sensor inside the
     * message, in `child_frame_id`, rather than by source id). A subscriber joining halfway through
     * would otherwise see whichever transform happened to go last and none of the others. Ten seconds
     * is what `platform-geometry2keelson.py` republishes at, and matching it keeps a phone-surveyed
     * rig indistinguishable from a connector-published one.
     *
     * **Stamped with the publish time, not the observation time** — the one deliberate exception to
     * the rule everywhere else in this file. A transform's timestamp is what a consumer builds its
     * transform tree against: stamped with the survey date, every transform in a recording would fall
     * outside the file's own time range and Foxglove would draw nothing at all. When each number was
     * measured is in the `calibration` block of the document instead.
     *
     * The document is serialised once, outside the loop: it cannot change while a run is going —
     * saving a calibration restarts the publisher — and re-rendering it every ten seconds would be
     * work for a string that is identical every time.
     */
    /**
     * Republish every publishing rig's geometry, on a loop.
     *
     * One collector for all rigs rather than one per rig: they share a ticker, a rate control and a
     * lifecycle, and the three subjects are the same three whichever rig is behind them. Each rig gets
     * its own keys and publishers ([RigPublishers]) and — because the sink stamps the MCAP channel
     * with the key — its own channels in the recording.
     *
     * The documents are serialised once, outside the loop: a rig's geometry does not change during a
     * run, and re-rendering four JSON documents every ten seconds for the life of a run is work
     * nothing asks for.
     */
    private suspend fun runCalibration(
        session: KeelsonSession,
        rigs: List<RigPublishers>,
        settings: Settings,
    ) {
        if (rigs.isEmpty()) return

        class RigStream(
            val rig: RigCalibration,
            val publishers: Map<PublishedSubject, AdvancedPublisher>,
            val transforms: SubjectSink,
            val document: SubjectSink,
            val zeroFix: SubjectSink,
            val json: String,
        )

        val streams = rigs.map { r ->
            RigStream(
                rig = r.rig,
                publishers = r.publishers,
                // Each sink carries this rig's key so the recorder writes one MCAP channel per
                // (rig, subject) — the channel topic is the full Zenoh key, and the replayer
                // republishes it verbatim.
                transforms = SubjectSink(
                    PublishedSubject.FRAME_TRANSFORM,
                    session,
                    r.keys.getValue(PublishedSubject.FRAME_TRANSFORM),
                ),
                document = SubjectSink(
                    PublishedSubject.CONFIGURATION_JSON,
                    session,
                    r.keys.getValue(PublishedSubject.CONFIGURATION_JSON),
                ),
                zeroFix = SubjectSink(
                    PublishedSubject.CALIBRATION_ZERO,
                    session,
                    r.keys.getValue(PublishedSubject.CALIBRATION_ZERO),
                ),
                // The wire document carries provenance; only the exported file has to satisfy
                // upstream's `additionalProperties: false`. See calibrate/PlatformGeometryJson.kt.
                json = r.rig.toPlatformGeometryJson(provenance = true),
            )
        }

        // A rate control set to Max would otherwise mean an interval of zero, and this loop has no
        // sensor to wait on — it would republish every rig as fast as the CPU allows.
        val intervalMillis = settings.rate(Subjects.FRAME_TRANSFORM).toIntervalMillis()
            .coerceAtLeast(MIN_CALIBRATION_INTERVAL_MILLIS)

        // The plotted value for `configuration_json` is the whole library's sensor count, not each
        // rig's. The live ring is keyed by subject, so a per-rig count would have three rigs writing
        // three different numbers into one series and the plot would oscillate between them for no
        // reason a reader could see.
        val totalSensors = streams.sumOf { it.rig.sensors.size }.toFloat()

        // Guarded on the first rig's transform sink: a failure here is a failure of the collector, and
        // the three calibration rows are per subject rather than per rig, so one report is the whole
        // story however many rigs are behind it.
        streams.first().transforms.guard {
            while (true) {
                val now = protoTimestamp()
                streams.forEach { stream ->
                    val calibration = stream.rig
                    // Only a surveyed position anchors anything. A tape-measured rig and a heading
                    // typed before any capture both leave this null, and the sink would drop it
                    // anyway — see Settings.offSubjects.
                    val zero = calibration.zero?.takeIf { it.hasPosition }
                    // The sample value is the sensor count, which is what the live view and the
                    // subject row show for this subject — there is no scalar reading to plot.
                    stream.document.emit(
                        stream.publishers.getValue(PublishedSubject.CONFIGURATION_JSON),
                        TimestampedString.newBuilder().setTimestamp(now).setValue(stream.json).build()
                            .toByteArray(),
                        totalSensors,
                    )
                    // Where the rig's zero was when it was surveyed — the geodetic anchor the
                    // transforms hang off, and without it they are floating relative geometry.
                    //
                    // **Stamped with the survey time**, unlike the transforms below. A transform's
                    // timestamp is machinery for building a frame tree; this one is the age of a
                    // measurement, and it is the honest answer to "is this where the rig is now?" — no.
                    zero?.let { z ->
                        val fix = LocationFix.newBuilder()
                            .setTimestamp(protoTimestamp(z.capturedAtEpochMillis * 1_000_000L))
                            .setFrameId(calibration.parentFrameId)
                            .setLatitude(z.latitude)
                            .setLongitude(z.longitude)
                            // proto3 has no presence on a double, so an unknown altitude and sea level
                            // are the same bytes. Same compromise the phone's own fix makes.
                            .setAltitude(z.altitudeM ?: 0.0)
                            .apply {
                                // Both or nothing, exactly as in runLocation: a zero in the up slot
                                // would claim the altitude was known perfectly, which is the axis GNSS
                                // is worst at. A typed position has neither and states no covariance.
                                val horizontal = z.accuracyM
                                val vertical = z.verticalAccuracyM
                                if (horizontal != null && vertical != null) {
                                    addAllPositionCovariance(
                                        diagonalEnuCovariance(
                                            horizontalMetres = horizontal,
                                            verticalMetres = vertical,
                                        )
                                    )
                                    positionCovarianceType =
                                        LocationFix.PositionCovarianceType.APPROXIMATED
                                }
                            }
                            .build()
                        stream.zeroFix.emit(
                            stream.publishers.getValue(PublishedSubject.CALIBRATION_ZERO),
                            fix.toByteArray(),
                        )
                    }
                    calibration.sensors.forEach { mount ->
                        // Normalised the same way the document normalises them, so the quaternion on
                        // the wire and the degrees in the JSON describe the same rotation rather than
                        // two that happen to be equivalent.
                        val q = mount.rotation.copy(
                            yaw = normaliseSignedDegrees(mount.rotation.yaw),
                            pitch = normaliseSignedDegrees(mount.rotation.pitch),
                            roll = normaliseSignedDegrees(mount.rotation.roll),
                        ).toQuaternion()
                        val payload = FrameTransform.newBuilder()
                            .setTimestamp(now)
                            .setParentFrameId(calibration.parentFrameId)
                            .setChildFrameId(mount.frameId)
                            .setTranslation(
                                Vector3.newBuilder()
                                    .setX(mount.translation.x)
                                    .setY(mount.translation.y)
                                    .setZ(mount.translation.z)
                            )
                            .setRotation(
                                Quaternion.newBuilder()
                                    .setX(q.x)
                                    .setY(q.y)
                                    .setZ(q.z)
                                    .setW(q.w)
                            )
                            .build()
                        stream.transforms.emit(
                            stream.publishers.getValue(PublishedSubject.FRAME_TRANSFORM),
                            payload.toByteArray(),
                        )
                    }
                }
                delay(intervalMillis)
            }
        }
    }

    /**
     * Per-subject failure handling, and the one place a sample is let through or held back.
     *
     * [guard] keeps a collector that dies from doing so silently — without it the `SupervisorJob`
     * swallows the throwable, the subject stops publishing, and the UI still reads "running".
     * Logging is once-per-subject on purpose: at ~55 Hz, a log line per failed publish is a flood.
     */
    private inner class SubjectSink(
        private val subject: PublishedSubject,
        private val session: KeelsonSession,
        /**
         * The key these samples go out on, when it is not this entry's own.
         *
         * Null for every phone subject, which take their key from [keys]. The calibration subjects
         * are the exception: several rigs publish through the same three registry entries under
         * different entity ids, so the key belongs to the rig rather than to the entry — and the
         * recorder stamps its MCAP channel with it, which is what gives each rig its own channels
         * instead of merging two rigs' transforms onto one topic.
         */
        private val keyOverride: String? = null,
    ) {

        private var logged = false

        /**
         * Publish one payload — or, when the subject is switched off, do nothing at all.
         *
         * **The switch is enforced here and nowhere else.** Wire, MCAP file, replay outbox, live view
         * and sample counter all hang off this one call, so they cannot end up disagreeing about
         * whether a sample happened: an "off" subject writes no channel to the file, buffers nothing
         * for replay, plots nothing and counts nothing.
         *
         * Returns null when the subject is off, so a caller with more to do on a real publish —
         * the fix that feeds the map, the frame that feeds the thumbnail — can tell the two apart.
         */
        fun emit(
            publisher: AdvancedPublisher,
            payload: ByteArray,
            value: Float? = null,
        ): Result<Unit>? {
            if (subject in offSubjects.value) return null
            val result = session.publish(publisher, wrap(payload))
            if (value != null) record(result, value) else record(result)
            return result
        }

        suspend fun guard(block: suspend () -> Unit) {
            try {
                block()
            } catch (c: CancellationException) {
                throw c // stop() cancels the scope; that is not a failure
            } catch (t: Throwable) {
                logOnce("collector stopped", t)
                statusStore.failed(subject, t.message ?: t.javaClass.simpleName)
            }
        }

        /**
         * Serialise for the wire, and hand the same bytes to the recorder on the way past.
         *
         * Recording happens **here**, before the publish is attempted, because a failed publish is
         * exactly when the local file matters. It records the *unwrapped* payload, which is what
         * keelson's MCAP tooling expects — writing envelopes would leave every replayed message
         * doubly wrapped.
         *
         * The instant is taken once and used for both the envelope's `enclosed_at` and the MCAP
         * `publish_time`, so the two cannot drift apart by a scheduling delay.
         */
        fun wrap(payload: ByteArray): ByteArray {
            val now = java.time.Instant.now()
            (keyOverride ?: keys[subject])?.let { key ->
                recorder.offer(
                    RecordSample(
                        key = key,
                        subject = subject.subject,
                        payload = payload,
                        publishTimeNanos = now.epochSecond * 1_000_000_000L + now.nano,
                    )
                )
            }
            // `bufferedForReplay` is false for the camera, and that exclusion is deliberate — see the
            // registry. Replay is paced by message count, so a handful of 150 kB frames is a burst the
            // egress queue would shed silently, taking live navigation data with it.
            if (backfillEnabled && subject.bufferedForReplay) {
                outbox.add(OutboxEntry(subject, payload, now.epochSecond * 1_000_000_000L + now.nano))
            }
            return enclose(payload, now)
        }

        fun record(result: Result<Unit>) {
            result
                .onSuccess { statusStore.tick(subject) }
                .onFailure { t ->
                    logOnce("publish failed", t)
                    statusStore.failed(subject, t.message ?: t.javaClass.simpleName)
                }
        }

        /**
         * As [record], but also retaining the value for the live view.
         *
         * Only what actually went out is retained, so the view cannot show a sample the bus never saw.
         * The retention is a lock and two array writes; it deliberately emits nothing, because the
         * publish path runs at ~217 samples/s and the UI must not be driven from it.
         *
         * **Stored against the time it went out, not the observation time it carries.** For nearly every
         * subject those are the same instant. They are not for the ones that hold a reading between
         * reports — `illuminance_lux` and the radio subjects — and there the observation time is the
         * wrong x-axis for a live plot: every held sample would land on one instant, so the trace would
         * have no width, no rate, and would fall out of the time window entirely while the subject was
         * still publishing once a second. The payload and the recording keep the observation time,
         * which is where it means something.
         */
        fun record(result: Result<Unit>, value: Float) {
            record(result)
            if (result.isSuccess) liveStore.record(subject, System.currentTimeMillis(), value)
        }

        private fun logOnce(what: String, t: Throwable) {
            if (!logged) {
                logged = true
                Log.e(TAG, "$subject $what", t)
            }
        }
    }

    /**
     * Most devices put `SensorEvent.timestamp` on the `elapsedRealtime` clock, but a few use uptime,
     * which silently loses every deep-sleep interval. Checking the first sample of each stream makes
     * that visible in the log instead of showing up as inexplicably old data.
     */
    private fun warnIfClockBaseLooksWrong(subject: String, observedAtNanos: Long) {
        val ageMillis = System.currentTimeMillis() - observedAtNanos / 1_000_000L
        if (ageMillis < -1_000L || ageMillis > 5_000L) {
            Log.w(
                TAG,
                "$subject: first sample is $ageMillis ms old — SensorEvent.timestamp may not be on " +
                    "the elapsedRealtime clock on this device"
            )
        }
    }

    /**
     * The app's three severities onto `foxglove.Log`'s six.
     *
     * DEBUG and FATAL are not offered: nothing a person types on a phone is debug output, and FATAL
     * claims the process is about to die. UNKNOWN is never sent — the panel would show a mark with no
     * level, which is the one thing its always-enforced severity filter cannot reason about.
     */
    private fun AnnotationSeverity.toLogLevel(): FoxgloveLog.Level = when (this) {
        AnnotationSeverity.Info -> FoxgloveLog.Level.INFO
        AnnotationSeverity.Warning -> FoxgloveLog.Level.WARNING
        AnnotationSeverity.Error -> FoxgloveLog.Level.ERROR
    }

    private fun vec3(x: Float, y: Float, z: Float): Vector3 =
        Vector3.newBuilder().setX(x.toDouble()).setY(y.toDouble()).setZ(z.toDouble()).build()

    /**
     * Euclidean magnitude, which is what the live view plots for the vector subjects.
     *
     * Three overlaid axes are unreadable at card size, and magnitude is the quantity that answers "is
     * this sensor sane" at a glance. The full vector still goes on the bus untouched.
     */
    private fun magnitude(x: Float, y: Float, z: Float): Float =
        kotlin.math.sqrt(x * x + y * y + z * z)

    private fun timestampedFloat(at: Timestamp, value: Float): TimestampedFloat =
        TimestampedFloat.newBuilder().setTimestamp(at).setValue(value).build()

    private fun timestampedInt(at: Timestamp, value: Int): TimestampedInt =
        TimestampedInt.newBuilder().setTimestamp(at).setValue(value).build()

    private fun timestampedInt64(at: Timestamp, value: Long): TimestampedInt64 =
        TimestampedInt64.newBuilder().setTimestamp(at).setValue(value).build()

    /**
     * A publisher is declared for every registry entry before any collector starts, so a miss here is
     * a programming error rather than a runtime condition to handle.
     */
    private fun Map<PublishedSubject, AdvancedPublisher>.of(subject: PublishedSubject): AdvancedPublisher =
        getValue(subject)
}

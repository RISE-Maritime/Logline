package se.rise.logline

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.runtime.LaunchedEffect
import java.util.UUID
import android.util.Log
import se.rise.logline.checklist.ChecklistConfig
import se.rise.logline.checklist.ChecklistReminder
import se.rise.logline.checklist.ChecklistReminders
import se.rise.logline.checklist.Operator
import se.rise.logline.calibrate.AveragedFix
import se.rise.logline.calibrate.CalibrationCapture
import se.rise.logline.calibrate.CaptureMethod
import se.rise.logline.calibrate.HeadingSource
import se.rise.logline.calibrate.RigCalibration
import se.rise.logline.calibrate.RigZero
import se.rise.logline.calibrate.bodyOffsetMetres
import se.rise.logline.calibrate.enuOffsetMetres
import se.rise.logline.calibrate.exportCalibration
import se.rise.logline.calibrate.initialBearingDegrees
import se.rise.logline.config.Settings
import se.rise.logline.config.TlsCredential
import se.rise.logline.config.TlsCredentialStore
import se.rise.logline.keelson.DiscoveredRouter
import se.rise.logline.keelson.isLocalEndpoint
import se.rise.logline.keelson.scoutRouters
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.Subjects
import se.rise.logline.keelson.pubsubKey
import se.rise.logline.keelson.qosForSubject
import se.rise.logline.publish.SubjectStatus
import se.rise.logline.sensors.achievedHz
import se.rise.logline.sensors.AudioProvider
import se.rise.logline.sensors.sensorCapabilities
import se.rise.logline.sensors.unavailableSubjects
import se.rise.logline.publish.PublisherService
import se.rise.logline.publish.PublisherStatus
import se.rise.logline.publish.LiveLatest
import se.rise.logline.publish.LiveSnapshot
import se.rise.logline.ui.LiveScreen
import se.rise.logline.ui.WINDOW_CHOICES
import se.rise.logline.ui.TrackMap
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import se.rise.logline.config.NOTE_CATEGORY
import se.rise.logline.publish.Annotation
import se.rise.logline.ui.AnnotationButtonsScreen
import se.rise.logline.ui.AnnotationScreen
import se.rise.logline.ui.ChecklistScreen
import se.rise.logline.ui.ChecklistsScreen
import se.rise.logline.ui.CalibrationScreen
import se.rise.logline.ui.CaptureState
import se.rise.logline.ui.CapturedOffset
import se.rise.logline.ui.CAPTURE_SECONDS
import se.rise.logline.ui.HEADING_SECONDS
import se.rise.logline.ui.MainScreen
import se.rise.logline.ui.NEW_SENSOR
import se.rise.logline.ui.SensorMountScreen
import se.rise.logline.ui.SettingsScreen
import se.rise.logline.ui.SubjectQosScreen
import se.rise.logline.ui.theme.LoglineTheme
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    // Re-read on every resume: the permission dialog and a trip to system settings both come back
    // through here, so this is the one place that needs to know.
    private var locationGranted by mutableStateOf(false)

    /**
     * The procedure a reminder notification asked for, or null.
     *
     * Held here rather than read from `intent` inside Compose: a tapped notification can arrive while
     * the Activity is already up, which comes through [onNewIntent] and never touches the original
     * intent. Cleared once the navigation has happened, so a configuration change does not re-navigate
     * out from under whatever the person did next.
     */
    private var reminderProcedureId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reminderProcedureId = intent?.getStringExtra(ChecklistReminders.EXTRA_PROCEDURE_ID)
        enableEdgeToEdge()
        setContent {
            LoglineTheme {
                // No Scaffold here: every screen brings its own, and nesting them applied the status
                // bar inset twice — a band of dead space above each title.
                App(
                    locationGranted = locationGranted,
                    reminderProcedureId = reminderProcedureId,
                    onReminderHandled = { reminderProcedureId = null },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reminderProcedureId = intent.getStringExtra(ChecklistReminders.EXTRA_PROCEDURE_ID)
    }

    override fun onResume() {
        super.onResume()
        locationGranted = hasLocationPermission(this)
    }
}

@Composable
private fun App(
    locationGranted: Boolean,
    reminderProcedureId: String?,
    onReminderHandled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as LoglineApp
    val settings by app.settingsRepository.settings.collectAsState(initial = null)
    val status by app.publisher.status.collectAsState()
    val recording by app.publisher.recording.collectAsState()
    val scope = rememberCoroutineScope()
    val nav = rememberNavController()

    // Publishing starts either way — a denied location permission means IMU-only, not "no logging",
    // and a denied notification permission only hides the notification.
    val tlsStore = remember { TlsCredentialStore(context.applicationContext) }
    var tlsRevision by remember { mutableIntStateOf(0) }
    val tlsCredentials = remember(tlsRevision) { TlsCredential.entries.map { tlsStore.state(it) } }
    var importing by remember { mutableStateOf<TlsCredential?>(null) }
    val credentialPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = importing
        importing = null
        if (uri != null && target != null) {
            runCatching { tlsStore.import(target, uri) }
                .onFailure { Log.w("MainActivity", "importing ${'$'}{target.label} failed", it) }
            tlsRevision++
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { PublisherService.start(context) }

    // Separate from the one above on purpose: this one only asks. Granting from the IMU-only warning
    // must not also start a run, and `locationGranted` refreshes in onResume when the dialog closes.
    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    /**
     * Asked for the first time a reminder is set, and not before.
     *
     * A checklist is perfectly usable without notifications — the phone is in your hand — so prompting
     * on the way in would be asking for something nothing was going to use. Setting a reminder is the
     * moment it becomes the whole point.
     */
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    // Live-view preferences, hoisted out of the destination so leaving the screen and coming back does
    // not reset them — a `remember` inside the route dies with the composable when it is popped.
    var liveFollowFix by rememberSaveable { mutableStateOf(true) }
    var liveWindowSeconds by rememberSaveable { mutableIntStateOf(WINDOW_CHOICES[1].first) }
    var livePaused by rememberSaveable { mutableStateOf(false) }
    var liveCollapsed by rememberSaveable { mutableStateOf(listOf<String>()) }

    // Which sensors this device simply does not have, so a row that will never publish can say so
    // rather than looking broken. Resolved here because it needs a Context; screens take data.
    val unavailable = remember { unavailableSubjects(context) }
    // Switched off rather than missing — the distinction the subject rows draw. The user's own set,
    // plus audio and the camera, which are off until someone asks for them; `offSubjects()` is the one
    // place those two are folded together, and the publisher's gate reads the same function.
    val disabled = settings?.offSubjects().orEmpty()

    // Reads `settings` when tapped, not when composed: which permissions a run needs depends on the
    // endpoint list, and this lambda is built before the first settings emission arrives.
    val startPublishing: () -> Unit = {
        val missing = settings?.let(::startupPermissions).orEmpty().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) PublisherService.start(context) else permissionLauncher.launch(missing.toTypedArray())
    }

    val current = settings ?: run {
        // One frame or two while DataStore is read. Centred and labelled, so it reads as loading
        // rather than as a screen that failed to draw.
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Text("Loading settings…", modifier = Modifier.padding(top = 12.dp))
        }
        return
    }

    // ── the checklist session ───────────────────────────────────────────────────────────────────
    //
    // Scoped to the checklist screens rather than to the app, and deliberately not to a single route:
    // the list and one procedure are two destinations, and tying the session to either would tear it
    // down and open a new one every time somebody stepped between them. `startsWith("checklist")`
    // covers both.
    val checklistState by app.checklist.state.collectAsState()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val inChecklists = backStackEntry?.destination?.route?.startsWith("checklist") == true
    val checklistConfig = ChecklistConfig(
        endpoints = current.routerEndpoints,
        realm = current.checklistRealm,
        entityId = current.checklistEntityId,
        operator = Operator(
            operatorId = current.operatorId,
            username = current.operatorName,
            role = current.operatorRole,
            rocSite = current.rocSite(),
        ),
    )

    LaunchedEffect(app) { app.checklist.loadLocal() }

    LaunchedEffect(inChecklists, current.checklistEnabled, checklistConfig) {
        // Unconditionally first: `start()` is a no-op while a session is up, so this is what makes an
        // endpoint or identity change actually take effect rather than being ignored until next time.
        app.checklist.stop()
        if (inChecklists && current.checklistEnabled && current.hasChecklistIdentity()) {
            app.checklist.start(checklistConfig)
        }
    }

    // A tapped reminder notification lands here. Consumed once — see `reminderProcedureId`.
    LaunchedEffect(reminderProcedureId) {
        val procedureId = reminderProcedureId
        if (!procedureId.isNullOrEmpty()) {
            nav.navigate("checklist/$procedureId")
            onReminderHandled()
        }
    }

    /** Arm or replace one item's reminder, and persist the list it belongs to. */
    val setReminder: (String, String, Int, Boolean) -> Unit = { procedureId, itemId, minutes, repeat ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val reminder = ChecklistReminder(
            procedureId = procedureId,
            itemId = itemId,
            dueAtEpochMillis = System.currentTimeMillis() + minutes * 60_000L,
            repeatMinutes = if (repeat) minutes else 0,
        )
        val others = checklistState.reminders
            .filterNot { it.procedureId == procedureId && it.itemId == itemId }
        app.checklist.setReminders(others + reminder)
        ChecklistReminders.arm(context, reminder)
    }

    val clearReminder: (String, String) -> Unit = { procedureId, itemId ->
        checklistState.reminders
            .firstOrNull { it.procedureId == procedureId && it.itemId == itemId }
            ?.let { ChecklistReminders.cancel(context, it) }
        app.checklist.setReminders(
            checklistState.reminders.filterNot { it.procedureId == procedureId && it.itemId == itemId }
        )
    }

    // ---- rig calibration --------------------------------------------------------------------
    //
    // The working calibration lives here rather than in the screen: a capture is a coroutine, and its
    // result has to survive the trip into the sensor editor and back. Re-seeded whenever the saved
    // calibration changes, which is what makes Save leave the form clean.
    var calibrationDraft by remember(current.calibration) { mutableStateOf(current.calibration) }
    var capture by remember { mutableStateOf<CaptureState>(CaptureState.Idle) }
    var capturedOffset by remember { mutableStateOf<CapturedOffset?>(null) }
    var captureToken by remember { mutableIntStateOf(0) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    /**
     * Average a position for [CAPTURE_SECONDS] and hand the result on, with a ticking progress state.
     *
     * The permission is checked here rather than inside the capture: a run started IMU-only is a normal
     * state for this app, and someone who has never granted location can still open this screen and
     * type every offset in by hand.
     */
    @SuppressLint("MissingPermission")
    fun captureFix(what: String, onDone: (AveragedFix) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            capture = CaptureState.Failed("Location permission is needed to capture a position.")
            locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        scope.launch {
            capture = CaptureState.Running(what, samples = 0, seconds = CAPTURE_SECONDS, elapsed = 0)
            // A second coroutine drives the bar: the sample callback only fires when a fix arrives, and
            // a progress bar that stops moving indoors reads as a hung app rather than as a poor sky.
            val ticker = launch {
                repeat(CAPTURE_SECONDS) { second ->
                    delay(1_000)
                    (capture as? CaptureState.Running)?.let { capture = it.copy(elapsed = second + 1) }
                }
            }
            val fix = CalibrationCapture(context).averagePosition(CAPTURE_SECONDS) { samples ->
                (capture as? CaptureState.Running)?.let { capture = it.copy(samples = samples) }
            }
            ticker.cancel()
            capture = if (fix == null) {
                CaptureState.Failed("No fix in $CAPTURE_SECONDS s — try again with a clear view of the sky.")
            } else {
                onDone(fix)
                CaptureState.Idle
            }
        }
    }

    NavHost(navController = nav, startDestination = "main", modifier = modifier) {
        composable("main") {
            // Pulled on a ticker like the live view, and for the same reason — but only the newest
            // value per subject, which is 29 floats rather than a quarter of a million.
            val live by produceState(LiveLatest(), app) {
                while (true) {
                    value = app.publisher.liveLatest()
                    delay(1_000)
                }
            }
            MainScreen(
                settings = current,
                status = status,
                recording = recording,
                live = live,
                locationGranted = locationGranted,
                unavailableSubjects = unavailable,
                disabledSubjects = disabled,
                onStart = startPublishing,
                onStop = { PublisherService.stop(context) },
                onGrantLocation = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                onOpenSettings = { nav.navigate("settings") },
                onOpenLive = { nav.navigate("live") },
                onOpenAnnotations = { nav.navigate("annotations") },
                onOpenChecklists = { nav.navigate("checklists") },
                onOpenCalibration = { nav.navigate("calibration") },
                // Routed by registry entry, not subject: `radio_rssi_dbm` is published under two
                // source ids, so the subject name no longer identifies a single card.
                onOpenSubjectQos = { entry -> nav.navigate("qos/${entry.name}") },
                onToggleSubject = { entry, enabled -> toggleSubject(app, scope, current, entry, enabled) },
                onToggleSubjects = { entries, enabled ->
                    scope.launch { app.settingsRepository.update(current.withSubjects(entries, enabled)) }
                },
            )
        }
        composable("live") {
            // Pulled on a ticker rather than pushed: the publish path runs at ~217 samples/s and must
            // not drive recomposition. 5 Hz is smooth to look at and two orders of magnitude cheaper.
            // Pausing stops the pull, so the plots freeze while publishing carries on underneath —
            // which is the point: it is for looking at something that just went past.
            val live by produceState(LiveSnapshot(), app, livePaused) {
                while (!livePaused) {
                    value = app.publisher.liveSnapshot()
                    delay(200)
                }
            }
            val heading = live[PublishedSubject.HEADING_TRUE_NORTH].latest
                ?: live[PublishedSubject.HEADING_MAGNETIC].latest
            LiveScreen(
                snapshot = live,
                status = status,
                recording = recording,
                running = status.running,
                unavailableSubjects = unavailable,
                disabledSubjects = disabled,
                followFix = liveFollowFix,
                onFollowFixChange = { liveFollowFix = it },
                windowSeconds = liveWindowSeconds,
                onWindowSecondsChange = { liveWindowSeconds = it },
                paused = livePaused,
                onPausedChange = { livePaused = it },
                collapsedGroups = liveCollapsed,
                onToggleGroup = { title ->
                    liveCollapsed =
                        if (title in liveCollapsed) liveCollapsed - title else liveCollapsed + title
                },
                mapView = { m ->
                    TrackMap(
                        track = live.track,
                        followFix = liveFollowFix,
                        headingDegrees = heading,
                        modifier = m,
                    )
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable("qos/{entry}") { backStackEntry ->
            val registryEntry = PublishedSubject.forName(
                backStackEntry.arguments?.getString("entry").orEmpty()
            )
            val subject = registryEntry?.subject.orEmpty()
            val subjectStatus = registryEntry?.let { status[it] } ?: SubjectStatus()
            SubjectQosScreen(
                subject = subject,
                sourceId = registryEntry?.fixedSourceId,
                current = qosForSubject(subject, current.qosOverrides),
                isOverridden = current.qosOverrides.containsKey(subject),
                rate = current.rate(subject),
                capabilities = remember(subject) { sensorCapabilities(context, subject) },
                achievedHz = achievedHz(
                    samples = subjectStatus.samplesPublished,
                    firstEpochMillis = subjectStatus.firstPublishEpochMillis,
                    lastEpochMillis = subjectStatus.lastPublishEpochMillis,
                ),
                onSave = { qos, rate ->
                    scope.launch {
                        saveSettings(
                            app,
                            current.copy(
                                qosOverrides = current.qosOverrides + (subject to qos),
                                // An event-driven subject has no rate — its screen shows no rate
                                // control — so storing one would persist a preference nothing reads
                                // and that the UI could never show back.
                                sensorRates = if (registryEntry?.eventDriven == true) {
                                    current.sensorRates
                                } else {
                                    current.sensorRates + (subject to rate)
                                },
                            ),
                        )
                        nav.popBackStack()
                    }
                },
                onResetToPolicy = {
                    scope.launch {
                        // Rate is not policy — only the QoS override goes back to qos.yaml.
                        saveSettings(app, current.copy(qosOverrides = current.qosOverrides - subject))
                        nav.popBackStack()
                    }
                },
                onCancel = { nav.popBackStack() },
            )
        }
        composable("annotations") {
            // Pulled on a ticker, like every other view of publisher state. A mark is rare enough that
            // pushing would cost nothing — but one mechanism for reading the publisher is worth more
            // than a second one that happens to be cheap here.
            val marks by produceState(emptyList<Annotation>() to 0, app) {
                while (true) {
                    value = app.publisher.recentAnnotations() to app.publisher.annotationCount()
                    delay(1_000)
                }
            }
            val nowMillis by produceState(System.currentTimeMillis()) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(1_000)
                }
            }
            AnnotationScreen(
                buttons = current.annotationButtons,
                running = status.running,
                recent = marks.first,
                totalMarks = marks.second,
                nowMillis = nowMillis,
                onMark = { button -> app.publisher.mark(button.label, button.severity, button.category) },
                onNote = { text, severity -> app.publisher.mark(text, severity, NOTE_CATEGORY) },
                onEditButtons = { nav.navigate("annotations/edit") },
                onStart = startPublishing,
                onBack = { nav.popBackStack() },
            )
        }
        composable("annotations/edit") {
            AnnotationButtonsScreen(
                initial = current.annotationButtons,
                // Deliberately *not* saveSettings(): that stops and restarts the publisher so it can
                // redeclare its publishers, which a list of button labels has no need of. Routing this
                // through it would drop the Zenoh session and close the MCAP file to rename a button.
                onSave = { buttons ->
                    scope.launch {
                        app.settingsRepository.update(current.copy(annotationButtons = buttons))
                        nav.popBackStack()
                    }
                },
                onCancel = { nav.popBackStack() },
            )
        }
        composable("checklists") {
            ChecklistsScreen(
                state = checklistState,
                operatorName = current.operatorName,
                operatorRole = current.operatorRole,
                rocSite = current.rocSite(),
                onSaveIdentity = { name, role, site ->
                    scope.launch {
                        // The operator id is generated once and then never moves: it is what stops
                        // this phone's own presence heartbeat, which comes back on the wildcard
                        // subscription, from showing up as another operator.
                        app.settingsRepository.update(
                            current.copy(
                                operatorId = current.operatorId.ifBlank { UUID.randomUUID().toString() },
                                operatorName = name,
                                operatorRole = role,
                                rocSiteId = site,
                                // Entering a name is the act of opting in; making them then find a
                                // switch in Settings would be a second gate on the same decision.
                                checklistEnabled = true,
                            )
                        )
                    }
                },
                onOpenProcedure = { procedureId ->
                    app.checklist.openProcedure(procedureId)
                    nav.navigate("checklist/$procedureId")
                },
                onPublishStarter = { app.checklist.publishStarterProcedures() },
                onBack = { nav.popBackStack() },
            )
        }
        composable("checklist/{procedureId}") { backStackEntry ->
            val procedureId = backStackEntry.arguments?.getString("procedureId").orEmpty()
            val procedure = checklistState.procedure(procedureId)
            // Drives the ages on the rows and the countdown on a reminder, like the main screen's.
            val nowMillis by produceState(System.currentTimeMillis(), procedureId) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(1_000)
                }
            }
            if (procedure == null) {
                // Reachable from a reminder notification for a procedure this phone no longer has a
                // definition for — the library is the router's, not ours, and it can change.
                ChecklistsScreen(
                    state = checklistState,
                    operatorName = current.operatorName,
                    operatorRole = current.operatorRole,
                    rocSite = current.rocSite(),
                    onSaveIdentity = { _, _, _ -> },
                    onOpenProcedure = { nav.navigate("checklist/$it") },
                    onPublishStarter = { app.checklist.publishStarterProcedures() },
                    onBack = { nav.popBackStack() },
                )
            } else {
                ChecklistScreen(
                    procedure = procedure,
                    state = checklistState,
                    nowMillis = nowMillis,
                    onStartItem = { app.checklist.startItem(procedureId, it) },
                    onCompleteItem = { itemId ->
                        app.checklist.completeItem(procedureId, itemId)
                        // A reminder for something already done is the fastest way to teach somebody
                        // to swipe reminders away unread.
                        clearReminder(procedureId, itemId)
                    },
                    onRevertItem = { app.checklist.revertItem(procedureId, it) },
                    onAddNote = { itemId, text -> app.checklist.addNote(procedureId, itemId, text) },
                    onFlagItem = { itemId, reason -> app.checklist.flagItem(procedureId, itemId, reason) },
                    onResolveFlag = { itemId, text -> app.checklist.resolveFlag(procedureId, itemId, text) },
                    onSetReminder = { itemId, minutes, repeat ->
                        setReminder(procedureId, itemId, minutes, repeat)
                    },
                    onClearReminder = { clearReminder(procedureId, it) },
                    onCompleteProcedure = {
                        app.checklist.completeProcedure(procedureId)
                        nav.popBackStack()
                    },
                    onFocusItem = { app.checklist.focusItem(it) },
                    onBack = { nav.popBackStack() },
                )
            }
        }
        composable("calibration") {
            // A rig nobody has named yet: the draft materialises on the first edit, so opening the
            // screen and backing out again leaves nothing behind.
            val draft = calibrationDraft ?: RigCalibration.forName("")
            CalibrationScreen(
                calibration = draft,
                onChange = { calibrationDraft = it },
                publishing = status.running,
                transformKey = pubsubKey(
                    current.realm,
                    draft.entityId.ifBlank { current.entityId },
                    Subjects.FRAME_TRANSFORM,
                    current.calibrationSource,
                ),
                capture = capture,
                onCaptureZero = {
                    captureFix("Averaging the zero point") { fix ->
                        // The heading is kept: it is established separately and a re-capture of the
                        // position is not a reason to forget which way the rig points.
                        val previous = draft.zero
                        calibrationDraft = draft.copy(
                            zero = RigZero(
                                latitude = fix.point.latitude,
                                longitude = fix.point.longitude,
                                altitudeM = fix.point.altitudeM.takeIf { fix.hasAltitude },
                                accuracyM = fix.accuracyM,
                                scatterM = fix.scatterM,
                                headingDeg = previous?.headingDeg ?: 0.0,
                                headingSource = previous?.headingSource ?: HeadingSource.MANUAL,
                                capture = CaptureMethod.GNSS_AVERAGE,
                                samples = fix.samples,
                                capturedAtEpochMillis = System.currentTimeMillis(),
                            )
                        )
                    }
                },
                onCaptureBaseline = {
                    captureFix("Averaging the point ahead") { fix ->
                        draft.zero?.let { zero ->
                            calibrationDraft = draft.copy(
                                zero = zero.copy(
                                    headingDeg = initialBearingDegrees(zero.point(), fix.point),
                                    headingSource = HeadingSource.BASELINE,
                                )
                            )
                        }
                    }
                },
                onCaptureHeading = {
                    scope.launch {
                        capture = CaptureState.Running("Reading the compass", 0, HEADING_SECONDS, 0)
                        val ticker = launch {
                            repeat(HEADING_SECONDS) { second ->
                                delay(1_000)
                                (capture as? CaptureState.Running)?.let {
                                    capture = it.copy(elapsed = second + 1)
                                }
                            }
                        }
                        val reading = CalibrationCapture(context)
                            .heading(near = draft.zero?.takeIf { it.hasPosition }?.point(), seconds = HEADING_SECONDS)
                        ticker.cancel()
                        capture = when {
                            reading == null ->
                                CaptureState.Failed("No compass on this device, or no reading arrived.")
                            // Magnetic north is not the rig's north. Rather than pass one off as the
                            // other, say what is missing: a position is what declination needs.
                            reading.trueDegrees == null -> CaptureState.Failed(
                                "Compass read ${reading.magneticDegrees.roundToInt()}° magnetic. " +
                                    "Capture the zero point first so this can be corrected to true north."
                            )
                            else -> {
                                val zero = draft.zero
                                calibrationDraft = draft.copy(
                                    zero = zero?.copy(
                                        headingDeg = reading.trueDegrees,
                                        headingSource = HeadingSource.COMPASS,
                                    ),
                                )
                                CaptureState.Idle
                            }
                        }
                    }
                },
                onEditSensor = { index ->
                    capturedOffset = null
                    nav.navigate("calibration/sensor/$index")
                },
                onExport = {
                    scope.launch {
                        exportMessage = withContext(Dispatchers.IO) {
                            runCatching { exportCalibration(context, draft) }.fold(
                                onSuccess = { "Wrote $it to Downloads/Logline" },
                                onFailure = { "Could not export: ${it.message ?: it::class.simpleName}" },
                            )
                        }
                    }
                },
                exportMessage = exportMessage,
                onSave = {
                    scope.launch {
                        saveSettings(
                            app,
                            current.copy(
                                calibration = calibrationDraft?.copy(
                                    updatedAtEpochMillis = System.currentTimeMillis(),
                                ),
                            ),
                        )
                        nav.popBackStack()
                    }
                },
                onClear = {
                    scope.launch {
                        saveSettings(app, current.copy(calibration = null))
                        calibrationDraft = null
                        nav.popBackStack()
                    }
                },
                onCancel = {
                    calibrationDraft = current.calibration
                    exportMessage = null
                    nav.popBackStack()
                },
                dirty = calibrationDraft != current.calibration,
            )
        }
        composable("calibration/sensor/{index}") { backStackEntry ->
            val index = backStackEntry.arguments?.getString("index")?.toIntOrNull() ?: NEW_SENSOR
            val draft = calibrationDraft ?: RigCalibration.forName("")
            val existing = draft.sensors.getOrNull(index)
            SensorMountScreen(
                rigName = draft.name,
                initial = existing,
                hasZero = draft.zero?.hasPosition == true,
                capture = capture,
                captured = capturedOffset,
                onCapture = {
                    captureFix("Averaging this sensor's position") { fix ->
                        val zero = draft.zero ?: return@captureFix
                        val body = bodyOffsetMetres(
                            enuOffsetMetres(zero.point(), fix.point),
                            zero.headingDeg,
                        )
                        captureToken += 1
                        capturedOffset = CapturedOffset(
                            translation = body,
                            accuracyM = fix.accuracyM,
                            atEpochMillis = System.currentTimeMillis(),
                            token = captureToken,
                        )
                    }
                },
                onSave = { mount ->
                    val sensors = draft.sensors.toMutableList()
                    if (index in sensors.indices) sensors[index] = mount else sensors.add(mount)
                    calibrationDraft = draft.copy(sensors = sensors)
                    capturedOffset = null
                    nav.popBackStack()
                },
                onDelete = existing?.let {
                    {
                        calibrationDraft = draft.copy(
                            sensors = draft.sensors.filterIndexed { i, _ -> i != index },
                        )
                        capturedOffset = null
                        nav.popBackStack()
                    }
                },
                onCancel = {
                    capturedOffset = null
                    nav.popBackStack()
                },
            )
        }
        composable("settings") {
            // The scan lives here, not in the screen: screens take data and lambdas, and this needs a
            // coroutine scope. Results are held per-visit — a stale list from last time would be worse
            // than an empty one.
            var scanning by remember { mutableStateOf(false) }
            var scanResults by remember { mutableStateOf(emptyList<DiscoveredRouter>()) }
            // An empty result and a scan that never ran look identical on screen otherwise — which is
            // exactly the confusion the first on-device scan caused.
            var scanMessage by remember { mutableStateOf<String?>(null) }

            val runScan: (String) -> Unit = { address ->
                scanning = true
                scanMessage = null
                scope.launch {
                    runCatching { scoutRouters(context, address) }
                        .onSuccess { found ->
                            scanResults = found
                            scanMessage = if (found.isEmpty()) {
                                "No routers answered on $address"
                            } else {
                                null
                            }
                        }
                        .onFailure {
                            Log.w("MainActivity", "router scan failed", it)
                            scanResults = emptyList()
                            scanMessage = "Scan failed: ${it.message ?: it::class.simpleName}"
                        }
                    scanning = false
                }
            }
            // The scan is multicast on the local network, so it needs `ACCESS_LOCAL_NETWORK` — and
            // without it Zenoh only reports EPERM from `sendto`, which reads as an empty network. Asked
            // on the first scan rather than at startup, since a cloud-only setup never scans.
            var pendingScan by remember { mutableStateOf<String?>(null) }
            val localNetworkLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                val address = pendingScan
                pendingScan = null
                when {
                    address == null -> Unit
                    granted -> runScan(address)
                    else -> scanMessage =
                        "Local network access is denied, so no scan is possible. Grant it in " +
                            "Android settings, or type the router's address in by hand."
                }
            }

            SettingsScreen(
                initial = current,
                // Asked of the hardware once, not assumed: only 44.1 kHz is guaranteed everywhere.
                supportedAudioRates = remember {
                    val audio = AudioProvider(context)
                    Settings.AUDIO_SAMPLE_RATES.filter { audio.supports(it, channels = 1) }.toSet()
                },
                scanning = scanning,
                scanResults = scanResults,
                scanMessage = scanMessage,
                onScan = { address ->
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_LOCAL_NETWORK
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        runScan(address)
                    } else {
                        pendingScan = address
                        localNetworkLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                    }
                },
                tlsCredentials = tlsCredentials,
                onImportCredential = { credential ->
                    importing = credential
                    // PEM has no reliable MIME type across providers; */* keeps every file pickable.
                    credentialPicker.launch(arrayOf("*/*"))
                },
                onClearCredential = { credential ->
                    tlsStore.clear(credential)
                    tlsRevision++
                },
                onSave = { updated ->
                    scope.launch {
                        // Generated here rather than in the screen, and only once: the operator id is
                        // what tells this phone's own presence heartbeat — which comes back on the
                        // wildcard subscription — apart from another operator's.
                        val withIdentity = if (updated.checklistEnabled && updated.operatorId.isBlank()) {
                            updated.copy(operatorId = UUID.randomUUID().toString())
                        } else {
                            updated
                        }
                        saveSettings(app, withIdentity)
                        nav.popBackStack()
                    }
                },
                onCancel = { nav.popBackStack() },
            )
        }
    }
}

/**
 * Switches one subject's publishing and recording on or off.
 *
 * **Deliberately not through [saveSettings] for most subjects.** That helper stops and restarts the
 * service so publishers are redeclared, which is right for a QoS or endpoint change and quite wrong
 * for a switch: it would drop the Zenoh session, and every other subject's stream with it, to change
 * whether one of them is let through. `PublisherService` watches the setting instead and applies it to
 * the live run.
 *
 * Audio and the camera are the exception — their foreground-service type and permission are fixed when
 * the service starts, so those two do take the restart. See [se.rise.logline.publish.START_TIME_SUBJECTS].
 */
private fun toggleSubject(
    app: LoglineApp,
    scope: CoroutineScope,
    current: Settings,
    entry: PublishedSubject,
    enabled: Boolean,
) {
    scope.launch {
        when (entry) {
            PublishedSubject.AUDIO -> saveSettings(app, current.copy(audioEnabled = enabled))
            PublishedSubject.IMAGE_COMPRESSED -> saveSettings(app, current.copy(cameraEnabled = enabled))
            else -> app.settingsRepository.update(current.withSubjects(listOf(entry), enabled))
        }
    }
}

/**
 * The same settings with [entries] switched on or off, in one value.
 *
 * A section's master switch moves up to twelve subjects at once, and each `SettingsRepository.update`
 * is a read-modify-write of the whole preferences file against a stale `current` — so twelve of them
 * would have eleven overwrite each other and only the last would stick.
 */
private fun Settings.withSubjects(entries: List<PublishedSubject>, enabled: Boolean): Settings =
    copy(disabledSubjects = if (enabled) disabledSubjects - entries.toSet() else disabledSubjects + entries)

/**
 * Persists settings, restarting a running publisher so it picks them up.
 *
 * The service re-reads DataStore on start, so nothing needs to travel in the intent. Used by both the
 * general settings form and the per-subject QoS screens — a QoS change only reaches the bus when the
 * publishers are redeclared.
 */
private suspend fun saveSettings(app: LoglineApp, updated: Settings) {
    val wasRunning = app.publisher.status.value.running
    if (wasRunning) PublisherService.stop(app)
    app.settingsRepository.update(updated)
    if (wasRunning) PublisherService.start(app)
}

/**
 * What a run needs, given where it is about to publish.
 *
 * `ACCESS_LOCAL_NETWORK` is conditional on purpose: the default endpoint is the cloud router, and
 * prompting every user for "access local network devices" on their first Start — for a permission that
 * config never uses — trains them to dismiss it. A LAN endpoint in the list is the signal that it is
 * genuinely needed.
 */
private fun startupPermissions(settings: Settings): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (settings.routerEndpoints.any(::isLocalEndpoint)) {
        add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }
    // Only when audio is switched on. Asking every user for the microphone when nothing records it
    // would be the surest way to teach them to refuse it.
    if (settings.audioEnabled) add(Manifest.permission.RECORD_AUDIO)
    // Same shape, same reason: nothing opens the camera unless the subject is switched on.
    if (settings.cameraEnabled) add(Manifest.permission.CAMERA)
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

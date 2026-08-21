package se.rise.logline

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
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
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import se.rise.logline.ui.components.LoglineNavBar
import androidx.compose.runtime.CompositionLocalProvider
import se.rise.logline.ui.components.LocalRunState
import se.rise.logline.ui.components.RunState
import se.rise.logline.ui.components.TopLevel
import se.rise.logline.ui.SetupScreen
import se.rise.logline.ui.RecordingLoad
import se.rise.logline.ui.Routes
import se.rise.logline.ui.rateCeilings
import se.rise.logline.ui.labelOf
import se.rise.logline.ui.rigSummaryOf
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
import se.rise.logline.calibrate.exportPlatformRegistry
import se.rise.logline.calibrate.ImportDisposition
import se.rise.logline.calibrate.importCandidates
import se.rise.logline.calibrate.importPlatforms
import se.rise.logline.calibrate.isValidEntityId
import se.rise.logline.calibrate.initialBearingDegrees
import se.rise.logline.config.Settings
import se.rise.logline.config.SettingsProfile
import se.rise.logline.config.applyProfile
import se.rise.logline.config.exportSettingsProfile
import se.rise.logline.config.parseSettingsProfile
import se.rise.logline.config.toConnectionProfile
import se.rise.logline.config.encode
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
import se.rise.logline.map.deleteOfflineMap
import se.rise.logline.map.displayNameOf
import se.rise.logline.map.importOfflineMap
import se.rise.logline.map.importedMaps
import se.rise.logline.publish.PublisherService
import se.rise.logline.record.SavedRecording
import se.rise.logline.record.deleteSavedRecording
import android.content.ContentUris
import java.io.File
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import se.rise.logline.record.TrackCache
import se.rise.logline.record.deleteSavedRecordings
import se.rise.logline.record.recordingsFreeBytes
import se.rise.logline.record.savedRecordings
import se.rise.logline.record.shareIntent
import se.rise.logline.publish.requestBatteryExemption
import se.rise.logline.publish.isBatteryOptimised
import se.rise.logline.publish.PublisherStatus
import se.rise.logline.publish.LiveLatest
import se.rise.logline.publish.LiveSnapshot
import se.rise.logline.publish.TextHistory
import se.rise.logline.publish.SampleWindow
import se.rise.logline.ui.LiveScreen
import se.rise.logline.ui.WINDOW_CHOICES
import se.rise.logline.ui.RecordingChart
import se.rise.logline.ui.RecordingFilter
import se.rise.logline.ui.RecordingSort
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
import se.rise.logline.ui.ConnectionQrDialog
import se.rise.logline.ui.ImportProfileDialog
import se.rise.logline.ui.MainScreen
import se.rise.logline.ui.QrScannerScreen
import se.rise.logline.ui.ChartMarks
import se.rise.logline.record.McapDetails
import se.rise.logline.record.McapTrack
import se.rise.logline.record.recordingDetails
import se.rise.logline.record.recordingEntry
import se.rise.logline.record.recordingTrack
import se.rise.logline.ui.RecordingDetailScreen
import se.rise.logline.ui.TrackState
import se.rise.logline.ui.MapLayer
import se.rise.logline.ui.RecordingsScreen
import se.rise.logline.ui.NEW_RIG
import se.rise.logline.ui.NEW_SENSOR
import se.rise.logline.platform.PlatformSyncConfig
import se.rise.logline.platform.mergeRemoteRigs
import se.rise.logline.ui.RigListScreen
import se.rise.logline.ui.SensorMountScreen
import se.rise.logline.ui.SettingsScreen
import se.rise.logline.ui.SubjectDetailScreen
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
     * Same shape, same reason: the exemption is granted in a system dialog or in Android settings, and
     * neither tells the app anything. `PowerManager` is the only answer that can be trusted, and the
     * moment to re-read it is when the user comes back.
     */
    private var batteryOptimised by mutableStateOf(false)

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
                    batteryOptimised = batteryOptimised,
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
        batteryOptimised = isBatteryOptimised(this)
    }
}

@Composable
private fun App(
    locationGranted: Boolean,
    batteryOptimised: Boolean,
    reminderProcedureId: String?,
    onReminderHandled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as LoglineApp
    val settings by app.settingsRepository.settings.collectAsState(initial = null)
    val status by app.publisher.status.collectAsState()
    val recording by app.publisher.recording.collectAsState()
    val recordingTags by app.recordingTags.tags.collectAsState(initial = emptyMap())
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

    // Imported tile archives, re-read after an import or a delete — the same revision trick the TLS
    // credentials use, and for the same reason: the source of truth is the filesystem.
    var offlineMapRevision by remember { mutableIntStateOf(0) }
    var offlineMapMessage by remember { mutableStateOf<String?>(null) }
    val offlineMaps = remember(offlineMapRevision) { importedMaps(context) }
    val offlineMapPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // The copy is hundreds of megabytes; the display name comes from the same resolver.
            val result = withContext(Dispatchers.IO) {
                importOfflineMap(context, uri, displayNameOf(context, uri))
            }
            offlineMapMessage = result.exceptionOrNull()?.message
            offlineMapRevision++
        }
    }

    // Settings profiles: export to a file, import one back, and the QR that carries the connection
    // half. `pendingProfile` holds a parsed one between the picker returning and the review dialog
    // being answered — an import is a review, not a switch.
    var profileMessage by remember { mutableStateOf<String?>(null) }
    var pendingProfile by remember { mutableStateOf<SettingsProfile?>(null) }
    var showConnectionQr by remember { mutableStateOf(false) }
    val profilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            val parsed = text?.let { parseSettingsProfile(it) }
            if (parsed == null) {
                profileMessage = "That file is not a Logline settings profile."
            } else {
                pendingProfile = parsed
            }
        }
    }

    // Nothing to do with the result: the system dialog reports back through onResume, which re-reads
    // PowerManager. A launcher rather than startActivity so the Activity result plumbing is the same
    // as everything else here.
    val batteryExemptionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { }

    /**
     * Asked at the first Start of a run, and never again.
     *
     * Not on first launch: at that point nothing is running, and a prompt about background execution
     * has no context to be understood in. A run beginning is exactly the moment it means something —
     * and the moment the answer starts to matter.
     *
     * Recorded as asked whether or not it is granted, and through `update` rather than `saveSettings`:
     * the latter stops and restarts the service to redeclare publishers, which would tear down the run
     * that has just been started to record the fact that a question was asked.
     */
    val askBatteryExemptionOnce: () -> Unit = ask@{
        val settings = settings ?: return@ask
        if (settings.batteryExemptionAsked || !isBatteryOptimised(context)) return@ask
        scope.launch { app.settingsRepository.update(settings.copy(batteryExemptionAsked = true)) }
        requestBatteryExemption(context) { batteryExemptionLauncher.launch(it) }
    }

    /** Start a run, then ask the one question that decides whether it survives being left alone. */
    val startRun: () -> Unit = {
        PublisherService.start(context)
        askBatteryExemptionOnce()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { startRun() }

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
    // Which chart the live view draws, and whether seamarks go over it. Hoisted like the rest of the
    // live-view preferences: switching layers and coming back to the other one would be an odd thing to
    // have to redo every time.
    //
    // **Satellite by default.** This app is used on the water, where the standard map's value is street
    // names and building outlines and there are none — imagery shows the shoreline, the shoals and the
    // jetty actually being approached, which is what a track is read against. It costs no more than the
    // other layer: both are online tiles at the same zoom, fetched only for what is on screen. Note the
    // consequence for a first run with no signal — a fresh install now opens on an empty grid rather
    // than a cached street map, and the layer menu is the way out of that.
    var liveLayer by rememberSaveable { mutableStateOf(MapLayer.Satellite) }
    // How the Files tab is being looked through, hoisted for the reason the live view's preferences
    // are: the tab is popped whenever a recording is opened, so a `remember` inside it would clear a
    // search on the way back from the thing the search found. Enums carry through `rememberSaveable`
    // on their own — they are `Serializable` — which is what `liveLayer` above relies on too.
    var recordingsQuery by rememberSaveable { mutableStateOf("") }
    var recordingsSort by rememberSaveable { mutableStateOf(RecordingSort.Newest) }
    var recordingsFilter by rememberSaveable { mutableStateOf(RecordingFilter.All) }
    // Four booleans rather than one `ChartMarks`, purely so `rememberSaveable` can carry them: it has
    // no saver for an arbitrary data class, and losing which marks are on to a process death is the
    // sort of small wrongness that reads as the app forgetting things. Assembled at the call site.
    var liveSeaMarks by rememberSaveable { mutableStateOf(false) }
    var liveShowTrack by rememberSaveable { mutableStateOf(true) }
    var liveShowHeading by rememberSaveable { mutableStateOf(true) }
    var liveShowCourse by rememberSaveable { mutableStateOf(true) }
    val liveMarks = ChartMarks(
        seaMarks = liveSeaMarks,
        track = liveShowTrack,
        headingLine = liveShowHeading,
        courseVector = liveShowCourse,
    )
    // Whether the live view plots the curated set or all of it. Defaults to the curated one: thirty-nine
    // plots is a page nobody scrolls during a run, and `PublishedSubject.featured` names the few that
    // answer "is this going well". Hoisted with the rest so it survives leaving the tab.
    var liveBasicOnly by rememberSaveable { mutableStateOf(true) }
    // Whether the Events tab lists every mark or shows one summary line. Hoisted with the rest of the
    // per-tab preferences, and collapsed by default: that screen's job is *marking*, and an expanded
    // list above the buttons would put them out of a thumb's reach on a moving boat.
    var eventsExpanded by rememberSaveable { mutableStateOf(false) }
    // Full screen for the quick buttons, and whether the note field comes with them. Hoisted with the
    // other per-tab preferences; the note flag governs full screen only, because a control has to be
    // reachable where its effect is visible and there is nowhere in the normal view to flip it back.
    // How many quick buttons sit across the Events tab. Three is the middle of the three choices and
    // what the screen has always drawn on a Pixel 6.
    var eventsColumns by rememberSaveable { mutableIntStateOf(3) }
    // Whether the typed-note section shows. On by default — it is the only way to mark something no
    // button covers — but hideable, because a phone whose operator never types gets the space back.
    var eventsNoteShown by rememberSaveable { mutableStateOf(true) }

    // Which sensors this device simply does not have, so a row that will never publish can say so
    // rather than looking broken. Resolved here because it needs a Context; screens take data.
    val unavailable = remember { unavailableSubjects(context) }
    // The fastest each source can go, asked once. `SensorManager` reports a `minDelay` per sensor; the
    // rest is the app's own poll and chunk floors, plus one honest estimate for GNSS — see
    // `rateCeilings()`, which keeps the provenance so a soft number can be marked as one. Remembered
    // because none of it changes while the process lives.
    val ceilings = remember { rateCeilings(context) }
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
        if (missing.isEmpty()) startRun() else permissionLauncher.launch(missing.toTypedArray())
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
    val currentRoute = backStackEntry?.destination?.route
    val inChecklists = Routes.inChecklists(currentRoute)
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

    // ── the platform session ────────────────────────────────────────────────────────────────────
    //
    // Scoped to the rig screens the same way the checklist session is scoped to its own, and keyed on
    // the prefix for the same reason: the list, an editor and a sensor form are three destinations,
    // and tying the session to any one of them would cycle it every time somebody stepped between.
    val platformState by app.platforms.state.collectAsState()
    val inRigScreens = Routes.inRigScreens(currentRoute)
    val platformConfig = PlatformSyncConfig(
        endpoints = current.routerEndpoints,
        realm = current.realm,
        calibrationSource = current.calibrationSource,
        rigs = current.rigs,
        origin = current.rigRegistryOrigin,
        registryVersion = current.rigRegistryVersion,
        shareLibrary = current.shareRigLibrary,
    )

    LaunchedEffect(inRigScreens, platformConfig) {
        // Unconditionally first, as for the checklist: `start()` is a no-op while a session is up, so
        // this is what makes an endpoint or library change actually take effect rather than being
        // ignored until the screen is next opened.
        app.platforms.stop()
        if (Routes.shouldSyncPlatforms(currentRoute)) app.platforms.start(platformConfig)
    }

    LaunchedEffect(inChecklists, current.checklistEnabled, checklistConfig) {
        // Unconditionally first: `start()` is a no-op while a session is up, so this is what makes an
        // endpoint or identity change actually take effect rather than being ignored until next time.
        app.checklist.stop()
        if (Routes.shouldSyncChecklists(currentRoute, current)) app.checklist.start(checklistConfig)
    }

    // A tapped reminder notification lands here. Consumed once — see `reminderProcedureId`.
    LaunchedEffect(reminderProcedureId) {
        val procedureId = reminderProcedureId
        if (!procedureId.isNullOrEmpty()) {
            nav.navigate("${Routes.CHECKLIST_PREFIX}/$procedureId")
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
    // library changes, which is what makes Save leave the form clean.
    //
    // Two pieces of state rather than one, because a rename is a re-key: `draftEntityId` is the id the
    // editor was *opened* under and is what says which library entry to replace, while the draft's own
    // entityId is what the person is typing. Collapsing them would make renaming a rig add a second
    // one — see Settings.upsertRig.
    var draftEntityId by remember { mutableStateOf<String?>(null) }
    var calibrationDraft by remember(current.rigs, draftEntityId) {
        mutableStateOf(draftEntityId?.let { id -> current.rigs.firstOrNull { it.entityId == id } })
    }
    /** The library entry the draft is editing, or null while a new rig is being added. */
    /**
     * Which step of the rig survey is showing.
     *
     * Up here with the draft rather than inside `CalibrationScreen`, and for the same reason: editing a
     * sensor pushes another destination and pops back, which destroys anything remembered in the screen
     * itself. Without this, adding a sensor on step 4 would return you to step 1.
     *
     * `rememberSaveable` because it is one `Int`, so the step survives process death for free.
     */
    var calibrationStep by rememberSaveable { mutableIntStateOf(0) }
    val savedRig = draftEntityId?.let { id -> current.rigs.firstOrNull { it.entityId == id } }

    /** What the last import or library export did, shown on the rig list until it is left. */
    var libraryMessage by remember { mutableStateOf<String?>(null) }
    /** Parsed and waiting on a confirmation, because applying it would overwrite existing rigs. */
    var pendingImport by remember { mutableStateOf<List<RigCalibration>>(emptyList()) }

    /**
     * Merge parsed rigs into the library.
     *
     * The imported document wins for everything a document describes. Which rig is active and which
     * rigs publish are **not** touched: those are this phone's local policy, and a file somebody
     * mailed over has no business changing what goes on the bus.
     */
    suspend fun applyImport(rigs: List<RigCalibration>) {
        val replaced = rigs.count { current.rigFor(it.entityId) != null }
        val merged = rigs.fold(current) { acc, rig ->
            acc.upsertRig(rig.entityId.takeIf { id -> acc.rigFor(id) != null }, rig)
        }
        saveSettings(app, merged.bumpRigRegistry())
        libraryMessage = "Imported ${rigs.size} " + (if (rigs.size == 1) "rig" else "rigs") +
            if (replaced > 0) " ($replaced replaced)" else ""
    }

    val platformPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching { importPlatforms(context, uri) }
            }
            imported.fold(
                onSuccess = { rigs ->
                    val replacements = importCandidates(rigs, current.rigs)
                        .filter { it.disposition == ImportDisposition.REPLACES }
                        .map { it.rig.name.ifBlank { it.rig.entityId } }
                    when {
                        // Nothing parsed is not a success. Reported plainly, because "imported" and
                        // "imported nothing" look identical from the outside otherwise.
                        rigs.isEmpty() -> libraryMessage = "No platforms in that file"
                        // Purely additive, so there is nothing to lose and nothing to ask about.
                        replacements.isEmpty() -> applyImport(rigs)
                        else -> pendingImport = rigs
                    }
                },
                onFailure = {
                    libraryMessage = "Could not import: ${it.message ?: it::class.simpleName}"
                },
            )
        }
    }
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

    // ── the navigation bar ──────────────────────────────────────────────────────────────────────
    //
    // Built once here and handed to the five top-level screens, which pass it into `ScreenScaffold`'s
    // existing `bottomBar` slot. That slot is also where `FormActions` lives on the five form screens,
    // and the two can never collide because no screen is both a tab and a form — a pushed destination
    // simply never receives this.
    //
    // `popUpTo(start) { saveState = true }` is what stops Run→Live→Run→Live piling up back-stack
    // entries, and `restoreState` is what lets Live keep its scroll position across a visit to Setup.
    // System back on any tab therefore pops to Run and then exits, which is why "main" has to stay the
    // start destination.
    val navBar: @Composable () -> Unit = {
        LoglineNavBar(current = currentRoute) { dest -> goToTab(nav, dest) }
    }

    // Polled here rather than inside the Events route: the badge is on a bar every screen draws, so the
    // one screen that could read this cheaply is the only screen that does not need it. Empty whenever
    // nothing is running, since the publisher clears its timers at both ends of a run.
    val runningTimerCount by produceState(0, app) {
        while (true) {
            value = app.publisher.runningTimers().size
            delay(1_000)
        }
    }

    // Provided once, for every screen's top bar. Ambient rather than threaded: the bar is shared
    // chrome, and passing the publisher's state through fourteen screen signatures to reach it would
    // put a run's connection state into the argument list of the rig editor.
    CompositionLocalProvider(
        LocalRunState provides RunState(
            running = status.running,
            connection = status.connection,
            recording = recording.recording,
            publishing = current.publishEnabled,
            runningTimers = runningTimerCount,
        )
    ) {
    NavHost(navController = nav, startDestination = Routes.MAIN, modifier = modifier) {
        composable(Routes.MAIN) {
            // Pulled on a ticker like the live view, and for the same reason — but only the newest
            // value per subject, which is 29 floats rather than a quarter of a million.
            val live by produceState(LiveLatest(), app) {
                while (true) {
                    value = app.publisher.liveLatest()
                    delay(1_000)
                }
            }
            // The recorder's backlog, on the same ticker and for the same reason: two atomic reads a
            // second, rather than a flow updated once per written sample on the coroutine whose falling
            // behind is the thing being reported.
            val load by produceState(RecordingLoad(), app) {
                while (true) {
                    val queue = app.publisher.recordingLoad()
                    value = RecordingLoad(queue.depth, queue.peak, queue.capacity)
                    delay(1_000)
                }
            }
            // Polled rather than read once: this app writes ~77 MB an hour into that volume and
            // everything else on the phone shares it, so a remembered figure would be wrong within
            // minutes. Ten seconds is a `statvfs` six times a minute against a number shown in GB —
            // the recorder's own tracker polls every thirty for the same reason.
            val freeBytes by produceState(0L, app) {
                while (true) {
                    value = withContext(Dispatchers.IO) { recordingsFreeBytes(context) }
                    delay(10_000)
                }
            }
            MainScreen(
                settings = current,
                status = status,
                recording = recording,
                live = live,
                locationGranted = locationGranted,
                freeBytes = freeBytes,
                unavailableSubjects = unavailable,
                disabledSubjects = disabled,
                ceilings = ceilings,
                load = load,
                onStart = startPublishing,
                onStop = { note -> PublisherService.stop(context, note) },
                // Through saveSettings, like the rig switches and unlike the per-subject ones: these
                // change what the sensors are registered at, so the run has to be redeclared.
                onSetRecordAllMax = { on ->
                    scope.launch { saveSettings(app, current.copy(recordAllMax = on)) }
                },
                // `update`, not `saveSettings`: the chips only exist while nothing is running, so
                // there are no publishers to redeclare — and `saveSettings` would stop and start the
                // foreground service to record a choice about a run that has not begun.
                onSetPublishEnabled = { on ->
                    scope.launch { app.settingsRepository.update(current.copy(publishEnabled = on)) }
                },
                onSetRecordingEnabled = { on ->
                    scope.launch { app.settingsRepository.update(current.copy(recordingEnabled = on)) }
                },
                onSetPublishAllMax = { on ->
                    scope.launch { saveSettings(app, current.copy(publishAllMax = on)) }
                },
                onGrantLocation = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                // Routed by registry entry, not subject: `radio_rssi_dbm` is published under two
                // source ids, so the subject name no longer identifies a single card.
                onOpenSubjectQos = { entry -> nav.navigate(Routes.subjectQos(entry.name)) },
                onToggleSubject = { entry, enabled -> toggleSubject(app, scope, current, entry, enabled) },
                onToggleSubjects = { entries, enabled ->
                    scope.launch { app.settingsRepository.update(current.withSubjects(entries, enabled)) }
                },
                bottomBar = navBar,
            )
        }
        // The fourth tab: everything configured rather than operated. It only routes — the screens it
        // opens are the same pushed destinations Home used to reach, so `route.startsWith(...)` session
        // scoping below is untouched by the move.
        composable(Routes.SETUP) {
            SetupScreen(
                rigSummary = rigSummaryOf(current),
                checklistsEnabled = current.checklistEnabled,
                identity = "${current.realm}/${current.entityId}",
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenRigs = { nav.navigate(Routes.RIGS) },
                onOpenChecklists = { nav.navigate(Routes.CHECKLISTS) },
                onOpenAnnotationButtons = { nav.navigate(Routes.ANNOTATION_BUTTONS) },
                bottomBar = navBar,
            )
        }
        composable(Routes.RECORDINGS) {
            // Re-read whenever a delete bumps the revision, the same shape `tlsRevision` uses. On IO
            // because it is a MediaStore query plus a seek per file — cheap each, but not on main.
            var recordingsRevision by remember { mutableIntStateOf(0) }
            val recordings by produceState<List<SavedRecording>?>(null, recordingsRevision) {
                value = withContext(Dispatchers.IO) { savedRecordings(context) }
            }
            // **At most two scans at a time.** A track is a full decompress of a recording's data
            // section, so a fast scroll through sixty rows would otherwise start sixty of them; the
            // rows that scrolled away have already cancelled, but the reads they began have not.
            val trackReads = remember { Semaphore(2) }
            val trackDirectory = remember(context) { File(context.filesDir, "tracks") }
            LaunchedEffect(recordings) {
                // A deleted recording should not leave its cache entry behind.
                recordings?.let { listed ->
                    withContext(Dispatchers.IO) {
                        TrackCache.prune(trackDirectory, listed.map { ContentUris.parseId(it.uri) }.toSet())
                    }
                }
            }
            // A run that finishes while this tab is on screen should appear without having to leave
            // and come back. The listing is otherwise read once per composition and after a delete,
            // and nothing else tells it a recording has arrived.
            var wasRecording by remember { mutableStateOf(recording.recording) }
            LaunchedEffect(recording.recording) {
                if (wasRecording && !recording.recording) recordingsRevision++
                wasRecording = recording.recording
            }
            LaunchedEffect(recordings) {
                recordings?.let { listed ->
                    app.recordingTags.prune(listed.map { it.name }.toSet())
                }
            }
            RecordingsScreen(
                files = recordings.orEmpty(),
                tagsOf = { name -> recordingTags[name].orEmpty() },
                onLoadTrack = { file ->
                    val id = ContentUris.parseId(file.uri)
                    val stamp = TrackCache.Stamp(file.sizeBytes, file.savedAtMillis)
                    withContext(Dispatchers.IO) {
                        TrackCache.get(trackDirectory, id, stamp)
                            ?: when {
                                // Reading half a gigabyte off the volume the recorder is draining onto
                                // is exactly the contention this app goes out of its way to avoid, and
                                // the Files tab is a between-runs screen. Cached rows still draw.
                                recording.recording -> null
                                else -> trackReads.withPermit {
                                    // Checked again inside the permit: the row ahead may have been
                                    // reading the same recording while this one waited.
                                    TrackCache.get(trackDirectory, id, stamp) ?: run {
                                        val scan = recordingTrack(context, file.uri, file.fixChannelId)
                                        // A truncated read draws a partial shape, and one cached is one
                                        // wrong forever; re-reading a broken file is the cheaper error.
                                        if (!scan.stoppedEarly) {
                                            TrackCache.put(trackDirectory, id, stamp, scan.fixes)
                                        }
                                        scan.fixes
                                    }
                                }
                            }
                    }
                },
                loaded = recordings != null,
                onShare = { context.startActivity(shareIntent(listOf(it))) },
                onOpen = { file ->
                    nav.navigate(Routes.recordingDetail(Uri.encode(file.uri.toString())))
                },
                onDelete = { file ->
                    scope.launch {
                        withContext(Dispatchers.IO) { deleteSavedRecording(context, file) }
                        recordingsRevision++
                    }
                },
                // Suspending, and awaited by the screen, because the screen reports the outcome in its
                // own snackbar — a sweep can partly fail and the count is the only honest thing to say.
                onDeleteAll = { doomed ->
                    val deleted = withContext(Dispatchers.IO) {
                        deleteSavedRecordings(context, doomed)
                    }
                    recordingsRevision++
                    deleted
                },
                query = recordingsQuery,
                onQueryChange = { recordingsQuery = it },
                sort = recordingsSort,
                onSortChange = { recordingsSort = it },
                filter = recordingsFilter,
                onFilterChange = { recordingsFilter = it },
                bottomBar = navBar,
            )
        }

        composable(Routes.LIVE) {
            // Pulled on a ticker rather than pushed: the publish path runs at ~217 samples/s and must
            // not drive recomposition. 5 Hz is smooth to look at and two orders of magnitude cheaper.
            // Pausing stops the pull, so the plots freeze while publishing carries on underneath —
            // which is the point: it is for looking at something that just went past.
            // The recorder's backlog, on its own poll here for the same reason the snapshot is: this
            // route is a sibling of the Session one, not a child, so it cannot see that ticker.
            val liveLoad by produceState(RecordingLoad(), app, livePaused) {
                while (!livePaused) {
                    val queue = app.publisher.recordingLoad()
                    value = RecordingLoad(queue.depth, queue.peak, queue.capacity)
                    delay(1_000)
                }
            }
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
                        offlineOnly = current.offlineTilesOnly,
                        layer = liveLayer,
                        marks = liveMarks,
                        mapTilerKey = current.mapTilerKey,
                        modifier = m,
                    )
                },
                layer = liveLayer,
                onLayerChange = { liveLayer = it },
                marks = liveMarks,
                onMarksChange = {
                    liveSeaMarks = it.seaMarks
                    liveShowTrack = it.track
                    liveShowHeading = it.headingLine
                    liveShowCourse = it.courseVector
                },
                basicOnly = liveBasicOnly,
                onBasicOnlyChange = { liveBasicOnly = it },
                onOpenSubject = { nav.navigate(Routes.subjectDetail(it.name)) },
                hasMapTilerKey = current.mapTilerKey.isNotBlank(),
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                load = liveLoad,
                bottomBar = navBar,
            )
        }
        composable(Routes.RECORDING_DETAIL) { backStackEntry ->
            val uri = backStackEntry.arguments?.getString("uri")?.let(Uri::parse)
            if (uri == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
                return@composable
            }
            // Asked of MediaStore rather than taken from the Files listing: this screen is reached by
            // URI so it survives a process death, and after one the listing has not been read. Querying
            // the one row is also cheaper than rebuilding the list, which reads a summary per file.
            val listed by produceState<se.rise.logline.record.SavedRecording?>(null, uri) {
                value = withContext(Dispatchers.IO) { recordingEntry(context, uri) }
            }

            // **Two loads, because they cost two different things.** The summary is a few seeks off the
            // footer whatever the file's size; the track is a full decompress of the data section, since
            // the writer emits no chunk index. Loading them together would hold the figures behind the
            // picture.
            val details by produceState<Pair<Boolean, McapDetails?>>(false to null, uri) {
                value = false to null
                val read = withContext(Dispatchers.IO) { recordingDetails(context, uri) }
                value = true to read
            }
            val (detailsLoaded, detail) = details

            val track by produceState<TrackState>(TrackState.Reading, uri, detail, detailsLoaded) {
                if (!detailsLoaded) {
                    value = TrackState.Reading
                    return@produceState
                }
                val known = detail?.topics?.let(McapTrack::fixChannel)
                // With a summary, the channel list says up front whether there is anything to look
                // for — so an IMU-only run costs nothing here. **Without one there is no such list**,
                // and a missing summary says nothing at all about GNSS: the scan is handed a null and
                // discovers the channel from the file's own Channel records instead.
                if (detail != null && known == null) {
                    value = TrackState.NoGnss
                    return@produceState
                }
                value = TrackState.Reading
                val scan = withContext(Dispatchers.IO) {
                    recordingTrack(context, uri, known?.channelId)
                }
                value = when {
                    scan.channelFound -> TrackState.Ready(scan.fixes, partial = scan.stoppedEarly)
                    // Nothing found and the walk did not finish: the reader cannot say either way, and
                    // must not fill the silence with a claim about the run.
                    scan.stoppedEarly -> TrackState.Unreadable
                    else -> TrackState.NoGnss
                }
            }

            // **Null until the row is read, and the editor is hidden until then.** Tags are keyed on
            // the file name; `uri.lastPathSegment` is the MediaStore id, so tagging before the name
            // arrives would file the words under a number the list never looks up — silently, and for
            // good.
            val name = listed?.name
            RecordingDetailScreen(
                tags = name?.let { recordingTags[it].orEmpty() },
                onTagsChange = { updated ->
                    name?.let { known -> scope.launch { app.recordingTags.set(known, updated) } }
                },
                chart = { fixes, m ->
                    RecordingChart(
                        fixes = fixes,
                        layer = liveLayer,
                        offlineOnly = current.offlineTilesOnly,
                        mapTilerKey = current.mapTilerKey,
                        modifier = m,
                    )
                },
                name = listed?.name ?: uri.lastPathSegment.orEmpty(),
                sizeBytes = listed?.sizeBytes ?: 0L,
                savedAtMillis = listed?.savedAtMillis ?: 0L,
                details = detail,
                detailsLoaded = detailsLoaded,
                track = track,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SUBJECT_DETAIL) { backStackEntry ->
            val detailEntry = PublishedSubject.forName(
                backStackEntry.arguments?.getString("entry").orEmpty()
            )
            if (detailEntry == null) {
                // A route that named nothing. Popping is the honest response — a blank screen with a
                // back arrow reads as a feature that failed rather than as a link that was wrong.
                LaunchedEffect(Unit) { nav.popBackStack() }
                return@composable
            }
            // **One subject's ring, not the whole snapshot.** `liveSnapshot()` copies every ring —
            // about 390 000 floats — and this screen draws one trace. Pulled on its own ticker for the
            // same reason the Live tab pulls rather than being pushed: the publish path runs at
            // hundreds of samples a second and must never drive recomposition.
            //
            // The text is pulled here and nowhere else, which is the point of it not being on
            // `LiveSnapshot`: copying two thousand strings on the Live tab's ticker, for a screen
            // usually closed, is the tax the whole store exists to avoid.
            val detail by produceState(
                SampleWindow() to TextHistory(),
                app,
                detailEntry,
            ) {
                while (true) {
                    value = app.publisher.liveWindow(detailEntry) to
                        app.publisher.liveText(detailEntry)
                    delay(200)
                }
            }
            val detailNow by produceState(System.currentTimeMillis()) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(500)
                }
            }
            SubjectDetailScreen(
                entry = detailEntry,
                window = detail.first,
                text = detail.second,
                nowMillis = detailNow,
                running = status.running,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SUBJECT_QOS) { backStackEntry ->
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
                // The **request**, not the clamped result: a field bound to `publishRate` cannot be
                // typed into, since a rate above the ceiling would redraw as the ceiling and saving
                // would then store it — the act of opening the screen would destroy the request.
                rate = current.requestedPublishRate(subject),
                publishCeiling = current.publishCeiling(subject),
                rateIsOverridden = current.sensorRates.containsKey(subject),
                recordRate = current.recordRate(subject),
                ratesCanDiffer = current.ratesCanDiffer(subject),
                capabilities = remember(subject) { sensorCapabilities(context, subject) },
                // From the same map the subject rows read, so the two cannot state different maxima
                // for one source.
                ceiling = registryEntry?.let { ceilings[it] },
                // Resolved to an *entry*, not a subject: the route is keyed on the entry name, and
                // `location_fix` is published by two of them. `rateOwnerEntry()` matches on the source
                // kind as well, so the rig's zero point points at its own geometry loop.
                rateOwnerLabel = registryEntry?.rateOwnerEntry()?.let { labelOf(it).name },
                onOpenRateOwner = registryEntry?.rateOwnerEntry()?.let { owner ->
                    { nav.navigate(Routes.subjectQos(owner.name)) }
                },
                achievedHz = achievedHz(
                    samples = subjectStatus.samplesPublished,
                    firstEpochMillis = subjectStatus.firstPublishEpochMillis,
                    lastEpochMillis = subjectStatus.lastPublishEpochMillis,
                ),
                onSave = { qos, rate, recordRate ->
                    scope.launch {
                        saveSettings(
                            app,
                            current.copy(
                                qosOverrides = current.qosOverrides + (subject to qos),
                                // An event-driven subject has no rate — its screen shows no rate
                                // control — so storing one would persist a preference nothing reads
                                // and that the UI could never show back.
                                //
                                // A **null** rate is the other way to store nothing, and it means
                                // something different: a subject that rides another and has been left
                                // following it. Removing the key rather than writing the owner's
                                // current rate is what keeps it following when the owner changes.
                                sensorRates = when {
                                    registryEntry?.eventDriven == true -> current.sensorRates
                                    rate == null -> current.sensorRates - subject
                                    else -> current.sensorRates + (subject to rate)
                                },
                                // Stored even when it equals the publish rate: the two are separate
                                // settings, and leaving this absent would have it silently follow a
                                // later change to the other one.
                                //
                                // Not for a subject that rides another, though. `Settings.recordRate`
                                // reads the *owner's* entry for those, so a key written here would be
                                // one nothing ever reads back — junk in the preferences file that
                                // looks like a setting.
                                recordRates = if (
                                    registryEntry?.eventDriven == true ||
                                    registryEntry?.rateOwner != null
                                ) {
                                    current.recordRates
                                } else {
                                    current.recordRates + (subject to recordRate)
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
        composable(Routes.ANNOTATIONS) {
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
            // On its own ticker rather than folded into `marks` above: a timer's face has to tick every
            // second whether or not anything was marked, and the marks poll is about a list that
            // usually has not changed.
            val timers by produceState(emptyMap<String, Long>(), app) {
                while (true) {
                    value = app.publisher.runningTimers()
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
                runningTimers = timers,
                onStartTimed = { b -> app.publisher.startTimed(b.label, b.severity, b.category) },
                onStopTimed = { b -> app.publisher.stopTimed(b.label, b.severity, b.category) },
                onNote = { text, severity -> app.publisher.mark(text, severity, NOTE_CATEGORY) },
                onEditButtons = { nav.navigate(Routes.ANNOTATION_BUTTONS) },
                onStart = startPublishing,
                expanded = eventsExpanded,
                onExpandedChange = { eventsExpanded = it },
                columns = eventsColumns,
                onColumnsChange = { eventsColumns = it },
                noteShown = eventsNoteShown,
                onNoteShownChange = { eventsNoteShown = it },
                bottomBar = navBar,
            )
        }
        composable(Routes.ANNOTATION_BUTTONS) {
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
        composable(Routes.CHECKLISTS) {
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
                    nav.navigate("${Routes.CHECKLIST_PREFIX}/$procedureId")
                },
                onPublishStarter = { app.checklist.publishStarterProcedures() },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.CHECKLIST) { backStackEntry ->
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
                    onOpenProcedure = { nav.navigate("${Routes.CHECKLIST_PREFIX}/$it") },
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
        composable(Routes.RIGS) {
            RigListScreen(
                rigs = current.rigs,
                activeEntityId = current.activeRigEntityId,
                publishingEntityIds = current.publishingRigEntityIds,
                publishing = status.running,
                onOpenRig = { entityId ->
                    draftEntityId = entityId
                    // Opening a rig starts at the beginning of it. The step survives the trip into a
                    // sensor and back, which is what it is for — but carrying step 4 across from the
                    // last rig somebody edited into a different one would just be disorienting.
                    calibrationStep = 0
                    nav.navigate(Routes.rig(Uri.encode(entityId)))
                },
                onAddRig = {
                    draftEntityId = null
                    calibrationDraft = null
                    calibrationStep = 0
                    nav.navigate(Routes.rig(NEW_RIG))
                },
                // Both of these change which publishers a run declares, so unlike the per-subject
                // switches they go through saveSettings and restart it. The list is read-only while
                // a run is going, so this cannot happen mid-run.
                onSetActive = { scope.launch { saveSettings(app, current.setActiveRig(it)) } },
                onSetPublishing = { entityId, on ->
                    scope.launch { saveSettings(app, current.setRigPublishing(entityId, on)) }
                },
                onExportRegistry = {
                    scope.launch {
                        libraryMessage = withContext(Dispatchers.IO) {
                            runCatching { exportPlatformRegistry(context, current.rigs, current.realm) }
                                .fold(
                                    onSuccess = { "Wrote $it to Downloads/Logline/config" },
                                    onFailure = {
                                        "Could not export: ${it.message ?: it::class.simpleName}"
                                    },
                                )
                        }
                    }
                },
                onImport = { platformPicker.launch(arrayOf("application/json", "*/*")) },
                message = libraryMessage,
                pendingReplacements = pendingImport
                    .filter { current.rigFor(it.entityId) != null }
                    .map { it.name.ifBlank { it.entityId } },
                onConfirmImport = {
                    val rigs = pendingImport
                    pendingImport = emptyList()
                    scope.launch { applyImport(rigs) }
                },
                onCancelImport = {
                    pendingImport = emptyList()
                    libraryMessage = "Import cancelled"
                },
                discovery = platformState.discovery,
                discovered = platformState.discovered,
                linkFailure = platformState.failure,
                onDiscover = { app.platforms.discover() },
                onAdopt = { platform ->
                    platform.rig?.let { rig ->
                        scope.launch {
                            saveSettings(app, current.upsertRig(null, rig).bumpRigRegistry())
                            libraryMessage = "Added ${rig.name.ifBlank { rig.entityId }}"
                        }
                    }
                },
                shareLibrary = current.shareRigLibrary,
                onSetShareLibrary = { on ->
                    scope.launch {
                        // The origin is generated the first time it is needed and then never changes,
                        // the same way operatorId is — it is what stops this phone applying its own
                        // library back over itself when the publisher's cache re-delivers it.
                        val withOrigin = if (on && current.rigRegistryOrigin.isBlank()) {
                            current.copy(rigRegistryOrigin = UUID.randomUUID().toString())
                        } else {
                            current
                        }
                        saveSettings(app, withOrigin.copy(shareRigLibrary = on))
                    }
                },
                incomingRigCount = platformState.incoming?.rigs?.size,
                onApplyIncoming = {
                    val remote = platformState.incoming
                    app.platforms.clearIncoming()
                    if (remote != null) {
                        scope.launch {
                            // Documents only. The active rig and the publishing set are this phone's
                            // policy and are deliberately untouched — see mergeRemoteRigs.
                            val merged = mergeRemoteRigs(
                                local = current.rigs,
                                remote = remote.rigs,
                                protectedEntityIds = current.publishingRigs().map { it.entityId }.toSet(),
                            )
                            // Bumped past the remote's version, not set to it. `mergeRemoteRigs`
                            // keeps rigs this phone is publishing, so what comes out is *not* what
                            // arrived — and republishing different content at the sender's own
                            // version leaves the shared key holding two libraries that each claim to
                            // be the same one, with neither station able to accept the other's.
                            saveSettings(
                                app,
                                current
                                    .copy(rigs = merged, rigRegistryVersion = remote.version)
                                    .bumpRigRegistry(),
                            )
                            libraryMessage = "Applied ${remote.rigs.size} rigs from another station"
                        }
                    }
                },
                onDismissIncoming = { app.platforms.clearIncoming() },
                onBack = {
                    libraryMessage = null
                    nav.popBackStack()
                },
            )
        }
        composable(Routes.RIG) {
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
                    nav.navigate(Routes.sensorMount(Uri.encode(draft.entityId), index))
                },
                onExport = {
                    scope.launch {
                        exportMessage = withContext(Dispatchers.IO) {
                            runCatching { exportCalibration(context, draft) }.fold(
                                onSuccess = { "Wrote $it to Downloads/Logline/config" },
                                onFailure = { "Could not export: ${it.message ?: it::class.simpleName}" },
                            )
                        }
                    }
                },
                exportMessage = exportMessage,
                // Refuses a collision rather than merging two rigs: the entity id is what every key
                // this rig publishes on is built from, so two rigs sharing one would put two rigs'
                // geometry on the same three keys and neither would be readable.
                entityIdError = when {
                    draft.entityId.isBlank() -> null
                    // Refuses a collision rather than merging two rigs: the entity id is what every
                    // key this rig publishes on is built from, so two rigs sharing one would put two
                    // rigs' geometry on the same three keys and neither would be readable.
                    current.entityIdTaken(draft.entityId, draftEntityId) ->
                        "Another rig already uses this id"
                    // A slash would add a chunk to every key this rig publishes on, and to this
                    // screen's own route. See isValidEntityId.
                    !isValidEntityId(draft.entityId) ->
                        "Lowercase letters, digits, - and _ only, starting with a letter or digit"
                    else -> null
                },
                onSave = {
                    scope.launch {
                        val saved = draft.copy(updatedAtEpochMillis = System.currentTimeMillis())
                        saveSettings(app, current.upsertRig(draftEntityId, saved).bumpRigRegistry())
                        draftEntityId = saved.entityId
                        nav.popBackStack()
                    }
                },
                onClear = {
                    scope.launch {
                        savedRig?.let { saveSettings(app, current.removeRig(it.entityId).bumpRigRegistry()) }
                        draftEntityId = null
                        calibrationDraft = null
                        nav.popBackStack()
                    }
                },
                onCancel = {
                    calibrationDraft = savedRig
                    exportMessage = null
                    nav.popBackStack()
                },
                dirty = calibrationDraft != savedRig,
                step = calibrationStep,
                onStepChange = { calibrationStep = it },
            )
        }
        composable(Routes.SENSOR_MOUNT) { backStackEntry ->
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
        composable(Routes.SCAN_QR) {
            QrScannerScreen(
                onScanned = { text ->
                    val parsed = parseSettingsProfile(text)
                    // Back first, then review: a dialog over a live camera preview is a poor place to
                    // read what is about to change.
                    nav.popBackStack()
                    if (parsed == null) {
                        profileMessage = "That QR is not a Logline connection profile."
                    } else {
                        pendingProfile = parsed
                    }
                },
                onBack = { nav.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
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

            pendingProfile?.let { profile ->
                ImportProfileDialog(
                    profile = profile,
                    onApply = { withOperator ->
                        pendingProfile = null
                        scope.launch {
                            // Through saveSettings, not update: endpoints and QoS are declared when
                            // publishers are, so a run has to be restarted to pick them up.
                            saveSettings(app, current.applyProfile(profile, withOperator))
                            profileMessage = "Settings applied."
                        }
                    },
                    onDismiss = { pendingProfile = null },
                )
            }
            if (showConnectionQr) {
                ConnectionQrDialog(
                    payload = current.toConnectionProfile().encode(pretty = false),
                    onDismiss = { showConnectionQr = false },
                )
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
                batteryOptimised = batteryOptimised,
                // The same dialog the first Start offers, so somebody who dismissed it then has a way
                // back to it that is not a hunt through Android settings.
                onRequestBatteryExemption = {
                    requestBatteryExemption(context) { batteryExemptionLauncher.launch(it) }
                },
                onExportProfile = {
                    scope.launch {
                        profileMessage = withContext(Dispatchers.IO) {
                            runCatching { exportSettingsProfile(context, current) }.fold(
                                onSuccess = { "Wrote $it to Downloads/Logline/config" },
                                onFailure = { "Could not export: ${it.message}" },
                            )
                        }
                    }
                },
                // Any file: a JSON profile has no MIME type a document provider agrees on, the same
                // reason the TLS and map imports ask for */*.
                onImportProfile = {
                    profileMessage = null
                    profilePicker.launch(arrayOf("*/*"))
                },
                onShowConnectionQr = { showConnectionQr = true },
                onScanConnectionQr = { nav.navigate(Routes.SCAN_QR) },
                profileMessage = profileMessage,
                offlineMaps = offlineMaps,
                offlineMapMessage = offlineMapMessage,
                // Any file: the archive extensions have no reliable MIME type across providers, which
                // is the same reason the TLS import asks for */*.
                onImportOfflineMap = {
                    offlineMapMessage = null
                    offlineMapPicker.launch(arrayOf("*/*"))
                },
                onDeleteOfflineMap = { map ->
                    deleteOfflineMap(map)
                    offlineMapRevision++
                },
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
            PublishedSubject.VIDEO_COMPRESSED -> saveSettings(app, current.copy(videoEnabled = enabled))
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
/**
 * Switch to a top-level destination.
 *
 * The three options are all load-bearing. `popUpTo(start) { saveState = true }` stops tab-hopping
 * accumulating back-stack entries and remembers what each tab had; `restoreState` puts that back, so
 * Live keeps its scroll and its map after a trip to Setup; `launchSingleTop` stops re-tapping the
 * current tab stacking a second copy of it.
 */
private fun goToTab(nav: NavHostController, dest: TopLevel) {
    nav.navigate(dest.route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

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
    if (settings.cameraEnabled || settings.videoEnabled) add(Manifest.permission.CAMERA)
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

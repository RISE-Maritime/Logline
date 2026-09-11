package se.rise.logline.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.rise.logline.checklist.CHECKLISTS_AVAILABLE
import se.rise.logline.checklist.CHECKLISTS_UNAVAILABLE_REASON
import se.rise.logline.config.Settings
import se.rise.logline.config.ThemeChoice
import se.rise.logline.map.OfflineMap
import se.rise.logline.config.TlsCredential
import se.rise.logline.config.TlsCredentialState
import se.rise.logline.keelson.DiscoveredRouter
import se.rise.logline.keelson.Subjects
import se.rise.logline.keelson.endpointNeedsTls
import se.rise.logline.keelson.isLocalEndpoint
import se.rise.logline.keelson.validateEndpoint
import se.rise.logline.sensors.toIntervalMillis
import se.rise.logline.ui.components.ConfirmDialog
import se.rise.logline.ui.components.FormActions
import se.rise.logline.ui.components.InfoDialog
import se.rise.logline.ui.components.NavRow
import se.rise.logline.ui.components.ScreenScaffold
import se.rise.logline.ui.components.SectionHeader
import se.rise.logline.ui.components.StatusLine
import se.rise.logline.ui.components.StatusTone

@Composable
fun SettingsScreen(
    initial: Settings,
    tlsCredentials: List<TlsCredentialState>,
    onImportCredential: (TlsCredential) -> Unit,
    onClearCredential: (TlsCredential) -> Unit,
    /**
     * The colour scheme, applied at once rather than on Save.
     *
     * Its own callback because it must **not** go through `edited`: every other field on this screen is
     * written by `saveSettings()`, which stops and restarts the service, and restarting a run to change
     * a colour would close the recording somebody is watching. The tags and the per-subject switches
     * take the same route for the same reason. It also means the theme never makes Save dirty — there
     * is nothing left to save.
     */
    onThemeChange: (ThemeChoice) -> Unit,
    /** Capture rates this device's microphone actually offers — asked, not assumed. */
    scanning: Boolean,
    scanResults: List<DiscoveredRouter>,
    /** Why the result list is empty, when it is — an empty scan must not look like a dead button. */
    scanMessage: String?,
    onScan: (String) -> Unit,
    /** Read from `PowerManager` on every resume — the system never announces a change to this. */
    batteryOptimised: Boolean,
    onRequestBatteryExemption: () -> Unit,
    /**
     * Where recordings are written, named the way a person would: `Downloads/Logline`.
     *
     * Not a field on this form. The picker is a system Activity and its answer is a permission grant,
     * so it is stored as it arrives; this is only what the row says.
     */
    folderLabel: String,
    onChooseFolder: () -> Unit,
    /** Tile archives already imported, largest first. */
    offlineMaps: List<OfflineMap>,
    onImportOfflineMap: () -> Unit,
    onDeleteOfflineMap: (OfflineMap) -> Unit,
    /** Why the last import failed, in a sentence, or null. */
    offlineMapMessage: String?,
    /** Hand this phone's configuration to another one, or take one from it. */
    onExportProfile: (withSecrets: Boolean) -> Unit,
    onImportProfile: () -> Unit,
    onShowConnectionQr: () -> Unit,
    onScanConnectionQr: () -> Unit,
    /** What the last export or import came to, in a sentence, or null. */
    profileMessage: String?,
    onSave: (Settings) -> Unit,
    onCancel: () -> Unit,
) {
    var showExport by remember { mutableStateOf(false) }
    var realm by remember { mutableStateOf(initial.realm) }
    var entityId by remember { mutableStateOf(initial.entityId) }
    val endpoints = remember { mutableStateListOf<String>().apply { addAll(initial.routerEndpoints) } }
    var newEndpoint by remember { mutableStateOf("") }
    var scoutAddress by remember { mutableStateOf(initial.scoutAddress) }
    var cameraEntityId by remember { mutableStateOf(initial.cameraEntityId) }
    var cameraResponderId by remember { mutableStateOf(initial.cameraResponderId) }
    var cameraPath by remember { mutableStateOf(initial.cameraPath) }
    var cameraStunUrl by remember { mutableStateOf(initial.cameraStunUrl) }
    var cameraTurnUrl by remember { mutableStateOf(initial.cameraTurnUrl) }
    var cameraTurnUsername by remember { mutableStateOf(initial.cameraTurnUsername) }
    var cameraTurnPassword by remember { mutableStateOf(initial.cameraTurnPassword) }
    var locationSource by remember { mutableStateOf(initial.locationSource) }
    var imuSource by remember { mutableStateOf(initial.imuSource) }
    var recordingEnabled by remember { mutableStateOf(initial.recordingEnabled) }
    var backfillEnabled by remember { mutableStateOf(initial.backfillEnabled) }
    var startOnBoot by remember { mutableStateOf(initial.startOnBoot) }
    var offlineTilesOnly by remember { mutableStateOf(initial.offlineTilesOnly) }
    var mapTilerKey by remember { mutableStateOf(initial.mapTilerKey) }
    var checklistEnabled by remember { mutableStateOf(initial.checklistEnabled) }
    var operatorName by remember { mutableStateOf(initial.operatorName) }
    var operatorRole by remember { mutableStateOf(initial.operatorRole) }
    var rocSiteId by remember { mutableStateOf(initial.rocSiteId) }
    var checklistRealm by remember { mutableStateOf(initial.checklistRealm) }
    var checklistEntityId by remember { mutableStateOf(initial.checklistEntityId) }
    // The time-lapse interval lives on the subject's own rate row, not here — this screen only reports
    val edited = initial.copy(
        realm = realm.trim(),
        entityId = entityId.trim(),
        routerEndpoints = endpoints.map { it.trim() }.filter { it.isNotEmpty() },
        locationSource = locationSource.trim(),
        imuSource = imuSource.trim(),
        recordingEnabled = recordingEnabled,
        backfillEnabled = backfillEnabled,
        startOnBoot = startOnBoot,
        offlineTilesOnly = offlineTilesOnly,
        mapTilerKey = mapTilerKey.trim(),
        scoutAddress = scoutAddress.trim().ifEmpty { Settings.DEFAULT_SCOUT_ADDRESS },
        cameraEntityId = cameraEntityId.trim(),
        cameraResponderId = cameraResponderId.trim().ifEmpty { Settings.DEFAULT_CAMERA_RESPONDER },
        cameraPath = cameraPath.trim(),
        // Not defaulted back: an empty STUN box is a choice on a LAN, where a STUN round trip only
        // delays gathering and host candidates are enough.
        cameraStunUrl = cameraStunUrl.trim(),
        cameraTurnUrl = cameraTurnUrl.trim(),
        cameraTurnUsername = cameraTurnUsername.trim(),
        cameraTurnPassword = cameraTurnPassword,
        checklistEnabled = checklistEnabled,
        operatorName = operatorName.trim(),
        operatorRole = operatorRole.trim(),
        rocSiteId = rocSiteId.trim(),
        checklistRealm = checklistRealm.trim().ifEmpty { Settings.DEFAULT_CHECKLIST_REALM },
        checklistEntityId = checklistEntityId.trim().ifEmpty { Settings.DEFAULT_CHECKLIST_ENTITY },
    )
    val dirty = edited != initial
    // Empty is not a saveable realm or entity: the key expression they build would be malformed.
    val saveable = edited.realm.isNotEmpty() && edited.entityId.isNotEmpty() &&
        edited.routerEndpoints.isNotEmpty()

    var confirmDiscard by remember { mutableStateOf(false) }
    /**
     * Which groups are open. **Only General starts open**, which is what this default means.
     *
     * The same rule the start screen's subject groups follow: twelve sections and twenty-odd
     * explanatory paragraphs is five screens to scroll past, and somebody opening Settings is looking
     * for one of them. Stored as the *open* set rather than the closed one so a group added later is
     * closed like the rest, with no list to remember to update.
     */
    var openSections by rememberSaveable { mutableStateOf(listOf("General")) }
    /**
     * The paragraph currently being read, as (title, body), or null.
     *
     * The long explanations that used to sit under four of these headings are worth having and are not
     * worth the space they took on a screen somebody is scrolling to find one field. Same move the live
     * view made with its magnitude note and axis reference — one dialog, opened from the ⓘ.
     */
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }

    info?.let { (title, body) ->
        InfoDialog(title = title, body = body, onDismiss = { info = null })
    }

    // Reads the *saved* settings rather than this screen's edits: export writes what is stored, and a
    // half-typed key in the field above is not what would go in the file.
    if (showExport) {
        ExportProfileDialog(
            hasMapTilerKey = initial.mapTilerKey.isNotBlank(),
            operatorSummary = listOf(initial.operatorName, initial.operatorRole, initial.rocSiteId)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { null },
            onExport = {
                showExport = false
                onExportProfile(it)
            },
            onDismiss = { showExport = false },
        )
    }
    var confirmClear by remember { mutableStateOf<TlsCredential?>(null) }

    // Leaving with unsaved edits used to drop them without a word, and the system gesture is how most
    // people leave a screen.
    val leave = { if (dirty) confirmDiscard = true else onCancel() }
    BackHandler(enabled = true) { leave() }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            body = "The settings you changed here have not been saved.",
            confirmLabel = "Discard",
            onConfirm = { confirmDiscard = false; onCancel() },
            onDismiss = { confirmDiscard = false },
        )
    }
    confirmClear?.let { credential ->
        ConfirmDialog(
            title = "Clear the ${credential.label.lowercase()}?",
            body = "It is deleted from this phone and has to be imported again from the file it came " +
                "from. Any tls/ endpoint stops working until all three credentials are present.",
            confirmLabel = "Clear",
            dismissLabel = "Cancel",
            onConfirm = { confirmClear = null; onClearCredential(credential) },
            onDismiss = { confirmClear = null },
        )
    }

    ScreenScaffold(
        title = "Settings",
        onBack = leave,
        bottomBar = {
            FormActions(
                onSave = { onSave(edited) },
                onCancel = leave,
                // **`dirty` as well as `saveable`**, the same pair `CalibrationScreen` uses. Enabled on
                // a freshly opened screen, Save is an invitation to restart the run — dropping the
                // Zenoh session and closing the MCAP file — in order to write settings that have not
                // changed.
                saveEnabled = dirty && saveable,
                hint = when {
                    !saveable -> "Realm, entity and at least one endpoint are required."
                    dirty -> "Saving restarts the session if a run is going."
                    else -> null
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsGroup(
                title = "General",
                trailing = null,
                expanded = "General" in openSections,
                onToggle = { openSections = toggleSection(openSections, "General") },
            ) {
                SectionHeader("Identity")
                OutlinedTextField(
                    value = realm,
                    onValueChange = { realm = it },
                    label = { Text("Realm") },
                    supportingText = { Text("First segment of every key — which bus this belongs to.") },
                    isError = realm.isBlank(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = entityId,
                    onValueChange = { entityId = it },
                    label = { Text("Entity ID") },
                    supportingText = { Text("Which physical thing is reporting — this phone.") },
                    isError = entityId.isBlank(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                SectionHeader("Source IDs")
                OutlinedTextField(
                    value = locationSource,
                    onValueChange = { locationSource = it },
                    label = { Text("Location source ID") },
                    supportingText = { Text("Names the GNSS hardware in the key, and the fix's frame_id.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = imuSource,
                    onValueChange = { imuSource = it },
                    label = { Text("IMU source ID") },
                    supportingText = { Text("Same, for the accelerometer, gyroscope and magnetometer.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                SectionHeader(
                    "Appearance",
                    onInfo = {
                        info = "Appearance" to
                            "Follow phone tracks the system setting, including a schedule if the phone " +
                            "has one set. Light and Dark override it.\n\n" +
                            "The colours mean the same thing in both: green fine, amber warning, red " +
                            "error, blue information, grey off."
                    },
                )
                // Consequence, and the reason this control behaves differently from every other one on
                // the screen: it takes effect immediately instead of waiting for Save.
                Text(
                    "Applies at once. It does not restart a run.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChoice.entries.forEach { choice ->
                        FilterChip(
                            selected = initial.theme == choice,
                            onClick = { onThemeChange(choice) },
                            label = { Text(choice.label) },
                        )
                    }
                }

                SectionHeader("Background running")
                SettingSwitch(
                    title = "Start on boot",
                    description = "Begin a run again after the phone restarts, for a phone left wired into " +
                        "a platform. Needs the location permission — Android will not allow an IMU-only run to " +
                        "start itself — and never brings audio or the camera with it, which that same rule " +
                        "forbids. A run stopped by hand stays stopped until the next restart.",
                    checked = startOnBoot,
                    onCheckedChange = { startOnBoot = it },
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (batteryOptimised) "Android may stop long runs" else "Exempt from battery optimisation",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            if (batteryOptimised) {
                                "The foreground service and wake lock are enough on some phones and not on " +
                                    "others — several manufacturers' battery managers stop an app that has " +
                                    "been in the background for hours, which is the shape of every logging " +
                                    "run. The exemption is the documented way out of that."
                            } else {
                                "Android will leave this app running in the background, which is what an " +
                                    "unattended run needs."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (batteryOptimised) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        // Only when there is something to ask for. A button that opens a dialog saying
                        // "already allowed" is a button that teaches people the screen is not to be
                        // trusted — and the state above already says so.
                        if (batteryOptimised) {
                            OutlinedButton(onClick = onRequestBatteryExemption) { Text("Ask Android to allow it") }
                        }
                    }
                }
            }

            SettingsGroup(
                title = "Connection",
                trailing = "${endpoints.size} endpoint" + if (endpoints.size == 1) "" else "s",
                expanded = "Connection" in openSections,
                onToggle = { openSections = toggleSection(openSections, "Connection") },
            ) {
                SectionHeader(
                    "Router endpoints",
                    trailing = "${endpoints.size} configured",
                    onInfo = {
                        info = "Router endpoints" to
                            "Tried in order; the session attaches to whichever answers first, so this is failover, " +
                            "not publishing to several at once. Keep it short and put the likeliest first — an " +
                            "endpoint that silently drops packets costs up to ten seconds before the next is " +
                            "tried, though a refused one fails instantly."
                    },
                )
                endpoints.forEachIndexed { index, value ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(value, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    buildString {
                                        append(
                                            if (endpointNeedsTls(value)) {
                                                "TLS — needs the credentials below"
                                            } else {
                                                "no TLS"
                                            }
                                        )
                                        // Saying so here is cheaper than the run failing with EPERM later.
                                        if (isLocalEndpoint(value)) append(" · asks for local network access")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // The last one cannot be removed: a session with nowhere to connect is not a
                            // state worth allowing.
                            TextButton(
                                onClick = { endpoints.removeAt(index) },
                                enabled = endpoints.size > 1,
                            ) { Text("Remove") }
                        }
                    }
                }
                if (endpoints.size == 1) {
                    Text(
                        "The last endpoint cannot be removed — add another first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val trimmedNew = newEndpoint.trim()
                val endpointError = when {
                    trimmedNew.isEmpty() -> null
                    trimmedNew in endpoints -> "Already in the list"
                    else -> validateEndpoint(trimmedNew)
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    OutlinedTextField(
                        value = newEndpoint,
                        onValueChange = { newEndpoint = it },
                        label = { Text("Add an endpoint") },
                        placeholder = { Text("tcp/192.168.1.42:7447") },
                        // Checked as it is typed: a bad locator used to be accepted here and only surface
                        // much later as a session that would not open.
                        supportingText = endpointError?.let { { Text(it) } },
                        isError = endpointError != null,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            endpoints.add(trimmedNew)
                            newEndpoint = ""
                        },
                        enabled = trimmedNew.isNotEmpty() && endpointError == null,
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Add") }
                }

                SectionHeader(
                    "Find a router",
                    onInfo = {
                        info = "Find a router" to
                            "Zenoh's default scan address is ${Settings.DEFAULT_SCOUT_ADDRESS}. " +
                            "Deployments move it — one keelson router uses :7448 — and a scan on the " +
                            "wrong address looks exactly like an empty network.\n\n" +
                            "Multicast does not leave the local segment, so this never finds an " +
                            "internet router, and a phone on mobile data finds nothing at all."
                    },
                )
                OutlinedTextField(
                    value = scoutAddress,
                    onValueChange = { scoutAddress = it },
                    label = { Text("Scan multicast address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // The button now follows the field it reads, and the results land directly beneath it.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { onScan(scoutAddress.trim()) }, enabled = !scanning) {
                        Text(if (scanning) "Scanning…" else "Scan for routers")
                    }
                    if (scanning) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            "Listening for a few seconds",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                scanMessage?.let {
                    StatusLine(text = "No router found", tone = StatusTone.Warning, detail = it)
                }
                scanResults.forEach { found ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(found.label, style = MaterialTheme.typography.bodyMedium)
                            found.locators.forEach { locator ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        locator,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        onClick = { if (locator !in endpoints) endpoints.add(locator) },
                                        enabled = locator !in endpoints,
                                    ) { Text(if (locator in endpoints) "Added" else "Add") }
                                }
                            }
                        }
                    }
                }
                // The mechanism moved to the ⓘ; this half is what happens when you tap, so it stays.
                Text(
                    "Nothing is connected to until you add it and save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SectionHeader(
                    "Router security",
                    trailing = "${tlsCredentials.count { it.present }}/${tlsCredentials.size} imported",
                    onInfo = {
                        info = "Router security" to
                            "A tls/ endpoint needs all three. They are imported into app-private storage, never " +
                            "bundled in the APK — the client key authenticates this phone to the shared fleet bus."
                    },
                )
                tlsCredentials.forEach { state ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(state.credential.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                state.summary ?: "Not set",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.present) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onImportCredential(state.credential) }) {
                                    Text(if (state.present) "Replace…" else "Import…")
                                }
                                if (state.present) {
                                    // Confirmed: this key is not recoverable from the phone.
                                    TextButton(onClick = { confirmClear = state.credential }) { Text("Clear") }
                                }
                            }
                        }
                    }
                }
            }

            SettingsGroup(
                title = "Recording",
                trailing = null,
                expanded = "Recording" in openSections,
                onToggle = { openSections = toggleSection(openSections, "Recording") },
            ) {
                SectionHeader("Local recording")
                SettingSwitch(
                    title = "Record to MCAP",
                    description = "Writes every published sample to a file. A Zenoh " +
                        "put succeeds even with no router, so the local file is the only complete record " +
                        "of a run — roughly 77 MB per hour, rolling to a new file at 512 MB.",
                    checked = recordingEnabled,
                    onCheckedChange = { recordingEnabled = it },
                )
                // **An action, not a field on this form.** The picker is a system Activity and its
                // answer is a permission grant, so it is stored the moment it arrives rather than
                // waiting for Save — the same rule the folder has always followed. Nothing about a
                // destination needs the run restarted either: it is read when a file is published, so
                // a change lands on the next file, including the one a rotation is about to open.
                NavRow(
                    title = "Recording folder",
                    subtitle = folderLabel,
                    onClick = onChooseFolder,
                )
                SettingSwitch(
                    title = "Fill in dropped links",
                    description = "Hold the last couple of minutes and replay them when the router comes " +
                        "back. Replayed samples keep their original timestamp but arrive after live data, " +
                        "and the window overlaps slightly, so expect a few seconds of duplicates.",
                    checked = backfillEnabled,
                    onCheckedChange = { backfillEnabled = it },
                )

                SectionHeader(
                    "Offline map",
                    trailing = if (offlineMaps.isEmpty()) null else formatBytes(offlineMaps.sumOf { it.sizeBytes }),
                    onInfo = {
                        info = "Offline map" to
                            "The live view's map draws from OpenStreetMap over the network. Import a tile archive " +
                            "— .mbtiles, .gemf, .zip or .sqlite — and it draws from that instead wherever the " +
                            "archive covers, with no network at all. Prepare one ashore for the water you are " +
                            "going to: OpenStreetMap's own tiles may not be bulk-downloaded, which is why the " +
                            "app cannot fetch an area for you."
                    },
                )
                offlineMapMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                offlineMaps.forEach { map ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(map.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                formatBytes(map.sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { onDeleteOfflineMap(map) }) { Text("Remove") }
                        }
                    }
                }
                OutlinedButton(onClick = onImportOfflineMap) { Text("Import a tile archive…") }
                SettingSwitch(
                    title = "Offline tiles only",
                    description = "Draw only from imported archives. Out of coverage the downloader still " +
                        "queues every tile an archive does not cover and waits for each to time out, so " +
                        "this is what stops a map that has what it needs grinding on the ones it does not.",
                    checked = offlineTilesOnly,
                    onCheckedChange = { offlineTilesOnly = it },
                )

                SectionHeader(
                    "Satellite imagery",
                    trailing = if (mapTilerKey.isBlank()) "Esri" else "MapTiler",
                    onInfo = {
                        info = "Satellite imagery" to
                            "Without a key the chart uses Esri's world imagery, which needs no account " +
                            "and covers the globe, but stops at zoom 19 and coarsens well before that " +
                            "away from cities. A MapTiler key swaps in theirs: finer over the " +
                            "Scandinavian coast and one zoom level deeper.\n\n" +
                            "Keys are free for light use from maptiler.com. This one is kept on this " +
                            "phone and is never built into the app. An exported settings profile can " +
                            "carry it, which is how a fleet is provisioned from one file — but only if " +
                            "you tick that box when exporting, because the file goes to Downloads where " +
                            "other apps can read it."
                    },
                )
                OutlinedTextField(
                    value = mapTilerKey,
                    onValueChange = { mapTilerKey = it },
                    label = { Text("MapTiler key") },
                    singleLine = true,
                    // State, not documentation: which source the chart will actually use.
                    supportingText = {
                        Text(
                            if (mapTilerKey.isBlank()) {
                                "Empty — the chart uses Esri world imagery."
                            } else {
                                "The chart uses MapTiler satellite."
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingsGroup(
                title = "Live camera",
                trailing = if (cameraEntityId.isNotBlank()) cameraEntityId.trim() else "off",
                expanded = "Live camera" in openSections,
                onToggle = { openSections = toggleSection(openSections, "Live camera") },
            ) {
                SectionHeader(
                    "Source",
                    onInfo = {
                        info = "Live camera" to
                            "A live view from another entity's camera, over WebRTC.\n\n" +
                            "It is not a keelson subject and none of it travels on the bus. keelson's " +
                            "mediamtx connector proxies only the handshake: this phone asks it over " +
                            "Zenoh, and the video and audio then flow straight from the vessel's " +
                            "MediaMTX to here.\n\n" +
                            "The path is MediaMTX's own name for the stream, not a subject — the " +
                            "<pathname> in its MTX_PATHS_<pathname>_SOURCE. Nothing on the bus " +
                            "advertises it, so it has to be typed.\n\n" +
                            "Leave the entity blank to switch the feature off."
                    },
                )
                OutlinedTextField(
                    value = cameraEntityId,
                    onValueChange = { cameraEntityId = it },
                    label = { Text("Camera entity") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cameraResponderId,
                    onValueChange = { cameraResponderId = it },
                    label = { Text("Responder id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cameraPath,
                    onValueChange = { cameraPath = it },
                    label = { Text("MediaMTX path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SectionHeader(
                    "ICE",
                    onInfo = {
                        info = "ICE" to
                            "How the two ends find each other.\n\n" +
                            "On one network, host candidates are enough and both boxes can be empty. " +
                            "Across networks a STUN server lets each side learn its public address, " +
                            "and where that is not enough — most vessel networks — a TURN server " +
                            "relays the media itself, which costs bandwidth at whoever runs it."
                    },
                )
                OutlinedTextField(
                    value = cameraStunUrl,
                    onValueChange = { cameraStunUrl = it },
                    label = { Text("STUN URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cameraTurnUrl,
                    onValueChange = { cameraTurnUrl = it },
                    label = { Text("TURN URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = cameraTurnUsername,
                        onValueChange = { cameraTurnUsername = it },
                        label = { Text("TURN user") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = cameraTurnPassword,
                        onValueChange = { cameraTurnPassword = it },
                        label = { Text("TURN password") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            SettingsGroup(
                title = "Collaboration",
                trailing = null,
                expanded = "Collaboration" in openSections,
                onToggle = { openSections = toggleSection(openSections, "Collaboration") },
            ) {
                SectionHeader(
                    "Checklists",
                    onInfo = {
                        info = "Checklists" to
                            "Checklists live under their own realm and entity — not this phone's. The " +
                            "defaults are where crowsnest already keeps them; change these only if a " +
                            "deployment has moved the tree."
                    },
                )
                SettingSwitch(
                    title = "Share checklists",
                    // The reason comes first while it cannot be used: somebody reading a greyed row
                    // wants to know why before they want to know what it would have done.
                    description = if (CHECKLISTS_AVAILABLE) {
                        "Work a shared procedure alongside the ROC stations. Opens a second " +
                            "Zenoh session while a checklist screen is open, and publishes your name " +
                            "and site with every item you tick."
                    } else {
                        "$CHECKLISTS_UNAVAILABLE_REASON Work a shared procedure alongside the ROC " +
                            "stations, publishing your name and site with every item you tick."
                    },
                    checked = checklistEnabled && CHECKLISTS_AVAILABLE,
                    onCheckedChange = { checklistEnabled = it },
                    enabled = CHECKLISTS_AVAILABLE,
                )
                if (checklistEnabled && CHECKLISTS_AVAILABLE) {
                    OutlinedTextField(
                        value = operatorName,
                        onValueChange = { operatorName = it },
                        label = { Text("Your name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = operatorRole,
                        onValueChange = { operatorRole = it },
                        label = { Text("Role") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = rocSiteId,
                        onValueChange = { rocSiteId = it },
                        label = { Text("Site") },
                        placeholder = { Text(entityId) },
                        supportingText = {
                            // Not cosmetic: crowsnest discards an incoming event whose site *and* operator
                            // both match its own, so a phone that borrowed a station's name would have its
                            // ticks silently ignored at that station.
                            Text("Must differ from the ROC stations' names. Defaults to the entity id.")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = checklistRealm,
                            onValueChange = { checklistRealm = it },
                            label = { Text("Checklist realm") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = checklistEntityId,
                            onValueChange = { checklistEntityId = it },
                            label = { Text("Checklist entity") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                SectionHeader(
                    "Configuration",
                    onInfo = {
                        info = "Configuration" to
                            "Hand this phone's settings to another one. The file carries everything shareable — " +
                            "endpoints, source ids, switched-off subjects, rates, QoS overrides, annotation " +
                            "buttons; the QR carries the connection alone, which is the part that is the same " +
                            "across a fleet. Neither carries this phone's entity id or its identity on the " +
                            "bus: a profile configures a phone, it does not clone one."
                    },
                )
                profileMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { showExport = true }, modifier = Modifier.weight(1f)) { Text("Export…") }
                    OutlinedButton(onClick = onImportProfile, modifier = Modifier.weight(1f)) { Text("Import…") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onShowConnectionQr, modifier = Modifier.weight(1f)) { Text("Show QR") }
                    OutlinedButton(onClick = onScanConnectionQr, modifier = Modifier.weight(1f)) { Text("Scan QR") }
                }
            }
        }
    }
}

/**
 * One collapsible group of settings.
 *
 * The screen used to be twelve flat sections in one scroll, every one of them expanded, which is what
 * made it read as a configuration file rather than a screen. `SectionHeader` already knew how to
 * collapse — the start screen has used it that way for a while — it simply had never been used here.
 *
 * The inner `SectionHeader`s are kept inside each group rather than flattened away: they carry the
 * per-section summaries ("2/3 imported") that are worth reading, and they are what keeps a long group
 * like Connection scannable once it is open.
 */
@Composable
private fun SettingsGroup(
    title: String,
    trailing: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    SectionHeader(title, trailing = trailing, expanded = expanded, onToggle = onToggle)
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** Open a group, or close it if it was already open. */
private fun toggleSection(open: List<String>, title: String): List<String> =
    if (title in open) open - title else open + title

/** A labelled switch with its explanation — the same shape wherever a setting is a toggle. */
@Composable
internal fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    /** False greys the whole row — for hardware this phone has not got. */
    enabled: Boolean = true,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** What a capture setting costs per hour, so the choice is made with the number in front of you. */
internal fun audioMegabytesPerHour(sampleRateHz: Int, channels: Int): Int =
    (sampleRateHz.toLong() * channels * 2 * 3600 / 1_048_576).toInt()

/**
 * What a frame size costs per hour at a given rate.
 *
 * JPEG size is not a formula — it depends on what the camera is looking at — so this is a rule of
 * thumb, and the constant below is where the honesty lives. It is here to make an order of magnitude
 * visible before someone leaves the camera running for a day, not to be exact.
 */
internal fun cameraMegabytesPerHour(width: Int, height: Int, framesPerSecond: Double): Int =
    (width.toLong() * height * BYTES_PER_PIXEL_Q80 * framesPerSecond * 3600 / 1_048_576).toInt()

/**
 * Bytes per pixel at quality 80.
 *
 * Measured on a Pixel 6: an indoor scene came out at 0.04–0.07, a lens against a desk at 0.014. This
 * sits deliberately **above** that band, because outdoor scenes with sky, water and coastline detail
 * compress far worse than a white ceiling — and a data-rate estimate that reads low is the one that
 * gets somebody a bill. Move it if a real deployment measures otherwise, and move `FormatTest` with it.
 */
private const val BYTES_PER_PIXEL_Q80 = 0.10

/** `0.5` reads as `one frame every 2 s`; anything at or above 1 Hz reads as a rate. */
internal fun formatFrameRate(hz: Double): String = when {
    hz <= 0.0 -> "an unknown rate"
    hz == 1.0 -> "one frame a second"
    hz > 1.0 -> "%.0f frames/s".fmt(hz)
    else -> "one frame every ${"%.0f".fmt(1.0 / hz)} s"
}

/** `44100` reads as `44.1 kHz`, `8000` as `8 kHz` — no trailing `.0`, and no rate called 44. */
internal fun audioRateLabel(sampleRateHz: Int): String = when {
    sampleRateHz < 1000 -> "$sampleRateHz Hz"
    sampleRateHz % 1000 == 0 -> "${sampleRateHz / 1000} kHz"
    else -> "%.1f kHz".fmt(sampleRateHz / 1000.0)
}

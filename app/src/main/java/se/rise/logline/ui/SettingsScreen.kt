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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.rise.logline.config.Settings
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
    /** Capture rates this device's microphone actually offers — asked, not assumed. */
    supportedAudioRates: Set<Int>,
    scanning: Boolean,
    scanResults: List<DiscoveredRouter>,
    /** Why the result list is empty, when it is — an empty scan must not look like a dead button. */
    scanMessage: String?,
    onScan: (String) -> Unit,
    /** Read from `PowerManager` on every resume — the system never announces a change to this. */
    batteryOptimised: Boolean,
    onRequestBatteryExemption: () -> Unit,
    onSave: (Settings) -> Unit,
    onCancel: () -> Unit,
) {
    var realm by remember { mutableStateOf(initial.realm) }
    var entityId by remember { mutableStateOf(initial.entityId) }
    val endpoints = remember { mutableStateListOf<String>().apply { addAll(initial.routerEndpoints) } }
    var newEndpoint by remember { mutableStateOf("") }
    var scoutAddress by remember { mutableStateOf(initial.scoutAddress) }
    var locationSource by remember { mutableStateOf(initial.locationSource) }
    var imuSource by remember { mutableStateOf(initial.imuSource) }
    var recordingEnabled by remember { mutableStateOf(initial.recordingEnabled) }
    var backfillEnabled by remember { mutableStateOf(initial.backfillEnabled) }
    var audioEnabled by remember { mutableStateOf(initial.audioEnabled) }
    var audioSampleRateHz by remember { mutableIntStateOf(initial.audioSampleRateHz) }
    var audioChannels by remember { mutableIntStateOf(initial.audioChannels) }
    var cameraEnabled by remember { mutableStateOf(initial.cameraEnabled) }
    var cameraLensFront by remember { mutableStateOf(initial.cameraLensFront) }
    var cameraWidth by remember { mutableIntStateOf(initial.cameraWidth) }
    var cameraHeight by remember { mutableIntStateOf(initial.cameraHeight) }
    var checklistEnabled by remember { mutableStateOf(initial.checklistEnabled) }
    var operatorName by remember { mutableStateOf(initial.operatorName) }
    var operatorRole by remember { mutableStateOf(initial.operatorRole) }
    var rocSiteId by remember { mutableStateOf(initial.rocSiteId) }
    var checklistRealm by remember { mutableStateOf(initial.checklistRealm) }
    var checklistEntityId by remember { mutableStateOf(initial.checklistEntityId) }
    // The time-lapse interval lives on the subject's own rate row, not here — this screen only reports
    // what it costs. Clamped the same way the publisher clamps it, so the figure matches what will run.
    val frameHz = 1_000.0 / initial.rate(Subjects.IMAGE_COMPRESSED)
        .toIntervalMillis()
        .coerceIn(MIN_FRAME_INTERVAL_MILLIS, MAX_FRAME_INTERVAL_MILLIS)

    val edited = initial.copy(
        realm = realm.trim(),
        entityId = entityId.trim(),
        routerEndpoints = endpoints.map { it.trim() }.filter { it.isNotEmpty() },
        locationSource = locationSource.trim(),
        imuSource = imuSource.trim(),
        recordingEnabled = recordingEnabled,
        backfillEnabled = backfillEnabled,
        scoutAddress = scoutAddress.trim().ifEmpty { Settings.DEFAULT_SCOUT_ADDRESS },
        audioEnabled = audioEnabled,
        audioSampleRateHz = audioSampleRateHz,
        audioChannels = audioChannels,
        cameraEnabled = cameraEnabled,
        cameraLensFront = cameraLensFront,
        cameraWidth = cameraWidth,
        cameraHeight = cameraHeight,
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
                saveEnabled = saveable,
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

            SectionHeader("Router endpoints", trailing = "${endpoints.size} configured")
            Text(
                "Tried in order; the session attaches to whichever answers first, so this is failover, " +
                    "not publishing to several at once. Keep it short and put the likeliest first — an " +
                    "endpoint that silently drops packets costs up to ten seconds before the next is " +
                    "tried, though a refused one fails instantly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

            SectionHeader("Find a router")
            OutlinedTextField(
                value = scoutAddress,
                onValueChange = { scoutAddress = it },
                label = { Text("Scan multicast address") },
                supportingText = {
                    Text(
                        "Zenoh's default is ${Settings.DEFAULT_SCOUT_ADDRESS}. Deployments move it — one " +
                            "keelson router uses :7448 — and a scan on the wrong address looks exactly " +
                            "like an empty network."
                    )
                },
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
            Text(
                "Multicast does not leave the local segment, so this never finds an internet router, and " +
                    "nothing is connected to until you add it and save.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

            SectionHeader("Local recording")
            SettingSwitch(
                title = "Record to MCAP",
                description = "Writes every published sample to a file in Downloads/Logline. A Zenoh " +
                    "put succeeds even with no router, so the local file is the only complete record " +
                    "of a run — roughly 77 MB per hour, rolling to a new file at 512 MB.",
                checked = recordingEnabled,
                onCheckedChange = { recordingEnabled = it },
            )
            SettingSwitch(
                title = "Fill in dropped links",
                description = "Hold the last couple of minutes and replay them when the router comes " +
                    "back. Replayed samples keep their original timestamp but arrive after live data, " +
                    "and the window overlaps slightly, so expect a few seconds of duplicates.",
                checked = backfillEnabled,
                onCheckedChange = { backfillEnabled = it },
            )

            SectionHeader("Background running")
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

            SectionHeader("Audio")
            SettingSwitch(
                title = "Record audio",
                description = "Captures the microphone continuously while a run is going and publishes " +
                    "it on the audio subject. It records every conversation held near the phone — " +
                    "Android shows its microphone indicator throughout, and this is off unless you " +
                    "turn it on.",
                checked = audioEnabled,
                onCheckedChange = { audioEnabled = it },
            )
            if (audioEnabled) {
                Text(
                    "Uncompressed WAV, because keelson's audio message allows only MP3 or WAV and " +
                        "Android cannot encode MP3. Roughly ${audioMegabytesPerHour(audioSampleRateHz, audioChannels)} " +
                        "MB per hour, against about 77 MB per hour for every other subject combined.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Settings.AUDIO_SAMPLE_RATES.forEach { rate ->
                        val available = rate in supportedAudioRates
                        FilterChip(
                            selected = rate == audioSampleRateHz,
                            enabled = available,
                            onClick = { audioSampleRateHz = rate },
                            // 44100 is "44.1 kHz" to anyone who works with audio; integer division
                            // would call it 44 and quietly misname the one rate every device supports.
                            label = { Text(audioRateLabel(rate)) },
                        )
                    }
                }
                if (Settings.AUDIO_SAMPLE_RATES.any { it !in supportedAudioRates }) {
                    Text(
                        "Greyed-out rates are ones this device's microphone does not offer.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1 to "Mono", 2 to "Stereo").forEach { (count, name) ->
                        FilterChip(
                            selected = count == audioChannels,
                            onClick = { audioChannels = count },
                            label = { Text(name) },
                        )
                    }
                }
            }

            SectionHeader("Camera")
            SettingSwitch(
                title = "Record a time-lapse",
                description = "Takes one picture at the image_compressed rate for the whole run and " +
                    "publishes it as a JPEG. It photographs whatever is in front of the phone — " +
                    "Android shows its camera indicator throughout, and this is off unless you turn " +
                    "it on.",
                checked = cameraEnabled,
                onCheckedChange = { cameraEnabled = it },
            )
            if (cameraEnabled) {
                Text(
                    "Roughly ${cameraMegabytesPerHour(cameraWidth, cameraHeight, frameHz)} MB per hour " +
                        "at ${formatFrameRate(frameHz)}, against about 77 MB per hour for every other " +
                        "subject combined. The interval is the image_compressed rate, on its own row " +
                        "in the subject list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Settings.CAMERA_RESOLUTIONS.forEach { (width, height) ->
                        FilterChip(
                            selected = width == cameraWidth && height == cameraHeight,
                            onClick = {
                                cameraWidth = width
                                cameraHeight = height
                            },
                            label = { Text("${width}x$height") },
                        )
                    }
                }
                Text(
                    "A request, like every other rate here: the camera picks the size it supports " +
                        "closest to this one.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(false to "Rear", true to "Front").forEach { (front, name) ->
                        FilterChip(
                            selected = front == cameraLensFront,
                            onClick = { cameraLensFront = front },
                            label = { Text(name) },
                        )
                    }
                }
            }

            SectionHeader("Checklists")
            SettingSwitch(
                title = "Share checklists",
                description = "Work a shared procedure alongside the ROC stations. Opens a second " +
                    "Zenoh session while a checklist screen is open, and publishes your name and site " +
                    "with every item you tick.",
                checked = checklistEnabled,
                onCheckedChange = { checklistEnabled = it },
            )
            if (checklistEnabled) {
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
                Text(
                    "Checklists live under their own realm and entity — not this phone's. The defaults " +
                        "are where crowsnest already keeps them; change these only if a deployment has " +
                        "moved the tree.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                "Router security",
                trailing = "${tlsCredentials.count { it.present }}/${tlsCredentials.size} imported",
            )
            Text(
                "A tls/ endpoint needs all three. They are imported into app-private storage, never " +
                    "bundled in the APK — the client key authenticates this phone to the shared fleet bus.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    }
}

/** A labelled switch with its explanation — the same shape wherever a setting is a toggle. */
@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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

/** The publisher's own bounds, mirrored so the quoted data rate is the one that will actually run. */
private const val MIN_FRAME_INTERVAL_MILLIS = 500L
private const val MAX_FRAME_INTERVAL_MILLIS = 600_000L

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

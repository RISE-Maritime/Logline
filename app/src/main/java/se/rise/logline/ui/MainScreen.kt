package se.rise.logline.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import se.rise.logline.config.Settings
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.publish.ConnectionState
import se.rise.logline.publish.START_TIME_SUBJECTS
import se.rise.logline.publish.LiveLatest
import se.rise.logline.publish.PublisherStatus
import se.rise.logline.publish.RuntimeEstimate
import se.rise.logline.publish.RuntimeLimit
import se.rise.logline.publish.SubjectStatus
import se.rise.logline.publish.timeLeft
import se.rise.logline.publish.TrackPoint
import se.rise.logline.record.RecordingStatus
import se.rise.logline.sensors.achievedHz
import se.rise.logline.ui.components.AppMark
import se.rise.logline.ui.components.ScreenScaffold
import se.rise.logline.ui.components.SectionHeader
import se.rise.logline.ui.components.StatusLine
import se.rise.logline.ui.components.StatusTone
import se.rise.logline.ui.components.readAsOneItem

/**
 * The operational screen: what the sensors are reading, and whether anything has stopped.
 *
 * Built around the reading rather than the plumbing. The earlier version led with sample counts, which
 * answer "is telemetry flowing" but never "is the boat doing three knots" — so a run could look
 * perfectly healthy while publishing nonsense. Each row now shows the measurement, with the rate and
 * the age as small print beneath it, and the screen stays visually quiet until something is wrong.
 */
@Composable
fun MainScreen(
    settings: Settings,
    status: PublisherStatus,
    recording: RecordingStatus,
    /** The newest value per subject, pulled on a ticker — never pushed from the publish path. */
    live: LiveLatest,
    locationGranted: Boolean,
    unavailableSubjects: Set<PublishedSubject>,
    disabledSubjects: Set<PublishedSubject>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onGrantLocation: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenAnnotations: () -> Unit,
    onOpenChecklists: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenSubjectQos: (PublishedSubject) -> Unit,
    /** Switch one subject's publishing and recording on or off. */
    onToggleSubject: (PublishedSubject, Boolean) -> Unit,
    /** Switch a whole section at once. One settings write, not one per subject. */
    onToggleSubjects: (List<PublishedSubject>, Boolean) -> Unit,
) {
    // Drives the ages, the elapsed recording clock and the stalled detection. Without it they freeze
    // exactly when a subject stops producing, which is the one moment anyone reads them.
    val nowMillis by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    // Collapsed rather than expanded state, so a group added later shows its rows by default.
    var collapsed by rememberSaveable { mutableStateOf(listOf<String>()) }

    ScreenScaffold(
        title = "Logline",
        titleIcon = { AppMark() },
        actions = { ConnectionChip(status) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(settings, status, recording, nowMillis, unavailableSubjects, disabledSubjects)
            Actions(
                running = status.running,
                checklistsEnabled = settings.checklistEnabled,
                onStart = onStart,
                onStop = onStop,
                onOpenSettings = onOpenSettings,
                onOpenLive = onOpenLive,
                onOpenAnnotations = onOpenAnnotations,
                onOpenChecklists = onOpenChecklists,
                onOpenCalibration = onOpenCalibration,
                calibratedRig = settings.calibration?.name,
            )

            if (!locationGranted) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusLine(
                            text = "Publishing IMU only",
                            tone = StatusTone.Warning,
                            detail = "Location permission is denied, so there is no GNSS, no speed or " +
                                "course, no true heading and no cell identity.",
                        )
                        TextButton(onClick = onGrantLocation) { Text("Grant location permission") }
                    }
                }
            }

            status.error?.let {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        StatusLine(
                            text = "The run could not start",
                            tone = StatusTone.Error,
                            detail = "$it\n\nFix it in Settings, then start again.",
                        )
                    }
                }
            }

            subjectGroups().forEach { group ->
                val summary = groupSummary(
                    entries = group.entries,
                    status = status,
                    running = status.running,
                    nowMillis = nowMillis,
                    unavailable = unavailableSubjects,
                    disabled = disabledSubjects,
                )
                val isCollapsed = group.title in collapsed
                // What the master switch governs — everything the group has *except* the two that
                // would restart the run, and that a group switch has no business turning on: nobody
                // tapping "Device" expects the microphone and the camera to come on with the
                // barometer. Those keep their own row switch and their own deliberate tap.
                val governed = group.entries.filter { it !in START_TIME_SUBJECTS }
                val anyOn = governed.any { it !in disabledSubjects }
                SectionHeader(
                    title = group.title,
                    trailing = groupBadge(summary, status.running),
                    trailingColor = when {
                        summary.needsAttention -> MaterialTheme.colorScheme.error
                        status.running && summary.live == summary.total -> MaterialTheme.colorScheme.primary
                        else -> null
                    },
                    expanded = !isCollapsed,
                    onToggle = {
                        collapsed = if (isCollapsed) collapsed - group.title else collapsed + group.title
                    },
                    action = {
                        // Checked while *anything* in the group is on, so a mixed group reads as on and
                        // one tap silences it. The badge carries the detail — "3/3 ✓ · 2 off" — because
                        // a two-state switch cannot say "some".
                        Switch(
                            checked = anyOn,
                            onCheckedChange = { onToggleSubjects(governed, it) },
                            enabled = governed.any { it !in unavailableSubjects },
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .semantics { contentDescription = "Publish all of ${group.title}" },
                        )
                    },
                )
                if (!isCollapsed) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            group.entries.forEachIndexed { index, entry ->
                                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                                SubjectRow(
                                    entry = entry,
                                    label = labelOf(entry),
                                    status = status[entry],
                                    value = live[entry],
                                    fix = live.fix,
                                    health = subjectHealth(
                                        status = status[entry],
                                        running = status.running,
                                        nowMillis = nowMillis,
                                        available = entry !in unavailableSubjects,
                                        enabled = entry !in disabledSubjects,
                                        eventDriven = entry.eventDriven,
                                    ),
                                    nowMillis = nowMillis,
                                    enabled = entry !in disabledSubjects,
                                    onOpen = { onOpenSubjectQos(entry) },
                                    onToggle = { onToggleSubject(entry, it) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The one thing worth knowing from across a cockpit: whether there is a router on the other end. */
@Composable
private fun ConnectionChip(status: PublisherStatus) {
    val text = when {
        !status.running -> "Idle"
        status.connection == ConnectionState.Connected -> "Connected"
        status.connection == ConnectionState.Disconnected -> "No router"
        else -> "Connecting"
    }
    val color = when {
        !status.running -> MaterialTheme.colorScheme.onSurfaceVariant
        status.connection == ConnectionState.Connected -> MaterialTheme.colorScheme.primary
        status.connection == ConnectionState.Disconnected -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 12.dp).readAsOneItem("Router $text"),
    ) {
        Surface(color = color, shape = CircleShape, modifier = Modifier.size(9.dp)) {}
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * Two lines of state, with the detail behind them on a tap.
 *
 * Publishing and recording are separate facts — a run can be publishing into nothing while recording
 * perfectly, and the reverse — so each gets a line, rather than one paragraph mixing endpoint, path,
 * file name, counts and size.
 */
@Composable
private fun StatusCard(
    settings: Settings,
    status: PublisherStatus,
    recording: RecordingStatus,
    nowMillis: Long,
    unavailableSubjects: Set<PublishedSubject>,
    disabledSubjects: Set<PublishedSubject>,
) {
    var showDetail by rememberSaveable { mutableStateOf(false) }
    val summary =
        runSummary(status, status.running, nowMillis, unavailableSubjects, disabledSubjects)

    Card(modifier = Modifier.fillMaxWidth().clickable { showDetail = !showDetail }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                !status.running -> StatusLine(
                    text = "Not publishing",
                    tone = StatusTone.Neutral,
                    detail = if (status.totalSamplesPublished > 0) {
                        "Last run published ${formatCounted(status.totalSamplesPublished, "sample")}."
                    } else {
                        "Start to put this phone's sensors on the bus."
                    },
                )
                status.connection == ConnectionState.Disconnected -> StatusLine(
                    text = "Publishing to nothing",
                    tone = StatusTone.Error,
                    detail = "${summary.live}/${summary.total} streams · the router is unreachable, so " +
                        "samples are being dropped.",
                )
                else -> StatusLine(
                    text = "Publishing",
                    tone = if (summary.needsAttention) StatusTone.Warning else StatusTone.Positive,
                    detail = buildString {
                        append("${summary.live}/${summary.total} streams · ")
                        append("${settings.realm}/${settings.entityId}")
                        if (summary.stalled > 0) append(" · ${summary.stalled} stalled")
                        if (summary.failed > 0) append(" · ${summary.failed} failed")
                    },
                )
            }

            if (recording.recording || recording.error != null || recording.dropped > 0) {
                StatusLine(
                    text = when {
                        recording.dropped > 0 ->
                            "Recording — ${formatCounted(recording.dropped, "sample")} dropped"
                        recording.error != null -> "Recording problem"
                        else -> "Recording  ${elapsed(recording.startedAtEpochMillis, nowMillis)}"
                    },
                    tone = when {
                        recording.dropped > 0 || recording.error != null -> StatusTone.Error
                        else -> StatusTone.Neutral
                    },
                    detail = "${formatCounted(recording.messagesWritten, "message")} · " +
                        "%.1f MB".fmt(recording.bytesWritten / 1_048_576.0),
                )
            }

            // Only while a run is going: after Stop the estimate describes a drain that has stopped.
            if (status.running) {
                // Battery and disk both end a run, and the nearer one is the answer — a phone with four
                // hours of charge and forty minutes of space has forty minutes.
                val left = timeLeft(status.batteryRuntime, recording.spaceRuntime)
                when {
                    left != null && left.millis < LOW_RUNTIME_MILLIS -> StatusLine(
                        text = when (left.limit) {
                            RuntimeLimit.Battery -> "Battery running out"
                            RuntimeLimit.Storage -> "Storage running out"
                        },
                        tone = StatusTone.Warning,
                        detail = "About ${formatRuntimeLeft(left.millis)} of logging left at the " +
                            "current rate.",
                    )
                    left != null -> Text(
                        "About ${formatRuntimeLeft(left.millis)} of logging left ${endingOf(left.limit)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Said out loud, because on an unattended rig "is it actually charging" is the
                    // question, and a missing estimate would otherwise look like a broken one. Only
                    // reached when the disk has nothing to say either, since a charging phone still
                    // fills its storage.
                    status.batteryRuntime == RuntimeEstimate.Charging -> Text(
                        "On external power — nothing to estimate a drain from",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Nothing at all for the first few minutes: a run that has not measured a drain yet
                    // has nothing to say, and a placeholder would only invite watching it.
                    else -> Unit
                }
            }

            if (status.replayPending > 0) {
                Text(
                    "Filling the gap — ${formatCounted(status.replayPending.toLong(), "buffered sample")}" +
                        " still to replay",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Said while the gap is still open, not afterwards. Once an outage has run longer than the
            // outbox the replay cannot fill it, and "Replayed 32 768 samples" on the far side of a
            // twenty-minute hole reads exactly like a run that caught up.
            if (status.replayLost > 0) {
                StatusLine(
                    text = "Longer outage than the buffer holds",
                    tone = StatusTone.Warning,
                    detail = "${formatCounted(status.replayLost, "sample")} cannot be replayed" +
                        if (recording.recording) " — they are in the recording." else ".",
                )
            }

            if (showDetail) {
                HorizontalDivider()
                Detail("Realm", settings.realm)
                Detail("Entity", settings.entityId)
                Detail(
                    if (settings.routerEndpoints.size == 1) "Router" else "Routers",
                    settings.routerEndpoints.singleOrNull()
                        ?: "${settings.routerEndpoints.size} configured",
                )
                if (recording.recording) {
                    Detail("File", recording.fileName ?: "starting…")
                    Detail("Folder", "Downloads/Logline")
                    // On its own line rather than folded into the headline, so the disk figure is
                    // readable on a run where the battery is the limit that binds.
                    Detail(
                        "Space",
                        (recording.spaceRuntime as? RuntimeEstimate.Remaining)
                            ?.let { "${formatRuntimeLeft(it.millis)} left" }
                            ?: "measuring…",
                    )
                    if (recording.filesCompleted > 0) {
                        Detail("Saved", formatCounted(recording.filesCompleted.toLong(), "file"))
                    }
                }
                recording.error?.let { Detail("Error", it) }
                if (status.replayed > 0) Detail("Replayed", formatCounted(status.replayed, "sample"))
                // Next to Replayed on purpose: the two are only meaningful read together.
                if (status.replayLost > 0) Detail("Not replayed", formatCounted(status.replayLost, "sample"))
            } else {
                Text(
                    "Tap for endpoint and file details",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Live view leads while a run is going: starting is a one-off, watching is the whole job. Stop stays
 * plainly visible in the error colour, but as an outline rather than a filled bar across the screen.
 */
@Composable
private fun Actions(
    running: Boolean,
    /**
     * Whether to offer the checklist at all.
     *
     * Hidden rather than shown-and-inert when the feature is off: this screen is already dense, and a
     * button most installs will never use is worth less than the row it costs. It is switched on from
     * Settings, or by entering an identity the first time.
     */
    checklistsEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenAnnotations: () -> Unit,
    onOpenChecklists: () -> Unit,
    onOpenCalibration: () -> Unit,
    /** The rig's name once one is calibrated, so the button says what it will open. */
    calibratedRig: String?,
) {
    if (!running) {
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start publishing") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenLive, modifier = Modifier.weight(1f)) { Text("Live view") }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("Settings") }
        }
        // Reachable when stopped as well, because configuring the buttons is what you do beforehand —
        // the screen says plainly that marking needs a run.
        OutlinedButton(
            onClick = onOpenAnnotations,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Mark event") }
        // Independent of a run: a pre-departure checklist is worked through *before* anything is
        // recorded, and it syncs over its own session either way.
        if (checklistsEnabled) {
            OutlinedButton(
                onClick = onOpenChecklists,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Checklists") }
        }
        // Only while stopped. Describing a rig is quayside work done once — offering it in the middle
        // of a run would cost a row from the three buttons that matter then.
        OutlinedButton(
            onClick = onOpenCalibration,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(calibratedRig?.let { "Rig calibration · $it" } ?: "Rig calibration") }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenLive, modifier = Modifier.weight(1f)) { Text("Live view") }
            // Filled, not outlined: while a run is going this is an action taken at a moment that is
            // passing, and it should not need looking for.
            Button(onClick = onOpenAnnotations, modifier = Modifier.weight(1f)) { Text("Mark event") }
        }
        if (checklistsEnabled) {
            OutlinedButton(
                onClick = onOpenChecklists,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Checklists") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("Settings") }
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Stop") }
        }
    }
}

/**
 * `4/4 ✓` when the whole group is healthy, and what is wrong when it is not.
 *
 * Switched-off subjects leave the denominator — `5/5 ✓` is the truth about a group whose sixth subject
 * nobody asked for — so the count of them is appended rather than folded in. Without that, a group
 * reading `3/3 ✓` could be quietly hiding two subjects that stopped because someone flipped a switch.
 */
internal fun groupBadge(summary: GroupSummary, running: Boolean): String {
    val state = when {
        // Nothing left in the denominator: every subject in the group is switched off. A normal state
        // now that a master switch can do it in one tap, and one where both "0/0 ✓" and the
        // unavailable branch below — which `0 == 0` would otherwise take — would be lies.
        summary.total == 0 -> "off"
        summary.unavailable == summary.total -> "not on this device"
        !running -> "${summary.total}"
        summary.failed > 0 -> "${summary.failed} failed"
        summary.stalled > 0 -> "${summary.stalled} stalled"
        summary.live == summary.total -> "${summary.live}/${summary.total} ✓"
        else -> "${summary.live}/${summary.total}"
    }
    return if (summary.off > 0 && summary.total > 0) "$state · ${summary.off} off" else state
}

/** Half an hour: long enough to finish what you are doing, short enough to still be a warning. */
private const val LOW_RUNTIME_MILLIS = 30 * 60 * 1_000L

/** What ends the run, said the way the sentence reads: "…of logging left on this battery". */
private fun endingOf(limit: RuntimeLimit): String = when (limit) {
    RuntimeLimit.Battery -> "on this battery"
    RuntimeLimit.Storage -> "before storage fills"
}

/** `00:12:34` — how long this recording has been going, which is what a log entry wants. */
internal fun elapsed(startedAtMillis: Long, nowMillis: Long): String {
    if (startedAtMillis <= 0L) return "00:00:00"
    val seconds = ((nowMillis - startedAtMillis) / 1000L).coerceAtLeast(0L)
    return "%02d:%02d:%02d".fmt(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
}

/**
 * One subject: what it reads, and how it is.
 *
 * The reading is the largest thing in the row and the only full-strength text in it. Rate, age and
 * state are small print, and they only take colour when something is wrong — so a healthy screen has no
 * colour in it at all, and a problem is the one thing that stands out.
 */
@Composable
private fun SubjectRow(
    entry: PublishedSubject,
    label: SubjectLabel,
    status: SubjectStatus,
    value: Float?,
    fix: TrackPoint?,
    health: SubjectHealth,
    nowMillis: Long,
    enabled: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val hz = achievedHz(
        samples = status.samplesPublished,
        firstEpochMillis = status.firstPublishEpochMillis,
        lastEpochMillis = status.lastPublishEpochMillis,
    )
    // A switched-off subject shows no reading at all. The live store still holds whatever it last
    // published, and a number sitting next to the word "Off" reads as data still arriving.
    val reading = if (health == SubjectHealth.Off) null else readingOf(entry, value, fix)
    val detail = when (health) {
        SubjectHealth.Unavailable -> "Not on this device"
        // The switch is in this row, so the old "turn it on in Settings" is no longer where to go —
        // except for the two that need a restart to take effect, which is worth saying up front.
        SubjectHealth.Off ->
            if (entry in START_TIME_SUBJECTS) "Off — switching it on restarts the run" else "Off"
        SubjectHealth.Failed -> "Failed — ${status.failure}"
        SubjectHealth.Waiting -> "Waiting for the first sample"
        SubjectHealth.Idle ->
            if (status.samplesPublished == 0L) {
                "Not published yet"
            } else {
                "${formatCounted(status.samplesPublished, "sample")} last run"
            }
        SubjectHealth.Stalled ->
            "Stalled — last sample ${formatAge(status.lastPublishEpochMillis, nowMillis)}"
        // An event-driven subject counts marks, it does not have a rate. Deriving one from five
        // button presses over a two-hour run produces "0,0007 Hz", which reads as a sampling rate
        // that has nearly stopped rather than as a person having marked five things.
        SubjectHealth.Live -> if (entry.eventDriven) {
            if (status.samplesPublished == 0L) {
                "Nothing marked yet"
            } else {
                "${formatCounted(status.samplesPublished, "mark")} · " +
                    "last ${formatAge(status.lastPublishEpochMillis, nowMillis)}"
            }
        } else buildString {
            hz?.let { append("${formatRate(it)} Hz · ") }
            append("updated ${formatAge(status.lastPublishEpochMillis, nowMillis)}")
        }
    }
    val detailColor = when (health) {
        SubjectHealth.Failed, SubjectHealth.Stalled -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val readingColor = when (health) {
        SubjectHealth.Live -> MaterialTheme.colorScheme.onSurface
        SubjectHealth.Stalled -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The reading half carries the merged semantics and the tap that opens the detail screen. The
        // switch is deliberately a sibling: `readAsOneItem` is `clearAndSetSemantics`, so a Switch
        // underneath it would be neither announced nor operable by a screen reader.
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
                // One node, so a screen reader says it as a sentence rather than four fragments.
                .readAsOneItem("${label.name}. ${reading ?: "no reading"} ${label.unit.orEmpty()}. $detail"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label.name, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = detailColor)
        }
        if (reading != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    reading,
                    style = MaterialTheme.typography.titleMedium,
                    color = readingColor,
                    textAlign = TextAlign.End,
                )
                label.unit?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Degrees and percent sit tight against the number; SI units take a space, the
                        // way every style guide and every chart plotter writes them.
                        modifier = Modifier.padding(
                            // Degrees, percent and an SI-prefixed rate sit tight against the number:
                            // "2,16G" + "bit/s" has to read as 2.16 Gbit/s, not as two things.
                            start = if (it == "°" || it == "%" || it == "bit/s") 0.dp else 3.dp,
                            bottom = 2.dp,
                        ),
                    )
                }
            }
        }
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            // Unavailable hardware is not something a switch can fix, so the row says so instead.
            enabled = health != SubjectHealth.Unavailable,
            modifier = Modifier
                .padding(end = 12.dp)
                .semantics { contentDescription = "Publish ${label.name}" },
        )
    }
}

/**
 * The value to show, which for the fix is two numbers rather than one.
 *
 * `location_fix` has no scalar to plot and therefore no entry in the live values — but a position is
 * exactly what someone glancing at this screen wants, so it comes off the track instead.
 */
private fun readingOf(entry: PublishedSubject, value: Float?, fix: TrackPoint?): String? = when {
    entry == PublishedSubject.LOCATION_FIX -> fix?.let { formatPosition(it.latitude, it.longitude) }
    value != null -> formatLiveValue(entry, value)
    else -> null
}

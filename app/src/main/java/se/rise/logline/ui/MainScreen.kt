package se.rise.logline.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import se.rise.logline.config.Settings
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.Subjects
import se.rise.logline.publish.ConnectionState
import se.rise.logline.publish.START_TIME_SUBJECTS
import se.rise.logline.publish.LiveLatest
import se.rise.logline.publish.PublisherStatus
import se.rise.logline.publish.RuntimeEstimate
import se.rise.logline.publish.RuntimeLimit
import se.rise.logline.publish.SubjectStatus
import se.rise.logline.publish.formatElapsed
import se.rise.logline.publish.timeLeft
import se.rise.logline.publish.TrackPoint
import se.rise.logline.record.RecordingStatus
import se.rise.logline.sensors.SensorRate
import se.rise.logline.sensors.achievedHz
import se.rise.logline.ui.components.InfoDialog
import se.rise.logline.ui.components.connectionColor
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
/**
 * What the Platforms button says: the active platform, and how many others are also on the bus.
 *
 * Named rather than counted when there is one thing to name — "Platforms · Sealog" is what somebody is
 * checking for. The `+2` matters because those two are publishing geometry under entity ids that are
 * nowhere else on this screen.
 */
internal fun platformSummaryOf(settings: Settings): String? {
    val publishing = settings.publishingPlatforms()
    val active = settings.activePlatform()
    return when {
        settings.platforms.isEmpty() -> null
        active == null -> "${settings.platforms.size} platforms"
        publishing.size > 1 -> "${active.name} +${publishing.size - 1}"
        else -> active.name
    }
}

/**
 * The recorder's backlog as of one poll.
 *
 * A plain snapshot rather than the live `QueueLoad`, because a screen here takes *data* — handing a
 * composable an object whose values change under it would make the reading depend on when Compose
 * happened to look.
 */
data class RecordingLoad(val depth: Long = 0, val peak: Long = 0, val capacity: Int = 0)

@Composable
fun MainScreen(
    settings: Settings,
    status: PublisherStatus,
    recording: RecordingStatus,
    /** The newest value per subject, pulled on a ticker — never pushed from the publish path. */
    live: LiveLatest,
    locationGranted: Boolean,
    /**
     * Free space on the volume the recordings go to, polled by the caller.
     *
     * Passed in rather than read here because a screen takes data, not a `Context` — and polled
     * rather than remembered because it moves: this app writes ~77 MB an hour into it, and everything
     * else on the phone is writing to the same volume.
     */
    freeBytes: Long,
    unavailableSubjects: Set<PublishedSubject>,
    disabledSubjects: Set<PublishedSubject>,
    /**
     * Each sensor's own ceiling in Hz, for the subjects the platform will state one for.
     *
     * Resolved in `App()` because it needs a `Context` and this screen takes data — the same route
     * `unavailableSubjects` takes. Absent for anything `SensorManager` does not own, and a subject
     * missing from the map simply shows its achieved rate alone.
     */
    /**
     * What each source can produce at best, from `rateCeilings()`.
     *
     * Absent for a subject whose hardware is not on this device, and for `log_message`, which is a
     * button press rather than a sample.
     */
    ceilings: Map<PublishedSubject, RateCeiling> = emptyMap(),
    onStart: () -> Unit,
    /** Stop the run, optionally marking it with a closing note. */
    onStop: (String?) -> Unit,
    /**
     * What the *next* run will do — the two chips beside Start.
     *
     * Only reachable while nothing is running, so these are plain settings writes: there are no
     * publishers to redeclare and no session to cycle, which is why `MainActivity` sends them through
     * `update()` rather than the `saveSettings()` that stops and restarts the service.
     */
    onSetPublishEnabled: (Boolean) -> Unit,
    onSetRecordingEnabled: (Boolean) -> Unit,
    /** Flip every subject to full rate, or back to the tuned profile. Restarts the run. */
    onSetRecordAllMax: (Boolean) -> Unit,
    onSetPublishAllMax: (Boolean) -> Unit,
    /**
     * A live camera from some other entity, supplied by `MainActivity` — null when none is configured,
     * which is the default. A `WebView` needs a `Context`, so it arrives the way the chart does.
     */
    cameraView: (@Composable (Modifier) -> Unit)? = null,
    /** What this device's microphone offers, for [MediaSection]. */
    supportedAudioRates: Set<Int>,
    /** Applied at once through `saveSettings`, hence the restart — see [MediaSection]. */
    onMediaChange: (Settings) -> Unit,
    /** The tag vocabulary and which of them are switched on — see [TagsSection]. */
    tags: List<String>,
    activeTags: Set<String>,
    onToggleTag: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onGrantLocation: () -> Unit,
    /** The recorder's backlog, pulled on the caller's ticker — never pushed from the publish path. */
    load: RecordingLoad = RecordingLoad(),
    onOpenSubjectQos: (PublishedSubject) -> Unit,
    /** Switch one subject's publishing and recording on or off. */
    onToggleSubject: (PublishedSubject, Boolean) -> Unit,
    /** Switch a whole section at once. One settings write, not one per subject. */
    onToggleSubjects: (List<PublishedSubject>, Boolean) -> Unit,
    /** The navigation bar, supplied by `MainActivity`. See `TopLevel`. */
    bottomBar: @Composable () -> Unit = {},
) {
    // Drives the ages, the elapsed recording clock and the stalled detection. Without it they freeze
    // exactly when a subject stops producing, which is the one moment anyone reads them.
    val nowMillis by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    /**
     * Which groups are open. **Everything starts closed**, which is what the empty default means.
     *
     * Thirty-nine subjects is five screens of rows to scroll past, and almost none of it is what
     * somebody opening the app wants: they want to know whether it is publishing and whether anything
     * is wrong. The group headings answer both without being opened — `groupBadge` carries `3/3 ✓`,
     * the stalled and failed counts and the `· N off` suffix, and the heading turns red when the group
     * needs attention. Opening one is for when the badge has said something worth looking into.
     *
     * Stored as the *expanded* set rather than the collapsed one so that reading follows from the
     * default rather than being maintained against it: a group added to the registry later is closed
     * like the rest, with no list to remember to update.
     */
    var expanded by rememberSaveable { mutableStateOf(listOf<String>()) }
    // Stop is a two-step now. It sits under the thumb in the pinned bar, next to nothing else, and it
    // ends a run that cannot be resumed — a recording is closed and copied, and the next one starts a
    // new file. One stray tap on a moving boat should not be able to do that.
    var confirmStop by rememberSaveable { mutableStateOf(false) }

    if (confirmStop) {
        StopDialog(
            status = status,
            recording = recording,
            publishing = settings.publishEnabled,
            nowMillis = nowMillis,
            onConfirm = { note ->
                confirmStop = false
                onStop(note)
            },
            onDismiss = { confirmStop = false },
        )
    }

    ScreenScaffold(
        // The tab's own name: the app mark in the bar now carries the identity, so repeating "Logline"
        // beside it said the same thing twice. The run status is drawn by `ScreenScaffold` for every
        // screen, so this no longer passes its own chip.
        title = "Session",
        // Start and Stop are pinned above the navigation bar rather than sitting in the scroll. The
        // page is thirty-nine subjects long, so the one control the screen exists for was a scroll
        // away the moment anybody opened a group — and the thing you reach for at the end of a run is
        // Stop, which was furthest from the thumb. `ScreenScaffold` has one `bottomBar` slot and this
        // stacks both into it, so there is still exactly one `Scaffold` and one set of insets.
        bottomBar = {
            Column {
                Actions(
                    running = status.running,
                    recording = recording.recording,
                    willRecord = settings.recordingEnabled,
                    willPublish = settings.publishEnabled,
                    onWillPublishChange = onSetPublishEnabled,
                    onWillRecordChange = onSetRecordingEnabled,
                    onStart = onStart,
                    onStop = { confirmStop = true },
                )
                bottomBar()
            }
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
            StatusCard(
                settings, status, recording, nowMillis, freeBytes, unavailableSubjects, disabledSubjects,
                load = load,
                // Summed rather than per subject here: the card answers "is the phone keeping up", and
                // which sensor is starved is what the Live view's breakdown is for.
                shedTotal = status.subjects.values.sumOf { it.shed },
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

            // Above the rates, because both are choices made before Start and this is the one that
            // says what the run *is* — the rates only say how much of it there will be.
            TagsSection(
                tags = tags,
                activeTags = activeTags,
                onToggleTag = onToggleTag,
                onAddTag = onAddTag,
                onRemoveTag = onRemoveTag,
            )

            RateModeCard(
                settings = settings,
                onSetRecordAllMax = onSetRecordAllMax,
                onSetPublishAllMax = onSetPublishAllMax,
            )

            subjectGroups().forEach { rawGroup ->
                // **The media subjects have their own section below**, so they are not also rows here —
                // the same control twice on one screen teaches the eye to trust neither. `START_TIME_SUBJECTS`
                // is exactly those three, and the group master switch already filters on it for the same
                // reason, so this is the existing rule applied one level up.
                val group = rawGroup.copy(entries = rawGroup.entries.filter { it !in START_TIME_SUBJECTS })
                if (group.entries.isEmpty()) return@forEach
                val summary = groupSummary(
                    entries = group.entries,
                    status = status,
                    running = status.running,
                    nowMillis = nowMillis,
                    unavailable = unavailableSubjects,
                    disabled = disabledSubjects,
                )
                val isExpanded = group.title in expanded
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
                    expanded = isExpanded,
                    onToggle = {
                        expanded = if (isExpanded) expanded - group.title else expanded + group.title
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
                if (isExpanded) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            group.entries.forEachIndexed { index, entry ->
                                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                                SubjectRow(
                                    entry = entry,
                                    label = labelOf(entry),
                                    status = status[entry],
                                    value = live[entry],
                                    // This row's own source, not "the position": three entries publish
                                    // `location_fix` and each shows what its own solution says.
                                    fix = live.fixes[entry],
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
                                    ceiling = ceilings[entry],
                                    requested = settings.publishRate(entry.subject),
                                    recording = settings.recordRate(entry.subject)
                                        .takeIf { it != settings.publishRate(entry.subject) },
                                    cappedBy = entry.rateOwnerEntry()
                                        ?.takeIf { settings.publishRateIsCapped(entry.subject) }
                                        ?.let { labelOf(it).name },
                                    onOpen = { onOpenSubjectQos(entry) },
                                    onToggle = { onToggleSubject(entry, it) },
                                )
                            }
                        }
                    }
                }
            }

            // **Last on the page.** Audio and the camera are the heaviest things a run can carry
            // and the least often changed — the switches are off by default and meant to stay that
            // way — so they sit below the per-subject groups rather than above them, where they were
            // pushing the sensor list a screen further down for a control most runs never touch.
            MediaSection(
                settings = settings,
                supportedAudioRates = supportedAudioRates,
                unavailableSubjects = unavailableSubjects,
                onChange = onMediaChange,
                onOpenSubjectQos = onOpenSubjectQos,
            )

            // **Below what this phone records, because it is the other direction.** Everything above
            // decides what this run captures; this is somebody else's camera arriving. It reads as a
            // footnote to the media section rather than a competitor to the chart, which is why it left
            // the Live tab — there it sat under the chart implying the two were the same kind of thing.
            cameraView?.let { camera ->
                SectionHeader("Live camera")
                Card(Modifier.fillMaxWidth()) {
                    camera(Modifier.fillMaxWidth().height(CAMERA_HEIGHT))
                }
            }

        }
    }
}


/**
 * The session summary: what the last run left behind, or what this one is doing.
 *
 * It answers three questions and no more — what happened last time, is there room for another run,
 * and where will it connect — with the endpoint and the file details one tap behind the row along its
 * bottom edge. It deliberately no longer says **"Not publishing"**: the chip in the app bar says
 * `Idle` and the button pinned above the navigation bar says `START Publish & REC`, so a third statement
 * of the same fact was most of the card, and the two facts worth having were underneath it.
 *
 * **An icon means attention.** [StatusLine] is used only for a warning or a failure, so the resting
 * card carries none — a run that saved perfectly used to be announced with the same ⓘ as one that
 * dropped samples, which teaches the eye to skip both. Everything calm is a label with its figure.
 *
 * Publishing and recording stay separate facts — a run can be publishing into nothing while recording
 * perfectly, and the reverse — so each keeps its own block rather than being mixed into one line.
 */
@Composable
private fun StatusCard(
    settings: Settings,
    status: PublisherStatus,
    recording: RecordingStatus,
    nowMillis: Long,
    freeBytes: Long,
    unavailableSubjects: Set<PublishedSubject>,
    disabledSubjects: Set<PublishedSubject>,
    /** The recorder's backlog, pulled on the caller's ticker. See `SensorPublisher.recordingLoad`. */
    load: RecordingLoad,
    /** Samples shed by the sensor flows across every subject this run. */
    shedTotal: Long,
) {
    var showDetail by rememberSaveable { mutableStateOf(false) }
    val summary =
        runSummary(status, status.running, nowMillis, unavailableSubjects, disabledSubjects)
    // Kept on screen after the run, which it did not use to be: the card was gated on `recording`
    // alone, so the file name, the count and the fact that it reached Downloads all disappeared at
    // exactly the moment somebody wanted to read them.
    val finishedRecording = !recording.recording &&
        (recording.messagesWritten > 0 || recording.filesCompleted > 0)

    Card(modifier = Modifier.fillMaxWidth().clickable { showDetail = !showDetail }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (status.running) {
                // An unreachable router is only a fault for a run that is *trying* to publish. On a
                // record-only run it is expected — very often the reason the run is record-only — and
                // an error there would have the app reporting a fault it was told to cause.
                if (status.connection == ConnectionState.Disconnected && settings.publishEnabled) {
                    StatusLine(
                        text = "Publishing to nothing",
                        tone = StatusTone.Error,
                        detail = "${summary.live}/${summary.total} streams · the router is unreachable, " +
                            "so samples are being dropped.",
                    )
                } else {
                    CardHeadline(
                        // A record-only run is not publishing, and a card that said so anyway would be
                        // the loudest wrong thing on the screen.
                        text = if (settings.publishEnabled) "Publishing" else "Recording only",
                        color = if (summary.needsAttention) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        value = buildString {
                            append("${summary.live}/${summary.total} streams")
                            // The achieved rate, summed — the one number that says the run is moving
                            // rather than merely open. Absent for the first second, where it is 0.
                            if (summary.samplesPerSecond > 0.0) {
                                append(" · ${formatRate(summary.samplesPerSecond)} samples/s")
                            }
                            if (summary.stalled > 0) append(" · ${summary.stalled} stalled")
                            if (summary.failed > 0) append(" · ${summary.failed} failed")
                        },
                    )
                }
                if (recording.recording && recording.dropped == 0L && recording.error == null) {
                    // The size and when it began, not the message count. A running total of messages
                    // is a number nobody can do anything with — it does not say whether the file is
                    // complete (`dropped` does, in red) and it does not say whether there is room for
                    // it (the size does).
                    CardFact(
                        label = "Recording · ${elapsed(recording.startedAtEpochMillis, nowMillis)}",
                        value = "%.1f MB".fmt(recording.bytesWritten / 1_048_576.0) +
                            " · started ${formatClock(recording.startedAtEpochMillis)}",
                    )
                }
            } else if (status.totalSamplesPublished > 0) {
                CardFact(
                    label = "Last run",
                    value = buildString {
                        append(formatCounted(status.totalSamplesPublished, "sample"))
                        // The span of the data, not of the session — see publishedSpanMillis. Left off
                        // when there is only one sample to span, where it would read 00:00:00.
                        val span = status.publishedSpanMillis
                        if (span >= 1_000L) append(" · ${formatElapsed(span)}")
                        lastRecordingOf(recording, finishedRecording)?.let { append(" · $it") }
                    },
                )
            } else {
                // Nothing has run in this process. "Ready to publish" adds something the `Idle` chip
                // does not: that the phone is configured and the space has been checked.
                CardHeadline(
                    text = "Ready to publish",
                    color = MaterialTheme.colorScheme.onSurface,
                    // Streams that will actually go out: switched-off ones are already out of `total`,
                    // and hardware this phone does not have would be a promise it cannot keep.
                    value = "${summary.total - summary.unavailable} streams ready",
                )
            }

            // Free space, and what it buys. Shown when nothing is running because that is the moment
            // the question is asked — "can I record the whole passage?" — and there is no measured
            // fill rate to answer it with yet. Once a run starts, `spaceRuntime` below measures the
            // real thing, including whatever else on the phone is filling the same volume, so this
            // arithmetic gets out of its way.
            if (!status.running) {
                val capacity = recordingCapacityMillis(freeBytes, megabytesPerHour(settings))
                Text(
                    buildString {
                        append(formatBytes(freeBytes))
                        append(" free")
                        if (capacity != null) {
                            append(" · about ")
                            append(formatCapacity(capacity))
                            append(" of recording")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (capacity == 0L) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            // Only while a run is going: after Stop the estimate describes a drain that has stopped.
            if (status.running) {
                // Battery and disk both end a run, and the nearer one is the answer — a phone with four
                // hours of charge and forty minutes of space has forty minutes.
                val left = timeLeft(status.batteryRuntime, recording.spaceRuntime)
                when {
                    // Ahead of the prediction below, because it is no longer one: the charge has
                    // already crossed the line and the run has already done something about it. A
                    // screen still saying "about 20 minutes left" over the top of that would be
                    // describing a future the phone has stopped waiting for.
                    status.batteryCritical -> StatusLine(
                        text = "Battery low — recording secured",
                        tone = StatusTone.Warning,
                        detail = "Everything up to this point is saved to Downloads. Recording " +
                            "continues into a new file; plug in to keep the run going.",
                    )
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
                    // Said out loud, because on an unattended platform "is it actually charging" is the
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

            // Whether the phone is keeping up, in three states rather than two. The middle one is the
            // whole point: before it, this card went straight from a healthy readout to "N dropped",
            // and the queue holds about forty-five seconds of slack that filled with nothing said.
            //
            // Shown only while running — a stopped run's peak belongs to the past, and the card above
            // already reports what the last one wrote.
            if (status.running) {
                val health = throughputHealth(
                    peakDepth = load.peak,
                    capacity = load.capacity,
                    dropped = recording.dropped,
                    shed = shedTotal,
                )
                when (health) {
                    ThroughputHealth.KeepingUp -> StatusLine(
                        text = "Keeping up",
                        tone = StatusTone.Positive,
                        detail = "Nothing waiting to be written, and nothing lost.",
                    )

                    ThroughputHealth.UnderStrain -> StatusLine(
                        text = "Under strain",
                        tone = StatusTone.Warning,
                        detail = "The recorder queue reached ${formatCount(load.peak)} of " +
                            "${formatCount(load.capacity.toLong())}. Nothing lost yet — lower a rate " +
                            "or switch a subject off if it keeps climbing.",
                    )

                    ThroughputHealth.Losing -> StatusLine(
                        text = "Losing data",
                        tone = StatusTone.Error,
                        detail = buildString {
                            if (recording.dropped > 0) {
                                append(formatCounted(recording.dropped, "sample"))
                                append(" never reached the file")
                            }
                            if (shedTotal > 0) {
                                if (isNotEmpty()) append("; ")
                                append(formatCounted(shedTotal, "sample"))
                                append(" never left the sensor")
                            }
                            append(".")
                        },
                    )
                }
            }

            // A file that could not be written or copied — a different failure from falling behind,
            // and the only one of the two that survives the run being stopped.
            recording.error?.let {
                StatusLine(text = "Recording problem", tone = StatusTone.Error, detail = it)
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

            HorizontalDivider()
            // A row rather than the instruction it used to be. "Tap for endpoint and file details" told
            // somebody what to do; this names where they are going, the way the rest of the app does.
            DetailsRow(
                label = when {
                    showDetail -> "Endpoint & recording"
                    // Never for several endpoints: Zenoh reports the routers' zids, not the locator a
                    // transport was opened on, so which one answered is a thing this app cannot know.
                    status.running && status.connection == ConnectionState.Connected ->
                        settings.routerEndpoints.singleOrNull()?.let { "Connected to $it" }
                            ?: "Endpoint & recording"
                    else -> "Endpoint & recording"
                },
                expanded = showDetail,
            )

            if (showDetail) {
                Detail("Realm", settings.realm)
                Detail("Entity", settings.entityId)
                Detail(
                    if (settings.routerEndpoints.size == 1) "Router" else "Routers",
                    settings.routerEndpoints.singleOrNull()
                        ?: "${settings.routerEndpoints.size} configured",
                    // The one row on this panel that is about right now rather than about
                    // configuration, so it carries the light rather than a fourth line of prose.
                    dot = connectionColor(status.running, status.connection),
                )
                if (recording.recording || finishedRecording) {
                    // Not the folder. Every finished recording goes to the same one, and the Files
                    // tab is now where they are read — a card repeating the path on every run was
                    // answering a question nobody had twice.
                    Detail("File", recording.fileName ?: "starting…")
                    if (recording.startedAtEpochMillis > 0L) {
                        Detail("Started", formatClock(recording.startedAtEpochMillis))
                        // Frozen at Stop by `stoppedAtEpochMillis`: taking `now` for a finished
                        // recording would have the clock run on over a file nothing is writing to.
                        Detail(
                            "Recording time",
                            formatElapsed(
                                (recording.stoppedAtEpochMillis.takeIf { !recording.recording && it > 0L }
                                    ?: nowMillis) - recording.startedAtEpochMillis,
                            ),
                        )
                    }
                    Detail("Size", formatBytes(recording.bytesWritten))
                    // On its own line rather than folded into the headline, so the disk figure is
                    // readable on a run where the battery is the limit that binds. Live only: the
                    // estimate is cleared at Stop, and a finished run showing "measuring…" would be
                    // reporting on a drain that is not happening.
                    if (recording.recording) {
                        Detail(
                            "Space",
                            (recording.spaceRuntime as? RuntimeEstimate.Remaining)
                                ?.let { "${formatRuntimeLeft(it.millis)} left" }
                                ?: "measuring…",
                        )
                    }
                    if (recording.filesCompleted > 0) {
                        Detail("Saved", formatCounted(recording.filesCompleted.toLong(), "file"))
                    }
                }
                // Answers "how long did that run for?" without arithmetic on two clock times. Only
                // once it is over: while it is running the headline already carries a live clock.
                if (!status.running && status.publishedSpanMillis >= 1_000L) {
                    Detail("Ran for", formatElapsed(status.publishedSpanMillis))
                }
                Detail("Free space", formatBytes(freeBytes))
                // The rate the capacity above divides by. Off the resting card because it is an input
                // to an estimate rather than a fact about this phone, and here because a capacity
                // nobody can check the arithmetic of is a capacity nobody believes.
                if (!status.running) Detail("Fill rate", "~${megabytesPerHour(settings)} MB/h")
                recording.error?.let { Detail("Error", it) }
                if (status.replayed > 0) Detail("Replayed", formatCounted(status.replayed, "sample"))
                // Next to Replayed on purpose: the two are only meaningful read together.
                if (status.replayLost > 0) Detail("Not replayed", formatCounted(status.replayLost, "sample"))
            }
        }
    }
}

/**
 * What the last run's recording is worth saying in one clause, or nothing.
 *
 * `bytesWritten` is **per file, not per run** — it restarts at every 512 MB rotation — so a run that
 * rotated is counted in files rather than megabytes, where a size would silently describe the last
 * one. `filesCompleted` counts successful copies to Downloads only, which is what makes "in Downloads"
 * an answer to "did it save?" rather than a hope.
 */
private fun lastRecordingOf(recording: RecordingStatus, finished: Boolean): String? = when {
    !finished -> null
    recording.filesCompleted > 1 ->
        "${formatCounted(recording.filesCompleted.toLong(), "file")} saved"
    // "saved" rather than "in Downloads", which wrapped the line onto a second row on a Pixel 6 —
    // measured, not guessed. Where it went is the `Folder` row in the panel below.
    recording.filesCompleted == 1 -> "%.1f MB saved".fmt(recording.bytesWritten / 1_048_576.0)
    recording.messagesWritten > 0 -> "%.1f MB recorded".fmt(recording.bytesWritten / 1_048_576.0)
    else -> null
}

/**
 * The three numbers a rate has, in the order the questions get asked.
 *
 * `55.3 Hz · set 50 · max 200` — what is actually going out, what this phone asked for, and what the
 * source could give. They are three different claims and any pair of them can disagree honestly: a
 * request is only a hint (`SensorRate`), and a `Reported` ceiling is what a sensor *advertises* while
 * Android delivers to every client at the fastest rate any of them asked for. Seeing one number alone
 * is what makes a run look wrong when it is not.
 *
 * Parts drop out rather than being faked: no achieved rate before the second sample, no ceiling where
 * the source will not state one.
 */
private fun rateLine(
    achievedHz: Double?,
    requested: SensorRate,
    ceiling: RateCeiling?,
    /** The recording rate, when it differs from the publish rate. Null when the two agree. */
    recording: SensorRate? = null,
    /**
     * The subject whose rate is holding this one down, when one is — otherwise null.
     *
     * **It replaces the ceiling rather than joining it**, which is what keeps this to three numbers.
     * In this state the sensor's own limit is the least useful of the three: it is not what is
     * deciding anything, and printed beside a rate it cannot explain it actively invites raising a
     * figure that will not move. What is worth a slot is the name of the thing to go and change.
     *
     * Only for a subject that *rides* another and asked for more than it is getting. A subject merely
     * following its owner is not flagged — its stored rate is absent, so the figure shown **is** the
     * owner's and contradicts nothing. And a head subject clamped to its own record rate is not
     * flagged either: the `rec` figure is already on the row, a few characters to the left.
     */
    cappedBy: String? = null,
): String =
    listOfNotNull(
        achievedHz?.let { "${formatRate(it)} Hz" },
        recording?.let { "rec ${rateWord(it)}" },
        // Labelled `pub` only when there is a `rec` beside it to be told apart from; on a subject where
        // the file and the wire agree, one unqualified figure is the honest reading.
        if (recording != null) "pub ${rateWord(requested)}" else requestedLabel(requested),
        cappedBy?.let { "capped by $it" } ?: ceiling?.label(),
    ).joinToString(" · ")

/** `50`, or `max` — the figure without its verb, for the two-rate form. */
private fun rateWord(rate: SensorRate): String = when (rate) {
    SensorRate.Max -> "max"
    is SensorRate.Hz -> formatRate(rate.hz)
}

/**
 * `set 50`, or `set max`.
 *
 * A word rather than a figure for [SensorRate.Max], because it is a zero delay — "give me everything"
 * — and not the advertised maximum written out: this device advertises 415.97 Hz on the gyroscope and
 * delivers around 442 Hz when asked for 400.
 */
private fun requestedLabel(requested: SensorRate): String = when (requested) {
    SensorRate.Max -> "set max"
    is SensorRate.Hz -> "set ${formatRate(requested.hz)}"
}

/** The card's headline, for a state rather than a figure: `Publishing`, `Ready to publish`. */
@Composable
private fun CardHeadline(text: String, color: Color, value: String?) {
    Column {
        Text(text, style = MaterialTheme.typography.titleMedium, color = color)
        value?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A figure with the label that says what it is — the resting card's one shape, and no icon. */
@Composable
private fun CardFact(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The way into the endpoint and file details, along the card's bottom edge. */
@Composable
private fun DetailsRow(label: String, expanded: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (expanded) "Hide details" else "Show endpoint and file details",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Detail(label: String, value: String, dot: Color? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        dot?.let {
            // Same 9 dp lamp the app bar's chip carries, from the same `connectionColor`. Never colour
            // alone: the chip above spells the state in words, and this row's value is the endpoint.
            Surface(color = it, shape = CircleShape, modifier = Modifier.size(9.dp)) {}
            Spacer(Modifier.width(6.dp))
        }
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}


/**
 * The second step of stopping, and the only place a run can be given a closing note.
 *
 * Two jobs in one interruption rather than two. Stopping is irreversible — the recording is closed and
 * copied to Downloads, and starting again opens a new file — so it is worth a deliberate second tap;
 * and the moment somebody decides to stop is exactly when they know what the run was, which is the
 * moment to ask. Splitting those into separate prompts would make the second one an obstacle.
 *
 * The note is a **note**, not a rename: it goes onto the bus and into the recording as a `log_message`
 * mark, where Foxglove's Log panel can find it beside every other mark of the run. Nothing is renamed —
 * a run can have rotated through several files by now, some already copied to Downloads.
 *
 * The figures are shown because they are the answer to "have I got what I came for", which is the
 * question actually being asked at this moment.
 */
@Composable
private fun StopDialog(
    status: PublisherStatus,
    recording: RecordingStatus,
    /** Whether this run is putting anything on the bus — `Settings.publishEnabled`. */
    publishing: Boolean,
    nowMillis: Long,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop the run?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stopFigures(
                        samples = status.totalSamplesPublished,
                        publishing = publishing,
                        elapsed = elapsed(recording.startedAtEpochMillis, nowMillis),
                        recording = recording.recording,
                        bytesWritten = recording.bytesWritten,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = note,
                    // A mark is one line in a log panel; a newline or a tab would only break the shape
                    // of it, the same rule the annotation screen's note field follows.
                    onValueChange = { note = it.replace('\n', ' ').replace('\t', ' ') },
                    label = { Text("Closing note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stopNoteHint(publishing = publishing, recording = recording.recording),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(note.trim().ifBlank { null }) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Stop") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep running") } },
    )
}

/**
 * Max rate, or the tuned profile — two switches, because the file and the wire want opposite things.
 *
 * The file is what analysis is run against and wants every sample the sensor gives; the bus is for
 * watching a trial and wants as little as will still show what is happening. Recording at max while
 * publishing a thin stream is the normal case, which is why these are two switches rather than one.
 *
 * **They set a rate and nothing else.** They do not switch a subject on: a source somebody deselected
 * stays deselected, and the off-subject set is a separate decision entirely — which is why the wording
 * here is "max rate" rather than "everything", a phrase that read as though it would start things.
 *
 * **And they are a mode, not a bulk edit.** Per-subject rates are untouched underneath, so flipping to
 * max for a trial and back returns the tuned profile intact.
 */
/**
 * Which rate the file and the bus run at — two choices, not two switches.
 *
 * These were toggles, and a toggle was the wrong control: **off** read as "recording is disabled" when
 * it meant "use the configured rate instead of the maximum". The state being chosen is *which rate*,
 * so a two-option selector says it and a switch cannot.
 *
 * The file and the bus get separate choices because they want opposite things — the file is what
 * analysis is run against, the bus is for watching a trial — and *Recording maximum, Publishing
 * configured* is the useful field-test position: keep everything locally without flooding the link.
 * That is also the shipped default.
 *
 * Neither choice switches a subject on. A source somebody deselected stays deselected; this is only
 * ever a rate.
 */
@Composable
private fun RateModeCard(
    settings: Settings,
    onSetRecordAllMax: (Boolean) -> Unit,
    onSetPublishAllMax: (Boolean) -> Unit,
) {
    var showHelp by rememberSaveable { mutableStateOf(false) }

    if (showHelp) {
        InfoDialog(
            title = "Sampling rates",
            body = "Two independent choices, one for the file and one for the bus.\n\n" +
                "Configured uses the rate set on each subject's own page. Maximum overrides all of " +
                "them with as fast as the hardware will give — without erasing them, so switching back " +
                "returns your tuned profile intact.\n\n" +
                "Recording fills the local file, which is what analysis is run against. Publishing " +
                "feeds the bus, which is for watching a trial as it happens; it can never be faster " +
                "than the recording, because there is no sample to send between recordings.\n\n" +
                "Neither turns anything on. A subject that is switched off stays off.\n\n" +
                "Measured on this phone: recording at maximum writes about " +
                "$MAX_MEGABYTES_PER_HOUR MB/h against roughly $CONFIGURED_MEGABYTES_PER_HOUR MB/h " +
                "configured, and drives the battery correspondingly harder.",
            onDismiss = { showHelp = false },
        )
    }

    SectionHeader("Sampling rates", onInfo = { showHelp = true })
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RateModeRow(
                label = "Recording",
                atMax = settings.recordAllMax,
                onChange = onSetRecordAllMax,
                // The consequence, not the adjective: a measured figure is worth more than "larger".
                detail = if (settings.recordAllMax) {
                    "Every sample the sensors give · ~$MAX_MEGABYTES_PER_HOUR MB/h"
                } else {
                    "Each subject's own rate · ~$CONFIGURED_MEGABYTES_PER_HOUR MB/h"
                },
            )
            HorizontalDivider()
            RateModeRow(
                label = "Publishing",
                atMax = settings.publishAllMax,
                onChange = onSetPublishAllMax,
                detail = if (settings.publishAllMax) {
                    "Unthinned to the bus · more link and battery use"
                } else {
                    "Each subject's own rate, thinned from the recording"
                },
            )
            Text(
                "Changing either restarts the run and starts a new file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A label, the two rates it can run at, and what choosing this one costs. */
@Composable
private fun RateModeRow(
    label: String,
    atMax: Boolean,
    onChange: (Boolean) -> Unit,
    detail: String,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        FilterChip(
            selected = !atMax,
            onClick = { onChange(false) },
            label = { Text("Configured") },
        )
        Spacer(Modifier.width(6.dp))
        FilterChip(
            selected = atMax,
            onClick = { onChange(true) },
            label = { Text("Maximum") },
        )
    }
    Text(
        detail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * What to do next, and nothing else.
 *
 * This used to be six full-width buttons that made the start screen a menu as much as a dashboard.
 * Settings, Platforms, Recordings and Checklists now live on the Setup tab, one tap away in the bar, which
 * leaves exactly one filled button in the resting state.
 *
 * Drawn as a `Surface` for the same reason [se.rise.logline.ui.components.FormActions] is: it is
 * pinned above the navigation bar rather than scrolling with the page, and the tonal step is what
 * separates it from the rows passing underneath.
 */
@Composable
private fun Actions(
    running: Boolean,
    /**
     * Whether the *next* run will record, i.e. `Settings.recordingEnabled` — not [recording], which is
     * false until a file is open and therefore always false on the button this labels.
     */
    willRecord: Boolean,
    /** Whether the *next* run will publish, i.e. `Settings.publishEnabled`. */
    willPublish: Boolean,
    onWillPublishChange: (Boolean) -> Unit,
    onWillRecordChange: (Boolean) -> Unit,
    /**
     * Whether a file is actually being written — **not** the same question as [running].
     *
     * A run publishes to the bus whether or not recording is switched on, so "it is going" and "it is
     * being kept" are two facts and only one of them is recoverable afterwards. This is the one that
     * decides whether there is anything to take off the phone.
     */
    recording: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!running) {
                // **The button says START and the chips say what of.** It used to carry both facts in
                // its label — `START Publish & REC` — which made the label the only place the choice
                // was visible and the *settings screen* the only place it could be changed. Two chips
                // beside a shorter button state the same thing and are the control as well.
                //
                // Both on is the default, and the pair is not exclusive: publish and record are
                // genuinely independent — a trial with no router still wants the file, and a quick
                // look at the bus does not need one.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Chips first, then the button: they qualify it, and English reads left to right,
                    // so `PUB REC ▶ START` is the sentence and `START PUB REC` is the same words in the
                    // wrong order.
                    ModeChip("PUB", willPublish, onWillPublishChange)
                    ModeChip("REC", willRecord, onWillRecordChange)
                    Button(
                        onClick = onStart,
                        // Neither chip on means a run that would do nothing at all: no file, nothing
                        // on the wire, and a foreground service holding a wake lock to achieve it.
                        // Disabled rather than hidden, so the reason is a glance away rather than a
                        // control that vanished.
                        enabled = willPublish || willRecord,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            // The word beside it says what this does; naming the glyph too would only
                            // repeat it to a screen reader.
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("START")
                    }
                }
                if (!willPublish && !willRecord) {
                    // State, not documentation: the only thing saying why the button will not press.
                    Text(
                        "Switch on PUB, REC or both — a run with neither does nothing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Just Stop. Three things have been removed from beside it, all for one reason.
                //
                // Live view and Mark event were duplicated out of the navigation bar on the theory
                // that a passing moment should not need looking for — but the bar is on screen at all
                // times and carries both, so the copies bought nothing and cost the one control a tab
                // cannot offer its prominence.
                //
                // A blinking `REC` lamp sat here too, and for a while it had to: the app bar used
                // `enterAlwaysScrollBehavior` and left the screen on the first downward scroll, which
                // on a page of thirty-nine subject rows meant the answer to "is it still recording"
                // could be scrolled away. The bar is pinned now and carries `PUB` and `REC` on every
                // screen, so this said the same thing a second time, three centimetres lower.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // **Filled, not outlined**, and it is the one place in the app where a large block
                    // of red is right. The colour rule here is that red means something is wrong — but
                    // that rule is about *readouts*, where a lamp has to be trusted at a glance. This is
                    // an action, and it is the destructive half of a pair: Start is a filled primary
                    // button, so an outlined Stop read as the lesser of the two when it is the one that
                    // ends a run and closes the file.
                    //
                    // `error` over `onError` rather than a hand-picked pair, because those two tokens
                    // are *defined* as a legible combination in both themes — on this phone that is a
                    // light red field with near-black-red text on it, which is what was asked for, and
                    // in a light theme it flips to a strong red field with white text rather than
                    // becoming two dark reds nobody can read.
                    Button(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        // A filled square, drawn rather than imported: `material-icons-core` has no
                        // stop glyph, and pulling in `material-icons-extended` for one shape would add
                        // tens of megabytes of vectors to the APK.
                        //
                        // Takes the button's content colour rather than naming one, so the glyph and
                        // the word beside it cannot end up different reds.
                        Box(
                            Modifier
                                .size(ButtonDefaults.IconSize * 0.6f)
                                .clip(RoundedCornerShape(2.dp))
                                .background(LocalContentColor.current)
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("Stop")
                    }
                }
            }
        }
    }
}

/**
 * One of the two things a run can do, as a chip that is also the switch for it.
 *
 * `FilterChip` rather than a `Switch`: these sit in a row beside a button, and what is being chosen is
 * *which of two independent things this run does* rather than a setting being turned up or down. The
 * selected fill is the state and the word is the label, which is the same shape the sampling-rate
 * selector on this screen uses.
 *
 * They read the same `PUB` and `REC` the top bar's lamps do, deliberately: the bar says what a run **is**
 * doing and these say what the next one **will**, and using two vocabularies for that would be two
 * things to learn.
 */
@Composable
private fun ModeChip(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = on,
        onClick = { onChange(!on) },
        label = { Text(label) },
        // **The button's own colours, not the chip default.** These are not a setting sitting near a
        // button; they are two thirds of what pressing it will do, and `PUB REC ▶ START` reads as one
        // control when the three share a fill and as a control beside two unrelated ones when they do
        // not. That is why this deviates from the `Configured | Maximum` selectors further up the
        // screen, which are a genuine standalone setting and keep the chip default.
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
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
    return formatElapsed(nowMillis - startedAtMillis)
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
    /** The fastest this source can produce, and where that number came from. See `rateCeilings()`. */
    ceiling: RateCeiling?,
    /** What the *wire* was asked for — `Settings.publishRate()`, which resolves a `rateOwner`. */
    requested: SensorRate,
    /**
     * What the *file* was asked for, when it differs.
     *
     * Null when the two agree, so an untuned subject still reads as one rate rather than repeating
     * itself — the row only mentions two when there are two.
     */
    recording: SensorRate? = null,
    /** The subject holding this one's publish rate down, when one is. See [rateLine]. */
    cappedBy: String? = null,
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
    // What this source was asked for, on every row whatever state it is in — including the ones that
    // are not producing, which is when somebody is deciding what to ask for. Null only for an
    // event-driven subject, which has no rate to state.
    val setLabel = if (entry.eventDriven) null else requestedLabel(requested)
    fun withSet(text: String) = setLabel?.let { "$text · $it" } ?: text

    val detail = when (health) {
        SubjectHealth.Unavailable -> withSet("Not on this device")
        // The switch is in this row, so the old "turn it on in Settings" is no longer where to go —
        // except for the two that need a restart to take effect, which is worth saying up front.
        SubjectHealth.Off ->
            withSet(if (entry in START_TIME_SUBJECTS) "Off — switching it on restarts the run" else "Off")
        SubjectHealth.Failed -> withSet("Failed — ${status.failure}")
        // Both of these have no achieved rate to state, so the ceiling stands in — which is the one
        // moment it is worth reading, since deciding what to ask a source for happens before a run and
        // not during one. Once there is a sample count from the last run, that is the better fact and
        // the line is long enough without both.
        SubjectHealth.Waiting -> "Waiting · ${rateLine(null, requested, ceiling, recording, cappedBy)}"
        SubjectHealth.Idle ->
            if (status.samplesPublished == 0L) {
                rateLine(null, requested, ceiling, recording, cappedBy).replaceFirstChar { it.uppercase() }
            } else {
                // `formatCount` rather than `formatCounted`: dropping the word "samples" is what keeps
                // the commonest of these lines on one row once the rate is appended.
                withSet("${formatCount(status.samplesPublished)} last run")
            }
        SubjectHealth.Stalled ->
            withSet("Stalled — last sample ${formatAge(status.lastPublishEpochMillis, nowMillis)}")
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
        } else {
            // The rate, and what the hardware could do. A live row's age is "just now" by definition —
            // that is what `Live` means, since anything older than five of its own intervals is
            // `Stalled` and says so in red with the age attached. Printing it beside the rate spent the
            // line on a word that never changed, on every row, for the whole of a run.
            //
            // The ceiling is only shown where the platform will state one: `SensorManager` reports a
            // `minDelay` per sensor, and nothing else here does — the fused location provider publishes
            // no rate limit, the battery and radio subjects are polled rather than sampled, and the IMU
            // temperature is found by string type with no `sensorType` to ask about. Those rows print
            // the achieved rate alone rather than an invented ceiling.
            // Before the first two samples there is no achieved rate to divide out, so the line is
            // the other two numbers — more use than "Publishing" was on a 0.1 Hz subject.
            rateLine(hz, requested, ceiling, recording, cappedBy).replaceFirstChar { it.uppercase() }
                .ifBlank { "Publishing" }
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
                // One node, so a screen reader says it as a sentence rather than four fragments. The
                // newline in a stacked position becomes a separator: read aloud it is a pause in the
                // middle of a coordinate pair, where the sentence wants the two halves joined.
                .readAsOneItem(
                    "${label.name}. ${reading?.replace("\n", ", ") ?: "no reading"} " +
                        "${label.unit.orEmpty()}. $detail",
                ),
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
    // Any entry on this subject, not just the fused one — the two unfused solutions each show their
    // own position, which is what makes the three rows comparable at a glance. `CALIBRATION_ZERO`
    // shares the subject and is never recorded live, so it falls through to null on its own.
    //
    // Stacked, latitude over longitude: the reading column has no weight, so a one-line position took
    // the width the rate line needed and this was the only row in the list that wrapped.
    entry.subject == Subjects.LOCATION_FIX ->
        fix?.let { formatPositionStacked(it.latitude, it.longitude) }
    value != null -> formatLiveValue(entry, value)
    else -> null
}

/**
 * The run's figures, in the words that are true of *this* run.
 *
 * **`totalSamplesPublished` is a count of samples that reached [SubjectSink.emit], not of puts.** On a
 * record-only run nothing goes on the wire and the counter means "samples produced" — which is the
 * honest reading, since it is what reached the file — so calling them *published* there stated
 * something the run had deliberately not done. Silence about the verb is better than the wrong one: on
 * such a run the sentence simply reads "95 317 samples over 00:01:58, 2 MB recorded."
 */
internal fun stopFigures(
    samples: Long,
    publishing: Boolean,
    elapsed: String,
    recording: Boolean,
    bytesWritten: Long,
): String = buildString {
    append(formatCounted(samples, "sample"))
    if (publishing) append(" published")
    append(" over ")
    append(elapsed)
    if (recording) {
        append(", ")
        append(formatBytes(bytesWritten))
        append(" recorded")
    }
    append(".")
}

/**
 * Where the closing note will end up.
 *
 * The same correction as [stopFigures], for the same reason: a note is a `log_message` through
 * `SubjectSink.emit`, so on a record-only run it reaches the file and **not** the bus. Promising both
 * was a claim about where somebody's words had gone.
 *
 * "The file keeps its name" only belongs where there is a file — and only there could anybody have
 * expected the note to rename one.
 */
internal fun stopNoteHint(publishing: Boolean, recording: Boolean): String {
    val destinations = listOfNotNull(
        "on the bus".takeIf { publishing },
        "in the recording".takeIf { recording },
    )
    return buildString {
        append("Marked against the run")
        // A comma before a list of two, "and" before a single one — "the run and on the bus and in the
        // recording" is the shape a naive join gives and nobody writes.
        when (destinations.size) {
            0 -> Unit
            1 -> append(" and ${destinations.single()}")
            else -> append(", ${destinations.joinToString(" and ")}")
        }
        append(".")
        if (recording) append(" The file keeps its name.")
    }
}

/**
 * How tall the live camera card is.
 *
 * Shorter than a 16:9 frame at page width: this is a glance at what another camera sees, and a card
 * taller than the sensor groups above it would claim more of the screen than the run it belongs to.
 */
private val CAMERA_HEIGHT = 200.dp

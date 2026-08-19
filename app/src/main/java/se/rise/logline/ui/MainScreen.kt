package se.rise.logline.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import se.rise.logline.ui.components.ConnectionChip
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
 * What the Rigs button says: the active rig, and how many others are also on the bus.
 *
 * Named rather than counted when there is one thing to name — "Rigs · SSRS18" is what somebody is
 * checking for. The `+2` matters because those two are publishing geometry under entity ids that are
 * nowhere else on this screen.
 */
internal fun rigSummaryOf(settings: Settings): String? {
    val publishing = settings.publishingRigs()
    val active = settings.activeRig()
    return when {
        settings.rigs.isEmpty() -> null
        active == null -> "${settings.rigs.size} rigs"
        publishing.size > 1 -> "${active.name} +${publishing.size - 1}"
        else -> active.name
    }
}

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
    maxRatesHz: Map<PublishedSubject, Double> = emptyMap(),
    onStart: () -> Unit,
    onStop: () -> Unit,
    onGrantLocation: () -> Unit,
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

    ScreenScaffold(
        title = "Logline",
        titleIcon = { AppMark() },
        actions = { ConnectionChip(status.running, status.connection) },
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
                    onStart = onStart,
                    onStop = onStop,
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
                                    maxRateHz = maxRatesHz[entry],
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


/**
 * The session summary: what the last run left behind, or what this one is doing.
 *
 * It answers three questions and no more — what happened last time, is there room for another run,
 * and where will it connect — with the endpoint and the file details one tap behind the row along its
 * bottom edge. It deliberately no longer says **"Not publishing"**: the chip in the app bar says
 * `Idle` and the button pinned above the navigation bar says `Start publishing`, so a third statement
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
                if (status.connection == ConnectionState.Disconnected) {
                    StatusLine(
                        text = "Publishing to nothing",
                        tone = StatusTone.Error,
                        detail = "${summary.live}/${summary.total} streams · the router is unreachable, " +
                            "so samples are being dropped.",
                    )
                } else {
                    CardHeadline(
                        text = "Publishing",
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
                        if (span >= 1_000L) append(" · ${duration(span)}")
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

            // The recording's own problems, which are the two things here that earn an icon: samples
            // that never reached the file, and a file that could not be written or copied.
            if (recording.dropped > 0 || recording.error != null) {
                StatusLine(
                    text = if (recording.dropped > 0) {
                        "Recording — ${formatCounted(recording.dropped, "sample")} dropped"
                    } else {
                        "Recording problem"
                    },
                    tone = StatusTone.Error,
                    detail = recording.error
                        ?: "${formatCounted(recording.messagesWritten, "message")} written to " +
                        (recording.fileName ?: "the current file"),
                )
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
                            duration(
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
                    Detail("Ran for", duration(status.publishedSpanMillis))
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
 * What to do next, and nothing else.
 *
 * This used to be six full-width buttons that made the start screen a menu as much as a dashboard.
 * Settings, Rigs, Recordings and Checklists now live on the Setup tab, one tap away in the bar, which
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
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        Icons.Default.PlayArrow,
                        // The word beside it says what this does; naming the glyph too would only
                        // repeat it to a screen reader.
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Start publishing")
                }
            } else {
                // Just Stop. Live view and Mark event used to sit here as well, duplicated out of the
                // navigation bar on the theory that a passing moment should not need looking for — but
                // the bar is on screen at all times and carries both, so the copies bought nothing and
                // cost the one control a tab cannot offer its prominence.
                //
                // REC sits *here* rather than beside the connection chip in the app bar, because that
                // bar uses `enterAlwaysScrollBehavior` and leaves the screen on the first downward
                // scroll — and thirty-nine subject rows is a page people scroll. This surface is
                // pinned, so the answer to "is it still recording" cannot be scrolled away.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (recording) RecordingLamp()
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        // A filled square, drawn rather than imported: `material-icons-core` has no
                        // stop glyph, and pulling in `material-icons-extended` for one shape would add
                        // tens of megabytes of vectors to the APK.
                        Box(
                            Modifier
                                .size(ButtonDefaults.IconSize * 0.6f)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.error)
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
 * The camera convention: a red lamp that blinks while something is being written.
 *
 * A steady dot reads as a status light and a blinking one reads as *now*, which is the distinction
 * worth drawing — the status card already carries the elapsed time and the file size for anyone who
 * wants the detail. The text is there as well because colour alone never carries a state in this app.
 *
 * The blink is applied in a `graphicsLayer` block on purpose. Reading the animated value inside that
 * lambda defers it to the draw phase, so the lamp re-draws each frame without recomposing anything —
 * the same instinct that keeps the publish path off the UI thread applies to an animation that runs
 * for the whole of a run.
 */
@Composable
private fun RecordingLamp() {
    val blink by rememberInfiniteTransition(label = "recording").animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "recording-lamp",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.readAsOneItem("Recording"),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .graphicsLayer { alpha = blink }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error)
        )
        Text(
            "REC",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 6.dp),
        )
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
    return duration(nowMillis - startedAtMillis)
}

/**
 * The same clock, for a span that has already finished.
 *
 * Not [formatRuntimeLeft], which rounds to the minute because an estimate off a fuel gauge has no
 * business claiming seconds. This is a measurement, and it should read the way the live clock beside
 * it reads — a run that says `00:12:34` while going should not become "13 min" the moment it stops.
 */
internal fun duration(millis: Long): String {
    val seconds = (millis / 1000L).coerceAtLeast(0L)
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
    /** What the hardware itself can do, where it can be asked. Null where it cannot — see the caller. */
    maxRateHz: Double?,
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
            hz?.let { achieved ->
                val ceiling = maxRateHz?.let { " · max ${formatRate(it)}" }.orEmpty()
                "${formatRate(achieved)} Hz$ceiling"
            } ?: "Publishing"
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

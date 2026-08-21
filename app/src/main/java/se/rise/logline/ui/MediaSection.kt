package se.rise.logline.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.rise.logline.config.Settings
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.Subjects
import se.rise.logline.sensors.toIntervalMillis
import se.rise.logline.ui.components.InfoDialog
import se.rise.logline.ui.components.SectionHeader

/**
 * Whether this run records the microphone, a time-lapse or video.
 *
 * **A per-run decision, so it belongs on the run screen.** It used to live two screens away under
 * Settings → Sensors & media, and the three switches existed *twice*: `toggleSubject()` already mapped
 * `AUDIO`, `IMAGE_COMPRESSED` and `VIDEO_COMPRESSED` onto these same flags, so they were also rows
 * buried in the Session page's Device group among nine other subjects. Two places, one truth. The
 * media subjects are now filtered out of that group — see `START_TIME_SUBJECTS` in `MainScreen` — and
 * this is the one place they are switched.
 *
 * **Every change here restarts the run**, unlike the per-subject switches, and that is not a choice:
 * the foreground-service type and its runtime permission are fixed at `startForeground`, which is what
 * `START_TIME_SUBJECTS` documents. Said once at the top rather than on each control.
 *
 * The figures are kept from where this came from, and are the reason the switches are legible at all:
 * audio is uncompressed WAV and costs more per hour than every other subject combined.
 */
@Composable
fun MediaSection(
    settings: Settings,
    /** What this device's microphone actually offers — greyed rates are ones it does not. */
    supportedAudioRates: Set<Int>,
    unavailableSubjects: Set<PublishedSubject>,
    /** Applied immediately, through `saveSettings` — hence the restart. */
    onChange: (Settings) -> Unit,
    /** The subject's own rate and QoS page: the time-lapse interval and the video frame rate. */
    onOpenSubjectQos: (PublishedSubject) -> Unit,
) {
    var showHelp by rememberSaveable { mutableStateOf(false) }
    // The time-lapse cost depends on how often a frame is taken, which is the subject's rate and not a
    // setting here — the same number its own page sets.
    val frameHz = 1_000.0 / settings.recordRate(Subjects.IMAGE_COMPRESSED)
        .toIntervalMillis()
        .coerceIn(MIN_FRAME_INTERVAL_MILLIS, MAX_FRAME_INTERVAL_MILLIS)

    if (showHelp) {
        InfoDialog(
            title = "Audio & video",
            body = "Three things a run can record besides its sensors, all off unless you turn them " +
                "on.\n\n" +
                "Resolution is a request, like every other rate in this app: the camera picks the size " +
                "it supports closest to the one asked for.\n\n" +
                "The time-lapse interval and the video frame rate are not set here. Each is that " +
                "subject's own rate, on its page behind the arrow.\n\n" +
                "Video replaces the time-lapse rather than joining it: the camera will not serve both " +
                "at once. Measured on a Pixel 6, asking it to killed the camera process within a " +
                "second, at matching resolutions as well as mismatched ones.",
            onDismiss = { showHelp = false },
        )
    }

    SectionHeader("Audio & video", onInfo = { showHelp = true })
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Changing any of these restarts the run and starts a new file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()

            SettingSwitch(
                title = "Record audio",
                description = "Captures the microphone continuously while a run is going and publishes " +
                    "it on the audio subject. It records every conversation held near the phone — " +
                    "Android shows its microphone indicator throughout, and this is off unless you " +
                    "turn it on.",
                checked = settings.audioEnabled,
                onCheckedChange = { onChange(settings.copy(audioEnabled = it)) },
            )
            if (settings.audioEnabled) {
                Text(
                    "Uncompressed WAV, because keelson's audio message allows only MP3 or WAV and " +
                        "Android cannot encode MP3. Roughly " +
                        "${audioMegabytesPerHour(settings.audioSampleRateHz, settings.audioChannels)} " +
                        "MB per hour, against about 77 MB per hour for every other subject combined.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Settings.AUDIO_SAMPLE_RATES.forEach { rate ->
                        FilterChip(
                            selected = rate == settings.audioSampleRateHz,
                            enabled = rate in supportedAudioRates,
                            onClick = { onChange(settings.copy(audioSampleRateHz = rate)) },
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
                            selected = count == settings.audioChannels,
                            onClick = { onChange(settings.copy(audioChannels = count)) },
                            label = { Text(name) },
                        )
                    }
                }
                SubjectLink("Audio rate & QoS") { onOpenSubjectQos(PublishedSubject.AUDIO) }
            }

            HorizontalDivider()
            MediaSwitch(
                title = "Record a time-lapse",
                description = "Takes one picture at the image_compressed rate for the whole run and " +
                    "publishes it as a JPEG. It photographs whatever is in front of the phone — " +
                    "Android shows its camera indicator throughout, and this is off unless you turn " +
                    "it on.",
                checked = settings.cameraEnabled && !settings.videoEnabled,
                unavailable = PublishedSubject.IMAGE_COMPRESSED in unavailableSubjects,
                // One camera consumer at a time — see the video switch below.
                onCheckedChange = {
                    onChange(settings.copy(cameraEnabled = it, videoEnabled = if (it) false else settings.videoEnabled))
                },
            )
            if (settings.cameraEnabled) {
                Text(
                    "Roughly ${cameraMegabytesPerHour(settings.cameraWidth, settings.cameraHeight, frameHz)} " +
                        "MB per hour at ${formatFrameRate(frameHz)}, against about 77 MB per hour for " +
                        "every other subject combined. The interval is the image_compressed rate, " +
                        "behind the arrow below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Settings.CAMERA_RESOLUTIONS.forEach { (width, height) ->
                        FilterChip(
                            selected = width == settings.cameraWidth && height == settings.cameraHeight,
                            onClick = { onChange(settings.copy(cameraWidth = width, cameraHeight = height)) },
                            label = { Text("${width}x$height") },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(false to "Rear", true to "Front").forEach { (front, name) ->
                        FilterChip(
                            selected = front == settings.cameraLensFront,
                            onClick = { onChange(settings.copy(cameraLensFront = front)) },
                            label = { Text(name) },
                        )
                    }
                }
                SubjectLink("Time-lapse interval & QoS") {
                    onOpenSubjectQos(PublishedSubject.IMAGE_COMPRESSED)
                }
            }

            HorizontalDivider()
            MediaSwitch(
                title = "Record video",
                description = "Publishes continuous H.264 on video_compressed. Replaces the " +
                    "time-lapse rather than joining it — the camera will not serve both at once — and " +
                    "is off by default for the same reason: this one records everything the lens " +
                    "sees, not a frame every few seconds.",
                checked = settings.videoEnabled,
                unavailable = PublishedSubject.VIDEO_COMPRESSED in unavailableSubjects,
                onCheckedChange = {
                    onChange(settings.copy(videoEnabled = it, cameraEnabled = if (it) false else settings.cameraEnabled))
                },
            )
            if (settings.videoEnabled) {
                Text(
                    "About ${videoMegabytesPerHour(settings.videoBitrateKbps)} MB per hour — the bitrate " +
                        "is what the encoder is told to produce, so unlike the time-lapse figure above " +
                        "this is not an estimate. At the default it costs less per hour than the " +
                        "time-lapse and carries twenty times the frames; at 2 Mbps it is five times the " +
                        "cost and turns ten days of recording into under two.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Settings.VIDEO_RESOLUTIONS.forEach { (width, height) ->
                        FilterChip(
                            selected = width == settings.videoWidth && height == settings.videoHeight,
                            onClick = { onChange(settings.copy(videoWidth = width, videoHeight = height)) },
                            label = { Text("${width}x$height") },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Settings.VIDEO_BITRATES_KBPS.forEach { kbps ->
                        FilterChip(
                            selected = kbps == settings.videoBitrateKbps,
                            onClick = { onChange(settings.copy(videoBitrateKbps = kbps)) },
                            label = { Text(if (kbps >= 1_000) "${kbps / 1_000} Mbps" else "$kbps kbps") },
                        )
                    }
                }
                SubjectLink("Video frame rate & QoS") {
                    onOpenSubjectQos(PublishedSubject.VIDEO_COMPRESSED)
                }
            }
        }
    }
}

/**
 * A switch that says when the hardware is not there.
 *
 * The Device rows used to carry this; taking the media subjects out of that group would otherwise leave
 * a switch that can be pressed and does nothing, which is worse than one that is plainly disabled.
 */
@Composable
private fun MediaSwitch(
    title: String,
    description: String,
    checked: Boolean,
    unavailable: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingSwitch(
        title = title,
        description = if (unavailable) "Not on this device. $description" else description,
        checked = checked && !unavailable,
        enabled = !unavailable,
        onCheckedChange = onCheckedChange,
    )
}

/** The way through to a subject's own rate and QoS, which is where the interval and frame rate live. */
@Composable
private fun SubjectLink(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label) }
}

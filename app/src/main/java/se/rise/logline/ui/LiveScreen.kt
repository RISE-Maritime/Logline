package se.rise.logline.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.publish.FramePreview
import se.rise.logline.publish.LiveSnapshot
import se.rise.logline.publish.PublisherStatus
import se.rise.logline.publish.SampleWindow
import se.rise.logline.record.RecordingStatus
import se.rise.logline.sensors.achievedHz
import se.rise.logline.ui.components.InfoDialog
import se.rise.logline.ui.components.ScreenScaffold
import se.rise.logline.ui.components.SectionHeader
import se.rise.logline.ui.components.StatusLine
import se.rise.logline.ui.components.StatusTone
import se.rise.logline.ui.components.readAsOneItem

/** The spans the window control offers. Bounded by what the store holds — see [LiveScreen]. */
val WINDOW_CHOICES = listOf(30 to "30 s", 120 to "2 min", 600 to "10 min")

/**
 * What is actually going on the bus, arranged to answer four questions in order: where am I, is the
 * data healthy, what is it doing now, is anything abnormal.
 *
 * The first screenful is the dashboard — map, position, the three readings, health per group. Below it
 * the plots, folded into the same sections the main screen uses, so a subject is in the same place on
 * both. Documentation that used to sit in the middle of the flow is behind the ⓘ buttons.
 *
 * Takes a plain [LiveSnapshot] and lambdas like every other screen, and the snapshot is *pulled* on a
 * ticker rather than pushed — the publish path runs at hundreds of samples a second and must never
 * drive recomposition.
 */
@Composable
fun LiveScreen(
    snapshot: LiveSnapshot,
    status: PublisherStatus,
    recording: RecordingStatus,
    running: Boolean,
    unavailableSubjects: Set<PublishedSubject>,
    disabledSubjects: Set<PublishedSubject>,
    followFix: Boolean,
    onFollowFixChange: (Boolean) -> Unit,
    windowSeconds: Int,
    onWindowSecondsChange: (Int) -> Unit,
    paused: Boolean,
    onPausedChange: (Boolean) -> Unit,
    collapsedGroups: List<String>,
    onToggleGroup: (String) -> Unit,
    mapView: @Composable (Modifier) -> Unit,
    onBack: () -> Unit,
) {
    // Drives the window slice and the health checks. The snapshot itself arrives on its own ticker.
    val nowMillis by produceState(System.currentTimeMillis(), paused) {
        while (!paused) {
            value = System.currentTimeMillis()
            delay(500)
        }
    }
    var showAbout by remember { mutableStateOf(false) }
    var showAxes by remember { mutableStateOf(false) }

    val fix = snapshot.lastFix
    val latest: (PublishedSubject) -> Float? = { snapshot[it].latest }
    val rateOf: (PublishedSubject) -> Double? = { entry ->
        achievedHz(
            samples = status[entry].samplesPublished,
            firstEpochMillis = status[entry].firstPublishEpochMillis,
            lastEpochMillis = status[entry].lastPublishEpochMillis,
        )
    }
    val heading = latest(PublishedSubject.HEADING_TRUE_NORTH) ?: latest(PublishedSubject.HEADING_MAGNETIC)
    val headingIsTrue = latest(PublishedSubject.HEADING_TRUE_NORTH) != null

    if (showAbout) {
        InfoDialog(
            title = "About this view",
            body = "Everything here is what actually went on the bus, not a separate read of the " +
                "sensors — so an empty plot means nothing was published.\n\n" +
                "Vector subjects are drawn as magnitude: three overlaid axes are unreadable at this " +
                "size, and magnitude is what answers \"is this sane\".\n\n" +
                "The window shows the last 30 seconds, 2 minutes or 10 minutes. It is limited by what " +
                "is held in memory — about 8000 samples per subject, which is roughly 2.5 minutes for " +
                "the 50 Hz IMU subjects and hours for the slow ones.",
            onDismiss = { showAbout = false },
        )
    }
    if (showAxes) {
        InfoDialog(
            title = "IMU axes",
            body = null,
            onDismiss = { showAxes = false },
            content = { AxisReferenceCard() },
        )
    }

    ScreenScaffold(
        title = "Live view",
        onBack = onBack,
        actions = {
            LiveChip(running, recording.recording)
            IconButton(onClick = { showAbout = true }) {
                Icon(Icons.Default.Info, contentDescription = "About the live view")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // -- the dashboard: everything above the fold ----------------------------------------
            Box(Modifier.fillMaxWidth().height(240.dp)) {
                mapView(Modifier.fillMaxSize())
                FollowControl(
                    followFix = followFix,
                    onFollowFixChange = onFollowFixChange,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
            }
            FixLine(fix)
            DashboardReadings(
                speedKnots = latest(PublishedSubject.SPEED_OVER_GROUND),
                courseDegrees = latest(PublishedSubject.COURSE_OVER_GROUND),
                headingDegrees = heading,
                headingIsTrue = headingIsTrue,
            )
            HealthChips(
                subjectGroups().map { group ->
                    HealthChip(
                        name = chipName(group.title),
                        healthy = !groupSummary(
                            entries = group.entries,
                            status = status,
                            running = running,
                            nowMillis = nowMillis,
                            unavailable = unavailableSubjects,
                            disabled = disabledSubjects,
                        ).needsAttention,
                    )
                }
            )

            HorizontalDivider(Modifier.padding(top = 4.dp))
            WindowControl(windowSeconds, onWindowSecondsChange, paused, onPausedChange)

            if (!running && snapshot.track.isEmpty()) {
                StatusLine(
                    text = "Nothing published yet",
                    tone = StatusTone.Neutral,
                    detail = "Start a run from the main screen — this view shows what went on the bus.",
                )
            }

            // -- the plots -----------------------------------------------------------------------
            subjectGroups().forEach { group ->
                val summary = groupSummary(
                    entries = group.entries,
                    status = status,
                    running = running,
                    nowMillis = nowMillis,
                    unavailable = unavailableSubjects,
                    disabled = disabledSubjects,
                )
                val collapsed = group.title in collapsedGroups
                SectionHeader(
                    title = group.title,
                    trailing = featuredValue(group, latest, rateOf, fix?.accuracyMetres)
                        ?: "${summary.live}/${summary.total}",
                    trailingColor = if (summary.needsAttention) MaterialTheme.colorScheme.error else null,
                    expanded = !collapsed,
                    onToggle = { onToggleGroup(group.title) },
                    onInfo = if (group.title == "IMU") ({ showAxes = true }) else null,
                )
                if (!collapsed) {
                    group.entries.forEach { entry ->
                        val window = windowedTo(snapshot[entry], windowSeconds, nowMillis)
                        if (!window.isEmpty) {
                            SparklineCard(
                                entry = entry,
                                window = window,
                                // Only the camera row gets a picture, and only the newest one — the
                                // store keeps a single frame, not a history.
                                frame = snapshot.frame
                                    .takeIf { entry == PublishedSubject.IMAGE_COMPRESSED },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** ● LIVE while publishing, ● REC beside it while recording. */
@Composable
private fun LiveChip(running: Boolean, recording: Boolean) {
    val text = when {
        running && recording -> "LIVE · REC"
        running -> "LIVE"
        else -> "IDLE"
    }
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.readAsOneItem(if (running) "Publishing $text" else "Not publishing"),
    )
}

/** The map's own control, so the three-line explanation can live in the ⓘ instead. */
@Composable
private fun FollowControl(
    followFix: Boolean,
    onFollowFixChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        FilterChip(
            selected = followFix,
            onClick = { onFollowFixChange(!followFix) },
            label = { Text(if (followFix) "◎ Following" else "◎ Follow") },
        )
    }
}

/**
 * How much history to plot, and whether to stop the clock.
 *
 * Pause is the one that earns its place during a test: something odd goes past, and without it the
 * evidence has scrolled off the window before anyone can look at it.
 */
@Composable
private fun WindowControl(
    windowSeconds: Int,
    onWindowSecondsChange: (Int) -> Unit,
    paused: Boolean,
    onPausedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WINDOW_CHOICES.forEach { (seconds, label) ->
            FilterChip(
                selected = seconds == windowSeconds,
                onClick = { onWindowSecondsChange(seconds) },
                label = { Text(label) },
            )
        }
        Box(Modifier.weight(1f))
        FilterChip(
            selected = paused,
            onClick = { onPausedChange(!paused) },
            label = { Text(if (paused) "Paused" else "Pause") },
        )
    }
}

/**
 * One subject: its name, its reading, its shape over the window.
 *
 * A third shorter than the card it replaces — the footer used to spend its line on a sample count,
 * which tells an operator nothing that the rate and the range do not tell them better.
 */
@Composable
private fun SparklineCard(
    entry: PublishedSubject,
    window: SampleWindow,
    /** The newest camera frame, for the one subject that has one. Null everywhere else. */
    frame: FramePreview? = null,
) {
    val label = labelOf(entry)
    val lineColor = MaterialTheme.colorScheme.primary
    // Unwrapped before anything else touches it: a heading crossing north is a two-degree step, and
    // decimating first would alias a fast turn beyond recovery.
    val plotted = if (isCircularDegrees(entry)) unwrapAngles(window.values) else window.values
    // The true range, for the footer. What gets drawn uses `plotBounds`, which refuses to magnify a
    // sensor's last significant figure into a full-height wobble.
    val bounds = boundsOf(plotted)
    val rate = windowRateHz(window)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(label.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    window.latest?.let { formatLiveValue(entry, it) } ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                )
                label.unit?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = unitGap(it), bottom = 2.dp),
                    )
                }
            }

            // Decoded against the bytes, not on every 5 Hz pull: the thumbnail only changes when a new
            // frame lands. A number cannot say the lens is covered or the exposure has gone black,
            // which is the whole reason the picture is here.
            val preview = remember(frame?.jpeg) {
                frame?.let { BitmapFactory.decodeByteArray(it.jpeg, 0, it.jpeg.size)?.asImageBitmap() }
            }
            preview?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Latest camera frame",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
            }

            if (window.isPlottable() && bounds != null) {
                val axis = plotBounds(bounds)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .padding(top = 4.dp)
                        .semantics {
                            contentDescription = "${label.name} trend over the window, " +
                                "between ${formatLiveValue(entry, bounds.min)} and " +
                                "${formatLiveValue(entry, bounds.max)}"
                        },
                ) {
                    val bins = envelope(plotted, size.width.toInt().coerceAtLeast(2)) ?: return@Canvas
                    val stepX = size.width / (bins.size - 1).coerceAtLeast(1)
                    // normalise() is bottom-up; screen y grows downward, hence the flip.
                    fun y(v: Float) = size.height * (1f - normalise(v, axis))

                    // The band: every sample in the window, so a spike is visible even where hundreds
                    // of samples share a pixel column.
                    val band = Path().apply {
                        moveTo(0f, y(bins.maxs[0]))
                        for (i in 1 until bins.size) lineTo(i * stepX, y(bins.maxs[i]))
                        for (i in bins.size - 1 downTo 0) lineTo(i * stepX, y(bins.mins[i]))
                        close()
                    }
                    drawPath(band, color = lineColor.copy(alpha = 0.35f))

                    // The running average through it, which is what makes a trend readable at 55 Hz.
                    val trend = Path().apply {
                        moveTo(0f, y(bins.means[0]))
                        for (i in 1 until bins.size) lineTo(i * stepX, y(bins.means[i]))
                    }
                    drawPath(trend, color = lineColor, style = Stroke(width = 2f))
                }
                Text(
                    buildString {
                        // The range of what is drawn: for a circular subject that is the unwrapped
                        // series, so a turn through north reads as 350–370 rather than 0–360.
                        append("${formatLiveValue(entry, bounds.min)} – ${formatLiveValue(entry, bounds.max)}")
                        rate?.let { append(" · ${formatRate(it)} Hz") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "one sample so far",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Degrees, percent and SI prefixes sit tight against the number; word units take a space. */
private fun unitGap(unit: String) = if (unit == "°" || unit == "%" || unit == "bit/s") 0.dp else 3.dp

package se.rise.logline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.publish.SampleWindow
import se.rise.logline.publish.TextHistory
import se.rise.logline.ui.components.EmptyState
import se.rise.logline.ui.components.ScreenScaffold
import se.rise.logline.ui.components.SectionHeader
import se.rise.logline.ui.components.readAsOneItem
import se.rise.logline.ui.theme.signalGreen

/** The spans the detail view offers, plus "all" — see [DETAIL_ALL]. */
private val DETAIL_WINDOWS = WINDOW_CHOICES + (Int.MAX_VALUE to "All")

/** The sentinel span meaning "everything the ring holds", which is what the store already bounds. */
private const val DETAIL_ALL = Int.MAX_VALUE

/**
 * One subject, at the size the Live tab cannot give it.
 *
 * The Live tab draws thirty-nine 34dp sparklines, which is the right size for "is anything obviously
 * wrong" and the wrong size for looking at anything. This is where a trace gets a full canvas, an axis
 * and clock times — and, for two kinds of subject, a presentation that is not a trace at all.
 *
 * **Which presentation is [detailKind]'s answer, not this screen's.** The card decides whether to draw a
 * sparkline from the same function, so a subject cannot be plotted in one place and tabulated in the
 * other.
 *
 * Takes data and lambdas like every other screen. The window and the text are pulled by the caller on a
 * ticker — the publish path runs at hundreds of samples a second and must never drive recomposition.
 */
@Composable
fun SubjectDetailScreen(
    entry: PublishedSubject,
    window: SampleWindow,
    /** Empty for every subject except the text ones. See `LiveSampleStore.TEXT_SUBJECTS`. */
    text: TextHistory,
    /** Drives the window slice, on the caller's ticker so the slice is a function of its inputs. */
    nowMillis: Long,
    running: Boolean,
    onBack: () -> Unit,
) {
    val label = labelOf(entry)
    val kind = detailKind(entry)
    // Carried between visits like the Live tab's own window: opening a subject, looking, going back and
    // opening another is a thing done in sequence, and re-choosing the span each time is friction.
    var seconds by rememberSaveable { mutableStateOf(DETAIL_WINDOWS[1].first) }

    val slice = remember(window, seconds, nowMillis) {
        if (seconds == DETAIL_ALL) window else windowedTo(window, seconds, nowMillis)
    }

    ScreenScaffold(title = label.name, onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DetailHeader(entry, label, window)

            // The text log has no time window to choose: it is the ring, all of it, and the ring's
            // depth is stated instead. Offering 30 s over a log that only holds 26 would be a control
            // that could not do what it said.
            if (kind != DetailKind.Text) {
                WindowChips(seconds) { seconds = it }
            }

            when (kind) {
                DetailKind.Plot -> DetailPlot(entry, slice)
                DetailKind.Timeline -> DetailTimeline(entry, slice, nowMillis)
                DetailKind.Text -> DetailText(text)
            }

            if (!running && window.isEmpty) {
                EmptyState(
                    title = "Nothing published yet",
                    body = "Start a run — this view shows what went on the bus, not a separate " +
                        "read of the sensor.",
                )
            }
        }
    }
}

@Composable
private fun DetailHeader(entry: PublishedSubject, label: SubjectLabel, window: SampleWindow) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(
            entry.subject,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            window.latest?.let { formatLiveValue(entry, it) } ?: "—",
            style = MaterialTheme.typography.headlineSmall,
        )
        label.unit?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = unitGap(it), bottom = 3.dp),
            )
        }
    }
}

@Composable
private fun WindowChips(seconds: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DETAIL_WINDOWS.forEach { (value, name) ->
            FilterChip(
                selected = seconds == value,
                onClick = { onChange(value) },
                label = { Text(name) },
            )
        }
    }
}

/** Tall enough to read a trend off, against the 34dp the card gives it. */
private val PLOT_HEIGHT = 220.dp

/**
 * The trace, with the furniture the sparkline has no room for.
 *
 * Every piece of the maths is the card's — `unwrapAngles`, `boundsOf`, `plotBounds`, `envelope`,
 * `normalise` — so the two cannot disagree about what the data looks like. What is different here is
 * only size and labelling.
 */
@Composable
private fun DetailPlot(entry: PublishedSubject, window: SampleWindow) {
    // Unwrapped before anything else touches it: a heading crossing north is a two-degree step, and
    // binning first would alias a fast turn beyond recovery.
    val plotted = if (isCircularDegrees(entry)) unwrapAngles(window.values) else window.values
    val bounds = boundsOf(plotted)
    if (!window.isPlottable() || bounds == null) {
        EmptyState(
            title = "Not enough samples yet",
            body = "A trend needs at least two, and this window holds ${window.size}.",
        )
        return
    }
    // **The data's own range, and deliberately not `plotBounds`.**
    //
    // The sparkline draws against a floored axis because it has none: at 34dp with no numbers on it,
    // a full-height wobble is indistinguishable from a real swing, so a barometer varying 0.002% of
    // its value has to render as the flat line it effectively is. That ambiguity is what the floor
    // exists to remove, and it does not exist here — the axis is labelled, so a reader can see the
    // span is five pascals and judge it themselves.
    //
    // Keeping the floor here would defeat the screen: air pressure drawn against a 1000 Pa axis is a
    // straight line on a 220dp canvas whose own footer says the range was 5 Pa. `boundsOf` already
    // pads a genuinely constant series, so a degenerate axis is not a case this has to handle.
    val axis = bounds
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            // **The gutter sizes to its labels, and the time axis lives inside the plot column.**
            //
            // A fixed gutter has to be wide enough for the longest value any subject can print, so on
            // every subject that prints a short one it is mostly empty — a band of dead card between
            // the edge and the number. `IntrinsicSize.Min` makes it exactly as wide as the widest of
            // the three labels and no wider.
            //
            // That forces the shape below: the start and end times cannot sit in a sibling row with a
            // hardcoded leading pad, because there is no longer a constant to hardcode. Putting them in
            // the same column as the canvas is what keeps them aligned to it by construction.
            // No height modifier: the row wraps its tallest child, which is the plot column. Asking for
            // `IntrinsicSize.Min` here would force an extra measurement pass over a canvas whose height
            // is already fixed, and buy nothing.
            Row(Modifier.fillMaxWidth()) {
                // The value axis, outside the canvas so the labels cannot overlap the trace.
                Column(
                    Modifier
                        .width(IntrinsicSize.Min)
                        .height(PLOT_HEIGHT)
                        .padding(end = 6.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    // **Labelled modulo a turn for a circular subject**, so every number on the axis
                    // is a bearing somebody could steer. The geometry is still the unwrapped series —
                    // it has to be, or a crossing of north draws a cliff — but its *numbers* run away
                    // from the compass: a phone turned twice reached 638, which is not a direction.
                    // Zero-padded to three digits, which is itself the tell that it is a bearing.
                    //
                    // A window spanning more than a full turn can therefore label two gridlines the
                    // same. That is true rather than confusing: the vessel really did pass that
                    // bearing twice, and the row below says how far it turned in total.
                    AxisLabel(axisLabel(entry, axis.max))
                    AxisLabel(axisLabel(entry, (axis.min + axis.max) / 2f))
                    AxisLabel(axisLabel(entry, axis.min))
                }
                Column(Modifier.weight(1f)) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(PLOT_HEIGHT)
                            .readAsOneItem(
                                "${labelOf(entry).name} over the window, between " +
                                    "${formatLiveValue(entry, bounds.min)} and " +
                                    "${formatLiveValue(entry, bounds.max)}, ${window.size} samples"
                            ),
                    ) {
                        fun y(v: Float) = size.height * (1f - normalise(v, axis))
                        listOf(axis.max, (axis.min + axis.max) / 2f, axis.min).forEach {
                            drawLine(grid, androidx.compose.ui.geometry.Offset(0f, y(it)),
                                androidx.compose.ui.geometry.Offset(size.width, y(it)), strokeWidth = 1f)
                        }
                        val bins = envelope(plotted, size.width.toInt().coerceAtLeast(2)) ?: return@Canvas
                        val stepX = size.width / (bins.size - 1).coerceAtLeast(1)
                        // The band is every sample in the window, so a spike survives even where hundreds
                        // share a pixel column; the mean through it is what makes a trend readable at 55 Hz.
                        val band = Path().apply {
                            moveTo(0f, y(bins.maxs[0]))
                            for (i in 1 until bins.size) lineTo(i * stepX, y(bins.maxs[i]))
                            for (i in bins.size - 1 downTo 0) lineTo(i * stepX, y(bins.mins[i]))
                            close()
                        }
                        drawPath(band, color = line.copy(alpha = 0.30f))
                        val trend = Path().apply {
                            moveTo(0f, y(bins.means[0]))
                            for (i in 1 until bins.size) lineTo(i * stepX, y(bins.means[i]))
                        }
                        drawPath(trend, color = line, style = Stroke(width = 2.5f))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        AxisLabel(formatClock(window.timesMillis.first()))
                        Box(Modifier.weight(1f))
                        AxisLabel(formatClock(window.timesMillis.last()))
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // The three facts a plot cannot show about itself. The range is the *data's*, not the
            // drawn axis, so a flat trace still says how flat — and for a circular subject it is a
            // turn instead, because the extremes of an unwrapped heading are not bearings.
            val turn = if (isCircularDegrees(entry)) circularTurn(window.values) else null
            if (turn != null) {
                Detail("Turn", turn.describe())
            } else {
                Detail("Range", buildString {
                    append("${formatLiveValue(entry, bounds.min)} – ${formatLiveValue(entry, bounds.max)}")
                    labelOf(entry).unit?.let { append(" $it") }
                })
            }
            Detail("Samples", formatCount(window.size.toLong()))
            windowRateHz(window)?.let { Detail("Rate", "${formatRate(it)} Hz") }
            spanOf(window)?.let { Detail("Span", it) }
        }
    }
}

/**
 * One tick on the value axis.
 *
 * Circular subjects are labelled through [formatBearing] rather than [formatLiveValue] — see the note
 * at the call site. Everything else prints exactly as it does on its card.
 */
private fun axisLabel(entry: PublishedSubject, value: Float): String =
    if (isCircularDegrees(entry)) formatBearing(value) else formatLiveValue(entry, value)

@Composable
private fun AxisLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private val BAR_HEIGHT = 26.dp

/**
 * When it changed, and how long it held.
 *
 * For a subject whose float is an enum, a boolean or an identifier, a line is actively wrong — it draws
 * a slope through states that have no value between them. One row per distinct value, filled where that
 * value held, is the shape that answers what these are actually read for.
 */
@Composable
private fun DetailTimeline(entry: PublishedSubject, window: SampleWindow, nowMillis: Long) {
    val segments = remember(window) { stateSegments(window) }
    val span = timelineSpanMillis(segments)
    if (segments.isEmpty() || span == null) {
        EmptyState(
            title = "Nothing to show yet",
            body = "This needs at least two samples at different times; the window holds " +
                "${window.size}.",
        )
        return
    }
    val rows = remember(segments) { timelineRows(segments) }
    val start = segments.first().startMillis

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            rows.forEach { value ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        formatLiveValue(entry, value),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(92.dp),
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(BAR_HEIGHT)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            segments.filter { it.value == value }.forEach { seg ->
                                val x0 = (seg.startMillis - start).toFloat() / span * size.width
                                val x1 = (seg.endMillis - start).toFloat() / span * size.width
                                drawRect(
                                    color = segmentColor,
                                    topLeft = androidx.compose.ui.geometry.Offset(x0, 0f),
                                    // A run of one sample has no duration and would be invisible at
                                    // zero width — drawn as a hairline instead, which says "it happened
                                    // here" without claiming it lasted.
                                    size = androidx.compose.ui.geometry.Size(
                                        (x1 - x0).coerceAtLeast(2f),
                                        size.height,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 92.dp, top = 4.dp)) {
                AxisLabel(formatClock(start))
                Box(Modifier.weight(1f))
                AxisLabel(formatClock(segments.last().endMillis))
            }
        }
    }

    SectionHeader(
        title = if (segments.size == 1) "No changes in this window" else "${segments.size - 1} changes",
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Newest first: the last transition is the one being looked for, and a long list would
            // otherwise have to be scrolled to reach it.
            segments.asReversed().forEach { seg ->
                val last = seg === segments.last()
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        formatLiveValue(entry, seg.value),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(92.dp),
                    )
                    Text(
                        formatClock(seg.startMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(88.dp),
                    )
                    Text(
                        // The newest segment is still running, so it is measured to *now* rather than
                        // to its last sample — otherwise a state that has held for ten minutes reads
                        // as however long ago its last republish was.
                        duration(
                            if (last) nowMillis - seg.startMillis else seg.durationMillis
                        ) + if (last) " (now)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * What the receiver actually said.
 *
 * A `LazyColumn` because the ring holds two thousand lines and a `Column` would compose all of them.
 * Monospace because NMEA is comma-delimited fields that only line up in a fixed pitch.
 */
@Composable
private fun DetailText(text: TextHistory) {
    val state = rememberLazyListState()
    var follow by rememberSaveable { mutableStateOf(true) }

    // Auto-scroll to the newest while following. Sentences arrive at ~77/s on this phone, so without a
    // way to stop it the log cannot be read at all — and without the auto-scroll the newest line is
    // two thousand rows below where the screen opens.
    LaunchedEffect(text.lines.size, follow) {
        if (follow && text.lines.isNotEmpty()) state.scrollToItem(text.lines.lastIndex)
    }

    if (text.lines.isEmpty()) {
        EmptyState(
            title = "No sentences yet",
            body = "The receiver publishes these once it is talking. They are kept in memory " +
                "only, so a run that has been stopped and restarted starts empty.",
        )
        return
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = follow,
            onClick = { follow = !follow },
            label = { Text(if (follow) "Following" else "Follow") },
        )
        Box(Modifier.weight(1f))
    }
    // What is held, and therefore what is not. A log that silently drops its head reads as a complete
    // record of a short period rather than a window onto a longer one.
    Text(
        buildString {
            append("${formatCount(text.lines.size.toLong())} lines")
            if (text.isFull) append(" · the oldest are dropped as new ones arrive")
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().height(420.dp),
    ) {
        LazyColumn(state = state, modifier = Modifier.padding(8.dp)) {
            items(text.lines) { line ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text(
                        formatClock(line.timeMillis),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        line.text,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/** Every segment the same colour: a cell id is neither good nor bad, and nor is "charging". */
private val segmentColor: Color get() = SEGMENT_FILL

private val SEGMENT_FILL = Color(0xFF6F8FD8)

/** How long a window covers, on the same clock the run's elapsed time uses. */
private fun spanOf(window: SampleWindow): String? {
    if (window.size < 2) return null
    return duration(window.timesMillis.last() - window.timesMillis.first())
}

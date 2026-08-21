package se.rise.logline.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import se.rise.logline.publish.formatElapsed
import se.rise.logline.record.McapDetails
import se.rise.logline.record.TrackFix
import se.rise.logline.record.normaliseTag
import se.rise.logline.ui.components.EmptyState
import se.rise.logline.ui.components.ScreenScaffold
import se.rise.logline.ui.components.SectionHeader
import se.rise.logline.ui.components.readAsOneItem

/** How the track is coming along, so "still reading" and "there is none" cannot be confused. */
sealed interface TrackState {
    /** The scan is running. It is the one expensive read on this screen — see `McapTrack`. */
    data object Reading : TrackState

    /** The recording has no fix channel at all: GNSS was off, or never reached the file. */
    data object NoGnss : TrackState

    /**
     * The walk stopped before it could establish anything.
     *
     * Deliberately not folded into [NoGnss]: that one states a fact about the run, and a reader that
     * gave up has no grounds for it. A truncated file and a day without GNSS are different answers.
     */
    data object Unreadable : TrackState

    data class Ready(
        val fixes: List<TrackFix>,
        /** The walk ended early, so these are the fixes so far rather than the whole track. */
        val partial: Boolean = false,
    ) : TrackState
}

/**
 * One recording, in more than a name and a size.
 *
 * The Files list could say which files exist and nothing about which run each was. This answers that,
 * and it does so in two speeds on purpose: the figures come off the footer for the price of a few seeks
 * whatever the file's size, while the track is a full decompress of the data section. The metadata is
 * therefore never held up by the picture.
 */
@Composable
fun RecordingDetailScreen(
    name: String,
    sizeBytes: Long,
    savedAtMillis: Long,
    /** Null when the file has no readable summary — see `readMcapDetails`. */
    details: McapDetails?,
    /** Null while the summary is still being read; the track cannot be started before it. */
    detailsLoaded: Boolean,
    track: TrackState,
    /**
     * The chart, supplied by `MainActivity` — a `MapView` needs a `Context`, a tile cache and a
     * lifecycle, none of which a screen may hold. The same slot `LiveScreen` takes its map through.
     */
    chart: @Composable (List<TrackFix>, Modifier) -> Unit,
    /**
     * The words the operator had switched on when this file closed, read out of the file itself.
     *
     * Not editable here, and that is the design rather than an omission: a tag describes what the run
     * *was*, it is decided while the run is happening on the Events screen, and it is written into the
     * recording at close. A closed MCAP file is not rewritten to change its mind.
     */
    tags: Set<String>,
    onBack: () -> Unit,
) {
    // Not hoisted: expanding the chart is something you do for a minute while looking at it, the same
    // call the live view's own `mapExpanded` makes.
    var chartExpanded by rememberSaveable { mutableStateOf(false) }
    val fixes = (track as? TrackState.Ready)?.fixes.orEmpty()
    val expandable = fixes.size >= 2
    // Collapse rather than leave, so the system gesture and the bar's arrow agree — and so a full-screen
    // chart cannot be a place somebody backs out of the recording from by accident.
    val leave = { if (chartExpanded) chartExpanded = false else onBack() }
    BackHandler(enabled = chartExpanded) { chartExpanded = false }

    ScreenScaffold(
        title = if (chartExpanded) "Track" else "Recording",
        onBack = leave,
    ) { padding ->
        // **Outside the scrolling column, which is the whole point.** Collapsed, the chart sits in a
        // `verticalScroll`, so a drag across it is a gesture the page and the map both want and the
        // page wins — panning barely works. Expanded there is no scroll to compete with, and the
        // pinch, drag and double-tap the `MapView` has always had become usable.
        //
        // The top bar deliberately stays, for the reason the live view records: a control that
        // disappears is how somebody ends up stranded on a full-screen map.
        if (chartExpanded && expandable) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                chart(fixes, Modifier.fillMaxSize())
                ChartExpandButton(
                    expanded = true,
                    onExpandedChange = { chartExpanded = it },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
            }
            return@ScreenScaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(name, style = MaterialTheme.typography.titleMedium)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Fact("Size", formatBytes(sizeBytes))
                    Fact("Saved", formatClock(savedAtMillis))
                    if (details != null) {
                        Fact("Messages", formatCount(details.summary.messages))
                        Fact("Duration", formatElapsed(details.summary.durationMillis))
                        Fact("Topics", details.topics.size.toString())
                    } else if (detailsLoaded) {
                        // **Not zeroes.** A recording rescued from a killed process has
                        // `summary_start = 0` — every message present and the statistics gone — and
                        // reporting that as an empty file is how somebody deletes a good run.
                        Text(
                            "No summary in this file. Every message is still there; the figures are " +
                                "written when a recording closes, and this one did not.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (tags.isNotEmpty()) {
                SectionHeader(title = "Tags")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { AssistChip(onClick = {}, enabled = false, label = { Text(it) }) }
                }
            }

            SectionHeader(title = "Track")
            TrackCard(track, chart, onExpand = { chartExpanded = true })

            if (details != null && details.topics.isNotEmpty()) {
                SectionHeader(title = "Topics", trailing = formatCount(details.summary.messages))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        details.topics.forEachIndexed { index, topic ->
                            if (index > 0) {
                                HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .readAsOneItem(
                                        "${topic.topic}, ${formatCount(topic.messages)} messages"
                                    ),
                            ) {
                                Text(
                                    // The subject, not the whole key: the realm, entity and category
                                    // are the same on every row of a recording and the differences are
                                    // what is being read.
                                    subjectOf(topic.topic),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    formatCount(topic.messages),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackCard(
    track: TrackState,
    chart: @Composable (List<TrackFix>, Modifier) -> Unit,
    onExpand: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        when (track) {
            TrackState.Reading -> Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                Text(
                    // Says why it is slow, because it is slow for a reason nobody can see: the file
                    // holds no index, so every chunk has to be decompressed to find the fixes.
                    "Reading the track — the whole recording has to be read to find the positions.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // **Not the same as an empty track**, and the difference matters: no channel means GNSS was
            // off for the run, which is a Tuesday. A channel with nothing in it would be a fault.
            TrackState.NoGnss -> EmptyState(
                title = "No positions in this recording",
                body = "GNSS was not publishing while this ran, so there is no track to draw.",
                modifier = Modifier.padding(12.dp),
            )

            // What a reader that gave up is allowed to say, which is only what it did.
            TrackState.Unreadable -> EmptyState(
                title = "Could not read the track",
                body = "Reading this recording stopped early, so whether it holds any positions is " +
                    "unknown. Its messages are still in the file.",
                modifier = Modifier.padding(12.dp),
            )

            is TrackState.Ready ->
                if (track.fixes.size < 2) {
                    EmptyState(
                        title = "Not enough positions",
                        body = "A track needs two fixes; this recording holds ${track.fixes.size}.",
                        modifier = Modifier.padding(12.dp),
                    )
                } else {
                    Column(Modifier.padding(12.dp)) {
                        Box(
                            Modifier.fillMaxWidth().height(CHART_HEIGHT).clip(CHART_SHAPE),
                        ) {
                            chart(track.fixes, Modifier.fillMaxSize())
                            ChartExpandButton(
                                expanded = false,
                                onExpandedChange = { onExpand() },
                                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                            )
                        }
                        val extent = trackExtentMetres(track.fixes)
                        Text(
                            if (track.partial) {
                                // "so far", because the count is the reader's progress rather than the
                                // run's total — the same distinction the Unreadable state exists for.
                                "${trackSummary(track.fixes.size, extent)} so far — " +
                                    "reading stopped early"
                            } else {
                                // **The extent, not just the count.** 1 843 positions reads as a voyage
                                // whether they span thirteen metres or thirteen miles, and on this
                                // phone every recording so far is the former.
                                trackSummary(track.fixes.size, extent)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
        }
    }
}

private val CHART_HEIGHT = 220.dp

/** Matches the card it sits in, so the tiles do not square off a rounded surface. */
private val CHART_SHAPE = RoundedCornerShape(8.dp)

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The subject out of a full Zenoh key.
 *
 * `rise/@v0/pixel_6/pubsub/air_pressure_pa/phone` reads as `air_pressure_pa · phone`: the realm, entity
 * and category are identical on every row of one recording, so printing them forty times pushes the
 * differences off the edge.
 */
internal fun subjectOf(topic: String): String {
    val parts = topic.split('/')
    // Anything that is not a pubsub key is printed whole rather than guessed at.
    if (parts.size < 2) return topic
    return "${parts[parts.size - 2]} · ${parts.last()}"
}

/**
 * The one control the recording's chart needs.
 *
 * A single button rather than the live view's `MapToolbar`: there is no fix to follow and no marks to
 * draw over a run that finished hours ago, so three quarters of that toolbar would be controls for
 * things this chart does not have. It keeps the same translucent hugging surface and the same 40dp
 * button, so the two charts do not look like they came from different apps.
 */
@Composable
private fun ChartExpandButton(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier,
    ) {
        MapIconButton(
            icon = if (expanded) IconFullscreenExit else IconFullscreen,
            description = if (expanded) "Shrink the chart" else "Expand the chart",
            onClick = { onExpandedChange(!expanded) },
        )
    }
}

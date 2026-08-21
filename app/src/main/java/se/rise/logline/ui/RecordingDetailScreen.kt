package se.rise.logline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import se.rise.logline.publish.formatElapsed
import se.rise.logline.record.McapDetails
import se.rise.logline.record.TrackFix
import se.rise.logline.ui.components.EmptyState
import se.rise.logline.ui.components.ScreenScaffold
import se.rise.logline.ui.components.SectionHeader
import se.rise.logline.ui.components.readAsOneItem
import kotlin.math.cos

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
    onBack: () -> Unit,
) {
    ScreenScaffold(title = "Recording", onBack = onBack) { padding ->
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

            SectionHeader(title = "Track")
            TrackCard(track)

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
private fun TrackCard(track: TrackState) {
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
                        TrackChart(track.fixes)
                        Text(
                            if (track.partial) {
                                // "so far", because the count is the reader's progress rather than the
                                // run's total — the same distinction the Unreadable state exists for.
                                "${formatCount(track.fixes.size.toLong())} positions so far — " +
                                    "reading stopped early"
                            } else {
                                "${formatCount(track.fixes.size.toLong())} positions"
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

private val CHART_HEIGHT = 200.dp

/**
 * The track as a shape, with no map under it.
 *
 * Deliberately not `TrackMap`: that wants a network, a tile cache and a lifecycle, and this is here to
 * make a run recognisable rather than to navigate by.
 *
 * **Longitude is scaled by `cos(latitude)`.** A degree of longitude is that much shorter than a degree
 * of latitude — 0.54 at 57°N — so a track plotted on raw degrees comes out nearly twice as wide as it
 * was sailed. The same correction the accuracy circle makes through `metersToPixels`, for the same
 * reason.
 */
@Composable
private fun TrackChart(fixes: List<TrackFix>) {
    val line = MaterialTheme.colorScheme.primary
    val start = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT)
            .readAsOneItem("The recording's track, ${fixes.size} positions"),
    ) {
        val midLatitude = (fixes.minOf { it.latitude } + fixes.maxOf { it.latitude }) / 2.0
        val scale = cos(Math.toRadians(midLatitude)).coerceAtLeast(0.01)
        val xs = fixes.map { it.longitude * scale }
        val ys = fixes.map { it.latitude }
        val minX = xs.min()
        val minY = ys.min()
        // A stationary run is one point repeated, so both spans are zero. Floored rather than divided
        // by: the track then draws as a dot in the middle, which is what it was.
        val spanX = (xs.max() - minX).takeIf { it > 0 } ?: 1.0
        val spanY = (ys.max() - minY).takeIf { it > 0 } ?: 1.0
        // One scale for both axes, so the shape is the shape rather than stretched to the card.
        val span = maxOf(spanX, spanY)
        val pad = 8f
        val extent = minOf(size.width, size.height) - pad * 2
        val offsetX = (size.width - extent) / 2f
        val offsetY = (size.height - extent) / 2f

        fun px(i: Int) = Offset(
            x = offsetX + pad + ((xs[i] - minX) / span * extent).toFloat(),
            // Screen y grows downwards; north is up.
            y = offsetY + pad + extent - ((ys[i] - minY) / span * extent).toFloat(),
        )

        val path = Path().apply {
            moveTo(px(0).x, px(0).y)
            for (i in 1 until fixes.size) {
                val p = px(i)
                lineTo(p.x, p.y)
            }
        }
        drawPath(path, color = line, style = Stroke(width = 3f))
        // Where it began, so a there-and-back track is not ambiguous about which end is which.
        drawCircle(color = start, radius = 5f, center = px(0))
    }
}

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

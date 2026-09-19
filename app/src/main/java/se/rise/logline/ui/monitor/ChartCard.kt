package se.rise.logline.ui.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import se.rise.logline.monitor.MonitorCard
import se.rise.logline.monitor.MonitorSnapshot
import se.rise.logline.monitor.inputs
import se.rise.logline.monitor.resolve
import se.rise.logline.publish.TrackPoint
import se.rise.logline.ui.fmt
import se.rise.logline.ui.formatBearing

/**
 * What the Chart card draws: the watched entity's recent track, the newest point carrying its course,
 * and its heading. Null when no position has arrived.
 *
 * The course rides on the last point's `bearingDegrees`, which is where `TrackMap` reads a course
 * vector from — and only while COG is current, so a stale course does not point confidently from a
 * fresh position.
 */
data class ChartModel(val track: List<TrackPoint>, val headingDegrees: Float?, val follow: Boolean)

internal fun chartModel(card: MonitorCard, snapshot: MonitorSnapshot): ChartModel? {
    val position = card.inputs().firstOrNull { it.role == "position" }?.let(snapshot::resolve) ?: return null
    val all = snapshot.tracks[position].orEmpty()
    if (all.isEmpty()) return null
    val keepMs = (card.num("trackMinutes", 3.0) * 60_000).toLong()
    val recent = all.filter { it.timeMillis >= snapshot.nowMillis - keepMs }.ifEmpty { listOf(all.last()) }
    val cog = snapshot.reading(card, "cog").fresh
    val track = recent.dropLast(1) + recent.last().copy(bearingDegrees = cog)
    return ChartModel(track, snapshot.reading(card, "heading").fresh, card.flag("follow"))
}

/**
 * The card body around the map. The map itself is a slot, because it needs a `Context` — the screen
 * is handed it from `App()`, the way `LiveScreen` is.
 */
@Composable
internal fun ChartCardBody(
    card: MonitorCard,
    snapshot: MonitorSnapshot,
    map: @Composable (ChartModel, Modifier) -> Unit,
) {
    val model = chartModel(card, snapshot)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (model == null) {
            MissingLine(listOf(card.string("position")))
            return@Column
        }
        map(model, Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(12.dp)))
        val last = model.track.last()
        val sog = snapshot.reading(card, "sog")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "%.5f, %.5f".fmt(last.latitude, last.longitude),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                listOfNotNull(
                    sog.value?.let { "SOG %.1f kn".fmt(it) },
                    last.bearingDegrees?.let { "COG ${formatBearing(it)}" },
                    model.headingDegrees?.let { "HDG ${formatBearing(it)}" },
                ).joinToString(" · ").ifEmpty { "—" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val age = (snapshot.nowMillis - last.timeMillis)
        if (age > card.num("staleAfterS", 5.0) * 1000) {
            Text(
                "Last known position · ${formatAge(age)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

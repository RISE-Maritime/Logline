package se.rise.logline.ui.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import se.rise.logline.monitor.CardKind
import se.rise.logline.monitor.MonitorCard
import se.rise.logline.monitor.MonitorSnapshot
import se.rise.logline.monitor.Topic
import se.rise.logline.monitor.inputs
import se.rise.logline.monitor.resolve
import se.rise.logline.publish.SampleWindow
import se.rise.logline.ui.fmt

/**
 * One scalar input as a card reads it: which topic it resolved to, its newest value, and whether that
 * value is still current.
 *
 * `value` null means **nothing has arrived** — shown as `—`, never as zero. A stale value is still
 * shown, dimmed with its age: a rudder angle from twelve seconds ago is information, just old.
 */
internal data class Reading(val topic: Topic?, val value: Float?, val ageMillis: Long?, val stale: Boolean) {
    val present: Boolean get() = value != null
    /** The value only while it is current — for anything derived, where a stale input must not count. */
    val fresh: Float? get() = if (stale) null else value
}

internal fun MonitorSnapshot.reading(card: MonitorCard, role: String): Reading {
    val input = card.inputs().firstOrNull { it.role == role } ?: return Reading(null, null, null, false)
    val topic = resolve(input) ?: return Reading(null, null, null, false)
    val value = latestScalar[topic]
    val age = info(topic)?.let { (nowMillis - it.lastArrivalMillis).coerceAtLeast(0) }
    val stale = age != null && age > card.num("staleAfterS", 5.0) * 1000
    return Reading(topic, value, age, stale)
}

/** The window behind a scalar input, or empty. */
internal fun MonitorSnapshot.window(card: MonitorCard, role: String): SampleWindow {
    val input = card.inputs().firstOrNull { it.role == role } ?: return SampleWindow()
    return resolve(input)?.let { scalars[it] } ?: SampleWindow()
}

/** Every card body. The Chart card is drawn by the screen's `chart` slot instead. */
@Composable
internal fun CardBody(card: MonitorCard, snapshot: MonitorSnapshot) {
    when (card.kind) {
        CardKind.Plot -> PlotCard(card, snapshot)
        CardKind.Value -> ValueCard(card, snapshot)
        CardKind.Heading -> HeadingCard(card, snapshot)
        CardKind.Rudder -> RudderCard(card, snapshot)
        CardKind.Engine -> EngineCard(card, snapshot)
        CardKind.BowThruster -> ThrusterCard(card, snapshot)
        CardKind.Wind -> WindCard(card, snapshot)
        CardKind.PitchRoll -> PitchRollCard(card, snapshot)
        CardKind.ShipVelocity -> ShipVelocityCard(card, snapshot)
        CardKind.Imu -> ImuCard(card, snapshot)
        CardKind.NavMap -> Unit
    }
}

/** A value in a given number of decimals, or `—`. Locale.ROOT via `.fmt()`, so never `55,3`. */
internal fun formatValue(value: Float?, decimals: Int): String =
    value?.let { "%.${decimals.coerceIn(0, 6)}f".fmt(it) } ?: "—"

/** `12 s old` / `3 min old`, for a stale reading. */
internal fun formatAge(ageMillis: Long): String {
    val s = ageMillis / 1000
    return when {
        s < 90 -> "$s s old"
        s < 5400 -> "${s / 60} min old"
        else -> "${s / 3600} h old"
    }
}

/** The colour a reading is drawn in: dimmed when stale, which is what makes an old number look old. */
@Composable
internal fun readingColor(reading: Reading): Color =
    if (reading.stale) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface

/**
 * A labelled figure with its unit, and its age when stale — the building block of every readout card.
 * The unit is quieter than the number, as everywhere else in the app.
 */
@Composable
internal fun Figure(
    caption: String,
    reading: Reading,
    text: String,
    unit: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            caption,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text,
                style = if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
                color = readingColor(reading),
                textAlign = TextAlign.Center,
            )
            if (unit.isNotEmpty() && reading.present) {
                Text(
                    unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (reading.stale && reading.ageMillis != null) {
            Text(
                formatAge(reading.ageMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** A line under a card saying which input it could not find — the card's own empty state. */
@Composable
internal fun MissingLine(subjects: List<String>) {
    if (subjects.isEmpty()) return
    Text(
        "Not seen: ${subjects.joinToString()}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The subjects among a card's inputs that the entity has not published. */
internal fun MonitorSnapshot.missing(card: MonitorCard): List<String> =
    card.inputs().filter { input -> resolve(input)?.let(::info) == null }.map { it.subject }.distinct()

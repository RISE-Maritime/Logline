package se.rise.logline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import se.rise.logline.publish.TrackPoint
import se.rise.logline.ui.components.readAsOneItem
import kotlin.math.roundToInt

/**
 * The readings you want before you want anything else: where the phone is, how fast, which way.
 *
 * Deliberately three, deliberately large. A dashboard that shows everything shows nothing, and these
 * are the ones an operator glances at mid-test — position to know the track is real, speed and course
 * to know it is moving sensibly, heading to see which way the phone points while it does.
 */
@Composable
fun DashboardReadings(
    speedKnots: Float?,
    courseDegrees: Float?,
    headingDegrees: Float?,
    headingIsTrue: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Reading(
            value = speedKnots?.let { "%.1f".fmt(it) } ?: "—",
            unit = "kn",
            caption = "SPEED",
            modifier = Modifier.weight(1f),
        )
        Reading(
            value = courseDegrees?.let { "${it.roundToInt()}" } ?: "—",
            unit = "°",
            caption = "COURSE",
            bearing = courseDegrees,
            modifier = Modifier.weight(1f),
        )
        Reading(
            value = headingDegrees?.let { "${it.roundToInt()}" } ?: "—",
            unit = "°",
            caption = if (headingIsTrue) "HEADING TRUE" else "HEADING MAG",
            bearing = headingDegrees,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Reading(
    value: String,
    unit: String,
    caption: String,
    modifier: Modifier = Modifier,
    bearing: Float? = null,
) {
    val describedAs = buildString {
        append("$caption ${if (value == "—") "unknown" else value}")
        if (value != "—") append(" $unit")
        bearing?.let { append(", ${cardinal(it)}") }
    }
    Column(
        modifier = modifier.readAsOneItem(describedAs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(
                unit,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Spaced by the one shared rule — see `unitGap`. This used to be its own predicate,
                // which is how "0.3 kn" here and "2.2Gbit/s" on a card were spaced differently.
                modifier = Modifier.padding(start = unitGap(unit), bottom = 3.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            // A number is a bearing only once you know which way it points; the arrow says it without
            // costing a line, and the cardinal name is in the screen-reader description.
            bearing?.let {
                BearingArrow(
                    degrees = it,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp).size(12.dp),
                )
            }
        }
    }
}

/** North-up arrow rotated to a bearing, drawn rather than glyphed so it points exactly. */
@Composable
fun BearingArrow(degrees: Float, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        rotate(degrees) {
            val path = Path().apply {
                moveTo(size.width / 2f, 0f)
                lineTo(size.width, size.height)
                lineTo(size.width / 2f, size.height * 0.68f)
                lineTo(0f, size.height)
                close()
            }
            drawPath(path, color = tint)
        }
    }
}

/** Position and how much to trust it, on one line. */
@Composable
fun FixLine(fix: TrackPoint?, modifier: Modifier = Modifier) {
    val quality = gnssQuality(fix?.accuracyMetres)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            fix?.let { formatPosition(it.latitude, it.longitude) } ?: "No fix yet",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        fix?.accuracyMetres?.let {
            Text(
                "±${it.roundToInt()} m",
                style = MaterialTheme.typography.bodyMedium,
                color = when (quality) {
                    FixQuality.Good -> MaterialTheme.colorScheme.onSurfaceVariant
                    FixQuality.Fair -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

/**
 * One chip per group: the name, whether it is behaving, and — now — a filter.
 *
 * These were a read-only readout, which made a row of six low-value labels competing for the space
 * just above the plots. Making them selectable turns the same pixels into the coarse "show me only
 * this" control the plot list otherwise lacks: tap one to narrow, tap it again to go back to all.
 *
 * Horizontally scrollable because six groups do not fit across a phone once they are tap targets with
 * padding rather than bare text.
 */
@Composable
fun HealthChips(
    chips: List<HealthChip>,
    modifier: Modifier = Modifier,
    /** The group name currently filtered to, or null for all of them. */
    selected: String? = null,
    onSelect: ((String?) -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEach { chip ->
            val colour = if (chip.healthy) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
            val isSelected = selected == chip.name
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .then(
                        if (onSelect != null) {
                            Modifier.clickable { onSelect(if (isSelected) null else chip.name) }
                        } else {
                            Modifier
                        }
                    )
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            Color.Transparent
                        }
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .readAsOneItem(
                        buildString {
                            append(chip.name)
                            append(if (chip.healthy) " healthy" else " needs attention")
                            if (onSelect != null) {
                                append(if (isSelected) ", showing only this" else ", tap to show only this")
                            }
                        }
                    ),
            ) {
                Surface(color = colour, shape = CircleShape, modifier = Modifier.size(7.dp)) {}
                Text(
                    chip.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (chip.healthy) MaterialTheme.colorScheme.onSurfaceVariant else colour,
                    modifier = Modifier.padding(start = 3.dp),
                )
            }
        }
    }
}

/**
 * The second line under the fix: is the data any good, and will the phone last.
 *
 * Four numbers that otherwise cost a scroll into GNSS, Radio and Device to answer "is this run
 * healthy" — satellites solving, what kind of fix it is, the cellular link's quality and the battery.
 * Each is skipped rather than shown as a dash when the platform has not reported it, the same rule the
 * publish path follows: proto3 cannot tell an absent float from `0.0`, and neither can a reader.
 */
@Composable
fun VitalsLine(
    satellitesUsed: Float?,
    fixQuality: String?,
    sinrDb: Float?,
    batteryPercent: Float?,
    modifier: Modifier = Modifier,
) {
    val parts = buildList {
        fixQuality?.let { add(it) }
        satellitesUsed?.let { add("${it.roundToInt()} sats") }
        sinrDb?.let { add("SINR ${it.roundToInt()} dB") }
        batteryPercent?.let { add("${it.roundToInt()} %") }
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    )
}

data class HealthChip(val name: String, val healthy: Boolean)

/** Short names for the chip row — the section headings are too long to sit five across a phone. */
fun chipName(groupTitle: String): String = when (groupTitle) {
    "Radio · cellular" -> "Cell"
    "Radio · wifi" -> "Wi-Fi"
    else -> groupTitle
}

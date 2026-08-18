package se.rise.logline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
                // Degrees sit tight against the number; a unit like kn takes a space.
                modifier = Modifier.padding(start = if (unit == "°") 0.dp else 2.dp, bottom = 3.dp),
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

/** One chip per group: the name, and whether it is behaving. Colour only when it is not. */
@Composable
fun HealthChips(chips: List<HealthChip>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEach { chip ->
            val colour = if (chip.healthy) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.readAsOneItem(
                    "${chip.name} ${if (chip.healthy) "healthy" else "needs attention"}"
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

data class HealthChip(val name: String, val healthy: Boolean)

/** Short names for the chip row — the section headings are too long to sit five across a phone. */
fun chipName(groupTitle: String): String = when (groupTitle) {
    "Radio · cellular" -> "Cell"
    "Radio · wifi" -> "Wi-Fi"
    else -> groupTitle
}

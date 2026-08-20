package se.rise.logline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.rise.logline.publish.TrackPoint
import se.rise.logline.ui.components.readAsOneItem
import kotlin.math.roundToInt

/**
 * The readings you want before you want anything else: where the phone is, how fast, which way.
 *
 * Deliberately three, deliberately large. A dashboard that shows everything shows nothing, and these
 * are the ones an operator glances at mid-test — position to know the track is real, speed and course
 * to know it is moving sensibly, heading to see which way the phone points while it does.
 *
 * **The captions are the nautical abbreviations**, and the screen-reader description is not: `SOG` is
 * three characters where "SPEED" is five and "HEADING TRUE" is twelve, which is what lets all three
 * captions sit at the same width without wrapping. `readAsOneItem` spells each one out in full, so the
 * abbreviation costs a sighted reader a moment's learning and a screen-reader user nothing at all.
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
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Reading(
            value = speedKnots?.let { "%.1f".fmt(it) } ?: "—",
            unit = "kn",
            caption = "SOG",
            spokenName = "Speed over ground",
            spokenUnit = "knots",
            modifier = Modifier.weight(1f),
        )
        Reading(
            // Zero-padded, so the row does not shift as a course crosses 10 or 100 — see
            // `formatBearing`. Nothing on a marine instrument writes a bearing with fewer than three
            // digits, for exactly this reason.
            value = courseDegrees?.let { "${formatBearing(it)}°" } ?: "—",
            unit = "",
            caption = "COG",
            spokenName = "Course over ground",
            spokenUnit = "degrees",
            bearing = courseDegrees,
            modifier = Modifier.weight(1f),
        )
        Reading(
            value = headingDegrees?.let { "${formatBearing(it)}°" } ?: "—",
            unit = "",
            // The suffix is the whole distinction, and it must never be dropped to save a character:
            // a heading referenced to magnetic north can be ten degrees off true in these waters.
            caption = if (headingIsTrue) "HDG T" else "HDG M",
            spokenName = if (headingIsTrue) "Heading, true north" else "Heading, magnetic",
            spokenUnit = "degrees",
            bearing = headingDegrees,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The height the caption row is held to, so a reading without a bearing lines up with one that has. */
private val ARROW_SLOT = 12.dp

@Composable
private fun Reading(
    value: String,
    unit: String,
    caption: String,
    spokenName: String,
    spokenUnit: String,
    modifier: Modifier = Modifier,
    bearing: Float? = null,
) {
    val describedAs = buildString {
        append("$spokenName ${if (value == "—") "unknown" else value}")
        if (value != "—") append(" $spokenUnit")
        bearing?.let { append(", ${cardinal(it)}") }
    }
    Column(
        modifier = modifier.readAsOneItem(describedAs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                // The top of the type ladder — these are the numbers the screen exists for, and they
                // are meant to be readable at arm's length on a wet deck.
                style = MaterialTheme.typography.headlineLarge,
            )
            // **A bearing carries its own degree sign, in the figure's own size.** Set at label size on
            // the baseline of a 32sp number, `°` is a few pixels across and sits where a full stop
            // sits — `000.` is what it reads as, which is worse than no unit at all. Every other unit
            // is a word or an SI prefix and is correctly quieter than the number it qualifies.
            if (unit.isNotEmpty()) {
                Text(
                    unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Spaced by the one shared rule — see `unitGap`. This used to be its own predicate,
                    // which is how "0.3 kn" here and "2.2Gbit/s" on a card were spaced differently.
                    modifier = Modifier.padding(start = unitGap(unit), bottom = 4.dp),
                )
            }
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
            //
            // **The slot is reserved whether or not there is an arrow in it.** Speed has no bearing, so
            // without this its caption row is shorter than the other two and the three baselines no
            // longer agree — which is visible as a wobble along a row that is meant to read as one
            // instrument.
            Box(
                Modifier.padding(start = 4.dp).size(ARROW_SLOT),
                contentAlignment = Alignment.Center,
            ) {
                bearing?.let {
                    BearingArrow(
                        degrees = it,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(ARROW_SLOT),
                    )
                }
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

/**
 * Position and how much to trust it, attached to the bottom of the chart.
 *
 * **Three states, because two of them were previously indistinguishable.** A position with `±11 m`
 * beside it reads as current whatever its age, so a phone that lost its fix a minute ago went on
 * presenting a stale coordinate as though it were live. When the fix has stopped arriving the line says
 * so, in words, above the same numbers.
 *
 * @param stale whether `location_fix` has stopped publishing, decided by the caller through the app's
 *   one staleness rule (`subjectHealth`) rather than by a threshold invented here. A second definition
 *   of "stopped" would eventually disagree with the group heading directly below it.
 *
 * Note this is **not** the same fact as the `No fix` word in [VitalsLine]. That one is the receiver
 * saying it is not solving, which it does while a perfectly current fused position — derived from wifi
 * and cell — is still arriving. A position can be fresh and unsolved, or solved and old.
 */
@Composable
fun FixLine(fix: TrackPoint?, nowMillis: Long, stale: Boolean, modifier: Modifier = Modifier) {
    val quality = gnssQuality(fix?.accuracyMetres)
    Column(modifier.fillMaxWidth()) {
        if (fix != null && stale) {
            Text(
                "Last known position · ${formatAge(fix.timeMillis, nowMillis)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                fix?.let { formatPosition(it.latitude, it.longitude) } ?: "No fix yet",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            fix?.accuracyMetres?.let {
                Text(
                    "±${it.roundToInt()} m",
                    // Diagnostic rather than primary: it qualifies the coordinate beside it and should
                    // not be read at the same weight.
                    style = MaterialTheme.typography.labelMedium,
                    color = when (quality) {
                        FixQuality.Good -> MaterialTheme.colorScheme.onSurfaceVariant
                        FixQuality.Fair -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

/**
 * One chip per group: the name, whether it is behaving, and a filter.
 *
 * These were a read-only readout, which made a row of six low-value labels competing for the space just
 * above the plots. Making them selectable turns the same pixels into the coarse "show me only this"
 * control the plot list otherwise lacks: tap one to narrow, tap it again to go back to all.
 *
 * **A healthy chip carries no indicator at all**, which is the whole of what stops the row reading as a
 * legend for a chart that is not there. Six coloured dots along the bottom of a screen say six times
 * over that everything is fine — and colour spent on the normal case is colour that cannot be spent on
 * the abnormal one. A group needing attention is the only thing here that is ever coloured, so it is
 * the only thing the eye is drawn to; the word is beside the dot, as everywhere else in this app.
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
            val isSelected = selected == chip.name
            val alert = MaterialTheme.colorScheme.error
            Surface(
                shape = RoundedCornerShape(50),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    Color.Transparent
                },
                // The outline is what makes these read as controls rather than as labels. Selected, the
                // fill carries it and a border on top would double the edge.
                border = if (isSelected) {
                    null
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                },
                modifier = Modifier.then(
                    if (onSelect != null) {
                        Modifier.clickable { onSelect(if (isSelected) null else chip.name) }
                    } else {
                        Modifier
                    }
                ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .readAsOneItem(
                            buildString {
                                append(chip.name)
                                append(if (chip.healthy) " healthy" else " needs attention")
                                if (onSelect != null) {
                                    append(
                                        if (isSelected) {
                                            ", showing only this"
                                        } else {
                                            ", tap to show only this"
                                        }
                                    )
                                }
                            }
                        ),
                ) {
                    if (!chip.healthy) {
                        Surface(
                            color = alert,
                            shape = CircleShape,
                            modifier = Modifier.size(7.dp).padding(end = 0.dp),
                        ) {}
                    }
                    Text(
                        chip.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (chip.healthy) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            alert
                        },
                        modifier = Modifier.padding(start = if (chip.healthy) 0.dp else 5.dp),
                    )
                }
            }
        }
    }
}

/**
 * The second line under the fix: is the data any good, and will the phone last.
 *
 * Four readings that otherwise cost a scroll into GNSS, Radio and Device to answer "is this run
 * healthy" — what kind of fix the receiver is producing, how many satellites it is solving with, the
 * cellular link's quality and the battery.
 *
 * **A labelled value each, not one run-on string.** `No fix · 0 sats · SINR 13 dB · 100 %` reads as
 * debug output left in by accident: four unrelated facts in one sentence, in one colour, with the
 * important one indistinguishable from the rest. Split into `LABEL` over `value`, one abnormal reading
 * is the only coloured thing on the row and finds the eye on its own.
 *
 * The figures stay figures. `13 dB` rather than "Good" — this is a trials instrument, and a number is
 * something an operator can compare against the last run where an adjective is not. The one word here
 * is the fix kind, which is a word on the wire too: an enum, not a measurement.
 *
 * Each part is skipped rather than shown as a dash when the platform has not reported it, the same rule
 * the publish path follows: proto3 cannot tell an absent float from `0.0`, and neither can a reader.
 */
@Composable
fun VitalsLine(
    satellitesUsed: Float?,
    fixQuality: String?,
    fixQualityTone: FixQuality,
    sinrDb: Float?,
    batteryPercent: Float?,
    modifier: Modifier = Modifier,
) {
    val vitals = buildList {
        fixQuality?.let { add(Triple("GNSS", it, fixQualityTone)) }
        // Never coloured. The count on its own does not decide anything — a fused fix indoors solves
        // with none and is still a position — and a second red thing beside the GNSS verdict would say
        // the same fault twice.
        satellitesUsed?.let { add(Triple("SAT", "${it.roundToInt()}", FixQuality.Good)) }
        sinrDb?.let { add(Triple("CELL", "${it.roundToInt()} dB", cellularQuality(it))) }
        batteryPercent?.let { add(Triple("BAT", "${it.roundToInt()} %", batteryQuality(it))) }
    }
    if (vitals.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        vitals.forEach { (label, value, tone) -> Vital(label, value, tone) }
    }
}

@Composable
private fun Vital(label: String, value: String, tone: FixQuality) {
    Column(modifier = Modifier.readAsOneItem("$label $value")) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Tracked out, because a four-letter uppercase label set tight reads as an acronym to be
            // decoded rather than as a caption to be skimmed past.
            letterSpacing = 0.8.sp,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = when (tone) {
                // Deliberately not green. Everything here is normally fine, and a row of green would
                // be six statements of the obvious competing with the one that matters.
                FixQuality.Good, FixQuality.Unknown -> MaterialTheme.colorScheme.onSurface
                FixQuality.Fair -> MaterialTheme.colorScheme.tertiary
                FixQuality.Poor -> MaterialTheme.colorScheme.error
            },
        )
    }
}

data class HealthChip(val name: String, val healthy: Boolean)

/** Short names for the chip row — the section headings are too long to sit five across a phone. */
fun chipName(groupTitle: String): String = when (groupTitle) {
    "Radio · cellular" -> "Cell"
    "Radio · wifi" -> "Wi-Fi"
    else -> groupTitle
}

package se.rise.logline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.keelson.Subjects

/**
 * What a subject's numbers are measured *against*.
 *
 * Without this, an x/y/z triple is three unlabelled numbers: the same reading means opposite things
 * depending on which way the phone is lying, and nothing on the wire says which convention was used.
 */
enum class SensorFrame {
    /** Fixed to the phone's body, in its natural orientation. Android's sensor coordinate system. */
    DeviceXyz,

    /** A rotation *from* the device frame *to* the world — east, north, up. */
    WorldEnu,

    /**
     * A compass bearing: degrees clockwise from north, of one particular device axis. It needs both
     * halves explained — which north, and which edge of the phone.
     */
    Bearing,

    /** A scalar, a geodetic position, or a course over ground — no device axes involved. */
    None,
}

/**
 * Named per subject rather than per source, because "comes off the IMU" stopped being the same question
 * as "is in device axes" the moment the compass subjects arrived: they are derived from the same
 * rotation vector as the quaternion, but a heading is an angle from north and `heading_accuracy_deg` is
 * an uncertainty with no direction at all.
 *
 * `McapSchemasTest` pins the part that could go stale — every `Decomposed3DVector` subject must be
 * classified here as device axes, so a new vector subject cannot quietly default to "no frame".
 */
fun frameOf(entry: PublishedSubject): SensorFrame = when (entry.subject) {
    Subjects.LINEAR_ACCELERATION_MPSS,
    Subjects.ANGULAR_VELOCITY_RADPS,
    Subjects.MAGNETIC_FIELD_GAUSS -> SensorFrame.DeviceXyz
    Subjects.ORIENTATION_QUATERNION -> SensorFrame.WorldEnu
    Subjects.HEADING_MAGNETIC_DEG,
    Subjects.HEADING_TRUE_NORTH_DEG -> SensorFrame.Bearing
    else -> SensorFrame.None
}

/** The subjects whose x, y and z are the phone's own axes. */
fun deviceFrameSubjects(): List<String> =
    PublishedSubject.entries.filter { frameOf(it) == SensorFrame.DeviceXyz }.map { it.subject }

/**
 * Which way is +X, and what that means for every subject that has a direction.
 *
 * The one fact worth shouting: **Android's sensor axes are fixed to the phone body in its natural
 * orientation and never rotate with the screen.** An app that wanted screen-relative readings would
 * have to call `remapCoordinateSystem` itself; this one publishes the device frame unaltered, so a
 * consumer must know which end of the phone `+Y` points at.
 */
@Composable
fun AxisReferenceCard(modifier: Modifier = Modifier, compact: Boolean = false) {
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = accent, fontSize = 12.sp)

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Axes, relative to the phone", style = MaterialTheme.typography.titleSmall)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(
                    modifier = Modifier
                        .width(120.dp)
                        .height(150.dp)
                        .semantics {
                            contentDescription = "Diagram of the phone in portrait. Plus X points " +
                                "right out of the right edge, plus Y points up out of the top edge, " +
                                "and plus Z points out of the screen towards you."
                        },
                ) {
                    drawPhoneAxes(outline, accent, measurer, labelStyle)
                }
                Column(
                    Modifier.padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("+X → right edge", style = MaterialTheme.typography.bodyMedium)
                    Text("+Y → top edge", style = MaterialTheme.typography.bodyMedium)
                    Text("+Z → out of the screen", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Rotation is right-handed: positive is counter-clockwise seen from the " +
                            "positive end of the axis.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Deliberately not the usual "flat on a table reads 9.8 on Z" example: this app publishes
            // TYPE_LINEAR_ACCELERATION, which has gravity already removed, so that check would send
            // someone looking for a number that is never there.
            Text(
                "Held upright in portrait, +X is right, +Y is up and +Z points at your face. " +
                    "${Subjects.LINEAR_ACCELERATION_MPSS} has gravity removed, so a phone at rest reads " +
                    "about zero on all three — push it towards its right edge and X goes positive.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // The trap: the axes belong to the phone body, not to what you are looking at.
            Text(
                "These axes are fixed to the phone's body in its natural (portrait) orientation and do " +
                    "not rotate when the screen does — turning the phone to landscape does not swap X " +
                    "and Y in the data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            if (!compact) {
                Text(
                    "In these axes: ${deviceFrameSubjects().joinToString(", ")}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${Subjects.ORIENTATION_QUATERNION} is the odd one out: it is the rotation from " +
                        "these device axes to the world frame — x east, y north, z up — so it is what " +
                        "turns the readings above into earth-referenced ones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${Subjects.HEADING_MAGNETIC_DEG} and ${Subjects.HEADING_TRUE_NORTH_DEG} are the " +
                        "same axes read as a compass: degrees clockwise from north, of +Y — the top " +
                        "edge. Lay the phone flat and it is the direction the top points; stand it " +
                        "upright and +Y points at the sky, where a heading stops meaning anything.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Everything else is direction-free: the scalars carry no axis, ${Subjects.LOCATION_FIX} " +
                        "is WGS-84 latitude, longitude and altitude, and ${Subjects.COURSE_OVER_GROUND_DEG} " +
                        "is degrees clockwise from true north — where the phone is *going*, which is not " +
                        "where it points.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A phone seen face-on, with the two in-plane axes as arrows and the third coming out of the screen.
 *
 * The earpiece slot at the top is not decoration — it is the only thing in the drawing that says which
 * end is the top, and "+Y towards the top" is meaningless without it.
 */
private fun DrawScope.drawPhoneAxes(
    outline: Color,
    accent: Color,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
) {
    val bodyWidth = size.width * 0.44f
    val bodyHeight = size.height * 0.66f
    val left = size.width * 0.16f
    val top = (size.height - bodyHeight) / 2f
    val centre = Offset(left + bodyWidth / 2f, top + bodyHeight / 2f)

    drawRoundRect(
        color = outline,
        topLeft = Offset(left, top),
        size = Size(bodyWidth, bodyHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
        style = Stroke(width = 3f),
    )
    // Earpiece slot — which end is up.
    drawLine(
        color = outline,
        start = Offset(centre.x - bodyWidth * 0.18f, top + bodyHeight * 0.06f),
        end = Offset(centre.x + bodyWidth * 0.18f, top + bodyHeight * 0.06f),
        strokeWidth = 3f,
    )

    // +Y, out of the top edge.
    val yTip = Offset(centre.x, top - size.height * 0.10f)
    drawArrow(centre, yTip, accent)
    drawText(measurer, "+Y", style = labelStyle, topLeft = Offset(yTip.x + 8f, yTip.y - 6f))

    // +X, out of the right edge.
    val xTip = Offset(left + bodyWidth + size.width * 0.22f, centre.y)
    drawArrow(centre, xTip, accent)
    drawText(measurer, "+X", style = labelStyle, topLeft = Offset(xTip.x - 14f, xTip.y + 8f))

    // +Z, out of the screen: the standard circle-and-dot for an arrow pointing at the reader.
    drawCircle(color = accent, radius = 11f, center = centre, style = Stroke(width = 3f))
    drawCircle(color = accent, radius = 3.5f, center = centre)
    drawText(measurer, "+Z", style = labelStyle, topLeft = Offset(centre.x + 16f, centre.y + 6f))
}

private fun DrawScope.drawArrow(from: Offset, to: Offset, color: Color) {
    drawLine(color = color, start = from, end = to, strokeWidth = 3f)
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
    val ux = dx / length
    val uy = dy / length
    val head = 14f
    // Perpendicular, so the head sits square on the shaft whichever way it points.
    val px = -uy
    val py = ux
    val base = Offset(to.x - ux * head, to.y - uy * head)
    val path = Path().apply {
        moveTo(to.x, to.y)
        lineTo(base.x + px * head * 0.45f, base.y + py * head * 0.45f)
        lineTo(base.x - px * head * 0.45f, base.y - py * head * 0.45f)
        close()
    }
    drawPath(path, color = color)
}

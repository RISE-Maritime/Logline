package se.rise.logline.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import se.rise.logline.calibrate.LatLonAlt
import se.rise.logline.calibrate.Vec3M
import se.rise.logline.calibrate.bodyOffsetMetres
import se.rise.logline.calibrate.enuOffsetMetres
import se.rise.logline.ui.components.FormActions
import se.rise.logline.ui.components.ScreenScaffold

/**
 * Put a sensor where it sits, by pointing at it on a chart.
 *
 * **A relative measurement between two points on one image, which is what makes it worth having.**
 * The offset is the difference between the zero and the sensor, and a chart's dominant error — the
 * imagery's georeferencing — is largely a uniform local shift, which cancels out of a difference.
 * What is left is how well somebody pointed, a few pixels. So this is not merely a convenience for
 * a sensor you cannot walk to: on a large platform it can beat walking there with the phone, whose
 * fix accuracy this app already warns is often larger than the offset being measured.
 *
 * It does **not** touch height or rotation. A chart is flat, so `z` is kept from whatever was there;
 * and a phone beside a radar can measure where the radar *is* but not where it is *looking*, which
 * is the reason rotation is always typed and a map changes nothing about that.
 */
@Composable
fun SensorOffsetPicker(
    label: String,
    zero: LatLonAlt,
    headingDeg: Double,
    /** The offset as it stands, so the sensor can be seen where it currently is. */
    current: Vec3M,
    map: @Composable (anchor: LatLonAlt, sensor: LatLonAlt, onCentre: (Double, Double) -> Unit, Modifier) -> Unit,
    sensorPoint: (Vec3M) -> LatLonAlt,
    onCancel: () -> Unit,
    onPick: (Vec3M) -> Unit,
) {
    var centre by remember { mutableStateOf<LatLonAlt?>(null) }

    // Forward and starboard from the crosshair; **down is kept**, because a chart cannot see height
    // and replacing a measured mast height with zero would be the confident wrong number this
    // codebase keeps out.
    val picked = centre?.let {
        bodyOffsetMetres(enuOffsetMetres(zero, it), headingDeg).copy(z = current.z)
    }
    val shown = picked ?: current

    ScreenScaffold(
        title = "Place ${label.ifBlank { "the sensor" }}",
        onBack = onCancel,
        bottomBar = {
            FormActions(
                saveLabel = "Use this position",
                onSave = { picked?.let(onPick) },
                saveEnabled = picked != null,
                onCancel = onCancel,
                hint = "Height and rotation are not changed — a chart cannot see either.",
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                map(
                    zero,
                    sensorPoint(shown),
                    { lat, lon -> centre = LatLonAlt(lat, lon, 0.0) },
                    Modifier.fillMaxSize(),
                )
                Icon(
                    imageVector = IconMyLocation,
                    contentDescription = "Where the sensor sits",
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(40.dp),
                )
            }
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    // The platform frame, in the words the fields use: forward, starboard, down.
                    // Not latitude and longitude — the model stores an offset, and showing the
                    // position it was derived from would be showing the working rather than the answer.
                    "${"%.2f".fmt(shown.x)} m forward · ${"%.2f".fmt(shown.y)} m starboard",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (picked == null) {
                        "The ring is the platform's zero, the dot is where this sensor sits now. " +
                            "Move the crosshair onto the sensor."
                    } else {
                        "${"%.2f".fmt(shown.magnitude())} m from the zero · height and rotation " +
                            "unchanged at ${"%.2f".fmt(shown.z)} m down"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

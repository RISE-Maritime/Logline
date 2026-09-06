package se.rise.logline.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import se.rise.logline.calibrate.LatLonAlt
import se.rise.logline.calibrate.PlatformZero
import se.rise.logline.calibrate.degreesPerMetreOfError
import se.rise.logline.calibrate.headingFromBaseline
import se.rise.logline.ui.components.FormActions
import se.rise.logline.ui.components.ScreenScaffold
import kotlin.math.roundToInt

/**
 * Set the platform's forward axis by pointing at where it goes.
 *
 * **Anchored at the zero and one point to place.** The axis passes through the zero by definition,
 * so there is nothing to decide about where it starts — pan a point ahead on the centreline under
 * the crosshair and the bearing is zero → there.
 *
 * **Why a chart can beat walking it.** A baseline's angular error is position error divided by
 * baseline length: a metre of uncertainty is about thirty degrees over two metres and under three
 * over twenty. Walked, both ends carry independent GNSS error. On a chart the dominant error is the
 * imagery's georeferencing, which is largely a *uniform local shift* — and a uniform shift cancels
 * out of a bearing between two points on the same imagery, leaving only how well somebody pointed.
 * So a long baseline down a quay edge here is often worth more than a short walked one, which is why
 * the length and the angle it implies are on screen while the point is being chosen rather than in a
 * paragraph read once.
 *
 * One value, two ways in: pan, or type. Whichever was touched last is what the ray shows and what
 * gets saved, and a typed bearing carries **no** baseline length — it did not come from one.
 */
@Composable
fun ForwardAxisPicker(
    zero: PlatformZero,
    map: @Composable (anchor: LatLonAlt, bearingDeg: Double?, onCentre: (Double, Double) -> Unit, Modifier) -> Unit,
    onCancel: () -> Unit,
    onPick: (headingDeg: Double, baselineM: Double?) -> Unit,
) {
    val anchor = zero.point()
    // Where the crosshair is, which is only known once the map has reported. Null until then, so the
    // readout says what it is waiting for rather than showing a bearing to nowhere.
    var centre by remember { mutableStateOf<LatLonAlt?>(null) }
    var typed by remember { mutableStateOf<String?>(null) }

    // **Only once the crosshair is far enough from the zero to mean anything.** The map opens
    // centred on the zero, so without this the screen's first frame reports a zero-length baseline —
    // a bearing `atan2` returns from two identical points, dressed up as a measurement, beside an
    // uncertainty of eighty-odd degrees. Seen on the phone: `324° true · 0 m from the zero`.
    val fromMap = centre
        ?.let { headingFromBaseline(anchor, it) }
        ?.takeIf { it.lengthM >= MIN_BASELINE_M }
    val typedValue = typed?.trim()?.toDoubleOrNull()
    val typedValid = typed == null || (typedValue != null && typedValue in 0.0..360.0)

    // Last touched wins. `typed` is set to null the moment the map moves, so panning after typing
    // goes back to the map's answer rather than leaving two values on screen disagreeing.
    val picked = if (typed != null) typedValue else fromMap?.bearingDegrees
    val baseline = if (typed != null) null else fromMap?.lengthM
    // The ray shows what *would* be saved, falling back to what already is — so the axis is drawn
    // from the first frame rather than appearing only once somebody has panned far enough.
    val heading = picked ?: zero.headingDeg

    ScreenScaffold(
        title = "Set the forward axis",
        onBack = onCancel,
        bottomBar = {
            FormActions(
                saveLabel = "Use this bearing",
                onSave = { picked?.let { onPick(it, baseline) } },
                // Nothing to save until something has actually been chosen: confirming the ray as it
                // opened would rewrite a compass heading as a typed one and lose how it was got.
                saveEnabled = picked != null && typedValid,
                onCancel = onCancel,
                hint = "Point at something ahead on the centreline — the further the better.",
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                map(
                    anchor,
                    heading,
                    { lat, lon ->
                        centre = LatLonAlt(lat, lon, 0.0)
                        typed = null
                    },
                    Modifier.fillMaxSize(),
                )
                Icon(
                    imageVector = IconMyLocation,
                    contentDescription = "The point the forward axis runs to",
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(40.dp),
                )
            }
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${formatBearing(heading.toFloat())}° true" + if (picked == null) " · unchanged" else "",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    // The whole "walk further" instruction as a figure, at the moment it applies.
                    // Adjectives were tried elsewhere in this app and lost: `~241 MB/h` beats "much
                    // larger files", and `13 dB` beats "Good".
                    when {
                        baseline != null ->
                            "${baseline.roundToInt()} m from the zero · a metre of error here is " +
                                "about ${"%.1f".fmt(degreesPerMetreOfError(baseline))}°"
                        typed != null ->
                            "A typed bearing records no baseline — nothing was measured over a distance."
                        else ->
                            "Move the crosshair at least ${MIN_BASELINE_M.roundToInt()} m along the " +
                                "centreline. A bearing taken over less than that is mostly error."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = typed ?: "",
                    onValueChange = { typed = it },
                    label = { Text("Or type a true bearing (degrees)") },
                    isError = !typedValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "The line runs from the zero point in the direction that will be saved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The shortest baseline this screen will accept.
 *
 * Not a round number for its own sake: at five metres a metre of position error is about eleven
 * degrees, which is already poor and is roughly where a reading stops being worth writing down.
 * Below it the bearing is not merely imprecise but unstable — at zero separation `atan2` answers
 * from two identical points, which is what this screen reported as `324° true` before the floor
 * existed.
 */
private const val MIN_BASELINE_M = 5.0

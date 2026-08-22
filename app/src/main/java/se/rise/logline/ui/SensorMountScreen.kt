package se.rise.logline.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import se.rise.logline.calibrate.CaptureMethod
import se.rise.logline.calibrate.EulerDeg
import se.rise.logline.calibrate.SensorMount
import se.rise.logline.calibrate.SensorType
import se.rise.logline.calibrate.Vec3M
import se.rise.logline.calibrate.defaultFrameId
import se.rise.logline.ui.components.ConfirmDialog
import se.rise.logline.ui.components.FormActions
import se.rise.logline.ui.components.InfoDialog
import se.rise.logline.ui.components.ScreenScaffold
import se.rise.logline.ui.components.SectionHeader
import se.rise.logline.ui.components.StatusLine
import se.rise.logline.ui.components.StatusTone

/**
 * One captured offset, handed back from `MainActivity.App()` when a capture finishes.
 *
 * [token] increments on every capture so the screen re-seeds its fields even when the operator stands
 * in the same place twice and the numbers come back identical.
 */
data class CapturedOffset(
    val translation: Vec3M,
    val accuracyM: Double?,
    val atEpochMillis: Long,
    val token: Int,
)

/**
 * One sensor's pose on the platform.
 *
 * Translation can be captured — walk the phone to the sensor and the offset falls out of two positions
 * and the platform's heading — or typed, which for anything under a few metres is the only honest option.
 * **Rotation is always typed.** A phone held against a radar can measure where the radar is; it cannot
 * measure where the radar is looking, and a capture button for it would be inventing a measurement.
 *
 * The six numbers are local text state rather than bound straight to the model, because a partially
 * typed number ("-", "0.") is not a `Double` and a model that only holds `Double` would delete each
 * character as it was typed.
 */
@Composable
fun SensorMountScreen(
    platformName: String,
    initial: SensorMount?,
    hasZero: Boolean,
    capture: CaptureState,
    captured: CapturedOffset?,
    onCapture: () -> Unit,
    onSave: (SensorMount) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label.orEmpty()) }
    var frameId by remember { mutableStateOf(initial?.frameId.orEmpty()) }
    var type by remember { mutableStateOf(initial?.sensorType ?: SensorType.OTHER) }
    var x by remember { mutableStateOf(initial?.translation?.x?.toString() ?: "0.0") }
    var y by remember { mutableStateOf(initial?.translation?.y?.toString() ?: "0.0") }
    var z by remember { mutableStateOf(initial?.translation?.z?.toString() ?: "0.0") }
    var yaw by remember { mutableStateOf(initial?.rotation?.yaw?.toString() ?: "0.0") }
    var pitch by remember { mutableStateOf(initial?.rotation?.pitch?.toString() ?: "0.0") }
    var roll by remember { mutableStateOf(initial?.rotation?.roll?.toString() ?: "0.0") }
    var method by remember { mutableStateOf(initial?.capture ?: CaptureMethod.MANUAL) }
    var accuracyM by remember { mutableStateOf(initial?.accuracyM) }
    var capturedAt by remember { mutableLongStateOf(initial?.capturedAtEpochMillis ?: 0L) }
    var confirmDelete by remember { mutableStateOf(false) }

    // A capture that has landed replaces the three translation fields and nothing else — the label,
    // the type and every rotation the operator typed stay exactly as they were.
    LaunchedEffect(captured?.token) {
        captured?.let {
            x = "%.3f".format(it.translation.x)
            y = "%.3f".format(it.translation.y)
            z = "%.3f".format(it.translation.z)
            method = CaptureMethod.GNSS_AVERAGE
            accuracyM = it.accuracyM
            capturedAt = it.atEpochMillis
        }
    }

    val translation = Vec3M(
        x = x.toDoubleOrNull() ?: 0.0,
        y = y.toDoubleOrNull() ?: 0.0,
        z = z.toDoubleOrNull() ?: 0.0,
    )
    val numbersParse = listOf(x, y, z, yaw, pitch, roll).all { it.toDoubleOrNull() != null }
    val effectiveFrameId = frameId.ifBlank { defaultFrameId(platformName, label) }
    val valid = label.isNotBlank() && numbersParse

    var info by remember { mutableStateOf<Pair<String, String>?>(null) }

    info?.let { (title, body) ->
        InfoDialog(title = title, body = body, onDismiss = { info = null })
    }

    ScreenScaffold(
        title = if (initial == null) "Add sensor" else "Sensor",
        onBack = onCancel,
        bottomBar = {
            FormActions(
                onSave = {
                    onSave(
                        SensorMount(
                            label = label.trim(),
                            frameId = effectiveFrameId,
                            sensorType = type,
                            translation = translation,
                            rotation = EulerDeg(
                                yaw = yaw.toDoubleOrNull() ?: 0.0,
                                pitch = pitch.toDoubleOrNull() ?: 0.0,
                                roll = roll.toDoubleOrNull() ?: 0.0,
                            ),
                            capture = method,
                            accuracyM = accuracyM,
                            capturedAtEpochMillis = capturedAt,
                        )
                    )
                },
                onCancel = onCancel,
                saveEnabled = valid,
                hint = when {
                    label.isBlank() -> "Name the sensor — it becomes its frame id."
                    !numbersParse -> "Every offset and angle has to be a number."
                    else -> "Published as $effectiveFrameId."
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader("What it is")
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Name") },
                supportingText = { Text("e.g. Ouster OS lidar. Becomes $effectiveFrameId on the wire.") },
                isError = label.isBlank(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SensorType.entries.take(3).forEach { option ->
                    FilterChip(
                        selected = type == option,
                        onClick = { type = option },
                        label = { Text(option.label) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SensorType.entries.drop(3).forEach { option ->
                    FilterChip(
                        selected = type == option,
                        onClick = { type = option },
                        label = { Text(option.label) },
                    )
                }
            }
            OutlinedTextField(
                value = frameId,
                onValueChange = { frameId = it },
                label = { Text("Frame id (optional)") },
                supportingText = { Text("Left empty it follows the name.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader(
                "Where it is",
                onInfo = {
                    info = "Where it is" to
                        "Metres from the platform's zero point: x forward, y to starboard, z DOWN — " +
                        "maritime convention, not robotics.\n\n" +
                        "Capture measures the offset by walking to the sensor, which needs a zero " +
                        "point first. A tape measure beats a phone at decimetre scale."
                },
            )
            // Kept: the field labels carry the directions, but nothing else carries the *sign*.
            Text(
                "A sensor up a mast has a negative z.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Forward (x)", x, { x = it }, Modifier.weight(1f))
                NumberField("Starboard (y)", y, { y = it }, Modifier.weight(1f))
                NumberField("Down (z)", z, { z = it }, Modifier.weight(1f))
            }
            CaptureRow(capture)
            OutlinedButton(
                onClick = onCapture,
                enabled = hasZero && capture !is CaptureState.Running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Capture from where I am standing") }
            if (!hasZero) {
                StatusLine(
                    text = "No zero point yet",
                    tone = StatusTone.Neutral,
                    detail = "Capture the platform's zero first, or type the offsets — a tape measure beats " +
                        "a phone fix for anything under about five metres anyway.",
                )
            }
            accuracyM?.let {
                val exceeds = it > translation.magnitude()
                StatusLine(
                    text = "Captured with a ±${"%.1f".format(it)} m fix",
                    tone = if (exceeds) StatusTone.Error else StatusTone.Neutral,
                    detail = if (exceeds) {
                        "That is larger than the offset itself, so these numbers are mostly GNSS " +
                            "noise. Measure this one with a tape."
                    } else {
                        null
                    },
                )
            }

            SectionHeader(
                "Which way it points",
                onInfo = {
                    info = "Which way it points" to
                        "Degrees, applied yaw, then pitch, then roll.\n\n" +
                        "Typed, always — and that is why there is no capture button here. A phone can " +
                        "measure where a sensor is by being carried to it; it cannot measure where the " +
                        "sensor is aimed."
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Yaw", yaw, { yaw = it }, Modifier.weight(1f))
                NumberField("Pitch", pitch, { pitch = it }, Modifier.weight(1f))
                NumberField("Roll", roll, { roll = it }, Modifier.weight(1f))
            }

            onDelete?.let {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove this sensor")
                }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = "Remove ${label.ifBlank { "this sensor" }}?",
            body = "It stops being published with the rest of the platform's geometry.",
            confirmLabel = "Remove",
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = value.toDoubleOrNull() == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier,
    )
}

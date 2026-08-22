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
import se.rise.logline.calibrate.SensorAttitudeReading
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
 * A rotation measured from the phone's own attitude, waiting to land in the three angle fields.
 *
 * [token] rather than equality, the same as [CapturedOffset]: measuring the same sensor twice
 * produces an identical reading, and a `LaunchedEffect` keyed on the value would not re-run — so the
 * second capture would appear to do nothing after the operator had deliberately edited a field.
 */
data class CapturedRotation(
    val reading: SensorAttitudeReading,
    val token: Int,
)

/**
 * Past this the compass's own estimate is too loose to steer by, and the yaw it produced is called out
 * in the error colour.
 *
 * Fifteen degrees because that is roughly where a heading stops being useful for pointing anything: a
 * radar bearing wrong by that much is a different vessel. A Pixel 6 well away from metal reports 3-5°,
 * and beside a steel rail it reports 25° and upwards, so the threshold sits in a gap rather than
 * through the middle of the distribution.
 */
private const val YAW_SUSPECT_DEGREES = 15.0

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
    /**
     * Which way the platform's bow points, true — `PlatformZero.headingDeg`.
     *
     * Null when no heading has been established, and then a rotation cannot be measured at all: yaw is
     * relative to the bow, so without it there is nothing to be relative *to*. The same gate
     * [hasZero] puts in front of the offset capture, for a different missing thing.
     */
    platformHeadingDeg: Double?,
    capture: CaptureState,
    captured: CapturedOffset?,
    onCapture: () -> Unit,
    /** A measured rotation that has landed, replacing the three angle fields and nothing else. */
    capturedRotation: CapturedRotation?,
    onCaptureRotation: () -> Unit,
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
    var rotationMethod by remember { mutableStateOf(initial?.rotationCapture ?: CaptureMethod.MANUAL) }
    var rotationAccuracyDeg by remember { mutableStateOf(initial?.rotationAccuracyDeg) }
    var rotationSpreadDeg by remember { mutableStateOf<Double?>(null) }
    var rotationLocked by remember { mutableStateOf(false) }
    var rotationMagneticOnly by remember { mutableStateOf(false) }
    var capturedAt by remember { mutableLongStateOf(initial?.capturedAtEpochMillis ?: 0L) }
    var confirmDelete by remember { mutableStateOf(false) }

    // A capture that has landed replaces the three translation fields and nothing else — the label,
    // the type and every rotation the operator typed stay exactly as they were.
    LaunchedEffect(captured?.token) {
        captured?.let {
            // `.fmt()`, never `format()`: the latter uses `Locale.getDefault()`, so on a Swedish
            // phone a captured 0.22 arrived in the field as `0,220`, which `toDoubleOrNull()` rejects
            // — the field went red and saving stored **0.0**, putting the sensor exactly on the
            // platform's origin. Nothing downstream can tell that from a real measurement.
            x = "%.3f".fmt(it.translation.x)
            y = "%.3f".fmt(it.translation.y)
            z = "%.3f".fmt(it.translation.z)
            method = CaptureMethod.GNSS_AVERAGE
            accuracyM = it.accuracyM
            capturedAt = it.atEpochMillis
        }
    }

    // The mirror of the offset capture above, and deliberately separate: the two measure different
    // things and the commonest survey is a captured position with a typed rotation.
    LaunchedEffect(capturedRotation?.token) {
        capturedRotation?.let {
            yaw = "%.1f".fmt(it.reading.rotation.yaw)
            pitch = "%.1f".fmt(it.reading.rotation.pitch)
            roll = "%.1f".fmt(it.reading.rotation.roll)
            rotationMethod = CaptureMethod.PHONE_ATTITUDE
            rotationAccuracyDeg = it.reading.compassAccuracyDegrees
            rotationSpreadDeg = it.reading.spreadDegrees
            rotationLocked = it.reading.gimbalLocked
            rotationMagneticOnly = it.reading.declinationDegrees == null
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
                            rotationCapture = rotationMethod,
                            rotationAccuracyDeg = rotationAccuracyDeg,
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
                    text = "Captured with a ±${"%.1f".fmt(it)} m fix",
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
                        "Degrees, applied yaw, then pitch, then roll. Yaw is positive swinging the " +
                        "sensor to starboard.\n\n" +
                        "Measuring lays the phone flat against the sensor's mounting face, screen up, " +
                        "top edge pointing the way the sensor faces, and reads its own attitude — the " +
                        "same posture the platform's compass heading uses.\n\n" +
                        "Pitch and roll come from gravity and are as good as anything aboard. Yaw " +
                        "comes from the magnetometer, which is exactly what a radar, a steel mast or " +
                        "a motor pulls out of true, so it is the one to check against a bearing you " +
                        "already know."
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Yaw", yaw, { yaw = it }, Modifier.weight(1f))
                NumberField("Pitch", pitch, { pitch = it }, Modifier.weight(1f))
                NumberField("Roll", roll, { roll = it }, Modifier.weight(1f))
            }
            CaptureRow(capture)
            // Instruction, not documentation: it is the only thing on the page saying which way up to
            // hold the phone, and a capture made face-down is wrong by 180° and looks fine.
            Text(
                "Lay the phone flat on the sensor, screen up, top edge the way it faces — with the " +
                    "platform level.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onCaptureRotation,
                enabled = platformHeadingDeg != null && capture !is CaptureState.Running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Measure with the phone") }
            if (platformHeadingDeg == null) {
                StatusLine(
                    text = "No heading for the platform yet",
                    tone = StatusTone.Neutral,
                    detail = "Yaw is measured from the bow, so the platform's forward direction has to " +
                        "be established first — on the Forward step. Pitch and roll can still be typed.",
                )
            }
            rotationSpreadDeg?.let { spread ->
                // Two different doubts, and the compass one is the only one worth colouring: a phone
                // held perfectly still next to a mast gives a tight spread around a wrong yaw.
                val poor = rotationAccuracyDeg != null && rotationAccuracyDeg!! > YAW_SUSPECT_DEGREES
                StatusLine(
                    text = rotationAccuracyDeg
                        ?.let { "Measured, compass ±${"%.0f".fmt(it)}°" }
                        ?: "Measured, compass accuracy not reported",
                    tone = if (poor) StatusTone.Error else StatusTone.Neutral,
                    detail = buildString {
                        append("Held to within ${"%.1f".fmt(spread)}°. ")
                        if (poor) {
                            append(
                                "That compass figure is too loose to trust for yaw — steel and motors " +
                                    "pull it. Sight the sensor against a bearing you already know, or " +
                                    "type the yaw. Pitch and roll are unaffected.",
                            )
                        } else {
                            append("Pitch and roll come from gravity; yaw is the one worth checking.")
                        }
                    },
                )
            }
            if (rotationMagneticOnly && rotationSpreadDeg != null) {
                StatusLine(
                    text = "Yaw is magnetic, not true",
                    tone = StatusTone.Error,
                    detail = "There is no position on the zero point, so the declination that turns a " +
                        "magnetic bearing into a true one cannot be computed — and the platform's own " +
                        "heading is recorded as true. Yaw is out by the local declination, which is " +
                        "about 6° in western Sweden and far more at high latitudes. Capture the zero " +
                        "point, or type the yaw.",
                )
            }
            if (rotationLocked) {
                StatusLine(
                    text = "Pointing straight up or down",
                    tone = StatusTone.Neutral,
                    detail = "There, yaw and roll turn about the same axis and only their sum is real. " +
                        "Roll is reported as zero and the whole turn put into yaw — the orientation is " +
                        "right, but which of the two carries it is a choice rather than a measurement.",
                )
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

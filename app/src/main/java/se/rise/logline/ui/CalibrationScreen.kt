package se.rise.logline.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import se.rise.logline.calibrate.CaptureMethod
import se.rise.logline.calibrate.HeadingSource
import se.rise.logline.calibrate.PlatformType
import se.rise.logline.calibrate.RigCalibration
import se.rise.logline.calibrate.RigZero
import se.rise.logline.calibrate.SensorMount
import se.rise.logline.calibrate.defaultEntityId
import se.rise.logline.calibrate.defaultParentFrameId
import se.rise.logline.ui.components.ConfirmDialog
import se.rise.logline.ui.components.FormActions
import se.rise.logline.ui.components.InfoDialog
import se.rise.logline.ui.components.ScreenScaffold
import se.rise.logline.ui.components.SectionHeader
import se.rise.logline.ui.components.StatusLine
import se.rise.logline.ui.components.StatusTone
import se.rise.logline.ui.components.readAsOneItem
import kotlin.math.roundToInt

/**
 * What a capture is doing right now.
 *
 * A capture is a bounded act with a visible cost — somebody is standing still holding a phone against
 * a radar — so the screen shows how far through it is rather than a spinner. [Failed] carries the
 * reason: "no fix arrived" and "permission denied" want different things done about them.
 */
sealed interface CaptureState {
    data object Idle : CaptureState
    data class Running(val what: String, val samples: Int, val seconds: Int, val elapsed: Int) : CaptureState
    data class Failed(val message: String) : CaptureState
}

/** How long a position capture averages for: twenty seconds of 1 Hz fixes. */
const val CAPTURE_SECONDS = 20

/**
 * How long a compass capture averages for.
 *
 * Shorter than a position capture because the rotation vector arrives at 50 Hz — four seconds is two
 * hundred samples, and standing still holding a heading for longer than that is asking for a wobble
 * rather than averaging one out.
 */
const val HEADING_SECONDS = 4

/** The index that means "a sensor that does not exist yet". */
const val NEW_SENSOR = -1

/**
 * The rig calibration: where the rig's zero is, and where each sensor sits relative to it.
 *
 * Stateless, like every screen here — the working calibration lives in `MainActivity.App()`, because a
 * capture is a coroutine whose result has to survive a trip into the sensor editor and back.
 *
 * Beyond collecting numbers, this screen's job is to be **honest about what they are worth**. Every
 * captured value shows the fix accuracy that produced it, an offset smaller than its own accuracy is
 * called out in the error colour, and the heading always says which of the three ways established it.
 * A calibration that quietly presents a ±4 m fix as a 0.3 m mounting offset is worse than no
 * calibration at all, because everything downstream will believe it.
 */
@Composable
fun CalibrationScreen(
    calibration: RigCalibration,
    onChange: (RigCalibration) -> Unit,
    /** True while a run is going, which is when these subjects actually publish. */
    publishing: Boolean,
    /** The key `frame_transform` goes out on, so the wire name is visible where it is configured. */
    transformKey: String,
    capture: CaptureState,
    onCaptureZero: () -> Unit,
    onCaptureHeading: () -> Unit,
    onCaptureBaseline: () -> Unit,
    onEditSensor: (Int) -> Unit,
    onExport: () -> Unit,
    exportMessage: String?,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    dirty: Boolean,
) {
    var showFrameHelp by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var typedPosition by remember { mutableStateOf(false) }
    var typedHeading by remember { mutableStateOf(false) }

    // The only two fields on this screen parsed from free text, and nothing else writes them — so
    // local string state cannot be overwritten under the keyboard by an arriving capture.
    var loa by remember { mutableStateOf(calibration.lengthOverAllM?.toString().orEmpty()) }
    var boa by remember { mutableStateOf(calibration.breadthOverAllM?.toString().orEmpty()) }

    ScreenScaffold(
        title = "Rig calibration",
        onBack = onCancel,
        bottomBar = {
            FormActions(
                onSave = onSave,
                onCancel = onCancel,
                saveEnabled = dirty && calibration.name.isNotBlank() && calibration.entityId.isNotBlank(),
                hint = when {
                    calibration.name.isBlank() -> "Give the rig a name first."
                    !calibration.isPublishable -> "Add at least one sensor before this can publish."
                    else -> "Saving restarts publishing so the new geometry goes out."
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
            Text(
                "Describe a sensor rig once, with this phone: mark the rig's zero point, then place " +
                    "each sensor relative to it. The result publishes as frame_transform and " +
                    "configuration_json, and exports as a file keelson's platform connector reads.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- the rig ------------------------------------------------------------------------
            SectionHeader("Rig", onInfo = { showFrameHelp = true })
            OutlinedTextField(
                value = calibration.name,
                onValueChange = { name ->
                    // The ids follow the name until somebody edits one of them by hand. Without this a
                    // rig named after the fact keeps the entity id of whatever it was called first.
                    val followedEntity = calibration.entityId == defaultEntityId(calibration.name)
                    val followedFrame = calibration.parentFrameId == defaultParentFrameId(calibration.name)
                    onChange(
                        calibration.copy(
                            name = name,
                            entityId = if (followedEntity) defaultEntityId(name) else calibration.entityId,
                            parentFrameId = if (followedFrame) {
                                defaultParentFrameId(name)
                            } else {
                                calibration.parentFrameId
                            },
                        )
                    )
                },
                label = { Text("Rig name") },
                supportingText = { Text("What the platform is called, e.g. SSRS18.") },
                isError = calibration.name.isBlank(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = calibration.entityId,
                onValueChange = { onChange(calibration.copy(entityId = it)) },
                label = { Text("Rig entity ID") },
                supportingText = {
                    Text(
                        "The geometry publishes under this rather than under the phone — it is the " +
                            "rig the data is about."
                    )
                },
                isError = calibration.entityId.isBlank(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = calibration.description,
                onValueChange = { onChange(calibration.copy(description = it)) },
                label = { Text("Description") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlatformType.entries.forEach { type ->
                    FilterChip(
                        selected = calibration.platformType == type,
                        onClick = {
                            onChange(
                                calibration.copy(
                                    platformType = if (calibration.platformType == type) null else type
                                )
                            )
                        },
                        label = { Text(type.wire) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = loa,
                    onValueChange = {
                        loa = it
                        onChange(calibration.copy(lengthOverAllM = it.toDoubleOrNull()))
                    },
                    label = { Text("Length overall") },
                    suffix = { Text("m") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = boa,
                    onValueChange = {
                        boa = it
                        onChange(calibration.copy(breadthOverAllM = it.toDoubleOrNull()))
                    },
                    label = { Text("Breadth") },
                    suffix = { Text("m") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            // ---- the zero -----------------------------------------------------------------------
            SectionHeader(
                "Zero point",
                trailing = if (calibration.zero?.hasPosition == true) "set" else null,
            )
            Text(
                "Stand at the rig's reference point and capture. Every sensor offset is measured from " +
                    "here. A rig measured entirely with a tape needs no position at all.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ZeroCard(calibration.zero)
            CaptureRow(capture)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCaptureZero,
                    enabled = capture !is CaptureState.Running,
                    modifier = Modifier.weight(1f),
                ) { Text("Capture position") }
                OutlinedButton(onClick = { typedPosition = true }, modifier = Modifier.weight(1f)) {
                    Text("Type position")
                }
            }

            SectionHeader(
                "Forward axis",
                trailing = calibration.zero?.let {
                    "${it.headingDeg.roundToInt()}° ${it.headingSource.label.lowercase()}"
                },
            )
            Text(
                "Which way the rig's +X points, true. Baseline: capture the zero, then a point ahead " +
                    "on the centreline. Compass: hold the phone flat, screen up, top edge forward.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCaptureBaseline,
                    enabled = calibration.zero != null && capture !is CaptureState.Running,
                    modifier = Modifier.weight(1f),
                ) { Text("Baseline") }
                OutlinedButton(
                    onClick = onCaptureHeading,
                    enabled = capture !is CaptureState.Running,
                    modifier = Modifier.weight(1f),
                ) { Text("Compass") }
                OutlinedButton(onClick = { typedHeading = true }, modifier = Modifier.weight(1f)) {
                    Text("Type")
                }
            }

            // ---- the sensors --------------------------------------------------------------------
            SectionHeader("Sensors", trailing = "${calibration.sensors.size}")
            if (calibration.sensors.isEmpty()) {
                Text(
                    "No sensors yet. Nothing publishes until there is at least one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            calibration.sensors.forEachIndexed { index, mount ->
                MountRow(mount) { onEditSensor(index) }
            }
            OutlinedButton(onClick = { onEditSensor(NEW_SENSOR) }, modifier = Modifier.fillMaxWidth()) {
                Text("Add sensor")
            }

            // ---- what becomes of it -------------------------------------------------------------
            SectionHeader("On the bus")
            StatusLine(
                text = if (publishing && calibration.isPublishable) {
                    "Publishing"
                } else {
                    "Publishes with the next run"
                },
                tone = if (publishing && calibration.isPublishable) {
                    StatusTone.Positive
                } else {
                    StatusTone.Neutral
                },
                detail = "$transformKey\nEvery ten seconds: one frame_transform per sensor, the whole " +
                    "document on configuration_json, and — once a position has been captured — the " +
                    "zero point itself on location_fix, stamped with the time it was surveyed.",
            )
            OutlinedButton(
                onClick = onExport,
                enabled = calibration.isPublishable,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Export platform-geometry JSON") }
            exportMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete this calibration")
            }
        }
    }

    if (showFrameHelp) {
        InfoDialog(
            title = "The rig frame",
            body = "X is positive forward, Y is positive to starboard, and Z is positive DOWN — " +
                "maritime convention, not robotics. A sensor three metres up the mast has z = -3.\n\n" +
                "Rotations are degrees, applied yaw, then pitch, then roll. Yaw is positive swinging " +
                "the sensor to starboard.\n\n" +
                "Everything is measured from the zero point, and keelson's CCRP — its Consistent " +
                "Common Reference Point — sits at that origin unless you say otherwise.",
            onDismiss = { showFrameHelp = false },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "Delete this calibration?",
            body = "The rig, its zero point and all ${calibration.sensors.size} sensors are removed " +
                "from this phone. Anything already exported or published stays where it is.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmClear = false
                onClear()
            },
            onDismiss = { confirmClear = false },
        )
    }
    if (typedPosition) {
        TypedPositionDialog(
            zero = calibration.zero,
            onDismiss = { typedPosition = false },
            onConfirm = { zero ->
                typedPosition = false
                onChange(calibration.copy(zero = zero))
            },
        )
    }
    if (typedHeading) {
        TypedHeadingDialog(
            initial = calibration.zero?.headingDeg,
            onDismiss = { typedHeading = false },
            onConfirm = { heading ->
                typedHeading = false
                val zero = calibration.zero
                onChange(
                    calibration.copy(
                        zero = zero?.copy(headingDeg = heading, headingSource = HeadingSource.MANUAL)
                            ?: typedOnlyHeading(heading),
                    )
                )
            },
        )
    }
}

/**
 * A heading typed before any position was captured.
 *
 * The forward axis is meaningful on its own — it is what turns "10 m north" into "10 m ahead" — so a
 * typed heading is kept rather than discarded for want of a position. Latitude and longitude of zero
 * would be the Gulf of Guinea, so the capture method says plainly that nothing was measured.
 */
private fun typedOnlyHeading(heading: Double) = RigZero(
    latitude = 0.0,
    longitude = 0.0,
    altitudeM = null,
    accuracyM = null,
    scatterM = null,
    headingDeg = heading,
    headingSource = HeadingSource.MANUAL,
    capture = CaptureMethod.MANUAL,
    samples = 0,
    capturedAtEpochMillis = 0L,
)

@Composable
private fun ZeroCard(zero: RigZero?) {
    // A heading typed before anything was captured is stored as a zero with no position — see
    // [RigZero.hasPosition]. Drawing it as a position would put the rig at 0°N 0°E in the Gulf of
    // Guinea, which is the most confident possible way of being wrong.
    if (zero == null || !zero.hasPosition) {
        StatusLine(
            text = "No zero point",
            tone = StatusTone.Neutral,
            detail = "Capture one to place sensors by walking to them. Typed offsets do not need it.",
        )
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                formatPosition(zero.latitude, zero.longitude),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                buildString {
                    append(zero.capture.label)
                    zero.altitudeM?.let { append(" · ${"%.1f".format(it)} m altitude") }
                    if (zero.samples > 0) append(" · ${zero.samples} samples")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Two different numbers, shown as two: scatter is how repeatable the capture was, accuracy
            // is what the platform thinks it is worth against the truth. They are routinely far apart.
            Text(
                buildString {
                    zero.accuracyM?.let { append("±${"%.1f".format(it)} m accuracy") }
                    zero.verticalAccuracyM?.let { append(" · ±${"%.1f".format(it)} m vertical") }
                    zero.scatterM?.let {
                        if (isNotEmpty()) append(" · ")
                        append("${"%.2f".format(it)} m scatter")
                    }
                    if (isEmpty()) append("No accuracy reported")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Said here rather than only in the docs: this position goes on the bus, and anyone
            // capturing one should know that before they do it.
            Text(
                "Published on location_fix under the rig, stamped with the time it was surveyed — " +
                    "not a live position.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shared with the sensor editor, which runs the same capture for an offset. */
@Composable
internal fun CaptureRow(capture: CaptureState) {
    when (capture) {
        is CaptureState.Idle -> Unit
        is CaptureState.Running -> Column(Modifier.fillMaxWidth()) {
            Text(
                "${capture.what}… ${capture.samples} samples",
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { (capture.elapsed.toFloat() / capture.seconds).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
        is CaptureState.Failed -> StatusLine(text = capture.message, tone = StatusTone.Error)
    }
}

@Composable
private fun MountRow(mount: SensorMount, onClick: () -> Unit) {
    val offset = "x ${"%.2f".format(mount.translation.x)}  " +
        "y ${"%.2f".format(mount.translation.y)}  " +
        "z ${"%.2f".format(mount.translation.z)} m"
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .readAsOneItem("${mount.label}, ${mount.sensorType.label}, $offset")
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    mount.label.ifBlank { mount.frameId },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    offset,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (mount.accuracyExceedsOffset) {
                    // The whole reason the accuracy is kept: this offset is smaller than the fix that
                    // produced it, so it is noise wearing a measurement's clothes.
                    Text(
                        "Fix was ±${"%.1f".format(mount.accuracyM)} m — larger than this offset",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                mount.sensorType.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

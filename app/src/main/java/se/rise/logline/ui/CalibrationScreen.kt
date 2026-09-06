package se.rise.logline.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import se.rise.logline.calibrate.zeroFromMap
import se.rise.logline.calibrate.LatLonAlt
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import se.rise.logline.calibrate.PlatformCalibration
import se.rise.logline.calibrate.PlatformZero
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
 * The platform calibration: where the platform's zero is, and where each sensor sits relative to it.
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
    calibration: PlatformCalibration,
    onChange: (PlatformCalibration) -> Unit,
    /** True while a run is going, which is when these subjects actually publish. */
    publishing: Boolean,
    /** The key `frame_transform` goes out on, so the wire name is visible where it is configured. */
    transformKey: String,
    capture: CaptureState,
    onCaptureZero: () -> Unit,
    onCaptureHeading: () -> Unit,
    onCaptureBaseline: () -> Unit,
    onEditSensor: (Int) -> Unit,
    /**
     * The picture of this platform, stored or just picked.
     *
     * Not part of [calibration] on purpose: that value is the platform *document*, and a photograph
     * belongs to neither the export nor the wire. See `PlatformPhotos`.
     */
    photo: PlatformPhotoSource?,
    onPickPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    /**
     * Why the camera cannot be used right now, if it cannot.
     *
     * Non-null disables **taking** a photo and says so; choosing one from the gallery is unaffected,
     * because that needs no camera. State, so it stays on the page.
     */
    cameraBusyReason: String?,
    onRemovePhoto: () -> Unit,
    /** Why the last pick produced no picture. State, so it stays on the page rather than in the ⓘ. */
    photoError: String? = null,
    onExport: () -> Unit,
    exportMessage: String?,
    /**
     * Why this entity id cannot be used, if it cannot.
     *
     * Only ever a collision with another platform in the library. It blocks Save rather than warning:
     * every key this platform publishes on is built from the id, so two platforms sharing one would put two
     * platforms' geometry on the same three keys and neither would be readable.
     */
    entityIdError: String? = null,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    dirty: Boolean,
    /**
     * Which step is showing, 0..4.
     *
     * Hoisted into `MainActivity.App()` rather than remembered here, because editing a sensor is a
     * *push* onto another destination and back — a `remember` in this composable dies with it, and
     * coming back would land on step 1 having just added a sensor on step 4.
     */
    step: Int,
    onStepChange: (Int) -> Unit,
    /**
     * The map somebody pans under a crosshair to put the zero point down, supplied by
     * `MainActivity` — a `MapView` needs a `Context`, a tile cache and a lifecycle, none of which a
     * screen may hold. The same slot `LiveScreen` and `RecordingDetailScreen` take their maps
     * through.
     */
    pickerMap: @Composable (
        start: LatLonAlt?,
        existing: LatLonAlt?,
        onCentre: (Double, Double) -> Unit,
        Modifier,
    ) -> Unit,
    /** The same map, still and untouchable, for the card. */
    previewMap: @Composable (at: LatLonAlt, Modifier) -> Unit,
) {
    var showFrameHelp by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    // Full screen rather than in the step, because the step is a scrolling column: an interactive
    // map inside one loses every drag to the page, which is written down where the recording chart
    // made the same choice. `rememberSaveable` so a rotation does not drop somebody out of it.
    var picking by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var typedPosition by remember { mutableStateOf(false) }
    var typedHeading by remember { mutableStateOf(false) }

    // The only two fields on this screen parsed from free text, and nothing else writes them — so
    // local string state cannot be overwritten under the keyboard by an arriving capture.
    var loa by remember { mutableStateOf(calibration.lengthOverAllM?.toString().orEmpty()) }
    var boa by remember { mutableStateOf(calibration.breadthOverAllM?.toString().orEmpty()) }

    // The one form screen that had no guard on it: a system-back with a half-surveyed platform in the
    // draft discarded it silently. Same `leave()` shape as `SettingsScreen`, and it matters more here
    // — twenty seconds of standing still at a point is not something to lose to a stray gesture.
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Back closes the picker before it considers leaving the editor. Without the ordering, backing
    // out of a map would discard a half-surveyed platform, which is what `leave()` exists to prevent.
    val leave = {
        when {
            picking -> picking = false
            dirty -> confirmDiscard = true
            else -> onCancel()
        }
    }
    BackHandler(enabled = true) { leave() }

    if (picking) {
        ZeroPositionPicker(
            zero = calibration.zero,
            map = pickerMap,
            onCancel = { picking = false },
            onPick = { latitude, longitude, accuracyM ->
                picking = false
                onChange(
                    calibration.copy(
                        zero = zeroFromMap(
                            latitude = latitude,
                            longitude = longitude,
                            accuracyM = accuracyM,
                            previous = calibration.zero,
                            atEpochMillis = System.currentTimeMillis(),
                        )
                    )
                )
            },
        )
        return
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            body = "This platform has been edited and not saved.",
            confirmLabel = "Discard",
            onConfirm = {
                confirmDiscard = false
                onCancel()
            },
            onDismiss = { confirmDiscard = false },
        )
    }

    ScreenScaffold(
        title = "Platform calibration",
        onBack = leave,
        bottomBar = {
            FormActions(
                onSave = onSave,
                onCancel = leave,
                saveEnabled = dirty && calibration.name.isNotBlank() &&
                    calibration.entityId.isNotBlank() && entityIdError == null,
                hint = when {
                    entityIdError != null -> entityIdError
                    calibration.name.isBlank() -> "Give the platform a name first."
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
            StepRail(
                step = step,
                calibration = calibration,
                // A capture is a bounded act with somebody standing still holding the
                // phone at a point. Wandering off to another step mid-way is how the
                // twenty seconds get wasted, so the rail is closed while one runs.
                enabled = capture !is CaptureState.Running,
                onStepChange = onStepChange,
            )

            when (step) {
                0 -> {
                    SectionHeader("Platform", onInfo = { showFrameHelp = true })
                    OutlinedTextField(
                        value = calibration.name,
                        onValueChange = { name ->
                            // The ids follow the name until somebody edits one of them by hand. Without this a
                            // platform named after the fact keeps the entity id of whatever it was called first.
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
                        label = { Text("Platform name") },
                        supportingText = { Text("What the platform is called, e.g. Sealog.") },
                        isError = calibration.name.isBlank(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = calibration.entityId,
                        onValueChange = { onChange(calibration.copy(entityId = it)) },
                        label = { Text("Platform entity ID") },
                        supportingText = {
                            Text(
                                entityIdError
                                    ?: ("The geometry publishes under this rather than under the phone — it " +
                                        "is the platform the data is about.")
                            )
                        },
                        isError = calibration.entityId.isBlank() || entityIdError != null,
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

                    // Last in the step because it is the only thing here that is not a measurement —
                    // and first in the list screen, because it is what tells two platforms apart at a
                    // glance where `sealog-1` and `sealog-2` do not.
                    Text("Photo", style = MaterialTheme.typography.titleSmall)
                    PlatformPhoto(
                        photo,
                        contentDescription = photo?.let {
                            "Photo of ${calibration.name.ifBlank { calibration.entityId }}"
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    // Two buttons rather than one behind a chooser: both are one tap, and a dialog
                    // whose only job is to offer the camera is a step that exists to be dismissed.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onTakePhoto, enabled = cameraBusyReason == null) {
                            Text("Take photo")
                        }
                        OutlinedButton(onClick = onPickPhoto) { Text("Choose photo") }
                        if (photo != null) {
                            TextButton(onClick = onRemovePhoto) { Text("Remove") }
                        }
                    }
                    cameraBusyReason?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        // Consequence, so it stays on the page rather than moving behind the ⓘ: it
                        // says where a picture of somebody's boat does and does not go.
                        photoError
                            ?: ("Kept on this phone. A photo is not part of the geometry document, so " +
                                "it is neither published nor written into an export."),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (photoError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                1 -> {
                    SectionHeader(
                        "Zero point",
                        trailing = if (calibration.zero?.hasPosition == true) "set" else null,
                        onInfo = {
                            info = "Zero point" to
                                "Every sensor offset is measured from here, so this is the platform's " +
                                "origin rather than a position report.\n\n" +
                                "A platform measured entirely with a tape needs no position at all — the " +
                                "offsets are what matter, and a captured zero only lets you place " +
                                "sensors by walking to them.\n\n" +
                                "Averaging reduces scatter, not bias: multipath holds still for " +
                                "minutes, so an offset smaller than the fix accuracy is noise."
                        },
                    )
                    // The instruction stays — it is the only thing saying where to stand. The
                    // reasoning behind it is now one tap away.
                    Text(
                        "Stand at the platform's reference point and capture.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ZeroCard(
                        zero = calibration.zero,
                        previewMap = previewMap,
                        onOpenMap = { picking = true },
                    )
                    CaptureRow(capture)
                    // **One word each, and each of them is the method's own name.** Three equal
                    // thirds of a 411dp screen leave about 73dp of text, so "Capture position" and
                    // "Pick on map" both wrapped while "Type" sat on one line — three buttons of the
                    // same width reading as three different shapes.
                    //
                    // The words are not shortened arbitrarily: they are what `CaptureMethod` already
                    // calls these, and what the card above prints back. Set the zero from the map and
                    // it reads `Map · 41.8 m altitude`, so the button that did it says `Map`.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onCaptureZero,
                            enabled = capture !is CaptureState.Running,
                            modifier = Modifier.weight(1f),
                        ) { Text("Capture", maxLines = 1) }
                        OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) {
                            Text("Map", maxLines = 1)
                        }
                        OutlinedButton(onClick = { typedPosition = true }, modifier = Modifier.weight(1f)) {
                            Text("Type", maxLines = 1)
                        }
                    }
                }
                2 -> {
                    SectionHeader(
                        "Forward axis",
                        trailing = calibration.zero?.let {
                            "${it.headingDeg.roundToInt()}° ${it.headingSource.label.lowercase()}"
                        },
                    )
                    Text(
                        "Which way the platform's +X points, true. Baseline: capture the zero, then a point ahead " +
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
                    // Said rather than left to a greyed button: a baseline is measured *from* the zero
                    // point, so without one there is nothing to measure from. This gap predates the
                    // move above and hiding the surrounding prose would only have deepened it.
                    if (calibration.zero == null) {
                        Text(
                            "Baseline needs a zero point first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                3 -> {
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
                }
                else -> {
                    SectionHeader(
                        "On the bus",
                        onInfo = {
                            info = "On the bus" to
                                "Every ten seconds while a run is going: one frame_transform per " +
                                "sensor, the whole document on configuration_json, and — once a " +
                                "position has been captured — the zero point itself on location_fix, " +
                                "stamped with the time it was surveyed rather than the time it was " +
                                "sent.\n\n" +
                                "All of a platform's transforms share one key, with the sensor named " +
                                "inside the message, which is why they are republished on a loop " +
                                "rather than once."
                        },
                    )
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
                        // The key stays: somebody writing a subscriber has to be able to spell it.
                        // What travels on it, and how often, is behind the ⓘ.
                        detail = transformKey,
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

            StepNav(step = step, onStepChange = onStepChange)
        }
    }

    info?.let { (title, body) ->
        InfoDialog(title = title, body = body, onDismiss = { info = null })
    }
    if (showFrameHelp) {
        InfoDialog(
            title = "The platform frame",
            body = "Describe a platform once, with this phone: mark the platform's zero point, then " +
                "place each sensor relative to it. The result publishes as frame_transform and " +
                "configuration_json, and exports as a file keelson's platform connector reads.\n\n" +
                "X is positive forward, Y is positive to starboard, and Z is positive DOWN — " +
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
            // The photo is named only when there is one: a list that mentions things that are not
            // there teaches people to stop reading it before the one item that mattered.
            body = "The platform, its zero point" +
                (if (photo != null) ", its photo" else "") +
                " and all ${calibration.sensors.size} sensors are removed from this phone. " +
                "Anything already exported or published stays where it is.",
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
private fun typedOnlyHeading(heading: Double) = PlatformZero(
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

/** The five steps, in order. The rail and the `when` in the body read from this one list. */
private val CALIBRATION_STEPS = listOf("Platform", "Zero", "Forward", "Sensors", "Review")

/**
 * Where you are in the survey, and a way to any other part of it.
 *
 * Deliberately navigation rather than a gate: every step is reachable at any time, in any order, for
 * a platform being described for the first time and for one being corrected two months later. A wizard
 * that made somebody walk five screens to fix a typo in a name would be worse than the single long
 * form this replaced. [StepNav] below is the obvious path for a first survey; it never blocks.
 *
 * The tick is *completeness*, not validity — a step with nothing in it yet reads as undone, which is
 * the one thing a person coming back to a half-finished platform wants to know.
 */
@Composable
private fun StepRail(
    step: Int,
    calibration: PlatformCalibration,
    enabled: Boolean,
    onStepChange: (Int) -> Unit,
) {
    val done = listOf(
        calibration.name.isNotBlank() && calibration.entityId.isNotBlank(),
        calibration.zero?.hasPosition == true,
        calibration.zero != null,
        calibration.sensors.isNotEmpty(),
        calibration.isPublishable,
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CALIBRATION_STEPS.forEachIndexed { index, label ->
            FilterChip(
                selected = index == step,
                onClick = { onStepChange(index) },
                enabled = enabled,
                label = { Text(if (done[index]) "$label ✓" else label) },
            )
        }
    }
}

/** Back and Next, for somebody working through a platform for the first time. */
@Composable
private fun StepNav(step: Int, onStepChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (step > 0) {
            OutlinedButton(
                onClick = { onStepChange(step - 1) },
                modifier = Modifier.weight(1f),
            ) { Text("Back") }
        }
        if (step < CALIBRATION_STEPS.lastIndex) {
            Button(
                onClick = { onStepChange(step + 1) },
                modifier = Modifier.weight(1f),
            ) { Text("Next") }
        }
    }
}

@Composable
private fun ZeroCard(
    zero: PlatformZero?,
    previewMap: @Composable (LatLonAlt, Modifier) -> Unit,
    onOpenMap: () -> Unit,
) {
    // A heading typed before anything was captured is stored as a zero with no position — see
    // [PlatformZero.hasPosition]. Drawing it as a position would put the platform at 0°N 0°E in the Gulf of
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
        // Where it landed, which the three numbers below cannot show. Not interactive — it sits in a
        // scrolling column, and a map that fights the page for drags is worse than one that does not
        // try. Tapping opens the picker, which has the whole screen to pan in.
        previewMap(
            zero.point(),
            Modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT)
                .clickable(onClick = onOpenMap),
        )
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                formatPosition(zero.latitude, zero.longitude),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                buildString {
                    append(zero.capture.label)
                    zero.altitudeM?.let { append(" · ${"%.1f".fmt(it)} m altitude") }
                    if (zero.samples > 0) append(" · ${zero.samples} samples")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Two different numbers, shown as two: scatter is how repeatable the capture was, accuracy
            // is what the platform thinks it is worth against the truth. They are routinely far apart.
            Text(
                buildString {
                    zero.accuracyM?.let { append("±${"%.1f".fmt(it)} m accuracy") }
                    zero.verticalAccuracyM?.let { append(" · ±${"%.1f".fmt(it)} m vertical") }
                    zero.scatterM?.let {
                        if (isNotEmpty()) append(" · ")
                        append("${"%.2f".fmt(it)} m scatter")
                    }
                    if (isEmpty()) append("No accuracy reported")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Said here rather than only in the docs: this position goes on the bus, and anyone
            // capturing one should know that before they do it.
            Text(
                "Published on location_fix under the platform, stamped with the time it was surveyed — " +
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
    val offset = "x ${"%.2f".fmt(mount.translation.x)}  " +
        "y ${"%.2f".fmt(mount.translation.y)}  " +
        "z ${"%.2f".fmt(mount.translation.z)} m"
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
                        "Fix was ±${"%.1f".fmt(mount.accuracyM)} m — larger than this offset",
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

/**
 * Put the zero point down on a chart.
 *
 * **The crosshair does not move; the map does.** A tap-to-place would be quicker and is wrong here:
 * a fingertip covers about a boat's beam at working zoom, and the point being placed is the origin
 * every sensor offset is measured from. Panning under a fixed mark is how a chartplotter does it,
 * and it lets somebody see the exact spot they are choosing rather than the spot their thumb is on.
 *
 * Full screen with no scrolling parent, which is the whole reason this is a separate surface rather
 * than a card in the wizard — see the note where `picking` is declared.
 */
@Composable
private fun ZeroPositionPicker(
    zero: PlatformZero?,
    map: @Composable (LatLonAlt?, LatLonAlt?, (Double, Double) -> Unit, Modifier) -> Unit,
    onCancel: () -> Unit,
    onPick: (latitude: Double, longitude: Double, accuracyM: Double?) -> Unit,
) {
    val existing = zero?.takeIf { it.hasPosition }?.point()
    // Seeded from the existing zero so the readout says something before the first drag, and so
    // opening the picker on an already-placed zero and confirming immediately is a no-op rather than
    // a move to wherever the map happened to open.
    var latitude by remember { mutableStateOf(existing?.latitude) }
    var longitude by remember { mutableStateOf(existing?.longitude) }
    var accuracy by rememberSaveable { mutableStateOf("") }

    val typedAccuracy = accuracy.trim().toDoubleOrNull()
    val accuracyValid = accuracy.isBlank() || (typedAccuracy != null && typedAccuracy > 0.0)

    ScreenScaffold(
        title = "Pick the zero point",
        onBack = onCancel,
        bottomBar = {
            FormActions(
                saveLabel = "Use this position",
                onSave = {
                    val lat = latitude
                    val lon = longitude
                    if (lat != null && lon != null) onPick(lat, lon, typedAccuracy)
                },
                saveEnabled = latitude != null && longitude != null && accuracyValid,
                onCancel = onCancel,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                map(existing, existing, { lat, lon -> latitude = lat; longitude = lon }, Modifier.fillMaxSize())
                // Screen space, not a map overlay: the crosshair is fixed to the middle of the view
                // and needs no projection to know where that is.
                Icon(
                    imageVector = IconMyLocation,
                    contentDescription = "The position under the crosshair",
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(40.dp),
                )
            }
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    // `.fmt()` rather than `String.format`: the latter follows the phone's locale and
                    // would print `57,4359` on a Swedish phone. That is the bug `SensorMountScreen`
                    // shipped, and for the zero point it is worse than a mis-placed sensor — a value
                    // that fails to parse back becomes 0.0, which reads as *no position at all*.
                    latitude?.let { lat -> longitude?.let { lon -> formatPosition(lat, lon) } }
                        ?: "Move the map to choose a position",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (existing != null) {
                    Text(
                        "The dot is where the zero point is now.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = accuracy,
                    onValueChange = { accuracy = it },
                    label = { Text("Accuracy (optional, metres)") },
                    isError = !accuracyValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    // Consequence and state, which stay on the page: this is what the calibration
                    // will claim about itself, and a reader downstream cannot tell an unstated
                    // accuracy from one nobody thought about.
                    "A picked position records no accuracy unless you state one — the app cannot " +
                        "know how well the chart is georeferenced, and imagery is often several " +
                        "metres out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Tall enough to place a quay in its surroundings, short enough to leave the numbers below it on the
 * same screenful. The card is a read-out with a picture, not a chart.
 */
private val PREVIEW_HEIGHT = 140.dp

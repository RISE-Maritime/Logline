package se.rise.logline.calibrate

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * What an edit would replace, and what that costs.
 *
 * **Measured values, never whole objects.** A `!=` on `PlatformZero` is the obvious comparison and is
 * wrong: `capturedAtEpochMillis` is rewritten by every capture, every map pick and the typed dialog,
 * and `samples`, `scatterM` and both accuracies are re-derived each time. Re-capturing the same spot
 * would read as "changed" while every number an operator cares about is identical. So a position
 * change is a *distance*, a heading change is a *turn*, and the provenance is reported on its own
 * terms rather than as a diff of the fields that carry it.
 *
 * Pure, because this is the part worth testing: the noise cases above are exactly what a screen
 * cannot check for itself.
 */
data class CalibrationChange(
    /** Which step it belongs to, so a screen can show only its own. */
    val area: ChangeArea,
    /** What changed, in a few words: `Zero point moved 12.4 m`. */
    val summary: String,
    /** The value being replaced, stated in full. Null where there was nothing before. */
    val was: String?,
    /**
     * What is given up by making the change, when something is.
     *
     * Not every change has one. This is the line that says a covariance will stop going out, which
     * nothing else on the screen would mention.
     */
    val cost: String? = null,
    /** The sensor this concerns, by `frame_id`. Null for platform-wide changes. */
    val frameId: String? = null,
)

enum class ChangeArea { PLATFORM, ZERO, FORWARD, SENSORS }

/**
 * Everything that differs between what is saved and what is being edited.
 *
 * Empty for a platform being created — there is nothing to differ from — which is also what a screen
 * uses to decide whether to show any of this at all.
 */
fun calibrationChanges(
    saved: PlatformCalibration?,
    draft: PlatformCalibration,
): List<CalibrationChange> {
    if (saved == null) return emptyList()
    return buildList {
        addAll(platformChanges(saved, draft))
        addAll(zeroChanges(saved.zero, draft.zero))
        addAll(forwardChanges(saved.zero, draft.zero))
        addAll(sensorChanges(saved.sensors, draft.sensors))
    }
}

private fun platformChanges(saved: PlatformCalibration, draft: PlatformCalibration) = buildList {
    if (saved.name != draft.name) {
        add(CalibrationChange(ChangeArea.PLATFORM, "Name changed", was = saved.name))
    }
    if (saved.entityId != draft.entityId) {
        add(
            CalibrationChange(
                ChangeArea.PLATFORM,
                "Entity id changed",
                was = saved.entityId,
                // The id is the `{entity_id}` chunk of every key this platform publishes on, so this
                // is not a rename but a move. Anything subscribed to the old id stops seeing it.
                cost = "Every subject this platform publishes on moves to the new id",
            )
        )
    }
    if (saved.parentFrameId != draft.parentFrameId) {
        add(CalibrationChange(ChangeArea.PLATFORM, "Parent frame id changed", was = saved.parentFrameId))
    }
    if (saved.platformType != draft.platformType) {
        add(
            CalibrationChange(
                ChangeArea.PLATFORM,
                "Type changed",
                was = saved.platformType?.wire ?: "not set",
            )
        )
    }
    if (saved.lengthOverAllM != draft.lengthOverAllM || saved.breadthOverAllM != draft.breadthOverAllM) {
        add(
            CalibrationChange(
                ChangeArea.PLATFORM,
                "Dimensions changed",
                was = "${metresOrDash(saved.lengthOverAllM)} × ${metresOrDash(saved.breadthOverAllM)}",
            )
        )
    }
    if (saved.ccrp != draft.ccrp) {
        add(CalibrationChange(ChangeArea.PLATFORM, "CCRP moved", was = offsetText(saved.ccrp)))
    }
}

private fun zeroChanges(saved: PlatformZero?, draft: PlatformZero?) = buildList {
    if (saved == null || !saved.hasPosition) {
        if (draft?.hasPosition == true) {
            add(CalibrationChange(ChangeArea.ZERO, "Zero point set", was = null))
        }
        return@buildList
    }
    if (draft == null || !draft.hasPosition) {
        add(
            CalibrationChange(
                ChangeArea.ZERO,
                "Zero point removed",
                was = positionText(saved),
                cost = "The platform stops publishing a position on location_fix",
            )
        )
        return@buildList
    }

    // A distance, not a pair of coordinate deltas: degrees of longitude are not metres, and at 57°N
    // they are not even the same number of metres as degrees of latitude.
    val enu = enuOffsetMetres(saved.point(), draft.point())
    val moved = hypot(enu.eastM, enu.northM)
    if (moved > 0.0) {
        add(
            CalibrationChange(
                ChangeArea.ZERO,
                "Zero point moved ${distanceText(moved)}",
                was = positionText(saved),
            )
        )
    }
    if (saved.altitudeM != draft.altitudeM) {
        add(
            CalibrationChange(
                ChangeArea.ZERO,
                "Altitude ${metresOrDash(saved.altitudeM)} → ${metresOrDash(draft.altitudeM)}",
                was = null,
            )
        )
    }
    // Provenance on its own terms. It changes on every re-measure of the same point, which is why it
    // is never folded into "moved" — and it is worth saying even when nothing moved, because how a
    // number was got is what says how much it is worth.
    if (provenanceOf(saved) != provenanceOf(draft)) {
        add(
            CalibrationChange(
                ChangeArea.ZERO,
                "Measured by ${draft.capture.label.lowercase()} instead of ${saved.capture.label.lowercase()}",
                was = provenanceOf(saved),
                // Both accuracies are what `SensorPublisher` needs before it will state a covariance
                // on the zero's `location_fix`. Losing them stops that going out, silently, because
                // the branch simply does not fire.
                cost = if (saved.accuracyM != null && saved.verticalAccuracyM != null &&
                    (draft.accuracyM == null || draft.verticalAccuracyM == null)
                ) {
                    "The zero point stops publishing a position covariance"
                } else {
                    null
                },
            )
        )
    }
}

private fun forwardChanges(saved: PlatformZero?, draft: PlatformZero?) = buildList {
    if (saved == null || draft == null) return@buildList
    val turn = turnDegrees(saved.headingDeg, draft.headingDeg)
    if (abs(turn) >= 0.05) {
        add(
            CalibrationChange(
                ChangeArea.FORWARD,
                // The same phrasing a live heading change uses in `LiveSignals`: the turn and the
                // side it went. `-124°` is a turn to port to anyone who reads the minus and a mystery
                // to anyone who does not.
                "Forward axis turned ${abs(turn).roundToInt()}° ${if (turn >= 0) "right" else "left"}",
                was = "${saved.headingDeg.roundToInt()}° ${saved.headingSource.label.lowercase()}",
            )
        )
    }
    if (saved.headingSource != draft.headingSource || saved.headingBaselineM != draft.headingBaselineM) {
        add(
            CalibrationChange(
                ChangeArea.FORWARD,
                "Axis now from ${draft.headingSource.label.lowercase()}",
                was = baselineText(saved),
                cost = if (saved.headingBaselineM != null && draft.headingBaselineM == null) {
                    "The baseline length goes with it — nothing will say what the angle is worth"
                } else {
                    null
                },
            )
        )
    }
}

/**
 * Sensors matched by `frame_id`, not by position in the list.
 *
 * `frame_id` is `child_frame_id` on the wire — the sensor's identity to every consumer — and it is
 * the only stable key here: matching by index would report every sensor after a deleted one as
 * changed, and matching by label would lose a sensor the moment somebody renamed it.
 */
private fun sensorChanges(saved: List<SensorMount>, draft: List<SensorMount>) = buildList {
    val before = saved.associateBy { it.frameId }
    val after = draft.associateBy { it.frameId }

    before.keys.filterNot { it in after }.forEach { gone ->
        val mount = before.getValue(gone)
        add(
            CalibrationChange(
                ChangeArea.SENSORS,
                "${nameOf(mount)} removed",
                was = offsetText(mount.translation),
                frameId = gone,
            )
        )
    }
    after.keys.filterNot { it in before }.forEach { fresh ->
        add(
            CalibrationChange(
                ChangeArea.SENSORS,
                "${nameOf(after.getValue(fresh))} added",
                was = null,
                frameId = fresh,
            )
        )
    }
    after.forEach { (frameId, now) ->
        val then = before[frameId] ?: return@forEach
        val moved = Vec3M(
            now.translation.x - then.translation.x,
            now.translation.y - then.translation.y,
            now.translation.z - then.translation.z,
        ).magnitude()
        if (moved > 0.0) {
            add(
                CalibrationChange(
                    ChangeArea.SENSORS,
                    "${nameOf(now)} moved ${distanceText(moved)}",
                    was = offsetText(then.translation),
                    frameId = frameId,
                )
            )
        }
        if (then.rotation != now.rotation) {
            add(
                CalibrationChange(
                    ChangeArea.SENSORS,
                    "${nameOf(now)} re-aimed",
                    was = rotationText(then.rotation),
                    frameId = frameId,
                )
            )
        }
        if (then.label != now.label) {
            add(
                CalibrationChange(
                    ChangeArea.SENSORS,
                    "${nameOf(then)} renamed to ${nameOf(now)}",
                    was = then.label,
                    frameId = frameId,
                )
            )
        }
    }
}

/**
 * Put one area back to what is saved, leaving the others alone.
 *
 * **The zero and the forward axis are exact complements**, which they have to be because they live on
 * one object: [ChangeArea.ZERO] restores the position and its provenance and leaves the heading
 * where it is, [ChangeArea.FORWARD] restores the heading, its source and the baseline and leaves the
 * position. That is the same split `zeroFromFix` already makes when it carries the heading through a
 * re-capture, and it is what stops reverting one from stranding the other.
 *
 * A [frameId] selects one sensor; without it [ChangeArea.SENSORS] restores the whole list, which is
 * what an added or removed sensor needs since neither has a row to revert from.
 */
fun revertArea(
    saved: PlatformCalibration,
    draft: PlatformCalibration,
    area: ChangeArea,
    frameId: String? = null,
): PlatformCalibration = when (area) {
    ChangeArea.PLATFORM -> draft.copy(
        name = saved.name,
        entityId = saved.entityId,
        parentFrameId = saved.parentFrameId,
        platformType = saved.platformType,
        description = saved.description,
        lengthOverAllM = saved.lengthOverAllM,
        breadthOverAllM = saved.breadthOverAllM,
        ccrp = saved.ccrp,
    )

    ChangeArea.ZERO -> draft.copy(
        zero = saved.zero?.copy(
            // The heading half stays as it is in the draft. Restoring it here would undo a forward
            // axis somebody established after moving the zero, which is not what "put the position
            // back" means.
            headingDeg = draft.zero?.headingDeg ?: saved.zero.headingDeg,
            headingSource = draft.zero?.headingSource ?: saved.zero.headingSource,
            headingBaselineM = draft.zero?.headingBaselineM,
        )
    )

    ChangeArea.FORWARD -> draft.zero?.let { current ->
        saved.zero?.let { was ->
            draft.copy(
                zero = current.copy(
                    headingDeg = was.headingDeg,
                    headingSource = was.headingSource,
                    headingBaselineM = was.headingBaselineM,
                )
            )
        }
    } ?: draft

    ChangeArea.SENSORS -> if (frameId == null) {
        draft.copy(sensors = saved.sensors)
    } else {
        val was = saved.sensors.firstOrNull { it.frameId == frameId }
        val without = draft.sensors.filterNot { it.frameId == frameId }
        // Appended rather than slotted back at its old index: the order is presentation, the frame id
        // is identity, and a list that reshuffles under somebody who just pressed revert is worse
        // than one that gains a row at the end.
        draft.copy(sensors = if (was == null) without else without + was)
    }
}

// ── phrasing ────────────────────────────────────────────────────────────────────────────────────
//
// Here rather than in the UI because these strings *are* the comparison: "moved 12 m" and "moved
// 12.43 m" are different claims about the same two points, and which one is right is a property of
// the measurement rather than of the screen showing it.

private fun nameOf(mount: SensorMount) = mount.label.ifBlank { mount.frameId }

private fun provenanceOf(zero: PlatformZero) = buildString {
    append(zero.capture.label)
    zero.accuracyM?.let { append(" ±${trim(it, 1)} m") }
    if (zero.samples > 0) append(", ${zero.samples} samples")
}

private fun baselineText(zero: PlatformZero) = buildString {
    append(zero.headingSource.label)
    zero.headingBaselineM?.let { append(", ${trim(it, 0)} m baseline") }
}

private fun positionText(zero: PlatformZero) =
    "${trim(zero.latitude, 5)}°, ${trim(zero.longitude, 5)}°"

private fun offsetText(v: Vec3M) =
    "${trim(v.x, 2)}, ${trim(v.y, 2)}, ${trim(v.z, 2)} m"

private fun rotationText(r: EulerDeg) =
    "yaw ${trim(r.yaw, 1)}°, pitch ${trim(r.pitch, 1)}°, roll ${trim(r.roll, 1)}°"

private fun metresOrDash(value: Double?) = value?.let { "${trim(it, 1)} m" } ?: "—"

/**
 * Distances read to the precision they are worth: centimetres under ten metres, tenths above it.
 *
 * A sensor offset that moved 0.83 m and a zero that moved 124 m are the same kind of number and not
 * the same kind of measurement, and `124.37 m` claims a precision the fix behind it does not have.
 */
private fun distanceText(metres: Double) =
    if (metres < 10.0) "${trim(metres, 2)} m" else "${trim(metres, 1)} m"

/**
 * `Locale.ROOT`, always, and via `BigDecimal` for the same reason `Double.json()` uses it: these
 * strings are read beside typed fields, and a comma where a point belongs is how the calibration
 * screen shipped a data-loss bug once already.
 */
private fun trim(value: Double, decimals: Int): String =
    java.math.BigDecimal(value)
        .setScale(decimals, java.math.RoundingMode.HALF_UP)
        .toPlainString()

package se.rise.logline.ui

import android.content.Context
import android.hardware.SensorManager
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.publish.MAX_VIDEO_FRAME_RATE
import se.rise.logline.publish.MIN_AUDIO_CHUNK_MILLIS
import se.rise.logline.publish.MIN_CALIBRATION_INTERVAL_MILLIS
import se.rise.logline.sensors.BatteryProvider
import se.rise.logline.sensors.RadioProvider
import se.rise.logline.sensors.imuTemperatureSensor

/**
 * Where a subject's fastest possible rate comes from — and they are not equally trustworthy.
 *
 * A row that prints `max 50` beside `55.3 Hz` looks like a contradiction until you know which of
 * these produced the 50. Keeping the provenance means the screen can mark the soft ones and the
 * per-subject page can say, in words, who is doing the limiting.
 */
enum class CeilingBasis {
    /**
     * `Sensor.getMinDelay()` — this device's own answer about its own hardware.
     *
     * Still not a hard cap: Android delivers to every client at the fastest rate *any* of them asked
     * for, so a sensor can and does exceed what it advertises. It is the best number available.
     */
    Reported,

    /**
     * A floor this app or the platform holds the loop to. Exact, and about software rather than
     * hardware — the radio poll, the audio chunk, the time-lapse interval, the calibration republish.
     */
    Imposed,

    /**
     * A judgement about hardware that answers no query, shown with a `~`.
     *
     * The fused location provider is the case this exists for: there is no supported-rate API, and
     * what arrives depends on the GNSS chipset and the sky.
     */
    Estimated,

    /**
     * An on-change sensor. There is no ceiling because there is no sampling — it reports when the
     * reading moves and can then be silent for twenty minutes, which is what `SampleHold` exists for.
     */
    OnChange,
}

/**
 * The fastest a subject can be expected to arrive, and where that number came from.
 *
 * [hz] is null only for [CeilingBasis.OnChange], where a number would be a fiction.
 */
data class RateCeiling(val hz: Double?, val basis: CeilingBasis) {

    /** `max 50`, `max 4`, `max ~1`, `on change` — what a subject row puts after the achieved rate. */
    /**
     * `sensor 25.0`, not `max 25.0`.
     *
     * "max" now names the *rate mode* on the Session screen, so a row reading `Rec max · pub 1.0 ·
     * max 25.0` used the same word for two different things a few characters apart — the mode chosen
     * and the hardware's own limit. This is the limit.
     */
    fun label(): String = when (basis) {
        CeilingBasis.OnChange -> "on change"
        CeilingBasis.Estimated -> "sensor ~${formatRate(hz ?: 0.0)}"
        else -> "sensor ${formatRate(hz ?: 0.0)}"
    }
}

/**
 * The ceiling for every subject this device can answer for.
 *
 * Asked once and remembered: it is a property of the hardware and the app's own loop floors, neither
 * of which changes while the process lives. A subject missing from the map is one whose hardware is
 * not there — the row already says "Not on this device" — or `log_message`, which is event-driven and
 * has no rate at all.
 */
fun rateCeilings(context: Context): Map<PublishedSubject, RateCeiling> {
    val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val imuTemperatureDelay = imuTemperatureSensor(context)?.minDelay
    return rateCeilings(
        sensorMinDelayUs = { type -> manager.getDefaultSensor(type)?.minDelay },
        imuTemperatureMinDelayUs = { imuTemperatureDelay },
    )
}

/**
 * The same, with the two platform lookups passed in so the decisions are testable off a device.
 *
 * Every branch below is a decision about what a source can do, and the only way to exercise them on a
 * phone is to own that phone — which is precisely the check nobody performs. `RateCeilingTest` runs
 * them all in a JVM.
 */
internal fun rateCeilings(
    sensorMinDelayUs: (Int) -> Int?,
    imuTemperatureMinDelayUs: () -> Int?,
): Map<PublishedSubject, RateCeiling> {
    val resolved = mutableMapOf<PublishedSubject, RateCeiling>()

    // Own rates first, then the subjects that ride them: a derived subject's ceiling *is* its owner's,
    // because it is published from the same samples. Deriving it any other way would let a row claim a
    // rate its own source cannot reach — `heading_true_north_deg` cannot outrun the rotation vector.
    PublishedSubject.entries.filter { it.rateOwner == null }.forEach { entry ->
        ownCeiling(entry, sensorMinDelayUs, imuTemperatureMinDelayUs)?.let { resolved[entry] = it }
    }
    PublishedSubject.entries.filter { it.rateOwner != null }.forEach { entry ->
        val owner = PublishedSubject.forSubject(entry.rateOwner!!)
        resolved[owner]?.let { resolved[entry] = it }
    }
    return resolved
}

private fun ownCeiling(
    entry: PublishedSubject,
    sensorMinDelayUs: (Int) -> Int?,
    imuTemperatureMinDelayUs: () -> Int?,
): RateCeiling? {
    // A button press is not a sample. There is nothing to cap and `SubjectRow` never asks.
    if (entry.eventDriven) return null

    entry.sensorType?.let { type ->
        val delay = sensorMinDelayUs(type) ?: return null
        return fromMinDelay(delay)
    }

    return when (entry) {
        // Found by string type rather than by constant, so the registry has no `sensorType` to ask
        // with — but it is a `SensorManager` sensor like any other and reports its own minimum delay.
        PublishedSubject.IMU_TEMPERATURE ->
            imuTemperatureMinDelayUs()?.let { fromMinDelay(it) }

        // The one genuine estimate. `FusedLocationProviderClient` publishes no supported-rate query;
        // a phone GNSS engine solves at 1 Hz, and the fused provider cannot outrun what the chipset
        // gives it. A chipset doing 5 Hz will simply beat the number, which is why it carries a `~`.
        PublishedSubject.LOCATION_FIX -> RateCeiling(1.0, CeilingBasis.Estimated)

        // Polled, not sampled — so the poll floor is the ceiling, and it is exact.
        PublishedSubject.BATTERY_STATE_OF_CHARGE ->
            RateCeiling(hzFromMillis(BatteryProvider.MIN_POLL_MILLIS), CeilingBasis.Imposed)
        PublishedSubject.CELLULAR_RSRP ->
            RateCeiling(hzFromMillis(RadioProvider.MIN_POLL_MILLIS), CeilingBasis.Imposed)

        // A chunk of sound is one message, so the shortest chunk is the fastest rate.
        PublishedSubject.AUDIO ->
            RateCeiling(hzFromMillis(MIN_AUDIO_CHUNK_MILLIS), CeilingBasis.Imposed)
        // Below half a second this stops being a time-lapse; capture-to-write is ~375 ms anyway.
        // `MIN_FRAME_INTERVAL_MILLIS` is the capacity estimate's own constant, in this package —
        // quoted rather than copied so the floor and the ceiling shown for it cannot drift.
        PublishedSubject.IMAGE_COMPRESSED ->
            RateCeiling(hzFromMillis(MIN_FRAME_INTERVAL_MILLIS), CeilingBasis.Imposed)
        // A data-rate guard rather than a hardware one — the camera would go faster.
        PublishedSubject.VIDEO_COMPRESSED ->
            RateCeiling(MAX_VIDEO_FRAME_RATE.toDouble(), CeilingBasis.Imposed)
        // The geometry is republished on a loop because Zenoh's latest-value store holds only the last
        // transform of each round; the loop's floor is the whole of the limit.
        PublishedSubject.FRAME_TRANSFORM ->
            RateCeiling(hzFromMillis(MIN_CALIBRATION_INTERVAL_MILLIS), CeilingBasis.Imposed)

        else -> null
    }
}

/**
 * `minDelay` in microseconds as a rate.
 *
 * Zero is the platform's way of saying **on-change** — `TYPE_LIGHT` reports it, and a naive division
 * would either divide by zero or claim an infinite rate for a sensor that may not speak for twenty
 * minutes. A trigger-only sensor reports −1 and is treated the same way.
 */
private fun fromMinDelay(minDelayUs: Int): RateCeiling =
    if (minDelayUs > 0) {
        RateCeiling(1_000_000.0 / minDelayUs, CeilingBasis.Reported)
    } else {
        RateCeiling(null, CeilingBasis.OnChange)
    }

private fun hzFromMillis(millis: Long): Double = 1_000.0 / millis

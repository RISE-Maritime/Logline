package se.rise.logline.sensors

import android.os.SystemClock

/**
 * Converts the boot-relative timestamps Android sensors report into nanoseconds since the Unix epoch.
 *
 * `SensorEvent.timestamp` is nanoseconds on the `elapsedRealtime` clock — time since boot, including
 * deep sleep — not an epoch time. Publishing it raw would put the whole stream decades in the past.
 *
 * The offset between the two clocks is read fresh for every sample, so the published timeline follows
 * UTC if the system clock is corrected mid-run. The cost is that `System.currentTimeMillis()` only has
 * millisecond resolution, so each converted timestamp is quantised to ~1 ms, and a backwards clock
 * correction moves samples backwards with it.
 */
object SensorClock {

    /**
     * Pure conversion, split out so it can be tested without a device.
     *
     * @param eventElapsedNanos the sensor event's boot-clock timestamp
     * @param wallMillis epoch milliseconds read now
     * @param elapsedNowNanos the boot clock read now
     */
    fun epochNanos(eventElapsedNanos: Long, wallMillis: Long, elapsedNowNanos: Long): Long =
        wallMillis * 1_000_000L - elapsedNowNanos + eventElapsedNanos

    /** Reads both clocks and converts. Call once per sample, at publish time. */
    fun epochNanosNow(eventElapsedNanos: Long): Long = epochNanos(
        eventElapsedNanos = eventElapsedNanos,
        wallMillis = System.currentTimeMillis(),
        elapsedNowNanos = SystemClock.elapsedRealtimeNanos(),
    )
}

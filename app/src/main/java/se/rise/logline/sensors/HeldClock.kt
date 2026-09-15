package se.rise.logline.sensors

/**
 * Converts a boot-clock observation time to epoch time **once per real reading**, however often that
 * reading is republished.
 *
 * [SensorClock.epochNanosNow] reads both clocks again on every call. That is right for a fresh sample
 * and wrong for a held one: converting the same reading on every tick gives it a slightly different
 * epoch time each time, so identical messages stop looking identical — which defeats the point of
 * repeating the original timestamp, and any consumer that deduplicates on it.
 *
 * It bit twice. `illuminance_lux` moved a held reading by a millisecond per publish. The cellular
 * subjects were worse: `SignalStrength` is a modem cache refreshed about every two minutes at sea, and
 * one recording turned about 166 real measurements into 18 766 "distinct" timestamps.
 *
 * Not thread-safe; use one per collector.
 */
class HeldClock(private val convert: (Long) -> Long = SensorClock::epochNanosNow) {
    private var lastElapsedNanos: Long? = null
    private var lastEpochNanos = 0L

    fun epochNanos(elapsedNanos: Long): Long {
        if (elapsedNanos != lastElapsedNanos) {
            lastElapsedNanos = elapsedNanos
            lastEpochNanos = convert(elapsedNanos)
        }
        return lastEpochNanos
    }
}

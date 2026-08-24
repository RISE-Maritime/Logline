package se.rise.logline.publish

import se.rise.logline.config.Settings
import se.rise.logline.keelson.PublishedSubject
import se.rise.logline.sensors.SensorRate

/**
 * Whether enough time has passed to put another sample of this subject on the wire.
 *
 * The bus and the file want opposite things. The file is what analysis is run against and wants every
 * sample the sensor gave; the bus is for watching a trial as it happens, where a tenth of the samples
 * says the same thing for a tenth of the bandwidth. So the sensor is registered at the *record* rate
 * and this thins what goes out to the *publish* rate.
 *
 * It also fixes a problem no rate setting could. A sensor is shared between apps, and Android runs it
 * at the fastest rate **any** client asked for, delivering every sample to all of them — measured here,
 * Logline asks the barometer for 1 Hz (`samplingPeriod=1000000us`) and Google Play Services asks the
 * same sensor for 10 Hz a second later, so ~12.5 Hz arrives. Nothing can decline another app's rate;
 * the only lever is to drop the surplus before it reaches the wire.
 *
 * **The first sample is always due.** A subject that waited for a full interval before its first
 * publish would leave the row on "Waiting for the first sample" for that long, and at 0.2 Hz that is
 * five seconds of looking broken.
 *
 * One instance per [se.rise.logline.publish.SensorPublisher] subject sink, which is what makes the
 * shared listeners come out right: eight subjects ride one `Location` callback, and thinning at the
 * callback would drag speed and course down with the fix.
 */
class PublishDecimator(private val intervalNanos: Long) {

    private var lastAtNanos: Long? = null

    /**
     * True when this sample should go out, and records it as sent.
     *
     * Not idempotent on purpose — asking is what claims the slot, so a caller cannot check twice and
     * publish twice.
     */
    fun due(nowNanos: Long): Boolean {
        // Everything through, which is what an interval of zero means: `SensorRate.Max`, or a publish
        // rate that has been clamped up to meet the record rate.
        if (intervalNanos <= 0L) return true
        val last = lastAtNanos
        // A clock that went backwards publishes rather than stalling. `System.nanoTime()` is monotonic,
        // so this should not happen — but the cost of being wrong is one extra sample, against a
        // subject that never publishes again for the life of the run.
        if (last == null || nowNanos < last || nowNanos - last >= intervalNanos) {
            lastAtNanos = nowNanos
            return true
        }
        return false
    }
}

/**
 * How long the wire must wait between samples of each subject, for subjects that are thinned at all.
 *
 * One entry per subject whose publish rate is slower than its record rate; everything absent from the
 * map publishes every sample it is given. `SensorPublisher` builds this once when a run starts and each
 * [se.rise.logline.publish.SensorPublisher] subject sink takes its own [PublishDecimator] from it —
 * **per sink, never per collector**, which is what makes a shared listener come out right. Eight
 * subjects ride one `Location` callback, and thinning at the callback would drag speed and course down
 * with the fix.
 *
 * Top-level and pure so it can be driven from a JVM test. It was a private member of `SensorPublisher`,
 * which put the one place the rates, the registry and the decimator meet behind a class that cannot be
 * built without Android and Zenoh — so the combination could only ever be checked by hand on a phone.
 */
internal fun publishIntervals(settings: Settings): Map<PublishedSubject, Long> =
    PublishedSubject.entries.mapNotNull { entry ->
        if (entry.eventDriven) return@mapNotNull null
        // The flag rather than the two names, so the subject page that *states* this and the
        // publish path that *enforces* it cannot drift — see PublishedSubject.neverThinned.
        if (entry.neverThinned) return@mapNotNull null
        val publish = settings.publishRate(entry.subject)
        val record = settings.recordRate(entry.subject)
        if (publish == record) return@mapNotNull null
        val hz = (publish as? SensorRate.Hz)?.hz ?: return@mapNotNull null
        entry to (1_000_000_000.0 / hz).toLong()
    }.toMap()

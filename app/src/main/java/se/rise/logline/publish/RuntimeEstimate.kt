package se.rise.logline.publish

/**
 * How much longer this run can go on whatever it is consuming — the battery, or the free space.
 *
 * Three states rather than a nullable duration, because "we cannot say yet" and "it is plugged in" are
 * different answers and the screen should say which one it means.
 */
sealed interface RuntimeEstimate {

    /** No usable trend yet: the first minutes of a run, a gauge that has not moved, or one that jumped. */
    data object Unknown : RuntimeEstimate

    /** On external power. A time-to-empty would be a fiction, and a rising gauge is not a drain rate. */
    data object Charging : RuntimeEstimate

    /**
     * Time to an empty tank at the drain measured over the recent window.
     *
     * [drainPerHour] is in whatever unit the estimator was fed — see [RuntimeEstimator]. It is
     * carried for the log and for tests, not for the screen: the screen shows the duration.
     */
    data class Remaining(val millis: Long, val drainPerHour: Double) : RuntimeEstimate
}

/**
 * Time to empty, from whatever is being consumed while logging.
 *
 * **Unit-agnostic on purpose**, and used for two different tanks. It is fed a "fuel remaining" number
 * and divides it by the fuel consumed per hour, so the unit cancels: microamp-hours from
 * `BATTERY_PROPERTY_CHARGE_COUNTER` where the device reports them, percent where it does not, and free
 * bytes above the recorder's floor for the disk. The caller picks; this does not care, and cannot tell.
 *
 * The rate is measured over a trailing window rather than from the whole run, because the thing being
 * estimated changes: switching the camera on doubles the drain, and an estimate that averaged in the
 * hour before that would keep promising time the phone no longer has. For the battery it is also
 * measured from the *gauge*, not from `CURRENT_NOW`, because instantaneous current on a phone swings by
 * an order of magnitude between screen-on and screen-off and would make the number jump around
 * uselessly.
 *
 * Least squares over the window rather than first-minus-last: a fuel gauge quantises (1 mAh steps on a
 * Pixel 6, 1% where percent is all there is), so two endpoints can differ by one quantum in either
 * direction and every sample in between is evidence.
 */
class RuntimeEstimator(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val minimumSpanMillis: Long = DEFAULT_MINIMUM_SPAN_MILLIS,
) {
    private val times = ArrayDeque<Long>()
    private val fuel = ArrayDeque<Double>()

    /**
     * Add a reading and get the current estimate.
     *
     * @param fuelRemaining what is left in the tank, in any unit, as long as it is the *same* unit
     *   every time — see the class note.
     *
     * Deliberately knows nothing about charging: that is a fact about one of the two tanks, and a
     * caller that sees external power calls [reset] and reports [RuntimeEstimate.Charging] itself.
     */
    fun record(timeMillis: Long, fuelRemaining: Double): RuntimeEstimate {
        val lastTime = times.lastOrNull()
        val lastFuel = fuel.lastOrNull()
        when {
            // A clock that went backwards — the epoch offset is re-read per sample, so an NTP
            // correction mid-run can do this. The old points are no longer comparable.
            lastTime != null && timeMillis <= lastTime -> reset()
            // An implausible jump: a fuel gauge recalibrating, or a caller that changed unit between
            // microamp-hours and percent. Either way the window now mixes two things, so drop it. The
            // magnitude guard keeps a legitimate 2% → 1% step near empty from tripping the ratio test.
            lastFuel != null && lastFuel > JUMP_FLOOR &&
                (fuelRemaining > lastFuel * JUMP_UP || fuelRemaining < lastFuel * JUMP_DOWN) -> reset()
        }

        times.addLast(timeMillis)
        fuel.addLast(fuelRemaining)
        while (times.size > 1 && timeMillis - times.first() > windowMillis) {
            times.removeFirst()
            fuel.removeFirst()
        }

        val span = timeMillis - times.first()
        if (times.size < MINIMUM_SAMPLES || span < minimumSpanMillis) return RuntimeEstimate.Unknown

        // Fuel per millisecond; negative while discharging.
        val slope = slope() ?: return RuntimeEstimate.Unknown
        if (slope >= 0.0) return RuntimeEstimate.Unknown

        val remainingMillis = (fuelRemaining / -slope).toLong()
        if (remainingMillis <= 0L || remainingMillis > MAX_REPORTABLE_MILLIS) return RuntimeEstimate.Unknown
        return RuntimeEstimate.Remaining(remainingMillis, -slope * MILLIS_PER_HOUR)
    }

    /** A new run starts with no history, the same way the counters do. */
    fun reset() {
        times.clear()
        fuel.clear()
    }

    /** Ordinary least squares on (time, fuel); null when every sample landed on the same instant. */
    private fun slope(): Double? {
        val n = times.size
        val meanTime = times.sumOf { it.toDouble() } / n
        val meanFuel = fuel.sum() / n
        var covariance = 0.0
        var variance = 0.0
        for (i in 0 until n) {
            val dt = times.elementAt(i) - meanTime
            covariance += dt * (fuel.elementAt(i) - meanFuel)
            variance += dt * dt
        }
        return if (variance == 0.0) null else covariance / variance
    }

    companion object {
        /**
         * Twenty minutes. Long enough for a 1%-resolution gauge to have moved a step or two, short
         * enough that turning the camera on shows up in the number within a coffee break.
         */
        const val DEFAULT_WINDOW_MILLIS = 20 * 60 * 1_000L

        /** Below three minutes the answer is quantisation noise dressed up as a prediction. */
        const val DEFAULT_MINIMUM_SPAN_MILLIS = 3 * 60 * 1_000L

        /**
         * Beyond four days it is not an estimate of anything — a phone that barely moves the gauge is a
         * phone on a trickle charge or one whose gauge has stalled, and "96 h left" would be believed.
         */
        const val MAX_REPORTABLE_MILLIS = 96 * 60 * 60 * 1_000L

        private const val MINIMUM_SAMPLES = 3
        private const val MILLIS_PER_HOUR = 3_600_000.0

        /** Only apply the ratio test above this, so 2% → 1% near empty is a step and not a jump. */
        private const val JUMP_FLOOR = 10.0
        private const val JUMP_UP = 1.5
        private const val JUMP_DOWN = 0.5
    }
}

/** Which tank runs dry first. Named rather than boolean, because the screen says which one out loud. */
enum class RuntimeLimit { Battery, Storage }

/** How much longer the run has, and what ends it. */
data class TimeLeft(val millis: Long, val limit: RuntimeLimit)

/**
 * The nearer of the two ends, or null while neither has been measured.
 *
 * A run stops when the battery empties *or* when the disk fills, and the two are independent — a phone
 * on a charger still fills its card, and a phone with 200 GB free still goes flat. Showing only the
 * battery meant a run with forty minutes of space promising four hours.
 *
 * Ties go to the battery: an empty battery ends the publishing too, not just the recording.
 */
fun timeLeft(battery: RuntimeEstimate, storage: RuntimeEstimate): TimeLeft? {
    val batteryMillis = (battery as? RuntimeEstimate.Remaining)?.millis
    val storageMillis = (storage as? RuntimeEstimate.Remaining)?.millis
    return when {
        batteryMillis != null && storageMillis != null && storageMillis < batteryMillis ->
            TimeLeft(storageMillis, RuntimeLimit.Storage)
        batteryMillis != null -> TimeLeft(batteryMillis, RuntimeLimit.Battery)
        storageMillis != null -> TimeLeft(storageMillis, RuntimeLimit.Storage)
        else -> null
    }
}

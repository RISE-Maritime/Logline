package se.rise.logline.sensors

/**
 * Conversions between the sampling rate a user thinks in (Hz) and the units Android wants.
 *
 * Both APIs take a *delay*, not a rate, and treat it as a hint: `registerListener` may deliver faster
 * or slower than asked depending on what the hardware supports, and the fused location provider
 * coalesces when nothing has changed. So a configured rate is a request, never a promise — which is
 * why the UI shows the achieved rate alongside it.
 */

/**
 * A configured sampling rate: an explicit number, or "whatever the hardware can do".
 *
 * [Max] is not the advertised maximum filled in as a number — it is a zero delay
 * (`SENSOR_DELAY_FASTEST`, and interval 0 for location), which asks the platform for everything it
 * has. That matters because the advertised maximum is not a ceiling in practice: this device reports
 * a 415.97 Hz gyroscope maximum and delivers ~442 Hz when asked for 400.
 */
sealed interface SensorRate {
    /** As fast as the hardware will deliver. */
    data object Max : SensorRate

    /** A specific requested rate. Still only a hint — the hardware delivers what it can. */
    data class Hz(val hz: Double) : SensorRate
}

/** Delay for `SensorManager.registerListener`; 0 is `SENSOR_DELAY_FASTEST`. */
fun SensorRate.toRateUs(): Int = when (this) {
    SensorRate.Max -> 0
    is SensorRate.Hz -> hzToRateUs(hz)
}

/** Interval for `LocationRequest`; 0 asks the provider for updates as fast as it can produce them. */
fun SensorRate.toIntervalMillis(): Long = when (this) {
    SensorRate.Max -> 0L
    is SensorRate.Hz -> hzToIntervalMillis(hz)
}

/** Round-trips through DataStore. */
fun SensorRate.serialise(): String = when (this) {
    SensorRate.Max -> "MAX"
    is SensorRate.Hz -> hz.toString()
}

fun parseSensorRate(stored: String?): SensorRate? = when {
    stored == null -> null
    stored == "MAX" -> SensorRate.Max
    else -> stored.toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }?.let { SensorRate.Hz(it) }
}

/** Microseconds between samples, for `SensorManager.registerListener`. */
fun hzToRateUs(hz: Double): Int = (1_000_000.0 / hz).toInt()

/** Milliseconds between updates, for `LocationRequest`. */
fun hzToIntervalMillis(hz: Double): Long = (1_000.0 / hz).toLong()

/** Hz from a text field, or null if it is not a usable rate. */
fun parseRateHz(text: String): Double? {
    val hz = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    return if (hz > 0.0 && hz.isFinite()) hz else null
}

/** Average rate over a run, or null before there is enough to divide by. */
fun achievedHz(samples: Long, firstEpochMillis: Long, lastEpochMillis: Long): Double? {
    if (samples < 2 || firstEpochMillis <= 0L) return null
    val elapsedMillis = lastEpochMillis - firstEpochMillis
    if (elapsedMillis <= 0L) return null
    // n samples span n-1 intervals; using n would overstate the rate on short runs.
    return (samples - 1) * 1_000.0 / elapsedMillis
}

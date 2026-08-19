package se.rise.logline.sensors

/**
 * Android's units are not keelson's units.
 *
 * Every conversion here exists because the subject name declares a unit that the Android API does not
 * use, and getting one wrong produces a plausible-looking number that is off by a fixed factor — the
 * kind of bug that survives review and shows up months later as "the pressure trace looks odd". They
 * are pure functions so they can be tested against hand-worked values rather than eyeballed on a phone.
 *
 * The factors, and where each comes from:
 *
 * | Quantity | Android | keelson subject | Factor |
 * | --- | --- | --- | --- |
 * | magnetic field | µT (`TYPE_MAGNETIC_FIELD`) | `magnetic_field_gauss` | ÷100 |
 * | air pressure | hPa (`TYPE_PRESSURE`) | `air_pressure_pa` | ×100 |
 * | speed | m/s (`Location.getSpeed`) | `speed_over_ground_knots` | ×1.943844 |
 * | battery voltage | mV (`EXTRA_VOLTAGE`) | `battery_voltage_v` | ÷1000 |
 * | battery current | µA (`BATTERY_PROPERTY_CURRENT_NOW`) | `battery_current_a` | ÷1e6 |
 * | battery temperature | deci-°C (`EXTRA_TEMPERATURE`) | `battery_temperature_celsius` | ÷10 |
 */

/** 1 gauss = 100 µT. Earth's field is 25–65 µT, i.e. 0.25–0.65 gauss — a useful sanity check. */
fun microteslaToGauss(microtesla: Float): Float = microtesla / 100f

/** 1 hPa (millibar) = 100 Pa. Sea-level standard is 1013.25 hPa = 101325 Pa. */
fun hectopascalToPascal(hectopascal: Float): Float = hectopascal * 100f

/** Exactly 1852 m per nautical mile, so 1 m/s = 3600/1852 knots. */
fun metresPerSecondToKnots(mps: Float): Float = mps * 3600f / 1852f

fun millivoltToVolt(millivolt: Int): Float = millivolt / 1_000f

/** Negative while discharging on most devices, which is meaningful — do not take the absolute value. */
fun microampToAmp(microamp: Int): Float = microamp / 1_000_000f

/** `EXTRA_TEMPERATURE` is tenths of a degree: 312 means 31.2 °C. */
fun deciCelsiusToCelsius(deciCelsius: Int): Float = deciCelsius / 10f

/** WiFi link speeds come in whole Mbit/s; the subject is bits per second. */
fun megabitsPerSecondToBitsPerSecond(mbps: Int): Float = mbps * 1_000_000f

/**
 * Cell bandwidth: Android reports kHz, the subject is MHz.
 *
 * **What the platform promises and what we infer are not the same here.** `CellIdentityLte.getBandwidth()`
 * is documented as "Cell bandwidth in kHz" and names no direction; it is published as the *downlink*
 * because the LTE cell identity's bandwidth is the DL system bandwidth and that is what every consumer
 * of the field takes it for. The uplink genuinely differs on an asymmetric carrier, and Android will
 * not tell an ordinary app what it is — `PhysicalChannelConfig` carries both and is gated behind
 * `READ_PRECISE_PHONE_STATE`, which is `signature|privileged`. So the honest options were this reading
 * or nothing, and the reading is documented rather than assumed.
 *
 * LTE carriers are 1.4, 3, 5, 10, 15 or 20 MHz, so the fractional case is real and the division must
 * not be integer.
 */
fun kilohertzToMegahertz(khz: Int): Float = khz / 1_000f

/**
 * Absent-or-value for the telephony sentinel.
 *
 * `CellInfo.UNAVAILABLE` is `Integer.MAX_VALUE`, while `-140` dBm is a *real* RSRP meaning "barely
 * alive". They are semantically opposite and two billion apart, so a sentinel that reaches an averaging
 * consumer does not merely add noise — it destroys the series. Normalise at the boundary so absence is
 * absence and nothing downstream has to know the magic number.
 *
 * Passed in rather than referenced from `android.telephony.CellInfo` so this stays JVM-testable.
 */
fun Int.orAbsent(sentinel: Int): Float? = if (this == sentinel) null else toFloat()

/** As [orAbsent], but keeping integer identity values exact rather than routing them through a float. */
fun Int.intOrAbsent(sentinel: Int): Int? = if (this == sentinel) null else this

/**
 * The 64-bit sentinel, which is a **different value** from the 32-bit one.
 *
 * `CellIdentityNr.getNci()` returns `CellInfo.UNAVAILABLE_LONG` (`Long.MAX_VALUE`) while every other
 * identity getter returns `CellInfo.UNAVAILABLE` (`Integer.MAX_VALUE`). Comparing an NCI against the
 * 32-bit sentinel would silently publish `9223372036854775807` as a cell id, which is why this exists
 * separately rather than as one clever generic helper.
 */
fun Long.longOrAbsent(sentinel: Long): Long? = if (this == sentinel) null else this

/**
 * Serving-cell bands as a single free-form string, since `radio_band` is a `TimestampedString`.
 *
 * LTE bands are bare numbers (`"3"`); NR bands conventionally carry an `n` prefix (`"n78"`). Carrier
 * aggregation reports several, so they are joined — the subject is deliberately free-form and a
 * consumer should not assume exactly one. An empty array means the platform did not report bands,
 * which is absence rather than "no bands".
 */
fun formatBands(bands: IntArray, nr: Boolean): String? {
    if (bands.isEmpty()) return null
    val prefix = if (nr) "n" else ""
    return bands.joinToString(",") { "$prefix$it" }
}

/**
 * WiFi's own sentinels, which are a different family again: link speed reports `-1`
 * (`WifiInfo.LINK_SPEED_UNKNOWN`) and a disconnected radio reports `-127` dBm. One generic
 * "is it unavailable" helper across telephony and WiFi would be wrong in both directions.
 */
fun wifiRssiOrAbsent(rssi: Int): Float? = if (rssi <= WIFI_RSSI_DISCONNECTED) null else rssi.toFloat()

fun wifiLinkSpeedOrAbsent(mbps: Int): Float? =
    if (mbps <= 0) null else megabitsPerSecondToBitsPerSecond(mbps)

/** `WifiInfo.INVALID_RSSI`. It is `@hide`, so the value is spelled out rather than referenced. */
const val WIFI_RSSI_DISCONNECTED = -127

/**
 * Charge level as a percentage.
 *
 * `EXTRA_SCALE` is nearly always 100, but it is documented as the divisor rather than fixed, so a
 * device reporting level out of 255 would otherwise publish a percentage far above 100. Returns null
 * rather than dividing by a missing or zero scale.
 */
fun chargePercent(level: Int, scale: Int): Float? =
    if (level < 0 || scale <= 0) null else level * 100f / scale

/**
 * A compass angle in the form every chart and every keelson consumer expects: degrees clockwise from
 * north, in `[0, 360)`.
 *
 * Android's `getOrientation` reports azimuth in radians over `(-π, π]`, so west arrives as −90° and
 * would otherwise be published as a negative bearing — a number no compass rose has. Adding a
 * declination can also push a heading past 360, which wraps here for the same reason.
 */
fun normaliseHeadingDegrees(degrees: Float): Float {
    val wrapped = degrees % 360f
    return if (wrapped < 0f) wrapped + 360f else wrapped
}

/** Radians to degrees, for the rotation vector's heading-accuracy estimate. */
fun radiansToDegrees(radians: Float): Float = Math.toDegrees(radians.toDouble()).toFloat()

package se.rise.logline.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val TAG = "RadioProvider"

/**
 * One reading of the cellular link. Every field is nullable — the modem is allowed to decline any of
 * them, and a declined field must not become a confident number on the bus.
 *
 * [measuredAtElapsedNanos] is the modem's own report time on the boot clock, not the read time. Because
 * this is polled, the same measurement is republished across many ticks; carrying its real timestamp is
 * what lets a consumer tell a fresh reading from one held for two minutes.
 */
data class CellularSample(
    val rsrpDbm: Float?,
    val rsrqDb: Float?,
    val sinrDb: Float?,
    val rssiDbm: Float?,
    val accessTechnology: String?,
    val measuredAtElapsedNanos: Long?,
)

/** One reading of the WiFi link. Null throughout when WiFi is not the associated network. */
data class WifiSample(
    val rssiDbm: Float?,
    val downlinkBitsPerSecond: Float?,
    val uplinkBitsPerSecond: Float?,
)

/**
 * The serving cell's identity. Null throughout when the platform reported nothing usable.
 *
 * [measuredAtElapsedNanos] is `CellInfo.getTimestampMillis()` — the modem's own report time on the boot
 * clock. It matters more here than for the quality metrics: `getAllCellInfo()` returns a *cache*, and
 * identity is precisely what a consumer uses to decide whether two measurements straddle a handover.
 */
data class CellIdentitySample(
    val cellId: Long?,
    val physicalCellId: Int?,
    val earfcn: Int?,
    val band: String?,
    /**
     * The serving cell's bandwidth in kHz, or null.
     *
     * **LTE only.** `CellIdentityNr` has no bandwidth field — the only place 5G carries one is
     * `PhysicalChannelConfig`, which needs `READ_PRECISE_PHONE_STATE` (`signature|privileged`, verified
     * with `pm list permissions -f`), so no ordinary app can reach it. Null on NR is therefore the
     * truth, not a hole worth filling with a guess.
     */
    val downlinkBandwidthKhz: Int?,
    val measuredAtElapsedNanos: Long?,
)

data class RadioSample(
    val cellular: CellularSample?,
    val wifi: WifiSample?,
    val identity: CellIdentitySample?,
)

/**
 * Radio link quality, polled.
 *
 * **Polled on purpose, despite the platform being push-based.** `SignalStrength` is a cached value the
 * modem refreshes only when its own hysteresis thresholds are crossed: measured on a stationary Pixel 6,
 * twelve reads over 30 s returned byte-identical values, and `SignalStrengthsListener` can go minutes
 * without firing. Polling therefore costs nothing a listener would save, and it avoids
 * `registerTelephonyCallback` — API 31 against a minSdk of 30 — along with its version gate and
 * listener lifecycle. The measurement timestamp travels with the payload so the repetition is visible
 * rather than misleading.
 *
 * Needs no dangerous permission. `TelephonyManager.getSignalStrength()` and the
 * `CellSignalStrengthLte` getters carry no `@RequiresPermission` at all, and reading WiFi through
 * `NetworkCapabilities.getTransportInfo()` needs only `ACCESS_NETWORK_STATE`, which is already declared.
 * Going via `WifiManager.getConnectionInfo()` instead would drag in `ACCESS_FINE_LOCATION` for the SSID
 * we do not want.
 */
class RadioProvider(private val context: Context) {

    private val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val probe = RadioProbe(context, telephony)

    fun samples(intervalMillis: Long): Flow<RadioSample> = flow {
        while (true) {
            emit(
                RadioSample(
                    cellular = readCellular(),
                    wifi = readWifi(),
                    identity = readCellIdentity(),
                )
            )
            delay(intervalMillis.coerceAtLeast(MIN_POLL_MILLIS))
        }
    }

    /**
     * Serving-cell identity from `getAllCellInfo()`.
     *
     * The **pull** path on purpose: `getAllCellInfo()` is annotated with `ACCESS_FINE_LOCATION` alone,
     * while the push path (`TelephonyCallback.CellInfoListener`) is annotated
     * `allOf{READ_PHONE_STATE, ACCESS_FINE_LOCATION}` and would cost a second dangerous permission for
     * data that changes only on handover.
     *
     * Four failure modes, all distinct and none of them an exception worth crashing on:
     *  - `SecurityException` — the permission is missing or revoked.
     *  - **empty list** — granted "while in use" but the location app-op resolved to `MODE_IGNORED`,
     *    i.e. suppressed. Not "no cells". Our `location` foreground service normally prevents this.
     *  - `null` — radio down (airplane mode); the framework clears its cache.
     *  - `UnsupportedOperationException` — no telephony hardware (`@RequiresFeature`).
     */
    private fun readCellIdentity(): CellIdentitySample? {
        // Checked rather than left to the SecurityException below: in an IMU-only run this path is hit
        // on every tick, and throwing once a second as normal control flow is not that. The catch
        // stays as the backstop for a permission revoked mid-run.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val cells = try {
            telephony.allCellInfo
        } catch (_: SecurityException) {
            return null // location not granted — expected in IMU-only runs, not worth logging per tick
        } catch (t: Throwable) {
            Log.w(TAG, "getAllCellInfo failed", t)
            return null
        } ?: return null
        if (probe.enabled) probe.cellList(cells)

        // Zero registered entries is legal, and so is more than one. Prefer the primary serving cell,
        // fall back to any registered one.
        val serving = cells.firstOrNull { it.cellConnectionStatus == CellInfo.CONNECTION_PRIMARY_SERVING }
            ?: cells.firstOrNull { it.isRegistered }
            ?: return null

        val at = serving.timestampMillis.takeIf { it > 0L }?.times(1_000_000L)

        return when (val id = serving.cellIdentity) {
            is CellIdentityNr -> CellIdentitySample(
                // NCI is 36-bit and its sentinel is the *long* one — see Units.longOrAbsent.
                cellId = id.nci.longOrAbsent(CellInfo.UNAVAILABLE_LONG),
                physicalCellId = id.pci.intOrAbsent(CellInfo.UNAVAILABLE),
                earfcn = id.nrarfcn.intOrAbsent(CellInfo.UNAVAILABLE),
                band = formatBands(id.bands, nr = true),
                // No such field on NR — see CellIdentitySample.downlinkBandwidthKhz.
                downlinkBandwidthKhz = null,
                measuredAtElapsedNanos = at,
            )
            is CellIdentityLte -> CellIdentitySample(
                cellId = id.ci.intOrAbsent(CellInfo.UNAVAILABLE)?.toLong(),
                physicalCellId = id.pci.intOrAbsent(CellInfo.UNAVAILABLE),
                earfcn = id.earfcn.intOrAbsent(CellInfo.UNAVAILABLE),
                band = formatBands(id.bands, nr = false),
                downlinkBandwidthKhz = id.bandwidth.intOrAbsent(CellInfo.UNAVAILABLE),
                measuredAtElapsedNanos = at,
            )
            // GSM/WCDMA/CDMA carry different identity shapes; publishing them under EARFCN/PCI names
            // would be wrong, so they simply report nothing.
            else -> null
        }
    }

    /** Null when there is no cellular radio state at all — airplane mode, or no SIM. */
    private fun readCellular(): CellularSample? {
        // Documented @Nullable, and genuinely null in airplane mode. This is the single most likely
        // crash in this file.
        val strength = try {
            telephony.signalStrength
        } catch (t: Throwable) {
            Log.w(TAG, "signalStrength unavailable", t)
            null
        } ?: return null
        if (probe.enabled) probe.signalStrength(strength)

        val lte =strength.getCellSignalStrengths(CellSignalStrengthLte::class.java).firstOrNull()
        val nr = strength.getCellSignalStrengths(CellSignalStrengthNr::class.java)
            .firstOrNull()
            ?.takeIf { it.ssRsrp != CellInfo.UNAVAILABLE }
        val technology = if (nr != null) "NR" else if (lte != null) "LTE" else null

        if (lte == null && nr == null) return null

        // The metrics must come from the leg the technology names, or the label and the numbers
        // describe different radios. On this device attached to 5G NSA the two legs read RSRP -83 (NR)
        // and -91 (LTE anchor) simultaneously — publishing "NR" alongside -91 would be quietly wrong.
        return CellularSample(
            rsrpDbm = nr?.ssRsrp?.orAbsent(CellInfo.UNAVAILABLE)
                ?: lte?.rsrp?.orAbsent(CellInfo.UNAVAILABLE),
            rsrqDb = nr?.ssRsrq?.orAbsent(CellInfo.UNAVAILABLE)
                ?: lte?.rsrq?.orAbsent(CellInfo.UNAVAILABLE),
            // Android calls the LTE one RSSNR — reference-signal SNR. Modems report it as the LTE SINR
            // and it is the quantity `radio_sinr_db` names; the two differ only in whether interference
            // is separated from noise, which the modem does not expose either way. NR reports SS-SINR
            // directly.
            sinrDb = nr?.ssSinr?.orAbsent(CellInfo.UNAVAILABLE)
                ?: lte?.rssnr?.orAbsent(CellInfo.UNAVAILABLE),
            // NR has no RSSI equivalent, so this stays the LTE anchor's even on NSA — which is a real
            // measurement of a real leg, just not the same leg as the rest. On standalone NR there is
            // no anchor and it is simply absent.
            rssiDbm = lte?.rssi?.orAbsent(CellInfo.UNAVAILABLE),
            accessTechnology = technology,
            measuredAtElapsedNanos = elapsedNanos(strength.timestampMillis),
        )
    }

    /**
     * `SignalStrength.getTimestampMillis()` is on the boot clock, like `SensorEvent.timestamp`, so it
     * goes through the same conversion. Zero or negative means the platform gave us nothing usable.
     */
    private fun elapsedNanos(timestampMillis: Long): Long? =
        if (timestampMillis > 0L) timestampMillis * 1_000_000L else null

    /** Null when WiFi is not associated — absence rather than the `-127`/`-1` poison values. */
    private fun readWifi(): WifiSample? {
        val network = connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null
        val info = connectivity.getNetworkCapabilities(network)?.transportInfo as? WifiInfo
            ?: return null

        return WifiSample(
            rssiDbm = wifiRssiOrAbsent(info.rssi),
            downlinkBitsPerSecond = wifiLinkSpeedOrAbsent(info.rxLinkSpeedMbps),
            uplinkBitsPerSecond = wifiLinkSpeedOrAbsent(info.txLinkSpeedMbps),
        )
    }

    /**
     * Internal rather than private so `rateCeilings()` can quote the floor instead of copying it: a
     * ceiling shown on screen and the loop that enforces it must be the same number.
     */
    internal companion object {
        internal const val MIN_POLL_MILLIS = 250L
    }
}

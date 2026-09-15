package se.rise.logline.sensors

import android.content.Context
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.util.Log

/**
 * A logcat probe for how fresh the modem's radio caches really are. **Off unless switched on from adb**
 * and it never changes what is published:
 *
 * ```
 * adb shell setprop log.tag.RadioProbe DEBUG     # on
 * adb logcat -s RadioProbe:D > probe.txt
 * adb shell setprop log.tag.RadioProbe ""        # off
 * ```
 *
 * It exists to answer two TODO questions on a real phone before `readCellular()` is changed. Is a
 * serving `CellInfo`'s own signal strength fresher than `SignalStrength`, which refreshed only about
 * every two minutes at sea? And does the cell list carry the NR leg on 5G NSA, or only the LTE anchor?
 * `tools/radio_probe_summary.py` reads the output; `docs/development.md` has the procedure.
 *
 * One `key=value` line per source per poll, each carrying `now` on the boot clock, so every age can be
 * computed from the log alone. While on, it also asks the modem for a fresh cell report every
 * [REQUEST_INTERVAL_MILLIS] (`REQCELL` lines), to see whether asking helps. That request is never made
 * with the probe off.
 */
internal class RadioProbe(private val context: Context, private val telephony: TelephonyManager) {

    private var lastRequestMillis = 0L

    val enabled: Boolean get() = Log.isLoggable(TAG, Log.DEBUG)

    fun signalStrength(strength: SignalStrength) {
        val lte = strength.getCellSignalStrengths(CellSignalStrengthLte::class.java).firstOrNull()
        val nr = strength.getCellSignalStrengths(CellSignalStrengthNr::class.java).firstOrNull()
        Log.d(
            TAG,
            "SS now=${SystemClock.elapsedRealtime()} ts=${strength.timestampMillis} " +
                "lteRsrp=${v(lte?.rsrp)} lteRsrq=${v(lte?.rsrq)} lteSinr=${v(lte?.rssnr)} " +
                "nrRsrp=${v(nr?.ssRsrp)} nrRsrq=${v(nr?.ssRsrq)} nrSinr=${v(nr?.ssSinr)}",
        )
    }

    /** The caller has already checked `ACCESS_FINE_LOCATION`; the request needs it too. */
    fun cellList(cells: List<CellInfo>) {
        val now = SystemClock.elapsedRealtime()
        logCells("CELL", now, cells)
        if (now - lastRequestMillis >= REQUEST_INTERVAL_MILLIS) {
            lastRequestMillis = now
            try {
                @Suppress("MissingPermission")
                telephony.requestCellInfoUpdate(
                    context.mainExecutor,
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) =
                            logCells("REQCELL", SystemClock.elapsedRealtime(), cellInfo)

                        override fun onError(errorCode: Int, detail: Throwable?) {
                            Log.d(TAG, "REQERR now=${SystemClock.elapsedRealtime()} code=$errorCode")
                        }
                    },
                )
            } catch (t: Throwable) {
                Log.d(TAG, "REQERR now=$now code=${t.javaClass.simpleName}")
            }
        }
    }

    private fun logCells(kind: String, now: Long, cells: List<CellInfo>) {
        if (cells.isEmpty()) Log.d(TAG, "$kind now=$now n=0")
        cells.forEachIndexed { i, cell ->
            val (type, rsrp, rsrq, sinr) = when (cell) {
                is CellInfoNr -> (cell.cellSignalStrength as CellSignalStrengthNr)
                    .let { listOf("NR", v(it.ssRsrp), v(it.ssRsrq), v(it.ssSinr)) }
                is CellInfoLte -> cell.cellSignalStrength
                    .let { listOf("LTE", v(it.rsrp), v(it.rsrq), v(it.rssnr)) }
                else -> listOf(cell.javaClass.simpleName, "na", "na", "na")
            }
            Log.d(
                TAG,
                "$kind now=$now n=${cells.size} i=$i type=$type status=${cell.cellConnectionStatus} " +
                    "reg=${cell.isRegistered} ts=${cell.timestampMillis} " +
                    "rsrp=$rsrp rsrq=$rsrq sinr=$sinr",
            )
        }
    }

    private fun v(value: Int?): String =
        if (value == null || value == CellInfo.UNAVAILABLE) "na" else value.toString()

    private companion object {
        const val TAG = "RadioProbe"
        const val REQUEST_INTERVAL_MILLIS = 10_000L
    }
}

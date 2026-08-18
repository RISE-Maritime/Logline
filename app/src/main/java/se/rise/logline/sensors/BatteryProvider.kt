package se.rise.logline.sensors

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * One battery reading, already converted to keelson's units.
 *
 * Every field is nullable because a device is allowed not to report it, and proto3 cannot express
 * "absent" for a scalar — so a missing value must be dropped before it becomes a confident `0.0` on
 * the bus. A phone reporting no current draw is not a phone drawing zero current.
 */
data class BatterySample(
    val stateOfChargePct: Float?,
    val voltageV: Float?,
    val currentA: Float?,
    val temperatureCelsius: Float?,
    val isCharging: Boolean?,
    /**
     * Charge left in the battery, in microamp-hours, straight off the fuel gauge.
     *
     * **Not published** — keelson has no subject for it — but it is the best input there is for the
     * time-left estimate: it moves in 1 mAh steps on a Pixel 6 where [stateOfChargePct] moves in
     * whole percent, which is roughly 46 mAh on the same phone. A percent-resolution gauge needs the
     * best part of ten minutes to prove it moved at all.
     */
    val chargeMicroAmpHours: Long?,
)

/**
 * Battery telemetry, polled.
 *
 * Two sources, neither of which needs a permission: the sticky `ACTION_BATTERY_CHANGED` broadcast
 * (level, voltage, temperature) and `BatteryManager` properties (instantaneous current). Registering a
 * null receiver against the sticky intent returns the last broadcast immediately without subscribing
 * to anything, so this is a read rather than a listener.
 *
 * Polled rather than driven by the broadcast so battery sits in the same rate model as every other
 * subject — one configurable Hz, one card, the same achieved-rate readout. The values repeat between
 * changes, which is ordinary for telemetry and much easier to reason about than a subject that emits
 * only when something happens and therefore looks dead when nothing does.
 */
class BatteryProvider(private val context: Context) {

    private val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    fun samples(intervalMillis: Long): Flow<BatterySample> = flow {
        while (true) {
            emit(read())
            // Cancellable, so cancelling the scope ends the loop. A zero interval (rate "Maximum")
            // would spin the CPU for no benefit on a value that changes over minutes, so it is floored.
            delay(intervalMillis.coerceAtLeast(MIN_POLL_MILLIS))
        }
    }

    private fun read(): BatterySample {
        val intent: Intent? =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val millivolts = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, MISSING) ?: MISSING
        val deciCelsius = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, MISSING) ?: MISSING

        // Documented to return Integer.MIN_VALUE when unsupported, and observed to return
        // Integer.MAX_VALUE on some devices — treat any absurd magnitude as "not reported" rather
        // than publishing ±2147 amperes.
        val microamps = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        // Long-valued, and its "unsupported" answer is Long.MIN_VALUE rather than the 32-bit sentinel —
        // the same width trap the cell-identity getters have. Zero is not a reading either: a gauge
        // that reports no charge on a running phone is a gauge that is not reporting.
        val chargeMicroAmpHours = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

        return BatterySample(
            stateOfChargePct = chargePercent(level, scale),
            voltageV = if (millivolts > 0) millivoltToVolt(millivolts) else null,
            temperatureCelsius = if (deciCelsius != MISSING) deciCelsiusToCelsius(deciCelsius) else null,
            currentA = if (kotlin.math.abs(microamps) < IMPLAUSIBLE_MICROAMPS) microampToAmp(microamps) else null,
            isCharging = intent?.let { manager.isCharging },
            chargeMicroAmpHours = chargeMicroAmpHours.takeIf { it > 0L && it < IMPLAUSIBLE_MICROAMP_HOURS },
        )
    }

    private companion object {
        const val MISSING = Int.MIN_VALUE
        const val MIN_POLL_MILLIS = 1_000L

        /** 100 A. Real phone draw is milliamps; anything past this is a sentinel, not a measurement. */
        const val IMPLAUSIBLE_MICROAMPS = 100_000_000

        /** 1000 Ah. A phone battery is single-digit amp-hours; past this it is a sentinel. */
        const val IMPLAUSIBLE_MICROAMP_HOURS = 1_000_000_000_000L
    }
}

package se.rise.logline

import se.rise.logline.publish.BatteryAction
import se.rise.logline.publish.CRITICAL_BATTERY_PCT
import se.rise.logline.publish.batteryAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When a run secures itself against a flat battery.
 *
 * The counterpart to the free-space floor, which the battery never had: `openSession()` refuses
 * below `MIN_FREE_BYTES`, so a full disk ends a run tidily, while a flat battery ended it by killing
 * the process. Nothing anywhere compared the charge to a number.
 *
 * These are transitions rather than values, and the state they turn on is a `var` inside a
 * collector — which is exactly why the decision is a pure function and this file exists. The
 * expensive mistake is not failing to fire; it is firing repeatedly, since each one closes and
 * publishes the current file.
 */
class BatteryWatchdogTest {

    @Test
    fun `a charge at or below the threshold secures the run`() {
        assertEquals(BatteryAction.Secure, batteryAction(CRITICAL_BATTERY_PCT, charging = false, secured = false))
        assertEquals(BatteryAction.Secure, batteryAction(3f, charging = false, secured = false))
    }

    @Test
    fun `a healthy charge does nothing`() {
        assertEquals(BatteryAction.Nothing, batteryAction(80f, charging = false, secured = false))
        assertEquals(BatteryAction.Nothing, batteryAction(CRITICAL_BATTERY_PCT + 1f, charging = false, secured = false))
    }

    /**
     * **The one that matters.** A charge sitting on the threshold is polled every thirty seconds, and
     * acting on each reading would close and publish the file every thirty seconds — turning one run
     * into a hundred files, each copy competing with the drain for the disk.
     */
    @Test
    fun `it fires once per crossing, not once per reading`() {
        assertEquals(BatteryAction.Nothing, batteryAction(9f, charging = false, secured = true))
        assertEquals(BatteryAction.Nothing, batteryAction(1f, charging = false, secured = true))
    }

    /**
     * Plugging in re-arms it, so a phone charged and later unplugged is protected the second time
     * too — a real shape on a boat, where a phone goes on a charger between legs.
     */
    @Test
    fun `charging re-arms a run that has already been secured`() {
        assertEquals(BatteryAction.Rearm, batteryAction(9f, charging = true, secured = true))
        // Re-armed once, not on every subsequent charging reading.
        assertEquals(BatteryAction.Nothing, batteryAction(50f, charging = true, secured = false))
    }

    /** Charging below the threshold is not a reason to act: the number is going the right way. */
    @Test
    fun `a low charge that is charging does not secure`() {
        assertEquals(BatteryAction.Nothing, batteryAction(5f, charging = true, secured = false))
    }

    /**
     * A reading with no charge in it is not information. It must not act, and — the subtler half —
     * must not *forget* an earlier crossing either, or a gauge that goes quiet would re-arm the run
     * and let it secure itself all over again on the next reading.
     */
    @Test
    fun `an absent reading neither acts nor forgets`() {
        assertEquals(BatteryAction.Nothing, batteryAction(null, charging = false, secured = false))
        assertEquals(BatteryAction.Nothing, batteryAction(null, charging = false, secured = true))
        assertEquals(BatteryAction.Nothing, batteryAction(null, charging = null, secured = true))
    }

    /**
     * An unknown charging state counts as **not** charging. A phone that will not say has a battery
     * still going down as far as anything here knows, and the cost of being wrong that way is one
     * extra file boundary against losing the run.
     */
    @Test
    fun `an unknown charging state is treated as discharging`() {
        assertEquals(BatteryAction.Secure, batteryAction(5f, charging = null, secured = false))
    }
}

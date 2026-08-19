package se.rise.logline.publish

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

private const val TAG = "BatteryOptimisation"

/**
 * Whether Android's battery manager is still free to stop this app in the background.
 *
 * A foreground service and a partial wake lock are enough on a Pixel, and are not enough everywhere:
 * several OEM battery managers stop an app that has been in the background for hours regardless, which
 * is exactly the shape of a logging run. The exemption is the documented way out of that, and this is
 * the only reliable way to know whether it is in place — nothing announces a change, so it is re-read
 * whenever the Activity resumes.
 */
fun isBatteryOptimised(context: Context): Boolean {
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return !power.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Put the exemption in front of the user, however this device is willing to.
 *
 * First the direct dialog, then — if no activity handles it, which is the case where a manufacturer
 * has removed it — the system's list of battery-optimised apps, which the user has to find this app
 * in. Deliberately **not** decided with `resolveActivity`: package visibility on Android 11+ can hide
 * an activity that is perfectly launchable, so asking first would send some devices to the long way
 * round for no reason. Launching and catching is the answer that cannot be wrong.
 *
 * [launch] is the caller's `ActivityResultLauncher`, which throws synchronously when nothing handles
 * the intent — that is what makes this shape work.
 */
fun requestBatteryExemption(context: Context, launch: (Intent) -> Unit) {
    try {
        launch(batteryExemptionIntent(context))
    } catch (e: ActivityNotFoundException) {
        Log.i(TAG, "no exemption dialog on this device; opening the settings list instead", e)
        try {
            launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            // Nowhere left to send them. Not worth surfacing: the settings screen already says what
            // the state is, and this is a device that has removed both routes to changing it.
            Log.w(TAG, "this device offers no way to change battery optimisation", e)
        }
    }
}

/**
 * The dialog itself.
 *
 * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` needs `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in the
 * manifest, and **Play policy restricts that permission to a short list of eligible app types and
 * rejects the rest** — which is what lint's `BatteryLife` is warning about. Suppressed rather than
 * worked around because this is an in-house tool that is never published to a store, and because the
 * alternative is making every user hunt for this app in a system list. Worth revisiting the day
 * anybody considers publishing it.
 */
@SuppressLint("BatteryLife")
private fun batteryExemptionIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.fromParts("package", context.packageName, null))

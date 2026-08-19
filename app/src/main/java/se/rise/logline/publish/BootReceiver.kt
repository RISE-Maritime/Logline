package se.rise.logline.publish

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import se.rise.logline.LoglineApp
import se.rise.logline.config.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private const val TAG = "BootReceiver"

/**
 * Starts a run again after a reboot, when the user has asked for that.
 *
 * `START_STICKY` already brings a run back after the *process* is killed; nothing brought it back
 * after a restart, which for a phone wired into a rig is the difference between an unattended
 * install and one somebody has to go and visit.
 *
 * **Two things narrow what a boot start can be**, and both are the platform's rules rather than
 * choices made here:
 *
 * * Android 12+ forbids starting a foreground service from the background, and receiving
 *   `BOOT_COMPLETED` is one of the documented exemptions — but the exemption belongs to the broadcast,
 *   so the service is started from inside [onReceive] rather than from a coroutine that outlives it.
 *   That is also why the settings read here is blocking: it is one small preferences file, and the
 *   alternative is starting outside the window the exemption covers.
 * * Android 15+ then refuses several foreground service *types* started from `BOOT_COMPLETED` —
 *   `dataSync`, `microphone` and `camera` among them. So a boot start only happens when
 *   `ACCESS_FINE_LOCATION` is granted, which is what makes the run a `location` service, and
 *   [PublisherService] drops audio and the camera from a boot start whatever the settings say. An
 *   IMU-only run would be a `dataSync` service and would be refused outright.
 *
 * Not registered for `MY_PACKAGE_REPLACED`: an app update is done by a person who is right there, and
 * a run restarting under them is a surprise rather than a service.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? LoglineApp ?: return

        val settings = runBlocking { app.settingsRepository.settings.first() }
        if (!settings.startOnBoot) {
            // Once per boot, and worth the line: "did the receiver even run" is otherwise
            // indistinguishable from "it ran and decided not to", which is the whole question when
            // somebody reports that their phone did not come back up publishing.
            Log.i(TAG, "boot completed; start on boot is switched off")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Refusing here rather than letting `startForeground` throw: without the permission the
            // run would be a `dataSync` service, which Android 15+ will not allow from this broadcast.
            Log.w(TAG, "start on boot is on, but ACCESS_FINE_LOCATION is not granted; not starting")
            return
        }

        Log.i(TAG, "starting a run after boot")
        PublisherService.start(context, fromBoot = true)
    }
}

/**
 * The settings a boot start can actually honour.
 *
 * Android 15+ refuses a `microphone` or `camera` foreground service started from a `BOOT_COMPLETED`
 * broadcast, and `startForeground` throws rather than quietly dropping the type — so a boot start with
 * audio switched on would take the *whole run* down instead of losing one subject. They come off here,
 * in one place, so the service's type mask and the collectors the publisher launches cannot disagree
 * about what this run contains.
 *
 * Everything else is left exactly as configured, and a run started by hand never comes through here:
 * the restriction is a property of the broadcast, not of the types.
 */
internal fun forBootStart(settings: Settings): Settings =
    settings.copy(audioEnabled = false, cameraEnabled = false)

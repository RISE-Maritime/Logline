package se.rise.logline.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/**
 * What the location stream can report.
 *
 * A fix is not the only outcome worth publishing to the UI: a provider that will never produce one
 * has to say so, because "no fix yet" and "location is switched off" look identical from the outside
 * — a row waiting silently forever, which is what this type exists to prevent.
 */
sealed interface LocationUpdate {

    /** A position. */
    data class Fix(val location: Location) : LocationUpdate

    /**
     * No fix is coming, and why — text meant to be read by a person on the status screen.
     *
     * Only raised for causes that are **certain and actionable**. A phone indoors reports itself
     * unavailable too, and saying "failed" about a phone that is merely under a roof would be the
     * kind of false alarm that teaches people to ignore the row. That case stays `Waiting`.
     */
    data class Unavailable(val reason: String) : LocationUpdate

    /** Whatever was wrong is not wrong any more; nothing has arrived yet. */
    data object Available : LocationUpdate
}

/** Text for the one cause a person can do something about, in the words of the Android setting. */
const val LOCATION_SWITCHED_OFF = "Location is switched off in Android settings"

class LocationProvider(context: Context) {

    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    /**
     * Positions, and the reasons there are none.
     *
     * Both come off **one** registration: `LocationCallback` carries `onLocationResult` and
     * `onLocationAvailability` together, so nothing is subscribed twice and the two cannot disagree
     * about which run they describe.
     *
     * Three things can say a fix is not coming, and they are deliberately not treated alike:
     *
     * * The master location switch, checked directly through `LocationManager` rather than inferred.
     *   It is checked once at registration, because the availability callback is not guaranteed to
     *   arrive at all, and again whenever the system broadcasts a mode change — which is what makes
     *   somebody switching location off *during* a run visible within a second rather than never.
     * * `requestLocationUpdates` failing outright, which is how a device with no Play Services
     *   presents: the call is rejected and no callback is ever invoked.
     * * `onLocationAvailability(false)` while the switch is on — the soft one, and the reason this
     *   does not simply forward it. Under a roof the fused provider reports itself unavailable and
     *   then recovers; that is the `Waiting` state doing its job, not a failure.
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun updates(intervalMillis: Long = 1_000L): Flow<LocationUpdate> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis)
            .build()

        fun reportSwitchState() {
            trySend(
                if (locationEnabled()) LocationUpdate.Available
                else LocationUpdate.Unavailable(LOCATION_SWITCHED_OFF)
            )
        }

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(LocationUpdate.Fix(it)) }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                // Only the switch is reported from here. Unavailability with the switch on is a phone
                // indoors, and `Waiting` already says that honestly.
                if (availability.isLocationAvailable) trySend(LocationUpdate.Available)
                else if (!locationEnabled()) trySend(LocationUpdate.Unavailable(LOCATION_SWITCHED_OFF))
            }
        }

        // The system's own signal for the master switch, and the only one that does not depend on the
        // fused provider choosing to tell us. Covers switching it back on too, so the row recovers
        // immediately instead of waiting out the time to first fix.
        val modeChanges = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = reportSwitchState()
        }
        ContextCompat.registerReceiver(
            appContext,
            modeChanges,
            IntentFilter(LocationManager.MODE_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Before anything is requested: a run started with location off should say so at once rather
        // than after a silence long enough to look like a bug.
        if (!locationEnabled()) trySend(LocationUpdate.Unavailable(LOCATION_SWITCHED_OFF))

        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener {
                // A device without Play Services lands here — no callback will ever be invoked, so
                // without this the stream would simply never emit anything again.
                trySend(
                    LocationUpdate.Unavailable(
                        "The location provider refused the request: ${it.message ?: it.javaClass.simpleName}"
                    )
                )
            }

        awaitClose {
            client.removeLocationUpdates(callback)
            runCatching { appContext.unregisterReceiver(modeChanges) }
        }
    }

    /**
     * Fixes only, for a caller that has nothing to report and nowhere to report it.
     *
     * The rig calibration averages a fixed number of seconds of position with the user watching a
     * countdown; if location is off it collects nothing and says so in its own words, so the reasons
     * would have no home there.
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun locations(intervalMillis: Long = 1_000L): Flow<Location> =
        updates(intervalMillis).filterIsInstance<LocationUpdate.Fix>().map { it.location }

    private fun locationEnabled(): Boolean {
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return manager?.isLocationEnabled ?: false
    }
}

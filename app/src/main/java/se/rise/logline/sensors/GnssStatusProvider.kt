package se.rise.logline.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * What the GNSS engine can see, and how much of it it is actually using.
 *
 * The gap between the two is the whole point: twenty satellites in view and none used is a phone under
 * a steel deck, and it looks exactly like twelve in view and eleven used from anywhere else in this
 * app — a position arrives either way, and only this says how much to trust it.
 */
data class GnssSample(
    /** Satellites the receiver can hear at all, across every constellation. */
    val visible: Int,
    /** How many of them went into the solution. Zero means the engine is not solving. */
    val used: Int,
)

/**
 * `GnssStatus`, which is the receiver talking about itself rather than about where it is.
 *
 * Like [NmeaProvider] this **does not start the GNSS engine** — it reports on one that is running, so
 * it lives on the location collector alongside the fused request that keeps the chip going. It goes
 * quiet rather than reporting zeroes when the engine stops, which is why the subjects can stall: no
 * receiver is running to be asked.
 */
class GnssStatusProvider(context: Context) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun status(): Flow<GnssSample> = callbackFlow {
        val locationManager = manager
        if (locationManager == null) {
            close(); return@callbackFlow
        }
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var used = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) used++
                }
                trySend(GnssSample(visible = status.satelliteCount, used = used))
            }
        }
        // A Handler on the main looper, matching the app's other location callbacks; the work per
        // callback is a loop over a couple of dozen satellites and a trySend.
        locationManager.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
        awaitClose { locationManager.unregisterGnssStatusCallback(callback) }
    }
}

/**
 * The kind of solution behind a fix, in keelson's terms.
 *
 * Two things go into it, and neither alone is enough. The satellite count is the receiver's own
 * statement about whether it is solving — `usedInFix` goes to zero the moment it stops. Whether the
 * fix carries an altitude is what separates a 2D solution from a 3D one, and only the `Location`
 * knows that.
 *
 * **`FIX_NO` with a position on the bus is a real and useful state, not a contradiction.** This app
 * publishes the *fused* position, which Android will happily derive from wifi and cell with the GNSS
 * engine solving nothing at all. Saying so is the point of the subject: the fix exists, and it is not
 * a GNSS fix.
 *
 * Left alone: `rtk_status` and `integrity`. Android exposes neither, and their zero values already
 * mean "not reported" — which is the truth, and better than a plausible guess.
 *
 * @param used satellites in the solution, from [GnssSample]
 * @param fixHasAltitude whether the most recent fix carried one; null before the first fix
 */
internal fun fixQualityOf(used: Int, fixHasAltitude: Boolean?): FixQuality = when {
    used == 0 -> FixQuality(FixKind.NoFix, solving = false)
    fixHasAltitude == null -> FixQuality(FixKind.NoFix, solving = true)
    fixHasAltitude -> FixQuality(FixKind.ThreeD, solving = true)
    else -> FixQuality(FixKind.TwoD, solving = true)
}

/** The app's own view of a fix, mapped onto the proto's enums where it is published. */
internal data class FixQuality(val kind: FixKind, val solving: Boolean)

internal enum class FixKind { NoFix, TwoD, ThreeD }

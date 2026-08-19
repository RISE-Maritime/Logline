package se.rise.logline.publish

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import se.rise.logline.LoglineApp
import se.rise.logline.MainActivity
import se.rise.logline.R
import se.rise.logline.config.Settings
import se.rise.logline.ui.formatRuntimeLeft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val TAG = "PublisherService"
private const val CHANNEL_ID = "publishing"
private const val NOTIFICATION_ID = 1
private const val WAKE_LOCK_TAG = "logline:publishing"
private const val NOTIFICATION_REFRESH_MILLIS = 5_000L

/**
 * Foreground service that owns the lifetime of a publishing run.
 *
 * It does no sensor or Zenoh work itself — [SensorPublisher] still does all of that. The service
 * exists to keep the process alive and the CPU awake once the Activity is gone, which is the only
 * way sensor and location callbacks keep arriving with the screen off.
 *
 * Two modes, chosen per start from the current permission state: with `ACCESS_FINE_LOCATION` it runs
 * as a `location` service and publishes all four subjects; without it, as a `dataSync` service
 * publishing the three IMU subjects only. `dataSync` is capped at roughly six hours per day, hence
 * [onTimeout].
 */
class PublisherService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val app: LoglineApp get() = application as LoglineApp

    private var wakeLock: PowerManager.WakeLock? = null
    private var settings: Settings? = null
    private var locationMode = false
    /** Whether this run declared the microphone type, which the notification says out loud. */
    private var audioMode = false
    /** Same, for the camera type. A phone quietly taking pictures should say so too. */
    private var cameraMode = false
    private var running = false

    /**
     * Settings read before `startForeground`, because the service type depends on them.
     *
     * The run itself re-reads DataStore on its own coroutine; this is only the early peek needed to
     * decide whether the microphone type belongs in the type mask, which must be right on the first
     * call or the service throws.
     */
    private var pendingSettings: Settings? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the system restarted us after killing the process (START_STICKY);
        // resume the run rather than sitting there doing nothing.
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                stopPublishing()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startPublishing(fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true)
        }
        return START_STICKY
    }

    private fun startPublishing(fromBoot: Boolean = false) {
        if (running) return

        // Blocking, and deliberately: `startForeground` must be called within five seconds of the
        // start, and its type mask depends on whether audio is on. One DataStore read of a small
        // preferences file is a few milliseconds against that budget.
        pendingSettings = forThisStart(runBlocking { app.settingsRepository.settings.first() }, fromBoot)

        locationMode = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val baseType = if (locationMode) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        // The microphone type is added only when audio is enabled *and* the permission is already
        // granted. Declaring it without the permission is not a warning on Android 14+ — it throws, and
        // the whole run dies at startForeground.
        audioMode = pendingSettings?.audioEnabled == true &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        // Same rule for the camera: on Android 14+ declaring the type without CAMERA throws.
        cameraMode = pendingSettings?.cameraEnabled == true &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        var type = baseType
        if (audioMode) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (cameraMode) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA

        try {
            startForeground(NOTIFICATION_ID, buildNotification(null), type)
        } catch (t: Throwable) {
            // Reachable if the permission was revoked between the tap and here, or if the system
            // restarted us somewhere a foreground start is not allowed.
            Log.e(TAG, "startForeground failed", t)
            stopSelf()
            return
        }

        running = true
        acquireWakeLock()

        scope.launch {
            val current = pendingSettings ?: forThisStart(app.settingsRepository.settings.first(), fromBoot)
            settings = current
            app.publisher.start(current)
            // Attached *after* the run has begun, and that ordering is load-bearing: `start()` clears
            // the previous run's error synchronously, and a `StateFlow` collector is handed the current
            // value the instant it subscribes. Attaching first therefore read the old error as this
            // run's and stopped the service before its session had opened. Nothing is missed by waiting
            // — `start()` returns as soon as it has launched, and a failure arriving before this line
            // is still the first thing the collector sees.
            watchForFailure()
            updateNotification()
        }

        watchOffSubjects()
        refreshNotificationPeriodically()
    }

    /** See [forBootStart]: a boot start cannot carry the microphone or camera types. */
    private fun forThisStart(settings: Settings, fromBoot: Boolean): Settings {
        if (!fromBoot) return settings
        if (settings.audioEnabled || settings.cameraEnabled) {
            Log.i(TAG, "boot start: audio and the camera stay off, which this broadcast cannot start")
        }
        return forBootStart(settings)
    }

    private fun stopPublishing() {
        if (!running) return
        running = false
        app.publisher.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * If the Zenoh session fails to open there is nothing left to keep alive — the error is already
     * on [SensorPublisher.status] for the UI to show, so drop the notification and the wake lock.
     */
    private fun watchForFailure() {
        scope.launch {
            app.publisher.status
                .map { it.error }
                .distinctUntilChanged()
                .collect { error ->
                    if (error != null) {
                        Log.w(TAG, "publisher reported error, stopping service: $error")
                        stopPublishing()
                        stopSelf()
                    }
                }
        }
    }

    /**
     * The per-subject switches, applied to a run in progress.
     *
     * Every other setting needs a restart — a publisher carries the key and the QoS it was declared
     * with — but a switch only decides whether samples are let through, so it can land immediately and
     * the session survives. That is why the toggles do not go through `saveSettings`, which stops and
     * restarts the service.
     *
     * Mapped and de-duplicated so an unrelated settings edit does not push the same set again.
     */
    private fun watchOffSubjects() {
        scope.launch {
            app.settingsRepository.settings
                .map { it.offSubjects() }
                .distinctUntilChanged()
                .collect { app.publisher.setOffSubjects(it) }
        }
    }

    private fun refreshNotificationPeriodically() {
        scope.launch {
            while (isActive) {
                delay(NOTIFICATION_REFRESH_MILLIS)
                if (!running) break
                updateNotification()
            }
        }
    }

    private fun updateNotification() {
        val total = app.publisher.status.value.totalSamplesPublished
        notificationManager().notify(NOTIFICATION_ID, buildNotification(total))
    }

    private fun buildNotification(totalSamples: Long?): Notification {
        val settings = this.settings
        val target = if (settings == null) {
            getString(R.string.notification_starting)
        } else {
            getString(R.string.notification_target, settings.entityId, settings.routerEndpoints.singleOrNull() ?: "${settings.routerEndpoints.size} routers")
        }
        val detail = buildString {
            if (totalSamples != null) {
                append(getString(R.string.notification_samples, totalSamples))
            }
            // A frozen sample count on its own looks identical to a crash; say which it is.
            if (app.publisher.status.value.connection == ConnectionState.Disconnected) {
                if (isNotEmpty()) append(" · ")
                append(getString(R.string.notification_disconnected))
            }
            if (!locationMode) {
                if (isNotEmpty()) append(" · ")
                append(getString(R.string.notification_imu_only))
            }
            // Said out loud, every time. A phone quietly recording a wheelhouse is exactly the thing
            // the microphone indicator exists to prevent, and the notification should agree with it.
            if (audioMode) {
                if (isNotEmpty()) append(" · ")
                append(getString(R.string.notification_audio))
            }
            if (cameraMode) {
                if (isNotEmpty()) append(" · ")
                append(getString(R.string.notification_camera))
            }
            // The one number an unattended run is read for from the lock screen: how much longer it
            // has, and what ends it — a full disk stops a recording as surely as a flat battery does.
            // Absent until a rate has actually been measured — see RuntimeEstimator.
            timeLeft(
                app.publisher.status.value.batteryRuntime,
                app.publisher.recording.value.spaceRuntime,
            )?.let {
                if (isNotEmpty()) append(" · ")
                val format = when (it.limit) {
                    RuntimeLimit.Battery -> R.string.notification_battery
                    RuntimeLimit.Storage -> R.string.notification_storage
                }
                append(getString(format, formatRuntimeLeft(it.millis)))
            }
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PublisherService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(target)
            .setContentText(detail.ifEmpty { null })
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // No timeout on purpose: a logging run is meant to last as long as the user leaves it running,
    // and every exit path (stop, onTimeout, onDestroy) releases it.
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * Android 15+ calls this when a `dataSync` service hits its daily cap, just before force-stopping
     * it. Shutting down here is what keeps that from being a crash. Never fires in location mode.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service type $fgsType timed out; stopping")
        stopPublishing()
        stopSelf()
    }

    override fun onDestroy() {
        stopPublishing()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "se.rise.logline.action.START"
        const val ACTION_STOP = "se.rise.logline.action.STOP"

        /** Set only by [BootReceiver]; see [forThisStart] for what it changes. */
        private const val EXTRA_FROM_BOOT = "se.rise.logline.extra.FROM_BOOT"

        fun start(context: Context, fromBoot: Boolean = false) {
            val intent = Intent(context, PublisherService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_FROM_BOOT, fromBoot)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, PublisherService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

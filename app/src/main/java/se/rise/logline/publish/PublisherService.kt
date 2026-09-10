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
import se.rise.logline.ui.formatCounted
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

/**
 * A second channel, for the one thing this app has to interrupt somebody about.
 *
 * It cannot share the ongoing notification's channel: that one is `IMPORTANCE_LOW` so a run does not
 * buzz every time its figures change, and from Android 8 the channel's importance decides whether
 * anything alerts — a `PRIORITY_HIGH` notification posted to a low channel is silent. So the run's
 * status stays quiet and this stays loud, which is the split the two actually want.
 */
private const val BATTERY_CHANNEL_ID = "battery"
private const val BATTERY_NOTIFICATION_ID = 2
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
                stopPublishing(intent?.getStringExtra(EXTRA_CLOSING_NOTE))
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
        cameraMode = (pendingSettings?.cameraEnabled == true || pendingSettings?.videoEnabled == true) &&
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
        watchBattery()
            updateNotification()
        }

        watchOffSubjects()
        watchTags()
        watchOutputFolder()
        refreshNotificationPeriodically()
    }

    /** See [forBootStart]: a boot start cannot carry the microphone or camera types. */
    private fun forThisStart(settings: Settings, fromBoot: Boolean): Settings {
        if (!fromBoot) return settings
        if (settings.audioEnabled || settings.cameraEnabled || settings.videoEnabled) {
            Log.i(TAG, "boot start: audio and the camera stay off, which this broadcast cannot start")
        }
        return forBootStart(settings)
    }

    private fun stopPublishing(closingNote: String? = null) {
        if (!running) return
        running = false
        app.publisher.stop(closingNote)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * If the Zenoh session fails to open there is nothing left to keep alive — the error is already
     * on [SensorPublisher.status] for the UI to show, so drop the notification and the wake lock.
     */
    /**
     * Alert once when the run secures itself against a flat battery.
     *
     * `distinctUntilChanged` is what makes it once: `batteryCritical` is a state that stays true for
     * the rest of the run, so collecting it raw would re-post on every unrelated status change — and
     * a warning that arrives every few seconds is one people learn to swipe away.
     */
    private fun watchBattery() {
        scope.launch {
            app.publisher.status
                .map { it.batteryCritical }
                .distinctUntilChanged()
                .collect { critical -> if (critical) notifyBatteryCritical() }
        }
    }

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

    /**
     * The tags the recording will carry, pushed in as they are toggled.
     *
     * The same shape as [watchOffSubjects] and for the same reason: a tag is written straight to
     * DataStore rather than through `saveSettings()`, because tearing down a Zenoh session and the open
     * MCAP file to record a word would lose the run somebody was labelling.
     */
    private fun watchTags() {
        scope.launch {
            app.settingsRepository.settings
                .map { it.activeTags }
                .distinctUntilChanged()
                .collect { app.publisher.setTags(it) }
        }
    }

    /**
     * Where finished recordings are copied to, pushed in as it is chosen.
     *
     * The same shape again. Nothing about a destination needs the publishers redeclared, and it is
     * read when a file is published rather than when the run starts — so choosing a folder mid-run
     * lands the next file there, including the one a rotation is about to open.
     */
    private fun watchOutputFolder() {
        scope.launch {
            app.settingsRepository.settings
                .map { it.recordingsFolderUri }
                .distinctUntilChanged()
                .collect { app.publisher.setOutputFolder(it) }
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
                // Through `formatCounted` like every other count in the app, rather than a `%d` in a
                // string resource: that read `681204 samples` on the lock screen while the screen two
                // taps away read `681 204`, and `1 samples` at the start of every run. The noun and its
                // plural live in one place for the same reason the grouping does — the other strings
                // here are fixed phrases, this one is a quantity.
                append(formatCounted(totalSamples, "sample"))
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

    /**
     * Tell the operator once, when the battery has fallen far enough that the run secured itself.
     *
     * **The only alerting notification in the app**, and it earns that because it is the one moment
     * where doing something — plugging in — changes the outcome of a run in progress. Everything the
     * app said about the battery before this was a foreground-only line on a screen nobody is looking
     * at with the phone in a pocket, plus a figure appended to a `PRIORITY_LOW` ongoing notification
     * with `setOnlyAlertOnce(true)`, which by construction cannot alert about anything that happens
     * after it is first posted.
     *
     * Not ongoing and dismissible: it reports something that has already happened, so leaving it
     * stuck to the shade would just be in the way.
     */
    private fun notifyBatteryCritical() {
        val contentIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, BATTERY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(getString(R.string.battery_alert_title))
            .setContentText(getString(R.string.battery_alert_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.battery_alert_text)))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { notificationManager().notify(BATTERY_NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "could not post the low-battery alert", it) }
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

        notificationManager().createNotificationChannel(
            NotificationChannel(
                BATTERY_CHANNEL_ID,
                getString(R.string.battery_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.battery_channel_description)
                setShowBadge(true)
            }
        )
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

        /** The line to mark a run with as it ends, set by the Stop dialog. */
        private const val EXTRA_CLOSING_NOTE = "se.rise.logline.extra.CLOSING_NOTE"

        /** Set only by [BootReceiver]; see [forThisStart] for what it changes. */
        private const val EXTRA_FROM_BOOT = "se.rise.logline.extra.FROM_BOOT"

        fun start(context: Context, fromBoot: Boolean = false) {
            val intent = Intent(context, PublisherService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_FROM_BOOT, fromBoot)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * @param closingNote marked against the run before it ends, or null. Carried through the
         *   intent rather than published by the caller, because the publisher is the only place that
         *   can put it out *before* it cancels the collectors — see `SensorPublisher.stopInternal`.
         */
        fun stop(context: Context, closingNote: String? = null) {
            val intent = Intent(context, PublisherService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_CLOSING_NOTE, closingNote)
            context.startService(intent)
        }
    }
}

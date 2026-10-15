package dev.caradb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import java.util.UUID
import android.os.Binder
import android.os.Build
import android.os.IBinder

/** Android foreground/binder ownership only; the controller owns the process-global Go engine. */
class TunnelService : Service() {
    inner class LocalBinder : Binder() {
        internal val controller: TunnelController get() = this@TunnelService.controller
    }

    private val binder = LocalBinder()
    private lateinit var controller: TunnelController
    private lateinit var notifications: NotificationManager
    private var owner = 0L
    private var commandStartId = 0
    private var latestDeliveredStartId = 0
    private var foreground = false
    private var destroyed = false
    private var notifiedText = ""
    private var notifiedToken: TunnelController.Token? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        controller = TunnelController.get(this)
        owner = controller.attach(this)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestDeliveredStartId = startId
        if (intent?.action == ACTION_STOP && !isCurrentStop(intent)) {
            // Ignore a previous session's action BEFORE it can replace the accepted lifecycle
            // token or cancel its timers. Android still counts this delivered startId for Stop.
            if (!foreground && !controller.hasWantedSession()) stopSelfResult(startId)
            return if (controller.snapshot.status.active && controller.snapshot.status.state != "stopping")
                START_STICKY else START_NOT_STICKY
        }
        val token = controller.claimStart(owner, startId) ?: return START_NOT_STICKY
        commandStartId = startId
        if (intent?.action == ACTION_STOP) {
            controller.requestStop()
            return START_NOT_STICKY
        }
        if (intent != null && intent.action !in setOf(ACTION_START, ACTION_RESTORE)) {
            controller.serviceReady(token, requestGeneration = null, stickyRestore = false)
            return START_NOT_STICKY
        }
        // Promote synchronously, before queued disk I/O, Keystore, static validation or DNS.
        try {
            val text = getString(R.string.status_starting)
            val notification = notification(text, token)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foreground = true
            notifiedText = text
            notifiedToken = token
        } catch (_: Exception) {
            controller.foregroundFailed(token)
            return START_NOT_STICKY
        }
        val epoch = if (intent?.hasExtra(EXTRA_GENERATION) == true) intent.getLongExtra(EXTRA_GENERATION, -1) else null
        controller.serviceReady(token, epoch, stickyRestore = intent == null, explicitRestore = intent?.action == ACTION_RESTORE)
        return START_STICKY
    }

    internal fun updateNotification(token: TunnelController.Token, status: TunnelStatus) {
        if (!owns(token) || !foreground) return
        val text = status.label(this)
        if (notifiedText == text && notifiedToken == token) return
        try {
            notifications.notify(NOTIFICATION_ID, notification(text, token))
            notifiedText = text
            notifiedToken = token
        } catch (_: Exception) {
            // Notification permission can change during a session; no secret or raw exception escapes.
        }
    }

    @Suppress("DEPRECATION")
    internal fun finishForeground(token: TunnelController.Token) {
        if (!owns(token) || latestDeliveredStartId == 0) return
        // The accepted token is still current. Include ignored stale actions in Android's startId
        // accounting without adopting them as new lifecycle commands or removing a newer session.
        if (!stopSelfResult(latestDeliveredStartId)) return
        if (foreground) {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
            foreground = false
        }
        notifications.cancel(NOTIFICATION_ID)
    }

    private fun owns(token: TunnelController.Token): Boolean =
        !destroyed && token.owner == owner && token.startId == commandStartId && controller.isCurrent(token)

    private fun isCurrentStop(intent: Intent): Boolean {
        val token = controller.token()
        return owns(token) && intent.data == stopIdentity(token)
    }

    private fun stopIdentity(token: TunnelController.Token): Uri =
        Uri.parse("caradb://stop/$PROCESS_SESSION/${token.owner}/${token.generation}/${token.startId}")

    @Suppress("DEPRECATION")
    private fun notification(text: String, token: TunnelController.Token): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TunnelService::class.java).setAction(ACTION_STOP).setData(stopIdentity(token)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_LOW)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(R.drawable.ic_notification, getString(R.string.disconnect), stop)
            .build()
    }

    override fun onDestroy() {
        destroyed = true
        controller.detach(owner)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.caradb.START"
        const val ACTION_STOP = "dev.caradb.STOP"
        const val ACTION_RESTORE = "dev.caradb.RESTORE"
        const val EXTRA_GENERATION = "generation"
        private const val CHANNEL = "adb_tunnel"
        private const val NOTIFICATION_ID = 1
        // A queued PendingIntent can outlive a process; owner/generation counters alone can repeat.
        private val PROCESS_SESSION = UUID.randomUUID().toString()

        internal fun launchConnect(context: Context, generation: Long) = launch(context, ACTION_START, generation)
        internal fun launchRestore(context: Context, generation: Long) = launch(context, ACTION_RESTORE, generation)

        private fun launch(context: Context, action: String, generation: Long) {
            // Intent carries only a non-secret epoch. Draft keys stay in process memory, not extras.
            val intent = Intent(context, TunnelService::class.java).setAction(action).putExtra(EXTRA_GENERATION, generation)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}

package com.kita.clips

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps the relay connection alive in the background and listens for clipboard changes.
 * Notification action "Send clipboard" opens the invisible capture activity, which is the
 * reliable way to read the clipboard on Android 10+.
 */
class ClipService : Service() {

    private lateinit var cm: ClipboardManager
    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        // Best effort: works while we have focus and on devices that still allow background reads.
        Hub.captureClipboard(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
        createChannel()

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val send = PendingIntent.getActivity(
            this, 1, Intent(this, ClipCaptureActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Kita Clips")
            .setContentText("Syncing your clipboard")
            .setContentIntent(open)
            .addAction(0, "Send clipboard", send)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        cm = getSystemService(ClipboardManager::class.java)
        cm.addPrimaryClipChangedListener(listener)

        Hub.ensureConnected()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (::cm.isInitialized) cm.removePrimaryClipChangedListener(listener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Clipboard sync", NotificationManager.IMPORTANCE_MIN),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 1

        fun start(ctx: Context) {
            try {
                androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, ClipService::class.java))
            } catch (_: Exception) {
                // Not allowed right now (e.g. app in background on a restricted device); ignore.
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ClipService::class.java))
        }
    }
}

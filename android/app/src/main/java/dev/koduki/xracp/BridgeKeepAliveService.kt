package dev.koduki.xracp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Android 15 Foreground Service (DATA_SYNC) that prevents the OS from suspending
 * the WebView process & active ACP WebSocket / Console connections when the user
 * focuses another spatial window in Android XR.
 */
class BridgeKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        ensureNotificationChannel()
        val title = intent?.getStringExtra(EXTRA_TITLE)
            ?: getString(R.string.keepalive_notification_title)
        val notification = buildNotification(title)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.keepalive_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, WorkbenchActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BridgeKeepAliveService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(getString(R.string.keepalive_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.keepalive_action_stop), stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "xr_acp_keepalive"
        private const val NOTIFICATION_ID = 1042
        private const val ACTION_STOP = "dev.koduki.xracp.action.STOP_KEEPALIVE"
        private const val EXTRA_TITLE = "extra_title"

        fun start(context: Context, title: String? = null) {
            val intent = Intent(context, BridgeKeepAliveService::class.java).apply {
                if (!title.isNullOrBlank()) putExtra(EXTRA_TITLE, title)
            }
            runCatching {
                context.startForegroundService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, BridgeKeepAliveService::class.java))
            }
        }
    }
}

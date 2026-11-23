package dev.koduki.xreditor

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
 * Keeps the embedded PRoot + openvscode-server process alive while the user
 * focuses sibling windows in Android XR Home Space.
 *
 * Implements Android 15 (API 35) `onTimeout` for `dataSync` FGS so exceeding
 * the OS 6-hour daily quota degrades gracefully instead of crashing the app.
 */
class ServerKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            PosixRuntimeManager.getInstance(this).stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        ensureNotificationChannel()
        val notification = buildNotification()
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

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 enforces a 6h/24h quota on dataSync foreground services;
        // stop the service cleanly rather than letting the system throw RemoteServiceException.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
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

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ServerKeepAliveService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.keepalive_notification_title))
            .setContentText(getString(R.string.keepalive_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.keepalive_action_stop), stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "xr_editor_server"
        private const val NOTIFICATION_ID = 2050
        private const val ACTION_STOP = "dev.koduki.xreditor.action.STOP_SERVER"

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, ServerKeepAliveService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ServerKeepAliveService::class.java))
            }
        }
    }
}

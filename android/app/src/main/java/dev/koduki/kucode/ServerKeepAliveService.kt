package dev.koduki.kucode

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
 * Uses `START_NOT_STICKY` so a process kill does not resurrect a ghost service
 * with a misleading "running" notification when no server process exists.
 * Uses `specialUse` foreground service type (avoiding Android 15's 6h/24h `dataSync`
 * quota) and guards `startForeground` / `onTimeout` so OEM/OS restrictions degrade
 * cleanly instead of crashing the app.
 */
class ServerKeepAliveService : Service() {

    internal enum class KeepAliveAction {
        START_FOREGROUND_AND_RUNTIME,
        STOP_RUNTIME_AND_SERVICE,
        STOP_SERVICE_ONLY,
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val telemetry = SessionTelemetryManager.getInstance(this)
        when (resolveAction(intent?.action, hasIntent = intent != null)) {
            KeepAliveAction.STOP_RUNTIME_AND_SERVICE -> {
                telemetry.info("android.service", "KeepAliveService stop requested via notification action")
                PosixRuntimeManager.getInstance(this).stop()
                telemetry.endCurrentSession(
                    reason = "User tapped Stop on foreground notification",
                    abnormal = false,
                    exitCategory = "CLEAN",
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            KeepAliveAction.STOP_SERVICE_ONLY -> {
                telemetry.info("android.service", "KeepAliveService started with null intent; stopping ghost service")
                stopSelf(startId)
                return START_NOT_STICKY
            }

            KeepAliveAction.START_FOREGROUND_AND_RUNTIME -> {
                telemetry.ensureSessionActive("keepalive_service_start")
                ensureNotificationChannel()
                val notification = buildNotification()
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } catch (e: Exception) {
                    telemetry.warn(
                        "android.service",
                        "KeepAliveService startForeground failed (${e.javaClass.simpleName}: ${e.message}); stopping keep-alive gracefully",
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }
                telemetry.info("android.service", "KeepAliveService running in foreground (type=specialUse)")
                PosixRuntimeManager.getInstance(this).startIfNeeded()
                return START_NOT_STICKY
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val telemetry = SessionTelemetryManager.getInstance(this)
        telemetry.info("android.service", "onTaskRemoved invoked (task swiped/closed)")
        telemetry.captureMetricSnapshotAsync("task_removed", includePss = true)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val telemetry = SessionTelemetryManager.getInstance(this)
        telemetry.warn(
            "android.service",
            "Foreground service timeout reached (startId=$startId, fgsType=$fgsType); degrading cleanly",
        )
        telemetry.captureMetricSnapshotAsync("fgs_timeout", includePss = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

        val variantTitle = "${getString(R.string.app_name_variant)} 起動中"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_kucode)
            .setContentTitle(variantTitle)
            .setContentText(getString(R.string.keepalive_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.keepalive_action_stop), stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "kucode_server"
        private const val NOTIFICATION_ID = 2050
        internal const val ACTION_STOP = "dev.koduki.kucode.action.STOP_SERVER"

        internal fun resolveAction(intentAction: String?, hasIntent: Boolean): KeepAliveAction = when {
            !hasIntent -> KeepAliveAction.STOP_SERVICE_ONLY
            intentAction == ACTION_STOP -> KeepAliveAction.STOP_RUNTIME_AND_SERVICE
            else -> KeepAliveAction.START_FOREGROUND_AND_RUNTIME
        }

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

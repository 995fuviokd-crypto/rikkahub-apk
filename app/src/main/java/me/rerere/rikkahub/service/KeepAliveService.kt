package me.rerere.rikkahub.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import me.rerere.rikkahub.KEEP_ALIVE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R

private const val TAG = "KeepAliveService"

/**
 * 后台保活前台服务: 进应用后立即在前台消息栏常驻显示"正在运行中",
 * 提升进程存活能力(配合忽略电池优化效果更佳)。
 */
class KeepAliveService : Service() {

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.action.KEEP_ALIVE_START"
        const val ACTION_STOP = "me.rerere.rikkahub.action.KEEP_ALIVE_STOP"
        const val NOTIFICATION_ID = 3001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            else -> {
                if (!startForegroundCompat()) {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            // 部分 OEM ROM 会拒绝 FGS 类型, 保活失败不影响 App 正常使用
            Log.e(TAG, "Failed to start keep-alive foreground service", e)
            false
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, KEEP_ALIVE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.notification_keep_alive_running))
            .setContentText(getString(R.string.notification_keep_alive_desc))
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}

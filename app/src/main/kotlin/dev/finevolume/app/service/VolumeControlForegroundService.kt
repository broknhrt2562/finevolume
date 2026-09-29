package dev.finevolume.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.finevolume.app.shizuku.ShizukuManager
import javax.inject.Inject

/**
 * Foreground service required to keep Shizuku Binder alive reliably
 * and to satisfy Android restrictions around background execution.
 * The notification must be minimal.
 */
@AndroidEntryPoint
class VolumeControlForegroundService : Service() {

    @Inject lateinit var shizukuManager: ShizukuManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        shizukuManager.cleanup()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "FineVolume Control",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "FineVolume is managing volume control"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FineVolume active")
            .setContentText("Volume control is active")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "finevolume_control"
    }
}

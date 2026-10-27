package com.bharath.cameramonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class CameraMonitorService : Service() {
    companion object {
        const val ACTION_PREPARE = "com.bharath.cameramonitor.PREPARE"
        const val ACTION_START_LIVE = "com.bharath.cameramonitor.START_LIVE"
        const val ACTION_STOP_LIVE = "com.bharath.cameramonitor.STOP_LIVE"
        private const val CHANNEL_ID = "camera_monitor_service"
        private const val NOTIFICATION_ID = 4102
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Remote camera ready"))
        CommandRepository.refreshFcmToken()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LIVE -> RtcEngine.startCamera(this) { updateNotification(it) }
            ACTION_STOP_LIVE -> RtcEngine.stopCamera { updateNotification(it) }
            ACTION_PREPARE, null -> updateNotification("Ready — waiting for remote command")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        RtcEngine.stopCamera()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Camera Monitor", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Camera Monitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
}

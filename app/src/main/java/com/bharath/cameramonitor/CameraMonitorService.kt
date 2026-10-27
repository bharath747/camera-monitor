package com.bharath.cameramonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.ListenerRegistration

class CameraMonitorService : Service() {
    companion object {
        const val ACTION_PREPARE = "com.bharath.cameramonitor.PREPARE"
        const val ACTION_START_LIVE = "com.bharath.cameramonitor.START_LIVE"
        const val ACTION_STOP_LIVE = "com.bharath.cameramonitor.STOP_LIVE"
        private const val CHANNEL_ID = "camera_monitor_service"
        private const val NOTIFICATION_ID = 4102
    }

    private var commandListener: ListenerRegistration? = null
    private val handledCommands = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting — connecting to Firebase"))
        CommandRepository.refreshFcmToken()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Commands are read directly from Firestore; Cloud Functions/FCM dispatch is not required.
        ensureCommandListener()
        when (intent?.action) {
            ACTION_START_LIVE -> startCamera()
            ACTION_STOP_LIVE -> stopCamera()
            ACTION_PREPARE, null -> updateNotification("Ready — listening for live commands")
        }
        return START_STICKY
    }

    private fun ensureCommandListener() {
        if (commandListener != null) return
        commandListener = CommandRepository.pendingCommands().addSnapshotListener { snapshot, error ->
            if (error != null) {
                updateNotification("Firestore listener error: ${error.message ?: "permission/network error"}")
                return@addSnapshotListener
            }
            snapshot?.documents?.forEach { document ->
                if (!handledCommands.add(document.id)) return@forEach
                val action = document.getString("action")?.uppercase()
                document.reference.update("status", "PROCESSING")
                    .addOnSuccessListener {
                        when (action) {
                            "START_LIVE" -> startCamera()
                            "STOP_LIVE" -> stopCamera()
                            else -> document.reference.update(
                                mapOf("status" to "FAILED", "error" to "Unknown action: $action")
                            )
                        }
                    }
                    .addOnFailureListener { failure ->
                        handledCommands.remove(document.id)
                        updateNotification("Cannot claim command: ${failure.message ?: "Firestore permission denied"}")
                    }
            }
        }
    }

    private fun startCamera() {
        updateNotification("Opening camera…")
        RtcEngine.startCamera(this) { message -> updateNotification(message) }
    }

    private fun stopCamera() {
        RtcEngine.stopCamera { updateNotification(it) }
    }

    override fun onDestroy() {
        commandListener?.remove()
        commandListener = null
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

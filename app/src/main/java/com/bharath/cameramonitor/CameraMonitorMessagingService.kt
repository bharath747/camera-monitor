package com.bharath.cameramonitor

import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CameraMonitorMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { CommandRepository.registerToken(token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["action"]?.uppercase()) {
            "START_LIVE" -> {
                val intent = Intent(this, CameraMonitorService::class.java)
                    .setAction(CameraMonitorService.ACTION_START_LIVE)
                runCatching { ContextCompat.startForegroundService(this, intent) }
            }
            "STOP_LIVE" -> {
                val intent = Intent(this, CameraMonitorService::class.java)
                    .setAction(CameraMonitorService.ACTION_STOP_LIVE)
                runCatching { startService(intent) }
            }
        }
    }
}

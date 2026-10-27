package com.bharath.cameramonitor

import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class CameraMonitorMessagingService : FirebaseMessagingService() {
    private val deviceRef
        get() = FirebaseFirestore.getInstance()
            .collection("devices")
            .document(CommandRepository.CAMERA_DEVICE_ID)

    private fun report(event: String, error: String? = null) {
        val fields = mutableMapOf<String, Any>(
            "lastCommandEvent" to event,
            "lastCommandEventAt" to FieldValue.serverTimestamp()
        )
        if (error != null) fields["lastCommandError"] = error
        deviceRef.update(fields)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Thread {
            runCatching { kotlinx.coroutines.runBlocking { CommandRepository.registerToken(token) } }
                .onFailure { report("TOKEN_REGISTER_FAILED", it.message ?: "Unknown token registration error") }
                .onSuccess { report("TOKEN_REFRESHED") }
        }.start()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val action = message.data["action"]?.uppercase()
        report("FCM_RECEIVED_${action ?: "NO_ACTION"}")
        when (action) {
            "START_LIVE" -> {
                val intent = Intent(this, CameraMonitorService::class.java)
                    .setAction(CameraMonitorService.ACTION_START_LIVE)
                try {
                    ContextCompat.startForegroundService(this, intent)
                    report("START_LIVE_SERVICE_REQUESTED")
                } catch (error: Exception) {
                    report("START_LIVE_SERVICE_FAILED", error.message ?: error.javaClass.simpleName)
                }
            }
            "STOP_LIVE" -> {
                try {
                    startService(Intent(this, CameraMonitorService::class.java)
                        .setAction(CameraMonitorService.ACTION_STOP_LIVE))
                    report("STOP_LIVE_SERVICE_REQUESTED")
                } catch (error: Exception) {
                    report("STOP_LIVE_SERVICE_FAILED", error.message ?: error.javaClass.simpleName)
                }
            }
            else -> report("FCM_UNKNOWN_ACTION", "Received action: ${action ?: "null"}")
        }
    }
}

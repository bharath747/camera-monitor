package com.bharath.cameramonitor

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

object CommandRepository {
    const val CAMERA_DEVICE_ID = "camera-monitor-device"
    private val db get() = FirebaseFirestore.getInstance()

    fun pendingCommands(): Query =
        db.collection("devices").document(CAMERA_DEVICE_ID)
            .collection("commands")
            .whereEqualTo("status", "PENDING")

    suspend fun registerToken(token: String) {
        db.collection("devices").document(CAMERA_DEVICE_ID).set(
            mapOf(
                "fcmToken" to token,
                "deviceName" to "Camera Monitor",
                "deviceType" to "camera-monitor",
                "tokenUpdatedAt" to FieldValue.serverTimestamp(),
                "lastSeen" to FieldValue.serverTimestamp()
            ), SetOptions.merge()
        ).await()
    }

    fun refreshFcmToken(onResult: (String?) -> Unit = {}) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { registerToken(token) }
                        .onSuccess { onResult(null) }
                        .onFailure { onResult(it.message ?: "Could not register FCM token") }
                }
            }
            .addOnFailureListener { onResult(it.message ?: "Could not get FCM token") }
    }

    suspend fun sendCommand(action: String) {
        val commandId = UUID.randomUUID().toString()
        db.collection("devices").document(CAMERA_DEVICE_ID)
            .collection("commands").document(commandId)
            .set(mapOf(
                "action" to action,
                "status" to "PENDING",
                "createdAt" to FieldValue.serverTimestamp(),
                "source" to "camera-monitor"
            )).await()
    }
}

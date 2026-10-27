package com.bharath.cameramonitor

import android.Manifest
import androidx.activity.ComponentActivity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val cameraOk = result[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val notificationsOk = Build.VERSION.SDK_INT < 33 ||
            result[Manifest.permission.POST_NOTIFICATIONS] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (cameraOk && notificationsOk) {
            ContextCompat.startForegroundService(this, Intent(this, CameraMonitorService::class.java).setAction(CameraMonitorService.ACTION_PREPARE))
            status.text = "Camera service enabled. Leave this notification active on the camera phone."
        } else status.text = "Camera and notification permissions are required."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 40)
        }
        fun addText(text: String, size: Float = 16f) {
            layout.addView(TextView(this).apply { this.text = text; textSize = size; setPadding(0, 12, 0, 12) })
        }
        fun addButton(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        addText("Camera Monitor", 26f)
        addText("On-demand live video for personal use. No recording.")
        addText("CAMERA PHONE", 18f)
        addText("On the unattended phone, enable the camera service once while the app is open. Keep the phone charged and allow background activity.")
        addButton("Enable Remote Camera") {
            val permissions = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
            permissionLauncher.launch(permissions.toTypedArray())
        }
        addButton("Disable Remote Camera") {
            stopService(Intent(this, CameraMonitorService::class.java))
            status.text = "Camera service stopped."
        }
        addText("VIEWER PHONE", 18f)
        addText("Install the same APK on your viewing phone. Tap Watch Live to open the live viewer and send the start command.")
        addButton("Watch Live") { startActivity(Intent(this, ViewerActivity::class.java)) }
        addButton("Stop Live") {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { CommandRepository.sendCommand("STOP_LIVE") }
                    .onSuccess { runOnUiThread { status.text = "Stop command sent." } }
                    .onFailure { runOnUiThread { status.text = "Command failed: " + it.message } }
            }
        }
        status = TextView(this).apply { text = "Connecting to Firebase…"; textSize = 14f; setPadding(0, 18, 0, 0) }
        layout.addView(status)
        setContentView(layout)

        CoroutineScope(Dispatchers.IO).launch {
            runCatching { FirebaseAuth.getInstance().signInAnonymously().await() }
                .onSuccess {
                    CommandRepository.refreshFcmToken { error ->
                        runOnUiThread {
                            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "unknown"
                            val tokenStatus = error?.let { "FCM token registration error: " + it } ?: "FCM token registered."
                            status.text = "Firebase UID: " + uid + "\n" + tokenStatus
                        }
                    }
                }
                .onFailure { runOnUiThread { status.text = "Firebase setup error: " + it.message } }
        }
    }
}

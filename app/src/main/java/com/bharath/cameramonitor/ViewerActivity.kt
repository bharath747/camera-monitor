package com.bharath.cameramonitor

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ViewerActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var renderer: org.webrtc.SurfaceViewRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderer = org.webrtc.SurfaceViewRenderer(this)
        status = TextView(this).apply { text = "Preparing live viewer…"; textSize = 14f; setPadding(12, 12, 12, 12) }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(Button(this).apply {
            text = "Start Live"
            setOnClickListener {
                status.text = "Connecting…"
                RtcEngine.startViewer(this@ViewerActivity, renderer) { runOnUiThread { status.text = it } }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener {
                RtcEngine.stopViewer { runOnUiThread { status.text = it } }
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { CommandRepository.sendCommand("STOP_LIVE") }
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(renderer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(controls)
            addView(status)
        }
        setContentView(root)
    }

    override fun onDestroy() {
        RtcEngine.stopViewer()
        renderer.release()
        super.onDestroy()
    }
}

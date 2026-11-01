package com.shyamannirman.cctv

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID

class AddCameraActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Session.isAdmin()) { finish(); return }
        setContentView(R.layout.activity_add)

        val advancedToggle = findViewById<TextView>(R.id.tvAdvancedToggle)
        val advancedSection = findViewById<View>(R.id.advancedSection)
        advancedToggle.setOnClickListener {
            val showing = advancedSection.visibility == View.VISIBLE
            advancedSection.visibility = if (showing) View.GONE else View.VISIBLE
            advancedToggle.text = if (showing) "Advanced options ▾" else "Advanced options ▴"
        }

        val name = findViewById<EditText>(R.id.etName)
        val location = findViewById<EditText>(R.id.etLocation)
        val host = findViewById<EditText>(R.id.etHost)
        val port = findViewById<EditText>(R.id.etPort)
        val user = findViewById<EditText>(R.id.etUser)
        val pass = findViewById<EditText>(R.id.etPass)
        val channel = findViewById<EditText>(R.id.etChannel)
        val sub = findViewById<RadioButton>(R.id.rbSub)
        val customUrl = findViewById<EditText>(R.id.etCustomUrl)
        val onvifCheck = findViewById<CheckBox>(R.id.cbOnvif)
        val onvifPort = findViewById<EditText>(R.id.etOnvifPort)

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val h = host.text.toString().trim()
            val u = user.text.toString().trim()
            val custom = customUrl.text.toString().trim()
            val usingCustom = custom.isNotEmpty()

            if (!usingCustom && (h.isEmpty() || u.isEmpty() || pass.text.isEmpty())) {
                Toast.makeText(this, "Enter a custom RTSP URL, or fill IP, ID and password", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (usingCustom && !custom.startsWith("rtsp://")) {
                Toast.makeText(this, "Custom URL must start with rtsp://", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val cam = Camera(
                id = UUID.randomUUID().toString(),
                name = name.text.toString().trim().ifEmpty { "Camera" },
                location = location.text.toString().trim(),
                host = h,
                port = port.text.toString().toIntOrNull() ?: 554,
                user = u,
                pass = pass.text.toString(),
                channel = channel.text.toString().toIntOrNull() ?: 1,
                subtype = if (sub.isChecked) 1 else 0,
                customUrl = custom,
                onvifEnabled = onvifCheck.isChecked && !usingCustom,
                onvifPort = onvifPort.text.toString().toIntOrNull() ?: 80,
                createdAt = System.currentTimeMillis()
            )
            CameraStore.add(cam)
            finish()
        }
    }
}

package com.shyamannirman.cctv

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class LiveActivity : AppCompatActivity() {
    // libVLC player (replaces ExoPlayer, which rejected this camera's stream description).
    private var libVlc: LibVLC? = null
    private var vlcPlayer: MediaPlayer? = null
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var playerView: View   // old layout view, kept hidden so the layout file needs no change
    private lateinit var status: TextView
    private lateinit var controls: View
    private var camera: Camera? = null

    // Digital zoom: pinch tracks the finger directly; the reset button eases back with a spring.
    private var scaleFactor = 1f
    private lateinit var scaleDetector: ScaleGestureDetector
    private var springX: SpringAnimation? = null
    private var springY: SpringAnimation? = null

    // Controls auto-hide after a few seconds of inactivity.
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { setControlsVisible(false) }

    // Auto-reconnect on a dropped/failed connection, with a short backoff.
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempt = 0
    private var manuallyStopped = false

    // PTZ (ONVIF), only used when the camera has it enabled.
    private val ptzExecutor = Executors.newSingleThreadExecutor()
    private var ptz: OnvifPtz? = null
    private var ptzToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playerView = findViewById(R.id.player)
        status = findViewById(R.id.status)
        controls = findViewById(R.id.controls)

        // Put the VLC video view exactly where the old player view was, then hide the old one.
        videoLayout = VLCVideoLayout(this)
        val parent = playerView.parent as ViewGroup
        val index = parent.indexOfChild(playerView)
        parent.addView(videoLayout, index, playerView.layoutParams)
        playerView.visibility = View.GONE

        camera = CameraStore.find(intent.getStringExtra("id") ?: "")
        findViewById<TextView>(R.id.tvTitle).text =

package com.shyamannirman.cctv

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class LiveActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
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
        camera = CameraStore.find(intent.getStringExtra("id") ?: "")
        findViewById<TextView>(R.id.tvTitle).text = camera?.name ?: "Live"
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        springX = SpringAnimation(playerView, DynamicAnimation.SCALE_X).setSpring(
            SpringForce(1f).setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_LOW)
        )
        springY = SpringAnimation(playerView, DynamicAnimation.SCALE_Y).setSpring(
            SpringForce(1f).setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_LOW)
        )

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(1f, 4f)
                playerView.scaleX = scaleFactor
                playerView.scaleY = scaleFactor
                return true
            }
        })
        playerView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) toggleControls()
            bumpAutoHide()
            true
        }

        findViewById<ImageButton>(R.id.btnSnapshot).setOnClickListener { takeSnapshotWithPermissionCheck() }
        findViewById<ImageButton>(R.id.btnResetZoom).setOnClickListener {
            scaleFactor = 1f
            springX?.animateToFinalPosition(1f)
            springY?.animateToFinalPosition(1f)
        }

        setupPtz()
        bumpAutoHide()
    }

    override fun onStart() { super.onStart(); manuallyStopped = false; reconnectAttempt = 0; start() }
    override fun onStop() {
        super.onStop()
        manuallyStopped = true
        reconnectHandler.removeCallbacksAndMessages(null)
        release()
    }
    override fun onDestroy() {
        super.onDestroy()
        ptzExecutor.shutdownNow()
        hideHandler.removeCallbacks(hideRunnable)
        reconnectHandler.removeCallbacksAndMessages(null)
    }

    // ---------- Controls visibility ----------

    private fun toggleControls() = setControlsVisible(controls.alpha == 0f)

    private fun setControlsVisible(visible: Boolean) {
        controls.animate().cancel()
        controls.animate().alpha(if (visible) 1f else 0f).setDuration(220).start()
        if (visible) bumpAutoHide() else hideHandler.removeCallbacks(hideRunnable)
    }

    private fun bumpAutoHide() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 3500)
    }

    // ---------- Playback ----------

    private fun start() {
        val cam = camera ?: run { status.text = "Camera not found"; return }
        // Like VLC, try both ways: some cameras/DVRs only work with TCP, others only with UDP.
        // Attempts alternate TCP, UDP, TCP, UDP... until one connects.
        val useTcp = reconnectAttempt % 2 == 0
        val mode = if (useTcp) "TCP" else "UDP"
        status.text = "Connecting ($mode)…"
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(useTcp)
            .setTimeoutMs(15000)
            .setUserAgent("LibVLC/3.0.18")
            .createMediaSource(MediaItem.fromUri(Uri.parse(cam.rtspUrl())))
        player = ExoPlayer.Builder(this).build().also { p ->
            playerView.player = p
            p.setMediaSource(source)
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) { status.text = ""; reconnectAttempt = 0 }
                    if (state == Player.STATE_ENDED) scheduleReconnect("Stream ended — reconnecting…")
                }
                override fun onPlayerError(error: PlaybackException) {
                    // Show the real reason (e.g. "401 Unauthorized", "timeout", "refused") to help diagnose.
                    val root = generateSequence<Throwable>(error) { it.cause }.last()
                    val detail = root.message ?: root.javaClass.simpleName
                    scheduleReconnect("Can't connect ($mode) — retrying…\n${error.errorCodeName}: $detail")
                }
            })
            p.prepare()
            p.playWhenReady = true
        }
    }

    private fun release() {
        player?.release(); player = null
    }

    /** Retries with a short, capped backoff (2s, 4s, 8s, then steady at 15s) rather than hammering the camera. */
    private fun scheduleReconnect(message: String) {
        if (manuallyStopped) return
        status.text = message
        release()
        reconnectAttempt++
        val delayMs = minOf(2000L * (1 shl (reconnectAttempt - 1).coerceAtMost(3)), 15000L)
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectHandler.postDelayed({ if (!manuallyStopped) start() }, delayMs)
    }

    // ---------- Snapshot ----------

    private fun takeSnapshotWithPermissionCheck() {
        // Scoped storage (API 29+) needs no permission for this; only older versions do.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100
            )
            return
        }
        takeSnapshot()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            takeSnapshot()
        } else if (requestCode == 100) {
            Toast.makeText(this, "Storage permission is needed to save snapshots", Toast.LENGTH_SHORT).show()
        }
    }

    /** Captures what's currently on screen (post-zoom) via PixelCopy and saves to Pictures. */
    private fun takeSnapshot() {
        val bitmap = Bitmap.createBitmap(playerView.width, playerView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                saveBitmap(bitmap)
            } else {
                runOnUiThread { Toast.makeText(this, "Snapshot failed", Toast.LENGTH_SHORT).show() }
            }
        }, Handler(mainLooper))
    }

    private fun saveBitmap(bitmap: Bitmap) {
        val name = "CPPlus_${camera?.name ?: "cam"}_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CPPlusViewer")
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        runOnUiThread {
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                Toast.makeText(this, "Saved to Pictures/CPPlusViewer", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Could not save snapshot", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- PTZ (ONVIF) ----------

    private fun setupPtz() {
        val cam = camera ?: return
        val pad = findViewById<View>(R.id.ptzPad)
        if (!cam.onvifEnabled || !Session.isAdmin()) { pad.visibility = View.GONE; return }
        pad.visibility = View.VISIBLE

        ptz = OnvifPtz(cam.host, cam.onvifPort, cam.user, cam.pass)
        ptzExecutor.execute {
            ptzToken = ptz?.getProfileToken()
            if (ptzToken == null) runOnUiThread {
                Toast.makeText(this, "PTZ: could not reach ONVIF service on this camera", Toast.LENGTH_LONG).show()
            }
        }

        fun bind(id: Int, x: Float, y: Float) {
            findViewById<ImageButton>(id).setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> ptzExecutor.execute {
                        ptzToken?.let { t -> ptz?.continuousMove(t, x, y) }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ptzExecutor.execute {
                        ptzToken?.let { t -> ptz?.stop(t) }
                    }
                }
                true
            }
        }
        bind(R.id.btnPtzUp, 0f, 1f)
        bind(R.id.btnPtzDown, 0f, -1f)
        bind(R.id.btnPtzLeft, -1f, 0f)
        bind(R.id.btnPtzRight, 1f, 0f)
    }
}

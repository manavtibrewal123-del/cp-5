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
        findViewById<TextView>(R.id.tvTitle).text = camera?.name ?: "Live"
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        springX = SpringAnimation(videoLayout, DynamicAnimation.SCALE_X).setSpring(
            SpringForce(1f).setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_LOW)
        )
        springY = SpringAnimation(videoLayout, DynamicAnimation.SCALE_Y).setSpring(
            SpringForce(1f).setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_LOW)
        )

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(1f, 4f)
                videoLayout.scaleX = scaleFactor
                videoLayout.scaleY = scaleFactor
                return true
            }
        })
        videoLayout.setOnTouchListener { _, event ->
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

    // ---------- Playback (libVLC) ----------

    private fun start() {
        val cam = camera ?: run { status.text = "Camera not found"; return }
        // Alternate TCP and UDP on each retry, like VLC: some cameras only work with one of them.
        val useTcp = reconnectAttempt % 2 == 0
        val mode = if (useTcp) "TCP" else "UDP"
        status.text = "Connecting ($mode)…"

        release()
        val vlc = LibVLC(this, arrayListOf<String>())
        val mp = MediaPlayer(vlc)
        libVlc = vlc
        vlcPlayer = mp

        mp.attachViews(videoLayout, null, false, false)
        mp.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT

        val media = Media(vlc, Uri.parse(cam.rtspUrl()))
        media.addOption(":network-caching=500")
        if (useTcp) media.addOption(":rtsp-tcp")
        mp.media = media
        media.release()

        mp.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> runOnUiThread { status.text = ""; reconnectAttempt = 0 }
                MediaPlayer.Event.EncounteredError ->
                    runOnUiThread { scheduleReconnect("Can't connect ($mode) — retrying…") }
                MediaPlayer.Event.EndReached ->
                    runOnUiThread { scheduleReconnect("Stream ended — reconnecting…") }
            }
        }
        mp.play()
    }

    private fun release() {
        vlcPlayer?.let { mp ->
            mp.setEventListener(null)
            try { mp.stop() } catch (_: Exception) {}
            try { mp.detachViews() } catch (_: Exception) {}
            mp.release()
        }
        vlcPlayer = null
        libVlc?.release()
        libVlc = null
    }

    /** Retries with a short, capped backoff (2s, 4s, 8s, then steady at 15s) rather than hammering the camera. */
    private fun scheduleReconnect(message: String) {
        if (manuallyStopped) return
        status.text = message
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

    /** Captures the video area as shown on screen (post-zoom) via PixelCopy and saves to Pictures. */
    private fun takeSnapshot() {
        if (videoLayout.width == 0 || videoLayout.height == 0) return
        val loc = IntArray(2)
        videoLayout.getLocationInWindow(loc)
        val rect = Rect(loc[0], loc[1], loc[0] + videoLayout.width, loc[1] + videoLayout.height)
        val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, rect, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    saveBitmap(bitmap)
                } else {
                    runOnUiThread { Toast.makeText(this, "Snapshot failed", Toast.LENGTH_SHORT).show() }
                }
            }, Handler(mainLooper))
        } catch (e: Exception) {
            Toast.makeText(this, "Snapshot failed", Toast.LENGTH_SHORT).show()
        }
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

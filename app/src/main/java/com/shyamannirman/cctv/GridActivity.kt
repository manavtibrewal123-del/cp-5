package com.shyamannirman.cctv

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** Shows saved cameras together using the light sub-stream, in a 2x2 or 3x3 grid (libVLC player). */
class GridActivity : AppCompatActivity() {
    private var libVlc: LibVLC? = null
    private val players = mutableListOf<MediaPlayer>()
    private lateinit var grid: GridLayout
    private lateinit var btn2x2: Button
    private lateinit var btn3x3: Button
    private var columns = 2
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var stopped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val bg = ContextCompat.getColor(this, R.color.bg)
        val surface = ContextCompat.getColor(this, R.color.surface_raised)
        val accent = ContextCompat.getColor(this, R.color.accent)
        val textSecondary = ContextCompat.getColor(this, R.color.text_secondary)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val toggleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 24, 20, 16)
        }
        btn2x2 = Button(this).apply { text = "2 × 2"; setOnClickListener { columns = 2; build() } }
        btn3x3 = Button(this).apply { text = "3 × 3"; setOnClickListener { columns = 3; build() } }
        for (b in listOf(btn2x2, btn3x3)) {
            b.setTextColor(textSecondary)
            b.setBackgroundColor(surface)
        }
        toggleBar.addView(btn2x2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 10 })
        toggleBar.addView(btn3x3, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(toggleBar)

        grid = GridLayout(this).apply { columnCount = columns }
        root.addView(ScrollView(this).apply { addView(grid) })
        setContentView(root)
        highlightToggle(accent, textSecondary)
    }

    private fun highlightToggle(accent: Int, dim: Int) {
        btn2x2.setTextColor(if (columns == 2) accent else dim)
        btn3x3.setTextColor(if (columns == 3) accent else dim)
    }

    override fun onStart() { super.onStart(); stopped = false; build() }
    override fun onStop() {
        super.onStop()
        stopped = true
        reconnectHandler.removeCallbacksAndMessages(null)
        release()
    }

    private var buildCount = 0

    private fun build() {
        buildCount++
        release()
        grid.removeAllViews()
        grid.columnCount = columns
        highlightToggle(ContextCompat.getColor(this, R.color.accent), ContextCompat.getColor(this, R.color.text_secondary))
        val dm = resources.displayMetrics
        val cellW = dm.widthPixels / columns
        val cellH = cellW * 9 / 16

        // One LibVLC engine is shared by all tiles.
        val vlc = LibVLC(this, arrayListOf<String>())
        libVlc = vlc
        val useTcp = buildCount % 2 == 1 // alternate TCP / UDP on each rebuild

        for (cam in CameraStore.all()) {
            val cell = FrameLayout(this)
            val videoLayout = VLCVideoLayout(this)
            val label = TextView(this).apply {
                text = cam.name
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0x88000000.toInt())
                setPadding(12, 4, 12, 4)
                textSize = if (columns == 3) 10f else 12f
            }
            cell.addView(videoLayout, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            cell.addView(label, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START))
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = cellW; height = cellH; setMargins(0, 0, 2, 2)
            })

            val mp = MediaPlayer(vlc)
            mp.attachViews(videoLayout, null, false, false)
            mp.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
            mp.volume = 0

            val media = Media(vlc, Uri.parse(cam.rtspUrl(sub = 1)))
            media.addOption(":network-caching=300")
            media.addOption(":no-audio") // tiles are silent, and this skips the audio track entirely
            if (useTcp) media.addOption(":rtsp-tcp")
            mp.media = media
            media.release()

            mp.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.EncounteredError,
                    MediaPlayer.Event.EndReached -> runOnUiThread { scheduleRebuild() }
                }
            }
            mp.play()
            players.add(mp)
        }
    }

    private fun release() {
        players.forEach { mp ->
            mp.setEventListener(null)
            try { mp.stop() } catch (_: Exception) {}
            try { mp.detachViews() } catch (_: Exception) {}
            mp.release()
        }
        players.clear()
        libVlc?.release()
        libVlc = null
    }

    /** One dropped tile rebuilds the whole grid after a short delay — simple and fine
     *  for the handful-of-tiles-on-screen case this view is meant for. */
    private fun scheduleRebuild() {
        if (stopped) return
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectHandler.postDelayed({ if (!stopped) build() }, 5000)
    }
}

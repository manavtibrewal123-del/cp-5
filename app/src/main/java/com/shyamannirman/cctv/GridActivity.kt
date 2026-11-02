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
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

/** Shows saved cameras together using the light sub-stream, in a 2x2 or 3x3 grid. */
@OptIn(UnstableApi::class)
class GridActivity : AppCompatActivity() {
    private val players = mutableListOf<ExoPlayer>()
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
        for (cam in CameraStore.all()) {
            val cell = FrameLayout(this)
            val pv = PlayerView(this).apply { useController = false }
            val label = TextView(this).apply {
                text = cam.name
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0x88000000.toInt())
                setPadding(12, 4, 12, 4)
                textSize = if (columns == 3) 10f else 12f
            }
            cell.addView(pv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            cell.addView(label, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START))
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = cellW; height = cellH; setMargins(0, 0, 2, 2)
            })

            val source = RtspMediaSource.Factory()
                .setForceUseRtpTcp(buildCount % 2 == 1) // alternate TCP / UDP on each rebuild
                .setTimeoutMs(15000)
                .setUserAgent("LibVLC/3.0.18")
                .createMediaSource(MediaItem.fromUri(Uri.parse(cam.rtspUrl(sub = 1))))
            val p = ExoPlayer.Builder(this).build()
            pv.player = p
            p.setMediaSource(source)
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { scheduleRebuild() }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) scheduleRebuild()
                }
            })
            p.prepare()
            p.playWhenReady = true
            p.volume = 0f
            players.add(p)
        }
    }

    private fun release() {
        players.forEach { it.release() }
        players.clear()
    }

    /** One dropped tile rebuilds the whole grid after a short delay — simple and fine
     *  for the handful-of-tiles-on-screen case this view is meant for. */
    private fun scheduleRebuild() {
        if (stopped) return
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectHandler.postDelayed({ if (!stopped) build() }, 5000)
    }
}

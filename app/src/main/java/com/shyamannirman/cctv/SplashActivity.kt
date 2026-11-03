package com.shyamannirman.cctv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Brief branded splash, then sends you to first-time admin setup or the sign-in screen.
 *  Any previous sign-in is dropped here, so the app always asks for a login after a restart. */
class SplashActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var shownAt = 0L

    // How long the splash screen stays visible, in milliseconds (2500 = 2.5 seconds).
    private val splashMillis = 2500L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        Session.logout()
        shownAt = System.currentTimeMillis()
        route()
    }

    private fun route() {
        UserStore.isSetupDone { done ->
            if (isFinishing) return@isSetupDone
            if (done == null) {
                Toast.makeText(this, "Can't reach the server — check your internet. Retrying…", Toast.LENGTH_SHORT).show()
                handler.postDelayed({ route() }, 3000)
                return@isSetupDone
            }
            val wait = (splashMillis - (System.currentTimeMillis() - shownAt)).coerceAtLeast(0)
            handler.postDelayed({
                val next = if (done) LoginActivity::class.java else AdminSetupActivity::class.java
                startActivity(Intent(this, next))
                finish()
            }, wait)
        }
    }

    override fun onDestroy() { super.onDestroy(); handler.removeCallbacksAndMessages(null) }
}

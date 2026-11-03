package com.shyamannirman.cctv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Branded splash. If someone is already signed in (saved on this phone), goes straight to the
 *  camera list. Otherwise goes to first-time admin setup or the sign-in screen.
 *  Signing out happens only when the user taps Logout in Settings. */
class SplashActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var shownAt = 0L

    // How long the splash screen stays visible, in milliseconds (2500 = 2.5 seconds).
    private val splashMillis = 2500L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        shownAt = System.currentTimeMillis()
        restoreOrRoute()
    }

    /** Waits out the rest of the splash time, then runs [action]. */
    private fun afterSplash(action: () -> Unit) {
        val wait = (splashMillis - (System.currentTimeMillis() - shownAt)).coerceAtLeast(0)
        handler.postDelayed({ if (!isFinishing) action() }, wait)
    }

    private fun restoreOrRoute() {
        UserStore.restoreSession { user, failed ->
            if (isFinishing) return@restoreSession
            when {
                user != null -> {
                    Session.login(user)
                    afterSplash {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                }
                failed -> {
                    Toast.makeText(this, "Can't reach the server — check your internet. Retrying…", Toast.LENGTH_SHORT).show()
                    handler.postDelayed({ restoreOrRoute() }, 3000)
                }
                else -> route()   // nobody signed in (or the account was removed)
            }
        }
    }

    private fun route() {
        UserStore.isSetupDone { done ->
            if (isFinishing) return@isSetupDone
            if (done == null) {
                Toast.makeText(this, "Can't reach the server — check your internet. Retrying…", Toast.LENGTH_SHORT).show()
                handler.postDelayed({ route() }, 3000)
                return@isSetupDone
            }
            afterSplash {
                val next = if (done) LoginActivity::class.java else AdminSetupActivity::class.java
                startActivity(Intent(this, next))
                finish()
            }
        }
    }

    override fun onDestroy() { super.onDestroy(); handler.removeCallbacksAndMessages(null) }
}

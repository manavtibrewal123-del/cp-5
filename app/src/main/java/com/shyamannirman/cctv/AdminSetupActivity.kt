package com.shyamannirman.cctv

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Shown only until the single admin account exists. */
class AdminSetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_setup)
        val username = findViewById<EditText>(R.id.etUsername)
        val password = findViewById<EditText>(R.id.etPassword)
        val error = findViewById<TextView>(R.id.tvSetupError)
        val button = findViewById<Button>(R.id.btnCreate)

        button.setOnClickListener {
            val u = username.text.toString().trim()
            val p = password.text.toString()
            when {
                !UserStore.validUsername(u) -> showError(error, UserStore.USERNAME_HINT)
                p.length < UserStore.MIN_PASSWORD ->
                    showError(error, "Password needs at least ${UserStore.MIN_PASSWORD} characters")
                else -> {
                    button.isEnabled = false
                    error.visibility = TextView.INVISIBLE
                    UserStore.createAdmin(u, p) { err ->
                        button.isEnabled = true
                        if (err == null) {
                            startActivity(Intent(this, MainActivity::class.java))
                            finish()
                        } else showError(error, err)
                    }
                }
            }
        }
    }

    private fun showError(view: TextView, msg: String) {
        view.text = msg
        view.visibility = TextView.VISIBLE
    }
}

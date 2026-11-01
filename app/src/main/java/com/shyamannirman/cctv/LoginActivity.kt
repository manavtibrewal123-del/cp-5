package com.shyamannirman.cctv

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        val username = findViewById<EditText>(R.id.etUsername)
        val password = findViewById<EditText>(R.id.etPassword)
        val error = findViewById<TextView>(R.id.tvLoginError)
        val button = findViewById<Button>(R.id.btnLogin)

        button.setOnClickListener {
            val u = username.text.toString().trim()
            val p = password.text.toString()
            if (u.isEmpty() || p.isEmpty()) {
                error.text = "Enter your username and password"
                error.visibility = TextView.VISIBLE
                return@setOnClickListener
            }
            button.isEnabled = false
            error.visibility = TextView.INVISIBLE
            UserStore.login(u, p) { user, message ->
                button.isEnabled = true
                if (user != null) {
                    Session.login(user)
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                } else {
                    error.text = message ?: "Incorrect username or password"
                    error.visibility = TextView.VISIBLE
                    password.text.clear()
                }
            }
        }
    }
}

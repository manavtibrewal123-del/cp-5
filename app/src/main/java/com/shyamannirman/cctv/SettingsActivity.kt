package com.shyamannirman.cctv

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private lateinit var userList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val me = Session.currentUser
        findViewById<TextView>(R.id.tvSignedInAs).text =
            "Signed in as ${me?.username ?: "?"} (${me?.role?.name?.lowercase() ?: ""})"

        userList = findViewById(R.id.userList)
        val adminSection = findViewById<LinearLayout>(R.id.adminSection)

        if (Session.isAdmin()) {
            adminSection.visibility = LinearLayout.VISIBLE
            refreshUserList()

            val btnAdd = findViewById<Button>(R.id.btnAddUser)
            btnAdd.setOnClickListener {
                val u = findViewById<EditText>(R.id.etNewUsername)
                val p = findViewById<EditText>(R.id.etNewPassword)
                val username = u.text.toString().trim()
                val password = p.text.toString()
                when {
                    !UserStore.validUsername(username) -> toast(UserStore.USERNAME_HINT)
                    password.length < UserStore.MIN_PASSWORD ->
                        toast("Password needs at least ${UserStore.MIN_PASSWORD} characters")
                    else -> {
                        btnAdd.isEnabled = false
                        UserStore.addViewer(this, username, password) { err ->
                            btnAdd.isEnabled = true
                            if (err == null) {
                                u.text.clear(); p.text.clear()
                                toast("Viewer account added")
                                refreshUserList()
                            } else toast(err)
                        }
                    }
                }
            }

            val legacy = CameraStore.legacyCameras(this)
            val btnImport = findViewById<Button>(R.id.btnImport)
            if (legacy.isNotEmpty()) {
                btnImport.visibility = View.VISIBLE
                btnImport.text = "Import ${legacy.size} camera(s) saved on this phone"
                btnImport.setOnClickListener {
                    CameraStore.importLegacy(this)
                    btnImport.visibility = View.GONE
                    toast("Cameras imported")
                }
            }
        }

        findViewById<TextView>(R.id.tvLogout).setOnClickListener {
            Session.logout()
            startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }

    /** Only viewer accounts are listed here — there is exactly one admin (created on first launch). */
    private fun refreshUserList() {
        UserStore.listViewers { viewers, err ->
            if (isFinishing) return@listViewers
            userList.removeAllViews()
            if (viewers == null) { toast(err ?: "Could not load accounts"); return@listViewers }
            val inflater = LayoutInflater.from(this)
            for (user in viewers) {
                val row = inflater.inflate(R.layout.item_user, userList, false)
                row.findViewById<TextView>(R.id.tvUsername).text = user.username
                row.findViewById<TextView>(R.id.tvRole).text = "Viewer"
                row.findViewById<ImageButton>(R.id.btnDeleteUser).setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Remove ${user.username}?")
                        .setPositiveButton("Remove") { _, _ ->
                            UserStore.removeViewer(user.uid) { e ->
                                if (e != null) toast(e)
                                refreshUserList()
                            }
                        }
                        .setNegativeButton("Cancel", null).show()
                }
                userList.addView(row)
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

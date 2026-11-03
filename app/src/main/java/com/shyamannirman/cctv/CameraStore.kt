package com.shyamannirman.cctv

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var search: EditText
    private lateinit var adapter: CameraListAdapter
    private var cameras = mutableListOf<Camera>()
    private var query = ""
    private val camerasChanged: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Session.currentUser == null) {
            // Process was likely recreated after being backgrounded; require a fresh login.
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        search = findViewById(R.id.etSearch)
        list.layoutManager = LinearLayoutManager(this)

        adapter = CameraListAdapter(
            emptyList(),
            onClick = { cam ->
                startActivity(Intent(this, LiveActivity::class.java).putExtra("id", cam.id))
            },
            onLongClick = { cam -> if (Session.isAdmin()) showCameraMenu(cam) }
        )
        list.adapter = adapter

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { query = s.toString(); refreshList() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        val btnAdd = findViewById<android.widget.ImageButton>(R.id.btnAdd)
        if (Session.isAdmin()) {
            btnAdd.setOnClickListener { startActivity(Intent(this, AddCameraActivity::class.java)) }
        } else {
            btnAdd.visibility = android.view.View.GONE
        }
        findViewById<android.widget.ImageButton>(R.id.btnGrid).setOnClickListener {
            if (cameras.isEmpty()) return@setOnClickListener
            startActivity(Intent(this, GridActivity::class.java))
        }
        findViewById<android.widget.ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        CameraStore.addListener(camerasChanged)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        CameraStore.removeListener(camerasChanged)
    }

    private fun showCameraMenu(cam: Camera) {
        val favLabel = if (cam.favorite) "Remove from favorites" else "Add to favorites"
        AlertDialog.Builder(this)
            .setTitle(cam.name)
            .setItems(arrayOf("Edit", "Rename", favLabel, "Delete")) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, AddCameraActivity::class.java).putExtra("id", cam.id)
                    )
                    1 -> showRenameDialog(cam)
                    2 -> { CameraStore.toggleFavorite(cam.id) }
                    3 -> AlertDialog.Builder(this)
                        .setTitle("Delete ${cam.name}?")
                        .setPositiveButton("Delete") { _, _ ->
                            CameraStore.remove(cam.id)
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    private fun showRenameDialog(cam: Camera) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(cam.name)
            setSelection(text.length)
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        AlertDialog.Builder(this)
            .setTitle("Rename camera")
            .setView(input, padding, padding, padding, 0)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    CameraStore.rename(cam.id, newName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refresh() {
        cameras = CameraStore.all()
        refreshList()
    }

    /** Groups by location (favorites always first within their own group), filtered by search. */
    private fun refreshList() {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) cameras else cameras.filter {
            it.name.lowercase().contains(q) || it.location.lowercase().contains(q)
        }

        val rows = mutableListOf<Row>()
        val favorites = filtered.filter { it.favorite }
        if (favorites.isNotEmpty()) {
            rows.add(Row.Header("FAVORITES"))
            favorites.forEach { rows.add(Row.Item(it)) }
        }
        val rest = filtered.filter { !it.favorite }
        val grouped = rest.groupBy { it.location.ifBlank { "Other" } }
        for ((location, camsInGroup) in grouped.toSortedMap(compareBy { if (it == "Other") "\uFFFF" else it })) {
            rows.add(Row.Header(location.uppercase()))
            camsInGroup.forEach { rows.add(Row.Item(it)) }
        }

        adapter.submit(rows)
        empty.visibility = if (cameras.isEmpty()) TextView.VISIBLE else TextView.GONE
        list.visibility = if (cameras.isEmpty()) TextView.GONE else TextView.VISIBLE
    }
}

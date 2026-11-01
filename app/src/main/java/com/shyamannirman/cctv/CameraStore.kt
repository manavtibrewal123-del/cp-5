package com.shyamannirman.cctv

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONArray

/**
 * Cameras are shared through Cloud Firestore (collection "cameras"), so every account sees the
 * same list. Everyone signed in can read; only the admin can write (enforced by firestore.rules).
 * A live listener keeps [all] up to date; screens read from that in-memory copy.
 */
object CameraStore {
    private val db get() = FirebaseFirestore.getInstance()
    private var cache: List<Camera> = emptyList()
    private var registration: ListenerRegistration? = null
    private val listeners = mutableSetOf<() -> Unit>()

    /** Called after login. */
    fun start() {
        if (registration != null) return
        registration = db.collection("cameras").addSnapshotListener { snap, err ->
            if (err != null || snap == null) return@addSnapshotListener // keep the last good list
            cache = snap.documents.mapNotNull { d -> d.data?.let { Camera.fromMap(d.id, it) } }
            listeners.toList().forEach { it() }
        }
    }

    /** Called on logout. */
    fun stop() {
        registration?.remove(); registration = null
        cache = emptyList()
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** Favorites first, then oldest first. */
    fun all(): MutableList<Camera> =
        cache.sortedWith(compareByDescending<Camera> { it.favorite }.thenBy { it.createdAt }).toMutableList()

    fun find(id: String): Camera? = cache.firstOrNull { it.id == id }

    // ---- Admin-only writes (the server rules reject anyone else regardless) ----

    private fun requireAdmin() {
        if (!Session.isAdmin()) throw SecurityException("Only the admin can change cameras")
    }

    fun add(cam: Camera) {
        requireAdmin()
        db.collection("cameras").document(cam.id).set(cam.toMap())
    }

    fun remove(id: String) {
        requireAdmin()
        db.collection("cameras").document(id).delete()
    }

    fun rename(id: String, newName: String) {
        requireAdmin()
        db.collection("cameras").document(id).update("name", newName)
    }

    fun toggleFavorite(id: String) {
        requireAdmin()
        val cam = find(id) ?: return
        db.collection("cameras").document(id).update("favorite", !cam.favorite)
    }

    // ---- One-time import of cameras saved on this phone by the older, offline version ----

    private fun legacyPrefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx, "cameras_secure",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun legacyCameras(ctx: Context): List<Camera> = try {
        val arr = JSONArray(legacyPrefs(ctx).getString("cameras", "[]") ?: "[]")
        List(arr.length()) { Camera.fromJson(arr.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    fun importLegacy(ctx: Context) {
        requireAdmin()
        val now = System.currentTimeMillis()
        legacyCameras(ctx).forEachIndexed { i, cam -> add(cam.copy(createdAt = now + i)) }
        legacyPrefs(ctx).edit().remove("cameras").apply()
    }
}

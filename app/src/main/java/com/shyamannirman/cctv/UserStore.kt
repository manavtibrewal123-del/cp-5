package com.shyamannirman.cctv

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source

/**
 * Accounts live in Firebase Authentication (email/password, where the "email" is
 * username@hmc-cctv.app behind the scenes). Each account's role is stored in Firestore at
 * users/{uid}. meta/admin records which uid is THE admin — it can only be created once.
 * The real enforcement is in firestore.rules; the checks in the app are just for the UI.
 */
object UserStore {
    const val MIN_PASSWORD = 6 // Firebase Authentication minimum
    const val USERNAME_HINT = "Username must be 3–30 characters: letters, numbers, . _ -"
    private val USERNAME_RE = Regex("^[a-z0-9._-]{3,30}$")

    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()

    fun normalize(username: String) = username.trim().lowercase()
    fun validUsername(username: String) = USERNAME_RE.matches(normalize(username))
    private fun email(username: String) = "${normalize(username)}@hmc-cctv.app"

    /** true = an admin exists, false = first-time setup needed, null = couldn't reach the server. */
    fun isSetupDone(cb: (Boolean?) -> Unit) {
        db.collection("meta").document("admin").get(Source.SERVER)
            .addOnSuccessListener { cb(it.exists()) }
            .addOnFailureListener { cb(null) }
    }

    /** Creates the one and only admin. cb(null) on success, otherwise an error message. */
    fun createAdmin(username: String, password: String, cb: (String?) -> Unit) {
        val name = normalize(username)
        auth.createUserWithEmailAndPassword(email(name), password)
            .addOnSuccessListener { res ->
                val fbUser = res.user ?: return@addOnSuccessListener cb("Sign-up failed")
                val uid = fbUser.uid
                fun undo(e: Exception) { fbUser.delete(); auth.signOut(); cb(describe(e, adminExists = true)) }
                db.collection("meta").document("admin").set(mapOf("uid" to uid))
                    .addOnSuccessListener {
                        db.collection("users").document(uid)
                            .set(mapOf("username" to name, "role" to Role.ADMIN.name))
                            .addOnSuccessListener { Session.login(User(uid, name, Role.ADMIN)); cb(null) }
                            .addOnFailureListener { undo(it) }
                    }
                    .addOnFailureListener { undo(it) }
            }
            .addOnFailureListener { cb(describe(it)) }
    }

    /** cb(user, null) on success, cb(null, message) on failure. Does not set the Session. */
    fun login(username: String, password: String, cb: (User?, String?) -> Unit) {
        auth.signInWithEmailAndPassword(email(username), password)
            .addOnSuccessListener { res ->
                val uid = res.user?.uid ?: return@addOnSuccessListener cb(null, "Sign-in failed")
                db.collection("users").document(uid).get()
                    .addOnSuccessListener { d ->
                        if (!d.exists()) {
                            auth.signOut()
                            cb(null, "This account has been removed")
                        } else {
                            val role = try { Role.valueOf(d.getString("role") ?: "VIEWER") } catch (e: Exception) { Role.VIEWER }
                            cb(User(uid, d.getString("username") ?: normalize(username), role), null)
                        }
                    }
                    .addOnFailureListener { auth.signOut(); cb(null, describe(it)) }
            }
            .addOnFailureListener { cb(null, describe(it)) }
    }

    /** Admin only. Creates the login on a throw-away second Firebase app so the admin stays signed in. */
    fun addViewer(ctx: Context, username: String, password: String, cb: (String?) -> Unit) {
        if (!Session.isAdmin()) return cb("Only the admin can add accounts")
        val name = normalize(username)
        val secondary = secondaryAuth(ctx)
        secondary.createUserWithEmailAndPassword(email(name), password)
            .addOnSuccessListener { res ->
                val fbUser = res.user ?: return@addOnSuccessListener cb("Sign-up failed")
                db.collection("users").document(fbUser.uid)
                    .set(mapOf("username" to name, "role" to Role.VIEWER.name))
                    .addOnSuccessListener { secondary.signOut(); cb(null) }
                    .addOnFailureListener { e ->
                        fbUser.delete().addOnCompleteListener { secondary.signOut() }
                        cb(describe(e))
                    }
            }
            .addOnFailureListener { cb(describe(it)) }
    }

    fun listViewers(cb: (List<User>?, String?) -> Unit) {
        db.collection("users").whereEqualTo("role", Role.VIEWER.name).get()
            .addOnSuccessListener { snap ->
                cb(snap.documents.map { User(it.id, it.getString("username") ?: "?", Role.VIEWER) }
                    .sortedBy { it.username }, null)
            }
            .addOnFailureListener { cb(null, describe(it)) }
    }

    /** Removes the viewer's access immediately (their login can no longer read anything). */
    fun removeViewer(uid: String, cb: (String?) -> Unit) {
        db.collection("users").document(uid).delete()
            .addOnSuccessListener { cb(null) }
            .addOnFailureListener { cb(describe(it)) }
    }

    private fun secondaryAuth(ctx: Context): FirebaseAuth {
        val appName = "secondary"
        val app = try {
            FirebaseApp.getInstance(appName)
        } catch (e: IllegalStateException) {
            FirebaseApp.initializeApp(ctx.applicationContext, FirebaseApp.getInstance().options, appName)
        }
        return FirebaseAuth.getInstance(app)
    }

    private fun describe(e: Exception, adminExists: Boolean = false): String = when {
        e is FirebaseAuthUserCollisionException ->
            "That username is already in use. (If you removed it earlier, delete it under Firebase Console > Authentication to reuse the name.)"
        e is FirebaseAuthInvalidCredentialsException || e is FirebaseAuthInvalidUserException ->
            "Incorrect username or password"
        e is FirebaseNetworkException -> "No internet connection"
        e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
            if (adminExists) "An admin account already exists. Close and reopen the app to sign in."
            else "Not allowed"
        else -> e.localizedMessage ?: "Something went wrong"
    }
}

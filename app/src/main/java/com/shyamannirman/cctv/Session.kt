package com.shyamannirman.cctv

import com.google.firebase.auth.FirebaseAuth

/** Who is currently logged in, for this process only — logging in is required again
 *  each time the app is cold-started (enforced via SplashActivity). */
object Session {
    var currentUser: User? = null
        private set

    fun isAdmin(): Boolean = currentUser?.role == Role.ADMIN

    fun login(user: User) {
        currentUser = user
        CameraStore.start()
    }

    fun logout() {
        currentUser = null
        CameraStore.stop()
        FirebaseAuth.getInstance().signOut()
    }
}

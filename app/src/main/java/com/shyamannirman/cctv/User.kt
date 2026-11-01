package com.shyamannirman.cctv

enum class Role { ADMIN, VIEWER }

/** uid is the Firebase Authentication user id. The role is stored in Firestore (users/{uid}). */
data class User(
    val uid: String,
    val username: String,
    val role: Role
)

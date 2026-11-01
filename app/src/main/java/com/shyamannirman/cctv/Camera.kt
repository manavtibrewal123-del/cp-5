package com.shyamannirman.cctv

import android.net.Uri
import org.json.JSONObject

data class Camera(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val pass: String,
    val channel: Int,
    val subtype: Int,
    val favorite: Boolean = false,
    val customUrl: String = "",
    val onvifEnabled: Boolean = false,
    val onvifPort: Int = 80,
    val location: String = "",
    val createdAt: Long = 0L
) {
    /** CP Plus / Dahua-style RTSP URL. subtype 0 = main stream, 1 = sub stream.
     *  If customUrl is set, it is used as-is (for test streams, other brands, etc). */
    fun rtspUrl(sub: Int = subtype): String {
        if (customUrl.isNotBlank()) return customUrl
        return "rtsp://${Uri.encode(user)}:${Uri.encode(pass)}@$host:$port" +
            "/cam/realmonitor?channel=$channel&subtype=$sub"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("host", host).put("port", port)
        .put("user", user).put("pass", pass).put("channel", channel).put("subtype", subtype)
        .put("favorite", favorite).put("customUrl", customUrl)
        .put("onvifEnabled", onvifEnabled).put("onvifPort", onvifPort)
        .put("location", location)

    fun toMap(): Map<String, Any> = mapOf(
        "name" to name, "host" to host, "port" to port, "user" to user, "pass" to pass,
        "channel" to channel, "subtype" to subtype, "favorite" to favorite, "customUrl" to customUrl,
        "onvifEnabled" to onvifEnabled, "onvifPort" to onvifPort, "location" to location,
        "createdAt" to createdAt
    )

    override fun toString() = (if (favorite) "★ " else "") + "$name  ($host, ch $channel)"

    companion object {
        fun fromMap(id: String, m: Map<String, Any?>) = Camera(
            id = id,
            name = m["name"] as? String ?: "Camera",
            host = m["host"] as? String ?: "",
            port = (m["port"] as? Number)?.toInt() ?: 554,
            user = m["user"] as? String ?: "",
            pass = m["pass"] as? String ?: "",
            channel = (m["channel"] as? Number)?.toInt() ?: 1,
            subtype = (m["subtype"] as? Number)?.toInt() ?: 0,
            favorite = m["favorite"] as? Boolean ?: false,
            customUrl = m["customUrl"] as? String ?: "",
            onvifEnabled = m["onvifEnabled"] as? Boolean ?: false,
            onvifPort = (m["onvifPort"] as? Number)?.toInt() ?: 80,
            location = m["location"] as? String ?: "",
            createdAt = (m["createdAt"] as? Number)?.toLong() ?: 0L
        )

        fun fromJson(o: JSONObject) = Camera(
            o.getString("id"), o.getString("name"), o.getString("host"), o.getInt("port"),
            o.getString("user"), o.getString("pass"), o.getInt("channel"), o.getInt("subtype"),
            o.optBoolean("favorite", false), o.optString("customUrl", ""),
            o.optBoolean("onvifEnabled", false), o.optInt("onvifPort", 80),
            o.optString("location", "")
        )
    }
}

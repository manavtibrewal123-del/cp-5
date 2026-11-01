package com.shyamannirman.cctv

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/**
 * Minimal ONVIF PTZ client using WS-UsernameToken (PasswordDigest) auth.
 *
 * Assumes device, media and PTZ services all answer at the same endpoint
 * (commonly /onvif/device_service), which is true for many NVRs and
 * Dahua-based cameras (CP Plus included) but not guaranteed for every
 * model. All network calls are blocking — call from a background thread.
 */
class OnvifPtz(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val pass: String
) {
    private val endpoint get() = "http://$host:$port/onvif/device_service"

    private fun securityHeader(): String {
        val nonceBytes = Random.nextBytes(16)
        val nonceB64 = Base64.getEncoder().encodeToString(nonceBytes)
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val created = sdf.format(Date())
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(nonceBytes)
        sha1.update(created.toByteArray(Charsets.UTF_8))
        sha1.update(pass.toByteArray(Charsets.UTF_8))
        val digest = Base64.getEncoder().encodeToString(sha1.digest())
        return """
            <soap:Header>
              <wsse:Security soap:mustUnderstand="1" xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
                <wsse:UsernameToken>
                  <wsse:Username>$user</wsse:Username>
                  <wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-username-token-profile-1.0#PasswordDigest">$digest</wsse:Password>
                  <wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-soap-message-security-1.0#Base64Binary">$nonceB64</wsse:Nonce>
                  <wsu:Created>$created</wsu:Created>
                </wsse:UsernameToken>
              </wsse:Security>
            </soap:Header>
        """.trimIndent()
    }

    private fun envelope(bodyXml: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope">
          ${securityHeader()}
          <soap:Body>
            $bodyXml
          </soap:Body>
        </soap:Envelope>
    """.trimIndent()

    /** Returns null (instead of throwing) on any network/parse failure, so
     *  callers on a background thread can just check for null. */
    private fun post(body: String, action: String): String? = try {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8; action=\"$action\"")
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        stream?.bufferedReader()?.use { it.readText() }
    } catch (e: Exception) {
        null
    }

    /** Returns the first media profile token, or null if it couldn't be found. */
    fun getProfileToken(): String? {
        val body = """<GetProfiles xmlns="http://www.onvif.org/ver10/media/wsdl"/>"""
        val resp = post(envelope(body), "http://www.onvif.org/ver10/media/wsdl/GetProfiles") ?: return null
        return Regex("token=\"([^\"]+)\"").find(resp)?.groupValues?.get(1)
    }

    /** x, y in -1..1. Positive x = pan right, positive y = tilt up. */
    fun continuousMove(token: String, x: Float, y: Float) {
        val body = """
            <ContinuousMove xmlns="http://www.onvif.org/ver20/ptz/wsdl">
              <ProfileToken>$token</ProfileToken>
              <Velocity>
                <PanTilt x="$x" y="$y" xmlns="http://www.onvif.org/ver10/schema"/>
              </Velocity>
            </ContinuousMove>
        """.trimIndent()
        post(envelope(body), "http://www.onvif.org/ver20/ptz/wsdl/ContinuousMove")
    }

    fun stop(token: String) {
        val body = """
            <Stop xmlns="http://www.onvif.org/ver20/ptz/wsdl">
              <ProfileToken>$token</ProfileToken>
              <PanTilt>true</PanTilt>
              <Zoom>true</Zoom>
            </Stop>
        """.trimIndent()
        post(envelope(body), "http://www.onvif.org/ver20/ptz/wsdl/Stop")
    }
}

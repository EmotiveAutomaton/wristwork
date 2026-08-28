package com.emotiveautomaton.wristwork.net

import com.emotiveautomaton.wristwork.BuildConfig
import java.security.MessageDigest
import okhttp3.Request

/**
 * Minimal PrusaLink client with HTTP digest auth (OkHttp has no built-in digest; PrusaLink
 * speaks digest only — user `maker`, password = the API key). Watch and printer share the LAN.
 * Uses the credential-free client on purpose: the bus token must never reach the printer, and an
 * Authorization header added by an interceptor would clobber the digest response.
 */
object PrusaClient {
    private const val USER = "maker"
    private val host get() = BuildConfig.PRINTER_HOST
    private val key get() = BuildConfig.PRINTER_API_KEY

    val configured: Boolean get() = host.isNotBlank() && key.isNotBlank()

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** GET with one 401 round-trip to answer the digest challenge. Returns body bytes or null. */
    fun get(path: String): ByteArray? = runCatching {
        val url = "http://$host$path"
        NtfyClient.plain.newCall(Request.Builder().url(url).build()).execute().use { first ->
            if (first.isSuccessful) return first.body.bytes()
            if (first.code != 401) return null
            val challenge = first.header("WWW-Authenticate") ?: return null
            fun param(name: String): String? =
                Regex("$name=\"?([^\",]+)\"?").find(challenge)?.groupValues?.get(1)
            val realm = param("realm") ?: return null
            val nonce = param("nonce") ?: return null
            val qop = param("qop")
            val cnonce = md5(System.nanoTime().toString()).take(16)
            val ha1 = md5("$USER:$realm:$key")
            val ha2 = md5("GET:$path")
            val response = if (qop != null)
                md5("$ha1:$nonce:00000001:$cnonce:$qop:$ha2")
            else md5("$ha1:$nonce:$ha2")
            val auth = buildString {
                append("Digest username=\"$USER\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$path\"")
                append(", response=\"$response\"")
                if (qop != null) append(", qop=$qop, nc=00000001, cnonce=\"$cnonce\"")
            }
            NtfyClient.plain.newCall(
                Request.Builder().url(url).header("Authorization", auth).build()
            ).execute().use { second ->
                if (second.isSuccessful) second.body.bytes() else null
            }
        }
    }.getOrNull()

    fun getText(path: String): String? = get(path)?.toString(Charsets.UTF_8)
}

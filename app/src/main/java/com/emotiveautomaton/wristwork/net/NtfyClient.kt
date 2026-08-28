package com.emotiveautomaton.wristwork.net

import com.emotiveautomaton.wristwork.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Thin HTTP edge. One client for the process; short timeouts because complications run on a
 * budget — 8 s rather than 5 s, because off home Wi-Fi every request crosses the phone's
 * Bluetooth proxy, which is slower than LAN by a wide margin.
 *
 * When a token is configured (config.properties -> BuildConfig, never in git) every request
 * carries it. Empty token = no header, which is the LAN-only mode the bus started in.
 */
object NtfyClient {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .apply {
                val token = BuildConfig.NTFY_TOKEN
                if (token.isNotEmpty()) addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("Authorization", "Bearer $token").build()
                    )
                }
            }
            .build()
    }

    /**
     * A client with NO bus credentials, for anything that is not the bus. The printer speaks HTTP
     * digest, and an interceptor that stamps `Authorization: Bearer …` on every request replaced
     * the digest header on the retry — so every PrusaLink call silently failed the moment the bus
     * gained a token (found 2026-08-26: the printer frame fell back to the bus record and no
     * thumbnail could be fetched). It also meant our bus token was being handed to the printer.
     */
    val plain: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    val baseUrl: String get() = BuildConfig.NTFY_BASE_URL.trimEnd('/')

    data class Health(val up: Boolean, val latencyMs: Long)

    /** GET {server}/v1/health. Never throws; "down" carries the elapsed time to failure. */
    fun health(): Health {
        val start = System.nanoTime()
        return try {
            http.newCall(Request.Builder().url("$baseUrl/v1/health").build()).execute().use { r ->
                Health(up = r.isSuccessful, latencyMs = elapsedMs(start))
            }
        } catch (e: Exception) {
            Health(up = false, latencyMs = elapsedMs(start))
        }
    }

    private fun elapsedMs(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000
}

package com.emotiveautomaton.telltale.net

import com.emotiveautomaton.telltale.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Thin HTTP edge. One client for the process; short timeouts because complications run on a budget. */
object NtfyClient {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
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

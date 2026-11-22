package dev.koduki.xracp

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Probes localhost (Android 15 AVF Linux Terminal / PRoot / local Node bridge)
 * to decide whether to connect directly to http://127.0.0.1:8765/ or boot from
 * bundled WebView assets (for Tailscale remote profiles or Offline Demo Mode).
 */
object LocalBridgeProbe {

    const val DEFAULT_LOCAL_ORIGIN = "http://127.0.0.1:8765"

    data class ProbeResult(
        val reachable: Boolean,
        val origin: String,
        val version: String = "",
        val authRequired: Boolean = false,
        val latencyMs: Long = -1L,
    ) {
        fun toJsonString(): String = JSONObject()
            .put("reachable", reachable)
            .put("origin", origin)
            .put("version", version)
            .put("authRequired", authRequired)
            .put("latencyMs", latencyMs)
            .toString()
    }

    fun probe(origin: String = DEFAULT_LOCAL_ORIGIN, timeoutMs: Int = 800): ProbeResult {
        val started = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("${origin.trimEnd('/')}/api/health")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
            }
            val code = conn.responseCode
            val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
            if (code != HttpURLConnection.HTTP_OK) {
                ProbeResult(reachable = false, origin = origin, latencyMs = latency)
            } else {
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(body)
                ProbeResult(
                    reachable = json.optBoolean("ok", false),
                    origin = origin,
                    version = json.optString("version", ""),
                    authRequired = json.optBoolean("authRequired", false),
                    latencyMs = latency,
                )
            }
        } catch (_: Throwable) {
            ProbeResult(reachable = false, origin = origin)
        } finally {
            conn?.disconnect()
        }
    }
}

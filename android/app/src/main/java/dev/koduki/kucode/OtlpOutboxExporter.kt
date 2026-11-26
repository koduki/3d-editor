package dev.koduki.kucode

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.util.Locale

/**
 * Strict validator for user-configured OTLP/HTTP endpoints.
 *
 * Security invariants:
 * - Remote endpoints MUST use `https://`.
 * - Plaintext `http://` is permitted ONLY for `127.0.0.1` or `localhost` (case-insensitive).
 * - Userinfo (`user:pass@`), non-HTTP(S) schemes, control characters, query strings, and fragments are rejected.
 * - Trailing `/v1/traces`, `/v1/metrics`, `/v1/logs`, and `/` suffixes are normalized away to form the base URL.
 */
object OtlpEndpointValidator {

    private val SIGNAL_SUFFIXES = listOf("/v1/traces", "/v1/metrics", "/v1/logs")

    data class ValidationResult(
        val valid: Boolean,
        val normalizedBaseUrl: String = "",
        val error: String? = null,
    )

    fun validate(rawUrl: String, requireNonBlank: Boolean = true): ValidationResult {
        if (rawUrl.any { it.code < 0x20 || it.code == 0x7F }) {
            return ValidationResult(false, "", "Endpoint URL must not contain control characters")
        }
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return if (requireNonBlank) {
                ValidationResult(false, "", "Endpoint URL is required when OTLP export is enabled")
            } else {
                ValidationResult(true, "", null)
            }
        }
        if (trimmed.any { it.isWhitespace() }) {
            return ValidationResult(false, "", "Endpoint URL must not contain whitespace")
        }

        val uri = runCatching { URI(trimmed) }.getOrElse {
            return ValidationResult(false, "", "Malformed endpoint URL")
        }
        if (uri.rawUserInfo != null || uri.userInfo != null || uri.rawAuthority?.contains('@') == true) {
            return ValidationResult(false, "", "Endpoint URL must not contain embedded userinfo credentials")
        }
        if (uri.rawQuery != null || uri.rawFragment != null) {
            return ValidationResult(false, "", "Endpoint URL must not contain query parameters or fragments")
        }

        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            return ValidationResult(false, "", "Endpoint URL must use https:// (or http:// for localhost/127.0.0.1)")
        }

        val host = uri.host
        if (host.isNullOrBlank()) {
            return ValidationResult(false, "", "Endpoint URL must include a valid host")
        }
        val lowerHost = host.lowercase(Locale.US)
        if (uri.port == 0 || uri.port > 65535) {
            return ValidationResult(false, "", "Endpoint URL port out of range")
        }

        if (scheme == "http" && lowerHost != "127.0.0.1" && lowerHost != "localhost") {
            return ValidationResult(
                false,
                "",
                "Plaintext http:// is only allowed for 127.0.0.1 or localhost; use https:// for remote endpoints",
            )
        }

        var normalizedPath = uri.rawPath.orEmpty().trimEnd('/')
        val lowerPath = normalizedPath.lowercase(Locale.US)
        for (suffix in SIGNAL_SUFFIXES) {
            if (lowerPath.endsWith(suffix)) {
                normalizedPath = normalizedPath.dropLast(suffix.length).trimEnd('/')
                break
            }
        }

        val portPart = if (uri.port > 0) ":${uri.port}" else ""
        val normalized = "$scheme://$lowerHost$portPart$normalizedPath"
        return ValidationResult(valid = true, normalizedBaseUrl = normalized, error = null)
    }
}

/**
 * Encryption abstraction for protecting OTLP authorization header secrets at rest.
 * Production Android wiring injects an Android Keystore AES-256-GCM implementation;
 * pure-JVM unit tests inject a deterministic fake cipher.
 */
interface OtlpSecretCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String?
}

/**
 * Key-value persistence abstraction backed by private `SharedPreferences` on Android
 * and in-memory maps in pure-JVM tests.
 */
interface KeyValueStorage {
    fun getString(key: String): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putString(key: String, value: String?)
    fun putBoolean(key: String, value: Boolean)
}

data class OtlpConfig(
    val enabled: Boolean = false,
    val endpointUrl: String = "",
    val granularity: String = OtlpJsonSerializer.GRANULARITY_SUMMARY_AND_ERRORS,
    val unmeteredOnly: Boolean = false,
    val authHeaderName: String = "Authorization",
    val authHeaderValue: String = "",
)

/**
 * Default-OFF OTLP export configuration store with ciphertext-only secret persistence
 * and automatic [LogRedactor.registerSecret] registration.
 */
class OtlpConfigStore(
    private val storage: KeyValueStorage,
    private val cipher: OtlpSecretCipher,
) {
    data class SaveResult(
        val ok: Boolean,
        val config: OtlpConfig? = null,
        val error: String? = null,
    )

    private val lock = Any()

    fun loadConfig(): OtlpConfig = synchronized(lock) {
        val enabled = storage.getBoolean(KEY_ENABLED, false)
        val endpointUrl = storage.getString(KEY_ENDPOINT_URL).orEmpty()
        val rawGranularity = storage.getString(KEY_GRANULARITY)
        val granularity = if (OtlpJsonSerializer.isValidGranularity(rawGranularity)) {
            rawGranularity!!
        } else {
            OtlpJsonSerializer.GRANULARITY_SUMMARY_AND_ERRORS
        }
        val unmeteredOnly = storage.getBoolean(KEY_UNMETERED_ONLY, false)
        val authHeaderName = storage.getString(KEY_AUTH_HEADER_NAME)
            ?.trim()
            ?.takeIf { HEADER_NAME_REGEX.matches(it) }
            ?: DEFAULT_AUTH_HEADER_NAME

        val encrypted = storage.getString(KEY_AUTH_HEADER_VALUE_ENC)
        val decrypted = if (!encrypted.isNullOrBlank()) {
            runCatching { cipher.decrypt(encrypted) }.getOrNull().orEmpty()
        } else {
            ""
        }
        registerAuthSecret(decrypted)

        OtlpConfig(
            enabled = enabled,
            endpointUrl = endpointUrl,
            granularity = granularity,
            unmeteredOnly = unmeteredOnly,
            authHeaderName = authHeaderName,
            authHeaderValue = decrypted,
        )
    }

    fun saveConfig(
        enabled: Boolean,
        endpointUrl: String,
        granularity: String,
        unmeteredOnly: Boolean,
        authHeaderName: String,
        authHeaderValue: String?,
        clearAuthHeader: Boolean = false,
    ): SaveResult = synchronized(lock) {
        val validatedUrl = OtlpEndpointValidator.validate(endpointUrl, requireNonBlank = enabled)
        if (!validatedUrl.valid) {
            return SaveResult(ok = false, error = validatedUrl.error ?: "Invalid endpoint URL")
        }
        if (!OtlpJsonSerializer.isValidGranularity(granularity)) {
            return SaveResult(ok = false, error = "Invalid granularity: $granularity")
        }
        val cleanHeaderName = authHeaderName.trim().ifEmpty { DEFAULT_AUTH_HEADER_NAME }
        if (!HEADER_NAME_REGEX.matches(cleanHeaderName)) {
            return SaveResult(ok = false, error = "Invalid auth header name")
        }

        val incomingSecret = authHeaderValue?.trim()
        if (!clearAuthHeader && !incomingSecret.isNullOrEmpty()) {
            if (incomingSecret.any { it == '\r' || it == '\n' }) {
                return SaveResult(ok = false, error = "Auth header value must not contain newlines")
            }
            val encrypted = runCatching { cipher.encrypt(incomingSecret) }.getOrElse { e ->
                return SaveResult(ok = false, error = "Failed to encrypt auth header: ${e.message ?: e.javaClass.simpleName}")
            }
            storage.putString(KEY_AUTH_HEADER_VALUE_ENC, encrypted)
            registerAuthSecret(incomingSecret)
        } else if (clearAuthHeader) {
            storage.putString(KEY_AUTH_HEADER_VALUE_ENC, null)
        }

        storage.putBoolean(KEY_ENABLED, enabled)
        storage.putString(KEY_ENDPOINT_URL, validatedUrl.normalizedBaseUrl)
        storage.putString(KEY_GRANULARITY, granularity)
        storage.putBoolean(KEY_UNMETERED_ONLY, unmeteredOnly)
        storage.putString(KEY_AUTH_HEADER_NAME, cleanHeaderName)

        SaveResult(ok = true, config = loadConfig())
    }

    fun getPublicConfigJson(outboxPendingCount: Int = 0): JSONObject {
        val cfg = loadConfig()
        val hasAuth = cfg.authHeaderValue.isNotEmpty()
        val preview = if (!hasAuth) "" else buildSafePreview(cfg.authHeaderValue)
        return JSONObject()
            .put("enabled", cfg.enabled)
            .put("endpointUrl", cfg.endpointUrl)
            .put("granularity", cfg.granularity)
            .put("unmeteredOnly", cfg.unmeteredOnly)
            .put("authHeaderName", cfg.authHeaderName)
            .put("hasAuthHeader", hasAuth)
            .put("authHeaderPreview", preview)
            .put("outboxPendingCount", outboxPendingCount)
    }

    private fun registerAuthSecret(secret: String) {
        val trimmed = secret.trim()
        if (trimmed.isEmpty()) return
        LogRedactor.registerSecret(trimmed)
        val spaceIdx = trimmed.indexOf(' ')
        if (spaceIdx > 0 && spaceIdx < trimmed.length - 1) {
            val suffix = trimmed.substring(spaceIdx + 1).trim()
            if (suffix.length >= 8) {
                LogRedactor.registerSecret(suffix)
            }
        }
    }

    private fun buildSafePreview(secret: String): String {
        val trimmed = secret.trim()
        val spaceIdx = trimmed.indexOf(' ')
        return if (spaceIdx in 1..24) {
            val scheme = trimmed.substring(0, spaceIdx)
            if (HEADER_NAME_REGEX.matches(scheme)) "$scheme ********" else "********"
        } else {
            "********"
        }
    }

    companion object {
        const val DEFAULT_AUTH_HEADER_NAME = "Authorization"
        private val HEADER_NAME_REGEX = Regex("^[A-Za-z0-9-]+$")
        private const val KEY_ENABLED = "otlp_enabled"
        private const val KEY_ENDPOINT_URL = "otlp_endpoint_url"
        private const val KEY_GRANULARITY = "otlp_granularity"
        private const val KEY_UNMETERED_ONLY = "otlp_unmetered_only"
        private const val KEY_AUTH_HEADER_NAME = "otlp_auth_header_name"
        private const val KEY_AUTH_HEADER_VALUE_ENC = "otlp_auth_header_value_enc"
    }
}

data class OtlpHttpResponse(
    val statusCode: Int,
    val body: String = "",
    val error: String? = null,
)

interface OtlpHttpClient {
    fun postJson(url: String, headers: Map<String, String>, jsonBody: String): OtlpHttpResponse
}

/**
 * Pure-JVM `HttpURLConnection` OTLP/HTTP JSON client.
 *
 * Enforces `instanceFollowRedirects = false`, `connectTimeout = 10_000`, `readTimeout = 15_000`,
 * `Content-Type: application/json; charset=utf-8`, and URL re-validation before sending.
 */
class HttpUrlConnectionOtlpClient : OtlpHttpClient {
    override fun postJson(url: String, headers: Map<String, String>, jsonBody: String): OtlpHttpResponse {
        val validated = OtlpEndpointValidator.validate(url, requireNonBlank = true)
        if (!validated.valid) {
            return OtlpHttpResponse(statusCode = 0, error = validated.error ?: "Invalid OTLP URL")
        }
        var conn: HttpURLConnection? = null
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                for ((k, v) in headers) {
                    if (k.isNotBlank() && v.isNotBlank()) {
                        setRequestProperty(k, v)
                    }
                }
            }
            conn = connection
            val payloadBytes = jsonBody.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payloadBytes.size)
            connection.outputStream.use { it.write(payloadBytes) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val buf = CharArray(MAX_RESPONSE_BODY_CHARS)
                val n = reader.read(buf)
                if (n > 0) String(buf, 0, n) else ""
            }.orEmpty()
            OtlpHttpResponse(statusCode = code, body = body)
        } catch (e: IOException) {
            OtlpHttpResponse(
                statusCode = 0,
                error = LogRedactor.redact("${e.javaClass.simpleName}: ${e.message.orEmpty()}"),
            )
        } catch (e: Exception) {
            OtlpHttpResponse(
                statusCode = 0,
                error = LogRedactor.redact("${e.javaClass.simpleName}: ${e.message.orEmpty()}"),
            )
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_RESPONSE_BODY_CHARS = 4_096
    }
}

/**
 * Isolated on-disk store-and-forward queue for OTLP session payloads (`<filesDir>/otlp-outbox`).
 * Uses atomic writes and prunes the oldest queued sessions when capacity exceeds [maxQueuedSessions].
 */
class OtlpOutboxStore(
    private val outboxDir: File,
    private val maxQueuedSessions: Int = OtlpOutboxExporter.DEFAULT_MAX_OUTBOX_SESSIONS,
) {
    data class OutboxItem(
        val sessionId: String,
        val enqueuedAtMs: Long,
        val attempts: Int = 0,
        val nextAttemptAtMs: Long = 0L,
        val lastError: String? = null,
        val tracesSent: Boolean = false,
        val metricsSent: Boolean = false,
        val logsSent: Boolean = false,
        val tracesJson: String,
        val metricsJson: String?,
        val logsJson: String?,
    ) {
        fun toJsonObject(): JSONObject = JSONObject()
            .put("sessionId", sessionId)
            .put("enqueuedAtMs", enqueuedAtMs)
            .put("attempts", attempts)
            .put("nextAttemptAtMs", nextAttemptAtMs)
            .put("lastError", lastError ?: JSONObject.NULL)
            .put("tracesSent", tracesSent)
            .put("metricsSent", metricsSent)
            .put("logsSent", logsSent)
            .put("tracesJson", tracesJson)
            .put("metricsJson", metricsJson ?: JSONObject.NULL)
            .put("logsJson", logsJson ?: JSONObject.NULL)

        companion object {
            fun fromJsonObject(obj: JSONObject): OutboxItem? {
                val sessionId = obj.optString("sessionId", "")
                val tracesJson = obj.optString("tracesJson", "")
                if (sessionId.isEmpty() || tracesJson.isEmpty()) return null
                return OutboxItem(
                    sessionId = sessionId,
                    enqueuedAtMs = obj.optLong("enqueuedAtMs", 0L),
                    attempts = obj.optInt("attempts", 0),
                    nextAttemptAtMs = obj.optLong("nextAttemptAtMs", 0L),
                    lastError = if (obj.isNull("lastError") || !obj.has("lastError")) null else obj.optString("lastError", "").ifEmpty { null },
                    tracesSent = obj.optBoolean("tracesSent", false),
                    metricsSent = obj.optBoolean("metricsSent", false),
                    logsSent = obj.optBoolean("logsSent", false),
                    tracesJson = tracesJson,
                    metricsJson = if (obj.isNull("metricsJson") || !obj.has("metricsJson")) null else obj.optString("metricsJson", "").ifEmpty { null },
                    logsJson = if (obj.isNull("logsJson") || !obj.has("logsJson")) null else obj.optString("logsJson", "").ifEmpty { null },
                )
            }
        }
    }

    fun put(item: OutboxItem) {
        val target = itemFile(item.sessionId) ?: return
        outboxDir.mkdirs()
        writeTextAtomically(target, item.toJsonObject().toString(), Charsets.UTF_8, worldReadable = false)
        pruneIfNeeded()
    }

    fun update(item: OutboxItem) {
        val target = itemFile(item.sessionId) ?: return
        outboxDir.mkdirs()
        writeTextAtomically(target, item.toJsonObject().toString(), Charsets.UTF_8, worldReadable = false)
    }

    fun remove(sessionId: String) {
        val target = itemFile(sessionId) ?: return
        runCatching { Files.deleteIfExists(target.toPath()) }
    }

    fun list(): List<OutboxItem> {
        if (!outboxDir.isDirectory) return emptyList()
        val files = outboxDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
        val items = mutableListOf<OutboxItem>()
        for (file in files) {
            val parsed = runCatching {
                OutboxItem.fromJsonObject(JSONObject(file.readText(Charsets.UTF_8)))
            }.getOrNull()
            if (parsed != null) {
                items.add(parsed)
            }
        }
        return items.sortedWith(compareBy<OutboxItem> { it.enqueuedAtMs }.thenBy { it.sessionId })
    }

    fun pendingCount(): Int {
        if (!outboxDir.isDirectory) return 0
        return outboxDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.size ?: 0
    }

    private fun pruneIfNeeded() {
        val cap = maxQueuedSessions.coerceAtLeast(1)
        val items = list()
        if (items.size <= cap) return
        val toDrop = items.size - cap
        for (item in items.take(toDrop)) {
            remove(item.sessionId)
        }
    }

    private fun itemFile(sessionId: String): File? {
        if (!SAFE_ID_REGEX.matches(sessionId)) return null
        return File(outboxDir, "$sessionId.json")
    }

    companion object {
        private val SAFE_ID_REGEX = Regex("^[A-Za-z0-9._-]+$")
    }
}

/**
 * Opt-in store-and-forward OTLP/HTTP JSON exporter.
 *
 * When disabled (default), `enqueueSession` returns `false` and `flushOutbox` sends zero HTTP requests.
 */
class OtlpOutboxExporter(
    private val sessionLogStore: SessionLogStore,
    outboxDir: File,
    val configStore: OtlpConfigStore,
    private val httpClient: OtlpHttpClient = HttpUrlConnectionOtlpClient(),
    private val isNetworkMetered: () -> Boolean = { false },
    private val clockMs: () -> Long = System::currentTimeMillis,
    maxOutboxSessions: Int = DEFAULT_MAX_OUTBOX_SESSIONS,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
) {
    data class FlushResult(
        val ok: Boolean,
        val enabled: Boolean,
        val enqueued: Int = 0,
        val flushed: Int = 0,
        val failed: Int = 0,
        val deferred: Int = 0,
        val remaining: Int = 0,
        val skippedReason: String? = null,
        val lastError: String? = null,
    ) {
        fun toJsonObject(): JSONObject = JSONObject()
            .put("ok", ok)
            .put("enabled", enabled)
            .put("enqueued", enqueued)
            .put("flushed", flushed)
            .put("failed", failed)
            .put("deferred", deferred)
            .put("remaining", remaining)
            .put("skippedReason", skippedReason ?: JSONObject.NULL)
            .put("lastError", lastError ?: JSONObject.NULL)
    }

    private val lock = Any()
    private val outboxStore = OtlpOutboxStore(outboxDir = outboxDir, maxQueuedSessions = maxOutboxSessions)

    fun pendingCount(): Int = synchronized(lock) { outboxStore.pendingCount() }

    fun hasSession(selector: String): Boolean = sessionLogStore.resolveSessionSummary(selector) != null

    fun getPublicConfigJson(): JSONObject = configStore.getPublicConfigJson(outboxPendingCount = pendingCount())

    fun enqueueSession(
        sessionId: String,
        granularity: String? = null,
        vscodeDataDir: File? = null,
    ): Boolean = synchronized(lock) {
        val cfg = configStore.loadConfig()
        if (!cfg.enabled) return false
        val resolved = sessionLogStore.resolveSessionSummary(sessionId) ?: return false
        val effectiveGranularity = if (granularity != null && OtlpJsonSerializer.isValidGranularity(granularity)) {
            granularity
        } else {
            cfg.granularity
        }
        val payloads = OtlpJsonSerializer.serializeSession(
            store = sessionLogStore,
            selector = resolved.sessionId,
            granularity = effectiveGranularity,
            vscodeDataDir = vscodeDataDir,
        ) ?: return false

        val item = OtlpOutboxStore.OutboxItem(
            sessionId = resolved.sessionId,
            enqueuedAtMs = clockMs(),
            attempts = 0,
            nextAttemptAtMs = 0L,
            lastError = null,
            tracesSent = false,
            metricsSent = payloads.metricsJson == null,
            logsSent = payloads.logsJson == null,
            tracesJson = payloads.tracesJson.toString(),
            metricsJson = payloads.metricsJson?.toString(),
            logsJson = payloads.logsJson?.toString(),
        )
        outboxStore.put(item)
        true
    }

    fun flushOutbox(ignoreBackoff: Boolean = false): FlushResult = synchronized(lock) {
        val cfg = configStore.loadConfig()
        val initialPending = outboxStore.pendingCount()
        if (!cfg.enabled) {
            return FlushResult(
                ok = false,
                enabled = false,
                remaining = initialPending,
                skippedReason = "disabled",
            )
        }

        val validated = OtlpEndpointValidator.validate(cfg.endpointUrl, requireNonBlank = true)
        if (!validated.valid) {
            return FlushResult(
                ok = false,
                enabled = true,
                remaining = initialPending,
                skippedReason = "invalid_endpoint",
                lastError = validated.error,
            )
        }

        if (cfg.unmeteredOnly && isNetworkMetered()) {
            return FlushResult(
                ok = true,
                enabled = true,
                deferred = initialPending,
                remaining = initialPending,
                skippedReason = "metered_network",
            )
        }

        val headers = linkedMapOf<String, String>()
        if (cfg.authHeaderName.isNotBlank() && cfg.authHeaderValue.isNotBlank()) {
            headers[cfg.authHeaderName] = cfg.authHeaderValue
        }

        val now = clockMs()
        val items = outboxStore.list()
        var flushed = 0
        var failed = 0
        var deferred = 0
        var lastError: String? = null

        for (item in items) {
            if (!ignoreBackoff && item.nextAttemptAtMs > now) {
                deferred++
                continue
            }

            var current = item
            var itemFailed = false
            var permanentFailure = false
            var itemError: String? = null

            val signals = mutableListOf<Pair<String, String>>()
            if (!current.tracesSent) {
                signals.add("/v1/traces" to current.tracesJson)
            }
            if (!current.metricsSent && current.metricsJson != null) {
                signals.add("/v1/metrics" to current.metricsJson!!)
            }
            if (!current.logsSent && current.logsJson != null) {
                signals.add("/v1/logs" to current.logsJson!!)
            }

            for ((path, body) in signals) {
                val url = "${validated.normalizedBaseUrl}$path"
                val resp = httpClient.postJson(url, headers, body)
                if (resp.statusCode in 200..299) {
                    current = when (path) {
                        "/v1/traces" -> current.copy(tracesSent = true)
                        "/v1/metrics" -> current.copy(metricsSent = true)
                        "/v1/logs" -> current.copy(logsSent = true)
                        else -> current
                    }
                } else {
                    itemFailed = true
                    itemError = LogRedactor.redact(resp.error ?: "HTTP ${resp.statusCode} on $path")
                    if (resp.statusCode in 400..499 && resp.statusCode != 408 && resp.statusCode != 429) {
                        permanentFailure = true
                    }
                    break
                }
            }

            if (!itemFailed) {
                outboxStore.remove(item.sessionId)
                flushed++
            } else {
                failed++
                lastError = itemError
                val nextAttempts = current.attempts + 1
                if (permanentFailure || nextAttempts >= maxRetries) {
                    outboxStore.remove(item.sessionId)
                } else {
                    val backoffMs = BACKOFF_SCHEDULE_MS.getOrElse(nextAttempts - 1) { BACKOFF_SCHEDULE_MS.last() }
                    outboxStore.update(
                        current.copy(
                            attempts = nextAttempts,
                            nextAttemptAtMs = now + backoffMs,
                            lastError = itemError,
                        ),
                    )
                }
            }
        }

        val remaining = outboxStore.pendingCount()
        FlushResult(
            ok = failed == 0,
            enabled = true,
            flushed = flushed,
            failed = failed,
            deferred = deferred,
            remaining = remaining,
            lastError = lastError,
        )
    }

    companion object {
        const val DEFAULT_MAX_OUTBOX_SESSIONS = 20
        const val DEFAULT_MAX_RETRIES = 3
        private val BACKOFF_SCHEDULE_MS = longArrayOf(30_000L, 120_000L)
    }
}

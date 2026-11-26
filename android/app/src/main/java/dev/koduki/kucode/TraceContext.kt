package dev.koduki.kucode

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * Pure-JVM W3C Trace Context (`traceparent`) parser, formatter, and ID generator.
 *
 * Implements the W3C Trace Context specification:
 * - `trace-id`: 16-byte / 32 lowercase hex characters, never all-zero
 * - `span-id` / `parent-id`: 8-byte / 16 lowercase hex characters, never all-zero
 * - `traceparent`: `00-<32hex traceId>-<16hex spanId>-<2hex traceFlags>`
 */
object TraceContext {

    private const val ZERO_TRACE_ID = "00000000000000000000000000000000"
    private const val ZERO_SPAN_ID = "0000000000000000"
    private val TRACE_ID_REGEX = Regex("^[0-9a-f]{32}$")
    private val SPAN_ID_REGEX = Regex("^[0-9a-f]{16}$")
    private val HEX2_REGEX = Regex("^[0-9a-f]{2}$")
    private val random = SecureRandom()

    data class ParsedTraceParent(
        val version: String,
        val traceId: String,
        val parentSpanId: String,
        val traceFlags: String,
    )

    fun newTraceId(): String {
        val bytes = ByteArray(16)
        while (true) {
            random.nextBytes(bytes)
            val hex = bytesToLowerHex(bytes)
            if (hex != ZERO_TRACE_ID) return hex
        }
    }

    fun newSpanId(): String {
        val bytes = ByteArray(8)
        while (true) {
            random.nextBytes(bytes)
            val hex = bytesToLowerHex(bytes)
            if (hex != ZERO_SPAN_ID) return hex
        }
    }

    fun deterministicTraceId(sessionId: String): String {
        val hex = sha256LowerHex("kucode-trace:$sessionId").take(32)
        return if (hex == ZERO_TRACE_ID) "0".repeat(31) + "1" else hex
    }

    fun deterministicSpanId(sessionId: String): String {
        val hex = sha256LowerHex("kucode-span:$sessionId").take(16)
        return if (hex == ZERO_SPAN_ID) "0".repeat(15) + "1" else hex
    }

    fun isValidTraceId(value: String?): Boolean =
        value != null && TRACE_ID_REGEX.matches(value) && value != ZERO_TRACE_ID

    fun isValidSpanId(value: String?): Boolean =
        value != null && SPAN_ID_REGEX.matches(value) && value != ZERO_SPAN_ID

    fun formatTraceParent(traceId: String, spanId: String, traceFlags: String = "01"): String {
        if (!isValidTraceId(traceId)) {
            throw IllegalArgumentException("Invalid W3C traceId: must be 32 non-zero lowercase hex characters")
        }
        if (!isValidSpanId(spanId)) {
            throw IllegalArgumentException("Invalid W3C spanId: must be 16 non-zero lowercase hex characters")
        }
        val normalizedFlags = traceFlags.lowercase(Locale.US)
        if (!HEX2_REGEX.matches(normalizedFlags)) {
            throw IllegalArgumentException("Invalid W3C traceFlags: must be 2 hex characters")
        }
        return "00-$traceId-$spanId-$normalizedFlags"
    }

    fun parseTraceParent(header: String?): ParsedTraceParent? {
        if (header.isNullOrBlank()) return null
        val normalized = header.trim().lowercase(Locale.US)
        val parts = normalized.split('-')
        if (parts.size != 4) return null
        val version = parts[0]
        val traceId = parts[1]
        val parentSpanId = parts[2]
        val traceFlags = parts[3]
        if (!HEX2_REGEX.matches(version) || version == "ff") return null
        if (!isValidTraceId(traceId)) return null
        if (!isValidSpanId(parentSpanId)) return null
        if (!HEX2_REGEX.matches(traceFlags)) return null
        return ParsedTraceParent(
            version = version,
            traceId = traceId,
            parentSpanId = parentSpanId,
            traceFlags = traceFlags,
        )
    }

    private fun sha256LowerHex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytesToLowerHex(digest)
    }

    private fun bytesToLowerHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val hexDigits = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = hexDigits[v ushr 4]
            chars[i * 2 + 1] = hexDigits[v and 0x0F]
        }
        return String(chars)
    }
}

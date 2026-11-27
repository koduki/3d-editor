package dev.koduki.kucode

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.util.Locale

/**
 * Pure-JVM canonical OTLP/HTTP JSON v1.x serializer for [SessionLogStore] flight-recorder sessions.
 *
 * Emits Protobuf-JSON canonical representations where all `*TimeUnixNano`, `AnyValue.intValue`,
 * and `NumberDataPoint.asInt` fields are encoded as decimal strings, and passes all string values
 * through [LogRedactor.redact].
 */
object OtlpJsonSerializer {

    const val GRANULARITY_SUMMARY_AND_ERRORS = "summary-and-errors"
    const val GRANULARITY_FULL = "full"

    private const val SCOPE_NAME = "dev.koduki.kucode.flight_recorder"
    private val NANOS_PER_MS = BigInteger.valueOf(1_000_000L)

    data class OtlpPayloads(
        val tracesJson: JSONObject,
        val metricsJson: JSONObject?,
        val logsJson: JSONObject?,
        val combinedJson: JSONObject,
    )

    fun isValidGranularity(value: String?): Boolean =
        value == GRANULARITY_SUMMARY_AND_ERRORS || value == GRANULARITY_FULL

    fun serializeSession(
        store: SessionLogStore,
        selector: String = "current",
        granularity: String = GRANULARITY_SUMMARY_AND_ERRORS,
        vscodeDataDir: File? = null,
    ): OtlpPayloads? {
        val raw = store.loadSessionRawData(selector, vscodeDataDir) ?: return null
        val effectiveGranularity = if (isValidGranularity(granularity)) {
            granularity
        } else {
            GRANULARITY_SUMMARY_AND_ERRORS
        }
        val summary = raw.summary
        val sessionTraceId = if (TraceContext.isValidTraceId(summary.traceId)) {
            summary.traceId
        } else {
            TraceContext.deterministicTraceId(summary.sessionId)
        }
        val sessionRootSpanId = if (TraceContext.isValidSpanId(summary.rootSpanId)) {
            summary.rootSpanId
        } else {
            TraceContext.deterministicSpanId(summary.sessionId)
        }

        val resourceSpansArray = buildResourceSpansArray(
            summary = summary,
            rawSpans = raw.spans,
            sessionTraceId = sessionTraceId,
            sessionRootSpanId = sessionRootSpanId,
            granularity = effectiveGranularity,
        )
        val tracesJson = JSONObject().put("resourceSpans", resourceSpansArray)

        val resourceMetricsArray = buildResourceMetricsArray(
            summary = summary,
            metrics = raw.metrics,
            granularity = effectiveGranularity,
        )
        val metricsJson = resourceMetricsArray?.let { JSONObject().put("resourceMetrics", it) }

        val resourceLogsArray = buildResourceLogsArray(
            raw = raw,
            sessionTraceId = sessionTraceId,
            sessionRootSpanId = sessionRootSpanId,
            granularity = effectiveGranularity,
        )
        val logsJson = resourceLogsArray?.let { JSONObject().put("resourceLogs", it) }

        val combinedJson = JSONObject()
            .put("resourceSpans", buildResourceSpansArray(summary, raw.spans, sessionTraceId, sessionRootSpanId, effectiveGranularity))
            .put("resourceMetrics", buildResourceMetricsArray(summary, raw.metrics, effectiveGranularity) ?: JSONArray())
            .put("resourceLogs", buildResourceLogsArray(raw, sessionTraceId, sessionRootSpanId, effectiveGranularity) ?: JSONArray())

        return OtlpPayloads(
            tracesJson = tracesJson,
            metricsJson = metricsJson,
            logsJson = logsJson,
            combinedJson = combinedJson,
        )
    }

    private fun buildResourceSpansArray(
        summary: SessionLogStore.SessionSummary,
        rawSpans: List<SessionLogStore.SpanRecord>,
        sessionTraceId: String,
        sessionRootSpanId: String,
        granularity: String,
    ): JSONArray {
        val spans = rawSpans.toMutableList()
        val hasRoot = spans.any { it.spanId == sessionRootSpanId || it.name == SessionLogStore.ROOT_SPAN_NAME }
        if (!hasRoot) {
            val synthesizedStatus = when (summary.status) {
                SessionLogStore.SessionStatus.ENDED_ABNORMALLY -> SessionLogStore.SpanStatus.ERROR
                SessionLogStore.SessionStatus.ENDED_CLEANLY -> SessionLogStore.SpanStatus.OK
                SessionLogStore.SessionStatus.RUNNING -> SessionLogStore.SpanStatus.UNSET
            }
            val synthesizedMessage = if (summary.status == SessionLogStore.SessionStatus.ENDED_ABNORMALLY) {
                summary.exitSummary.ifBlank { null }
            } else {
                null
            }
            spans.add(
                0,
                SessionLogStore.SpanRecord(
                    traceId = sessionTraceId,
                    spanId = sessionRootSpanId,
                    parentSpanId = null,
                    name = SessionLogStore.ROOT_SPAN_NAME,
                    kind = SessionLogStore.SpanKind.INTERNAL,
                    startMs = summary.startedAtMs,
                    endMs = maxOf(summary.startedAtMs, summary.endedAtMs ?: summary.updatedAtMs),
                    status = synthesizedStatus,
                    statusMessage = synthesizedMessage,
                    attributes = emptyMap(),
                ),
            )
        }

        val fallbackEndMs = summary.endedAtMs ?: summary.updatedAtMs
        val spansArray = JSONArray()
        for (span in spans) {
            val spanTraceId = if (TraceContext.isValidTraceId(span.traceId)) span.traceId else sessionTraceId
            val spanId = if (TraceContext.isValidSpanId(span.spanId)) span.spanId else TraceContext.newSpanId()
            val endMs = maxOf(span.startMs, span.endMs ?: fallbackEndMs)

            val spanObj = JSONObject()
                .put("traceId", spanTraceId)
                .put("spanId", spanId)
                .put("name", LogRedactor.redact(span.name))
                .put("kind", span.kind.otlpCode)
                .put("startTimeUnixNano", msToUnixNanoString(span.startMs))
                .put("endTimeUnixNano", msToUnixNanoString(endMs))
                .put("attributes", mapToAttributesArray(span.attributes))

            if (TraceContext.isValidSpanId(span.parentSpanId)) {
                spanObj.put("parentSpanId", span.parentSpanId)
            }

            val statusObj = JSONObject().put("code", span.status.otlpCode)
            val statusMsg = span.statusMessage?.let { LogRedactor.redact(it) }?.takeIf { it.isNotEmpty() }
            if (statusMsg != null) {
                statusObj.put("message", statusMsg)
            }
            spanObj.put("status", statusObj)
            spansArray.put(spanObj)
        }

        val scopeSpan = JSONObject()
            .put("scope", buildScopeJson(summary))
            .put("spans", spansArray)

        val resourceSpan = JSONObject()
            .put("resource", buildResourceJson(summary, granularity))
            .put("scopeSpans", JSONArray().put(scopeSpan))

        return JSONArray().put(resourceSpan)
    }

    private fun buildResourceMetricsArray(
        summary: SessionLogStore.SessionSummary,
        metrics: List<SessionLogStore.MetricSnapshot>,
        granularity: String,
    ): JSONArray? {
        if (metrics.isEmpty()) return null

        val specs = listOf(
            MetricExtractor("kucode.process.pss_mb", "MiBy") { if (it.pssMb >= 0L) it.pssMb else null },
            MetricExtractor("kucode.process.avail_mem_mb", "MiBy") { if (it.availMemMb >= 0L) it.availMemMb else null },
            MetricExtractor("kucode.process.total_mem_mb", "MiBy") { if (it.totalMemMb >= 0L) it.totalMemMb else null },
            MetricExtractor("kucode.process.jvm_heap_used_mb", "MiBy") { if (it.jvmHeapUsedMb >= 0L) it.jvmHeapUsedMb else null },
            MetricExtractor("kucode.process.native_heap_mb", "MiBy") { if (it.nativeHeapMb >= 0L) it.nativeHeapMb else null },
            MetricExtractor("kucode.process.open_fds", "{fd}") { if (it.openFds >= 0) it.openFds.toLong() else null },
            MetricExtractor("kucode.process.thread_count", "{thread}") { if (it.threadCount >= 0) it.threadCount.toLong() else null },
            MetricExtractor("kucode.disk.free_mb", "MiBy") { if (it.freeDiskMb >= 0L) it.freeDiskMb else null },
            MetricExtractor("kucode.host.cpu_time_ms", "ms") { if (it.hostCpuTimeMs >= 0L) it.hostCpuTimeMs else null },
            MetricExtractor("kucode.device.battery_pct", "%") { if (it.batteryPct >= 0) it.batteryPct.toLong() else null },
            MetricExtractor("kucode.process.low_memory", "1") { if (it.lowMemory) 1L else 0L },
            MetricExtractor("kucode.server.alive", "1") { if (it.serverAlive) 1L else 0L },
            MetricExtractor("kucode.thermal.level", "1") { thermalStatusToLevel(it.thermalStatus) },
            MetricExtractor("kucode.telemetry.capture_duration_ms", "ms") {
                if (it.captureDurationMs >= 0L) it.captureDurationMs else null
            },
        )

        val metricsArray = JSONArray()
        for (spec in specs) {
            val dataPoints = JSONArray()
            for (snapshot in metrics) {
                val value = spec.extract(snapshot) ?: continue
                val dpAttrs = JSONArray()
                    .put(attr("kucode.metric.trigger", snapshot.trigger))
                    .put(attr("kucode.runtime.state", snapshot.runtimeState))
                    .put(attr("kucode.active_view", snapshot.activeView))
                    .put(attr("kucode.thermal.status", snapshot.thermalStatus))
                    .put(attr("kucode.network.summary", snapshot.networkSummary))
                    .put(attr("kucode.window.focus", snapshot.windowFocus))
                val dp = JSONObject()
                    .put("timeUnixNano", msToUnixNanoString(snapshot.timestampMs))
                    .put("asInt", value.toString())
                    .put("attributes", dpAttrs)
                dataPoints.put(dp)
            }
            if (dataPoints.length() > 0) {
                metricsArray.put(
                    JSONObject()
                        .put("name", spec.name)
                        .put("unit", spec.unit)
                        .put("gauge", JSONObject().put("dataPoints", dataPoints)),
                )
            }
        }

        if (metricsArray.length() == 0) return null

        val scopeMetric = JSONObject()
            .put("scope", buildScopeJson(summary))
            .put("metrics", metricsArray)

        val resourceMetric = JSONObject()
            .put("resource", buildResourceJson(summary, granularity))
            .put("scopeMetrics", JSONArray().put(scopeMetric))

        return JSONArray().put(resourceMetric)
    }

    private fun buildResourceLogsArray(
        raw: SessionLogStore.SessionRawData,
        sessionTraceId: String,
        sessionRootSpanId: String,
        granularity: String,
    ): JSONArray? {
        val summary = raw.summary
        val selectedEvents = if (granularity == GRANULARITY_FULL) {
            (raw.headEvents + raw.prevTailEvents + raw.tailEvents + raw.alertEvents)
                .distinctBy { it.seq }
                .sortedBy { it.seq }
        } else {
            raw.alertEvents.filter { it.level != SessionLogStore.EventLevel.INFO }
        }

        val logRecords = JSONArray()
        for (ev in selectedEvents) {
            val evTraceId = if (TraceContext.isValidTraceId(ev.traceId)) ev.traceId!! else sessionTraceId
            val evSpanId = if (TraceContext.isValidSpanId(ev.spanId)) ev.spanId!! else sessionRootSpanId
            val tsNano = msToUnixNanoString(ev.timestampMs)
            val attrs = JSONArray()
                .put(attr("kucode.event.source", ev.source))
                .put(attr("kucode.event.seq", ev.seq.toLong()))
                .put(attr("kucode.log.kind", "event"))
            if (!ev.benignNote.isNullOrBlank()) {
                attrs.put(attr("kucode.event.benign", ev.benignNote))
            }

            logRecords.put(
                JSONObject()
                    .put("timeUnixNano", tsNano)
                    .put("observedTimeUnixNano", tsNano)
                    .put("severityNumber", eventLevelToSeverityNumber(ev.level))
                    .put("severityText", ev.level.name)
                    .put("body", JSONObject().put("stringValue", LogRedactor.redact(ev.message)))
                    .put("traceId", evTraceId)
                    .put("spanId", evSpanId)
                    .put("attributes", attrs),
            )
        }

        val sessionEndNano = msToUnixNanoString(summary.endedAtMs ?: summary.updatedAtMs)
        if (raw.exitTrace.isNotBlank()) {
            val attrs = JSONArray()
                .put(attr("kucode.event.source", "exit_trace"))
                .put(attr("kucode.log.kind", "exit_trace"))
            logRecords.put(
                JSONObject()
                    .put("timeUnixNano", sessionEndNano)
                    .put("observedTimeUnixNano", sessionEndNano)
                    .put("severityNumber", 21)
                    .put("severityText", "FATAL")
                    .put("body", JSONObject().put("stringValue", LogRedactor.redact(raw.exitTrace.trim())))
                    .put("traceId", sessionTraceId)
                    .put("spanId", sessionRootSpanId)
                    .put("attributes", attrs),
            )
        }

        if (granularity == GRANULARITY_FULL && raw.vscodeSubLogs.isNotBlank()) {
            val attrs = JSONArray()
                .put(attr("kucode.event.source", "openvscode.sublogs"))
                .put(attr("kucode.log.kind", "vscode_sublogs"))
            logRecords.put(
                JSONObject()
                    .put("timeUnixNano", sessionEndNano)
                    .put("observedTimeUnixNano", sessionEndNano)
                    .put("severityNumber", 9)
                    .put("severityText", "INFO")
                    .put("body", JSONObject().put("stringValue", LogRedactor.redact(raw.vscodeSubLogs.trim())))
                    .put("traceId", sessionTraceId)
                    .put("spanId", sessionRootSpanId)
                    .put("attributes", attrs),
            )
        }

        if (logRecords.length() == 0) return null

        val scopeLog = JSONObject()
            .put("scope", buildScopeJson(summary))
            .put("logRecords", logRecords)

        val resourceLog = JSONObject()
            .put("resource", buildResourceJson(summary, granularity))
            .put("scopeLogs", JSONArray().put(scopeLog))

        return JSONArray().put(resourceLog)
    }

    private fun buildResourceJson(
        summary: SessionLogStore.SessionSummary,
        granularity: String,
    ): JSONObject {
        val attrs = JSONArray()
            .put(attr("service.name", "kucode"))
            .put(attr("service.namespace", "kucode"))
            .put(attr("service.version", summary.appVersion))
            .put(attr("service.instance.id", summary.sessionId))
            .put(attr("session.id", summary.sessionId))
            .put(attr("os.type", "android"))
            .put(attr("os.version", summary.androidSummary))
            .put(attr("device.model.identifier", summary.deviceSummary))
            .put(attr("process.pid", summary.pid.toLong()))
            .put(attr("kucode.package.name", summary.packageName))
            .put(attr("kucode.build.sha", summary.buildSha))
            .put(attr("kucode.runtime.version", summary.runtimeVersion))
            .put(attr("kucode.xr.spatial_supported", summary.xrSpatialSupported))
            .put(attr("kucode.page_size", summary.pageSize))
            .put(attr("kucode.webview.version", summary.webviewVersion))
            .put(attr("kucode.session.status", summary.status.name))
            .put(attr("kucode.exit.category", summary.exitCategory))
            .put(attr("kucode.exit.summary", summary.exitSummary))
            .put(attr("kucode.runtime.state", summary.lastRuntimeState))
            .put(attr("kucode.disable_seccomp", summary.disableSeccomp))
            .put(attr("kucode.has_all_files_access", summary.hasAllFilesAccess))
            .put(attr("kucode.notifications_enabled", summary.notificationsEnabled))
            .put(attr("kucode.battery_opt_ignored", summary.batteryOptIgnored))
            .put(attr("kucode.telemetry.granularity", granularity))
        return JSONObject().put("attributes", attrs)
    }

    private fun buildScopeJson(summary: SessionLogStore.SessionSummary): JSONObject = JSONObject()
        .put("name", SCOPE_NAME)
        .put("version", LogRedactor.redact(summary.appVersion))

    private fun mapToAttributesArray(attributes: Map<String, Any?>): JSONArray {
        val arr = JSONArray()
        for ((key, value) in attributes) {
            if (value == null || value == JSONObject.NULL) continue
            arr.put(attr(key, value))
        }
        return arr
    }

    private fun attr(key: String, value: Any): JSONObject = JSONObject()
        .put("key", LogRedactor.redact(key))
        .put("value", anyValue(value))

    private fun anyValue(value: Any): JSONObject = when (value) {
        is String -> JSONObject().put("stringValue", LogRedactor.redact(value))
        is Boolean -> JSONObject().put("boolValue", value)
        is Int -> JSONObject().put("intValue", value.toLong().toString())
        is Long -> JSONObject().put("intValue", value.toString())
        is Short -> JSONObject().put("intValue", value.toLong().toString())
        is Byte -> JSONObject().put("intValue", value.toLong().toString())
        is Double -> if (value.isFinite()) {
            JSONObject().put("doubleValue", value)
        } else {
            JSONObject().put("stringValue", value.toString())
        }
        is Float -> if (value.isFinite()) {
            JSONObject().put("doubleValue", value.toDouble())
        } else {
            JSONObject().put("stringValue", value.toString())
        }
        else -> JSONObject().put("stringValue", LogRedactor.redact(value.toString()))
    }

    private fun msToUnixNanoString(epochMs: Long): String =
        BigInteger.valueOf(epochMs.coerceAtLeast(0L)).multiply(NANOS_PER_MS).toString()

    private fun eventLevelToSeverityNumber(level: SessionLogStore.EventLevel): Int = when (level) {
        SessionLogStore.EventLevel.INFO -> 9
        SessionLogStore.EventLevel.WARN -> 13
        SessionLogStore.EventLevel.ERROR -> 17
        SessionLogStore.EventLevel.FATAL -> 21
    }

    private fun thermalStatusToLevel(status: String): Long? = when (status.trim().uppercase(Locale.US)) {
        "NONE" -> 0L
        "LIGHT" -> 1L
        "MODERATE" -> 2L
        "SEVERE" -> 3L
        "CRITICAL" -> 4L
        "EMERGENCY" -> 5L
        "SHUTDOWN" -> 6L
        else -> null
    }

    private class MetricExtractor(
        val name: String,
        val unit: String,
        val extract: (SessionLogStore.MetricSnapshot) -> Long?,
    )
}

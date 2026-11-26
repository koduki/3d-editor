#!/usr/bin/env node
/**
 * Read-Only KuCode Flight Recorder analyzer.
 *
 * Supports:
 * 1. Raw SessionLogStore session directories (`meta.json`, `events.alerts.jsonl`, `metrics.jsonl`, `exit-trace.txt`, `vscode-sublogs.txt`)
 * 2. Exported KuCode AI Diagnostic Bundle Markdown (`# KuCode AI セッション診断バンドル (Flight Recorder)`),
 *    whether uploaded to `/home/antigravity/upload/` or pasted into a GitHub Issue body.
 * 3. OpenTelemetry (OTLP/HTTP JSON v1.x) payloads — single-signal (`resourceSpans` / `resourceMetrics` / `resourceLogs`),
 *    combined `{ resourceSpans, resourceMetrics, resourceLogs }`, multi-session batches/arrays, or HTTP(S) endpoints.
 *
 * Strips unbounded Section 4 raw timelines and routine INFO logs so the main agent receives only structured
 * sessionId-correlated triage metadata, span summaries, alert counts, anomalous metrics, and crash traces.
 * Treats all diagnostic inputs strictly as untrusted data, never instructions.
 */
import fs from "node:fs";
import http from "node:http";
import https from "node:https";
import path from "node:path";
import { fileURLToPath } from "node:url";

const DEFAULT_FETCH_MAX_BYTES = 10 * 1024 * 1024; // 10 MiB
const DEFAULT_FETCH_TIMEOUT_MS = 15_000; // 15s
const SUBLOG_ERROR_RE = /(error|fatal|exception|eacces|enospc|crash|killed|sigsegv|sigkill)/i;
const HEADER_NAME_VALUE_RE = /^([A-Za-z0-9-]+)\s*:\s*(.+)$/;

function readJsonl(filePath) {
  if (!fs.existsSync(filePath)) return [];
  return fs
    .readFileSync(filePath, "utf8")
    .split(/\r?\n/)
    .filter((l) => l.trim())
    .map((line) => {
      try {
        return JSON.parse(line);
      } catch {
        return { parseError: true, raw: line.slice(0, 200) };
      }
    });
}

function truncateText(value, maxLen) {
  const s = String(value ?? "");
  return s.length > maxLen ? s.slice(0, maxLen) : s;
}

function extractAnyValue(val) {
  if (val === null || val === undefined) return undefined;
  if (typeof val !== "object") return val;
  if ("stringValue" in val) return String(val.stringValue);
  if ("boolValue" in val) return Boolean(val.boolValue);
  if ("intValue" in val) {
    const n = Number(val.intValue);
    return Number.isFinite(n) ? n : String(val.intValue);
  }
  if ("doubleValue" in val) {
    const n = Number(val.doubleValue);
    return Number.isFinite(n) ? n : String(val.doubleValue);
  }
  if ("arrayValue" in val && Array.isArray(val.arrayValue?.values)) {
    return val.arrayValue.values.map(extractAnyValue);
  }
  if ("kvlistValue" in val && Array.isArray(val.kvlistValue?.values)) {
    return attributesToMap(val.kvlistValue.values);
  }
  if ("bytesValue" in val) return String(val.bytesValue);
  return undefined;
}

function attributesToMap(attrs) {
  const out = {};
  if (!Array.isArray(attrs)) return out;
  for (const attr of attrs) {
    if (!attr || typeof attr.key !== "string") continue;
    out[attr.key] = extractAnyValue(attr.value);
  }
  return out;
}

function parseNanoToBigInt(value) {
  if (typeof value === "bigint") return value;
  if (typeof value === "number" && Number.isFinite(value)) return BigInt(Math.trunc(value));
  if (typeof value === "string" && /^\d+$/.test(value.trim())) return BigInt(value.trim());
  return null;
}

function parseMetricPointNumber(dp) {
  if (!dp || typeof dp !== "object") return null;
  if ("asInt" in dp && dp.asInt !== null && dp.asInt !== undefined) {
    const n = Number(dp.asInt);
    return Number.isFinite(n) ? n : null;
  }
  if ("asDouble" in dp && dp.asDouble !== null && dp.asDouble !== undefined) {
    const n = Number(dp.asDouble);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

function normalizeSpanStatus(status) {
  if (!status || typeof status !== "object") return "UNSET";
  const code = status.code;
  if (code === 2 || code === "2" || code === "STATUS_CODE_ERROR" || code === "ERROR") return "ERROR";
  if (code === 1 || code === "1" || code === "STATUS_CODE_OK" || code === "OK") return "OK";
  return "UNSET";
}

function normalizeLogSeverity(rec) {
  const sevNum =
    typeof rec?.severityNumber === "number"
      ? rec.severityNumber
      : rec?.severityNumber !== undefined && rec?.severityNumber !== null
        ? Number(rec.severityNumber)
        : Number.NaN;
  const sevText = String(rec?.severityText || "").toUpperCase();
  if (Number.isFinite(sevNum) && sevNum > 0) {
    if (sevNum >= 21) return "FATAL";
    if (sevNum >= 17) return "ERROR";
    if (sevNum >= 13) return "WARN";
    if (sevNum >= 9) return "INFO";
    if (sevNum >= 5) return "DEBUG";
    return "TRACE";
  }
  if (sevText.startsWith("FATAL") || sevText.startsWith("CRIT")) return "FATAL";
  if (sevText.startsWith("ERR")) return "ERROR";
  if (sevText.startsWith("WARN")) return "WARN";
  if (sevText.startsWith("INFO")) return "INFO";
  if (sevText.startsWith("DEBUG")) return "DEBUG";
  if (sevText.startsWith("TRACE")) return "TRACE";
  return "UNKNOWN";
}

function isSingleOtlpObject(obj) {
  if (!obj || typeof obj !== "object" || Array.isArray(obj)) return false;
  return (
    Array.isArray(obj.resourceSpans) ||
    Array.isArray(obj.resourceMetrics) ||
    Array.isArray(obj.resourceLogs)
  );
}

function isOtlpPayload(parsed) {
  if (isSingleOtlpObject(parsed)) return true;
  if (Array.isArray(parsed) && parsed.length > 0 && parsed.every((item) => isSingleOtlpObject(item))) {
    return true;
  }
  return false;
}

function createEmptyOtlpSessionBucket(fallbackSessionId) {
  return {
    resourceAttrs: {},
    fallbackSessionId,
    traceId: "",
    rootTraceId: "",
    spans: [],
    metrics: [],
    logRecords: [],
  };
}

function getOrCreateBucket(buckets, key, fallbackSessionId) {
  let bucket = buckets.get(key);
  if (!bucket) {
    bucket = createEmptyOtlpSessionBucket(fallbackSessionId);
    buckets.set(key, bucket);
  }
  return bucket;
}

function mergeResourceAttrs(bucket, resAttrs) {
  for (const [k, v] of Object.entries(resAttrs)) {
    if (v !== undefined && v !== null && v !== "" && bucket.resourceAttrs[k] === undefined) {
      bucket.resourceAttrs[k] = v;
    }
  }
}

function inferDefaultSessionKeyForBundle(bundle) {
  const found = new Set();
  for (const list of [bundle.resourceSpans, bundle.resourceMetrics, bundle.resourceLogs]) {
    if (!Array.isArray(list)) continue;
    for (const entry of list) {
      const attrs = attributesToMap(entry?.resource?.attributes);
      const sid = attrs["session.id"] || attrs["service.instance.id"];
      if (sid) found.add(String(sid));
    }
  }
  return found.size === 1 ? Array.from(found)[0] : null;
}

function finalizeOtlpBucket(bucket, sourceLabel) {
  const attrs = bucket.resourceAttrs;
  const sessionId = String(attrs["session.id"] || attrs["service.instance.id"] || bucket.fallbackSessionId || "unknown");
  const buildSha = String(attrs["kucode.build.sha"] || "unknown");
  const status = String(attrs["kucode.session.status"] || "UNKNOWN");
  const exitCategory = String(attrs["kucode.exit.category"] || "unknown");
  const appVersion = String(attrs["service.version"] || "unknown");
  const packageName = String(attrs["kucode.package.name"] || "unknown");

  // --- Spans ---
  const parsedSpans = [];
  const errorSpans = [];
  let errorCount = 0;
  let traceId = bucket.rootTraceId || bucket.traceId || "";

  for (const span of bucket.spans) {
    if (!span || typeof span !== "object") continue;
    const spanTraceId = typeof span.traceId === "string" ? span.traceId : "";
    if (spanTraceId) {
      if (!traceId) traceId = spanTraceId;
      if (span.name === "kucode.session" || !span.parentSpanId) {
        traceId = spanTraceId;
      }
    }
    const startNs = parseNanoToBigInt(span.startTimeUnixNano);
    const endNs = parseNanoToBigInt(span.endTimeUnixNano);
    const durationMs =
      startNs !== null && endNs !== null && endNs >= startNs
        ? Number((endNs - startNs) / 1_000_000n)
        : 0;
    const normStatus = normalizeSpanStatus(span.status);
    const entry = {
      name: truncateText(span.name || "unnamed", 128),
      spanId: String(span.spanId || ""),
      parentSpanId: span.parentSpanId ? String(span.parentSpanId) : null,
      durationMs,
      status: normStatus,
      statusMessage: truncateText(span.status?.message || "", 240),
    };
    parsedSpans.push(entry);
    if (normStatus === "ERROR") {
      errorCount += 1;
      if (errorSpans.length < 20) {
        errorSpans.push({
          name: entry.name,
          spanId: entry.spanId,
          parentSpanId: entry.parentSpanId,
          durationMs: entry.durationMs,
          statusMessage: entry.statusMessage,
        });
      }
    }
  }

  const slowestSpans = [...parsedSpans]
    .sort((a, b) => b.durationMs - a.durationMs)
    .slice(0, 10)
    .map((s) => ({
      name: s.name,
      spanId: s.spanId,
      durationMs: s.durationMs,
      status: s.status,
    }));

  // --- Metrics ---
  const snapshotTimestamps = new Set();
  let maxPointCount = 0;
  let maxPssMb = -1;
  let minFreeDiskMb = Number.POSITIVE_INFINITY;
  let lowMemoryEvents = 0;
  let serverDownSnapshots = 0;
  const thermalStatuses = new Set();

  for (const metric of bucket.metrics) {
    if (!metric || typeof metric !== "object") continue;
    const metricName = String(metric.name || "");
    const dataPoints =
      metric.gauge?.dataPoints ||
      metric.sum?.dataPoints ||
      metric.histogram?.dataPoints ||
      [];
    if (!Array.isArray(dataPoints)) continue;
    if (dataPoints.length > maxPointCount) maxPointCount = dataPoints.length;

    for (const dp of dataPoints) {
      if (!dp || typeof dp !== "object") continue;
      if (dp.timeUnixNano !== undefined && dp.timeUnixNano !== null && String(dp.timeUnixNano) !== "") {
        snapshotTimestamps.add(String(dp.timeUnixNano));
      }
      const dpAttrs = attributesToMap(dp.attributes);
      if (dpAttrs["kucode.thermal.status"] && dpAttrs["kucode.thermal.status"] !== "UNKNOWN") {
        thermalStatuses.add(String(dpAttrs["kucode.thermal.status"]));
      }

      const numVal = parseMetricPointNumber(dp);
      if (numVal === null) continue;

      if (metricName === "kucode.process.pss_mb" && numVal > maxPssMb) {
        maxPssMb = numVal;
      } else if (metricName === "kucode.disk.free_mb" && numVal >= 0 && numVal < minFreeDiskMb) {
        minFreeDiskMb = numVal;
      } else if (metricName === "kucode.process.low_memory" && numVal >= 1) {
        lowMemoryEvents += 1;
      } else if (metricName === "kucode.server.alive" && numVal === 0) {
        serverDownSnapshots += 1;
      }
    }
  }

  const snapshotCount = snapshotTimestamps.size > 0 ? snapshotTimestamps.size : maxPointCount;

  // --- Logs ---
  const alertCountsByLevel = {};
  const alertCountsBySource = {};
  const alerts = [];
  const exitTraceLines = [];
  const vscodeSublogErrors = [];
  let omittedInfoLogsCount = 0;

  for (const rec of bucket.logRecords) {
    if (!rec || typeof rec !== "object") continue;
    if (!traceId && typeof rec.traceId === "string" && rec.traceId) {
      traceId = rec.traceId;
    }
    const logAttrs = attributesToMap(rec.attributes);
    const logKind = String(logAttrs["kucode.log.kind"] || "");
    const bodyText = String(extractAnyValue(rec.body) ?? "");

    if (logKind === "exit_trace") {
      for (const line of bodyText.split(/\r?\n/)) {
        const trimmed = line.trimEnd();
        if (trimmed && exitTraceLines.length < 30) {
          exitTraceLines.push(truncateText(trimmed, 400));
        }
      }
      continue;
    }

    if (logKind === "vscode_sublogs") {
      for (const line of bodyText.split(/\r?\n/)) {
        if (SUBLOG_ERROR_RE.test(line)) {
          vscodeSublogErrors.push(truncateText(line.trimEnd(), 400));
        }
      }
      continue;
    }

    const level = normalizeLogSeverity(rec);
    if (level === "WARN" || level === "ERROR" || level === "FATAL") {
      const source = String(logAttrs["kucode.event.source"] || "unknown");
      alertCountsByLevel[level] = (alertCountsByLevel[level] || 0) + 1;
      alertCountsBySource[source] = (alertCountsBySource[source] || 0) + 1;
      alerts.push({
        level,
        source,
        msg: truncateText(bodyText, 240),
      });
    } else {
      omittedInfoLogsCount += 1;
    }
  }

  const trimmedSublogErrors = vscodeSublogErrors.slice(-20);
  const abnormal =
    status === "ENDED_ABNORMALLY" ||
    errorCount > 0 ||
    (alertCountsByLevel.FATAL || 0) > 0 ||
    (alertCountsByLevel.ERROR || 0) > 0 ||
    exitTraceLines.length > 0;

  return {
    schema: 2,
    readOnly: true,
    untrustedData: true,
    format: "otlp-json",
    source: sourceLabel,
    sessionId,
    traceId,
    buildSha,
    status,
    exitCategory,
    abnormal,
    appVersion,
    packageName,
    spansSummary: {
      total: parsedSpans.length,
      errorCount,
      errorSpans,
      slowestSpans,
    },
    metricsSummary: {
      snapshotCount,
      maxPssMb,
      minFreeDiskMb: Number.isFinite(minFreeDiskMb) ? minFreeDiskMb : -1,
      lowMemoryEvents,
      serverDownSnapshots,
      thermalStatuses: Array.from(thermalStatuses),
    },
    alertsSummary: {
      total: alerts.length,
      byLevel: alertCountsByLevel,
      bySource: alertCountsBySource,
      recentAlerts: alerts.slice(-10),
    },
    exitTraceHead: exitTraceLines,
    vscodeSublogErrors: trimmedSublogErrors,
    omittedInfoLogsCount,
  };
}

export function parseOtlpJsonBundle(rawOrObj, sourceLabel = "otlp.json") {
  let parsed = rawOrObj;
  if (typeof rawOrObj === "string") {
    parsed = JSON.parse(rawOrObj);
  }
  const inputWasArray = Array.isArray(parsed);
  if (inputWasArray && parsed.length === 0) {
    return [];
  }
  if (!isOtlpPayload(parsed)) {
    throw new Error("Input is not a valid OTLP JSON payload (missing resourceSpans/resourceMetrics/resourceLogs)");
  }

  const bundles = inputWasArray ? parsed : [parsed];
  const buckets = new Map();

  for (let idx = 0; idx < bundles.length; idx += 1) {
    const bundle = bundles[idx];
    const bundleDefaultSid = inferDefaultSessionKeyForBundle(bundle);
    const fallbackKey = inputWasArray ? `__bundle_${idx}__` : "__default__";

    if (Array.isArray(bundle.resourceSpans)) {
      for (const rs of bundle.resourceSpans) {
        const resAttrs = attributesToMap(rs?.resource?.attributes);
        let spanTraceId = "";
        const spans = [];
        for (const ss of Array.isArray(rs?.scopeSpans) ? rs.scopeSpans : []) {
          for (const sp of Array.isArray(ss?.spans) ? ss.spans : []) {
            if (!spanTraceId && typeof sp?.traceId === "string" && sp.traceId) {
              spanTraceId = sp.traceId;
            }
            spans.push(sp);
          }
        }
        const sid = resAttrs["session.id"] || resAttrs["service.instance.id"] || bundleDefaultSid;
        const key = sid ? `sid:${sid}` : spanTraceId ? `trace:${spanTraceId}` : fallbackKey;
        const bucket = getOrCreateBucket(buckets, key, sid ? String(sid) : "unknown");
        mergeResourceAttrs(bucket, resAttrs);
        if (spanTraceId && !bucket.traceId) bucket.traceId = spanTraceId;
        bucket.spans.push(...spans);
      }
    }

    if (Array.isArray(bundle.resourceMetrics)) {
      for (const rm of bundle.resourceMetrics) {
        const resAttrs = attributesToMap(rm?.resource?.attributes);
        const sid = resAttrs["session.id"] || resAttrs["service.instance.id"] || bundleDefaultSid;
        const key = sid ? `sid:${sid}` : fallbackKey;
        const bucket = getOrCreateBucket(buckets, key, sid ? String(sid) : "unknown");
        mergeResourceAttrs(bucket, resAttrs);
        for (const sm of Array.isArray(rm?.scopeMetrics) ? rm.scopeMetrics : []) {
          for (const metric of Array.isArray(sm?.metrics) ? sm.metrics : []) {
            bucket.metrics.push(metric);
          }
        }
      }
    }

    if (Array.isArray(bundle.resourceLogs)) {
      for (const rl of bundle.resourceLogs) {
        const resAttrs = attributesToMap(rl?.resource?.attributes);
        let logTraceId = "";
        const records = [];
        for (const sl of Array.isArray(rl?.scopeLogs) ? rl.scopeLogs : []) {
          for (const rec of Array.isArray(sl?.logRecords) ? sl.logRecords : []) {
            if (!logTraceId && typeof rec?.traceId === "string" && rec.traceId) {
              logTraceId = rec.traceId;
            }
            records.push(rec);
          }
        }
        const sid = resAttrs["session.id"] || resAttrs["service.instance.id"] || bundleDefaultSid;
        const key = sid ? `sid:${sid}` : logTraceId ? `trace:${logTraceId}` : fallbackKey;
        const bucket = getOrCreateBucket(buckets, key, sid ? String(sid) : "unknown");
        mergeResourceAttrs(bucket, resAttrs);
        if (logTraceId && !bucket.traceId) bucket.traceId = logTraceId;
        bucket.logRecords.push(...records);
      }
    }
  }

  if (buckets.size === 0) {
    const emptyDigest = finalizeOtlpBucket(createEmptyOtlpSessionBucket("unknown"), sourceLabel);
    return inputWasArray ? [emptyDigest] : emptyDigest;
  }

  const summaries = Array.from(buckets.values()).map((b) => finalizeOtlpBucket(b, sourceLabel));
  if (!inputWasArray && summaries.length === 1) {
    return summaries[0];
  }
  return summaries;
}

export function filterOtlpSummaries(items, filters = {}) {
  const list = Array.isArray(items) ? items : items ? [items] : [];
  const { sessionId, buildSha, abnormalOnly } = filters || {};
  return list.filter((item) => {
    if (!item || typeof item !== "object") return false;
    if (sessionId && String(item.sessionId || "") !== String(sessionId)) {
      return false;
    }
    if (buildSha && String(item.buildSha || "") !== String(buildSha)) {
      return false;
    }
    if (abnormalOnly && item.abnormal !== true) {
      return false;
    }
    return true;
  });
}

function resolveAuthHeaderFromEnv(headerEnv, env) {
  if (!headerEnv) return null;
  const envName = String(headerEnv).trim();
  if (!envName) return null;
  const raw = env[envName];
  if (raw === undefined || raw === null || String(raw).trim() === "") {
    throw new Error(`Auth header environment variable '${envName}' is not set or empty`);
  }
  const trimmed = String(raw).trim();
  if (/[\r\n]/.test(trimmed)) {
    throw new Error(`Auth header environment variable '${envName}' contains invalid newline characters`);
  }
  const match = trimmed.match(HEADER_NAME_VALUE_RE);
  if (match) {
    return { name: match[1], value: match[2].trim() };
  }
  return { name: "Authorization", value: trimmed };
}

export function fetchOtlpJsonFromUrl(url, options = {}) {
  const {
    headerEnv = null,
    env = process.env,
    maxBytes = DEFAULT_FETCH_MAX_BYTES,
    timeoutMs = DEFAULT_FETCH_TIMEOUT_MS,
  } = options || {};

  let parsedUrl;
  try {
    parsedUrl = new URL(String(url));
  } catch {
    return Promise.reject(new Error("Invalid URL provided to fetchOtlpJsonFromUrl"));
  }

  if (parsedUrl.protocol !== "http:" && parsedUrl.protocol !== "https:") {
    return Promise.reject(
      new Error(`Unsupported URL scheme '${parsedUrl.protocol}'; only http: and https: are allowed`),
    );
  }

  let authHeader = null;
  try {
    authHeader = resolveAuthHeaderFromEnv(headerEnv, env);
  } catch (err) {
    return Promise.reject(err);
  }

  const safeTarget = `${parsedUrl.origin}${parsedUrl.pathname}`;
  const headers = {
    Accept: "application/json",
  };
  if (authHeader) {
    headers[authHeader.name] = authHeader.value;
  }

  const transport = parsedUrl.protocol === "https:" ? https : http;

  return new Promise((resolve, reject) => {
    let settled = false;
    const finishError = (message) => {
      if (settled) return;
      settled = true;
      reject(new Error(message));
    };
    const finishSuccess = (data) => {
      if (settled) return;
      settled = true;
      resolve(data);
    };

    const req = transport.request(
      parsedUrl,
      {
        method: "GET",
        headers,
        timeout: timeoutMs,
      },
      (res) => {
        const statusCode = res.statusCode || 0;
        if (statusCode < 200 || statusCode >= 300) {
          res.resume();
          finishError(`HTTP ${statusCode} while fetching OTLP JSON from ${safeTarget}`);
          return;
        }

        const contentLength = Number(res.headers["content-length"]);
        if (Number.isFinite(contentLength) && contentLength > maxBytes) {
          res.destroy();
          finishError(`OTLP response from ${safeTarget} exceeds maximum allowed size (${maxBytes} bytes)`);
          return;
        }

        const chunks = [];
        let totalBytes = 0;
        res.on("data", (chunk) => {
          totalBytes += chunk.length;
          if (totalBytes > maxBytes) {
            res.destroy();
            req.destroy();
            finishError(`OTLP response from ${safeTarget} exceeds maximum allowed size (${maxBytes} bytes)`);
            return;
          }
          chunks.push(chunk);
        });
        res.on("end", () => {
          if (settled) return;
          try {
            const text = Buffer.concat(chunks, totalBytes).toString("utf8");
            const json = JSON.parse(text);
            finishSuccess(json);
          } catch {
            finishError(`Failed to parse OTLP JSON response from ${safeTarget}`);
          }
        });
        res.on("error", () => {
          finishError(`Network read error while fetching OTLP JSON from ${safeTarget}`);
        });
      },
    );

    req.setTimeout(timeoutMs, () => {
      req.destroy();
      finishError(`Timed out after ${timeoutMs}ms fetching OTLP JSON from ${safeTarget}`);
    });

    req.on("error", (err) => {
      const code = err && typeof err.code === "string" ? ` (${err.code})` : "";
      finishError(`Request failed${code} while fetching OTLP JSON from ${safeTarget}`);
    });

    req.end();
  });
}

export function analyzeSessionDir(sessionDir) {
  const metaPath = path.join(sessionDir, "meta.json");
  const meta = fs.existsSync(metaPath) ? JSON.parse(fs.readFileSync(metaPath, "utf8")) : { sessionId: path.basename(sessionDir) };

  const alerts = readJsonl(path.join(sessionDir, "events.alerts.jsonl"));
  const alertCountsByLevel = {};
  const alertCountsBySource = {};
  for (const ev of alerts) {
    const lvl = ev.level || "UNKNOWN";
    const src = ev.source || "unknown";
    alertCountsByLevel[lvl] = (alertCountsByLevel[lvl] || 0) + 1;
    alertCountsBySource[src] = (alertCountsBySource[src] || 0) + 1;
  }

  const metrics = readJsonl(path.join(sessionDir, "metrics.jsonl"));
  let maxPssMb = -1;
  let minFreeDiskMb = Number.POSITIVE_INFINITY;
  let lowMemoryEvents = 0;
  let serverDownSnapshots = 0;
  const thermalStatuses = new Set();

  for (const m of metrics) {
    if (typeof m.pssMb === "number" && m.pssMb > maxPssMb) maxPssMb = m.pssMb;
    if (typeof m.freeDiskMb === "number" && m.freeDiskMb >= 0 && m.freeDiskMb < minFreeDiskMb) minFreeDiskMb = m.freeDiskMb;
    if (m.lowMemory === true) lowMemoryEvents += 1;
    if (m.serverAlive === false) serverDownSnapshots += 1;
    if (m.thermalStatus && m.thermalStatus !== "UNKNOWN") thermalStatuses.add(m.thermalStatus);
  }

  const exitTracePath = path.join(sessionDir, "exit-trace.txt");
  const exitTraceLines = fs.existsSync(exitTracePath)
    ? fs.readFileSync(exitTracePath, "utf8").split(/\r?\n/).filter(Boolean).slice(0, 30)
    : [];

  const sublogsPath = path.join(sessionDir, "vscode-sublogs.txt");
  const sublogErrors = fs.existsSync(sublogsPath)
    ? fs
        .readFileSync(sublogsPath, "utf8")
        .split(/\r?\n/)
        .filter((l) => SUBLOG_ERROR_RE.test(l))
        .slice(-20)
    : [];

  const abnormal =
    meta.status === "ENDED_ABNORMALLY" ||
    (alertCountsByLevel.FATAL || 0) > 0 ||
    (alertCountsByLevel.ERROR || 0) > 0 ||
    exitTraceLines.length > 0;

  return {
    schema: 2,
    readOnly: true,
    format: "session-dir",
    sessionId: meta.sessionId || path.basename(sessionDir),
    status: meta.status || "UNKNOWN",
    abnormal,
    appVersion: meta.appVersion || meta.env?.appVersion || "unknown",
    packageName: meta.packageName || meta.env?.packageName || "unknown",
    buildSha: meta.buildSha || "unknown",
    alertsSummary: {
      total: alerts.length,
      byLevel: alertCountsByLevel,
      bySource: alertCountsBySource,
      recentAlerts: alerts.slice(-10).map((a) => ({
        level: a.level,
        source: a.source,
        msg: String(a.msg || a.message || "").slice(0, 240),
      })),
    },
    metricsSummary: {
      snapshotCount: metrics.length,
      maxPssMb,
      minFreeDiskMb: Number.isFinite(minFreeDiskMb) ? minFreeDiskMb : -1,
      lowMemoryEvents,
      serverDownSnapshots,
      thermalStatuses: Array.from(thermalStatuses),
    },
    exitTraceHead: exitTraceLines,
    vscodeSublogErrors: sublogErrors,
  };
}

export function parseKuCodeAiMarkdownBundle(text, sourceLabel = "bundle.md") {
  const grab = (re) => {
    const m = text.match(re);
    return m ? m[1].trim() : "unknown";
  };

  const sessionId = grab(/-\s*Session ID:\s*`([^`]+)`/);
  const statusMatch = text.match(/-\s*Session Status:\s*`([^`]+)`\s*\(`([^`]+)`\)/);
  const status = statusMatch ? statusMatch[1] : grab(/-\s*Session Status:\s*`([^`]+)`/);
  const exitCategory = statusMatch ? statusMatch[2] : "unknown";
  const exitSummary = grab(/-\s*Exit \/ State Summary:\s*`([^`]+)`/);
  const app = grab(/App:\s*`([^`]+)`/);
  const device = grab(/Device:\s*`([^`]+)`/);
  const android = grab(/Android:\s*`([^`]+)`/);
  const counters = grab(/-\s*Counters:\s*`([^`]+)`/);

  // Split by "## <n>." sections; intentionally drop unbounded Section 4 raw timeline.
  const section = (num) => {
    const re = new RegExp(`##\\s+${num}\\.[^\\n]*\\n([\\s\\S]*?)(?=\\n##\\s+\\d+\\.|$)`);
    const m = text.match(re);
    return m ? m[1] : "";
  };

  const sec1 = section(1);
  const alerts = sec1
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => /^-\s*`\[\+/.test(l))
    .slice(-15);

  const sec2 = section(2);
  const anomalousMetricRows = sec2
    .split(/\r?\n/)
    .filter((l) => l.startsWith("| +") && (/srv=false|MB!|MODERATE|SEVERE|CRITICAL|EMERGENCY|SHUTDOWN/i.test(l)))
    .slice(0, 12);

  const sec3 = section(3)
    .replace(/```[a-z]*\n?/gi, "")
    .split(/\r?\n/)
    .map((l) => l.trimEnd())
    .filter(Boolean)
    .slice(0, 30);

  const sec5 = section(5)
    .replace(/```[a-z]*\n?/gi, "")
    .split(/\r?\n/)
    .filter((l) => SUBLOG_ERROR_RE.test(l))
    .slice(-20);

  const errorCountMatch = counters.match(/errors=(\d+)/);
  const errorCount = errorCountMatch ? Number(errorCountMatch[1]) : 0;
  const abnormal = status === "ENDED_ABNORMALLY" || errorCount > 0 || sec3.length > 0;

  return {
    schema: 2,
    readOnly: true,
    format: "kucode-ai-markdown-bundle",
    source: sourceLabel,
    sessionId,
    status,
    exitCategory,
    exitSummary,
    app,
    device,
    android,
    counters,
    abnormal,
    alerts,
    anomalousMetricRows,
    exitTraceHead: sec3,
    vscodeSublogErrors: sec5,
    omittedRawTimelineSection4: true,
  };
}

export function analyzeDiagnosticBundleFile(filePath) {
  const raw = fs.readFileSync(filePath, "utf8");
  const trimmed = raw.trim();
  if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
    try {
      const parsed = JSON.parse(trimmed);
      if (isOtlpPayload(parsed)) {
        return parseOtlpJsonBundle(parsed, path.basename(filePath));
      }
    } catch {
      // Fall through to Markdown / generic text handling.
    }
  }
  if (/KuCode AI セッション診断バンドル|## 1\. AI トリアージ要約/.test(raw)) {
    return parseKuCodeAiMarkdownBundle(raw, path.basename(filePath));
  }
  const lines = raw.split(/\r?\n/);
  const errorLines = lines
    .filter((l) => /(ENDED_ABNORMALLY|FATAL|ERROR|Exception|Caused by:|exit=|sigkill|sigsegv|OOM)/i.test(l))
    .slice(0, 35);
  return {
    schema: 2,
    readOnly: true,
    format: "generic-text",
    bundleFile: path.basename(filePath),
    totalLines: lines.length,
    abnormal: errorLines.length > 0,
    keyErrorLines: errorLines,
  };
}

function parseCliArgs(argv) {
  const opts = {
    target: null,
    url: null,
    headerEnv: null,
    sessionId: null,
    buildSha: null,
    abnormalOnly: false,
  };

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--abnormal-only") {
      opts.abnormalOnly = true;
    } else if (arg === "--url" || arg.startsWith("--url=")) {
      const val = arg === "--url" ? argv[++i] : arg.slice("--url=".length);
      if (!val) throw new Error("Missing value for --url");
      opts.url = val;
    } else if (arg === "--header-env" || arg.startsWith("--header-env=")) {
      const val = arg === "--header-env" ? argv[++i] : arg.slice("--header-env=".length);
      if (!val) throw new Error("Missing value for --header-env");
      opts.headerEnv = val;
    } else if (arg === "--session-id" || arg.startsWith("--session-id=")) {
      const val = arg === "--session-id" ? argv[++i] : arg.slice("--session-id=".length);
      if (!val) throw new Error("Missing value for --session-id");
      opts.sessionId = val;
    } else if (arg === "--build-sha" || arg.startsWith("--build-sha=")) {
      const val = arg === "--build-sha" ? argv[++i] : arg.slice("--build-sha=".length);
      if (!val) throw new Error("Missing value for --build-sha");
      opts.buildSha = val;
    } else if (arg === "--help" || arg === "-h") {
      opts.help = true;
    } else if (arg.startsWith("-")) {
      throw new Error(`Unknown option: ${arg}`);
    } else if (!opts.target) {
      opts.target = arg;
    } else {
      throw new Error(`Unexpected positional argument: ${arg}`);
    }
  }
  return opts;
}

const USAGE = [
  "Usage:",
  "  analyze-flight-recorder.mjs <session-dir | diagnostics-bundle | otlp.json | upload-dir> [--session-id <id>] [--build-sha <sha>] [--abnormal-only]",
  "  analyze-flight-recorder.mjs --url <http(s)://...> [--header-env <ENV_NAME>] [--session-id <id>] [--build-sha <sha>] [--abnormal-only]",
].join("\n");

export async function runCli(argv = process.argv.slice(2)) {
  let opts;
  try {
    opts = parseCliArgs(argv);
  } catch (err) {
    console.error(err instanceof Error ? err.message : String(err));
    console.error(USAGE);
    process.exit(1);
  }

  if (opts.help || (!opts.url && !opts.target)) {
    console.error(USAGE);
    process.exit(opts.help ? 0 : 1);
  }

  const hasFilters = Boolean(opts.sessionId || opts.buildSha || opts.abnormalOnly);
  const filters = {
    sessionId: opts.sessionId,
    buildSha: opts.buildSha,
    abnormalOnly: opts.abnormalOnly,
  };

  let rawResult;
  if (opts.url) {
    const parsedUrl = (() => {
      try {
        const u = new URL(opts.url);
        return `${u.origin}${u.pathname}`;
      } catch {
        return "remote-otlp";
      }
    })();
    const fetched = await fetchOtlpJsonFromUrl(opts.url, { headerEnv: opts.headerEnv });
    rawResult = parseOtlpJsonBundle(fetched, parsedUrl);
  } else {
    const target = opts.target;
    if (!fs.existsSync(target)) {
      console.error(`Path not found: ${target}`);
      console.error(USAGE);
      process.exit(1);
    }
    const stat = fs.statSync(target);
    if (stat.isFile()) {
      rawResult = analyzeDiagnosticBundleFile(target);
    } else if (fs.existsSync(path.join(target, "meta.json")) || fs.existsSync(path.join(target, "events.alerts.jsonl"))) {
      rawResult = analyzeSessionDir(target);
    } else {
      const entries = fs
        .readdirSync(target, { withFileTypes: true })
        .map((e) => ({ name: e.name, full: path.join(target, e.name), isDir: e.isDirectory(), mtime: fs.statSync(path.join(target, e.name)).mtimeMs }))
        .sort((a, b) => b.mtime - a.mtime);
      if (entries.length === 0) {
        rawResult = { schema: 2, readOnly: true, empty: true, abnormal: false };
      } else if (hasFilters) {
        const collected = [];
        for (const entry of entries) {
          const item = entry.isDir ? analyzeSessionDir(entry.full) : analyzeDiagnosticBundleFile(entry.full);
          if (Array.isArray(item)) collected.push(...item);
          else collected.push(item);
        }
        rawResult = collected;
      } else if (entries[0].isDir) {
        rawResult = analyzeSessionDir(entries[0].full);
      } else {
        rawResult = analyzeDiagnosticBundleFile(entries[0].full);
      }
    }
  }

  const output = hasFilters
    ? filterOtlpSummaries(rawResult, filters)
    : rawResult;

  console.log(JSON.stringify(output, null, 2));
  return output;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  runCli(process.argv.slice(2)).catch((err) => {
    console.error(err instanceof Error ? err.message : String(err));
    process.exit(1);
  });
}

#!/usr/bin/env node
/**
 * Read-Only KuCode Flight Recorder analyzer.
 *
 * Supports both:
 * 1. Raw SessionLogStore session directories (`meta.json`, `events.alerts.jsonl`, `metrics.jsonl`, `exit-trace.txt`, `vscode-sublogs.txt`)
 * 2. Exported KuCode AI Diagnostic Bundle Markdown (`# KuCode AI セッション診断バンドル (Flight Recorder)`),
 *    whether uploaded to `/home/antigravity/upload/` or pasted into a GitHub Issue body.
 *
 * Strips unbounded Section 4 raw timelines so the main agent receives only structured
 * sessionId-correlated triage metadata, alert counts, anomalous metrics, and crash traces.
 * Treats all diagnostic inputs strictly as untrusted data, never instructions.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

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
        .filter((l) => /(error|fatal|exception|eacces|enospc|crash|killed|sigsegv|sigkill)/i.test(l))
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
    .filter((l) => /(error|fatal|exception|eacces|enospc|crash|killed|sigsegv|sigkill)/i.test(l))
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

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const target = process.argv[2];
  if (!target || !fs.existsSync(target)) {
    console.error("Usage: analyze-flight-recorder.mjs <session-dir | diagnostics-bundle.md | upload-dir>");
    process.exit(1);
  }
  const stat = fs.statSync(target);
  let result;
  if (stat.isFile()) {
    result = analyzeDiagnosticBundleFile(target);
  } else if (fs.existsSync(path.join(target, "meta.json")) || fs.existsSync(path.join(target, "events.alerts.jsonl"))) {
    result = analyzeSessionDir(target);
  } else {
    const entries = fs
      .readdirSync(target, { withFileTypes: true })
      .map((e) => ({ name: e.name, full: path.join(target, e.name), isDir: e.isDirectory(), mtime: fs.statSync(path.join(target, e.name)).mtimeMs }))
      .sort((a, b) => b.mtime - a.mtime);
    if (entries.length === 0) {
      result = { schema: 2, readOnly: true, empty: true, abnormal: false };
    } else if (entries[0].isDir) {
      result = analyzeSessionDir(entries[0].full);
    } else {
      result = analyzeDiagnosticBundleFile(entries[0].full);
    }
  }
  console.log(JSON.stringify(result, null, 2));
}

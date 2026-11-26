#!/usr/bin/env node
/**
 * Read-Only KuCode Flight Recorder analyzer (SessionLogStore / diagnostics-import).
 *
 * Follows the "never feed raw logs to AI" and "correlate by sessionId" discipline:
 * 1. Reads `meta.json` (app version, buildSha, session status, OS exit info)
 * 2. Aggregates `events.alerts.jsonl` by level & source (counts + recent tail)
 * 3. Summarizes `metrics.jsonl` (max PSS, min freeDisk, thermal peaks, serverAlive drops)
 * 4. Extracts bounded top/tail snippets from `exit-trace.txt` & `vscode-sublogs.txt`
 *
 * Treats all diagnostic input strictly as untrusted data, never instructions.
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
    schema: 1,
    readOnly: true,
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

export function analyzeDiagnosticBundleFile(filePath) {
  const lines = fs.readFileSync(filePath, "utf8").split(/\r?\n/);
  const errorLines = lines
    .filter((l) => /(ENDED_ABNORMALLY|FATAL|ERROR|Exception|Caused by:|exit=|sigkill|sigsegv|OOM)/i.test(l))
    .slice(0, 35);
  return {
    schema: 1,
    readOnly: true,
    bundleFile: path.basename(filePath),
    totalLines: lines.length,
    abnormal: errorLines.length > 0,
    keyErrorLines: errorLines,
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const target = process.argv[2];
  if (!target || !fs.existsSync(target)) {
    console.error("Usage: analyze-flight-recorder.mjs <session-dir | diagnostics-import-dir | bundle-file>");
    process.exit(1);
  }
  const stat = fs.statSync(target);
  let result;
  if (stat.isFile()) {
    result = analyzeDiagnosticBundleFile(target);
  } else if (fs.existsSync(path.join(target, "meta.json")) || fs.existsSync(path.join(target, "events.alerts.jsonl"))) {
    result = analyzeSessionDir(target);
  } else {
    // Pick the newest session subdirectory or exported bundle file inside the directory.
    const entries = fs
      .readdirSync(target, { withFileTypes: true })
      .map((e) => ({ name: e.name, full: path.join(target, e.name), isDir: e.isDirectory(), mtime: fs.statSync(path.join(target, e.name)).mtimeMs }))
      .sort((a, b) => b.mtime - a.mtime);
    if (entries.length === 0) {
      result = { schema: 1, readOnly: true, empty: true, abnormal: false };
    } else if (entries[0].isDir) {
      result = analyzeSessionDir(entries[0].full);
    } else {
      result = analyzeDiagnosticBundleFile(entries[0].full);
    }
  }
  console.log(JSON.stringify(result, null, 2));
}

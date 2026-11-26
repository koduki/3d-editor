#!/usr/bin/env node
/**
 * LogSage-inspired CI failure log preprocessor for KuCode (ASE 2025 pattern).
 *
 * Strips timestamps, ANSI escapes, and download/progress noise from raw CI logs,
 * classifies the failure category, and emits a bounded structured JSON or Markdown
 * triage report so coding agents never consume raw multi-megabyte logs.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ANSI_RE = /\x1B\[[0-9;]*[A-Za-z]/g;
const GHA_TS_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+Z\s+/;
const NOISE_LINE_RE = /^(Download https?:\/\/|> Task :app:\S+\s+UP-TO-DATE|Calculating task graph|Configuration cache|^\s*\d+%\s+|^\s*#\s+duration_ms|^\s*ok\s+\d+\s+-)/i;

const ERROR_LINE_RE = /(GUARD FAILED|FAIL:|ERROR:|::error::|FAILURE:|Execution failed for task|AssertionError|AssertionFailedError|Exception:|Error:|not ok \d+|e:\s+file:\/\/|SyntaxError:|TypeError:|ReferenceError:|FAILED \(|Traceback \(most recent call last\)|cannot find symbol|Unresolved reference)/i;

const CATEGORIES = [
  { id: "guard_violation", re: /GUARD FAILED|\[no-lint-or-type-bypass\]|\[no-test-skip-or-ignore\]|\[workspace-trust-and-acp-scope\]|\[extension-module-boundary\]/i },
  { id: "apk_elf_alignment", re: /16KB LOAD alignment|LOAD offset=|libproot\.so|has no LOAD segments/i },
  { id: "runtime_asset_lock", re: /runtime-assets\.py|sha256|size mismatch|unknown lock entry/i },
  { id: "openvscode_smoke", re: /OpenVSCode Server smoke test failed|remoteagent\.log/i },
  { id: "gradle_compile", re: /compile\w*Kotlin|e:\s+file:\/\/|Unresolved reference|Compilation error/i },
  { id: "gradle_test", re: /testDebugUnitTest|testDebugMediumUnitTest|testReleaseUnitTest|AssertionError|AssertionFailedError/i },
  { id: "node_test", re: /not ok \d+|tests\/.*\.test\.cjs|node --check/i },
  { id: "python_test", re: /test_runtime_assets\.py|Traceback \(most recent call last\)/i },
];

export function compressCiLog(rawText, options = {}) {
  const { maxSnippets = 45, maxTailLines = 25, maxChars = 6000 } = options;
  const cleaned = rawText
    .replace(ANSI_RE, "")
    .split(/\r?\n/)
    .map((line) => line.replace(GHA_TS_RE, "").trimEnd())
    .filter((line) => line.length > 0 && !NOISE_LINE_RE.test(line));

  let category = "unknown";
  for (const c of CATEGORIES) {
    if (cleaned.some((line) => c.re.test(line))) {
      category = c.id;
      break;
    }
  }

  const matchedIndexes = new Set();
  for (let i = 0; i < cleaned.length; i++) {
    if (ERROR_LINE_RE.test(cleaned[i])) {
      for (let j = Math.max(0, i - 1); j <= Math.min(cleaned.length - 1, i + 2); j++) {
        matchedIndexes.add(j);
      }
    }
  }

  const keyLines = Array.from(matchedIndexes)
    .sort((a, b) => a - b)
    .map((idx) => cleaned[idx]);

  // Deduplicate adjacent identical lines and enforce strict token budget.
  const deduped = [];
  for (const line of keyLines) {
    if (deduped[deduped.length - 1] !== line) {
      deduped.push(line);
    }
    if (deduped.length >= maxSnippets) break;
  }

  const tailLines = cleaned.slice(-maxTailLines);

  // Extract candidate repository file paths mentioned in error lines.
  const fileSet = new Set();
  const pathRe = /\b((?:android|scripts|tests|docs|\.github|\.agents)\/[A-Za-z0-9_./-]+\.[A-Za-z0-9]+)(?::(\d+))?/g;
  for (const line of [...deduped, ...tailLines]) {
    let m;
    while ((m = pathRe.exec(line)) !== null) {
      fileSet.add(m[2] ? `${m[1]}:${m[2]}` : m[1]);
    }
  }

  let report = {
    schema: 1,
    category,
    rawLineCount: rawText.split(/\r?\n/).length,
    filteredLineCount: cleaned.length,
    suspectedFiles: Array.from(fileSet).slice(0, 15),
    keyErrorLines: deduped,
    tailLines,
  };

  // Hard cap serialized size.
  while (JSON.stringify(report).length > maxChars && report.keyErrorLines.length > 5) {
    report.keyErrorLines.pop();
  }
  while (JSON.stringify(report).length > maxChars && report.tailLines.length > 5) {
    report.tailLines.shift();
  }

  return report;
}

export function formatMarkdown(report) {
  const lines = [
    "### CI Failure Triage (Compressed / LogSage-style)",
    "",
    `- **Category:** \`${report.category}\``,
    `- **Log compression:** ${report.rawLineCount} raw lines -> ${report.keyErrorLines.length} key error lines`,
    `- **Suspected files:** ${report.suspectedFiles.length ? report.suspectedFiles.map((f) => `\`${f}\``).join(", ") : "_none detected_"}`,
    "",
    "#### Key Error Lines",
    "```text",
    ...(report.keyErrorLines.length ? report.keyErrorLines : ["(no explicit error keyword matched; see tail below)"]),
    "```",
    "",
    "#### Log Tail",
    "```text",
    ...report.tailLines,
    "```",
  ];
  return lines.join("\n");
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const asJson = args.includes("--json");
  const fileArg = args.find((a) => !a.startsWith("-"));
  const raw = fileArg ? fs.readFileSync(fileArg, "utf8") : fs.readFileSync(0, "utf8");
  const report = compressCiLog(raw);
  console.log(asJson ? JSON.stringify(report, null, 2) : formatMarkdown(report));
}

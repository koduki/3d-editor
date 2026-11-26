#!/usr/bin/env node
/**
 * Discovers and runs spec-derived clean-room oracle test suites under `tests/oracle/`
 * (Node `*.oracle.test.cjs` and Python `test_*_oracle.py`).
 *
 * Options:
 *   --require-non-empty : fail if zero oracle test files exist (used after S3a).
 *   --json              : print machine-readable summary.
 */
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

export function discoverOracleTests(repoRoot) {
  const oracleDir = path.join(repoRoot, "tests", "oracle");
  const nodeFiles = [];
  const pythonFiles = [];
  const kotlinFiles = [];

  if (fs.existsSync(oracleDir)) {
    for (const entry of fs.readdirSync(oracleDir, { withFileTypes: true })) {
      if (!entry.isFile()) continue;
      const rel = path.join("tests", "oracle", entry.name).replace(/\\/g, "/");
      if (entry.name.endsWith(".oracle.test.cjs")) nodeFiles.push(rel);
      if (/^test_.*_oracle\.py$/.test(entry.name)) pythonFiles.push(rel);
    }
  }

  const kotlinOracleDir = path.join(repoRoot, "android", "app", "src", "test", "java", "dev", "koduki", "kucode", "oracle");
  if (fs.existsSync(kotlinOracleDir)) {
    for (const entry of fs.readdirSync(kotlinOracleDir, { withFileTypes: true })) {
      if (entry.isFile() && entry.name.endsWith("OracleTest.kt")) {
        kotlinFiles.push(path.relative(repoRoot, path.join(kotlinOracleDir, entry.name)).replace(/\\/g, "/"));
      }
    }
  }

  return {
    nodeFiles: nodeFiles.sort(),
    pythonFiles: pythonFiles.sort(),
    kotlinFiles: kotlinFiles.sort(),
    total: nodeFiles.length + pythonFiles.length + kotlinFiles.length,
  };
}

export function runOracleSuites(repoRoot, options = {}) {
  const { requireNonEmpty = false } = options;
  const discovered = discoverOracleTests(repoRoot);
  const runs = [];
  let passed = true;

  if (requireNonEmpty && discovered.total === 0) {
    return {
      passed: false,
      discovered,
      runs,
      error: "No oracle test files found under tests/oracle/ or android/.../oracle/ despite --require-non-empty.",
    };
  }

  if (discovered.nodeFiles.length > 0) {
    const res = spawnSync(process.execPath, ["--test", ...discovered.nodeFiles], {
      cwd: repoRoot,
      encoding: "utf8",
    });
    runs.push({
      runner: "node",
      files: discovered.nodeFiles,
      exitCode: res.status ?? 1,
      output: `${res.stdout || ""}\n${res.stderr || ""}`.trim().slice(-2000),
    });
    if (res.status !== 0) passed = false;
  }

  if (discovered.pythonFiles.length > 0) {
    const res = spawnSync("python3", ["-m", "unittest", "discover", "-s", "tests/oracle", "-p", "test_*_oracle.py", "-v"], {
      cwd: repoRoot,
      encoding: "utf8",
    });
    runs.push({
      runner: "python",
      files: discovered.pythonFiles,
      exitCode: res.status ?? 1,
      output: `${res.stdout || ""}\n${res.stderr || ""}`.trim().slice(-2000),
    });
    if (res.status !== 0) passed = false;
  }

  return {
    passed,
    discovered,
    kotlinNote: discovered.kotlinFiles.length > 0 ? "Kotlin oracle tests execute via Gradle :app:testDebugUnitTest / :app:testDebugMediumUnitTest." : null,
    runs,
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = new Set(process.argv.slice(2));
  const result = runOracleSuites(process.cwd(), {
    requireNonEmpty: args.has("--require-non-empty"),
  });

  if (args.has("--json")) {
    console.log(JSON.stringify(result, null, 2));
  } else if (result.passed) {
    console.log(
      `OK: Oracle test runner passed (node=${result.discovered.nodeFiles.length}, python=${result.discovered.pythonFiles.length}, kotlin=${result.discovered.kotlinFiles.length}).`,
    );
  } else {
    console.error("ORACLE TESTS FAILED:");
    if (result.error) console.error(result.error);
    for (const r of result.runs) {
      if (r.exitCode !== 0) {
        console.error(`[${r.runner}] exit=${r.exitCode}\n${r.output}`);
      }
    }
  }
  process.exit(result.passed ? 0 : 1);
}

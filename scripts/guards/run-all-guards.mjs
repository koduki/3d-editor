#!/usr/bin/env node
/**
 * KuCode non-ignorable deterministic guards (pre-commit, PostToolUse hook, and CI).
 *
 * Design principles:
 * - Zero external dependencies (runs on standard Node 22 / bundled OpenVSCode Node).
 * - Cannot be bypassed with inline ignore comments.
 * - Every violation includes actionable remediation guidance for coding agents.
 */
import fs from "node:fs";
import path from "node:path";
import { execSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const SELF_GUARD_FILES = new Set([
  "scripts/guards/run-all-guards.mjs",
  "scripts/guards/ci-log-compressor.mjs",
  "tests/agentic-harness.test.cjs",
]);

// Existing audited Kotlin suppressions (new additions are blocked by default).
const ALLOWED_KOTLIN_SUPPRESSIONS = new Map([
  ["android/app/src/main/java/dev/koduki/kucode/MainActivity.kt", new Set([
    '@SuppressLint("SetJavaScriptEnabled")',
    '@SuppressLint("BatteryLife")',
  ])],
  ["android/app/src/main/java/dev/koduki/kucode/SessionTelemetryManager.kt", new Set([
    '@Suppress("DEPRECATION")',
  ])],
]);

const SECRET_SCAN_EXCLUDED = new Set([
  "android/app/src/main/java/dev/koduki/kucode/LogRedactor.kt",
  "android/app/src/test/java/dev/koduki/kucode/LogRedactorTest.kt",
  "android/app/src/test/java/dev/koduki/kucode/SessionLogStoreTest.kt",
  "scripts/guards/run-all-guards.mjs",
  "tests/agentic-harness.test.cjs",
]);

function normalizeRel(filePath) {
  return filePath.replace(/\\/g, "/").replace(/^\.\//, "");
}

function listTrackedFiles(repoRoot, changedOnly) {
  const execOpts = { cwd: repoRoot, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] };
  if (changedOnly) {
    try {
      const out = execSync("git status --porcelain", execOpts);
      const files = out
        .split("\n")
        .filter(Boolean)
        .map((line) => line.slice(3).trim())
        .map((entry) => (entry.includes(" -> ") ? entry.split(" -> ").pop() : entry))
        .map(normalizeRel)
        .filter((rel) => fs.existsSync(path.join(repoRoot, rel)) && fs.statSync(path.join(repoRoot, rel)).isFile());
      return Array.from(new Set(files));
    } catch {
      // Fallback to directory walk below.
    }
  }

  try {
    const out = execSync("git ls-files --cached --others --exclude-standard", execOpts);
    return out
      .split("\n")
      .map((s) => normalizeRel(s.trim()))
      .filter((rel) => rel && fs.existsSync(path.join(repoRoot, rel)) && fs.statSync(path.join(repoRoot, rel)).isFile());
  } catch {
    const result = [];
    function walk(dir) {
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        if (entry.name === ".git" || entry.name === "node_modules" || entry.name === ".gradle" || entry.name === ".ship" || entry.name === "build") {
          continue;
        }
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
          walk(full);
        } else if (entry.isFile()) {
          result.push(normalizeRel(path.relative(repoRoot, full)));
        }
      }
    }
    walk(repoRoot);
    return result;
  }
}

function isTextCodeFile(rel) {
  return /\.(kt|kts|js|cjs|mjs|ts|tsx|py|sh|yml|yaml|json|xml|html|pro)$/.test(rel);
}

export function runGuards(repoRoot, options = {}) {
  const { changedOnly = false } = options;
  const files = listTrackedFiles(repoRoot, changedOnly);
  const violations = [];

  const addViolation = (ruleId, file, line, detail, remediation) => {
    violations.push({ ruleId, file, line, detail, remediation });
  };

  // --- Per-file static scans ---
  for (const rel of files) {
    // Guard 8a: forbid committing keystore binaries
    if (/\.(keystore|jks|p12)$/i.test(rel)) {
      addViolation(
        "no-secret-or-keystore-commit",
        rel,
        1,
        "Keystore or signing binary found in repository",
        "Remove the signing key from the repository immediately and pass it via ANDROID_KEYSTORE_PATH / GitHub Secrets.",
      );
      continue;
    }

    if (!isTextCodeFile(rel)) continue;
    const abs = path.join(repoRoot, rel);
    const content = fs.readFileSync(abs, "utf8");
    const lines = content.split("\n");

    const skipSelf = SELF_GUARD_FILES.has(rel);

    lines.forEach((lineText, idx) => {
      const lineNo = idx + 1;

      // Guard 1: forbid lint/type suppression directives
      if (!skipSelf && !rel.startsWith("docs/") && !rel.startsWith(".agents/")) {
        const bypassMatch = lineText.match(/eslint-disable|@ts-ignore|@ts-nocheck|@ts-expect-error|#\s*noqa\b/);
        if (bypassMatch) {
          addViolation(
            "no-lint-or-type-bypass",
            rel,
            lineNo,
            `Suppression directive '${bypassMatch[0]}' is forbidden`,
            "Fix the underlying type or lint issue properly instead of suppressing checks. If a contract needs revision, escalate to the human.",
          );
        }
      }

      // Guard 2: forbid unconditional test skips / @Ignore
      if (!skipSelf && (rel.startsWith("tests/") || rel.includes("/src/test/"))) {
        const skipMatch = lineText.match(/@Ignore\b|\b(?:test|it|describe)\.skip\s*\(|@unittest\.skip\b/);
        if (skipMatch) {
          addViolation(
            "no-test-skip-or-ignore",
            rel,
            lineNo,
            `Unconditional test skip '${skipMatch[0]}' is forbidden`,
            "Do not skip or weaken tests to pass CI. Fix the implementation or reproduce the real defect.",
          );
        }
      }

      // Guard 3: forbid new @Suppress / @SuppressLint in Kotlin
      if (rel.endsWith(".kt")) {
        const supMatch = lineText.match(/@Suppress(?:Lint)?\([^)]*\)/);
        if (supMatch) {
          const allowed = ALLOWED_KOTLIN_SUPPRESSIONS.get(rel);
          if (!allowed || !allowed.has(supMatch[0])) {
            addViolation(
              "no-new-kotlin-suppress",
              rel,
              lineNo,
              `Unapproved Kotlin suppression '${supMatch[0]}'`,
              "Resolve the compiler/lint warning cleanly instead of adding @Suppress/@SuppressLint. If an Android API fallback truly requires it, ask the human to audit and allowlist it.",
            );
          }
        }
      }

      // Guard 4: forbid reintroducing --disable-workspace-trust (PR #33 hardening)
      if (!skipSelf && !rel.startsWith("docs/") && !rel.startsWith(".agents/") && rel !== "AGENTS.md") {
        if (lineText.includes("--disable-workspace-trust")) {
          addViolation(
            "no-workspace-trust-disable",
            rel,
            lineNo,
            "Forbidden flag '--disable-workspace-trust' detected",
            "Keep VS Code Workspace Trust enabled (`security.workspace.trust.enabled: true`) per ADR-0012 and PR #33.",
          );
        }
      }

      // Guard 6: enforce module boundary between kucode-android and kucode-tools
      if (rel.startsWith("android/app/src/main/assets/kucode-android/") && /kucode-tools/.test(lineText)) {
        addViolation(
          "extension-module-boundary",
          rel,
          lineNo,
          "kucode-android references kucode-tools directly",
          "Keep kucode-android decoupled from kucode-tools internals (ADR-0012 §5). Communicate only via the versioned Host Bridge or kucode CLI contract.",
        );
      }
      if (rel.startsWith("android/app/src/main/assets/kucode-tools/") && /kucode-android/.test(lineText)) {
        addViolation(
          "extension-module-boundary",
          rel,
          lineNo,
          "kucode-tools references kucode-android directly",
          "Keep kucode-tools generic and decoupled from kucode-android internals (ADR-0012 §5).",
        );
      }

      // Guard 8b: forbid accidental secret / private key literals
      if (!SECRET_SCAN_EXCLUDED.has(rel) && !rel.startsWith("docs/") && !rel.startsWith(".agents/")) {
        const secretPatterns = [
          /-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----/,
          /\bgh[pousr]_[A-Za-z0-9_]{20,}\b/,
          /\bgithub_pat_[A-Za-z0-9_]{20,}\b/,
          /\bsk-(?:proj-|ant-)[A-Za-z0-9_-]{20,}\b/,
          /\bAIza[0-9A-Za-z_-]{35}\b/,
          /\b(?:AKIA|ASIA)[0-9A-Z]{16}\b/,
        ];
        for (const pat of secretPatterns) {
          if (pat.test(lineText)) {
            addViolation(
              "no-secret-or-keystore-commit",
              rel,
              lineNo,
              "Potential hardcoded secret or private key detected",
              "Remove the secret immediately and load credentials from environment variables or GitHub Secrets.",
            );
            break;
          }
        }
      }
    });
  }

  // --- Structural repository-wide invariants (always checked unless changedOnly excludes relevant files) ---
  const shouldCheck = (targetRel) => !changedOnly || files.includes(targetRel);

  // Guard 5: Workspace Trust & application-scoped ACP configuration
  const toolsPkgRel = "android/app/src/main/assets/kucode-tools/package.json";
  const toolsPkgAbs = path.join(repoRoot, toolsPkgRel);
  if (fs.existsSync(toolsPkgAbs) && shouldCheck(toolsPkgRel)) {
    try {
      const pkg = JSON.parse(fs.readFileSync(toolsPkgAbs, "utf8"));
      if (pkg?.capabilities?.untrustedWorkspaces?.supported !== "limited") {
        addViolation(
          "workspace-trust-and-acp-scope",
          toolsPkgRel,
          1,
          'kucode-tools must declare capabilities.untrustedWorkspaces.supported = "limited"',
          'Set capabilities.untrustedWorkspaces.supported to "limited" so clipboard/themes/diagnostics work safely while ACP stays gated.',
        );
      }
      const props = pkg?.contributes?.configuration?.properties ?? {};
      for (const key of ["kucode.acp.defaultAgent", "kucode.acp.permissionMode", "kucode.acp.agents"]) {
        const prop = props[key];
        if (!prop || prop.scope !== "application" || prop.restricted !== true) {
          addViolation(
            "workspace-trust-and-acp-scope",
            toolsPkgRel,
            1,
            `Setting '${key}' must enforce scope="application" and restricted=true`,
            `Restore "scope": "application" and "restricted": true on '${key}' to prevent untrusted workspace command injection.`,
          );
        }
      }
    } catch (err) {
      addViolation("workspace-trust-and-acp-scope", toolsPkgRel, 1, `Invalid JSON: ${err.message}`, "Fix JSON syntax in kucode-tools/package.json.");
    }
  }

  const androidPkgRel = "android/app/src/main/assets/kucode-android/package.json";
  const androidPkgAbs = path.join(repoRoot, androidPkgRel);
  if (fs.existsSync(androidPkgAbs) && shouldCheck(androidPkgRel)) {
    try {
      const pkg = JSON.parse(fs.readFileSync(androidPkgAbs, "utf8"));
      if (pkg?.capabilities?.untrustedWorkspaces?.supported !== false) {
        addViolation(
          "workspace-trust-and-acp-scope",
          androidPkgRel,
          1,
          "kucode-android must declare capabilities.untrustedWorkspaces.supported = false",
          "Set capabilities.untrustedWorkspaces.supported to false so Gradle/build tasks cannot run in untrusted workspaces.",
        );
      }
    } catch (err) {
      addViolation("workspace-trust-and-acp-scope", androidPkgRel, 1, `Invalid JSON: ${err.message}`, "Fix JSON syntax in kucode-android/package.json.");
    }
  }

  // Guard 7: Android build invariants (targetSdk=35, arm64-v8a, useLegacyPackaging, noCompress tgz)
  const gradleRel = "android/app/build.gradle.kts";
  const gradleAbs = path.join(repoRoot, gradleRel);
  if (fs.existsSync(gradleAbs) && shouldCheck(gradleRel)) {
    const gradleText = fs.readFileSync(gradleAbs, "utf8");
    const requiredChecks = [
      { re: /\btargetSdk\s*=\s*35\b/, msg: "targetSdk must remain 35 (ADR-0002 W^X / 16KB compliance)" },
      { re: /"arm64-v8a"/, msg: 'abiFilters must include "arm64-v8a" (ADR-0012 ARM64-only target)' },
      { re: /useLegacyPackaging\s*=\s*true/, msg: "jniLibs.useLegacyPackaging must remain true so PRoot extracts with +x" },
      { re: /noCompress\s*\+=\s*listOf\([^)]*"tgz"/, msg: 'androidResources.noCompress must include "tgz" for embedded rootfs/OpenVSCode archives' },
    ];
    for (const check of requiredChecks) {
      if (!check.re.test(gradleText)) {
        addViolation(
          "android-build-invariants",
          gradleRel,
          1,
          check.msg,
          "Restore the required Android XR / PRoot packaging invariant in android/app/build.gradle.kts (see ADR-0002 and ADR-0012).",
        );
      }
    }
  }

  // Guard 9: Runtime asset lock & OSS license presence
  const lockRel = "runtime-assets.lock.json";
  const lockAbs = path.join(repoRoot, lockRel);
  if (fs.existsSync(lockAbs) && shouldCheck(lockRel)) {
    try {
      const lock = JSON.parse(fs.readFileSync(lockAbs, "utf8"));
      const requiredAssets = [
        "libproot.so",
        "libproot-loader.so",
        "debian-arm64-rootfs.tgz",
        "openvscode-server-linux-arm64.tgz",
        "debian-manifest.txt",
      ];
      if (lock.abi !== "arm64-v8a") {
        addViolation("runtime-lock-and-license-presence", lockRel, 1, `Unexpected lock abi: ${lock.abi}`, 'Keep runtime-assets.lock.json abi set to "arm64-v8a".');
      }
      for (const asset of requiredAssets) {
        const entry = lock?.assets?.[asset];
        if (!entry || !/^[0-9a-f]{64}$/.test(entry.sha256 || "") || !(entry.size > 0)) {
          addViolation(
            "runtime-lock-and-license-presence",
            lockRel,
            1,
            `Missing or malformed lock entry for '${asset}'`,
            "Regenerate runtime-assets.lock.json via scripts/runtime-assets.py lock with valid SHA-256 and byte sizes.",
          );
        }
      }
    } catch (err) {
      addViolation("runtime-lock-and-license-presence", lockRel, 1, `Invalid JSON: ${err.message}`, "Fix JSON syntax in runtime-assets.lock.json.");
    }
  }

  if (!changedOnly) {
    for (const reqFile of ["LICENSE", "THIRD_PARTY_NOTICES.md", "LICENSES/MIT-OpenVSCode.txt"]) {
      if (!fs.existsSync(path.join(repoRoot, reqFile))) {
        addViolation(
          "runtime-lock-and-license-presence",
          reqFile,
          1,
          `Required OSS compliance file missing: ${reqFile}`,
          "Restore required OSS license and notice files per ADR-0008.",
        );
      }
    }
  }

  return {
    passed: violations.length === 0,
    checkedFiles: files.length,
    changedOnly,
    violationCount: violations.length,
    violations,
  };
}

// CLI entrypoint
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = new Set(process.argv.slice(2));
  const changedOnly = args.has("--changed-only");
  const asJson = args.has("--json");
  const repoRoot = process.cwd();
  const report = runGuards(repoRoot, { changedOnly });

  if (asJson) {
    console.log(JSON.stringify(report, null, 2));
  } else if (report.passed) {
    console.log(`OK: KuCode non-ignorable guards passed (${report.checkedFiles} files checked${changedOnly ? ", changed-only" : ""}).`);
  } else {
    console.error(`GUARD FAILED (${report.violationCount} violation(s)):`);
    for (const v of report.violations) {
      console.error(`- ${v.file}:${v.line} [${v.ruleId}] ${v.detail}`);
      console.error(`  Remediation: ${v.remediation}`);
    }
  }
  process.exit(report.passed ? 0 : 1);
}

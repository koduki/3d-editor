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

// Guard 11: Verified Antigravity custom subagent frontmatter allowlists (prevents unmapped-tool hangs).
const ALLOWED_SUBAGENT_TOOLS = new Set([
  "view_file",
  "write_to_file",
  "replace_file_content",
  "multi_replace_file_content",
  "list_dir",
  "find_by_name",
  "grep_search",
  "run_command",
  "manage_task",
  "send_message",
]);

const SUBAGENT_WRITE_TOOLS = new Set([
  "write_to_file",
  "replace_file_content",
  "multi_replace_file_content",
]);

const READ_ONLY_SUBAGENTS = new Set([
  "ship-recon",
  "ship-architect",
  "ship-verifier",
  "ship-reviewer",
]);

const ALLOWED_SUBAGENT_MODELS = new Set(["inherit", "flash", "pro"]);
const ALLOWED_COMMAND_POLICIES = new Set(["off", "auto", "eager", "sandbox"]);

function stripYamlQuotes(raw) {
  const s = String(raw || "").trim();
  if ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith("'") && s.endsWith("'"))) {
    return s.slice(1, -1).trim();
  }
  return s;
}

function parseAgentFrontmatter(content) {
  const lines = content.replace(/\r\n/g, "\n").split("\n");
  if (lines[0]?.trim() !== "---") {
    return { validDelimiters: false, fields: {}, tools: [] };
  }
  let endIdx = -1;
  for (let i = 1; i < lines.length; i++) {
    if (lines[i].trim() === "---") {
      endIdx = i;
      break;
    }
  }
  if (endIdx === -1) {
    return { validDelimiters: false, fields: {}, tools: [] };
  }

  const fields = {};
  const tools = [];
  let currentListKey = null;

  for (let i = 1; i < endIdx; i++) {
    const rawLine = lines[i];
    const trimmed = rawLine.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;

    const listMatch = rawLine.match(/^\s*-\s+(.+)$/);
    if (listMatch && currentListKey === "tools") {
      const val = stripYamlQuotes(listMatch[1]);
      if (val) tools.push({ value: val, line: i + 1 });
      continue;
    }

    const kvMatch = rawLine.match(/^([A-Za-z_][A-Za-z0-9_-]*):\s*(.*)$/);
    if (kvMatch) {
      const key = kvMatch[1];
      const rawVal = kvMatch[2].trim();
      if (key === "tools") {
        if (rawVal.startsWith("[") && rawVal.endsWith("]")) {
          const inner = rawVal.slice(1, -1).trim();
          if (inner) {
            for (const part of inner.split(",")) {
              const val = stripYamlQuotes(part);
              if (val) tools.push({ value: val, line: i + 1 });
            }
          }
          currentListKey = null;
        } else if (rawVal === "") {
          currentListKey = "tools";
        } else {
          tools.push({ value: stripYamlQuotes(rawVal), line: i + 1 });
          currentListKey = null;
        }
      } else {
        currentListKey = null;
        fields[key] = { value: stripYamlQuotes(rawVal), line: i + 1 };
      }
    }
  }

  return { validDelimiters: true, fields, tools };
}

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

    // Guard 11: validate custom subagent YAML frontmatter, official tool allowlists, model tiers, and read-only invariants
    if (/(^|\/)\.agents\/agents\/[^/]+\.md$/.test(rel)) {
      const abs = path.join(repoRoot, rel);
      const content = fs.readFileSync(abs, "utf8");
      const parsed = parseAgentFrontmatter(content);
      const expectedName = path.basename(rel, ".md");
      if (!parsed.validDelimiters) {
        addViolation(
          "custom-subagent-frontmatter",
          rel,
          1,
          "Custom subagent file is missing valid YAML frontmatter delimited by '---'",
          "Add valid YAML frontmatter (name, description, tools, mainAgent, subagent, model, commandExecutionPolicy) at the top of the file.",
        );
        continue;
      }

      const { fields, tools } = parsed;
      const agentName = fields.name?.value || "";
      if (!agentName || !/^[a-z0-9][a-z0-9_.-]*$/.test(agentName)) {
        addViolation(
          "custom-subagent-frontmatter",
          rel,
          fields.name?.line || 1,
          `Invalid or missing subagent 'name': '${agentName}'`,
          `Set 'name: ${expectedName}' matching [a-z0-9][a-z0-9_.-]*.`,
        );
      } else if (agentName !== expectedName) {
        addViolation(
          "custom-subagent-frontmatter",
          rel,
          fields.name?.line || 1,
          `Subagent name '${agentName}' does not match filename '${expectedName}.md'`,
          `Rename the file or set 'name: ${expectedName}' so Antigravity subagent lookup stays unambiguous.`,
        );
      }

      if (!fields.description?.value) {
        addViolation(
          "custom-subagent-frontmatter",
          rel,
          fields.description?.line || 1,
          "Custom subagent is missing a non-empty 'description' field",
          "Provide a clear description explaining when the orchestrator should invoke this subagent.",
        );
      }

      if (fields.subagent?.value !== "true") {
        addViolation(
          "custom-subagent-frontmatter",
          rel,
          fields.subagent?.line || 1,
          `Custom subagent must set 'subagent: true' (got '${fields.subagent?.value || ""}')`,
          "Set 'subagent: true' in frontmatter so the agent can be invoked via invoke_subagent.",
        );
      }

      const modelVal = fields.model?.value || "";
      if (!ALLOWED_SUBAGENT_MODELS.has(modelVal)) {
        addViolation(
          "custom-subagent-frontmatter",
          rel,
          fields.model?.line || 1,
          `Invalid custom subagent model tier '${modelVal}'`,
          "Set 'model' to one of the supported Antigravity tiers: 'inherit', 'flash', or 'pro'.",
        );
      }

      const policyVal = fields.commandExecutionPolicy?.value || "";
      if (!ALLOWED_COMMAND_POLICIES.has(policyVal)) {
        addViolation(
          "custom-subagent-frontmatter",
          rel,
          fields.commandExecutionPolicy?.line || 1,
          `Invalid or missing commandExecutionPolicy '${policyVal}'`,
          "Set 'commandExecutionPolicy' to one of: 'off', 'auto', 'eager', or 'sandbox'.",
        );
      }

      if (tools.length === 0) {
        addViolation(
          "custom-subagent-frontmatter",
          rel,
          1,
          "Custom subagent must declare an explicit non-empty 'tools' allowlist",
          "Declare only the minimal verified tools needed (e.g., view_file, run_command, send_message).",
        );
      } else {
        const isReadOnlyAgent = READ_ONLY_SUBAGENTS.has(agentName) || READ_ONLY_SUBAGENTS.has(expectedName);
        for (const toolEntry of tools) {
          if (!ALLOWED_SUBAGENT_TOOLS.has(toolEntry.value)) {
            addViolation(
              "custom-subagent-frontmatter",
              rel,
              toolEntry.line,
              `Unmapped or unsupported tool name '${toolEntry.value}' in custom subagent tools list`,
              `Replace '${toolEntry.value}' with a verified Antigravity core tool (${Array.from(ALLOWED_SUBAGENT_TOOLS).join(", ")}) to avoid subagent tool-resolution hangs.`,
            );
          }
          if (isReadOnlyAgent && SUBAGENT_WRITE_TOOLS.has(toolEntry.value)) {
            addViolation(
              "custom-subagent-frontmatter",
              rel,
              toolEntry.line,
              `Read-only subagent '${expectedName}' must not be granted write tool '${toolEntry.value}'`,
              "Remove all file-write tools from read-only subagents (ship-recon, ship-architect, ship-verifier, ship-reviewer).",
            );
          }
        }
      }
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

    // Guard 10: clean-room oracle test integrity (must reference acceptance criteria AC-* and contain real assertions)
    const isOracleFile =
      /(^|\/)tests\/oracle\/.+\.(?:oracle\.test\.cjs|py)$/.test(rel) ||
      /OracleTest\.kt$/.test(rel);
    if (isOracleFile) {
      if (!/\bAC-\d+\b/.test(content)) {
        addViolation(
          "oracle-test-integrity",
          rel,
          1,
          "Oracle test file does not reference any acceptance criterion ID (e.g., AC-1)",
          "Map each oracle test case explicitly to a spec acceptance criterion ID (AC-1, AC-2, ...) from docs/specs/<slug>/acceptance.json.",
        );
      }
      if (!/\b(assert|assertEquals|assertTrue|assertFalse|assertNotNull|assertThrows|self\.assert)\b/.test(content)) {
        addViolation(
          "oracle-test-integrity",
          rel,
          1,
          "Oracle test file contains no assertions",
          "Add behavioral contract assertions derived strictly from the specification so the oracle cannot trivially pass.",
        );
      }
    }
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

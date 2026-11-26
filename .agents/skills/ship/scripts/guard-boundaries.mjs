#!/usr/bin/env node
/**
 * Antigravity PreToolUse hook: enforces KuCode immutable harness/supply-chain/legal
 * boundaries at all times, and automatically locks contract files once /ship S2 is approved.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { loadState, resolveStatePath } from "./state.mjs";

const ALWAYS_LOCKED_PATTERNS = [
  /(^|\/)\.github\/workflows\//,
  /(^|\/)scripts\/guards\//,
  /(^|\/)\.agents\/hooks\.json$/,
  /(^|\/)runtime-assets\.lock\.json$/,
  /(^|\/)scripts\/build-proot-android\.sh$/,
  /(^|\/)scripts\/fetch-runtime-assets\.sh$/,
  /(^|\/)(LICENSE|THIRD_PARTY_NOTICES\.md)$/,
  /(^|\/)LICENSES\//,
  /(^|\/)android\/gradle\/wrapper\/gradle-wrapper\.properties$/,
  /(^|\/)android\/app\/proguard-rules\.pro$/,
  /(^|\/)android\/app\/src\/main\/res\/xml\/network_security_config\.xml$/,
  /(^|\/)\.env(\.|$)/,
  /\.(keystore|jks|p12)$/i,
];

const CONTRACT_LOCKED_PATTERNS = [
  /(^|\/)spec\.md$/,
  /(^|\/)docs\/adr\//,
  /(^|\/)docs\/specs\/[^/]+\/spec\.md$/,
];

export function evaluateBoundaryWrite(targetPath, options = {}) {
  const normalized = String(targetPath || "").replace(/\\/g, "/");
  const { contractLocked = false, overrideEnv = process.env.KUCODE_ALLOW_BOUNDARY_EDIT === "1" } = options;

  if (!normalized || overrideEnv) {
    return { decision: "allow" };
  }

  if (ALWAYS_LOCKED_PATTERNS.some((re) => re.test(normalized))) {
    return {
      decision: "deny",
      reason: `Immutable KuCode boundary cannot be modified by an agent: ${normalized}. Escalate harness, CI, lock, or license edits to the human.`,
    };
  }

  if (contractLocked && CONTRACT_LOCKED_PATTERNS.some((re) => re.test(normalized))) {
    return {
      decision: "deny",
      reason: `Contract is locked after /ship S2 approval: ${normalized}. Escalate any contract revision to the human before editing.`,
    };
  }

  return { decision: "allow" };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const raw = fs.readFileSync(0, "utf8").trim();
  const payload = raw ? JSON.parse(raw) : {};
  const target = payload?.toolCall?.args?.TargetFile ?? "";
  const cwd = payload?.workspacePaths?.[0] || process.cwd();
  const state = loadState(resolveStatePath(cwd));
  const result = evaluateBoundaryWrite(target, {
    contractLocked: Boolean(state?.contractLocked),
  });
  console.log(JSON.stringify(result));
}

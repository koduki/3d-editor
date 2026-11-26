#!/usr/bin/env node
/**
 * Generic Antigravity PreToolUse boundary lock hook.
 * Loads boundary regexes from `.agents/ship.config.json` (with safe fallbacks):
 * - `neverUnlockablePatterns` (.env, .keystore, etc.): ALWAYS denied, never unlockable.
 * - `protectedPatterns` (CI, guards, hooks, locks, licenses): denied by default,
 *   unlockable ONLY for exact paths explicitly approved in S2 (`state.allowedProtectedPaths`).
 * - `contractPatterns` (spec.md, docs/adr, docs/specs/<slug>/spec.md): locked automatically
 *   after `state.contractLocked === true` unless explicitly in `allowedProtectedPaths`.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { loadShipConfig, loadState, resolveStatePath } from "./state.mjs";

const DEFAULT_NEVER_UNLOCKABLE = [
  /(^|\/)\.env(\.|$)/,
  /\.(keystore|jks|p12)$/i,
];

const DEFAULT_PROTECTED = [
  /(^|\/)\.github\/workflows\//,
  /(^|\/)scripts\/guards\//,
  /(^|\/)\.agents\/hooks\.json$/,
  /(^|\/)\.agents\/ship\.config\.json$/,
  /(^|\/)runtime-assets\.lock\.json$/,
  /(^|\/)scripts\/build-proot-android\.sh$/,
  /(^|\/)scripts\/fetch-runtime-assets\.sh$/,
  /(^|\/)(LICENSE|THIRD_PARTY_NOTICES\.md)$/,
  /(^|\/)LICENSES\//,
  /(^|\/)android\/gradle\/wrapper\/gradle-wrapper\.properties$/,
  /(^|\/)android\/app\/proguard-rules\.pro$/,
  /(^|\/)android\/app\/src\/main\/res\/xml\/network_security_config\.xml$/,
];

const DEFAULT_CONTRACT = [
  /(^|\/)spec\.md$/,
  /(^|\/)docs\/adr\//,
  /(^|\/)docs\/specs\/[^/]+\/spec\.md$/,
];

function compilePatterns(list, fallback) {
  if (!Array.isArray(list) || list.length === 0) return fallback;
  return list.map((p) => new RegExp(p));
}

function matchesApprovedPath(normalizedTarget, allowedPaths = []) {
  for (const allowed of allowedPaths) {
    const clean = String(allowed || "").replace(/\\/g, "/").replace(/^\.\//, "").replace(/^\/+/, "");
    if (!clean) continue;
    if (normalizedTarget === clean || normalizedTarget.endsWith(`/${clean}`)) {
      return true;
    }
  }
  return false;
}

export function evaluateBoundaryWrite(targetPath, options = {}) {
  const normalized = String(targetPath || "").replace(/\\/g, "/");
  const {
    contractLocked = false,
    allowedProtectedPaths = [],
    config = {},
    overrideEnv = process.env.KUCODE_ALLOW_BOUNDARY_EDIT === "1",
  } = options;

  if (!normalized) {
    return { decision: "allow" };
  }

  const neverUnlockable = compilePatterns(config?.boundaries?.neverUnlockablePatterns, DEFAULT_NEVER_UNLOCKABLE);
  if (neverUnlockable.some((re) => re.test(normalized))) {
    return {
      decision: "deny",
      reason: `Secret/signing boundary is never unlockable by an agent: ${normalized}.`,
    };
  }

  if (overrideEnv || matchesApprovedPath(normalized, allowedProtectedPaths)) {
    return { decision: "allow" };
  }

  const protectedPatterns = compilePatterns(config?.boundaries?.protectedPatterns, DEFAULT_PROTECTED);
  if (protectedPatterns.some((re) => re.test(normalized))) {
    return {
      decision: "deny",
      reason: `Protected boundary cannot be modified without explicit S2 contract approval (allowedProtectedPaths): ${normalized}. Escalate to the human.`,
    };
  }

  const contractPatterns = compilePatterns(config?.boundaries?.contractPatterns, DEFAULT_CONTRACT);
  if (contractLocked && contractPatterns.some((re) => re.test(normalized))) {
    return {
      decision: "deny",
      reason: `Contract is locked after /ship S2 approval: ${normalized}. Escalate any contract revision to the human.`,
    };
  }

  return { decision: "allow" };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const raw = fs.readFileSync(0, "utf8").trim();
  const payload = raw ? JSON.parse(raw) : {};
  const target = payload?.toolCall?.args?.TargetFile ?? "";
  const cwd = payload?.workspacePaths?.[0] || process.cwd();
  const config = loadShipConfig(cwd);
  const state = loadState(resolveStatePath(cwd));
  const result = evaluateBoundaryWrite(target, {
    contractLocked: Boolean(state?.contractLocked),
    allowedProtectedPaths: state?.allowedProtectedPaths || [],
    config,
  });
  console.log(JSON.stringify(result));
}

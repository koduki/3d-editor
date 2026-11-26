#!/usr/bin/env node
/**
 * Generic deterministic state machine CLI & library for /ship.
 * Reads project policies from `.agents/ship.config.json` and persists per-branch
 * checkpoints under `.ship/runs/<branch-slug>.json`.
 *
 * Enforces Clean-Room Spec-Derived Oracle Testing:
 *   S2_CONTRACT_LOCK (human approval) -> S3a_ORACLE_TEST_GEN (separate agent process,
 *   spec-only, pre-impl Red) -> lock-oracle -> S3b_IMPL_WORKFLOW (oracle files locked
 *   by PreToolUse hook) -> S5_PR_CI_HEAL_LOOP (requires oraclePassed) -> S6_AUTO_MERGE_AND_DEPLOY
 *   (requires ciPassed + codexPassed).
 */
import fs from "node:fs";
import path from "node:path";
import { execSync } from "node:child_process";
import { fileURLToPath } from "node:url";

export const VALID_STATES = [
  "S0_RECON",
  "S1_AUTO_GRILL",
  "S2_CONTRACT_LOCK",
  "S3a_ORACLE_TEST_GEN",
  "S3b_IMPL_WORKFLOW",
  "S4_LOCAL_CODEX_REVIEW",
  "S5_PR_CI_HEAL_LOOP",
  "S6_AUTO_MERGE_AND_DEPLOY",
  "S7_POST_DEPLOY_VERIFY",
  "S8_HARNESS_LEARN",
  "ESCALATED",
  "DONE",
];

export const VALID_GATES = ["AUTO_PROCEED", "WAITING_HUMAN", "DONE"];
export const DEFAULT_MAX_RETRIES = 2;

export function loadShipConfig(cwd = process.cwd()) {
  const cfgPath = path.join(cwd, ".agents", "ship.config.json");
  if (!fs.existsSync(cfgPath)) return {};
  try {
    return JSON.parse(fs.readFileSync(cfgPath, "utf8"));
  } catch {
    return {};
  }
}

export function getBranchSlug(cwd = process.cwd()) {
  try {
    const branch = execSync("git rev-parse --abbrev-ref HEAD", {
      cwd,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
    return branch.replace(/[^a-zA-Z0-9._-]/g, "_") || "detached";
  } catch {
    return "local";
  }
}

export function resolveStatePath(cwd = process.cwd()) {
  if (process.env.KUCODE_SHIP_STATE_FILE) {
    return path.resolve(process.env.KUCODE_SHIP_STATE_FILE);
  }
  return path.join(cwd, ".ship", "runs", `${getBranchSlug(cwd)}.json`);
}

export function loadState(statePath) {
  if (!fs.existsSync(statePath)) return null;
  return JSON.parse(fs.readFileSync(statePath, "utf8"));
}

export function saveState(statePath, state) {
  fs.mkdirSync(path.dirname(statePath), { recursive: true });
  state.updatedAt = new Date().toISOString();
  fs.writeFileSync(statePath, JSON.stringify(state, null, 2) + "\n", "utf8");
  return state;
}

function normalizePathList(list) {
  return Array.from(
    new Set(
      (list || [])
        .map((p) => String(p || "").trim().replace(/\\/g, "/").replace(/^\.\//, ""))
        .filter(Boolean),
    ),
  );
}

export function initRun(statePath, goal, options = {}) {
  const cfg = loadShipConfig(options.cwd || process.cwd());
  const now = new Date().toISOString();
  const state = {
    schema: 3,
    goal,
    lane: options.lane || "standard",
    deployTarget: options.deployTarget || cfg?.delivery?.defaultTarget || "merge-only",
    currentState: "S0_RECON",
    gate: "AUTO_PROCEED",
    contractLocked: false,
    allowedProtectedPaths: [],
    oracleLocked: false,
    oracleTestFiles: [],
    oraclePreImplRedVerified: false,
    oracleStatus: "pending",
    oracleExemptReason: null,
    specPath: options.specPath || "",
    acceptancePath: options.acceptancePath || "",
    prNumber: null,
    ciStatus: "pending",
    codexStatus: "pending",
    grillSummary: { rounds: 0, questionsAsked: 0, skipped: false },
    retries: { impl: 0, codex: 0, ci: 0 },
    stallsInState: 0,
    escalationReason: null,
    findingsHistory: [],
    createdAt: now,
    updatedAt: now,
  };
  return saveState(statePath, state);
}

export function approveContract(statePath, updates = {}) {
  const state = loadState(statePath);
  if (!state) throw new Error("No active /ship state found.");
  if (state.currentState !== "S2_CONTRACT_LOCK" && state.currentState !== "ESCALATED") {
    throw new Error(`Contract can only be approved from S2_CONTRACT_LOCK or ESCALATED (current: ${state.currentState}).`);
  }
  state.contractLocked = true;
  if (updates.specPath) state.specPath = updates.specPath;
  if (updates.acceptancePath) state.acceptancePath = updates.acceptancePath;
  if (updates.deployTarget) state.deployTarget = updates.deployTarget;
  if (Array.isArray(updates.allowedProtectedPaths)) {
    state.allowedProtectedPaths = normalizePathList(updates.allowedProtectedPaths);
  }
  // Always enter S3a_ORACLE_TEST_GEN first unless oracle was already locked before an escalation.
  state.currentState = state.oracleLocked ? "S3b_IMPL_WORKFLOW" : "S3a_ORACLE_TEST_GEN";
  state.gate = "AUTO_PROCEED";
  state.stallsInState = 0;
  state.escalationReason = null;
  return saveState(statePath, state);
}

export function lockOracle(statePath, options = {}) {
  const state = loadState(statePath);
  if (!state) throw new Error("No active /ship state found.");
  if (!state.contractLocked) {
    throw new Error("Precondition failed: cannot lock oracle tests before contract approval (S2_CONTRACT_LOCK).");
  }
  if (state.currentState !== "S3a_ORACLE_TEST_GEN" && state.currentState !== "ESCALATED") {
    throw new Error(`Oracle tests can only be locked from S3a_ORACLE_TEST_GEN or ESCALATED (current: ${state.currentState}).`);
  }

  const files = normalizePathList(options.files || []);
  const exemptReason = options.exemptReason ? String(options.exemptReason).trim() : null;

  if (files.length === 0 && !exemptReason) {
    throw new Error("Precondition failed: lock-oracle requires at least one oracle test file (--files) or an explicit docs/chore exemption (--exempt-reason).");
  }
  if (files.length > 0 && options.preImplRedVerified !== true && !options.allowExistingGreen) {
    throw new Error("Precondition failed: verify pre-implementation Red failure on new oracle tests (--red-verified) or pass --allow-existing-green for regression-only characterization tests.");
  }

  state.oracleLocked = true;
  state.oracleTestFiles = files;
  state.oraclePreImplRedVerified = Boolean(options.preImplRedVerified);
  state.oracleExemptReason = exemptReason;
  state.oracleStatus = exemptReason ? "exempt" : "pending";
  state.currentState = "S3b_IMPL_WORKFLOW";
  state.gate = "AUTO_PROCEED";
  state.stallsInState = 0;
  return saveState(statePath, state);
}

export function transitionState(statePath, nextState, options = {}) {
  const state = loadState(statePath);
  if (!state) throw new Error("No active /ship state found.");
  if (!VALID_STATES.includes(nextState)) {
    throw new Error(`Invalid state: ${nextState}`);
  }

  const postContractStates = new Set([
    "S3a_ORACLE_TEST_GEN",
    "S3b_IMPL_WORKFLOW",
    "S4_LOCAL_CODEX_REVIEW",
    "S5_PR_CI_HEAL_LOOP",
    "S6_AUTO_MERGE_AND_DEPLOY",
    "S7_POST_DEPLOY_VERIFY",
    "S8_HARNESS_LEARN",
    "DONE",
  ]);
  if (postContractStates.has(nextState) && !state.contractLocked) {
    throw new Error(`Precondition failed: cannot transition to ${nextState} before contractLocked=true (approve S2_CONTRACT_LOCK first).`);
  }

  const postOracleLockStates = new Set([
    "S3b_IMPL_WORKFLOW",
    "S4_LOCAL_CODEX_REVIEW",
    "S5_PR_CI_HEAL_LOOP",
    "S6_AUTO_MERGE_AND_DEPLOY",
    "S7_POST_DEPLOY_VERIFY",
    "S8_HARNESS_LEARN",
    "DONE",
  ]);
  if (postOracleLockStates.has(nextState) && !state.oracleLocked) {
    throw new Error(`Precondition failed: cannot transition to ${nextState} before independent oracle tests are authored and locked (run S3a_ORACLE_TEST_GEN and state.mjs lock-oracle first).`);
  }

  if (options.prNumber != null) state.prNumber = Number(options.prNumber);
  if (options.oraclePassed) state.oracleStatus = "passed";
  if (options.ciPassed) state.ciStatus = "passed";
  if (options.codexPassed) state.codexStatus = "passed";
  if (options.lane) state.lane = options.lane;
  if (options.deployTarget) state.deployTarget = options.deployTarget;
  if (options.specPath) state.specPath = options.specPath;
  if (options.acceptancePath) state.acceptancePath = options.acceptancePath;

  const postOraclePassStates = new Set([
    "S4_LOCAL_CODEX_REVIEW",
    "S5_PR_CI_HEAL_LOOP",
    "S6_AUTO_MERGE_AND_DEPLOY",
    "S7_POST_DEPLOY_VERIFY",
    "S8_HARNESS_LEARN",
    "DONE",
  ]);
  if (postOraclePassStates.has(nextState) && state.oracleStatus !== "passed" && state.oracleStatus !== "exempt") {
    throw new Error(`Precondition failed: cannot transition to ${nextState} until locked oracle tests pass (--oracle-passed).`);
  }

  if (nextState === "S6_AUTO_MERGE_AND_DEPLOY") {
    if (state.ciStatus !== "passed") {
      throw new Error("Precondition failed: cannot transition to S6_AUTO_MERGE_AND_DEPLOY until CI status is passed (--ci-passed).");
    }
    if (state.codexStatus !== "passed") {
      throw new Error("Precondition failed: cannot transition to S6_AUTO_MERGE_AND_DEPLOY until Codex PR review completes cleanly (--codex-passed).");
    }
  }

  state.currentState = nextState;
  state.stallsInState = 0;
  if (nextState === "DONE") {
    state.gate = "DONE";
  } else if (nextState === "S1_AUTO_GRILL" || nextState === "S2_CONTRACT_LOCK" || nextState === "ESCALATED") {
    state.gate = "WAITING_HUMAN";
  } else {
    state.gate = options.gate || "AUTO_PROCEED";
  }
  return saveState(statePath, state);
}

export function recordRetry(statePath, bucket, cwd = process.cwd()) {
  const state = loadState(statePath);
  if (!state) throw new Error("No active /ship state found.");
  if (!(bucket in state.retries)) {
    throw new Error(`Unknown retry bucket '${bucket}'. Use impl | codex | ci.`);
  }
  const cfg = loadShipConfig(cwd);
  const max = Number(cfg?.execution?.maxRetries?.[bucket] ?? DEFAULT_MAX_RETRIES);
  state.retries[bucket] += 1;
  const exceeded = state.retries[bucket] > max;
  saveState(statePath, state);
  return { bucket, count: state.retries[bucket], max, exceeded };
}

export function escalateRun(statePath, reason) {
  const state = loadState(statePath);
  if (!state) throw new Error("No active /ship state found.");
  state.currentState = "ESCALATED";
  state.gate = "WAITING_HUMAN";
  state.escalationReason = reason || "Unspecified escalation";
  state.stallsInState = 0;
  return saveState(statePath, state);
}

export function recordFinding(statePath, patternId, detail) {
  const state = loadState(statePath);
  if (!state) throw new Error("No active /ship state found.");
  state.findingsHistory.push({
    patternId,
    detail,
    state: state.currentState,
    timestamp: new Date().toISOString(),
  });
  saveState(statePath, state);

  const historyPath = path.join(path.dirname(path.dirname(statePath)), "history.json");
  const history = fs.existsSync(historyPath) ? JSON.parse(fs.readFileSync(historyPath, "utf8")) : { counts: {} };
  history.counts[patternId] = (history.counts[patternId] || 0) + 1;
  fs.mkdirSync(path.dirname(historyPath), { recursive: true });
  fs.writeFileSync(historyPath, JSON.stringify(history, null, 2) + "\n", "utf8");
  return { patternId, totalOccurrences: history.counts[patternId], promoteToGuard: history.counts[patternId] >= 2 };
}

function parseFlag(args, name) {
  const idx = args.indexOf(name);
  return idx >= 0 && idx + 1 < args.length ? args[idx + 1] : undefined;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [cmd, ...rest] = process.argv.slice(2);
  const statePath = resolveStatePath();

  try {
    switch (cmd) {
      case "status": {
        const state = loadState(statePath);
        console.log(JSON.stringify({ statePath, active: Boolean(state), state }, null, 2));
        break;
      }
      case "init": {
        const goal = rest.find((a) => !a.startsWith("--")) || "Unnamed task";
        const state = initRun(statePath, goal, {
          lane: parseFlag(rest, "--lane"),
          deployTarget: parseFlag(rest, "--deploy-target"),
        });
        console.log(JSON.stringify(state, null, 2));
        break;
      }
      case "set-gate": {
        const gate = rest[0];
        if (!VALID_GATES.includes(gate)) throw new Error(`Invalid gate: ${gate}`);
        const state = loadState(statePath);
        if (!state) throw new Error("No active /ship state found.");
        state.gate = gate;
        saveState(statePath, state);
        console.log(JSON.stringify(state, null, 2));
        break;
      }
      case "record-grill": {
        const state = loadState(statePath);
        if (!state) throw new Error("No active /ship state found.");
        state.grillSummary = {
          rounds: Number(parseFlag(rest, "--rounds") ?? 0),
          questionsAsked: Number(parseFlag(rest, "--questions") ?? 0),
          skipped: rest.includes("--skipped"),
        };
        saveState(statePath, state);
        console.log(JSON.stringify(state, null, 2));
        break;
      }
      case "approve-contract": {
        const allowRaw = parseFlag(rest, "--allow-protected");
        const allowedProtectedPaths = allowRaw ? allowRaw.split(",").map((s) => s.trim()).filter(Boolean) : undefined;
        const state = approveContract(statePath, {
          specPath: parseFlag(rest, "--spec"),
          acceptancePath: parseFlag(rest, "--acceptance"),
          deployTarget: parseFlag(rest, "--deploy-target"),
          allowedProtectedPaths,
        });
        console.log(JSON.stringify(state, null, 2));
        break;
      }
      case "lock-oracle": {
        const filesRaw = parseFlag(rest, "--files");
        const files = filesRaw ? filesRaw.split(",").map((s) => s.trim()).filter(Boolean) : [];
        const state = lockOracle(statePath, {
          files,
          preImplRedVerified: rest.includes("--red-verified"),
          allowExistingGreen: rest.includes("--allow-existing-green"),
          exemptReason: parseFlag(rest, "--exempt-reason"),
        });
        console.log(JSON.stringify(state, null, 2));
        break;
      }
      case "transition": {
        const nextState = rest[0];
        const state = transitionState(statePath, nextState, {
          oraclePassed: rest.includes("--oracle-passed"),
          ciPassed: rest.includes("--ci-passed"),
          codexPassed: rest.includes("--codex-passed"),
          prNumber: parseFlag(rest, "--pr"),
          lane: parseFlag(rest, "--lane"),
          deployTarget: parseFlag(rest, "--deploy-target"),
          specPath: parseFlag(rest, "--spec"),
          acceptancePath: parseFlag(rest, "--acceptance"),
          gate: parseFlag(rest, "--gate"),
        });
        console.log(JSON.stringify(state, null, 2));
        break;
      }
      case "record-retry": {
        const result = recordRetry(statePath, rest[0]);
        console.log(JSON.stringify(result, null, 2));
        if (result.exceeded) process.exit(2);
        break;
      }
      case "record-finding": {
        const [patternId, ...detailParts] = rest;
        if (!patternId) throw new Error("Usage: state.mjs record-finding <patternId> <detail>");
        const res = recordFinding(statePath, patternId, detailParts.join(" "));
        console.log(JSON.stringify(res, null, 2));
        break;
      }
      case "escalate": {
        const reason = rest.join(" ") || "Escalated to human";
        const state = escalateRun(statePath, reason);
        console.log(JSON.stringify(state, null, 2));
        break;
      }
      case "reset": {
        if (fs.existsSync(statePath)) fs.unlinkSync(statePath);
        console.log(JSON.stringify({ reset: true, statePath }));
        break;
      }
      default:
        console.error("Usage: state.mjs <status|init|set-gate|record-grill|approve-contract|lock-oracle|transition|record-retry|record-finding|escalate|reset>");
        process.exit(1);
    }
  } catch (err) {
    console.error(JSON.stringify({ error: err.message }));
    process.exit(1);
  }
}

#!/usr/bin/env node
/**
 * Deterministic state machine CLI & library for /ship.
 * Persists per-branch checkpoints under `.ship/runs/<branch-slug>.json` and
 * enforces transition preconditions so phases cannot be skipped.
 */
import fs from "node:fs";
import path from "node:path";
import { execSync } from "node:child_process";
import { fileURLToPath } from "node:url";

export const VALID_STATES = [
  "S0_RECON",
  "S1_AUTO_GRILL",
  "S2_CONTRACT_LOCK",
  "S3_IMPL_WORKFLOW",
  "S4_LOCAL_CODEX_REVIEW",
  "S5_PR_CI_HEAL_LOOP",
  "S6_AUTO_MERGE_AND_DEPLOY",
  "S7_POST_DEPLOY_VERIFY",
  "S8_HARNESS_LEARN",
  "ESCALATED",
  "DONE",
];

export const VALID_GATES = ["AUTO_PROCEED", "WAITING_HUMAN", "DONE"];
export const MAX_RETRIES_PER_PHASE = 2;

export function getBranchSlug(cwd = process.cwd()) {
  try {
    const branch = execSync("git rev-parse --abbrev-ref HEAD", { cwd, encoding: "utf8" }).trim();
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

export function initRun(statePath, goal, options = {}) {
  const now = new Date().toISOString();
  const state = {
    schema: 1,
    goal,
    lane: options.lane || "standard",
    deployTarget: options.deployTarget || "merge-only",
    currentState: "S0_RECON",
    gate: "AUTO_PROCEED",
    contractLocked: false,
    specPath: options.specPath || "",
    acceptancePath: options.acceptancePath || "",
    prNumber: null,
    ciStatus: "pending",
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
  state.currentState = "S3_IMPL_WORKFLOW";
  state.gate = "AUTO_PROCEED";
  state.stallsInState = 0;
  state.escalationReason = null;
  return saveState(statePath, state);
}

export function transitionState(statePath, nextState, options = {}) {
  const state = loadState(statePath);
  if (!state) throw new Error("No active /ship state found.");
  if (!VALID_STATES.includes(nextState)) {
    throw new Error(`Invalid state: ${nextState}`);
  }

  // Enforce deterministic preconditions so phases cannot be bypassed.
  const postContractStates = new Set([
    "S3_IMPL_WORKFLOW",
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
  if (options.prNumber != null) state.prNumber = Number(options.prNumber);
  if (options.ciPassed) state.ciStatus = "passed";
  if (options.lane) state.lane = options.lane;
  if (options.deployTarget) state.deployTarget = options.deployTarget;
  if (options.specPath) state.specPath = options.specPath;
  if (options.acceptancePath) state.acceptancePath = options.acceptancePath;

  if (nextState === "S6_AUTO_MERGE_AND_DEPLOY" && state.ciStatus !== "passed") {
    throw new Error("Precondition failed: cannot transition to S6_AUTO_MERGE_AND_DEPLOY until CI status is passed (--ci-passed).");
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

export function recordRetry(statePath, bucket) {
  const state = loadState(statePath);
  if (!state) throw new Error("No active /ship state found.");
  if (!(bucket in state.retries)) {
    throw new Error(`Unknown retry bucket '${bucket}'. Use impl | codex | ci.`);
  }
  state.retries[bucket] += 1;
  const exceeded = state.retries[bucket] > MAX_RETRIES_PER_PHASE;
  saveState(statePath, state);
  return { bucket, count: state.retries[bucket], max: MAX_RETRIES_PER_PHASE, exceeded };
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

  // Also track cross-run counts under .ship/history.json for "2 strikes -> guard"
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
        const state = approveContract(statePath, {
          specPath: parseFlag(rest, "--spec"),
          acceptancePath: parseFlag(rest, "--acceptance"),
          deployTarget: parseFlag(rest, "--deploy-target"),
        });
        console.log(JSON.stringify(state, null, 2));
        break;
      }
      case "transition": {
        const nextState = rest[0];
        const state = transitionState(statePath, nextState, {
          ciPassed: rest.includes("--ci-passed"),
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
        console.error("Usage: state.mjs <status|init|set-gate|record-grill|approve-contract|transition|record-retry|record-finding|escalate|reset>");
        process.exit(1);
    }
  } catch (err) {
    console.error(JSON.stringify({ error: err.message }));
    process.exit(1);
  }
}

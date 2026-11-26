#!/usr/bin/env node
/**
 * Polls GitHub PR CI checks and ChatGPT Codex Connector PR review status until
 * both finish (or timeout), following `.agents/ship.config.json`:
 * - If Codex has not reacted or reviewed after `autoMentionIfSilentSeconds` (default 90s),
 *   posts `@codex review` once automatically (unless `--no-auto-mention` is passed).
 * - Waits up to `timeoutSeconds` (default 600s / 10 min) for Codex review completion.
 * - On Codex timeout or ChatGPT Plus quota limit, sets `readyToAutoMerge: false` and
 *   exits with code 3 so `/ship` stops auto-merge and escalates to the human.
 * - Compresses failed CI logs via LogSage-style preprocessing (never dumps raw logs).
 */
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { compressCiLog } from "../../../../scripts/guards/ci-log-compressor.mjs";
import { loadShipConfig } from "./state.mjs";

function runGh(args) {
  return execFileSync("gh", args, { encoding: "utf8", maxBuffer: 20 * 1024 * 1024 });
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

const CODEX_LOGIN_RE = /codex|chatgpt-codex/i;
const QUOTA_RE = /(usage limit|rate limit|quota exceeded|out of (?:code )?review|limit reached)/i;
const BLOCKING_RE = /\b(P0|P1)\b|badge\/P[01]|critical|must fix|severity:\s*(?:critical|high)/i;

export function extractCodexFindings(reviews = [], lineComments = [], issueComments = [], options = {}) {
  const sinceMs = options.sinceIso ? Date.parse(options.sinceIso) : 0;
  const isFresh = (ts) => !sinceMs || !ts || Date.parse(ts) >= sinceMs - 5000;
  const isCodexUser = (login) => CODEX_LOGIN_RE.test(String(login || ""));

  const findings = [];
  let quotaLimited = false;
  let reviewPosted = false;

  for (const r of reviews) {
    const login = r?.author?.login || r?.user?.login;
    const ts = r?.submittedAt || r?.submitted_at || r?.createdAt;
    if (isCodexUser(login) && isFresh(ts)) {
      reviewPosted = true;
      const body = String(r.body || "");
      if (QUOTA_RE.test(body)) quotaLimited = true;
      if (body.trim()) {
        findings.push({ source: "review", state: r.state, createdAt: ts || null, body: body.slice(0, 600) });
      }
    }
  }

  for (const c of lineComments) {
    const login = c?.user?.login;
    const ts = c?.created_at || c?.updated_at;
    if (isCodexUser(login) && isFresh(ts)) {
      reviewPosted = true;
      const body = String(c.body || "");
      if (QUOTA_RE.test(body)) quotaLimited = true;
      if (body.trim()) {
        findings.push({
          source: "inline",
          path: c.path,
          line: c.line || c.original_line || null,
          createdAt: ts || null,
          body: body.slice(0, 600),
        });
      }
    }
  }

  for (const c of issueComments) {
    const login = c?.author?.login || c?.user?.login;
    const ts = c?.createdAt || c?.created_at;
    if (isCodexUser(login) && isFresh(ts)) {
      const body = String(c.body || "");
      if (QUOTA_RE.test(body)) quotaLimited = true;
      reviewPosted = true;
      if (body.trim()) {
        findings.push({ source: "comment", createdAt: ts || null, body: body.slice(0, 600) });
      }
    }
  }

  const blocking = findings.filter((f) => BLOCKING_RE.test(f.body) && !QUOTA_RE.test(f.body));
  const status = quotaLimited
    ? "quota_limited"
    : !reviewPosted
      ? "pending"
      : blocking.length > 0
        ? "blocking_findings"
        : "clean";

  return {
    status,
    reviewPosted,
    quotaLimited,
    totalCodexItems: findings.length,
    blockingCount: blocking.length,
    blocking,
    all: findings.slice(0, 20),
  };
}

export function evaluateGateVerdict({ ciPassed, codex, timedOut }) {
  if (!ciPassed) {
    return {
      readyToAutoMerge: false,
      exitCode: 2,
      nextAction: "heal_ci",
      escalateReason: null,
    };
  }
  if (codex.quotaLimited) {
    return {
      readyToAutoMerge: false,
      exitCode: 3,
      nextAction: "escalate_human",
      escalateReason: "OpenAI ChatGPT Plus Codex review hit usage/quota limit. Auto-merge halted for human confirmation.",
    };
  }
  if (timedOut || !codex.reviewPosted) {
    return {
      readyToAutoMerge: false,
      exitCode: 3,
      nextAction: "escalate_human",
      escalateReason: "Codex PR review did not complete within the configured timeout. Auto-merge halted for human confirmation.",
    };
  }
  if (codex.blockingCount > 0) {
    return {
      readyToAutoMerge: false,
      exitCode: 2,
      nextAction: "fix_codex_findings",
      escalateReason: null,
    };
  }
  return {
    readyToAutoMerge: true,
    exitCode: 0,
    nextAction: "auto_merge",
    escalateReason: null,
  };
}

async function pollPr(prNumber, options = {}) {
  const cfg = loadShipConfig(process.cwd());
  const pollSeconds = options.pollSeconds ?? 15;
  const ciTimeoutSeconds = options.ciTimeoutSeconds ?? 1800;
  const codexTimeoutSeconds = options.codexTimeoutSeconds ?? Number(cfg?.codexReview?.timeoutSeconds ?? 600);
  const autoMentionAfterSec = options.autoMentionAfterSec ?? Number(cfg?.codexReview?.autoMentionIfSilentSeconds ?? 90);
  const autoMentionComment = cfg?.codexReview?.autoMentionComment || "@codex review";
  const allowAutoMention = !options.noAutoMention;
  const repoArgs = options.repo ? ["-R", options.repo] : [];

  const repoName = options.repo || JSON.parse(runGh(["repo", "view", "--json", "nameWithOwner"])).nameWithOwner;
  const startMs = Date.now();
  const ciDeadline = startMs + ciTimeoutSeconds * 1000;
  let codexDeadline = startMs + codexTimeoutSeconds * 1000;
  let mentionedCodex = false;

  // Determine the timestamp of the latest commit on the PR so we wait for fresh Codex review
  const initialPr = JSON.parse(runGh(["pr", "view", String(prNumber), ...repoArgs, "--json", "commits,url"]));
  const lastCommit = (initialPr.commits || []).slice(-1)[0];
  const sinceIso = options.sinceIso || lastCommit?.committedDate || new Date(startMs - 10_000).toISOString();

  let checks = [];
  let ciDone = false;
  let codex = { status: "pending", reviewPosted: false, quotaLimited: false, totalCodexItems: 0, blockingCount: 0, blocking: [], all: [] };
  let codexTimedOut = false;

  while (Date.now() < ciDeadline) {
    const pr = JSON.parse(runGh(["pr", "view", String(prNumber), ...repoArgs, "--json", "statusCheckRollup,reviews,comments,url"]));
    checks = pr.statusCheckRollup || [];
    const pendingChecks = checks.filter((c) => {
      const s = String(c.status || c.state || "").toUpperCase();
      return s === "IN_PROGRESS" || s === "QUEUED" || s === "PENDING" || s === "WAITING";
    });
    ciDone = checks.length > 0 && pendingChecks.length === 0;

    let lineComments = [];
    try {
      lineComments = JSON.parse(runGh(["api", `repos/${repoName}/pulls/${prNumber}/comments`, "--paginate"]));
    } catch {
      lineComments = [];
    }
    codex = extractCodexFindings(pr.reviews, lineComments, pr.comments, { sinceIso });

    // If Codex hasn't reacted/posted after autoMentionAfterSec, nudge once with `@codex review`
    const elapsedSec = (Date.now() - startMs) / 1000;
    if (allowAutoMention && !mentionedCodex && !codex.reviewPosted && elapsedSec >= autoMentionAfterSec) {
      let hasEyesReaction = false;
      try {
        const reactions = JSON.parse(runGh(["api", `repos/${repoName}/issues/${prNumber}/reactions`]));
        hasEyesReaction = reactions.some((r) => CODEX_LOGIN_RE.test(r?.user?.login || "") && r.content === "eyes");
      } catch {
        hasEyesReaction = false;
      }
      if (!hasEyesReaction) {
        try {
          runGh(["pr", "comment", String(prNumber), ...repoArgs, "--body", autoMentionComment]);
          mentionedCodex = true;
          codexDeadline = Math.max(codexDeadline, Date.now() + codexTimeoutSeconds * 1000);
        } catch {
          mentionedCodex = true;
        }
      } else {
        mentionedCodex = true;
      }
    }

    const codexFinished = codex.reviewPosted || codex.quotaLimited;
    codexTimedOut = !codexFinished && Date.now() >= codexDeadline;

    // Exit loop once CI has finished AND (Codex finished OR Codex timed out)
    if (ciDone && (codexFinished || codexTimedOut)) {
      break;
    }
    // If CI failed fast, don't block 10 minutes on Codex before starting CI repair
    const anyCiFailed = ciDone && checks.some((c) => ["FAILURE", "TIMED_OUT", "ACTION_REQUIRED", "ERROR"].includes(String(c.conclusion || c.state || "").toUpperCase()));
    if (anyCiFailed) {
      break;
    }

    await sleep(pollSeconds * 1000);
  }

  const failedChecks = checks.filter((c) => {
    const conc = String(c.conclusion || c.state || "").toUpperCase();
    return ["FAILURE", "TIMED_OUT", "ACTION_REQUIRED", "ERROR"].includes(conc);
  });
  const ciPassed = ciDone && failedChecks.length === 0;

  const failureReports = [];
  for (const fc of failedChecks.slice(0, 3)) {
    const url = fc.detailsUrl || "";
    const runMatch = url.match(/\/actions\/runs\/(\d+)/);
    if (runMatch) {
      try {
        const failedLog = runGh(["run", "view", runMatch[1], ...repoArgs, "--log-failed"]);
        failureReports.push({
          checkName: fc.name || fc.context,
          runId: runMatch[1],
          triage: compressCiLog(failedLog),
        });
      } catch (err) {
        failureReports.push({
          checkName: fc.name || fc.context,
          runId: runMatch[1],
          error: `Could not fetch failed logs: ${err.message}`,
        });
      }
    } else {
      failureReports.push({ checkName: fc.name || fc.context, conclusion: fc.conclusion || fc.state });
    }
  }

  const verdict = evaluateGateVerdict({ ciPassed, codex, timedOut: codexTimedOut });

  return {
    prNumber: Number(prNumber),
    url: initialPr.url,
    ciPassed,
    totalChecks: checks.length,
    failedChecks: failedChecks.map((c) => ({ name: c.name || c.context, conclusion: c.conclusion || c.state })),
    failureReports,
    codex: {
      ...codex,
      autoMentioned: mentionedCodex,
      timedOut: codexTimedOut,
    },
    ...verdict,
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const prNumber = args.find((a) => /^\d+$/.test(a));
  if (!prNumber) {
    console.error("Usage: wait-pr-gates.mjs <pr-number> [--repo owner/repo] [--codex-timeout 600] [--no-auto-mention]");
    process.exit(1);
  }
  const flag = (k) => {
    const idx = args.indexOf(k);
    return idx >= 0 ? args[idx + 1] : undefined;
  };
  const report = await pollPr(prNumber, {
    repo: flag("--repo"),
    codexTimeoutSeconds: flag("--codex-timeout") ? Number(flag("--codex-timeout")) : undefined,
    ciTimeoutSeconds: flag("--ci-timeout") ? Number(flag("--ci-timeout")) : undefined,
    noAutoMention: args.includes("--no-auto-mention"),
  });
  console.log(JSON.stringify(report, null, 2));
  process.exit(report.exitCode);
}

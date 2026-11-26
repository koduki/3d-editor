#!/usr/bin/env node
/**
 * Polls GitHub PR CI checks and ChatGPT Codex Connector PR review comments until
 * completion, compresses any failed CI run log using LogSage-style preprocessing,
 * and returns a bounded JSON report to /ship.
 */
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { compressCiLog } from "../../../../scripts/guards/ci-log-compressor.mjs";

function runGh(args) {
  return execFileSync("gh", args, { encoding: "utf8", maxBuffer: 20 * 1024 * 1024 });
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export function extractCodexFindings(reviews = [], lineComments = [], issueComments = []) {
  const isCodexUser = (login) => /codex|chatgpt-codex/i.test(String(login || ""));
  const findings = [];

  for (const r of reviews) {
    if (isCodexUser(r?.author?.login || r?.user?.login) && r.body) {
      findings.push({ source: "review", state: r.state, body: String(r.body).slice(0, 600) });
    }
  }
  for (const c of lineComments) {
    if (isCodexUser(c?.user?.login) && c.body) {
      findings.push({
        source: "inline",
        path: c.path,
        line: c.line || c.original_line || null,
        body: String(c.body).slice(0, 600),
      });
    }
  }
  for (const c of issueComments) {
    if (isCodexUser(c?.author?.login || c?.user?.login) && c.body) {
      findings.push({ source: "comment", body: String(c.body).slice(0, 600) });
    }
  }

  const blocking = findings.filter((f) => /\b(P0|P1)\b|badge\/P[01]|critical|must fix/i.test(f.body));
  return { totalCodexItems: findings.length, blockingCount: blocking.length, blocking, all: findings.slice(0, 20) };
}

async function pollPr(prNumber, options = {}) {
  const { pollSeconds = 15, timeoutSeconds = 1800, repo } = options;
  const repoArgs = repo ? ["-R", repo] : [];
  const deadline = Date.now() + timeoutSeconds * 1000;

  let checks = [];
  while (Date.now() < deadline) {
    const raw = runGh(["pr", "view", String(prNumber), ...repoArgs, "--json", "statusCheckRollup,headRefName,url"]);
    const pr = JSON.parse(raw);
    checks = pr.statusCheckRollup || [];

    const pending = checks.filter((c) => {
      const status = String(c.status || c.state || "").toUpperCase();
      return status === "IN_PROGRESS" || status === "QUEUED" || status === "PENDING" || status === "WAITING";
    });
    if (checks.length > 0 && pending.length === 0) {
      break;
    }
    await sleep(pollSeconds * 1000);
  }

  const failedChecks = checks.filter((c) => {
    const conc = String(c.conclusion || c.state || "").toUpperCase();
    return ["FAILURE", "TIMED_OUT", "ACTION_REQUIRED", "ERROR"].includes(conc);
  });

  // Compress failed workflow logs (never stream raw logs to the main agent)
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

  // Fetch Codex GitHub App reviews & inline comments
  const prDetails = JSON.parse(runGh(["pr", "view", String(prNumber), ...repoArgs, "--json", "reviews,comments,url"]));
  let lineComments = [];
  try {
    const repoName = repo || JSON.parse(runGh(["repo", "view", "--json", "nameWithOwner"])).nameWithOwner;
    lineComments = JSON.parse(runGh(["api", `repos/${repoName}/pulls/${prNumber}/comments`, "--paginate"]));
  } catch {
    lineComments = [];
  }
  const codex = extractCodexFindings(prDetails.reviews, lineComments, prDetails.comments);

  return {
    prNumber: Number(prNumber),
    url: prDetails.url,
    ciPassed: checks.length > 0 && failedChecks.length === 0,
    totalChecks: checks.length,
    failedChecks: failedChecks.map((c) => ({ name: c.name || c.context, conclusion: c.conclusion || c.state })),
    failureReports,
    codex,
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const prNumber = args.find((a) => /^\d+$/.test(a));
  if (!prNumber) {
    console.error("Usage: wait-pr-gates.mjs <pr-number> [--repo owner/repo] [--timeout 1800]");
    process.exit(1);
  }
  const flag = (k) => {
    const idx = args.indexOf(k);
    return idx >= 0 ? args[idx + 1] : undefined;
  };
  const report = await pollPr(prNumber, {
    repo: flag("--repo"),
    timeoutSeconds: Number(flag("--timeout") || 1800),
  });
  console.log(JSON.stringify(report, null, 2));
  process.exit(report.ciPassed && report.codex.blockingCount === 0 ? 0 : 2);
}

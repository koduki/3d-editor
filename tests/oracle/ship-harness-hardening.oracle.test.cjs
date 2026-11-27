'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const { pathToFileURL } = require('node:url');

const execFileAsync = promisify(execFile);
const REPO_ROOT = path.resolve(__dirname, '..', '..');

function makeTempDir(prefix) {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}

function importRepoEsm(relPath) {
  return import(pathToFileURL(path.join(REPO_ROOT, relPath)).href);
}

function parseFrontmatterBlock(markdown) {
  const normalized = markdown.replace(/\r\n/g, '\n');
  const match = normalized.match(/^---\n([\s\S]*?)\n---\n?/);
  if (!match) return null;
  return match[1];
}

// ---------------------------------------------------------------------------
// AC-1: ship-reviewer subagent, Guard 11 enforcement, & ship.config.json schema
// ---------------------------------------------------------------------------
test('[AC-1] ship-reviewer.md is a valid Guard-11 read-only pro subagent and ship.config.json declares reviewer, tiers, grace, and micro-fix policy', async () => {
  const reviewerPath = path.join(REPO_ROOT, '.agents', 'agents', 'ship-reviewer.md');
  assert.equal(fs.existsSync(reviewerPath), true, '.agents/agents/ship-reviewer.md must exist');

  const reviewerMd = fs.readFileSync(reviewerPath, 'utf8');
  const fm = parseFrontmatterBlock(reviewerMd);
  assert.ok(fm, 'ship-reviewer.md must contain YAML frontmatter');
  const fmLines = fm.split('\n');
  assert.ok(fmLines.some((l) => /^name:\s*ship-reviewer\s*$/.test(l)), 'frontmatter must set name: ship-reviewer');
  assert.ok(fmLines.some((l) => /^subagent:\s*true\s*$/.test(l)), 'frontmatter must set subagent: true');
  assert.ok(fmLines.some((l) => /^model:\s*pro\s*$/.test(l)), 'frontmatter must set model: pro');
  assert.ok(fmLines.some((l) => /^commandExecutionPolicy:\s*sandbox\s*$/.test(l)), 'frontmatter must set commandExecutionPolicy: sandbox');
  assert.match(fm, /view_file/);
  assert.match(fm, /run_command/);
  assert.match(fm, /send_message/);
  assert.equal(/\b(?:write_to_file|replace_file_content|multi_replace_file_content)\b/.test(fm), false, 'ship-reviewer must not declare write tools');
  assert.match(reviewerMd, /lock\s*DAG|CAS|consent|crash|security/i);

  const { runGuards } = await importRepoEsm('scripts/guards/run-all-guards.mjs');
  const liveReport = runGuards(REPO_ROOT, { changedOnly: false });
  assert.equal(liveReport.passed, true, `runGuards(REPO_ROOT) failed: ${JSON.stringify(liveReport.violations, null, 2)}`);

  // Guard 11 temp fixture: adding write_to_file to ship-reviewer.md must fail Guard 11
  const tmp = makeTempDir('kucode-ac1-guard11-');
  try {
    const write = (rel, content) => {
      const full = path.join(tmp, rel);
      fs.mkdirSync(path.dirname(full), { recursive: true });
      fs.writeFileSync(full, content, 'utf8');
    };
    write('LICENSE', 'MIT\n');
    write('THIRD_PARTY_NOTICES.md', 'notices\n');
    write('LICENSES/MIT-OpenVSCode.txt', 'MIT\n');
    write('.agents/agents/ship-reviewer.md', [
      '---',
      'name: ship-reviewer',
      'description: Read-only adversarial reviewer accidentally granted a write tool',
      'tools:',
      '  - view_file',
      '  - write_to_file',
      '  - run_command',
      '  - send_message',
      'subagent: true',
      'model: pro',
      'commandExecutionPolicy: sandbox',
      '---',
      '# System Prompt',
    ].join('\n'));

    const tmpReport = runGuards(tmp, { changedOnly: false });
    assert.equal(tmpReport.passed, false);
    const guard11 = tmpReport.violations.filter(
      (v) => v.ruleId === 'custom-subagent-frontmatter' && v.file.endsWith('ship-reviewer.md'),
    );
    assert.ok(guard11.length >= 1, `Expected Guard 11 violation for ship-reviewer.md, got: ${JSON.stringify(tmpReport.violations)}`);
    assert.ok(guard11.some((v) => /write_to_file/.test(v.detail)));
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }

  const cfg = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, '.agents', 'ship.config.json'), 'utf8'));
  assert.equal(cfg.execution?.agents?.reviewer, 'ship-reviewer');
  assert.equal(cfg.execution?.models?.reviewer, 'pro');
  assert.deepEqual(cfg.execution?.implementerTiers, {
    standard: 'flash',
    complex: 'inherit',
  });
  assert.equal(cfg.codexReview?.runningGraceSeconds, 300);
  assert.equal(cfg.codexReview?.maxTotalTimeoutSeconds, 900);
  assert.equal(cfg.ciSelfHeal?.microFixMaxLines, 50);
});

// ---------------------------------------------------------------------------
// AC-2: Codex review-state parsing, updatedAt, headRefOid matching, running/eyes
// ---------------------------------------------------------------------------
test('[AC-2] parseCodexReviewState parses nested and flat codex-review-state:v1 markers and rejects absent/malformed input', async () => {
  const gates = await importRepoEsm('.agents/skills/ship/scripts/wait-pr-gates.mjs');
  assert.equal(typeof gates.parseCodexReviewState, 'function', 'parseCodexReviewState must be exported');

  const nestedBody = [
    'Codex is reviewing your pull request.',
    '<!-- codex-review-state:v1 {"code_review":{"commit_sha":"a1b2c3d4e5f67890","has_any_findings":null,"has_unresolved_findings":null,"status":"running"},"security_review":{"commit_sha":"a1b2c3d4e5f67890","has_any_findings":null,"has_unresolved_findings":null,"status":"running"}} -->',
  ].join('\n');

  const nestedParsed = gates.parseCodexReviewState(nestedBody);
  assert.ok(nestedParsed, 'Expected parsed state for nested marker');
  assert.equal(nestedParsed.version, 1);
  assert.equal(nestedParsed.status, 'running');
  assert.equal(nestedParsed.headRefOid, 'a1b2c3d4e5f67890');
  assert.equal(nestedParsed.hasAnyFindings, null);
  assert.equal(nestedParsed.hasUnresolvedFindings, null);
  assert.equal(typeof nestedParsed.raw, 'object');

  const flatBody = '<!-- codex-review-state:v1 {"status":" COMPLETED ","headRefOid":"ff00112233445566","has_any_findings":false,"has_unresolved_findings":false} -->';
  const flatParsed = gates.parseCodexReviewState(flatBody);
  assert.ok(flatParsed, 'Expected parsed state for flat marker');
  assert.equal(flatParsed.version, 1);
  assert.equal(flatParsed.status, 'completed');
  assert.equal(flatParsed.headRefOid, 'ff00112233445566');
  assert.equal(flatParsed.hasAnyFindings, false);
  assert.equal(flatParsed.hasUnresolvedFindings, false);

  assert.equal(gates.parseCodexReviewState(''), null);
  assert.equal(gates.parseCodexReviewState(null), null);
  assert.equal(gates.parseCodexReviewState('No marker here'), null);
  assert.equal(gates.parseCodexReviewState('<!-- codex-review-state:v1 {not-json} -->'), null);
});

test('[AC-2] extractCodexFindings & evaluateGateVerdict respect updatedAt, headRefOid matching, and never mark running/eyes as clean', async () => {
  const { extractCodexFindings, evaluateGateVerdict } = await importRepoEsm('.agents/skills/ship/scripts/wait-pr-gates.mjs');

  const sinceIso = '2026-11-26T10:10:00.000Z';
  const currentSha = 'deadbeef1234567890abcdef1234567890abcdef';
  const oldSha = '0000111122223333444455556666777788889999';

  // 1) In-place edited running placeholder (created BEFORE sinceIso, updated AFTER sinceIso) -> MUST be running, NEVER clean
  const runningFindings = extractCodexFindings([], [], [
    {
      author: { login: 'chatgpt-codex-connector[bot]' },
      createdAt: '2026-11-26T10:00:00.000Z',
      updatedAt: '2026-11-26T10:11:00.000Z',
      body: `Codex is reviewing...\n<!-- codex-review-state:v1 {"code_review":{"commit_sha":"${currentSha}","has_any_findings":null,"has_unresolved_findings":null,"status":"running"}} -->`,
    },
  ], { sinceIso, headRefOid: currentSha });

  assert.equal(runningFindings.status, 'running');
  assert.equal(runningFindings.isRunning, true);
  assert.equal(runningFindings.reviewPosted, false);
  const runningVerdict = evaluateGateVerdict({ ciPassed: true, codex: runningFindings, timedOut: false });
  assert.equal(runningVerdict.readyToAutoMerge, false);
  assert.equal(runningVerdict.exitCode, 3);
  assert.equal(runningVerdict.nextAction, 'escalate_human');

  // 2) Active eyes reaction without completed review -> MUST be running, NEVER clean
  const eyesFindings = extractCodexFindings([], [], [], {
    sinceIso,
    headRefOid: currentSha,
    reactions: [{ user: { login: 'chatgpt-codex-connector[bot]' }, content: 'eyes' }],
  });
  assert.equal(eyesFindings.hasEyesReaction, true);
  assert.equal(eyesFindings.isRunning, true);
  assert.equal(eyesFindings.reviewPosted, false);
  assert.equal(eyesFindings.status, 'running');
  const eyesVerdict = evaluateGateVerdict({ ciPassed: true, codex: eyesFindings, timedOut: false });
  assert.equal(eyesVerdict.readyToAutoMerge, false);
  assert.equal(eyesVerdict.exitCode, 3);
  assert.equal(eyesVerdict.nextAction, 'escalate_human');

  // 3) Stale completed comment matching an older SHA MUST NOT mark current headRefOid as clean
  const staleShaFindings = extractCodexFindings([], [], [
    {
      author: { login: 'chatgpt-codex-connector[bot]' },
      createdAt: '2026-11-26T10:01:00.000Z',
      updatedAt: '2026-11-26T10:12:00.000Z',
      body: `No major issues found.\n<!-- codex-review-state:v1 {"code_review":{"commit_sha":"${oldSha}","has_any_findings":false,"has_unresolved_findings":false,"status":"completed"}} -->`,
    },
  ], { sinceIso, headRefOid: currentSha });
  assert.notEqual(staleShaFindings.status, 'clean');
  assert.equal(staleShaFindings.reviewPosted, false);
  assert.equal(staleShaFindings.status, 'pending');

  // 4) Matching completed comment edited in-place after sinceIso (using snake_case updated_at and >=7 char prefix match) -> clean & auto_merge
  const cleanFindings = extractCodexFindings([], [], [
    {
      user: { login: 'chatgpt-codex-connector[bot]' },
      created_at: '2026-11-26T10:00:00.000Z',
      updated_at: '2026-11-26T10:15:00.000Z',
      body: `Codex Review: No major issues found.\n<!-- codex-review-state:v1 {"code_review":{"commit_sha":"DEADBEEF12","has_any_findings":false,"has_unresolved_findings":false,"status":"completed"}} -->`,
    },
  ], { sinceIso, headRefOid: currentSha });
  assert.equal(cleanFindings.status, 'clean');
  assert.equal(cleanFindings.reviewPosted, true);
  const cleanVerdict = evaluateGateVerdict({ ciPassed: true, codex: cleanFindings, timedOut: false });
  assert.equal(cleanVerdict.readyToAutoMerge, true);
  assert.equal(cleanVerdict.exitCode, 0);
  assert.equal(cleanVerdict.nextAction, 'auto_merge');
});

// ---------------------------------------------------------------------------
// AC-3: Running grace period (+300s capped at 900s) & pollPr completion
// ---------------------------------------------------------------------------
test('[AC-3] computeCodexDeadline extends by +300s capped at 900s and pollPr completes clean inside running grace window', async () => {
  const gates = await importRepoEsm('.agents/skills/ship/scripts/wait-pr-gates.mjs');
  assert.equal(typeof gates.computeCodexDeadline, 'function', 'computeCodexDeadline must be exported');
  assert.equal(typeof gates.pollPr, 'function', 'pollPr must be exported');

  // Direct computeCodexDeadline checks
  const startMs = 1_000_000;
  const baseDeadlineMs = startMs + 600_000;
  const extended = gates.computeCodexDeadline({
    nowMs: startMs + 590_000,
    baseDeadlineMs,
    startMs,
    codex: { isRunning: true, status: 'running', hasEyesReaction: false },
    runningGraceSeconds: 300,
    maxTotalTimeoutSeconds: 900,
    graceGranted: false,
  });
  assert.equal(extended.graceGranted, true);
  assert.equal(extended.extended, true);
  assert.equal(extended.deadlineMs, startMs + 890_000);

  // Capped at startMs + 900s
  const capped = gates.computeCodexDeadline({
    nowMs: startMs + 750_000,
    baseDeadlineMs,
    startMs,
    codex: { isRunning: false, status: 'pending', hasEyesReaction: true },
    runningGraceSeconds: 300,
    maxTotalTimeoutSeconds: 900,
    graceGranted: false,
  });
  assert.equal(capped.graceGranted, true);
  assert.equal(capped.extended, true);
  assert.equal(capped.deadlineMs, startMs + 900_000);

  // Already granted -> no further extension
  const noDouble = gates.computeCodexDeadline({
    nowMs: startMs + 880_000,
    baseDeadlineMs: startMs + 890_000,
    startMs,
    codex: { isRunning: true, status: 'running', hasEyesReaction: true },
    runningGraceSeconds: 300,
    maxTotalTimeoutSeconds: 900,
    graceGranted: true,
  });
  assert.equal(noDouble.extended, false);
  assert.equal(noDouble.deadlineMs, startMs + 890_000);

  // Mocked pollPr: transitions from running (+ eyes) past 600s into completed (clean) at 700s
  const t0 = Date.parse('2026-11-26T12:00:00.000Z');
  let nowMs = t0;
  const headSha = '1234567890abcdef1234567890abcdef12345678';
  const logs = [];

  const ghRunner = (args) => {
    const elapsedSec = (nowMs - t0) / 1000;
    if (args[0] === 'pr' && args[1] === 'view') {
      const completed = elapsedSec >= 700;
      const commentBody = completed
        ? `Codex Review: No major issues found.\n<!-- codex-review-state:v1 {"code_review":{"commit_sha":"${headSha}","has_any_findings":false,"has_unresolved_findings":false,"status":"completed"}} -->`
        : `Codex is reviewing...\n<!-- codex-review-state:v1 {"code_review":{"commit_sha":"${headSha}","has_any_findings":null,"has_unresolved_findings":null,"status":"running"}} -->`;
      return JSON.stringify({
        url: 'https://github.com/koduki/kucode/pull/99',
        headRefOid: headSha,
        commits: [{ oid: headSha, committedDate: new Date(t0 - 10_000).toISOString() }],
        statusCheckRollup: [{ name: 'test', status: 'COMPLETED', conclusion: 'SUCCESS' }],
        reviews: [],
        comments: [
          {
            author: { login: 'chatgpt-codex-connector[bot]' },
            createdAt: new Date(t0 + 1000).toISOString(),
            updatedAt: new Date(nowMs).toISOString(),
            body: commentBody,
          },
        ],
      });
    }
    if (args[0] === 'api' && String(args[1]).includes('/pulls/99/comments')) {
      return '[]';
    }
    if (args[0] === 'api' && String(args[1]).includes('/issues/99/reactions')) {
      return JSON.stringify([{ user: { login: 'chatgpt-codex-connector[bot]' }, content: 'eyes' }]);
    }
    throw new Error(`Unexpected gh invocation: ${args.join(' ')}`);
  };

  const report = await gates.pollPr(99, {
    repo: 'koduki/kucode',
    pollSeconds: 100,
    heartbeatSeconds: 100,
    codexTimeoutSeconds: 600,
    runningGraceSeconds: 300,
    maxTotalTimeoutSeconds: 900,
    ciTimeoutSeconds: 1800,
    ghRunner,
    nowFn: () => nowMs,
    sleepFn: async (ms) => {
      nowMs += ms;
    },
    logFn: (line) => logs.push(line),
  });

  assert.equal(report.readyToAutoMerge, true);
  assert.equal(report.exitCode, 0);
  assert.equal(report.nextAction, 'auto_merge');
  assert.equal(report.codex.status, 'clean');
  assert.equal(report.codex.runningGraceGranted, true);
  assert.equal(report.codex.timedOut, false);
  assert.ok(report.codex.effectiveTimeoutSeconds > 600 && report.codex.effectiveTimeoutSeconds <= 900);
});

// ---------------------------------------------------------------------------
// AC-4: runGhWithRetry exponential backoff on 5xx/network vs fast-fail on 4xx
// ---------------------------------------------------------------------------
test('[AC-4] runGhWithRetry retries transient 5xx/ETIMEDOUT with exponential backoff up to maxRetries=4 and fails fast on 4xx', async () => {
  const gates = await importRepoEsm('.agents/skills/ship/scripts/wait-pr-gates.mjs');
  assert.equal(typeof gates.runGhWithRetry, 'function', 'runGhWithRetry must be exported');

  // 1) Transient 502, 503, ETIMEDOUT -> recovers on 4th attempt
  let attempts = 0;
  const delays = [];
  const transientErrors = [
    new Error('gh: HTTP 502 Bad Gateway'),
    new Error('gh: HTTP 503 Service Unavailable'),
    new Error('request failed: ETIMEDOUT'),
  ];
  const res = await gates.runGhWithRetry(['pr', 'view', '42'], {
    maxRetries: 4,
    baseDelayMs: 100,
    runner: () => {
      attempts += 1;
      if (attempts <= transientErrors.length) {
        throw transientErrors[attempts - 1];
      }
      return '{"number":42}';
    },
    sleepFn: async (ms) => {
      delays.push(ms);
    },
  });
  assert.equal(res, '{"number":42}');
  assert.equal(attempts, 4);
  assert.deepEqual(delays, [100, 200, 400]);

  // 2) Persistent 502 exhausts maxRetries=4 -> throws after 5 total attempts
  let exhaustAttempts = 0;
  const exhaustDelays = [];
  await assert.rejects(
    () => gates.runGhWithRetry(['pr', 'view', '42'], {
      maxRetries: 4,
      baseDelayMs: 50,
      runner: () => {
        exhaustAttempts += 1;
        throw new Error('HTTP 502: Bad Gateway');
      },
      sleepFn: async (ms) => {
        exhaustDelays.push(ms);
      },
    }),
    /502|Bad Gateway/,
  );
  assert.equal(exhaustAttempts, 5);
  assert.deepEqual(exhaustDelays, [50, 100, 200, 400]);

  // 3) Deterministic 4xx client errors fail fast with ZERO retries
  for (const errMsg of ['HTTP 404: Not Found', 'HTTP 401: Unauthorized', 'HTTP 422: Unprocessable Entity']) {
    let clientAttempts = 0;
    const clientDelays = [];
    await assert.rejects(
      () => gates.runGhWithRetry(['pr', 'view', '999'], {
        maxRetries: 4,
        baseDelayMs: 100,
        runner: () => {
          clientAttempts += 1;
          throw new Error(errMsg);
        },
        sleepFn: async (ms) => {
          clientDelays.push(ms);
        },
      }),
    );
    assert.equal(clientAttempts, 1, `Expected 0 retries on ${errMsg}, got ${clientAttempts - 1}`);
    assert.deepEqual(clientDelays, []);
  }
});

// ---------------------------------------------------------------------------
// AC-5: @codex review deduplication across invocations & single-line heartbeat
// ---------------------------------------------------------------------------
test('[AC-5] hasFreshCodexMention deduplicates @codex review across pollPr runs and emits single-line heartbeat to logFn', async () => {
  const gates = await importRepoEsm('.agents/skills/ship/scripts/wait-pr-gates.mjs');
  assert.equal(typeof gates.hasFreshCodexMention, 'function', 'hasFreshCodexMention must be exported');

  const sinceIso = '2026-11-26T14:00:00.000Z';
  assert.equal(
    gates.hasFreshCodexMention(
      [{ body: 'Please @Codex  review this PR', createdAt: '2026-11-26T14:00:10.000Z' }],
      { sinceIso },
    ),
    true,
  );
  assert.equal(
    gates.hasFreshCodexMention(
      [{ body: '@codex review', createdAt: '2026-11-26T13:50:00.000Z', updatedAt: '2026-11-26T13:50:00.000Z' }],
      { sinceIso },
    ),
    false,
  );
  assert.equal(
    gates.hasFreshCodexMention(
      [{ body: 'Looks good without trigger keyword', createdAt: '2026-11-26T14:01:00.000Z' }],
      { sinceIso },
    ),
    false,
  );

  // pollPr with existing fresh @codex review comment -> must NOT post a duplicate pr comment
  const t0 = Date.parse('2026-11-26T14:00:00.000Z');
  let nowMs = t0;
  const headSha = 'abcdef0123456789abcdef0123456789abcdef01';
  const prCommentCalls = [];
  const heartbeatLogs = [];

  const ghRunner = (args) => {
    if (args[0] === 'pr' && args[1] === 'comment') {
      prCommentCalls.push(args);
      return '';
    }
    if (args[0] === 'pr' && args[1] === 'view') {
      const elapsedSec = (nowMs - t0) / 1000;
      const comments = [
        {
          author: { login: 'kucode-bot' },
          createdAt: new Date(t0 + 5_000).toISOString(),
          body: '@codex review',
        },
      ];
      if (elapsedSec >= 150) {
        comments.push({
          author: { login: 'chatgpt-codex-connector[bot]' },
          createdAt: new Date(nowMs).toISOString(),
          body: `No major issues found.\n<!-- codex-review-state:v1 {"code_review":{"commit_sha":"${headSha}","has_any_findings":false,"has_unresolved_findings":false,"status":"completed"}} -->`,
        });
      }
      return JSON.stringify({
        url: 'https://github.com/koduki/kucode/pull/105',
        headRefOid: headSha,
        commits: [{ oid: headSha, committedDate: new Date(t0).toISOString() }],
        statusCheckRollup: [{ name: 'ci', status: 'COMPLETED', conclusion: 'SUCCESS' }],
        reviews: [],
        comments,
      });
    }
    if (args[0] === 'api' && String(args[1]).includes('/comments')) return '[]';
    if (args[0] === 'api' && String(args[1]).includes('/reactions')) return '[]';
    throw new Error(`Unexpected gh invocation: ${args.join(' ')}`);
  };

  const report = await gates.pollPr(105, {
    repo: 'koduki/kucode',
    pollSeconds: 30,
    heartbeatSeconds: 30,
    autoMentionAfterSec: 60,
    codexTimeoutSeconds: 600,
    ciTimeoutSeconds: 1800,
    sinceIso: new Date(t0).toISOString(),
    ghRunner,
    nowFn: () => nowMs,
    sleepFn: async (ms) => {
      nowMs += ms;
    },
    logFn: (msg) => heartbeatLogs.push(String(msg)),
  });

  assert.equal(prCommentCalls.length, 0, 'Must not post duplicate @codex review when fresh mention already exists');
  assert.equal(report.readyToAutoMerge, true);
  assert.equal(report.exitCode, 0);
  assert.ok(heartbeatLogs.length >= 1, 'Expected periodic heartbeat log lines');
  for (const line of heartbeatLogs) {
    assert.equal(line.trim().includes('\n'), false, `Heartbeat must be single-line, got: ${JSON.stringify(line)}`);
  }
});

// ---------------------------------------------------------------------------
// AC-6: ci-log-compressor classification precision on libproot.so vs gradle_test
// ---------------------------------------------------------------------------
test('[AC-6] compressCiLog classifies Gradle unit test failures with benign libproot.so lines as gradle_test and genuine alignment errors as apk_elf_alignment', async () => {
  const { compressCiLog } = await importRepoEsm('scripts/guards/ci-log-compressor.mjs');

  const gradleFailWithBenignLibproot = [
    '2026-11-26T02:00:00.000Z Copying bundled native library libproot.so -> android/app/src/main/jniLibs/arm64-v8a/libproot.so',
    '2026-11-26T02:00:01.000Z Copying bundled native library libproot-loader.so -> android/app/src/main/jniLibs/arm64-v8a/libproot-loader.so',
    '2026-11-26T02:00:02.000Z Checking ELF segments for libproot.so... OK',
    '2026-11-26T02:01:00.000Z > Task :app:testDebugUnitTest FAILED',
    '2026-11-26T02:01:00.100Z PosixRuntimeConfigTest > preservesUserToolchainOrder FAILED',
    '2026-11-26T02:01:00.200Z     java.lang.AssertionError: expected:<1> but was:<2>',
    '2026-11-26T02:01:00.300Z FAILURE: Build failed with an exception.',
  ].join('\n');

  const gradleReport = compressCiLog(gradleFailWithBenignLibproot);
  assert.equal(gradleReport.category, 'gradle_test');
  assert.ok(gradleReport.keyErrorLines.some((l) => /AssertionError|testDebugUnitTest/i.test(l)));

  const realAlignmentFail = [
    '2026-11-26T02:05:00.000Z Running 16KB ELF alignment verification...',
    '2026-11-26T02:05:00.100Z FAIL: libproot.so has LOAD offset=0x1000 not 16KB-aligned',
    '2026-11-26T02:05:00.200Z ::error::ELF alignment check failed',
  ].join('\n');
  assert.equal(compressCiLog(realAlignmentFail).category, 'apk_elf_alignment');

  const missingLoadSegments = 'FAIL: libproot-loader.so has no LOAD segments';
  assert.equal(compressCiLog(missingLoadSegments).category, 'apk_elf_alignment');
});

// ---------------------------------------------------------------------------
// AC-7: state.mjs resumeRun & CLI resume behavior & lock preconditions
// ---------------------------------------------------------------------------
test('[AC-7] state.mjs resumeRun and CLI resume cleanly from ESCALATED, enforce preconditions, and selectively reset retries', async () => {
  const stateMod = await importRepoEsm('.agents/skills/ship/scripts/state.mjs');
  assert.equal(typeof stateMod.resumeRun, 'function', 'resumeRun must be exported');

  const tmp = makeTempDir('kucode-ac7-resume-');
  const statePath = path.join(tmp, '.ship', 'runs', 'feat_hardening.json');
  const stateCli = path.join(REPO_ROOT, '.agents', 'skills', 'ship', 'scripts', 'state.mjs');

  try {
    stateMod.initRun(statePath, 'Harden ship harness', { cwd: REPO_ROOT });
    stateMod.transitionState(statePath, 'S2_CONTRACT_LOCK');
    stateMod.approveContract(statePath, {
      specPath: 'docs/specs/ship-harness-hardening/spec.md',
      acceptancePath: 'docs/specs/ship-harness-hardening/acceptance.json',
      deployTarget: 'merge-only',
    });
    stateMod.lockOracle(statePath, {
      files: ['tests/oracle/ship-harness-hardening.oracle.test.cjs'],
      preImplRedVerified: true,
    });
    stateMod.transitionState(statePath, 'S5_PR_CI_HEAL_LOOP', { prNumber: 77, oraclePassed: true });

    stateMod.recordRetry(statePath, 'impl', REPO_ROOT);
    stateMod.recordRetry(statePath, 'codex', REPO_ROOT);
    stateMod.recordRetry(statePath, 'codex', REPO_ROOT);
    stateMod.recordRetry(statePath, 'ci', REPO_ROOT);
    stateMod.escalateRun(statePath, 'Codex exceeded 900s');

    // CLI resume S5_PR_CI_HEAL_LOOP --reset-retries codex,ci
    const { stdout } = await execFileAsync(
      process.execPath,
      [stateCli, 'resume', 'S5_PR_CI_HEAL_LOOP', '--reset-retries', 'codex,ci'],
      {
        cwd: REPO_ROOT,
        env: { ...process.env, KUCODE_SHIP_STATE_FILE: statePath },
      },
    );
    const resumed = JSON.parse(stdout);
    assert.equal(resumed.currentState, 'S5_PR_CI_HEAL_LOOP');
    assert.equal(resumed.gate, 'AUTO_PROCEED');
    assert.equal(resumed.escalationReason, null);
    assert.equal(resumed.stallsInState, 0);
    assert.deepEqual(resumed.retries, { impl: 1, codex: 0, ci: 0 });

    // --reset-retries all & default targetState
    stateMod.recordRetry(statePath, 'codex', REPO_ROOT);
    stateMod.recordRetry(statePath, 'ci', REPO_ROOT);
    stateMod.escalateRun(statePath, 'Another stall');
    const allReset = stateMod.resumeRun(statePath, { resetRetries: 'all' });
    assert.equal(allReset.currentState, 'S3b_IMPL_WORKFLOW');
    assert.equal(allReset.gate, 'AUTO_PROCEED');
    assert.equal(allReset.escalationReason, null);
    assert.deepEqual(allReset.retries, { impl: 0, codex: 0, ci: 0 });

    // Reject invalid retry bucket
    assert.throws(() => stateMod.resumeRun(statePath, { targetState: 'S5_PR_CI_HEAL_LOOP', resetRetries: ['unknown'] }));

    // Reject resuming into S5 when oracleLocked is false
    const unlockedPath = path.join(tmp, '.ship', 'runs', 'unlocked.json');
    stateMod.initRun(unlockedPath, 'Unlocked run', { cwd: REPO_ROOT });
    stateMod.transitionState(unlockedPath, 'S2_CONTRACT_LOCK');
    stateMod.approveContract(unlockedPath, { specPath: 'docs/specs/x/spec.md' });
    stateMod.escalateRun(unlockedPath, 'Stalled during S3a');
    assert.throws(() => stateMod.resumeRun(unlockedPath, { targetState: 'S5_PR_CI_HEAL_LOOP' }));
    const cliFail = await execFileAsync(
      process.execPath,
      [stateCli, 'resume', 'S5_PR_CI_HEAL_LOOP'],
      { cwd: REPO_ROOT, env: { ...process.env, KUCODE_SHIP_STATE_FILE: unlockedPath } },
    ).then(() => ({ ok: true }), (err) => ({ ok: false, code: err.code }));
    assert.equal(cliFail.ok, false);
    assert.notEqual(cliFail.code, 0);

    // Reject resuming from DONE
    stateMod.transitionState(statePath, 'S5_PR_CI_HEAL_LOOP', { oraclePassed: true });
    stateMod.transitionState(statePath, 'S6_AUTO_MERGE_AND_DEPLOY', { ciPassed: true, codexPassed: true });
    stateMod.transitionState(statePath, 'DONE');
    assert.throws(() => stateMod.resumeRun(statePath, { targetState: 'S5_PR_CI_HEAL_LOOP' }));
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

// ---------------------------------------------------------------------------
// AC-8: check-staged-boundaries.mjs & .githooks/pre-commit secondary boundary lock
// ---------------------------------------------------------------------------
test('[AC-8] check-staged-boundaries.mjs and .githooks/pre-commit enforce secondary boundary locks in a real git repo', async () => {
  const stagedMod = await importRepoEsm('.agents/skills/ship/scripts/check-staged-boundaries.mjs');
  assert.equal(typeof stagedMod.parseNameStatusOutput, 'function', 'parseNameStatusOutput must be exported');
  assert.equal(typeof stagedMod.evaluateStagedEntries, 'function', 'evaluateStagedEntries must be exported');

  // 1) parseNameStatusOutput (tab and NUL delimited)
  const tabParsed = stagedMod.parseNameStatusOutput(
    'A\ttests/oracle/new.oracle.test.cjs\nM\tsrc/app.js\nR100\ttests/oracle/a.oracle.test.cjs\ttests/oracle/b.oracle.test.cjs\n',
    false,
  );
  assert.equal(tabParsed.length, 3);
  assert.equal(tabParsed[0].status, 'A');
  assert.equal(tabParsed[0].path, 'tests/oracle/new.oracle.test.cjs');
  assert.equal(tabParsed[2].status.startsWith('R'), true);
  assert.equal(tabParsed[2].oldPath, 'tests/oracle/a.oracle.test.cjs');
  assert.equal(tabParsed[2].path, 'tests/oracle/b.oracle.test.cjs');

  const nulParsed = stagedMod.parseNameStatusOutput(
    'A\0tests/oracle/x.oracle.test.cjs\0R100\0tests/oracle/old.oracle.test.cjs\0tests/oracle/new.oracle.test.cjs\0',
    true,
  );
  assert.equal(nulParsed.length, 2);
  assert.equal(nulParsed[1].oldPath, 'tests/oracle/old.oracle.test.cjs');
  assert.equal(nulParsed[1].path, 'tests/oracle/new.oracle.test.cjs');

  // 2) evaluateStagedEntries unit contract checks
  const cfg = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, '.agents', 'ship.config.json'), 'utf8'));

  // Always denies secrets even with overrideEnv: true and no active /ship run
  for (const secretPath of ['.env', '.env.production', 'release.keystore']) {
    const res = stagedMod.evaluateStagedEntries([{ status: 'A', path: secretPath }], {
      hasActiveShipRun: false,
      overrideEnv: true,
      allowedProtectedPaths: [secretPath],
      config: cfg,
    });
    assert.equal(res.decision, 'deny', `Secret ${secretPath} must always be denied`);
  }

  // Active locked run: allows A for new oracle, denies M/D for oracle, denies unapproved protected, allows approved protected
  const lockedOpts = {
    hasActiveShipRun: true,
    currentState: 'S3b_IMPL_WORKFLOW',
    contractLocked: true,
    oracleLocked: true,
    oracleTestFiles: ['tests/oracle/existing.oracle.test.cjs'],
    allowedProtectedPaths: ['scripts/guards/ci-log-compressor.mjs'],
    config: cfg,
    overrideEnv: false,
  };
  assert.equal(
    stagedMod.evaluateStagedEntries([{ status: 'A', path: 'tests/oracle/new-feature.oracle.test.cjs' }], lockedOpts).decision,
    'allow',
  );
  assert.equal(
    stagedMod.evaluateStagedEntries([{ status: 'M', path: 'tests/oracle/existing.oracle.test.cjs' }], lockedOpts).decision,
    'deny',
  );
  assert.equal(
    stagedMod.evaluateStagedEntries([{ status: 'D', path: 'tests/oracle/existing.oracle.test.cjs' }], lockedOpts).decision,
    'deny',
  );
  assert.equal(
    stagedMod.evaluateStagedEntries([{ status: 'M', path: 'scripts/guards/run-all-guards.mjs' }], lockedOpts).decision,
    'deny',
  );
  assert.equal(
    stagedMod.evaluateStagedEntries([{ status: 'M', path: 'scripts/guards/ci-log-compressor.mjs' }], lockedOpts).decision,
    'allow',
  );
  assert.equal(
    stagedMod.evaluateStagedEntries([{ status: 'M', path: 'tests/oracle/existing.oracle.test.cjs' }], {
      ...lockedOpts,
      overrideEnv: true,
    }).decision,
    'allow',
  );

  // 3) Real temporary git repository + .githooks/pre-commit E2E test
  const preCommitText = fs.readFileSync(path.join(REPO_ROOT, '.githooks', 'pre-commit'), 'utf8');
  assert.match(preCommitText, /check-staged-boundaries\.mjs/);
  assert.ok(
    preCommitText.indexOf('check-staged-boundaries.mjs') < preCommitText.indexOf('run-all-guards.mjs'),
    '.githooks/pre-commit must run check-staged-boundaries.mjs before run-all-guards.mjs',
  );

  const tmpRepo = makeTempDir('kucode-ac8-git-');
  const tmpState = path.join(tmpRepo, '.ship', 'runs', 'test.json');
  try {
    const copyIntoRepo = (rel) => {
      const dst = path.join(tmpRepo, rel);
      fs.mkdirSync(path.dirname(dst), { recursive: true });
      fs.copyFileSync(path.join(REPO_ROOT, rel), dst);
    };

    copyIntoRepo('.agents/ship.config.json');
    copyIntoRepo('.agents/skills/ship/scripts/state.mjs');
    copyIntoRepo('.agents/skills/ship/scripts/guard-boundaries.mjs');
    copyIntoRepo('.agents/skills/ship/scripts/check-staged-boundaries.mjs');
    copyIntoRepo('.githooks/pre-commit');
    fs.chmodSync(path.join(tmpRepo, '.githooks', 'pre-commit'), 0o755);

    const oracleRel = 'tests/oracle/tracked.oracle.test.cjs';
    fs.mkdirSync(path.join(tmpRepo, 'tests', 'oracle'), { recursive: true });
    fs.writeFileSync(
      path.join(tmpRepo, oracleRel),
      "const test = require('node:test');\nconst assert = require('node:assert/strict');\ntest('[AC-1] ok', () => { assert.equal(1, 1); });\n",
      'utf8',
    );

    await execFileAsync('git', ['init'], { cwd: tmpRepo });
    await execFileAsync('git', ['config', 'user.name', 'Oracle Test'], { cwd: tmpRepo });
    await execFileAsync('git', ['config', 'user.email', 'oracle@example.com'], { cwd: tmpRepo });
    await execFileAsync('git', ['add', '.'], { cwd: tmpRepo });
    await execFileAsync('git', ['commit', '-m', 'initial tracked oracle'], { cwd: tmpRepo });
    await execFileAsync('git', ['config', 'core.hooksPath', '.githooks'], { cwd: tmpRepo });

    fs.mkdirSync(path.dirname(tmpState), { recursive: true });
    fs.writeFileSync(tmpState, JSON.stringify({
      schema: 3,
      currentState: 'S3b_IMPL_WORKFLOW',
      contractLocked: true,
      oracleLocked: true,
      oracleTestFiles: [oracleRel],
      allowedProtectedPaths: [],
    }), 'utf8');

    // Stage M edit to tracked oracle file -> git commit must be blocked by .githooks/pre-commit!
    fs.appendFileSync(path.join(tmpRepo, oracleRel), '// weakened\n', 'utf8');
    await execFileAsync('git', ['add', oracleRel], { cwd: tmpRepo });

    const commitBlocked = await execFileAsync(
      'git',
      ['commit', '-m', 'tamper tracked oracle'],
      { cwd: tmpRepo, env: { ...process.env, KUCODE_SHIP_STATE_FILE: tmpState } },
    ).then(() => ({ blocked: false }), (err) => ({ blocked: true, stderr: err.stderr || '', code: err.code }));

    assert.equal(commitBlocked.blocked, true, '.githooks/pre-commit must block staged M edit to locked oracle file');
    assert.notEqual(commitBlocked.code, 0);

    // Direct CLI check with staged A on a new oracle file -> allowed
    await execFileAsync('git', ['reset', '--hard', 'HEAD'], { cwd: tmpRepo });
    const newOracleRel = 'tests/oracle/new-feature.oracle.test.cjs';
    fs.writeFileSync(
      path.join(tmpRepo, newOracleRel),
      "const test = require('node:test');\nconst assert = require('node:assert/strict');\ntest('[AC-2] ok', () => { assert.equal(2, 2); });\n",
      'utf8',
    );
    await execFileAsync('git', ['add', newOracleRel], { cwd: tmpRepo });

    const checkCli = path.join(REPO_ROOT, '.agents', 'skills', 'ship', 'scripts', 'check-staged-boundaries.mjs');
    await execFileAsync(
      process.execPath,
      [checkCli, '--cwd', tmpRepo],
      { cwd: REPO_ROOT, env: { ...process.env, KUCODE_SHIP_STATE_FILE: tmpState } },
    );

    // Staging .env -> always blocked even with KUCODE_ALLOW_BOUNDARY_EDIT=1
    fs.writeFileSync(path.join(tmpRepo, '.env'), 'DUMMY_CONFIG=local\n', 'utf8');
    await execFileAsync('git', ['add', '.env'], { cwd: tmpRepo });
    const secretBlocked = await execFileAsync(
      process.execPath,
      [checkCli, '--cwd', tmpRepo],
      {
        cwd: REPO_ROOT,
        env: { ...process.env, KUCODE_SHIP_STATE_FILE: tmpState, KUCODE_ALLOW_BOUNDARY_EDIT: '1' },
      },
    ).then(() => false, () => true);
    assert.equal(secretBlocked, true, 'Staged .env must always be blocked');
  } finally {
    fs.rmSync(tmpRepo, { recursive: true, force: true });
  }
});

// ---------------------------------------------------------------------------
// AC-9: Documentation & prompt contracts + AGENTS.md <= 90 lines
// ---------------------------------------------------------------------------
test('[AC-9] Agent prompts, kotlin-android-host.md rule, SKILL.md, and AGENTS.md (<= 90 lines) encode all hardened contracts', () => {
  const read = (rel) => fs.readFileSync(path.join(REPO_ROOT, rel), 'utf8');

  const oracleAuthorMd = read('.agents/agents/oracle-test-author.md');
  assert.match(oracleAuthorMd, /execFileSync/);
  assert.match(oracleAuthorMd, /\b(?:execFile|spawn)\b/);
  assert.match(oracleAuthorMd, /loopback|HTTP\s+server/i);
  assert.match(oracleAuthorMd, /HttpURLConnection/);
  assert.match(oracleAuthorMd, /\bOrigin\b/);
  assert.match(oracleAuthorMd, /\bSocket\b/);

  const architectMd = read('.agents/agents/ship-architect.md');
  assert.match(architectMd, /lock\s*DAG|@JavascriptInterface/i);
  assert.match(architectMd, /CAS|monotonic/i);
  assert.match(architectMd, /consent/i);
  assert.match(architectMd, /crash|recovery/i);
  assert.match(architectMd, /\bstandard\b[\s\S]*\bflash\b|\bflash\b[\s\S]*\bstandard\b/i);
  assert.match(architectMd, /\bcomplex\b[\s\S]*\binherit\b|\binherit\b[\s\S]*\bcomplex\b/i);

  const implementerMd = read('.agents/agents/ship-implementer.md');
  assert.match(implementerMd, /lock\s*DAG|@JavascriptInterface/i);
  assert.match(implementerMd, /CAS|monotonic/i);
  assert.match(implementerMd, /consent/i);
  assert.match(implementerMd, /crash|recovery/i);

  const kotlinRuleMd = read('.agents/rules/kotlin-android-host.md');
  assert.match(kotlinRuleMd, /@JavascriptInterface/);
  assert.match(kotlinRuleMd, /I\/O|lock/i);

  const skillMd = read('.agents/skills/ship/SKILL.md');
  assert.match(skillMd, /ship-reviewer/);
  assert.match(skillMd, /implementerTiers|tiered/i);
  assert.match(skillMd, /micro[- ]fix|microFixMaxLines|50\s+lines/i);
  assert.match(skillMd, /state\.mjs\s+resume/);
  assert.match(skillMd, /check-staged-boundaries\.mjs/);

  const agentsMd = read('AGENTS.md');
  assert.match(agentsMd, /ship-reviewer/);
  const lineCount = agentsMd.trimEnd().split(/\r?\n/).length;
  assert.ok(lineCount <= 90, `AGENTS.md must stay <= 90 lines (got ${lineCount})`);
});

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const REPO_ROOT = path.resolve(__dirname, '..');

function makeTempDir(prefix) {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}

test('ship.config.json defines cloud-first Remote Agents, 1-gate, one-by-one grilling, Clean-Room Oracle, and Codex 10-min policy', async () => {
  const { loadShipConfig } = await import('../.agents/skills/ship/scripts/state.mjs');
  const cfg = loadShipConfig(REPO_ROOT);
  assert.equal(cfg.project.repository, 'koduki/kucode');
  assert.equal(cfg.execution.primaryEnvironment, 'remote-agent');
  assert.equal(cfg.execution.subagentWorkspaceMode, 'inherit');
  assert.deepEqual(cfg.execution.agents, {
    recon: 'ship-recon',
    architect: 'ship-architect',
    oracleAuthor: 'oracle-test-author',
    implementer: 'ship-implementer',
    verifier: 'ship-verifier',
  });
  assert.deepEqual(cfg.execution.models, {
    orchestrator: 'inherit',
    recon: 'flash',
    architect: 'pro',
    oracleAuthor: 'pro',
    implementer: 'inherit',
    verifier: 'flash',
  });
  assert.ok(cfg.boundaries.protectedPatterns.includes('(^|/)\\.agents/agents/'));
  assert.equal(cfg.grilling.mode, 'one-by-one');
  assert.equal(cfg.grilling.autoSkipWhenClear, true);
  assert.equal(cfg.grilling.maxQuestions, 20);
  assert.deepEqual(cfg.humanGates, ['S2_CONTRACT_LOCK']);
  assert.equal(cfg.oracleTesting.enabled, true);
  assert.equal(cfg.oracleTesting.requireSeparateAgentProcess, true);
  assert.equal(cfg.oracleTesting.authorBeforeImplementation, true);
  assert.equal(cfg.oracleTesting.forbidReadingImplementationDiff, true);
  assert.equal(cfg.oracleTesting.lockOracleDuringImplementation, true);
  assert.equal(cfg.codexReview.primarySurface, 'github-app');
  assert.equal(cfg.codexReview.timeoutSeconds, 600);
  assert.equal(cfg.codexReview.onTimeoutOrQuota, 'escalate');
  assert.equal(cfg.ciSelfHeal.track, 'subscription-only');
});

test('run-all-guards passes cleanly on the live KuCode repository', async () => {
  const { runGuards } = await import('../scripts/guards/run-all-guards.mjs');
  const report = runGuards(REPO_ROOT, { changedOnly: false });
  assert.equal(report.passed, true, `Unexpected guard violations: ${JSON.stringify(report.violations, null, 2)}`);
  assert.ok(report.checkedFiles > 20);
});

test('run-all-guards blocks suppressions, skips, trust regressions, module leaks, build drift, secrets, and weak oracle tests', async () => {
  const { runGuards } = await import('../scripts/guards/run-all-guards.mjs');
  const tmp = makeTempDir('kucode-guard-test-');
  try {
    const write = (rel, content) => {
      const full = path.join(tmp, rel);
      fs.mkdirSync(path.dirname(full), { recursive: true });
      fs.writeFileSync(full, content, 'utf8');
    };

    write('android/app/src/main/assets/kucode-android/extension.js',
      '// eslint-disable-next-line\nconst x = require("../kucode-tools/acp-client.js");\n');
    write('android/app/src/main/assets/kucode-android/package.json',
      JSON.stringify({ capabilities: { untrustedWorkspaces: { supported: true } } }));
    write('android/app/src/main/assets/kucode-tools/package.json',
      JSON.stringify({
        capabilities: { untrustedWorkspaces: { supported: true } },
        contributes: { configuration: { properties: { 'kucode.acp.defaultAgent': { scope: 'window' } } } },
      }));
    write('tests/bad.test.cjs', 'test.skip("weakened", () => {});\n');
    write('tests/oracle/empty.oracle.test.cjs', 'const test = require("node:test"); test("no ac or assert", () => {});\n');
    write('android/app/src/main/java/dev/koduki/kucode/Foo.kt',
      '@Suppress("UNCHECKED_CAST")\nclass Foo { val tok = "ghp_1234567890abcdefghijklmno" }\n');
    write('android/app/build.gradle.kts', 'android { defaultConfig { targetSdk = 34 } }\n');
    write('scripts/bad.sh', 'openvscode-server --disable-workspace-trust\n');
    write('runtime-assets.lock.json', JSON.stringify({ abi: 'x86_64', assets: {} }));
    write('release.keystore', 'dummy');

    const report = runGuards(tmp, { changedOnly: false });
    assert.equal(report.passed, false);
    const ids = new Set(report.violations.map((v) => v.ruleId));

    for (const expected of [
      'no-lint-or-type-bypass',
      'no-test-skip-or-ignore',
      'no-new-kotlin-suppress',
      'no-workspace-trust-disable',
      'workspace-trust-and-acp-scope',
      'extension-module-boundary',
      'android-build-invariants',
      'no-secret-or-keystore-commit',
      'runtime-lock-and-license-presence',
      'oracle-test-integrity',
    ]) {
      assert.ok(ids.has(expected), `Expected violation ${expected}, got: ${Array.from(ids).join(', ')}`);
    }
    assert.ok(report.violations.every((v) => v.remediation && v.remediation.length > 10));
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('Guard 11 validates .agents/agents/*.md frontmatter, official tool allowlist, model tiers, and read-only enforcement', async () => {
  const { runGuards } = await import('../scripts/guards/run-all-guards.mjs');

  const liveAgentsDir = path.join(REPO_ROOT, '.agents', 'agents');
  const liveAgentFiles = fs.readdirSync(liveAgentsDir).filter((f) => f.endsWith('.md')).sort();
  assert.deepEqual(liveAgentFiles, [
    'oracle-test-author.md',
    'ship-architect.md',
    'ship-implementer.md',
    'ship-recon.md',
    'ship-verifier.md',
  ]);

  const tmp = makeTempDir('kucode-guard11-test-');
  try {
    const write = (rel, content) => {
      const full = path.join(tmp, rel);
      fs.mkdirSync(path.dirname(full), { recursive: true });
      fs.writeFileSync(full, content, 'utf8');
    };

    // Required OSS files so only Guard 11 triggers
    write('LICENSE', 'MIT\n');
    write('THIRD_PARTY_NOTICES.md', 'notices\n');
    write('LICENSES/MIT-OpenVSCode.txt', 'MIT\n');

    // (a) Misspelled/unmapped tool name
    write('.agents/agents/bad-tool.md', [
      '---',
      'name: bad-tool',
      'description: Agent with unmapped tool',
      'tools:',
      '  - view_file',
      '  - read_file_typo',
      'subagent: true',
      'model: flash',
      'commandExecutionPolicy: sandbox',
      '---',
      '# Prompt',
    ].join('\n'));

    // (b) Invalid model tier
    write('.agents/agents/bad-model.md', [
      '---',
      'name: bad-model',
      'description: Agent with invalid model tier',
      'tools: [view_file, send_message]',
      'subagent: true',
      'model: gpt-99',
      'commandExecutionPolicy: sandbox',
      '---',
      '# Prompt',
    ].join('\n'));

    // (c) Read-only subagent accidentally granted write tool
    write('.agents/agents/ship-verifier.md', [
      '---',
      'name: ship-verifier',
      'description: Read-only verifier with forbidden write tool',
      'tools:',
      '  - view_file',
      '  - write_to_file',
      '  - run_command',
      'subagent: true',
      'model: flash',
      'commandExecutionPolicy: sandbox',
      '---',
      '# Prompt',
    ].join('\n'));

    const report = runGuards(tmp, { changedOnly: false });
    assert.equal(report.passed, false);
    const guard11 = report.violations.filter((v) => v.ruleId === 'custom-subagent-frontmatter');
    assert.equal(guard11.length, 3, `Expected 3 Guard 11 violations, got: ${JSON.stringify(guard11, null, 2)}`);
    assert.ok(guard11.some((v) => v.file.endsWith('bad-tool.md') && /read_file_typo/.test(v.detail)));
    assert.ok(guard11.some((v) => v.file.endsWith('bad-model.md') && /gpt-99/.test(v.detail)));
    assert.ok(guard11.some((v) => v.file.endsWith('ship-verifier.md') && /write_to_file/.test(v.detail)));
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('guard-boundaries enforces two-way Clean-Room Oracle isolation, S2 allowedProtectedPaths, and never-unlockable secrets', async () => {
  const { evaluateBoundaryWrite } = await import('../.agents/skills/ship/scripts/guard-boundaries.mjs');
  const { loadShipConfig } = await import('../.agents/skills/ship/scripts/state.mjs');
  const config = loadShipConfig(REPO_ROOT);

  assert.equal(evaluateBoundaryWrite('/repo/.github/workflows/ci.yml', { config }).decision, 'deny');
  assert.equal(evaluateBoundaryWrite('/repo/runtime-assets.lock.json', { config }).decision, 'deny');
  assert.equal(evaluateBoundaryWrite('/repo/scripts/guards/run-all-guards.mjs', { config }).decision, 'deny');
  assert.equal(evaluateBoundaryWrite('/repo/.agents/ship.config.json', { config }).decision, 'deny');
  assert.equal(evaluateBoundaryWrite('/repo/.agents/agents/ship-verifier.md', { config }).decision, 'deny');
  assert.equal(
    evaluateBoundaryWrite('/repo/.agents/agents/ship-verifier.md', {
      config,
      allowedProtectedPaths: ['.agents/agents/ship-verifier.md'],
    }).decision,
    'allow',
  );

  // Explicit S2 approval unlocks only the approved protected path
  assert.equal(
    evaluateBoundaryWrite('/repo/runtime-assets.lock.json', {
      config,
      currentState: 'S3b_IMPL_WORKFLOW',
      contractLocked: true,
      oracleLocked: true,
      allowedProtectedPaths: ['runtime-assets.lock.json'],
    }).decision,
    'allow',
  );
  assert.equal(
    evaluateBoundaryWrite('/repo/.github/workflows/ci.yml', {
      config,
      currentState: 'S3b_IMPL_WORKFLOW',
      contractLocked: true,
      oracleLocked: true,
      allowedProtectedPaths: ['runtime-assets.lock.json'],
    }).decision,
    'deny',
  );

  // Secrets & signing files are NEVER unlockable even if listed in allowedProtectedPaths
  assert.equal(
    evaluateBoundaryWrite('/repo/.env.production', {
      config,
      allowedProtectedPaths: ['.env.production'],
    }).decision,
    'deny',
  );
  assert.equal(
    evaluateBoundaryWrite('/repo/release.keystore', {
      config,
      allowedProtectedPaths: ['release.keystore'],
    }).decision,
    'deny',
  );

  // Two-way Clean-Room Oracle Isolation:
  // 1) During S3a_ORACLE_TEST_GEN: ONLY oracle test files may be written; production code is blocked!
  assert.equal(
    evaluateBoundaryWrite('/repo/tests/oracle/xr-focus.oracle.test.cjs', {
      config,
      currentState: 'S3a_ORACLE_TEST_GEN',
      contractLocked: true,
      oracleLocked: false,
    }).decision,
    'allow',
  );
  assert.equal(
    evaluateBoundaryWrite('/repo/android/app/src/main/java/dev/koduki/kucode/MainActivity.kt', {
      config,
      currentState: 'S3a_ORACLE_TEST_GEN',
      contractLocked: true,
      oracleLocked: false,
    }).decision,
    'deny',
  );

  // 2) After lock-oracle (S3b onward): production code is allowed, but ALL oracle tests are hard-locked!
  assert.equal(
    evaluateBoundaryWrite('/repo/tests/oracle/xr-focus.oracle.test.cjs', {
      config,
      currentState: 'S3b_IMPL_WORKFLOW',
      contractLocked: true,
      oracleLocked: true,
      oracleTestFiles: ['tests/oracle/xr-focus.oracle.test.cjs'],
    }).decision,
    'deny',
  );
  assert.equal(
    evaluateBoundaryWrite('/repo/android/app/src/test/java/dev/koduki/kucode/oracle/XrFocusOracleTest.kt', {
      config,
      currentState: 'S3b_IMPL_WORKFLOW',
      contractLocked: true,
      oracleLocked: true,
    }).decision,
    'deny',
  );
  assert.equal(
    evaluateBoundaryWrite('/repo/android/app/src/main/java/dev/koduki/kucode/PosixRuntimeConfig.kt', {
      config,
      currentState: 'S3b_IMPL_WORKFLOW',
      contractLocked: true,
      oracleLocked: true,
    }).decision,
    'allow',
  );

  // Contracts editable before S2 approval, locked after
  assert.equal(evaluateBoundaryWrite('/repo/docs/specs/new-feat/spec.md', { config, contractLocked: false }).decision, 'allow');
  assert.equal(evaluateBoundaryWrite('/repo/docs/specs/new-feat/spec.md', { config, contractLocked: true }).decision, 'deny');
});

test('guard-commands blocks hook bypass, force push, secret prints, and Stable KuCode tampering', async () => {
  const { evaluateCommand } = await import('../.agents/skills/ship/scripts/guard-commands.mjs');

  assert.equal(evaluateCommand('git commit -m "x" --no-verify').decision, 'deny');
  assert.equal(evaluateCommand('git push --force origin feat/x').decision, 'deny');
  assert.equal(evaluateCommand('git reset --hard HEAD~1').decision, 'deny');
  assert.equal(evaluateCommand('adb uninstall dev.koduki.kucode').decision, 'deny');
  assert.equal(evaluateCommand('kucode android install app.apk dev.koduki.kucode').decision, 'deny');
  assert.equal(evaluateCommand('gh auth token').decision, 'deny');

  assert.equal(evaluateCommand('adb install -r app-debug.apk').decision, 'allow');
  assert.equal(evaluateCommand('kucode android install app-debug.apk dev.koduki.kucode.debug').decision, 'allow');
  assert.equal(evaluateCommand('node --test tests/agentic-harness.test.cjs').decision, 'allow');
});

test('/ship state machine enforces S3a oracle-before-impl lock, --oracle-passed, CI+Codex preconditions, retry caps, and Stop hook', async () => {
  const stateMod = await import('../.agents/skills/ship/scripts/state.mjs');
  const stopMod = await import('../.agents/skills/ship/scripts/stop-driver.mjs');
  const sessionInitMod = await import('../.agents/skills/ship/scripts/session-init.mjs');
  const tmp = makeTempDir('kucode-ship-state-');
  const statePath = path.join(tmp, '.ship', 'runs', 'feat_demo.json');

  try {
    stateMod.initRun(statePath, 'Add XR focus indicator', { cwd: REPO_ROOT });
    assert.throws(() => stateMod.transitionState(statePath, 'S3a_ORACLE_TEST_GEN'), /contractLocked=true/);

    stateMod.transitionState(statePath, 'S2_CONTRACT_LOCK');
    assert.equal(stopMod.evaluateStop({ fullyIdle: true }, statePath).decision, 'allow');

    // Approve contract -> enters S3a_ORACLE_TEST_GEN first (NOT S3b)
    const approved = stateMod.approveContract(statePath, {
      specPath: 'docs/specs/demo/spec.md',
      deployTarget: 'merge-only',
      allowedProtectedPaths: ['runtime-assets.lock.json'],
    });
    assert.equal(approved.contractLocked, true);
    assert.equal(approved.oracleLocked, false);
    assert.equal(approved.currentState, 'S3a_ORACLE_TEST_GEN');
    assert.equal(approved.gate, 'AUTO_PROCEED');

    // Cannot skip straight to S3b_IMPL_WORKFLOW before locking independent oracle tests!
    assert.throws(() => stateMod.transitionState(statePath, 'S3b_IMPL_WORKFLOW'), /oracle tests are authored and locked/);

    // lock-oracle rejects empty or unverified-Red oracle tests
    assert.throws(() => stateMod.lockOracle(statePath, { files: [] }), /requires at least one oracle test file/);
    assert.throws(
      () => stateMod.lockOracle(statePath, { files: ['tests/oracle/demo.oracle.test.cjs'], preImplRedVerified: false }),
      /pre-implementation Red failure/,
    );

    // Lock oracle with Red verification -> enters S3b_IMPL_WORKFLOW
    const locked = stateMod.lockOracle(statePath, {
      files: ['tests/oracle/demo.oracle.test.cjs'],
      preImplRedVerified: true,
    });
    assert.equal(locked.oracleLocked, true);
    assert.deepEqual(locked.oracleTestFiles, ['tests/oracle/demo.oracle.test.cjs']);
    assert.equal(locked.currentState, 'S3b_IMPL_WORKFLOW');

    // SessionStart hook surfaces active state
    const prevEnv = process.env.KUCODE_SHIP_STATE_FILE;
    process.env.KUCODE_SHIP_STATE_FILE = statePath;
    try {
      const initOut = sessionInitMod.runSessionInit(tmp);
      assert.equal(initOut.injectSteps.length, 1);
      assert.match(initOut.injectSteps[0].systemMessage.systemMessage, /S3b_IMPL_WORKFLOW/);
    } finally {
      if (prevEnv === undefined) delete process.env.KUCODE_SHIP_STATE_FILE;
      else process.env.KUCODE_SHIP_STATE_FILE = prevEnv;
    }

    // Stop hook auto-continues up to MAX_STALLS_PER_STATE then escalates
    for (let i = 0; i < stopMod.MAX_STALLS_PER_STATE; i++) {
      assert.equal(stopMod.evaluateStop({ fullyIdle: true }, statePath).decision, 'continue');
    }
    assert.equal(stopMod.evaluateStop({ fullyIdle: true }, statePath).decision, 'allow');
    assert.equal(stateMod.loadState(statePath).currentState, 'ESCALATED');

    // Resume after escalation returns to S3b because oracleLocked is already true
    const resumed = stateMod.approveContract(statePath);
    assert.equal(resumed.currentState, 'S3b_IMPL_WORKFLOW');

    // Cannot advance to S5_PR_CI_HEAL_LOOP without --oracle-passed
    assert.throws(() => stateMod.transitionState(statePath, 'S5_PR_CI_HEAL_LOOP', { prNumber: 99 }), /locked oracle tests pass/);

    // Advance with --oracle-passed -> S6 requires BOTH --ci-passed AND --codex-passed
    stateMod.transitionState(statePath, 'S5_PR_CI_HEAL_LOOP', { prNumber: 99, oraclePassed: true });
    assert.throws(() => stateMod.transitionState(statePath, 'S6_AUTO_MERGE_AND_DEPLOY'), /CI status is passed/);
    assert.throws(
      () => stateMod.transitionState(statePath, 'S6_AUTO_MERGE_AND_DEPLOY', { ciPassed: true }),
      /Codex PR review completes cleanly/,
    );

    // Retry cap (max 2) & 2-strikes guard promotion
    assert.equal(stateMod.recordRetry(statePath, 'ci', REPO_ROOT).exceeded, false);
    assert.equal(stateMod.recordRetry(statePath, 'ci', REPO_ROOT).exceeded, false);
    assert.equal(stateMod.recordRetry(statePath, 'ci', REPO_ROOT).exceeded, true);
    assert.equal(stateMod.recordFinding(statePath, 'acp-path-order', 'first hit').promoteToGuard, false);
    assert.equal(stateMod.recordFinding(statePath, 'acp-path-order', 'second hit').promoteToGuard, true);

    // Both gates passed -> S6 -> DONE
    stateMod.transitionState(statePath, 'S6_AUTO_MERGE_AND_DEPLOY', { ciPassed: true, codexPassed: true });
    stateMod.transitionState(statePath, 'DONE');
    assert.equal(stopMod.evaluateStop({ fullyIdle: true }, statePath).decision, 'allow');
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('run-oracle-tests discovers and executes Node/Python clean-room oracle suites and supports --require-non-empty', async () => {
  const { runOracleSuites } = await import('../scripts/guards/run-oracle-tests.mjs');
  const tmp = makeTempDir('kucode-oracle-runner-');
  try {
    // Empty repo passes by default, fails with requireNonEmpty
    assert.equal(runOracleSuites(tmp, { requireNonEmpty: false }).passed, true);
    assert.equal(runOracleSuites(tmp, { requireNonEmpty: true }).passed, false);

    // Add a valid Node oracle test referencing AC-1
    const oracleDir = path.join(tmp, 'tests', 'oracle');
    fs.mkdirSync(oracleDir, { recursive: true });
    fs.writeFileSync(
      path.join(oracleDir, 'demo.oracle.test.cjs'),
      "const test = require('node:test');\nconst assert = require('node:assert/strict');\ntest('[AC-1] contract passes', () => { assert.equal(1 + 1, 2); });\n",
      'utf8',
    );
    const res = runOracleSuites(tmp, { requireNonEmpty: true });
    assert.equal(res.passed, true);
    assert.equal(res.discovered.nodeFiles.length, 1);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('LogSage CI log compressor filters noise and classifies failures within token budget', async () => {
  const { compressCiLog, formatMarkdown } = await import('../scripts/guards/ci-log-compressor.mjs');
  const rawLog = [
    ...Array.from({ length: 120 }, (_, i) => `2026-11-26T01:00:00.000Z Download https://repo.maven.apache.org/pkg-${i}.jar`),
    '2026-11-26T01:01:00.000Z > Task :app:compileDebugKotlin FAILED',
    '2026-11-26T01:01:00.100Z e: file:///work/android/app/src/main/java/dev/koduki/kucode/AndroidHostBridge.kt:42:13 Unresolved reference: fooBar',
    '2026-11-26T01:01:00.200Z FAILURE: Build failed with an exception.',
  ].join('\n');

  const report = compressCiLog(rawLog);
  assert.equal(report.category, 'gradle_compile');
  assert.ok(report.rawLineCount > 120);
  assert.ok(report.keyErrorLines.length < 15);
  assert.ok(report.suspectedFiles.some((f) => f.includes('AndroidHostBridge.kt:42')));
  const md = formatMarkdown(report);
  assert.ok(md.includes('gradle_compile'));
  assert.ok(md.length < 4000);
});

test('Codex PR gate verdict halts auto-merge on timeout or Plus quota limit and allows auto-merge only when clean', async () => {
  const { parseCodexReviewOutput } = await import('../.agents/skills/ship/scripts/run-codex-review.mjs');
  const { extractCodexFindings, evaluateGateVerdict } = await import('../.agents/skills/ship/scripts/wait-pr-gates.mjs');

  const localParsed = parseCodexReviewOutput('P1: Missing NOFOLLOW_LINKS check in WorkspaceDocumentStore.kt\nVerdict: patch is incorrect');
  assert.equal(localParsed.verdict, 'issues_found');
  assert.equal(localParsed.p1Count, 1);

  const prBlocking = extractCodexFindings(
    [{ author: { login: 'chatgpt-codex-connector[bot]' }, state: 'COMMENTED', body: 'Found 1 P0 security regression' }],
    [{ user: { login: 'chatgpt-codex-connector[bot]' }, path: 'acp-webview.js', line: 88, body: 'P1: workspace override accepted' }],
    [{ author: { login: 'human-reviewer' }, body: 'LGTM' }],
  );
  assert.equal(prBlocking.blockingCount, 2);
  assert.equal(evaluateGateVerdict({ ciPassed: true, codex: prBlocking, timedOut: false }).nextAction, 'fix_codex_findings');

  const prQuota = extractCodexFindings([], [], [
    { author: { login: 'chatgpt-codex-connector[bot]' }, body: 'Codex reached your ChatGPT Plus usage limit for code review.' },
  ]);
  const quotaVerdict = evaluateGateVerdict({ ciPassed: true, codex: prQuota, timedOut: false });
  assert.equal(quotaVerdict.readyToAutoMerge, false);
  assert.equal(quotaVerdict.exitCode, 3);
  assert.equal(quotaVerdict.nextAction, 'escalate_human');

  const prSilent = extractCodexFindings([], [], []);
  const timeoutVerdict = evaluateGateVerdict({ ciPassed: true, codex: prSilent, timedOut: true });
  assert.equal(timeoutVerdict.readyToAutoMerge, false);
  assert.equal(timeoutVerdict.exitCode, 3);
  assert.equal(timeoutVerdict.nextAction, 'escalate_human');

  const prClean = extractCodexFindings(
    [{ author: { login: 'chatgpt-codex-connector[bot]' }, state: 'COMMENTED', body: 'No major issues found.' }],
    [],
    [],
  );
  const cleanVerdict = evaluateGateVerdict({ ciPassed: true, codex: prClean, timedOut: false });
  assert.equal(cleanVerdict.readyToAutoMerge, true);
  assert.equal(cleanVerdict.exitCode, 0);
  assert.equal(cleanVerdict.nextAction, 'auto_merge');
});

test('Flight Recorder analyzer parses both session directories and exported KuCode AI Markdown bundles', async () => {
  const { analyzeSessionDir, parseKuCodeAiMarkdownBundle } = await import('../.agents/skills/ship/scripts/analyze-flight-recorder.mjs');
  const tmp = makeTempDir('kucode-flight-recorder-');
  try {
    fs.writeFileSync(path.join(tmp, 'meta.json'), JSON.stringify({
      sessionId: 'sess-20261126-001',
      status: 'ENDED_ABNORMALLY',
      appVersion: '0.10.0-debug',
      packageName: 'dev.koduki.kucode.debug',
      buildSha: 'dc86353',
    }));
    fs.writeFileSync(path.join(tmp, 'events.alerts.jsonl'), [
      JSON.stringify({ level: 'WARN', source: 'launcher', msg: 'Port 18765 busy, retrying' }),
      JSON.stringify({ level: 'FATAL', source: 'proot', msg: 'SIGSEGV in guest bootstrap' }),
    ].join('\n'));
    fs.writeFileSync(path.join(tmp, 'metrics.jsonl'), [
      JSON.stringify({ pssMb: 320, freeDiskMb: 4096, lowMemory: false, serverAlive: true, thermalStatus: 'LIGHT' }),
      JSON.stringify({ pssMb: 512, freeDiskMb: 2048, lowMemory: true, serverAlive: false, thermalStatus: 'MODERATE' }),
    ].join('\n'));
    fs.writeFileSync(path.join(tmp, 'exit-trace.txt'), 'java.lang.IllegalStateException: PRoot exited 139\n  at OpenVsCodeLauncher.kt:210\n');
    fs.writeFileSync(path.join(tmp, 'vscode-sublogs.txt'), 'info ok\nERROR ptyhost crashed with SIGSEGV\n');

    const dirReport = analyzeSessionDir(tmp);
    assert.equal(dirReport.readOnly, true);
    assert.equal(dirReport.sessionId, 'sess-20261126-001');
    assert.equal(dirReport.abnormal, true);
    assert.equal(dirReport.alertsSummary.byLevel.FATAL, 1);
    assert.equal(dirReport.metricsSummary.maxPssMb, 512);

    const sampleMd = [
      '# KuCode AI セッション診断バンドル (Flight Recorder)',
      '- Schema: `2`',
      '- Session ID: `sess-aura-999`',
      '- Session Status: `ENDED_ABNORMALLY` (`CRASH_JVM`)',
      '- Exit / State Summary: `Uncaught IllegalStateException in RenderWorker`',
      '- Process PID: `4242` | App: `dev.koduki.kucode.debug 0.10.0-debug`',
      '- Device: `XREAL AURA` | Android: `15 (API 35)` | PageSize: `16384`',
      '',
      '## 1. AI トリアージ要約 (Anomaly & Error Digest)',
      '- Counters: `totalEvents=450, errors=2, warnings=3, knownBenignFiltered=12`',
      '- Detected Alerts (Chronological, up to 2):',
      '  - `[+00:12.100] [ERROR] [bridge]` Failed to open installer session',
      '  - `[+00:12.400] [FATAL] [jvm]` Uncaught IllegalStateException',
      '',
      '## 2. リソース & ヘルスメトリクス推移 (Metrics Timeline)',
      '| Offset | Trigger | Avail/Total RAM | PSS | Host CPU ms | JVM Heap | Native | FDs | Thr | DiskFree | Thermal | Bat | Net | State / View / Focus |',
      '|---|---|---|---|---|---|---|---|---|---|---|---|---|---|',
      '| +00:12.000 | `periodic` | 1200/8192MB! | 510MB | 12000 | 90/256MB | 40MB | 120 | 30 | 2048MB | MODERATE | 80% | wifi | `RUNNING/EDITOR/focus=true/srv=false` |',
      '',
      '## 3. クラッシュ / OS 終了トレース (Crash & ApplicationExitInfo Trace)',
      '```text',
      'java.lang.IllegalStateException: boom',
      '  at dev.koduki.kucode.AndroidHostBridge.kt:188',
      '```',
      '',
      '## 4. 統合イベント・標準出力・WebView・Logcat タイムライン',
      '```text',
      'RAW_TIMELINE_SHOULD_BE_OMITTED_FROM_DIGEST',
      '```',
      '',
      '## 5. OpenVSCode 内部ログ抜粋 (remoteagent / ptyhost / remoteexthost)',
      '```text',
      'INFO startup',
      'ERROR ptyhost exited unexpectedly',
      '```',
    ].join('\n');

    const mdReport = parseKuCodeAiMarkdownBundle(sampleMd, 'kucode-bundle.md');
    assert.equal(mdReport.format, 'kucode-ai-markdown-bundle');
    assert.equal(mdReport.sessionId, 'sess-aura-999');
    assert.equal(mdReport.exitCategory, 'CRASH_JVM');
    assert.equal(mdReport.abnormal, true);
    assert.equal(mdReport.alerts.length, 2);
    assert.equal(mdReport.anomalousMetricRows.length, 1);
    assert.equal(mdReport.exitTraceHead.length, 2);
    assert.equal(mdReport.vscodeSublogErrors.length, 1);
    assert.equal(mdReport.omittedRawTimelineSection4, true);
    assert.equal(JSON.stringify(mdReport).includes('RAW_TIMELINE_SHOULD_BE_OMITTED_FROM_DIGEST'), false);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

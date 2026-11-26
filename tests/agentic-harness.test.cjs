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

test('run-all-guards passes cleanly on the live KuCode repository', async () => {
  const { runGuards } = await import('../scripts/guards/run-all-guards.mjs');
  const report = runGuards(REPO_ROOT, { changedOnly: false });
  assert.equal(report.passed, true, `Unexpected guard violations: ${JSON.stringify(report.violations, null, 2)}`);
  assert.ok(report.checkedFiles > 20);
});

test('run-all-guards blocks suppressions, skips, trust regressions, module leaks, build drift, and secrets', async () => {
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
    ]) {
      assert.ok(ids.has(expected), `Expected violation ${expected}, got: ${Array.from(ids).join(', ')}`);
    }
    assert.ok(report.violations.every((v) => v.remediation && v.remediation.length > 10));
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('guard-boundaries locks immutable files always and locks contracts only after S2 approval', async () => {
  const { evaluateBoundaryWrite } = await import('../.agents/skills/ship/scripts/guard-boundaries.mjs');

  assert.equal(evaluateBoundaryWrite('/repo/.github/workflows/ci.yml', { contractLocked: false }).decision, 'deny');
  assert.equal(evaluateBoundaryWrite('/repo/runtime-assets.lock.json', { contractLocked: false }).decision, 'deny');
  assert.equal(evaluateBoundaryWrite('/repo/scripts/guards/run-all-guards.mjs', { contractLocked: false }).decision, 'deny');
  assert.equal(evaluateBoundaryWrite('/repo/THIRD_PARTY_NOTICES.md', { contractLocked: false }).decision, 'deny');

  // Contract files editable during S0-S2, blocked once contractLocked=true
  assert.equal(evaluateBoundaryWrite('/repo/docs/specs/new-feat/spec.md', { contractLocked: false }).decision, 'allow');
  assert.equal(evaluateBoundaryWrite('/repo/docs/specs/new-feat/spec.md', { contractLocked: true }).decision, 'deny');
  assert.equal(evaluateBoundaryWrite('/repo/spec.md', { contractLocked: true }).decision, 'deny');

  // Normal implementation files remain allowed
  assert.equal(evaluateBoundaryWrite('/repo/android/app/src/main/java/dev/koduki/kucode/PosixRuntimeConfig.kt', { contractLocked: true }).decision, 'allow');
});

test('guard-commands blocks hook bypass, force push, secret prints, and Stable KuCode tampering', async () => {
  const { evaluateCommand } = await import('../.agents/skills/ship/scripts/guard-commands.mjs');

  assert.equal(evaluateCommand('git commit -m "x" --no-verify').decision, 'deny');
  assert.equal(evaluateCommand('git push --force origin feat/x').decision, 'deny');
  assert.equal(evaluateCommand('git reset --hard HEAD~1').decision, 'deny');
  assert.equal(evaluateCommand('adb uninstall dev.koduki.kucode').decision, 'deny');
  assert.equal(evaluateCommand('kucode android install app.apk dev.koduki.kucode').decision, 'deny');
  assert.equal(evaluateCommand('gh auth token').decision, 'deny');

  // Safe commands & Dev package targeting remain allowed
  assert.equal(evaluateCommand('adb install -r app-debug.apk').decision, 'allow');
  assert.equal(evaluateCommand('kucode android install app-debug.apk dev.koduki.kucode.debug').decision, 'allow');
  assert.equal(evaluateCommand('node --test tests/agentic-harness.test.cjs').decision, 'allow');
});

test('/ship state machine enforces preconditions, retry caps, 2-strikes guard promotion, and Stop hook driver', async () => {
  const stateMod = await import('../.agents/skills/ship/scripts/state.mjs');
  const stopMod = await import('../.agents/skills/ship/scripts/stop-driver.mjs');
  const tmp = makeTempDir('kucode-ship-state-');
  const statePath = path.join(tmp, '.ship', 'runs', 'feat_demo.json');

  try {
    stateMod.initRun(statePath, 'Add XR focus indicator');
    // Cannot jump to S3 before contract approval
    assert.throws(() => stateMod.transitionState(statePath, 'S3_IMPL_WORKFLOW'), /contractLocked=true/);

    // Move to S2 (WAITING_HUMAN) -> Stop hook allows stopping
    stateMod.transitionState(statePath, 'S2_CONTRACT_LOCK');
    assert.equal(stopMod.evaluateStop({ fullyIdle: true }, statePath).decision, 'allow');

    // Approve contract -> S3_IMPL_WORKFLOW + AUTO_PROCEED + contractLocked=true
    const approved = stateMod.approveContract(statePath, { specPath: 'docs/specs/demo/spec.md', deployTarget: 'merge-only' });
    assert.equal(approved.contractLocked, true);
    assert.equal(approved.currentState, 'S3_IMPL_WORKFLOW');
    assert.equal(approved.gate, 'AUTO_PROCEED');

    // Stop hook forces continuation up to MAX_STALLS_PER_STATE, then escalates safely
    for (let i = 0; i < stopMod.MAX_STALLS_PER_STATE; i++) {
      assert.equal(stopMod.evaluateStop({ fullyIdle: true }, statePath).decision, 'continue');
    }
    assert.equal(stopMod.evaluateStop({ fullyIdle: true }, statePath).decision, 'allow');
    assert.equal(stateMod.loadState(statePath).currentState, 'ESCALATED');

    // Resume after escalation -> cannot jump to S6 without CI passed
    stateMod.approveContract(statePath);
    stateMod.transitionState(statePath, 'S5_PR_CI_HEAL_LOOP', { prNumber: 99 });
    assert.throws(() => stateMod.transitionState(statePath, 'S6_AUTO_MERGE_AND_DEPLOY'), /CI status is passed/);

    // Retry cap (max 2)
    assert.equal(stateMod.recordRetry(statePath, 'ci').exceeded, false);
    assert.equal(stateMod.recordRetry(statePath, 'ci').exceeded, false);
    assert.equal(stateMod.recordRetry(statePath, 'ci').exceeded, true);

    // 2 strikes -> promoteToGuard
    assert.equal(stateMod.recordFinding(statePath, 'acp-path-order', 'first hit').promoteToGuard, false);
    assert.equal(stateMod.recordFinding(statePath, 'acp-path-order', 'second hit').promoteToGuard, true);

    // Complete cleanly with CI passed
    stateMod.transitionState(statePath, 'S6_AUTO_MERGE_AND_DEPLOY', { ciPassed: true });
    stateMod.transitionState(statePath, 'DONE');
    assert.equal(stopMod.evaluateStop({ fullyIdle: true }, statePath).decision, 'allow');
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

test('Codex CLI parser and PR gate extractor detect P0/P1 findings cleanly', async () => {
  const { parseCodexReviewOutput } = await import('../.agents/skills/ship/scripts/run-codex-review.mjs');
  const { extractCodexFindings } = await import('../.agents/skills/ship/scripts/wait-pr-gates.mjs');

  const localParsed = parseCodexReviewOutput('P1: Missing NOFOLLOW_LINKS check in WorkspaceDocumentStore.kt\nVerdict: patch is incorrect');
  assert.equal(localParsed.verdict, 'issues_found');
  assert.equal(localParsed.p1Count, 1);

  const prParsed = extractCodexFindings(
    [{ author: { login: 'chatgpt-codex-connector[bot]' }, state: 'COMMENTED', body: 'Found 1 P0 security regression' }],
    [{ user: { login: 'chatgpt-codex-connector[bot]' }, path: 'acp-webview.js', line: 88, body: 'P1: workspace override accepted' }],
    [{ author: { login: 'human-reviewer' }, body: 'LGTM' }],
  );
  assert.equal(prParsed.totalCodexItems, 2);
  assert.equal(prParsed.blockingCount, 2);
});

test('Flight Recorder Read-Only analyzer correlates meta, alerts, metrics, and traces by sessionId', async () => {
  const { analyzeSessionDir } = await import('../.agents/skills/ship/scripts/analyze-flight-recorder.mjs');
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

    const report = analyzeSessionDir(tmp);
    assert.equal(report.readOnly, true);
    assert.equal(report.sessionId, 'sess-20261126-001');
    assert.equal(report.abnormal, true);
    assert.equal(report.alertsSummary.byLevel.FATAL, 1);
    assert.equal(report.metricsSummary.maxPssMb, 512);
    assert.equal(report.metricsSummary.serverDownSnapshots, 1);
    assert.equal(report.exitTraceHead.length, 2);
    assert.equal(report.vscodeSublogErrors.length, 1);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

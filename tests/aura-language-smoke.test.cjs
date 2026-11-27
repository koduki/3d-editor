'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const { promisify } = require('node:util');

const execFileAsync = promisify(cp.execFile);

const REPO_ROOT = path.resolve(__dirname, '..');
const SMOKE_SCRIPT = path.join(REPO_ROOT, 'scripts/aura-language-smoke.sh');

function writeExec(dir, name, content) {
  const filePath = path.join(dir, name);
  fs.writeFileSync(filePath, content, { encoding: 'utf8', mode: 0o755 });
  return filePath;
}

test('aura-language-smoke.sh rejects unknown flags with exit code 2', async (t) => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kucode-smoke-flag-'));
  t.after(() => fs.rmSync(tmp, { recursive: true, force: true }));

  await assert.rejects(
    () => execFileAsync('bash', [SMOKE_SCRIPT, '--allow-non-aura', '--bogus-flag', path.join(tmp, 'out')], {
      cwd: REPO_ROOT,
    }),
    (err) => err && err.code === 2,
  );
});

test('aura-language-smoke.sh aborts with exit 2 on non-ARM64 unless --allow-non-aura or --ci-self-test is given', async (t) => {
  if (process.arch === 'arm64') return;
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kucode-smoke-arch-'));
  t.after(() => fs.rmSync(tmp, { recursive: true, force: true }));

  const outDir = path.join(tmp, 'out');
  await assert.rejects(
    () => execFileAsync('bash', [SMOKE_SCRIPT, outDir], { cwd: REPO_ROOT }),
    (err) => err && err.code === 2 && /NOT RUN: AURA native ARM64 is required/.test((err.stdout || '') + (err.stderr || '')),
  );
});

test('aura-language-smoke.sh records PASS, FAIL, NOT RUN, sanitized versions, 5-col results.tsv, and summary.json', async (t) => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kucode-smoke-run-'));
  t.after(() => fs.rmSync(tmp, { recursive: true, force: true }));

  const fakeBin = path.join(tmp, 'bin');
  fs.mkdirSync(fakeBin, { recursive: true });
  fs.symlinkSync(process.execPath, path.join(fakeBin, 'node'));
  writeExec(fakeBin, 'npm', '#!/bin/sh\necho "10.9.2"\n');
  writeExec(fakeBin, 'go', '#!/bin/sh\nif [ "$1" = "version" ]; then echo "go version go1.23.1\tlinux/amd64"; exit 0; fi\nexit 1\n');

  const outDir = path.join(tmp, 'evidence');
  let exitCode = 0;
  try {
    await execFileAsync('bash', [SMOKE_SCRIPT, '--ci-self-test', '--output', outDir], {
      cwd: REPO_ROOT,
      env: {
        PATH: `${fakeBin}:/usr/bin:/bin`,
        HOME: tmp,
      },
    });
  } catch (err) {
    exitCode = err.code;
  }

  assert.equal(exitCode, 1);

  const envText = fs.readFileSync(path.join(outDir, 'environment.txt'), 'utf8');
  assert.match(envText, /network_mode=offline/);

  const tsv = fs.readFileSync(path.join(outDir, 'results.tsv'), 'utf8');
  const lines = tsv.trim().split('\n');
  assert.equal(lines[0], 'test\tresult\tduration_seconds\tversion\texecution_mode');
  assert.equal(lines.length, 10);

  const rows = Object.fromEntries(
    lines.slice(1).map((line) => {
      const cols = line.split('\t');
      assert.equal(cols.length, 5, `Row must have 5 columns: ${line}`);
      return [cols[0], { result: cols[1], duration: cols[2], version: cols[3], executionMode: cols[4] }];
    }),
  );

  assert.equal(rows.javascript.result, 'PASS');
  assert.equal(rows.javascript.version, process.version);
  assert.equal(rows.go.result, 'FAIL');
  assert.equal(rows.go.version, 'go version go1.23.1 linux/amd64');
  assert.equal(rows.rust.result, 'NOT RUN');
  assert.equal(rows.rust.version, 'not-installed');

  const summary = JSON.parse(fs.readFileSync(path.join(outDir, 'summary.json'), 'utf8'));
  assert.equal(summary.schema, 1);
  assert.equal(summary.networkMode, 'offline');
  assert.equal(summary.exitCode, 1);
  assert.equal(summary.counts.total, 9);
  assert.ok(summary.counts.pass >= 1);
  assert.ok(summary.counts.fail >= 1);
  assert.ok(summary.counts.notRun >= 1);
  assert.equal(summary.counts.pass + summary.counts.fail + summary.counts.notRun, summary.counts.total);
  assert.equal(summary.results.find((r) => r.test === 'javascript').logFile, 'javascript.log');
  assert.ok(fs.existsSync(path.join(outDir, 'javascript.log')));
});

test('aura-language-smoke.sh supports --with-network and KUCODE_SMOKE_NETWORK=1 against loopback URL', async (t) => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kucode-smoke-net-'));
  t.after(() => fs.rmSync(tmp, { recursive: true, force: true }));

  const fakeBin = path.join(tmp, 'bin');
  fs.mkdirSync(fakeBin, { recursive: true });
  fs.symlinkSync(process.execPath, path.join(fakeBin, 'node'));
  writeExec(fakeBin, 'npm', '#!/bin/sh\necho "10.9.2"\n');

  let hits = 0;
  const server = http.createServer((_req, res) => {
    hits += 1;
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('ok');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => server.close());

  const netUrl = `http://127.0.0.1:${server.address().port}/ping`;
  const outWithFlag = path.join(tmp, 'out-flag');
  try {
    await execFileAsync(
      'bash',
      [SMOKE_SCRIPT, '--allow-non-aura', '--with-network', `--output=${outWithFlag}`],
      {
        cwd: REPO_ROOT,
        env: {
          PATH: `${fakeBin}:/usr/bin:/bin`,
          HOME: tmp,
          KUCODE_SMOKE_NETWORK_URL: netUrl,
        },
      },
    );
  } catch (_) {}

  assert.ok(hits >= 1);
  const summaryFlag = JSON.parse(fs.readFileSync(path.join(outWithFlag, 'summary.json'), 'utf8'));
  assert.equal(summaryFlag.networkMode, 'with-network');
  assert.equal(summaryFlag.results.find((r) => r.test === 'network').result, 'PASS');

  const outWithEnv = path.join(tmp, 'out-env');
  try {
    await execFileAsync(
      'bash',
      [SMOKE_SCRIPT, '--allow-non-aura', outWithEnv],
      {
        cwd: REPO_ROOT,
        env: {
          PATH: `${fakeBin}:/usr/bin:/bin`,
          HOME: tmp,
          KUCODE_SMOKE_NETWORK: '1',
          KUCODE_SMOKE_NETWORK_URL: netUrl,
        },
      },
    );
  } catch (_) {}

  assert.ok(hits >= 2);
  const summaryEnv = JSON.parse(fs.readFileSync(path.join(outWithEnv, 'summary.json'), 'utf8'));
  assert.equal(summaryEnv.networkMode, 'with-network');
  assert.equal(summaryEnv.results.find((r) => r.test === 'network').result, 'PASS');
});

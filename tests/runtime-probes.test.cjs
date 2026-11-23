'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const probeFile = path.resolve(__dirname, '../android/app/src/main/assets/runtime-probes.cjs');
const { checkPty, checkWatcher } = require(probeFile);

function workspace(t) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'xr-tests-'));
    t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
    return dir;
}

test('Android stdin entry point actually dispatches a probe', () => {
    const result = spawnSync(process.execPath, ['-', 'unexpected'], { input: fs.readFileSync(probeFile), encoding: 'utf8' });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /Unknown probe/);
});

test('an unrelated watcher event fails and releases only probe files', async t => {
    const dir = workspace(t);
    fs.writeFileSync(path.join(dir, 'keep'), 'user data');
    let closed = false;
    const watcher = {
        async subscribe(root, onEvent) {
            onEvent(null, [{ type: 'create', path: path.join(root, 'other') }]);
            return { async unsubscribe() { closed = true; } };
        },
    };
    await assert.rejects(checkWatcher(watcher, dir, 30), /No create event/);
    assert.equal(closed, true);
    assert.deepEqual(fs.readdirSync(dir), ['keep']);
    assert.equal(fs.readFileSync(path.join(dir, 'keep'), 'utf8'), 'user data');
});

test('PTY echo alone cannot pass the shell and size check', async () => {
    let data;
    let killed = false;
    const terminal = {
        onData(handler) { data = handler; },
        onExit() { setImmediate(() => data('XR_PTY_READY\n')); },
        resize() {},
        write(input) { data(input); },
        kill() { killed = true; },
    };
    await assert.rejects(checkPty({ spawn: () => terminal }, 40), /acknowledgement missing/);
    assert.equal(killed, true);
});

const modules = process.env.XR_NATIVE_MODULES;
test('OpenVSCode node-pty input and resize round trip', { skip: !modules }, async () => {
    await checkPty(require(path.join(modules, 'node-pty')));
});

test('OpenVSCode watcher receives workspace events in two simultaneous runs', { skip: !modules }, async t => {
    const dir = workspace(t);
    const watcher = require(path.join(modules, '@parcel/watcher'));
    await Promise.all([checkWatcher(watcher, dir), checkWatcher(watcher, dir)]);
    assert.deepEqual(fs.readdirSync(dir), []);
});

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');

async function checkWatcher(watcher, workspace, timeoutMs = 6000) {
    const dir = fs.mkdtempSync(path.join(workspace, '.xr-watch-'));
    const marker = path.join(dir, 'marker');
    let subscription;
    let timer;
    const cleanup = () => {
        if (fs.existsSync(marker)) fs.unlinkSync(marker);
        fs.rmdirSync(dir);
    };
    // Also remove our own files if the native module hangs and the CLI watchdog exits.
    process.once('exit', cleanup);
    try {
        let onEvent;
        const received = new Promise((resolve, reject) => {
            onEvent = (error, events) => {
                if (error) reject(error);
                else if (events.some(e => e.type === 'create' && e.path === marker)) resolve();
            };
        });
        // A native error can arrive before subscribe finishes.
        received.catch(() => {});
        subscription = await watcher.subscribe(dir, onEvent);
        fs.writeFileSync(marker, 'watch', { flag: 'wx' });
        await Promise.race([received, new Promise((_, reject) => {
            timer = setTimeout(() => reject(new Error('No create event for the workspace file')), timeoutMs);
        })]);
    } finally {
        clearTimeout(timer);
        try {
            if (subscription) await subscription.unsubscribe();
        } finally {
            process.removeListener('exit', cleanup);
            cleanup();
        }
    }
}

async function checkPty(pty, timeoutMs = 6000) {
    const nonce = randomUUID();
    const command = [
        'set -eu',
        'stty -echo',
        'echo XR_PTY_READY',
        'IFS= read -r input',
        'printf "XR_PTY_ACK %s %s\\n" "$(stty size)" "$input"',
    ].join('\n');
    const terminal = pty.spawn('/bin/sh', ['-c', command], { cols: 80, rows: 24 });
    let timer;
    let exited = false;
    let sent = false;
    let output = '';
    try {
        await new Promise((resolve, reject) => {
            const check = () => {
                if (exited && output.includes('XR_PTY_ACK 37 103 ' + nonce)) resolve();
            };
            timer = setTimeout(() => reject(new Error('PTY input, resize or exit acknowledgement missing')), timeoutMs);
            terminal.onData(chunk => {
                output = (output + chunk).slice(-4096);
                if (!sent && output.includes('XR_PTY_READY')) {
                    sent = true;
                    try {
                        terminal.resize(103, 37);
                        terminal.write(nonce + '\r');
                    } catch (error) {
                        reject(error);
                    }
                }
                check();
            });
            terminal.onExit(({ exitCode, signal }) => {
                exited = true;
                if (exitCode !== 0 || signal) reject(new Error('PTY exited: ' + exitCode + ', signal=' + signal));
                else check();
            });
        });
    } finally {
        clearTimeout(timer);
        if (!exited) terminal.kill();
    }
}

module.exports = { checkWatcher, checkPty };

if (process.argv[1] === '-' || require.main === module) {
    const modules = '/opt/openvscode-server/node_modules/';
    const timeout = setTimeout(() => { console.error('Native probe timed out'); process.exit(124); }, 10000);
    (async () => {
        const mode = process.argv[2];
        if (mode === 'watcher') {
            await checkWatcher(require(modules + '@parcel/watcher'), '/workspace');
            console.log('Workspace file create event received');
        } else if (mode === 'pty') {
            await checkPty(require(modules + 'node-pty'));
            console.log('PTY input echoed by shell with resized rows and columns');
        } else {
            throw new Error('Unknown probe');
        }
    })().catch(error => { console.error(error.message); process.exitCode = 1; })
        .finally(() => clearTimeout(timeout));
}

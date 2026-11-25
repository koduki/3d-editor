'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const Module = require('node:module');

const extDir = path.resolve(__dirname, '../android/app/src/main/assets/kucode-tools');
const acpClientPath = path.join(extDir, 'acp-client.js');
const acpWebviewPath = path.join(extDir, 'acp-webview.js');

const {
  PROTOCOL_VERSION,
  DEFAULT_AGENT_PRESETS,
  resolveSafeWorkspacePath,
  sliceLines,
  AcpSessionClient,
} = require(acpClientPath);

/**
 * Writes a self-contained mock ACP v1 agent script to disk.
 * Exercises initialize, session/new, session/prompt, streaming session/update,
 * session/request_permission, fs/read_text_file, fs/write_text_file,
 * terminal/{create,wait_for_exit,output,release}, and session/cancel.
 */
function createMockAcpAgentScript(tempDir) {
  const scriptPath = path.join(tempDir, 'mock-acp-agent.cjs');
  const script = `'use strict';
const readline = require('node:readline');
const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });

let nextReqId = 1;
const pendingClientCalls = new Map();
let activePromptId = null;
let activeCancelTimer = null;
let cancelledCurrent = false;

function send(obj) {
  process.stdout.write(JSON.stringify(obj) + '\\n');
}

function callClient(method, params) {
  return new Promise((resolve, reject) => {
    const id = 'agent-' + (nextReqId++);
    pendingClientCalls.set(id, { resolve, reject });
    send({ jsonrpc: '2.0', id, method, params });
  });
}

rl.on('line', async (line) => {
  const trimmed = line.trim();
  if (!trimmed) return;
  const msg = JSON.parse(trimmed);

  // Client response to an agent-initiated request
  if (msg.id !== undefined && msg.method === undefined) {
    const p = pendingClientCalls.get(msg.id);
    if (p) {
      pendingClientCalls.delete(msg.id);
      if (msg.error) p.reject(Object.assign(new Error(msg.error.message), { code: msg.error.code }));
      else p.resolve(msg.result);
    }
    return;
  }

  if (msg.method === 'initialize') {
    send({
      jsonrpc: '2.0',
      id: msg.id,
      result: {
        protocolVersion: 1,
        agentInfo: { name: 'mock-acp-agent', version: '1.0.0' },
        agentCapabilities: { loadSession: false },
        authMethods: [],
      },
    });
    return;
  }

  if (msg.method === 'session/new') {
    send({
      jsonrpc: '2.0',
      id: msg.id,
      result: {
        sessionId: 'sess-mock-001',
        modes: { currentModeId: 'code', availableModes: [{ id: 'code', name: 'Code' }] },
      },
    });
    send({
      jsonrpc: '2.0',
      method: 'session/update',
      params: {
        sessionId: 'sess-mock-001',
        update: {
          sessionUpdate: 'available_commands_update',
          availableCommands: [{ name: 'review', description: 'Code review' }],
        },
      },
    });
    return;
  }

  if (msg.method === 'session/cancel') {
    cancelledCurrent = true;
    if (activeCancelTimer) {
      clearTimeout(activeCancelTimer);
      activeCancelTimer = null;
    }
    if (activePromptId !== null) {
      const id = activePromptId;
      activePromptId = null;
      send({ jsonrpc: '2.0', id, result: { stopReason: 'cancelled' } });
    }
    return;
  }

  if (msg.method === 'session/prompt') {
    cancelledCurrent = false;
    const text = (msg.params && msg.params.prompt || []).map((b) => b.text || '').join('\\n');

    if (text.includes('TRIGGER_CANCEL')) {
      activePromptId = msg.id;
      send({
        jsonrpc: '2.0',
        method: 'session/update',
        params: {
          sessionId: 'sess-mock-001',
          update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'working...' } },
        },
      });
      activeCancelTimer = setTimeout(() => {
        if (activePromptId !== null) {
          const id = activePromptId;
          activePromptId = null;
          send({ jsonrpc: '2.0', id, result: { stopReason: 'end_turn' } });
        }
      }, 5000);
      return;
    }

    if (text.includes('TRIGGER_FULL_FLOW')) {
      send({
        jsonrpc: '2.0',
        method: 'session/update',
        params: {
          sessionId: 'sess-mock-001',
          update: { sessionUpdate: 'agent_thought_chunk', content: { type: 'text', text: 'Inspecting files' } },
        },
      });
      send({
        jsonrpc: '2.0',
        method: 'session/update',
        params: {
          sessionId: 'sess-mock-001',
          update: {
            sessionUpdate: 'tool_call',
            toolCallId: 'tc-1',
            title: 'Read & Edit hello.txt',
            kind: 'edit',
            status: 'in_progress',
          },
        },
      });

      const perm = await callClient('session/request_permission', {
        sessionId: 'sess-mock-001',
        toolCall: { toolCallId: 'tc-1', title: 'Write hello.txt', rawInput: { path: 'hello.txt' } },
        options: [
          { optionId: 'allow-once', name: 'Allow once', kind: 'allow_once' },
          { optionId: 'reject-once', name: 'Reject', kind: 'reject_once' },
        ],
      });

      if (!perm || !perm.outcome || perm.outcome.outcome !== 'selected' || perm.outcome.optionId !== 'allow-once') {
        send({ jsonrpc: '2.0', id: msg.id, result: { stopReason: 'refusal' } });
        return;
      }

      const readRes = await callClient('fs/read_text_file', {
        sessionId: 'sess-mock-001',
        path: 'hello.txt',
        line: 2,
        limit: 2,
      });

      // Verify path traversal is rejected by client
      let traversalBlocked = false;
      try {
        await callClient('fs/read_text_file', { sessionId: 'sess-mock-001', path: '../escape.txt' });
      } catch (e) {
        traversalBlocked = true;
      }

      await callClient('fs/write_text_file', {
        sessionId: 'sess-mock-001',
        path: 'sub/output.txt',
        content: 'slice=' + readRes.content + '|blocked=' + traversalBlocked,
      });

      // Run terminal lifecycle
      const created = await callClient('terminal/create', {
        sessionId: 'sess-mock-001',
        command: process.execPath,
        args: ['-e', 'console.log("acp-term-ok")'],
      });
      const exitInfo = await callClient('terminal/wait_for_exit', {
        sessionId: 'sess-mock-001',
        terminalId: created.terminalId,
      });
      const termOut = await callClient('terminal/output', {
        sessionId: 'sess-mock-001',
        terminalId: created.terminalId,
      });
      await callClient('terminal/release', {
        sessionId: 'sess-mock-001',
        terminalId: created.terminalId,
      });

      send({
        jsonrpc: '2.0',
        method: 'session/update',
        params: {
          sessionId: 'sess-mock-001',
          update: {
            sessionUpdate: 'tool_call_update',
            toolCallId: 'tc-1',
            status: 'completed',
          },
        },
      });
      send({
        jsonrpc: '2.0',
        method: 'session/update',
        params: {
          sessionId: 'sess-mock-001',
          update: {
            sessionUpdate: 'agent_message_chunk',
            content: {
              type: 'text',
              text: 'DONE exit=' + exitInfo.exitCode + ' out=' + termOut.output.trim() + ' blocked=' + traversalBlocked,
            },
          },
        },
      });
      send({ jsonrpc: '2.0', id: msg.id, result: { stopReason: 'end_turn' } });
      return;
    }

    send({
      jsonrpc: '2.0',
      method: 'session/update',
      params: {
        sessionId: 'sess-mock-001',
        update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'ECHO: ' + text } },
      },
    });
    send({ jsonrpc: '2.0', id: msg.id, result: { stopReason: 'end_turn' } });
  }
});
`;
  fs.writeFileSync(scriptPath, script, 'utf8');
  return scriptPath;
}

test('resolveSafeWorkspacePath enforces workspace boundary and blocks traversal and symlink escape', (t) => {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), 'kucode-acp-path-'));
  t.after(() => fs.rmSync(base, { recursive: true, force: true }));

  const workspace = path.join(base, 'workspace');
  const outside = path.join(base, 'outside');
  fs.mkdirSync(path.join(workspace, 'src'), { recursive: true });
  fs.mkdirSync(outside, { recursive: true });
  fs.writeFileSync(path.join(outside, 'secret.txt'), 'TOP_SECRET', 'utf8');
  fs.symlinkSync(outside, path.join(workspace, 'escape-link'));

  const inside = resolveSafeWorkspacePath(workspace, 'src/new-file.js');
  assert.ok(inside.startsWith(fs.realpathSync(workspace)));
  assert.ok(inside.endsWith(path.join('src', 'new-file.js')));

  assert.throws(() => resolveSafeWorkspacePath(workspace, '../outside/secret.txt'), /escapes workspace/);
  assert.throws(() => resolveSafeWorkspacePath(workspace, path.join(outside, 'secret.txt')), /escapes workspace/);
  assert.throws(() => resolveSafeWorkspacePath(workspace, 'escape-link/secret.txt'), /Symlink escapes workspace/);
  assert.throws(() => resolveSafeWorkspacePath(workspace, 'a\0b'), /Invalid file path/);
});

test('sliceLines slices 1-based line ranges cleanly', () => {
  const sample = 'line1\nline2\nline3\nline4';
  assert.equal(sliceLines(sample, 2, 2), 'line2\nline3');
  assert.equal(sliceLines(sample, 3), 'line3\nline4');
  assert.equal(sliceLines(sample), sample);
});

test('AcpSessionClient completes full ACP v1 handshake, fs, terminal, permission, and cancel flow', async (t) => {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), 'kucode-acp-e2e-'));
  t.after(() => fs.rmSync(base, { recursive: true, force: true }));

  const workspace = path.join(base, 'workspace');
  fs.mkdirSync(workspace, { recursive: true });
  fs.writeFileSync(path.join(workspace, 'hello.txt'), 'L1\nL2\nL3\nL4\n', 'utf8');
  fs.writeFileSync(path.join(base, 'escape.txt'), 'SHOULD_NOT_BE_READ', 'utf8');

  const agentScript = createMockAcpAgentScript(base);
  const updates = [];
  const permissionsSeen = [];

  const client = new AcpSessionClient({
    command: process.execPath,
    args: [agentScript],
    workspaceRoot: workspace,
    onPermissionRequest: async (params) => {
      permissionsSeen.push(params);
      return { outcome: { outcome: 'selected', optionId: 'allow-once' } };
    },
  });
  t.after(async () => {
    await client.dispose();
  });

  client.on('sessionUpdate', (params) => updates.push(params));

  assert.equal(PROTOCOL_VERSION, 1);
  assert.ok(DEFAULT_AGENT_PRESETS.copilot);
  assert.deepEqual(DEFAULT_AGENT_PRESETS.copilot.args, ['--acp', '--stdio']);

  const ready = await client.connect();
  assert.equal(ready.sessionId, 'sess-mock-001');
  assert.equal(ready.agentInfo.name, 'mock-acp-agent');

  // 1. Full flow: thought + tool call + permission + fs read/write + traversal block + terminal + message
  const res = await client.prompt('TRIGGER_FULL_FLOW');
  assert.equal(res.stopReason, 'end_turn');
  assert.equal(permissionsSeen.length, 1);
  assert.equal(permissionsSeen[0].toolCall.title, 'Write hello.txt');

  const written = fs.readFileSync(path.join(workspace, 'sub', 'output.txt'), 'utf8');
  assert.equal(written, 'slice=L2\nL3|blocked=true');

  const msgChunks = updates
    .map((u) => u.update)
    .filter((u) => u && u.sessionUpdate === 'agent_message_chunk')
    .map((u) => u.content && u.content.text)
    .join('');
  assert.match(msgChunks, /DONE exit=0 out=acp-term-ok blocked=true/);
  assert.ok(updates.some((u) => u.update && u.update.sessionUpdate === 'available_commands_update'));
  assert.ok(updates.some((u) => u.update && u.update.sessionUpdate === 'tool_call_update' && u.update.status === 'completed'));

  // 2. Active turn cancellation via session/cancel
  const cancelPromise = client.prompt('TRIGGER_CANCEL');
  await new Promise((r) => setTimeout(r, 80));
  client.cancel();
  const cancelRes = await cancelPromise;
  assert.equal(cancelRes.stopReason, 'cancelled');
});

test('AcpChatViewProvider renders strict CSP OST webview and completes prompt roundtrip', async (t) => {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), 'kucode-acp-wv-'));
  t.after(() => fs.rmSync(base, { recursive: true, force: true }));

  const workspace = path.join(base, 'workspace');
  fs.mkdirSync(workspace, { recursive: true });
  const agentScript = createMockAcpAgentScript(base);

  const postedMessages = [];
  let messageListener = null;
  let disposeListener = null;

  const mockVscode = {
    workspace: {
      workspaceFolders: [{ uri: { fsPath: workspace } }],
      textDocuments: [],
      getConfiguration() {
        return {
          get(key) {
            if (key === 'acp.defaultAgent') return 'mock';
            if (key === 'acp.permissionMode') return 'ask';
            if (key === 'acp.agents') {
              return {
                mock: {
                  label: 'Mock ACP',
                  command: process.execPath,
                  args: [agentScript],
                },
              };
            }
            return undefined;
          },
        };
      },
    },
    window: {
      activeTextEditor: null,
      showInformationMessage() {},
      showErrorMessage() {},
    },
    env: { clipboard: { async writeText() {} } },
  };

  delete require.cache[acpWebviewPath];
  const origLoad = Module._load;
  Module._load = function patchedLoad(request, parent, isMain) {
    if (request === 'vscode') return mockVscode;
    return origLoad.call(this, request, parent, isMain);
  };
  let AcpChatViewProvider;
  try {
    ({ AcpChatViewProvider } = require(acpWebviewPath));
  } finally {
    Module._load = origLoad;
  }

  const provider = new AcpChatViewProvider({ extensionVersion: '0.9.0' });
  t.after(async () => {
    await provider.dispose();
  });

  const webviewView = {
    webview: {
      cspSource: 'vscode-webview://kucode-test',
      html: '',
      options: {},
      postMessage(msg) {
        postedMessages.push(msg);
      },
      onDidReceiveMessage(fn) {
        messageListener = fn;
        return { dispose() {} };
      },
    },
    onDidDispose(fn) {
      disposeListener = fn;
      return { dispose() {} };
    },
    show() {},
  };

  provider.resolveWebviewView(webviewView);

  // Verify strict CSP nonce, OST pure-black palette, and syntactically valid inline JS
  assert.match(webviewView.webview.html, /Content-Security-Policy/);
  assert.match(webviewView.webview.html, /script-src 'nonce-[A-Za-z0-9+/=]+'/);
  assert.match(webviewView.webview.html, /--bg:.*#000000/);
  const scriptMatch = webviewView.webview.html.match(/<script[^>]*>([\s\S]*?)<\/script>/);
  assert.ok(scriptMatch, 'Expected inline <script> block in webview HTML');
  assert.doesNotThrow(() => new Function(scriptMatch[1]), 'Inline webview script must be syntactically valid JS');

  // Simulate webview ready & prompt roundtrip
  assert.equal(typeof messageListener, 'function');
  assert.equal(typeof disposeListener, 'function');
  messageListener({ type: 'ready' });
  await new Promise((r) => setTimeout(r, 30));
  const bootstrap = postedMessages.find((m) => m.type === 'bootstrap');
  assert.ok(bootstrap);
  assert.equal(bootstrap.activeAgentId, 'mock');
  assert.ok(bootstrap.agents.some((a) => a.id === 'mock' && a.installed === true));

  messageListener({ type: 'sendPrompt', text: 'Hello ACP World' });
  await new Promise((r) => setTimeout(r, 400));

  const chunks = postedMessages
    .filter((m) => m.type === 'sessionUpdate' && m.update && m.update.sessionUpdate === 'agent_message_chunk')
    .map((m) => m.update.content && m.update.content.text)
    .join('');
  assert.equal(chunks, 'ECHO: Hello ACP World');
  assert.ok(postedMessages.some((m) => m.type === 'turnEnd' && m.stopReason === 'end_turn'));
});

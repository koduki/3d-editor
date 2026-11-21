#!/usr/bin/env node
/**
 * ACP v1 compliant mock agent for local verification, UI development, and E2E testing.
 * Communicates over stdio using NDJSON (JSON-RPC 2.0).
 */

import readline from 'node:readline';
import crypto from 'node:crypto';
import path from 'node:path';

const rl = readline.createInterface({
  input: process.stdin,
  crlfDelay: Infinity,
});

const sessions = new Map();
let nextServerReqId = 10000;
const pendingClientResponses = new Map();

function send(message) {
  process.stdout.write(`${JSON.stringify(message)}\n`);
}

function sendResult(id, result) {
  send({ jsonrpc: '2.0', id, result });
}

function sendError(id, code, message, data) {
  const err = { code, message };
  if (data !== undefined) err.data = data;
  send({ jsonrpc: '2.0', id, error: err });
}

function sendNotification(method, params) {
  send({ jsonrpc: '2.0', method, params });
}

function sendClientRequest(method, params) {
  const id = nextServerReqId++;
  return new Promise((resolve, reject) => {
    pendingClientResponses.set(String(id), { resolve, reject });
    send({ jsonrpc: '2.0', id, method, params });
  });
}

function sleep(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new Error('cancelled'));
      return;
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    function onAbort() {
      clearTimeout(timer);
      reject(new Error('cancelled'));
    }
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

const DEFAULT_MODES = [
  {
    id: 'default',
    name: 'Default',
    description: 'ファイル変更やコマンド実行の前に承認を求める安全モード',
  },
  {
    id: 'accept-edits',
    name: 'Accept Edits',
    description: 'ファイル編集を自動承認して素早く作業するモード',
  },
  {
    id: 'plan',
    name: 'Plan',
    description: 'ファイル変更を行わず設計・構成案の策定に専念するモード',
  },
];

function extractPromptText(promptBlocks) {
  if (!Array.isArray(promptBlocks)) return '';
  return promptBlocks
    .filter((b) => b && b.type === 'text' && typeof b.text === 'string')
    .map((b) => b.text)
    .join('\n')
    .trim();
}

async function runPromptTurn(session, promptText, reqId) {
  const controller = new AbortController();
  session.activeTurn = { reqId, controller };
  const { signal } = controller;

  const sendUpdate = (update) => {
    sendNotification('session/update', {
      sessionId: session.id,
      update,
    });
  };

  try {
    const isPlanOnly = session.currentModeId === 'plan';
    const wantsNoEdit = /質問|説明|summarize|explain|no-edit/i.test(promptText);
    const shouldEdit = !isPlanOnly && !wantsNoEdit;

    // 1. Execution plan
    const planEntries = [
      { content: 'ユーザー指示の意図と対象ファイルを解析', priority: 'high', status: 'in_progress' },
      ...(shouldEdit
        ? [{ content: 'Unified Diff を生成し編集内容を適用', priority: 'high', status: 'pending' }]
        : [{ content: '構成案・回答を整理して提示', priority: 'high', status: 'pending' }]),
      { content: '変更内容のサマリーを報告', priority: 'medium', status: 'pending' },
    ];
    sendUpdate({ sessionUpdate: 'plan', entries: planEntries });
    await sleep(80, signal);

    // 2. Collapsible thought stream
    const thoughts = [
      `指示内容「${promptText.slice(0, 60)}${promptText.length > 60 ? '…' : ''}」を確認しています。\n`,
      `現在の動作モードは \`${session.currentModeId}\`、ワークスペースは \`${session.cwd}\` です。\n`,
      shouldEdit
        ? 'XREAL AURA の 70° FOV でも縦方向に読みやすい最小限の差分を生成します。'
        : 'Plan モードまたは質問応答として、ファイルを変更せずに回答を作成します。',
    ];
    for (const chunk of thoughts) {
      sendUpdate({
        sessionUpdate: 'agent_thought_chunk',
        content: { type: 'text', text: chunk },
      });
      await sleep(70, signal);
    }

    // 3. Read tool simulation
    const readCallId = `call_read_${crypto.randomBytes(3).toString('hex')}`;
    const targetFile = path.join(session.cwd, 'posts', 'xr-workflow-draft.md');
    sendUpdate({
      sessionUpdate: 'tool_call',
      toolCallId: readCallId,
      name: 'read_file',
      title: `ファイル読み込み: posts/xr-workflow-draft.md`,
      kind: 'read',
      status: 'in_progress',
      locations: [{ path: targetFile, line: 1 }],
    });
    await sleep(100, signal);
    sendUpdate({
      sessionUpdate: 'tool_call_update',
      toolCallId: readCallId,
      status: 'completed',
      content: [{ type: 'content', content: { type: 'text', text: '14 行を読み込みました。' } }],
    });

    planEntries[0].status = 'completed';
    planEntries[1].status = 'in_progress';
    sendUpdate({ sessionUpdate: 'plan', entries: planEntries });

    let editApproved = true;
    let editRejectedReason = '';

    // 4. Optional edit + Unified Diff permission request
    if (shouldEdit) {
      const editCallId = `call_edit_${crypto.randomBytes(3).toString('hex')}`;
      const oldText = [
        '# XR 空間でのブログ執筆メモ',
        '',
        '## 構成案',
        '- XREAL AURA と code-server の連携',
        '- 物理キーボードでの操作感',
        '- TODO: 音声入力の使い勝手を追記する',
        '',
        '## まとめ',
        '今後検証を進める。',
      ].join('\n');

      const summaryLine = promptText.replace(/\s+/g, ' ').slice(0, 72) || 'AI エージェントとの協調編集';
      const newText = [
        '# XR 空間でのブログ執筆メモ',
        '',
        '## 構成案',
        '- XREAL AURA (OST) と code-server (Zen Mode) の2画面連携',
        '- 物理キーボード + IME 安全ショートカットでの快適な操作感',
        '- Web Speech API による Push-to-Talk ラフ書きワークフロー',
        `- 追記トピック: ${summaryLine}`,
        '',
        '## まとめ',
        '光学シースルーに最適化した黒背景 UI と縦型 Diff レビューにより、首を振らずに執筆と実装を往復できる。',
      ].join('\n');

      const diffContent = [
        {
          type: 'diff',
          path: targetFile,
          oldText,
          newText,
        },
      ];

      sendUpdate({
        sessionUpdate: 'tool_call',
        toolCallId: editCallId,
        name: 'write_file',
        title: `記事ドラフトを更新: posts/xr-workflow-draft.md`,
        kind: 'edit',
        status: 'pending',
        locations: [{ path: targetFile, line: 4 }],
        content: diffContent,
        rawInput: { path: targetFile, summary: summaryLine },
      });

      const needsApproval = session.currentModeId === 'default' && !session.alwaysAllowEdits;

      if (needsApproval) {
        const permResponse = await sendClientRequest('session/request_permission', {
          sessionId: session.id,
          toolCall: {
            toolCallId: editCallId,
          },
          options: [
            { optionId: 'allow-once', name: '1回許可 (y)', kind: 'allow_once' },
            { optionId: 'allow-always', name: 'このセッションで常に許可 (a)', kind: 'allow_always' },
            { optionId: 'reject-once', name: '拒否 (n)', kind: 'reject_once' },
          ],
        });

        if (signal.aborted) {
          throw new Error('cancelled');
        }

        const outcome = permResponse?.outcome;
        if (outcome?.outcome === 'selected') {
          if (outcome.optionId === 'allow-always') {
            session.alwaysAllowEdits = true;
            editApproved = true;
          } else if (outcome.optionId === 'allow-once') {
            editApproved = true;
          } else {
            editApproved = false;
            editRejectedReason = 'ユーザーにより編集が拒否されました。';
          }
        } else {
          editApproved = false;
          editRejectedReason = '権限リクエストがキャンセルされました。';
        }
      }

      if (editApproved) {
        sendUpdate({
          sessionUpdate: 'tool_call_update',
          toolCallId: editCallId,
          status: 'in_progress',
        });
        await sleep(110, signal);
        sendUpdate({
          sessionUpdate: 'tool_call_update',
          toolCallId: editCallId,
          status: 'completed',
        });
      } else {
        sendUpdate({
          sessionUpdate: 'tool_call_update',
          toolCallId: editCallId,
          status: 'failed',
          content: [{ type: 'content', content: { type: 'text', text: editRejectedReason } }],
        });
      }
    }

    planEntries[1].status = editApproved ? 'completed' : 'pending';
    planEntries[2].status = 'in_progress';
    sendUpdate({ sessionUpdate: 'plan', entries: planEntries });

    // 5. Stream Markdown response
    const messageId = `msg_agent_${crypto.randomBytes(4).toString('hex')}`;
    const responseChunks = shouldEdit
      ? editApproved
        ? [
            `### 編集を反映しました\n\n`,
            `指示内容（\`${promptText.slice(0, 60)}\`）をもとに、\`posts/xr-workflow-draft.md\` の構成案を更新しました。\n\n`,
            `- **対象ファイル:** \`posts/xr-workflow-draft.md\`\n`,
            `- **現在のモード:** \`${session.currentModeId}\`\n`,
            `- **同期確認:** 正面パネルの \`code-server\` にリアルタイム反映されます。\n\n`,
            '```bash\n# 実機では DEFAULT_AGENT=agy で agy acp へ接続します\nPORT=8765 WORKSPACE_DIR=~/blog node server.mjs\n```\n',
          ]
        : [
            `### 編集をスキップしました\n\n`,
            `${editRejectedReason}\n\n`,
            `別のアプローチや修正方針があればそのまま指示してください。`,
          ]
      : [
          `### 構成案・回答（${session.currentModeId} モード）\n\n`,
          `「${promptText}」について、ファイル変更なしで整理しました。\n\n`,
          `1. **XR 空間配置:** 左パネルに XR-ACP Companion、正面に \`code-server\` (Zen Mode)\n`,
          `2. **光学最適化:** OST では完全黒 \`#000000\`、VST では半透明ダークサーフェスを使用\n`,
          `3. **操作効率:** \`Ctrl+Enter\` で IME 安全送信、\`Ctrl+Space\` で音声ラフ書き\n`,
        ];

    for (const text of responseChunks) {
      sendUpdate({
        sessionUpdate: 'agent_message_chunk',
        messageId,
        content: { type: 'text', text },
      });
      await sleep(55, signal);
    }

    planEntries[2].status = 'completed';
    sendUpdate({ sessionUpdate: 'plan', entries: planEntries });

    // 6. Token usage update
    session.tokensUsed += Math.max(420, promptText.length * 4 + 380);
    sendUpdate({
      sessionUpdate: 'usage_update',
      used: session.tokensUsed,
      size: 200000,
      cost: {
        amount: Number(((session.tokensUsed / 1_000_000) * 3.0).toFixed(4)),
        currency: 'USD',
      },
    });

    sendResult(reqId, { stopReason: 'end_turn' });
  } catch (err) {
    if (err?.message === 'cancelled' || signal.aborted) {
      sendResult(reqId, { stopReason: 'cancelled' });
    } else {
      sendError(reqId, -32603, err?.message || 'Internal mock agent error');
    }
  } finally {
    if (session.activeTurn?.reqId === reqId) {
      session.activeTurn = null;
    }
  }
}

rl.on('line', (line) => {
  const trimmed = line.trim();
  if (!trimmed) return;

  let msg;
  try {
    msg = JSON.parse(trimmed);
  } catch {
    return;
  }

  // Handle response from client (e.g., session/request_permission)
  if (!msg.method && msg.id !== undefined) {
    const waiter = pendingClientResponses.get(String(msg.id));
    if (waiter) {
      pendingClientResponses.delete(String(msg.id));
      if (msg.error) {
        waiter.reject(new Error(msg.error.message || 'Client returned error'));
      } else {
        waiter.resolve(msg.result);
      }
    }
    return;
  }

  const { id, method, params = {} } = msg;

  switch (method) {
    case 'initialize': {
      sendResult(id, {
        protocolVersion: 1,
        agentCapabilities: {
          loadSession: false,
          promptCapabilities: {
            image: false,
            audio: false,
            embeddedContext: false,
          },
          sessionCapabilities: {
            resume: {},
          },
        },
        agentInfo: {
          name: 'xr-acp-mock-agent',
          title: 'XR-ACP Mock Agent',
          version: '0.1.0',
        },
        authMethods: [],
      });
      break;
    }

    case 'session/new': {
      const sessionId = `sess_mock_${crypto.randomBytes(4).toString('hex')}`;
      const session = {
        id: sessionId,
        cwd: params.cwd || process.cwd(),
        currentModeId: 'default',
        alwaysAllowEdits: false,
        tokensUsed: 1200,
        activeTurn: null,
      };
      sessions.set(sessionId, session);
      sendResult(id, {
        sessionId,
        modes: {
          currentModeId: session.currentModeId,
          availableModes: DEFAULT_MODES,
        },
      });
      break;
    }

    case 'session/resume': {
      const session = sessions.get(params.sessionId);
      if (!session) {
        sendError(id, -32002, `Session not found: ${params.sessionId}`);
        break;
      }
      if (params.cwd) session.cwd = params.cwd;
      sendResult(id, {
        modes: {
          currentModeId: session.currentModeId,
          availableModes: DEFAULT_MODES,
        },
      });
      break;
    }

    case 'session/set_mode': {
      const session = sessions.get(params.sessionId);
      if (!session) {
        sendError(id, -32002, `Session not found: ${params.sessionId}`);
        break;
      }
      const valid = DEFAULT_MODES.some((m) => m.id === params.modeId);
      if (!valid) {
        sendError(id, -32602, `Unsupported mode: ${params.modeId}`);
        break;
      }
      session.currentModeId = params.modeId;
      sendResult(id, {});
      sendNotification('session/update', {
        sessionId: session.id,
        update: {
          sessionUpdate: 'current_mode_update',
          currentModeId: session.currentModeId,
        },
      });
      break;
    }

    case 'session/cancel': {
      const session = sessions.get(params.sessionId);
      if (session?.activeTurn) {
        session.activeTurn.controller.abort();
      }
      // Cancel any pending permission requests
      for (const [key, waiter] of pendingClientResponses.entries()) {
        pendingClientResponses.delete(key);
        waiter.resolve({ outcome: { outcome: 'cancelled' } });
      }
      break;
    }

    case 'session/prompt': {
      const session = sessions.get(params.sessionId);
      if (!session) {
        sendError(id, -32002, `Session not found: ${params.sessionId}`);
        break;
      }
      const text = extractPromptText(params.prompt);
      if (session.activeTurn) {
        // Steering: abort previous turn gracefully and start new one
        session.activeTurn.controller.abort();
      }
      runPromptTurn(session, text, id);
      break;
    }

    default: {
      if (id !== undefined) {
        sendError(id, -32601, `Method not found: ${method}`);
      }
      break;
    }
  }
});

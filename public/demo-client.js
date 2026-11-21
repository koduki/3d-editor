/**
 * Offline browser-side ACP v1 Demo Client (v0.3).
 * Implements the exact same EventTarget & method interface as AcpClient so
 * XREAL AURA / Meta Quest 3 users and app store reviewers can test streaming,
 * vertical Unified Diff permissions, mode switching, and cancellation without a backend.
 */

const DEMO_MODES = [
  {
    id: 'default',
    name: 'default',
    description: 'ファイル編集・コマンド実行前に承認を求める安全モード',
  },
  {
    id: 'accept-edits',
    name: 'accept-edits',
    description: 'ファイル編集を自動承認して高速に作業するモード',
  },
  {
    id: 'plan',
    name: 'plan',
    description: 'ファイル変更を行わず構成案・設計レビューに専念するモード',
  },
];

const DEMO_COMMANDS = [
  { name: 'draft', description: 'ブログ記事ドラフトに追記案の Diff を生成' },
  { name: 'code', description: 'UI コンポーネントのコード改善 Diff を生成' },
  { name: 'test', description: 'テストコマンド実行の権限承認フローを体験' },
  { name: 'plan', description: 'ファイル変更なしで設計・構成案を提示' },
];

function makeId(prefix) {
  return `${prefix}_${Math.random().toString(36).slice(2, 10)}`;
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

export class DemoAcpClient extends EventTarget {
  constructor() {
    super();
    this.isDemo = true;
    this.state = 'disconnected';
    this.agentInfo = {
      name: 'xr-acp-offline-demo',
      title: 'XR-ACP Offline Demo',
      version: '0.3.0',
    };
    this.agentCapabilities = {
      loadSession: false,
      sessionCapabilities: { resume: {} },
    };
    this.sessionId = null;
    this.modes = null;

    this._sessions = new Map();
    this._pendingPerms = new Map();
    this._nextPermId = 9000;
    this._stepDelayMs = 45;
  }

  #emit(type, detail = {}) {
    this.dispatchEvent(new CustomEvent(type, { detail }));
  }

  #setState(nextState, info = {}) {
    this.state = nextState;
    this.#emit('state', { state: nextState, ...info });
  }

  #emitUpdate(sessionId, update) {
    this.#emit('update', { sessionId, update });
  }

  connect({ cwd = '/workspace/xr-blog', savedSessionId = null, reset = false } = {}) {
    this.disconnect({ manual: false });
    this.#setState('connecting');

    setTimeout(() => {
      this.#setState('initializing');
      this.#emit('initialized', {
        protocolVersion: 1,
        agentInfo: this.agentInfo,
        agentCapabilities: this.agentCapabilities,
      });

      let session;
      let resumed = false;

      if (!reset && savedSessionId && this._sessions.has(savedSessionId)) {
        session = this._sessions.get(savedSessionId);
        session.cwd = cwd;
        resumed = true;
      } else if (!reset && savedSessionId && String(savedSessionId).startsWith('sess_demo_')) {
        session = this.#createSessionState(savedSessionId, cwd);
        resumed = true;
      } else {
        session = this.#createSessionState(makeId('sess_demo'), cwd);
      }

      this.sessionId = session.id;
      this.modes = {
        currentModeId: session.currentModeId,
        availableModes: DEMO_MODES,
      };

      this.#emit('session', {
        sessionId: this.sessionId,
        resumed,
        method: resumed ? 'session/resume' : 'session/new',
        modes: this.modes,
      });

      this.#emitUpdate(this.sessionId, {
        sessionUpdate: 'available_commands_update',
        availableCommands: DEMO_COMMANDS,
      });

      this.#setState('ready', {
        sessionId: this.sessionId,
        agentInfo: this.agentInfo,
        modes: this.modes,
      });
    }, 30);
  }

  #createSessionState(id, cwd) {
    const state = {
      id,
      cwd,
      currentModeId: 'default',
      alwaysAllowEdits: false,
      tokensUsed: 1850,
      activeTurn: null,
    };
    this._sessions.set(id, state);
    return state;
  }

  disconnect({ manual = true } = {}) {
    this.cancelPrompt();
    if (manual) {
      this.#setState('disconnected');
    }
  }

  async createNewSession(cwd = '/workspace/xr-blog') {
    const session = this.#createSessionState(makeId('sess_demo'), cwd);
    this.sessionId = session.id;
    this.modes = {
      currentModeId: session.currentModeId,
      availableModes: DEMO_MODES,
    };
    this.#emit('session', {
      sessionId: this.sessionId,
      resumed: false,
      method: 'session/new',
      modes: this.modes,
    });
    return { sessionId: this.sessionId, modes: this.modes };
  }

  async setMode(modeId) {
    const session = this._sessions.get(this.sessionId);
    if (!session) throw new Error('アクティブなデモセッションがありません');
    if (!DEMO_MODES.some((m) => m.id === modeId)) {
      throw new Error(`未対応モードです: ${modeId}`);
    }
    session.currentModeId = modeId;
    this.modes = { currentModeId: modeId, availableModes: DEMO_MODES };
    this.#emitUpdate(session.id, {
      sessionUpdate: 'current_mode_update',
      currentModeId: modeId,
    });
    return {};
  }

  respondPermission(requestId, outcome) {
    const waiter = this._pendingPerms.get(String(requestId));
    if (waiter) {
      this._pendingPerms.delete(String(requestId));
      waiter.resolve({ outcome });
    }
  }

  cancelPrompt() {
    const session = this._sessions.get(this.sessionId);
    if (session?.activeTurn) {
      session.activeTurn.controller.abort();
      session.activeTurn = null;
    }
    for (const [key, waiter] of this._pendingPerms.entries()) {
      this._pendingPerms.delete(key);
      waiter.resolve({ outcome: { outcome: 'cancelled' } });
    }
  }

  #requestPermission(sessionId, toolCall, options) {
    const id = this._nextPermId++;
    return new Promise((resolve) => {
      this._pendingPerms.set(String(id), { resolve });
      this.#emit('permission', {
        id,
        params: {
          sessionId,
          toolCall,
          options,
        },
      });
    });
  }

  async sendPrompt(text) {
    const session = this._sessions.get(this.sessionId);
    if (!session) throw new Error('アクティブなデモセッションがありません');

    if (session.activeTurn) {
      session.activeTurn.controller.abort();
    }

    const controller = new AbortController();
    const { signal } = controller;
    session.activeTurn = { controller };

    const update = (payload) => this.#emitUpdate(session.id, payload);
    const delay = (ms) => sleep(Math.min(ms, this._stepDelayMs * 2), signal);

    try {
      const lower = text.toLowerCase();
      const isPlan = session.currentModeId === 'plan' || /^\/plan\b|構成案のみ|説明して|質問/i.test(text);
      const isExec = !isPlan && (/^\/test\b|テスト実行|コマンド|npm test|ビルド|shell|exec/i.test(lower));
      const isCode = !isPlan && !isExec && (/^\/code\b|css|html|js|コード|実装|修正|リファクタ/i.test(lower));

      const planEntries = [
        { content: '指示内容と対象コンテキストを分析', priority: 'high', status: 'in_progress' },
        {
          content: isPlan
            ? '編集を行わず提案・設計案を整理'
            : isExec
              ? '検証コマンドの実行権限を確認してテストを実行'
              : '70° FOV 向け縦型 Unified Diff を生成してレビュー依頼',
          priority: 'high',
          status: 'pending',
        },
        { content: '作業結果と次のアクションを要約', priority: 'medium', status: 'pending' },
      ];
      update({ sessionUpdate: 'plan', entries: planEntries });
      await delay(60);

      const thoughts = [
        `[オフラインデモ] 入力「${text.slice(0, 60)}${text.length > 60 ? '…' : ''}」を処理しています。\n`,
        `現在モード: \`${session.currentModeId}\` / ワークスペース: \`${session.cwd}\`\n`,
        isExec
          ? 'コマンド実行前に `session/request_permission` で安全確認を行います。'
          : isPlan
            ? 'Plan モードのためファイル変更を伴わない回答を作成します。'
            : '編集差分を縦型 Unified Diff として提示し、キーボード承認 (`y`/`a`/`n`/`Esc`) を待機します。',
      ];
      for (const chunk of thoughts) {
        update({ sessionUpdate: 'agent_thought_chunk', content: { type: 'text', text: chunk } });
        await delay(50);
      }

      planEntries[0].status = 'completed';
      planEntries[1].status = 'in_progress';
      update({ sessionUpdate: 'plan', entries: planEntries });

      let approved = true;
      let rejectReason = '';

      if (isExec) {
        const callId = makeId('call_exec');
        const cmd = 'npm test';
        update({
          sessionUpdate: 'tool_call',
          toolCallId: callId,
          name: 'run_command',
          title: `テスト実行: ${cmd}`,
          kind: 'execute',
          status: 'pending',
          locations: [{ path: `${session.cwd}/package.json`, line: 1 }],
          rawInput: { command: cmd, cwd: session.cwd },
        });

        const permRes = await this.#requestPermission(
          session.id,
          { toolCallId: callId },
          [
            { optionId: 'allow-once', name: '実行を許可 (y)', kind: 'allow_once' },
            { optionId: 'reject-once', name: '拒否 (n)', kind: 'reject_once' },
          ],
        );
        if (signal.aborted) throw new Error('cancelled');

        if (permRes?.outcome?.outcome === 'selected' && permRes.outcome.optionId === 'allow-once') {
          update({ sessionUpdate: 'tool_call_update', toolCallId: callId, status: 'in_progress' });
          await delay(90);
          update({
            sessionUpdate: 'tool_call_update',
            toolCallId: callId,
            status: 'completed',
            content: [
              {
                type: 'content',
                content: {
                  type: 'text',
                  text: '✔ computeUnifiedDiff\n✔ renderMarkdown XSS safety\n✔ bridge auth & warm session/resume\nℹ tests 10 | pass 10 | fail 0',
                },
              },
            ],
          });
        } else {
          approved = false;
          rejectReason = 'コマンド実行がキャンセル／拒否されました。';
          update({
            sessionUpdate: 'tool_call_update',
            toolCallId: callId,
            status: 'failed',
            content: [{ type: 'content', content: { type: 'text', text: rejectReason } }],
          });
        }
      } else if (!isPlan) {
        const callId = makeId('call_edit');
        const targetPath = isCode
          ? `${session.cwd}/src/xr-panel-layout.js`
          : `${session.cwd}/posts/xr-spatial-writing.md`;

        const summary = text.replace(/^\/\w+\s*/, '').replace(/\s+/g, ' ').slice(0, 68) || '空間執筆ワークフロー改善';

        const oldText = isCode
          ? [
              'export function applyPanelLuminance(isFocused, mode) {',
              '  const base = mode === "ost" ? 1.0 : 0.92;',
              '  return isFocused ? base : 0.7;',
              '}',
              '',
              'export const SHORTCUTS = {',
              '  submit: "Enter",',
              '};',
            ].join('\n')
          : [
              '# XREAL AURA × Meta Quest 3 空間執筆環境',
              '',
              '## 画面構成',
              '- 左パネル: AI エージェント対話',
              '- 正面パネル: code-server (Zen Mode)',
              '- TODO: キーボード承認と音声入力を追記',
              '',
              '## メモ',
              'Micro-OLED では黒背景が透過する特性を活かす。',
            ].join('\n');

        const newText = isCode
          ? [
              'export function applyPanelLuminance(isFocused, mode, dimOnBlur = true) {',
              '  const base = mode === "ost" ? 1.0 : 0.92;',
              '  if (!dimOnBlur || isFocused) return base;',
              '  // Reduce luminance to 50% when editing in code-server',
              '  return base * 0.5;',
              '}',
              '',
              'export const SHORTCUTS = {',
              '  submit: "Ctrl+Enter", // IME-safe submission',
              '  voiceToggle: "Ctrl+Space",',
              `  note: ${JSON.stringify(summary)},`,
              '};',
            ].join('\n')
          : [
              '# XREAL AURA × Meta Quest 3 空間執筆環境',
              '',
              '## 画面構成',
              '- 左パネル: XR-ACP Companion（OST 完全黒 / VST 半透明切替）',
              '- 正面パネル: code-server (Zen Mode / 共有ワークスペース監視)',
              '- 承認フロー: 70° FOV 向け縦型 Unified Diff を `y` / `a` / `n` / `Esc` で即決',
              '- 音声入力: `Ctrl+Space` で Web Speech API (ja-JP) ラフ書き',
              `- 追記トピック: ${summary}`,
              '',
              '## メモ',
              'Micro-OLED では `#000000` が透過し、`window.blur` 時の 50% 自動減光で視界干渉を防ぐ。',
            ].join('\n');

        update({
          sessionUpdate: 'tool_call',
          toolCallId: callId,
          name: 'write_file',
          title: `編集提案: ${targetPath.split('/').slice(-2).join('/')}`,
          kind: 'edit',
          status: 'pending',
          locations: [{ path: targetPath, line: 4 }],
          content: [{ type: 'diff', path: targetPath, oldText, newText }],
          rawInput: { path: targetPath, summary },
        });

        const needsApproval = session.currentModeId === 'default' && !session.alwaysAllowEdits;
        if (needsApproval) {
          const permRes = await this.#requestPermission(
            session.id,
            { toolCallId: callId },
            [
              { optionId: 'allow-once', name: '1回許可 (y)', kind: 'allow_once' },
              { optionId: 'allow-always', name: '常に許可 (a)', kind: 'allow_always' },
              { optionId: 'reject-once', name: '拒否 (n)', kind: 'reject_once' },
            ],
          );
          if (signal.aborted) throw new Error('cancelled');

          const outcome = permRes?.outcome;
          if (outcome?.outcome === 'selected') {
            if (outcome.optionId === 'allow-always') {
              session.alwaysAllowEdits = true;
            } else if (outcome.optionId !== 'allow-once') {
              approved = false;
              rejectReason = 'ユーザーにより編集提案が拒否されました。';
            }
          } else {
            approved = false;
            rejectReason = '承認がキャンセルされました。';
          }
        }

        if (approved) {
          update({ sessionUpdate: 'tool_call_update', toolCallId: callId, status: 'in_progress' });
          await delay(70);
          update({ sessionUpdate: 'tool_call_update', toolCallId: callId, status: 'completed' });
        } else {
          update({
            sessionUpdate: 'tool_call_update',
            toolCallId: callId,
            status: 'failed',
            content: [{ type: 'content', content: { type: 'text', text: rejectReason } }],
          });
        }
      }

      planEntries[1].status = approved ? 'completed' : 'pending';
      planEntries[2].status = 'in_progress';
      update({ sessionUpdate: 'plan', entries: planEntries });

      const messageId = makeId('msg_demo');
      const chunks = isPlan
        ? [
            `### 構成案・設計メモ（オフラインデモ / \`${session.currentModeId}\`）\n\n`,
            `「${text}」について、ファイル変更なしで提案をまとめました。\n\n`,
            `- **OST / VST 切替:** ヘッダー右上の \`OST\` / \`VST\` ボタンで即時に光学プロファイルを切替可能\n`,
            `- **履歴キャッシュ:** リロードしても「履歴」パネルから直前の会話と Diff を復元\n`,
            `- **実エージェント接続:** 右上の「設定」からローカル Termux または Tailscale リモートへ接続\n`,
          ]
        : approved
          ? [
              `### デモ操作が完了しました\n\n`,
              isExec
                ? `テストコマンド \`npm test\` の実行が完了し、全件パスしました。\n\n`
                : `縦型 Unified Diff の承認を受け、ドラフトを更新しました（オフラインデモ環境のためローカルファイルは変更されません）。\n\n`,
              '```bash\n# 実機（Termux / リモート PC）での起動例\nBIND_HOST=0.0.0.0 ACP_BRIDGE_TOKEN="secret" WORKSPACE_DIR=~/blog npm start\n```\n',
            ]
          : [
              `### 操作を中断しました\n\n`,
              `${rejectReason}\n\n`,
              `別案の指示を送るか、上部セレクトで \`accept-edits\` / \`plan\` モードへ切り替えてお試しください。`,
            ];

      for (const chunk of chunks) {
        update({
          sessionUpdate: 'agent_message_chunk',
          messageId,
          content: { type: 'text', text: chunk },
        });
        await delay(45);
      }

      planEntries[2].status = 'completed';
      update({ sessionUpdate: 'plan', entries: planEntries });

      session.tokensUsed += Math.max(360, text.length * 4 + 320);
      update({
        sessionUpdate: 'usage_update',
        used: session.tokensUsed,
        size: 200000,
        cost: {
          amount: Number(((session.tokensUsed / 1_000_000) * 3.0).toFixed(4)),
          currency: 'USD',
        },
      });

      return { stopReason: 'end_turn' };
    } catch (err) {
      if (err?.message === 'cancelled' || signal.aborted) {
        return { stopReason: 'cancelled' };
      }
      throw err;
    } finally {
      if (session.activeTurn?.controller === controller) {
        session.activeTurn = null;
      }
    }
  }
}

/**
 * ACP v1 JSON-RPC 2.0 Client over WebSocket (NDJSON).
 */

export class AcpClient extends EventTarget {
  constructor() {
    super();
    this.ws = null;
    this.nextId = 1;
    this.pending = new Map(); // String(id) -> { resolve, reject, method }
    this.state = 'disconnected';
    this.agentInfo = null;
    this.agentCapabilities = {};
    this.sessionId = null;
    this.modes = null;

    this._manualClose = false;
    this._reconnectTimer = null;
    this._reconnectAttempt = 0;
    this._lastConnectOpts = null;
  }

  #emit(type, detail = {}) {
    this.dispatchEvent(new CustomEvent(type, { detail }));
  }

  #setState(nextState, info = {}) {
    this.state = nextState;
    this.#emit('state', { state: nextState, ...info });
  }

  connect({ wsUrl, cwd, savedSessionId = null, reset = false }) {
    this.disconnect({ manual: true });
    this._manualClose = false;
    this._reconnectAttempt = 0;
    this._lastConnectOpts = { wsUrl, cwd, savedSessionId, reset };
    this.#openSocket({ wsUrl, cwd, savedSessionId, reset });
  }

  #openSocket({ wsUrl, cwd, savedSessionId, reset }) {
    if (this._reconnectTimer) {
      clearTimeout(this._reconnectTimer);
      this._reconnectTimer = null;
    }

    this.#setState('connecting', { wsUrl });

    let finalUrl = wsUrl;
    if (reset) {
      try {
        const u = new URL(wsUrl);
        u.searchParams.set('reset', '1');
        finalUrl = u.toString();
      } catch {
        // leave as-is
      }
    }

    let ws;
    try {
      ws = new WebSocket(finalUrl);
    } catch (err) {
      this.#setState('error', { message: `WebSocket URL エラー: ${err.message}` });
      return;
    }

    this.ws = ws;

    ws.addEventListener('open', async () => {
      if (this.ws !== ws) return;
      this._reconnectAttempt = 0;
      this.#setState('initializing');

      try {
        const initRes = await this.request('initialize', {
          protocolVersion: 1,
          clientCapabilities: {
            session: {
              notices: {},
            },
          },
          clientInfo: {
            name: 'xr-acp-companion',
            title: 'XR-ACP Companion',
            version: '0.1.0',
          },
        });

        this.agentInfo = initRes?.agentInfo || null;
        this.agentCapabilities = initRes?.agentCapabilities || {};
        this.#emit('initialized', {
          protocolVersion: initRes?.protocolVersion,
          agentInfo: this.agentInfo,
          agentCapabilities: this.agentCapabilities,
        });

        await this.#setupSession({
          cwd,
          savedSessionId: reset ? null : savedSessionId || this.sessionId,
        });

        // Clear one-shot reset flag for subsequent reconnects
        if (this._lastConnectOpts) {
          this._lastConnectOpts.reset = false;
          this._lastConnectOpts.savedSessionId = this.sessionId;
        }

        this.#setState('ready', {
          sessionId: this.sessionId,
          agentInfo: this.agentInfo,
          modes: this.modes,
        });
      } catch (err) {
        this.#setState('error', { message: err?.message || 'ACP 初期化に失敗しました' });
      }
    });

    ws.addEventListener('message', (event) => {
      if (this.ws !== ws) return;
      const raw = typeof event.data === 'string' ? event.data : '';
      const lines = raw.split(/\r?\n/);
      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed) continue;
        this.#handleLine(trimmed);
      }
    });

    ws.addEventListener('close', (event) => {
      if (this.ws !== ws) return;
      this.ws = null;
      this.#rejectAllPending(new Error(event.reason || `接続が切断されました (code=${event.code})`));

      if (this._manualClose) {
        this.#setState('disconnected', { code: event.code, reason: event.reason });
        return;
      }

      // Do not auto-reconnect when explicitly replaced by another tab
      if (event.code === 4001) {
        this.#setState('disconnected', {
          code: event.code,
          reason: '別のウィンドウからの接続に切り替わりました',
        });
        return;
      }

      this.#setState('reconnecting', {
        code: event.code,
        reason: event.reason || '再接続を試行中…',
      });
      this.#scheduleReconnect();
    });

    ws.addEventListener('error', () => {
      // Close event will follow and handle state/reconnect
    });
  }

  async #setupSession({ cwd, savedSessionId }) {
    const canResume = Boolean(this.agentCapabilities?.sessionCapabilities?.resume);
    const canLoad = Boolean(this.agentCapabilities?.loadSession);
    const sessionCwd = cwd || '/';

    if (savedSessionId && (canResume || canLoad)) {
      const method = canResume ? 'session/resume' : 'session/load';
      try {
        const res = await this.request(method, {
          sessionId: savedSessionId,
          cwd: sessionCwd,
          mcpServers: [],
        });
        this.sessionId = savedSessionId;
        this.modes = res?.modes || this.modes;
        this.#emit('session', {
          sessionId: this.sessionId,
          resumed: true,
          method,
          modes: this.modes,
        });
        return;
      } catch {
        // Fallback to session/new when previous session is no longer active
      }
    }

    const res = await this.request('session/new', {
      cwd: sessionCwd,
      mcpServers: [],
    });
    this.sessionId = res?.sessionId;
    this.modes = res?.modes || null;
    this.#emit('session', {
      sessionId: this.sessionId,
      resumed: false,
      method: 'session/new',
      modes: this.modes,
    });
  }

  #scheduleReconnect() {
    if (this._manualClose || !this._lastConnectOpts) return;
    const delay = Math.min(1000 * Math.pow(1.6, this._reconnectAttempt), 12000);
    this._reconnectAttempt++;

    this._reconnectTimer = setTimeout(() => {
      this._reconnectTimer = null;
      if (this._manualClose || !this._lastConnectOpts) return;
      this.#openSocket({
        ...this._lastConnectOpts,
        savedSessionId: this.sessionId || this._lastConnectOpts.savedSessionId,
        reset: false,
      });
    }, Math.round(delay));
  }

  disconnect({ manual = true } = {}) {
    this._manualClose = manual;
    if (this._reconnectTimer) {
      clearTimeout(this._reconnectTimer);
      this._reconnectTimer = null;
    }
    if (this.ws) {
      const sock = this.ws;
      this.ws = null;
      try {
        sock.close(1000, 'Client disconnected');
      } catch {
        // ignore
      }
    }
    this.#rejectAllPending(new Error('Disconnected'));
    if (manual) {
      this.#setState('disconnected');
    }
  }

  #rejectAllPending(err) {
    for (const [, waiter] of this.pending.entries()) {
      waiter.reject(err);
    }
    this.pending.clear();
  }

  #sendJson(obj) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      throw new Error('WebSocket が未接続です');
    }
    this.ws.send(`${JSON.stringify(obj)}\n`);
  }

  request(method, params = {}) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      try {
        this.#sendJson({ jsonrpc: '2.0', id, method, params });
      } catch (err) {
        reject(err);
        return;
      }
      this.pending.set(String(id), { resolve, reject, method });
    });
  }

  notify(method, params = {}) {
    this.#sendJson({ jsonrpc: '2.0', method, params });
  }

  async sendPrompt(text) {
    if (!this.sessionId) throw new Error('アクティブなセッションがありません');
    return this.request('session/prompt', {
      sessionId: this.sessionId,
      prompt: [{ type: 'text', text }],
    });
  }

  cancelPrompt() {
    if (!this.sessionId) return;
    try {
      this.notify('session/cancel', { sessionId: this.sessionId });
    } catch {
      // ignore when disconnected
    }
  }

  async setMode(modeId) {
    if (!this.sessionId) throw new Error('アクティブなセッションがありません');
    return this.request('session/set_mode', {
      sessionId: this.sessionId,
      modeId,
    });
  }

  async createNewSession(cwd) {
    const res = await this.request('session/new', {
      cwd: cwd || this._lastConnectOpts?.cwd || '/',
      mcpServers: [],
    });
    this.sessionId = res?.sessionId;
    this.modes = res?.modes || null;
    if (this._lastConnectOpts) {
      this._lastConnectOpts.savedSessionId = this.sessionId;
    }
    this.#emit('session', {
      sessionId: this.sessionId,
      resumed: false,
      method: 'session/new',
      modes: this.modes,
    });
    return res;
  }

  respondPermission(requestId, outcome) {
    this.#sendJson({
      jsonrpc: '2.0',
      id: requestId,
      result: { outcome },
    });
  }

  #handleLine(line) {
    let msg;
    try {
      msg = JSON.parse(line);
    } catch {
      return;
    }

    // Response to our outgoing JSON-RPC request
    if (!msg.method && msg.id !== undefined) {
      const waiter = this.pending.get(String(msg.id));
      if (waiter) {
        this.pending.delete(String(msg.id));
        if (msg.error) {
          const err = new Error(msg.error.message || `RPC error ${msg.error.code}`);
          err.code = msg.error.code;
          err.data = msg.error.data;
          waiter.reject(err);
        } else {
          waiter.resolve(msg.result);
        }
      }
      return;
    }

    // Notification: session/update
    if (msg.method === 'session/update') {
      this.#emit('update', {
        sessionId: msg.params?.sessionId,
        update: msg.params?.update,
      });
      return;
    }

    // Inbound Request: session/request_permission
    if (msg.method === 'session/request_permission' && msg.id !== undefined) {
      this.#emit('permission', {
        id: msg.id,
        params: msg.params || {},
      });
      return;
    }

    // Reject unknown inbound requests politely per JSON-RPC 2.0
    if (msg.method && msg.id !== undefined) {
      try {
        this.#sendJson({
          jsonrpc: '2.0',
          id: msg.id,
          error: {
            code: -32601,
            message: `Client does not implement method: ${msg.method}`,
          },
        });
      } catch {
        // ignore
      }
    }
  }
}

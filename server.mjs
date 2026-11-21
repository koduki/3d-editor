#!/usr/bin/env node
/**
 * XR-ACP Companion Backend (server.mjs)
 * - Serves static PWA assets from public/
 * - Exposes GET /api/config for workspace cwd and available agents
 * - Bridges WebSocket <-> ACP agent stdio as transparent NDJSON (JSON-RPC 2.0)
 * - Keeps agent processes alive across browser reloads/sleep (default 5 min)
 * - Enforces token auth when bound to non-loopback hosts (e.g. Tailscale / 0.0.0.0)
 */

import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { WebSocketServer, WebSocket } from 'ws';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const PUBLIC_DIR = path.join(__dirname, 'public');
const MOCK_AGENT_PATH = path.join(__dirname, 'scripts', 'mock-agent.mjs');

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8',
};

const DEFAULT_AGENTS = {
  agy: {
    command: 'agy',
    args: ['acp'],
    label: 'agy acp',
  },
  copilot: {
    command: 'copilot',
    args: ['--acp'],
    label: 'copilot --acp',
  },
  gemini: {
    command: 'gemini',
    args: ['--acp'],
    label: 'gemini --acp',
  },
  mock: {
    command: process.execPath,
    args: [MOCK_AGENT_PATH],
    label: 'Mock ACP Agent (Demo)',
  },
};

export function isLoopbackHost(host) {
  if (!host) return true;
  const normalized = host.trim().toLowerCase().replace(/^\[|\]$/g, '');
  return normalized === '127.0.0.1' || normalized === '::1' || normalized === 'localhost';
}

function sha256(value) {
  return crypto.createHash('sha256').update(String(value)).digest();
}

export function verifyToken(expected, provided) {
  if (!expected) return true;
  if (!provided) return false;
  return crypto.timingSafeEqual(sha256(expected), sha256(provided));
}

function extractToken(req, urlObj) {
  const queryToken = urlObj.searchParams.get('token');
  if (queryToken) return queryToken;
  const authHeader = req.headers.authorization;
  if (typeof authHeader === 'string' && authHeader.toLowerCase().startsWith('bearer ')) {
    return authHeader.slice(7).trim();
  }
  return '';
}

function loadAgentRegistry(customAgentsJson, customAgentsOverride) {
  const registry = { ...DEFAULT_AGENTS };
  if (customAgentsJson) {
    try {
      const parsed = JSON.parse(customAgentsJson);
      if (parsed && typeof parsed === 'object') {
        for (const [key, val] of Object.entries(parsed)) {
          if (val && typeof val.command === 'string') {
            registry[key] = {
              command: val.command,
              args: Array.isArray(val.args) ? val.args.map(String) : [],
              label: typeof val.label === 'string' ? val.label : `${val.command} ${(val.args || []).join(' ')}`.trim(),
            };
          }
        }
      }
    } catch (err) {
      throw new Error(`Invalid ACP_AGENTS_JSON: ${err.message}`);
    }
  }
  if (customAgentsOverride && typeof customAgentsOverride === 'object') {
    Object.assign(registry, customAgentsOverride);
  }
  return registry;
}

class AgentProcessEntry {
  constructor({ key, agentKey, agentDef, workspaceDir, keepAliveMs, onRemove, logger }) {
    this.key = key;
    this.agentKey = agentKey;
    this.agentDef = agentDef;
    this.workspaceDir = workspaceDir;
    this.keepAliveMs = keepAliveMs;
    this.onRemove = onRemove;
    this.logger = logger;

    this.ws = null;
    this.killTimer = null;
    this.stdoutBuffer = '';
    this.closed = false;

    // Minimal bridge state for seamless browser reload / sleep reattach
    this.cachedInitializeResult = null;
    this.pendingInitIds = new Set();
    this.pendingPermissions = new Map(); // String(id) -> raw NDJSON line

    this.child = spawn(agentDef.command, agentDef.args, {
      cwd: workspaceDir,
      stdio: ['pipe', 'pipe', 'pipe'],
      env: process.env,
    });

    this.logger.log(`[bridge] Spawned agent "${agentKey}" (${agentDef.command} ${agentDef.args.join(' ')}) pid=${this.child.pid}`);

    this.child.stdout.setEncoding('utf8');
    this.child.stdout.on('data', (chunk) => this.#onStdoutData(chunk));

    this.child.stderr.setEncoding('utf8');
    this.child.stderr.on('data', (chunk) => {
      const text = chunk.trim();
      if (text) {
        this.logger.warn(`[agent:${agentKey}:stderr] ${text}`);
      }
    });

    this.child.on('error', (err) => {
      this.logger.error(`[bridge] Agent "${agentKey}" spawn error: ${err.message}`);
      this.#handleProcessExit(`Failed to start "${agentKey}": ${err.code || err.message}`);
    });

    this.child.on('exit', (code, signal) => {
      this.logger.log(`[bridge] Agent "${agentKey}" exited (code=${code}, signal=${signal})`);
      this.#handleProcessExit(`Agent "${agentKey}" exited (code=${code ?? 'null'}, signal=${signal ?? 'null'})`);
    });
  }

  attach(ws) {
    if (this.killTimer) {
      clearTimeout(this.killTimer);
      this.killTimer = null;
      this.logger.log(`[bridge] Reattached to warm agent "${this.agentKey}" (pid=${this.child.pid})`);
    }
    if (this.replayTimer) {
      clearTimeout(this.replayTimer);
      this.replayTimer = null;
    }

    if (this.ws && this.ws !== ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.close(4001, 'Replaced by new connection');
    }

    this.ws = ws;
    this.replayedOnCurrentSocket = false;

    // Replay unanswered permission requests once the client finishes socket open / resume
    if (this.pendingPermissions.size > 0) {
      this.replayTimer = setTimeout(() => {
        this.replayTimer = null;
        this.#flushPendingPermissions(ws);
      }, 100);
    }

    ws.on('message', (data) => this.#onWsMessage(ws, data));
    ws.on('close', () => this.#onWsClose(ws));
    ws.on('error', (err) => {
      this.logger.warn(`[bridge] WebSocket error on "${this.agentKey}": ${err.message}`);
    });
  }

  #flushPendingPermissions(ws) {
    if (this.closed || ws !== this.ws || this.replayedOnCurrentSocket) return;
    if (ws.readyState !== WebSocket.OPEN || this.pendingPermissions.size === 0) return;
    this.replayedOnCurrentSocket = true;
    for (const rawLine of this.pendingPermissions.values()) {
      ws.send(rawLine);
    }
  }

  #onWsMessage(ws, data) {
    if (this.closed || ws !== this.ws) return;
    const text = typeof data === 'string' ? data : data.toString('utf8');
    const lines = text.split(/\r?\n/);

    for (const raw of lines) {
      const line = raw.trim();
      if (!line) continue;

      let parsed = null;
      try {
        parsed = JSON.parse(line);
      } catch {
        // Non-JSON lines are still forwarded verbatim
      }

      if (parsed && typeof parsed === 'object') {
        // Reattach helper: answer duplicate initialize from cache so warm stdio agents don't reject it
        if (parsed.method === 'initialize' && parsed.id !== undefined) {
          if (this.cachedInitializeResult !== null) {
            if (this.ws && this.ws.readyState === WebSocket.OPEN) {
              this.ws.send(
                JSON.stringify({
                  jsonrpc: '2.0',
                  id: parsed.id,
                  result: this.cachedInitializeResult,
                }),
              );
            }
            continue;
          }
          this.pendingInitIds.add(String(parsed.id));
        }

        if ((parsed.method === 'session/resume' || parsed.method === 'session/load') && parsed.id !== undefined) {
          this.pendingResumeIds = this.pendingResumeIds || new Set();
          this.pendingResumeIds.add(String(parsed.id));
        }

        // Clear buffered permission request once client responds
        if (!parsed.method && parsed.id !== undefined && this.pendingPermissions.has(String(parsed.id))) {
          this.pendingPermissions.delete(String(parsed.id));
        }
      }

      if (this.child.stdin && !this.child.stdin.destroyed) {
        this.child.stdin.write(`${line}\n`);
      }
    }
  }

  #onStdoutData(chunk) {
    this.stdoutBuffer += chunk;
    let newlineIdx;
    while ((newlineIdx = this.stdoutBuffer.indexOf('\n')) !== -1) {
      const line = this.stdoutBuffer.slice(0, newlineIdx).replace(/\r$/, '');
      this.stdoutBuffer = this.stdoutBuffer.slice(newlineIdx + 1);
      if (!line.trim()) continue;
      this.#onAgentLine(line);
    }
  }

  #onAgentLine(line) {
    let parsed = null;
    try {
      parsed = JSON.parse(line);
    } catch {
      // Forward raw line even if non-JSON
    }

    let flushAfterSend = false;
    if (parsed && typeof parsed === 'object') {
      if (!parsed.method && parsed.id !== undefined && this.pendingInitIds.has(String(parsed.id))) {
        this.pendingInitIds.delete(String(parsed.id));
        if (parsed.result !== undefined) {
          this.cachedInitializeResult = parsed.result;
        }
      }

      if (!parsed.method && parsed.id !== undefined && this.pendingResumeIds?.has(String(parsed.id))) {
        this.pendingResumeIds.delete(String(parsed.id));
        flushAfterSend = true;
      }

      if (parsed.method === 'session/request_permission' && parsed.id !== undefined) {
        this.pendingPermissions.set(String(parsed.id), line);
      }
    }

    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(line);
      if (flushAfterSend) {
        if (this.replayTimer) {
          clearTimeout(this.replayTimer);
          this.replayTimer = null;
        }
        this.#flushPendingPermissions(this.ws);
      }
    }
  }

  #onWsClose(ws) {
    if (ws !== this.ws) return;
    this.ws = null;
    if (this.replayTimer) {
      clearTimeout(this.replayTimer);
      this.replayTimer = null;
    }
    if (this.closed) return;

    if (this.keepAliveMs <= 0) {
      this.destroy();
      return;
    }

    this.logger.log(`[bridge] Client detached from "${this.agentKey}". Keeping process alive for ${this.keepAliveMs}ms.`);
    this.killTimer = setTimeout(() => {
      this.logger.log(`[bridge] Keep-alive expired for "${this.agentKey}". Terminating pid=${this.child.pid}.`);
      this.destroy();
    }, this.keepAliveMs);
  }

  #handleProcessExit(reason) {
    if (this.closed) return;
    this.closed = true;
    if (this.killTimer) {
      clearTimeout(this.killTimer);
      this.killTimer = null;
    }
    if (this.replayTimer) {
      clearTimeout(this.replayTimer);
      this.replayTimer = null;
    }
    this.onRemove(this.key, this);

    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.close(1011, reason.slice(0, 120));
    }
    this.ws = null;
  }

  destroy() {
    if (this.closed) return;
    this.closed = true;
    if (this.killTimer) {
      clearTimeout(this.killTimer);
      this.killTimer = null;
    }
    if (this.replayTimer) {
      clearTimeout(this.replayTimer);
      this.replayTimer = null;
    }
    this.onRemove(this.key, this);

    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.close(1000, 'Agent process terminated');
    }
    this.ws = null;

    if (this.child && !this.child.killed) {
      try {
        this.child.kill('SIGTERM');
      } catch {
        // ignore
      }
      const forceTimer = setTimeout(() => {
        try {
          if (this.child.exitCode === null) {
            this.child.kill('SIGKILL');
          }
        } catch {
          // ignore
        }
      }, 3000);
      forceTimer.unref?.();
    }
  }
}

export function createBridgeServer(options = {}) {
  const bindHost = options.bindHost ?? process.env.BIND_HOST ?? '127.0.0.1';
  const port = options.port ?? Number(process.env.PORT ?? 8765);
  const workspaceDir = path.resolve(options.workspaceDir ?? process.env.WORKSPACE_DIR ?? process.cwd());
  const defaultAgent = options.defaultAgent ?? process.env.DEFAULT_AGENT ?? 'agy';
  const keepAliveMs = options.keepAliveMs ?? Number(process.env.KEEPALIVE_MS ?? 5 * 60 * 1000);
  const bridgeToken = options.token ?? process.env.ACP_BRIDGE_TOKEN ?? '';
  const logger = options.logger ?? console;
  const agents = loadAgentRegistry(options.agentsJson ?? process.env.ACP_AGENTS_JSON, options.agents);

  const loopbackOnly = isLoopbackHost(bindHost);
  if (!loopbackOnly && !bridgeToken) {
    throw new Error(
      `Refusing to bind to non-loopback host "${bindHost}" without ACP_BRIDGE_TOKEN set. ` +
        `Set ACP_BRIDGE_TOKEN=<secret> before starting on 0.0.0.0 or a Tailscale IP.`,
    );
  }

  const authRequired = !loopbackOnly || Boolean(bridgeToken);
  const pool = new Map(); // poolKey -> AgentProcessEntry

  function removePoolEntry(key, entry) {
    if (pool.get(key) === entry) {
      pool.delete(key);
    }
  }

  const API_CORS_HEADERS = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type',
  };

  async function serveStatic(res, pathname, method = 'GET') {
    const cleanPath = pathname === '/' ? '/index.html' : pathname;
    let decoded;
    try {
      decoded = decodeURIComponent(cleanPath);
    } catch {
      res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Bad Request');
      return;
    }

    const resolved = path.resolve(PUBLIC_DIR, `.${decoded}`);
    if (resolved !== PUBLIC_DIR && !resolved.startsWith(PUBLIC_DIR + path.sep)) {
      res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Forbidden');
      return;
    }

    try {
      const stat = await fs.stat(resolved);
      if (!stat.isFile()) {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('Not Found');
        return;
      }
      const ext = path.extname(resolved).toLowerCase();
      const contentType = MIME_TYPES[ext] || 'application/octet-stream';
      const cacheControl = ext === '.svg' || ext === '.png' ? 'public, max-age=3600' : 'no-cache';

      res.writeHead(200, {
        'Content-Type': contentType,
        'Cache-Control': cacheControl,
        'X-Content-Type-Options': 'nosniff',
      });
      if (method === 'HEAD') {
        res.end();
        return;
      }
      const content = await fs.readFile(resolved);
      res.end(content);
    } catch {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Not Found');
    }
  }

  const httpServer = http.createServer(async (req, res) => {
    const urlObj = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);

    if (req.method === 'OPTIONS' && urlObj.pathname.startsWith('/api/')) {
      res.writeHead(204, API_CORS_HEADERS);
      res.end();
      return;
    }

    if (urlObj.pathname === '/api/health') {
      res.writeHead(200, {
        'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'no-store',
        ...API_CORS_HEADERS,
      });
      res.end(
        JSON.stringify({
          ok: true,
          name: 'xr-acp-companion',
          version: '0.3.0',
          authRequired,
          activeProcesses: pool.size,
        }),
      );
      return;
    }

    if (urlObj.pathname === '/api/config') {
      if (authRequired) {
        const provided = extractToken(req, urlObj);
        if (!verifyToken(bridgeToken, provided)) {
          res.writeHead(401, {
            'Content-Type': 'application/json; charset=utf-8',
            'Cache-Control': 'no-store',
            ...API_CORS_HEADERS,
          });
          res.end(JSON.stringify({ error: 'Unauthorized: valid token required' }));
          return;
        }
      }

      const agentList = Object.entries(agents).map(([key, def]) => ({
        key,
        label: def.label,
      }));

      res.writeHead(200, {
        'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'no-store',
        ...API_CORS_HEADERS,
      });
      res.end(
        JSON.stringify({
          workspaceDir,
          defaultAgent: agents[defaultAgent] ? defaultAgent : Object.keys(agents)[0],
          agents: agentList,
          authRequired,
          keepAliveMs,
        }),
      );
      return;
    }

    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Method Not Allowed');
      return;
    }

    await serveStatic(res, urlObj.pathname, req.method);
  });

  const wss = new WebSocketServer({ noServer: true });

  httpServer.on('upgrade', (req, socket, head) => {
    const urlObj = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);

    if (authRequired) {
      const provided = extractToken(req, urlObj);
      if (!verifyToken(bridgeToken, provided)) {
        socket.write('HTTP/1.1 401 Unauthorized\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nUnauthorized');
        socket.destroy();
        return;
      }
    }

    const requestedAgent = urlObj.searchParams.get('agent') || defaultAgent;
    const agentDef = agents[requestedAgent];
    if (!agentDef) {
      socket.write(`HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nUnknown agent: ${requestedAgent}`);
      socket.destroy();
      return;
    }

    const slot = urlObj.searchParams.get('slot') || 'default';
    const shouldReset = urlObj.searchParams.get('reset') === '1';
    const poolKey = `${requestedAgent}::${slot}`;

    wss.handleUpgrade(req, socket, head, (ws) => {
      if (shouldReset && pool.has(poolKey)) {
        pool.get(poolKey).destroy();
      }

      let entry = pool.get(poolKey);
      if (!entry || entry.closed) {
        entry = new AgentProcessEntry({
          key: poolKey,
          agentKey: requestedAgent,
          agentDef,
          workspaceDir,
          keepAliveMs,
          onRemove: removePoolEntry,
          logger,
        });
        pool.set(poolKey, entry);
      }

      entry.attach(ws);
    });
  });

  function start() {
    return new Promise((resolve, reject) => {
      httpServer.once('error', reject);
      httpServer.listen(port, bindHost, () => {
        httpServer.removeListener('error', reject);
        const addr = httpServer.address();
        const actualPort = typeof addr === 'object' && addr ? addr.port : port;
        logger.log(
          `[xr-acp-companion] Listening on http://${bindHost}:${actualPort} ` +
            `(workspace=${workspaceDir}, defaultAgent=${defaultAgent}, auth=${authRequired ? 'token' : 'none'})`,
        );
        resolve({ host: bindHost, port: actualPort });
      });
    });
  }

  function close() {
    for (const entry of [...pool.values()]) {
      entry.destroy();
    }
    pool.clear();
    return new Promise((resolve) => {
      wss.close(() => {
        httpServer.close(() => resolve());
      });
    });
  }

  return {
    httpServer,
    wss,
    pool,
    start,
    close,
  };
}

// Direct CLI execution
if (process.argv[1] && path.resolve(process.argv[1]) === __filename) {
  let server;
  try {
    server = createBridgeServer();
    await server.start();
  } catch (err) {
    console.error(`[xr-acp-companion] Fatal: ${err.message}`);
    process.exit(1);
  }

  const shutdown = async (sig) => {
    console.log(`\n[xr-acp-companion] Received ${sig}, shutting down...`);
    await server.close();
    process.exit(0);
  };
  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));
}

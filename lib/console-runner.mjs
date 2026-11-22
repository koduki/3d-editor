/**
 * Lightweight, zero-native-dependency Command Console & ACP terminal/* runner.
 * Uses Node.js child_process.spawn instead of node-pty so it runs cleanly on
 * Android 15 AVF Linux, Alpine PRoot, Termux, and remote Linux/macOS hosts.
 */

import { spawn } from 'node:child_process';
import crypto from 'node:crypto';

const DEFAULT_MAX_BUFFER = 256 * 1024; // 256 KB per command

export class ConsoleRunner {
  constructor({ workspaceDir, logger = console }) {
    this.workspaceDir = workspaceDir;
    this.logger = logger;
    this.processes = new Map(); // id -> entry
  }

  exec({ id = null, command, args = null, cwd = null, onOutput = null, onExit = null }) {
    const cmdId = id || `term_${crypto.randomBytes(4).toString('hex')}`;
    const effectiveCwd = cwd || this.workspaceDir;
    const startedAt = Date.now();

    const useProcessGroup = process.platform !== 'win32';
    let child;
    if (Array.isArray(args)) {
      child = spawn(command, args.map(String), {
        cwd: effectiveCwd,
        detached: useProcessGroup,
        stdio: ['pipe', 'pipe', 'pipe'],
        env: { ...process.env, FORCE_COLOR: '1', TERM: 'xterm-256color' },
      });
    } else {
      const shell = process.platform === 'win32' ? 'cmd.exe' : '/bin/sh';
      const shellArgs = process.platform === 'win32' ? ['/c', String(command)] : ['-c', String(command)];
      child = spawn(shell, shellArgs, {
        cwd: effectiveCwd,
        detached: useProcessGroup,
        stdio: ['pipe', 'pipe', 'pipe'],
        env: { ...process.env, FORCE_COLOR: '1', TERM: 'xterm-256color' },
      });
    }

    let resolveExit;
    const exitPromise = new Promise((resolve) => {
      resolveExit = resolve;
    });

    const entry = {
      id: cmdId,
      command: Array.isArray(args) ? `${command} ${args.join(' ')}`.trim() : String(command),
      cwd: effectiveCwd,
      pid: child.pid,
      useProcessGroup,
      child,
      output: '',
      truncated: false,
      exitStatus: null,
      exitPromise,
      startedAt,
    };

    const appendOutput = (stream, chunk) => {
      const text = typeof chunk === 'string' ? chunk : chunk.toString('utf8');
      entry.output += text;
      if (entry.output.length > DEFAULT_MAX_BUFFER) {
        entry.output = entry.output.slice(entry.output.length - DEFAULT_MAX_BUFFER);
        entry.truncated = true;
      }
      onOutput?.({ id: cmdId, stream, data: text });
    };

    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (chunk) => appendOutput('stdout', chunk));

    child.stderr.setEncoding('utf8');
    child.stderr.on('data', (chunk) => appendOutput('stderr', chunk));

    child.on('error', (err) => {
      appendOutput('stderr', `\n[spawn error] ${err.message}\n`);
      if (!entry.exitStatus) {
        entry.exitStatus = { exitCode: 127, signal: null };
        const durationMs = Date.now() - startedAt;
        resolveExit(entry.exitStatus);
        onExit?.({ id: cmdId, code: 127, signal: null, durationMs });
      }
    });

    child.on('exit', (code, signal) => {
      if (!entry.exitStatus) {
        entry.exitStatus = { exitCode: code, signal };
        const durationMs = Date.now() - startedAt;
        resolveExit(entry.exitStatus);
        onExit?.({ id: cmdId, code, signal, durationMs });
      }
    });

    this.processes.set(cmdId, entry);
    return entry;
  }

  #signalEntry(entry, signal) {
    if (entry.useProcessGroup && entry.pid) {
      try {
        process.kill(-entry.pid, signal);
        return true;
      } catch {
        // fallback to direct child kill
      }
    }
    try {
      entry.child.kill(signal);
      return true;
    } catch {
      return false;
    }
  }

  get(id) {
    return this.processes.get(id) || null;
  }

  sendInput(id, data) {
    const entry = this.processes.get(id);
    if (!entry || !entry.child.stdin || entry.child.stdin.destroyed) return false;
    entry.child.stdin.write(String(data));
    return true;
  }

  kill(id, signal = 'SIGINT') {
    const entry = this.processes.get(id);
    if (!entry) return false;
    if (entry.exitStatus) return true;
    return this.#signalEntry(entry, signal);
  }

  release(id) {
    const entry = this.processes.get(id);
    if (!entry) return false;
    if (!entry.exitStatus) {
      this.#signalEntry(entry, 'SIGTERM');
    }
    this.processes.delete(id);
    return true;
  }

  killAll() {
    for (const id of [...this.processes.keys()]) {
      this.release(id);
    }
  }
}

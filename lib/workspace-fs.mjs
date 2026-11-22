/**
 * Workspace File System & Git helper for XR-ACP Spatial Desktop IDE.
 * All paths are strictly confined within `workspaceDir` (fail-closed against traversal/symlink escapes).
 */

import fs from 'node:fs/promises';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);

const IGNORED_DIRS = new Set(['.git', 'node_modules', '.next', 'dist', 'build', '.cache']);
const MAX_READ_BYTES = 2 * 1024 * 1024; // 2 MB
const MAX_SEARCH_FILES = 400;

export class WorkspaceSecurityError extends Error {
  constructor(message) {
    super(message);
    this.statusCode = 403;
  }
}

export class WorkspaceNotFoundError extends Error {
  constructor(message) {
    super(message);
    this.statusCode = 404;
  }
}

export function normalizeRelPath(input = '') {
  const cleaned = String(input || '')
    .replace(/\\/g, '/')
    .replace(/^\/+/, '')
    .replace(/\/+$/, '');
  return cleaned;
}

/**
 * Resolve a relative workspace path and verify it stays strictly inside `workspaceDir`.
 */
export async function resolveWorkspacePath(workspaceDir, relPath = '', { mustExist = false } = {}) {
  const rootReal = await fs.realpath(workspaceDir);
  const cleanRel = normalizeRelPath(relPath);
  const candidate = path.resolve(rootReal, cleanRel);

  if (candidate !== rootReal && !candidate.startsWith(rootReal + path.sep)) {
    throw new WorkspaceSecurityError(`Path escapes workspace root: ${relPath}`);
  }

  // Verify realpath for existing target or nearest existing ancestor to block symlink escapes
  let checkPath = candidate;
  while (true) {
    try {
      const real = await fs.realpath(checkPath);
      if (real !== rootReal && !real.startsWith(rootReal + path.sep)) {
        throw new WorkspaceSecurityError(`Symlink target escapes workspace root: ${relPath}`);
      }
      break;
    } catch (err) {
      if (err instanceof WorkspaceSecurityError) throw err;
      if (mustExist && checkPath === candidate) {
        throw new WorkspaceNotFoundError(`File not found: ${relPath}`);
      }
      const parent = path.dirname(checkPath);
      if (parent === checkPath) break;
      checkPath = parent;
    }
  }

  const normalizedRel = path.relative(rootReal, candidate).split(path.sep).join('/');
  return { absPath: candidate, relPath: normalizedRel, rootReal };
}

export async function listWorkspaceTree(workspaceDir, relPath = '', { maxDepth = 4, includeHidden = true } = {}) {
  const { absPath, rootReal } = await resolveWorkspacePath(workspaceDir, relPath, { mustExist: true });

  async function walk(dirPath, depth) {
    let dirents;
    try {
      dirents = await fs.readdir(dirPath, { withFileTypes: true });
    } catch {
      return [];
    }

    const entries = [];
    for (const d of dirents) {
      if (IGNORED_DIRS.has(d.name)) continue;
      if (!includeHidden && d.name.startsWith('.')) continue;

      const full = path.join(dirPath, d.name);
      const rel = path.relative(rootReal, full).split(path.sep).join('/');
      const isDir = d.isDirectory();

      const node = {
        name: d.name,
        path: rel,
        type: isDir ? 'dir' : 'file',
      };

      if (isDir) {
        node.children = depth < maxDepth ? await walk(full, depth + 1) : [];
      }
      entries.push(node);
    }

    entries.sort((a, b) => {
      if (a.type !== b.type) return a.type === 'dir' ? -1 : 1;
      return a.name.localeCompare(b.name, 'en', { sensitivity: 'base' });
    });

    return entries;
  }

  return walk(absPath, 0);
}

export async function readWorkspaceFile(workspaceDir, relPath) {
  const { absPath, relPath: normRel } = await resolveWorkspacePath(workspaceDir, relPath, { mustExist: true });
  const stat = await fs.stat(absPath);
  if (!stat.isFile()) {
    throw new WorkspaceNotFoundError(`Not a regular file: ${relPath}`);
  }
  if (stat.size > MAX_READ_BYTES) {
    const err = new Error(`File too large to open in editor (${stat.size} bytes)`);
    err.statusCode = 413;
    throw err;
  }

  const buf = await fs.readFile(absPath);
  // Detect binary content via NUL byte scan in first 8 KB
  const sampleLen = Math.min(buf.length, 8192);
  for (let i = 0; i < sampleLen; i++) {
    if (buf[i] === 0) {
      return {
        path: normRel,
        binary: true,
        size: stat.size,
        mtimeMs: stat.mtimeMs,
        content: '',
      };
    }
  }

  return {
    path: normRel,
    binary: false,
    size: stat.size,
    mtimeMs: stat.mtimeMs,
    content: buf.toString('utf8'),
  };
}

export async function writeWorkspaceFile(workspaceDir, relPath, content) {
  if (!normalizeRelPath(relPath)) {
    throw new WorkspaceSecurityError('Invalid empty file path');
  }
  const { absPath, relPath: normRel } = await resolveWorkspacePath(workspaceDir, relPath);
  await fs.mkdir(path.dirname(absPath), { recursive: true });
  await fs.writeFile(absPath, String(content ?? ''), 'utf8');
  const stat = await fs.stat(absPath);
  return { path: normRel, size: stat.size, mtimeMs: stat.mtimeMs };
}

export async function createWorkspaceDir(workspaceDir, relPath) {
  if (!normalizeRelPath(relPath)) {
    throw new WorkspaceSecurityError('Invalid empty directory path');
  }
  const { absPath, relPath: normRel } = await resolveWorkspacePath(workspaceDir, relPath);
  await fs.mkdir(absPath, { recursive: true });
  return { path: normRel };
}

export async function renameWorkspaceEntry(workspaceDir, fromRel, toRel) {
  if (!normalizeRelPath(fromRel) || !normalizeRelPath(toRel)) {
    throw new WorkspaceSecurityError('Invalid rename path');
  }
  const from = await resolveWorkspacePath(workspaceDir, fromRel, { mustExist: true });
  const to = await resolveWorkspacePath(workspaceDir, toRel);
  await fs.mkdir(path.dirname(to.absPath), { recursive: true });
  await fs.rename(from.absPath, to.absPath);
  return { from: from.relPath, to: to.relPath };
}

export async function deleteWorkspaceEntry(workspaceDir, relPath) {
  const clean = normalizeRelPath(relPath);
  if (!clean) {
    throw new WorkspaceSecurityError('Refusing to delete workspace root');
  }
  const { absPath, relPath: normRel } = await resolveWorkspacePath(workspaceDir, clean, { mustExist: true });
  await fs.rm(absPath, { recursive: true, force: true });
  return { deleted: normRel };
}

export async function searchWorkspaceText(workspaceDir, query, { maxResults = 60 } = {}) {
  const q = String(query || '').trim();
  if (!q) return [];
  const qLower = q.toLowerCase();
  const rootReal = await fs.realpath(workspaceDir);

  const results = [];
  let filesScanned = 0;

  async function scanDir(dirPath) {
    if (results.length >= maxResults || filesScanned >= MAX_SEARCH_FILES) return;
    let dirents;
    try {
      dirents = await fs.readdir(dirPath, { withFileTypes: true });
    } catch {
      return;
    }

    for (const d of dirents) {
      if (results.length >= maxResults || filesScanned >= MAX_SEARCH_FILES) return;
      if (IGNORED_DIRS.has(d.name)) continue;

      const full = path.join(dirPath, d.name);
      if (d.isDirectory()) {
        await scanDir(full);
      } else if (d.isFile()) {
        filesScanned++;
        let stat;
        try {
          stat = await fs.stat(full);
        } catch {
          continue;
        }
        if (stat.size > 256 * 1024) continue;

        let buf;
        try {
          buf = await fs.readFile(full);
        } catch {
          continue;
        }
        if (buf.subarray(0, 2048).includes(0)) continue;

        const text = buf.toString('utf8');
        if (!text.toLowerCase().includes(qLower)) continue;

        const rel = path.relative(rootReal, full).split(path.sep).join('/');
        const lines = text.split(/\r?\n/);
        for (let i = 0; i < lines.length; i++) {
          if (lines[i].toLowerCase().includes(qLower)) {
            results.push({
              path: rel,
              line: i + 1,
              text: lines[i].trim().slice(0, 160),
            });
            if (results.length >= maxResults) return;
          }
        }
      }
    }
  }

  await scanDir(rootReal);
  return results;
}

export async function getGitStatus(workspaceDir) {
  try {
    const [{ stdout: branchOut }, { stdout: statusOut }] = await Promise.all([
      execFileAsync('git', ['rev-parse', '--abbrev-ref', 'HEAD'], { cwd: workspaceDir, timeout: 4000 }).catch(() => ({
        stdout: 'HEAD',
      })),
      execFileAsync('git', ['status', '--porcelain=v1', '-uall'], { cwd: workspaceDir, timeout: 4000 }),
    ]);

    const files = {};
    for (const line of statusOut.split(/\r?\n/)) {
      if (!line || line.length < 4) continue;
      const code = line.slice(0, 2);
      let filePath = line.slice(3).trim();
      const arrowIdx = filePath.indexOf(' -> ');
      if (arrowIdx >= 0) filePath = filePath.slice(arrowIdx + 4);
      filePath = filePath.replace(/^"|"$/g, '');
      files[filePath] = code.trim() || 'M';
    }

    return {
      isGitRepo: true,
      branch: branchOut.trim() || 'main',
      files,
    };
  } catch {
    return {
      isGitRepo: false,
      branch: '',
      files: {},
    };
  }
}

export async function getGitDiff(workspaceDir, relPath = '') {
  try {
    const args = ['diff', '--no-color'];
    if (relPath) {
      const { relPath: norm } = await resolveWorkspacePath(workspaceDir, relPath);
      args.push('--', norm);
    }
    const { stdout } = await execFileAsync('git', args, {
      cwd: workspaceDir,
      timeout: 5000,
      maxBuffer: 2 * 1024 * 1024,
    });
    return { ok: true, diff: stdout };
  } catch (err) {
    return { ok: false, diff: '', error: err?.message || 'git diff failed' };
  }
}

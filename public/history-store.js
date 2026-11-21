/**
 * Local session history cache (v0.2).
 * Restores conversation timeline & Unified Diff cards immediately after reload or
 * sleep resume even when an ACP agent (e.g. `agy acp`) does not replay history on `session/resume`.
 * Uses IndexedDB when available with transparent localStorage / memory fallback.
 */

const DB_NAME = 'xr-acp-companion-history';
const DB_VERSION = 1;
const STORE_NAME = 'sessions';
const LS_FALLBACK_KEY = 'xr-acp-companion:history-fallback:v1';
const MAX_SAVED_SESSIONS = 30;

export function deriveSessionTitle(entries, fallback = '新しいセッション') {
  if (Array.isArray(entries)) {
    const firstUser = entries.find((e) => e && e.type === 'user' && typeof e.text === 'string' && e.text.trim());
    if (firstUser) {
      const singleLine = firstUser.text.trim().replace(/\s+/g, ' ');
      return singleLine.length > 48 ? `${singleLine.slice(0, 48)}…` : singleLine;
    }
  }
  return fallback;
}

function normalizeRecord(record) {
  const now = Date.now();
  const entries = Array.isArray(record.entries) ? record.entries : [];
  return {
    sessionId: String(record.sessionId),
    profileId: String(record.profileId || 'local-aura'),
    agentKey: String(record.agentKey || 'agy'),
    title: record.title || deriveSessionTitle(entries),
    modeId: record.modeId || 'default',
    usage: record.usage || null,
    createdAt: typeof record.createdAt === 'number' ? record.createdAt : now,
    updatedAt: typeof record.updatedAt === 'number' ? record.updatedAt : now,
    entries,
  };
}

function createFallbackBackend(storage) {
  const memoryMap = new Map();

  function readAll() {
    if (!storage) return [...memoryMap.values()];
    try {
      const raw = storage.getItem(LS_FALLBACK_KEY);
      if (!raw) return [];
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }

  function writeAll(items) {
    const sorted = [...items]
      .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
      .slice(0, MAX_SAVED_SESSIONS);
    if (!storage) {
      memoryMap.clear();
      for (const item of sorted) memoryMap.set(item.sessionId, item);
      return;
    }
    try {
      storage.setItem(LS_FALLBACK_KEY, JSON.stringify(sorted));
    } catch {
      // Trim aggressively if quota exceeded
      try {
        storage.setItem(LS_FALLBACK_KEY, JSON.stringify(sorted.slice(0, 10)));
      } catch {
        // ignore
      }
    }
  }

  return {
    async get(sessionId) {
      return readAll().find((s) => s.sessionId === sessionId) || null;
    },
    async put(record) {
      const all = readAll().filter((s) => s.sessionId !== record.sessionId);
      all.unshift(record);
      writeAll(all);
    },
    async getAll() {
      return readAll().sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
    },
    async delete(sessionId) {
      writeAll(readAll().filter((s) => s.sessionId !== sessionId));
    },
    async clear() {
      writeAll([]);
    },
  };
}

function createIdbBackend(idbFactory) {
  let dbPromise = null;

  function openDb() {
    if (dbPromise) return dbPromise;
    dbPromise = new Promise((resolve, reject) => {
      const req = idbFactory.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains(STORE_NAME)) {
          const store = db.createObjectStore(STORE_NAME, { keyPath: 'sessionId' });
          store.createIndex('updatedAt', 'updatedAt', { unique: false });
        }
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error || new Error('Failed to open IndexedDB'));
    });
    return dbPromise;
  }

  async function withStore(mode, fn) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, mode);
      const store = tx.objectStore(STORE_NAME);
      let result;
      try {
        result = fn(store);
      } catch (err) {
        reject(err);
        return;
      }
      if (result && typeof result.onsuccess !== 'undefined') {
        result.onsuccess = () => resolve(result.result);
        result.onerror = () => reject(result.error);
      } else {
        tx.oncomplete = () => resolve(result);
        tx.onerror = () => reject(tx.error);
      }
    });
  }

  return {
    async get(sessionId) {
      return (await withStore('readonly', (s) => s.get(sessionId))) || null;
    },
    async put(record) {
      await withStore('readwrite', (s) => s.put(record));
      const all = await this.getAll();
      if (all.length > MAX_SAVED_SESSIONS) {
        const overflow = all.slice(MAX_SAVED_SESSIONS);
        await withStore('readwrite', (s) => {
          for (const old of overflow) s.delete(old.sessionId);
        });
      }
    },
    async getAll() {
      const items = (await withStore('readonly', (s) => s.getAll())) || [];
      return items.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
    },
    async delete(sessionId) {
      await withStore('readwrite', (s) => s.delete(sessionId));
    },
    async clear() {
      await withStore('readwrite', (s) => s.clear());
    },
  };
}

export function createHistoryStore({
  indexedDB: idb = globalThis.indexedDB,
  storage = globalThis.localStorage,
} = {}) {
  const fallback = createFallbackBackend(storage);
  const primary = idb ? createIdbBackend(idb) : fallback;

  async function runWithFallback(op) {
    try {
      return await op(primary);
    } catch {
      return op(fallback);
    }
  }

  return {
    async getSession(sessionId) {
      if (!sessionId) return null;
      return runWithFallback((b) => b.get(sessionId));
    },

    async saveSession(record) {
      if (!record?.sessionId) return null;
      const normalized = normalizeRecord(record);
      await runWithFallback((b) => b.put(normalized));
      return normalized;
    },

    async listSessions({ profileId = null, agentKey = null } = {}) {
      const all = await runWithFallback((b) => b.getAll());
      return all.filter((s) => {
        if (profileId && s.profileId !== profileId) return false;
        if (agentKey && s.agentKey !== agentKey) return false;
        return true;
      });
    },

    async deleteSession(sessionId) {
      if (!sessionId) return;
      await runWithFallback((b) => b.delete(sessionId));
    },

    async clearAll() {
      await runWithFallback((b) => b.clear());
    },
  };
}

/**
 * Connection profile management & Tailscale/remote bridge diagnostics (v0.2).
 * Supports multiple named profiles (local AURA, remote Tailscale Quest 3, offline Demo).
 */

export const PROFILES_STORAGE_KEY = 'xr-acp-companion:profiles:v2';
export const LEGACY_SETTINGS_KEY = 'xr-acp-companion:settings:v1';

export function createDefaultProfiles() {
  return [
    {
      id: 'local-aura',
      name: 'AURA ローカル (127.0.0.1)',
      mode: 'bridge',
      wsUrl: '',
      agent: '',
      token: '',
      workspaceDir: '',
      preferredTheme: '',
    },
    {
      id: 'remote-tailscale',
      name: 'リモート PC (Tailscale / Quest 3)',
      mode: 'bridge',
      wsUrl: 'ws://100.64.0.10:8765',
      agent: 'agy',
      token: '',
      workspaceDir: '',
      preferredTheme: 'vst',
    },
    {
      id: 'offline-demo',
      name: 'オフラインデモ (サーバー不要)',
      mode: 'demo',
      wsUrl: '',
      agent: 'demo',
      token: '',
      workspaceDir: '/workspace/xr-blog',
      preferredTheme: '',
    },
  ];
}

export function normalizeProfile(raw, fallbackId = null) {
  const id = String(raw?.id || fallbackId || `profile-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`);
  const mode = raw?.mode === 'demo' ? 'demo' : 'bridge';
  const preferredTheme = raw?.preferredTheme === 'ost' || raw?.preferredTheme === 'vst' ? raw.preferredTheme : '';
  return {
    id,
    name: String(raw?.name || (mode === 'demo' ? 'オフラインデモ' : '新規プロファイル')).trim() || 'プロファイル',
    mode,
    wsUrl: mode === 'demo' ? '' : String(raw?.wsUrl || '').trim(),
    agent: String(raw?.agent || '').trim(),
    token: String(raw?.token || '').trim(),
    workspaceDir: String(raw?.workspaceDir || '').trim(),
    preferredTheme,
  };
}

export function loadProfileStore(storage = globalThis.localStorage, queryString = '') {
  const defaults = {
    activeProfileId: 'local-aura',
    theme: 'ost',
    dimOnBlur: true,
    profiles: createDefaultProfiles(),
  };

  let loaded = null;
  try {
    const raw = storage?.getItem(PROFILES_STORAGE_KEY);
    if (raw) {
      loaded = JSON.parse(raw);
    }
  } catch {
    loaded = null;
  }

  if (loaded && Array.isArray(loaded.profiles) && loaded.profiles.length > 0) {
    defaults.profiles = loaded.profiles.map((p) => normalizeProfile(p));
    defaults.activeProfileId = loaded.activeProfileId || defaults.profiles[0].id;
    defaults.theme = loaded.theme === 'vst' ? 'vst' : 'ost';
    defaults.dimOnBlur = loaded.dimOnBlur !== false;
  } else {
    // Seamless migration from v0.1 settings key
    try {
      const legacyRaw = storage?.getItem(LEGACY_SETTINGS_KEY);
      if (legacyRaw) {
        const legacy = JSON.parse(legacyRaw);
        defaults.theme = legacy.theme === 'vst' ? 'vst' : 'ost';
        defaults.dimOnBlur = legacy.dimOnBlur !== false;
        const first = defaults.profiles[0];
        if (legacy.agent) first.agent = legacy.agent;
        if (legacy.customWsUrl) first.wsUrl = legacy.customWsUrl;
        if (legacy.token) first.token = legacy.token;
        if (legacy.workspaceOverride) first.workspaceDir = legacy.workspaceOverride;
      }
    } catch {
      // ignore legacy parse error
    }
  }

  // Ensure activeProfileId exists
  if (!defaults.profiles.some((p) => p.id === defaults.activeProfileId)) {
    defaults.activeProfileId = defaults.profiles[0].id;
  }

  // Apply URL query overrides (?demo=1, ?profile=..., ?agent=..., ?token=..., ?theme=ost|vst)
  if (queryString) {
    const params = new URLSearchParams(queryString);
    if (params.get('demo') === '1' || params.get('mode') === 'demo') {
      const demo = defaults.profiles.find((p) => p.mode === 'demo');
      if (demo) defaults.activeProfileId = demo.id;
    } else if (params.get('profile')) {
      const matched = defaults.profiles.find((p) => p.id === params.get('profile'));
      if (matched) defaults.activeProfileId = matched.id;
    }

    const active = getActiveProfile(defaults);
    if (params.get('agent') && active.mode !== 'demo') active.agent = params.get('agent');
    if (params.get('token') && active.mode !== 'demo') active.token = params.get('token');
    if (params.get('ws') && active.mode !== 'demo') active.wsUrl = params.get('ws');
    if (params.get('theme') === 'ost' || params.get('theme') === 'vst') {
      defaults.theme = params.get('theme');
    }
  }

  return defaults;
}

export function saveProfileStore(store, storage = globalThis.localStorage) {
  try {
    storage?.setItem(PROFILES_STORAGE_KEY, JSON.stringify(store));
  } catch {
    // ignore quota errors
  }
}

export function getActiveProfile(store) {
  return store.profiles.find((p) => p.id === store.activeProfileId) || store.profiles[0];
}

export function upsertProfile(store, profileInput) {
  const normalized = normalizeProfile(profileInput);
  const idx = store.profiles.findIndex((p) => p.id === normalized.id);
  if (idx >= 0) {
    store.profiles[idx] = normalized;
  } else {
    store.profiles.push(normalized);
  }
  return normalized;
}

export function deleteProfile(store, profileId) {
  if (store.profiles.length <= 1) return false;
  const idx = store.profiles.findIndex((p) => p.id === profileId);
  if (idx < 0) return false;
  store.profiles.splice(idx, 1);
  if (store.activeProfileId === profileId) {
    store.activeProfileId = store.profiles[0].id;
  }
  return true;
}

/**
 * Derive HTTP(S) origin from a WS(S) URL or page origin.
 */
export function deriveHttpBaseUrl(wsUrl, pageOrigin = '') {
  const trimmed = String(wsUrl || '').trim();
  if (!trimmed) {
    if (!pageOrigin) throw new Error('接続先 URL が空です');
    return pageOrigin.replace(/\/+$/, '');
  }

  let parsed;
  try {
    parsed = new URL(trimmed);
  } catch {
    throw new Error(`無効な URL 形式です: ${trimmed}`);
  }

  if (parsed.protocol === 'ws:') {
    parsed.protocol = 'http:';
  } else if (parsed.protocol === 'wss:') {
    parsed.protocol = 'https:';
  } else if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error(`ws:// または wss:// で始まる URL を指定してください (${parsed.protocol})`);
  }

  return parsed.origin;
}

/**
 * Build WebSocket URL for connecting to ws-acp-bridge.
 */
export function buildProfileWsUrl(profile, { defaultAgent = 'agy', pageOrigin = '' } = {}) {
  const agentKey = profile.agent || defaultAgent || 'agy';
  let base = String(profile.wsUrl || '').trim();

  if (!base) {
    const originUrl = new URL(pageOrigin || 'http://127.0.0.1:8765');
    originUrl.protocol = originUrl.protocol === 'https:' ? 'wss:' : 'ws:';
    originUrl.pathname = '/';
    originUrl.search = '';
    base = originUrl.toString();
  } else {
    const parsed = new URL(base);
    if (parsed.protocol === 'http:') parsed.protocol = 'ws:';
    if (parsed.protocol === 'https:') parsed.protocol = 'wss:';
    base = parsed.toString();
  }

  const url = new URL(base);
  url.searchParams.set('agent', agentKey);
  if (profile.token) {
    url.searchParams.set('token', profile.token);
  }
  return url.toString();
}

/**
 * Run live diagnostics against /api/health and /api/config to verify reachability,
 * Tailscale routing, round-trip latency, and token authentication.
 */
export async function diagnoseBridgeConnection(
  profile,
  { pageOrigin = '', fetchImpl = globalThis.fetch, timeoutMs = 5000 } = {},
) {
  if (profile.mode === 'demo') {
    return {
      ok: true,
      status: 'demo',
      latencyMs: 0,
      message: 'オフラインデモモード（ローカルブリッジ不要で動作します）',
      hint: '',
    };
  }

  let baseUrl;
  try {
    baseUrl = deriveHttpBaseUrl(profile.wsUrl, pageOrigin);
  } catch (err) {
    return {
      ok: false,
      status: 'invalid_url',
      latencyMs: null,
      message: err.message,
      hint: '例: ws://100.64.0.10:8765 または空欄（同一ホスト）を指定してください。',
    };
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const started = performance.now();
    const healthRes = await fetchImpl(`${baseUrl}/api/health`, {
      cache: 'no-store',
      signal: controller.signal,
    });
    const latencyMs = Math.max(1, Math.round(performance.now() - started));

    if (!healthRes.ok) {
      return {
        ok: false,
        status: 'unreachable',
        latencyMs,
        message: `/api/health が HTTP ${healthRes.status} を返しました`,
        hint: 'server.mjs が起動しているか、ポート番号が一致しているか確認してください。',
      };
    }

    const health = await healthRes.json();

    const headers = {};
    if (profile.token) {
      headers.Authorization = `Bearer ${profile.token}`;
    }

    const configRes = await fetchImpl(`${baseUrl}/api/config`, {
      cache: 'no-store',
      headers,
      signal: controller.signal,
    });

    if (configRes.status === 401) {
      return {
        ok: false,
        status: 'auth_required',
        latencyMs,
        health,
        message: `到達可能 (${latencyMs}ms) ですが認証トークンが一致しません (401)`,
        hint: 'ホスト側で設定した ACP_BRIDGE_TOKEN と一致するトークンを入力してください。',
      };
    }

    if (!configRes.ok) {
      return {
        ok: false,
        status: 'unreachable',
        latencyMs,
        health,
        message: `/api/config が HTTP ${configRes.status} を返しました`,
        hint: 'ブリッジのバージョンと設定を確認してください。',
      };
    }

    const config = await configRes.json();
    const agentNames = (config.agents || []).map((a) => a.key).join(', ');
    return {
      ok: true,
      status: 'ok',
      latencyMs,
      health,
      config,
      message: `接続 OK (${latencyMs}ms) — ワークスペース: ${config.workspaceDir}`,
      hint: `利用可能エージェント: ${agentNames || config.defaultAgent}`,
    };
  } catch (err) {
    const isAbort = err?.name === 'AbortError';
    return {
      ok: false,
      status: 'unreachable',
      latencyMs: null,
      message: isAbort
        ? `接続タイムアウト (${timeoutMs}ms): ${baseUrl}`
        : `接続できません: ${baseUrl} (${err?.message || 'Network error'})`,
      hint:
        'Tailscale VPN の接続状態、ホスト側の BIND_HOST=0.0.0.0（または Tailscale IP）、ファイアウォール設定を確認してください。',
    };
  } finally {
    clearTimeout(timer);
  }
}

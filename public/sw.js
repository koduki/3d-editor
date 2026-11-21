// Network-First Service Worker with Offline App Shell Precache.
// - Online: always hits the network first so Termux edit -> browser reload is instantaneous.
// - Offline: falls back to precached shell so Demo Mode & TWA / Meta Horizon Store launch cleanly.

const CACHE_NAME = 'xr-acp-companion-v3';
const SHELL_ASSETS = [
  '/',
  '/index.html',
  '/style.css',
  '/app.js',
  '/acp-client.js',
  '/demo-client.js',
  '/profiles.js',
  '/history-store.js',
  '/diff.js',
  '/markdown.js',
  '/manifest.json',
  '/privacy.html',
  '/icon.svg',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
  '/icons/icon-maskable-512.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE_NAME)
      .then((cache) => cache.addAll(SHELL_ASSETS))
      .catch(() => {})
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin || url.pathname.startsWith('/api/')) {
    return;
  }

  event.respondWith(
    fetch(request)
      .then((response) => {
        if (response && response.ok) {
          const clone = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(request, clone)).catch(() => {});
        }
        return response;
      })
      .catch(async () => {
        const exact = await caches.match(request);
        if (exact) return exact;
        if (request.mode === 'navigate') {
          return caches.match('/index.html');
        }
        return Response.error();
      }),
  );
});

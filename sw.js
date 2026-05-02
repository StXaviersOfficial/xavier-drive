const CACHE = 'xavierdrive-v1';

// Core files to cache for offline use
const PRECACHE = [
  '/xavier-drive/',
  '/xavier-drive/index.html',
  '/xavier-drive/manifest.json',
  '/xavier-drive/icon-192.png',
  '/xavier-drive/icon-512.png',
];

// Install — cache core files
self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE).then(cache => cache.addAll(PRECACHE))
  );
  self.skipWaiting();
});

// Activate — clean old caches
self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
    )
  );
  self.clients.claim();
});

// Fetch — network first, fall back to cache
self.addEventListener('fetch', e => {
  // Skip non-GET and cross-origin API calls (Groq, Google Drive, etc.)
  if (e.request.method !== 'GET') return;
  const url = new URL(e.request.url);
  if (url.hostname.includes('groq.com') ||
      url.hostname.includes('googleapis.com') ||
      url.hostname.includes('google.com') ||
      url.hostname.includes('gstatic.com') ||
      url.hostname.includes('fonts.googleapis.com')) return;

  e.respondWith(
    fetch(e.request)
      .then(res => {
        // Cache successful responses from our own origin
        if (res && res.status === 200 && url.origin === location.origin) {
          const clone = res.clone();
          caches.open(CACHE).then(cache => cache.put(e.request, clone));
        }
        return res;
      })
      .catch(() => caches.match(e.request))
  );
});

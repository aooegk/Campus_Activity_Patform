// 找个搭子 · 最小 service worker（2026-11-10）：只为让手机能“安装到主屏幕”，不做激进缓存。
// 策略全部是网络优先、离线才用缓存，避免发新版后用户还看到旧版；/api 请求不拦截、不缓存。
const CACHE = 'campus-buddy-v1'
const CORE = ['/', '/index.html', '/manifest.json', '/img/logo.webp', '/img/hero.webp', '/img/cover-1.webp', '/img/cover-2.webp', '/img/cover-3.webp', '/icon-192.png', '/icon-512.png']
self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(CORE)).then(() => self.skipWaiting()))
})
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()))
})
self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return
  const url = new URL(e.request.url)
  if (url.origin !== self.location.origin) return
  if (url.pathname.startsWith('/api')) return
  e.respondWith(
    fetch(e.request).then((res) => {
      if (res && res.ok) {
        const copy = res.clone()
        caches.open(CACHE).then((c) => c.put(e.request, copy))
      }
      return res
    }).catch(() => caches.match(e.request))
  )
})

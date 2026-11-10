// AnixQ edge: a cache in front of AnimeLib's API (Cloudflare Workers).
// - Public GET answers are kept at the edge and refreshed in the background (stale-while-revalidate),
//   so catalog pages, titles and episode lists open in a few dozen milliseconds.
// - If AnimeLib is down or slow, the last good answer is served (up to 7 days old).
// - Requests carrying the user's AnimeLib login (Authorization) are never cached: passed straight through.
const ORIGINS = ['https://api.cdnlibs.org', 'https://api.lib.social'];
const KEEP = 7 * 86400; // how long a stale copy may be served when the origin fails

function ttl(path) {
  if (path.startsWith('/api/constants')) return 86400;
  if (/^\/api\/anime\/[^/]+\/(relations|similar)/.test(path)) return 6 * 3600;
  if (/^\/api\/anime\/[^/]+$/.test(path)) return 3600;
  if (path === '/api/anime') return 600;
  if (/^\/api\/episodes\/\d+/.test(path)) return 300;
  if (path.startsWith('/api/episodes')) return 600;
  return 300;
}

const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'site-id, content-type, authorization', 'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS' };

async function origin(req, url, body) {
  const headers = new Headers();
  for (const h of ['site-id', 'authorization', 'accept', 'content-type', 'user-agent']) {
    const v = req.headers.get(h); if (v) headers.set(h, v);
  }
  if (!headers.has('site-id')) headers.set('site-id', '5');
  headers.set('referer', 'https://anilib.me/');
  headers.set('origin', 'https://anilib.me');
  // Reads: ask every address at once and take the first real answer (one of them is often slow).
  if (req.method === 'GET') {
    try {
      return await Promise.any(ORIGINS.map(async (o) => {
        const r = await fetch(o + url.pathname + url.search, { headers, signal: AbortSignal.timeout(8000) });
        const type = r.headers.get('content-type') || '';
        if (r.status >= 500 || (!type.includes('json') && r.status >= 400)) throw new Error('bad ' + r.status);
        const out = new Response(r.body, r); out.headers.set('x-anixq-origin', o); return out;
      }));
    } catch (e) {
      return new Response(JSON.stringify({ error: 'origin unavailable' }), { status: 502, headers: { 'content-type': 'application/json' } });
    }
  }
  let last;
  for (const o of ORIGINS) {
    try {
      const r = await fetch(o + url.pathname + url.search, { method: req.method, headers, body, signal: AbortSignal.timeout(6000) });
      const type = r.headers.get('content-type') || '';
      // A protection / block page is not an answer: try the next address.
      if (r.status >= 500 || (!type.includes('json') && r.status >= 400)) { last = r; continue; }
      return r;
    } catch (e) { last = null; }
  }
  return last || new Response(JSON.stringify({ error: 'origin unavailable' }), { status: 502, headers: { 'content-type': 'application/json' } });
}

async function store(cache, key, r) {
  const copy = new Response(await r.clone().arrayBuffer(), r);
  const h = new Headers(copy.headers);
  h.set('x-anixq-at', String(Date.now()));
  h.set('cache-control', `public, max-age=${KEEP}`);
  h.delete('set-cookie');
  await cache.put(key, new Response(copy.body, { status: copy.status, headers: h }));
}

function out(r, extra) {
  const h = new Headers(r.headers);
  for (const [k, v] of Object.entries({ ...CORS, ...extra })) h.set(k, v);
  h.delete('set-cookie');
  return new Response(r.body, { status: r.status, headers: h });
}

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    if (req.method === 'OPTIONS') return new Response(null, { headers: CORS });
    // Apps rank API addresses with a HEAD request: answer at once when we're reachable.
    if (req.method === 'HEAD') return new Response(null, { headers: { ...CORS, 'content-type': 'application/json' } });
    if (url.pathname === '/' || url.pathname === '/health') return new Response('AnixQ edge ok', { headers: CORS });
    if (!url.pathname.startsWith('/api/')) return new Response('not found', { status: 404 });

    const personal = req.headers.has('authorization') || url.pathname.startsWith('/api/auth') || url.pathname.startsWith('/api/bookmarks');
    if (req.method !== 'GET' || personal) {
      const body = req.method === 'GET' || req.method === 'HEAD' ? undefined : await req.arrayBuffer();
      return out(await origin(req, url, body), { 'x-anixq-cache': 'pass' });
    }

    const cache = caches.default;
    const site = req.headers.get('site-id') || '5';
    const key = new Request(`https://anixq-cache${url.pathname}${url.search}${url.search ? '&' : '?'}__site=${site}`);
    const hit = await cache.match(key);
    const limit = ttl(url.pathname) * 1000;
    if (hit) {
      const age = Date.now() - Number(hit.headers.get('x-anixq-at') || 0);
      if (age < limit) return out(hit, { 'x-anixq-cache': 'hit' });
      if (age < 86400 * 1000) {
        // Stale but recent: answer now, refresh for the next viewer.
        ctx.waitUntil((async () => { const r = await origin(req, url); if (r.ok) await store(cache, key, r); })());
        return out(hit, { 'x-anixq-cache': 'stale' });
      }
    }
    const r = await origin(req, url);
    if (r.ok) { ctx.waitUntil(store(cache, key, r.clone())); return out(r, { 'x-anixq-cache': 'miss' }); }
    if (hit) return out(hit, { 'x-anixq-cache': 'fallback' });
    return out(r, { 'x-anixq-cache': 'error' });
  },
};

/**
 * 文库 EPUB 工坊 —— 第三方中继（Cloudflare Worker）
 *
 * 用途：直连 wenku8 被 Cloudflare 403 拦下时，为**免登录公开页**提供一条自建通道。
 * 部署到你**自己的** Cloudflare 账号（`wrangler deploy`），再把域名填进 App
 * 「设置 → 网络 → 中继端点」。不要把端点硬编码进 APK。
 *
 * 合规边界（与 AGENTS §4.2 / §4.11 严格一致）：
 * 1. **只代理匿名白名单端点**：articleinfo.php、authorarticle.php、目录页、
 *    /zt/sugoi/、/zt/booklist/；
 * 2. **拒绝一切登录墙内接口**：login.php、search.php、toplist.php、tags.php、
 *    articlelist.php —— 中继不接受会话，永远不产生带 Cookie 的请求；
 * 3. **不转发 Cookie / Authorization**，也不透传客户端 IP；
 * 4. 保留上游原始字节（GBK 编码不改），只做地址改写与缓存；
 * 5. 简单令牌桶限流，防止你的 Worker 被当成刷量跳板。
 *
 * 这是**可选**功能：不部署也能正常使用 App（只是部分网络下直连不通）。
 */

/** 上游基址。 */
const UPSTREAM = 'https://www.wenku8.net';

/**
 * 允许的路径（正则）。与 App 侧 `Wenku8Endpoint.isRelayablePath` 一一对应，
 * 两侧都有契约测试防止漂移。
 */
const ALLOWED = [
  /^\/modules\/article\/articleinfo\.php$/,
  /^\/modules\/article\/authorarticle\.php$/,
  /^\/novel\/\d+\/\d+\/index\.html?$/,
  /^\/zt\/sugoi\/\d{4}\.php$/,
  /^\/zt\/booklist\/\d{6}\.php$/,
];

/** 明确拒绝的登录墙内端点（即使将来 ALLOWED 写错也拒）。 */
const DENIED = [
  /^\/login\.php$/,
  /^\/modules\/article\/(search|toplist|tags|articlelist)\.php$/,
  /^\/api\//,
];

/** 每 IP 每分钟上限（令牌桶，内存态；多实例部署时按实例计）。 */
const RATE_LIMIT = 60;
const buckets = new Map();

/** 5 分钟缓存：榜单/目录这类页面变化很慢。 */
const CACHE_TTL_SECONDS = 300;

export default {
  async fetch(request, env, ctx) {
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return json(405, { error: 'only GET/HEAD' });
    }

    const url = new URL(request.url);
    const path = url.pathname;

    if (DENIED.some((rule) => rule.test(path))) {
      return json(403, { error: 'denied endpoint' });
    }
    if (!ALLOWED.some((rule) => rule.test(path))) {
      return json(404, { error: 'not relayable' });
    }

    if (!allowRequest(request.headers.get('CF-Connecting-IP') || 'unknown')) {
      return json(429, { error: 'rate limited' });
    }

    const upstreamUrl = UPSTREAM + path + url.search;
    const cache = caches.default;
    const cacheKey = new Request(upstreamUrl, { method: 'GET' });

    const cached = await cache.match(cacheKey);
    if (cached) {
      // 命中缓存也回一个 X-Relay-Cache 头，方便排查
      const hit = new Response(cached.body, cached);
      hit.headers.set('X-Relay-Cache', 'HIT');
      return hit;
    }

    const upstreamRequest = new Request(upstreamUrl, {
      method: request.method === 'HEAD' ? 'HEAD' : 'GET',
      headers: {
        'User-Agent': request.headers.get('User-Agent') || 'Wenku8EPUBStudio-Relay/1.0',
        Accept: request.headers.get('Accept') || 'text/html,application/xhtml+xml',
        'Accept-Language': 'zh-CN,zh;q=0.9',
        Referer: UPSTREAM + '/',
      },
      // 刻意不转发 Cookie / Authorization：中继只服务匿名公开页
    });

    const upstreamResponse = await fetch(upstreamRequest, { redirect: 'follow' });
    if (!upstreamResponse.ok) {
      return json(upstreamResponse.status, { error: 'upstream returned ' + upstreamResponse.status });
    }

    const body = await upstreamResponse.arrayBuffer();
    const response = new Response(body, {
      status: 200,
      headers: {
        // 上游是 GBK/GB18030：保留原始字节，不解码、不重编码
        'Content-Type': upstreamResponse.headers.get('Content-Type') || 'text/html; charset=gb18030',
        'Cache-Control': `public, max-age=${CACHE_TTL_SECONDS}`,
        'X-Relay-Cache': 'MISS',
      },
    });
    // 5 分钟缓存；默认不写任何访问日志（隐私）
    ctx.waitUntil(cache.put(cacheKey, response.clone()));
    return response;
  },
};

/** 令牌桶：每分钟 RATE_LIMIT 次。 */
function allowRequest(ip) {
  const now = Date.now();
  const bucket = buckets.get(ip);
  if (!bucket || now - bucket.startedAt > 60_000) {
    buckets.set(ip, { startedAt: now, count: 1 });
    // 简单清理，避免 Map 无限增长
    if (buckets.size > 4096) {
      for (const [key, value] of buckets) {
        if (now - value.startedAt > 60_000) buckets.delete(key);
      }
    }
    return true;
  }
  if (bucket.count >= RATE_LIMIT) return false;
  bucket.count += 1;
  return true;
}

function json(status, body) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
  });
}
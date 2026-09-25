'use strict';

const dns = require('node:dns').promises;
const net = require('node:net');
const iconv = require('iconv-lite');
const { AppError } = require('./errors');

const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Wenku8EPUBStudio/1.0';
const DEFAULT_TIMEOUT_MS = 20_000;
const DEFAULT_REQUEST_INTERVAL_MS = 1_000;
const DEFAULT_RETRIES = 3;
const RETRY_BASE_DELAY_MS = 750;
const RATE_LIMIT_FALLBACK_MS = 5_000;
const MAX_RETRY_DELAY_MS = 30_000;
const MAX_RETRY_AFTER_MS = 120_000;
const MAX_HTML_BYTES = 16 * 1024 * 1024;
const MAX_REDIRECTS = 5;
const requestStates = new Map();

function isPrivateIp(address) {
  if (net.isIPv4(address)) {
    const parts = address.split('.').map(Number);
    const [a, b] = parts;
    return (
      a === 0 || a === 10 || a === 127 ||
      (a === 169 && b === 254) ||
      (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) ||
      (a === 100 && b >= 64 && b <= 127) ||
      (a === 192 && b === 0) || (a === 198 && (b === 18 || b === 19)) ||
      a >= 224
    );
  }

  if (net.isIPv6(address)) {
    const normalized = address.toLowerCase();
    if (normalized === '::' || normalized === '::1') return true;
    if (normalized.startsWith('fc') || normalized.startsWith('fd')) return true;
    if (normalized.startsWith('fe8') || normalized.startsWith('fe9') || normalized.startsWith('fea') || normalized.startsWith('feb')) return true;
    const mapped = normalized.match(/^::ffff:(\d+\.\d+\.\d+\.\d+)$/);
    return mapped ? isPrivateIp(mapped[1]) : false;
  }

  return true;
}

async function assertSafeHttpUrl(value) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new AppError('网址格式无效。', { code: 'INVALID_URL' });
  }

  if (!['http:', 'https:'].includes(url.protocol)) {
    throw new AppError('只允许访问 HTTP 或 HTTPS 资源。', { code: 'UNSUPPORTED_PROTOCOL' });
  }
  if (url.username || url.password) {
    throw new AppError('网址不能包含用户名或密码。', { code: 'URL_CREDENTIALS' });
  }

  const hostname = url.hostname.replace(/^\[|\]$/g, '');
  if (net.isIP(hostname)) {
    if (isPrivateIp(hostname)) {
      throw new AppError('为防止访问本机或内网，已阻止该地址。', { code: 'PRIVATE_ADDRESS' });
    }
    return url;
  }

  let addresses;
  try {
    addresses = await dns.lookup(hostname, { all: true, verbatim: true });
  } catch (error) {
    throw new AppError(`无法解析资源域名：${hostname}`, { code: 'DNS_ERROR', cause: error });
  }

  if (!addresses.length || addresses.some(({ address }) => isPrivateIp(address))) {
    throw new AppError('为防止访问本机或内网，已阻止该地址。', { code: 'PRIVATE_ADDRESS' });
  }
  return url;
}

function combineSignals(signal, timeoutMs) {
  const timeout = AbortSignal.timeout(timeoutMs);
  return signal ? AbortSignal.any([signal, timeout]) : timeout;
}

function sleep(ms, signal) {
  if (ms <= 0) return signal?.aborted
    ? Promise.reject(signal.reason || new DOMException('操作已取消', 'AbortError'))
    : Promise.resolve();
  return new Promise((resolve, reject) => {
    let timer;
    let abort;
    const cleanup = () => {
      clearTimeout(timer);
      if (signal && abort) signal.removeEventListener('abort', abort);
    };
    const finish = () => {
      cleanup();
      resolve();
    };
    abort = () => {
      cleanup();
      reject(signal.reason || new DOMException('操作已取消', 'AbortError'));
    };
    timer = setTimeout(finish, ms);
    if (signal) {
      if (signal.aborted) abort();
      else signal.addEventListener('abort', abort, { once: true });
    }
  });
}

function requestOrigin(value) {
  try {
    const url = new URL(value);
    const hostname = url.hostname.toLowerCase();
    if (/(^|\.)wenku8\.(net|cc|com)$/.test(hostname)) return 'wenku8-source';
    return url.origin;
  } catch {
    return null;
  }
}

function requestState(origin) {
  if (!origin) return null;
  let state = requestStates.get(origin);
  if (!state) {
    state = { nextAt: 0, tail: null };
    requestStates.set(origin, state);
  }
  return state;
}

function parseRetryAfter(value, now = Date.now()) {
  if (!value) return 0;
  const seconds = Number(value);
  if (Number.isFinite(seconds) && seconds >= 0) {
    return Math.min(MAX_RETRY_AFTER_MS, Math.round(seconds * 1_000));
  }
  const timestamp = Date.parse(value);
  if (Number.isFinite(timestamp)) {
    return Math.max(0, Math.min(MAX_RETRY_AFTER_MS, timestamp - now));
  }
  return 0;
}

function deferOrigin(origin, delayMs) {
  const state = requestState(origin);
  if (state) state.nextAt = Math.max(state.nextAt, Date.now() + Math.max(0, delayMs));
}

async function withRequestGate(url, options, action) {
  const origin = requestOrigin(url);
  const state = requestState(origin);
  if (!state) return action();

  const previous = state.tail || Promise.resolve();
  let release;
  const current = new Promise((resolve) => { release = resolve; });
  state.tail = current;
  await previous.catch(() => {});

  const configuredInterval = Number(options.requestIntervalMs);
  const interval = Number.isFinite(configuredInterval)
    ? Math.max(0, configuredInterval)
    : DEFAULT_REQUEST_INTERVAL_MS;
  try {
    await sleep(Math.max(0, state.nextAt - Date.now()), options.signal);
    state.nextAt = Date.now() + interval;
    return await action();
  } finally {
    release();
    if (state.tail === current) state.tail = null;
  }
}

async function requestThroughGate(url, options) {
  return withRequestGate(url, options, async () => {
    const result = await fetchOnce(url, options);
    const retryAfterMs = parseRetryAfter(result.response.headers.get('retry-after'));
    if (result.response.status === 429) {
      deferOrigin(requestOrigin(url), Math.max(retryAfterMs, RATE_LIMIT_FALLBACK_MS));
    } else if (retryAfterMs && (result.response.status === 408 || result.response.status >= 500)) {
      deferOrigin(requestOrigin(url), retryAfterMs);
    }
    return { ...result, retryAfterMs };
  });
}

async function fetchOnce(url, options = {}) {
  let current = new URL(url);
  for (let redirect = 0; redirect <= MAX_REDIRECTS; redirect += 1) {
    await assertSafeHttpUrl(current);
    const response = await (options.fetchImpl || fetch)(current, {
      redirect: 'manual',
      signal: combineSignals(options.signal, options.timeoutMs || DEFAULT_TIMEOUT_MS),
      headers: {
        'user-agent': options.userAgent || USER_AGENT,
        'accept-language': 'zh-CN,zh;q=0.9,en;q=0.4',
        accept: options.accept || '*/*',
        ...(options.referer ? { referer: options.referer } : {}),
        ...(options.headers || {}),
      },
    });

    if ([301, 302, 303, 307, 308].includes(response.status)) {
      const location = response.headers.get('location');
      if (!location) {
        throw new AppError(`资源返回重定向但没有 Location：${current}`, { code: 'BAD_REDIRECT' });
      }
      current = new URL(location, current);
      continue;
    }

    return { response, finalUrl: current.toString() };
  }

  throw new AppError('资源重定向次数过多。', { code: 'TOO_MANY_REDIRECTS' });
}

async function fetchResource(url, options = {}) {
  const configuredRetries = Number(options.retries);
  const retries = Number.isFinite(configuredRetries) ? Math.max(0, Math.floor(configuredRetries)) : DEFAULT_RETRIES;
  let lastError;

  for (let attempt = 0; attempt <= retries; attempt += 1) {
    try {
      const { response, finalUrl, retryAfterMs } = await requestThroughGate(url, options);
      if (response.status >= 500 || response.status === 408 || response.status === 429) {
        if (response.body) await response.body.cancel().catch(() => {});
        const rateLimited = response.status === 429;
        const error = new AppError(rateLimited
          ? '源站暂时限制了请求（HTTP 429），已按退避策略等待，请稍后重试。'
          : `源站暂时不可用（HTTP ${response.status}）。`, {
          status: 502,
          code: rateLimited ? 'UPSTREAM_RATE_LIMIT' : 'UPSTREAM_UNAVAILABLE',
        });
        error.retryAfterMs = retryAfterMs;
        error.response = response;
        throw error;
      }
      return { response, finalUrl };
    } catch (error) {
      lastError = error;
      if (options.signal?.aborted || error?.name === 'AbortError' || error?.code === 'ABORT_ERR') throw error;
      if (error instanceof AppError && error.status < 500 && error.code !== 'UPSTREAM_UNAVAILABLE' && error.code !== 'UPSTREAM_RATE_LIMIT') throw error;
      if (attempt < retries) {
        const backoff = Math.min(MAX_RETRY_DELAY_MS, RETRY_BASE_DELAY_MS * (2 ** attempt));
        await sleep(Math.max(backoff, Number(error?.retryAfterMs) || 0), options.signal);
      }
    }
  }

  throw lastError;
}

function decodeHtml(buffer, contentType = '') {
  const head = buffer.subarray(0, 4096).toString('latin1');
  const declared = (
    contentType.match(/charset\s*=\s*["']?([^;\s"']+)/i)?.[1] ||
    head.match(/charset\s*=\s*["']?([^;\s"'\/>]+)/i)?.[1] ||
    ''
  ).toLowerCase();

  const charset = declared === 'gb2312' || declared === 'gbk' ? 'gbk' : declared;
  if (charset && iconv.encodingExists(charset)) return iconv.decode(buffer, charset);
  if (!charset && /charset\s*=\s*["']?(gb2312|gbk)/i.test(head)) return iconv.decode(buffer, 'gbk');
  return buffer.toString('utf8');
}

async function readLimited(response, maxBytes) {
  const declared = Number(response.headers.get('content-length') || 0);
  if (declared > maxBytes) {
    throw new AppError(`资源超过 ${Math.round(maxBytes / 1024 / 1024)} MB 限制。`, { code: 'RESOURCE_TOO_LARGE' });
  }

  if (!response.body) return Buffer.alloc(0);
  const chunks = [];
  let total = 0;
  for await (const chunk of response.body) {
    total += chunk.length;
    if (total > maxBytes) {
      throw new AppError(`资源超过 ${Math.round(maxBytes / 1024 / 1024)} MB 限制。`, { code: 'RESOURCE_TOO_LARGE' });
    }
    chunks.push(Buffer.from(chunk));
  }
  return Buffer.concat(chunks);
}

async function fetchHtml(url, options = {}) {
  const { response, finalUrl } = await fetchResource(url, { ...options, accept: 'text/html,application/xhtml+xml' });
  if (!response.ok) {
    const hint = response.status === 403 || response.status === 429
      ? '源站拒绝了请求，可能触发了访问限制；请稍后重试。'
      : `源站返回 HTTP ${response.status}。`;
    throw new AppError(hint, { status: 502, code: 'UPSTREAM_HTTP_ERROR' });
  }
  const buffer = await readLimited(response, MAX_HTML_BYTES);
  return {
    html: decodeHtml(buffer, response.headers.get('content-type') || ''),
    finalUrl,
    contentType: response.headers.get('content-type') || '',
  };
}

module.exports = {
  USER_AGENT,
  assertSafeHttpUrl,
  decodeHtml,
  fetchHtml,
  fetchResource,
  parseRetryAfter,
  readLimited,
  sleep,
};
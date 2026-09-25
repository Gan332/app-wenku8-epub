'use strict';

const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');
const iconv = require('iconv-lite');
const {
  normalizeSourceUrl,
  parseBookHtml,
  parseChapterHtml,
  parseIndexHtml,
  sourceIds,
} = require('../src/wenku8');
const { assertSafeHttpUrl, decodeHtml, fetchResource, parseRetryAfter } = require('../src/http');

const fixture = (name) => path.join(__dirname, 'fixtures', name);
const read = (name) => fs.readFileSync(fixture(name), 'utf8');
const bookUrl = 'https://www.wenku8.net/book/2835.htm';
const indexUrl = 'https://www.wenku8.net/novel/2/2835/index.htm';

function parsedFixtures() {
  const book = parseBookHtml(read('book.html'), { bookUrl, requestedDirectoryUrl: indexUrl });
  const index = parseIndexHtml(read('ccindex.html'), { indexUrl, bookId: '2835' });
  return { book, index };
}

test('normalizes supported book IDs and rejects unsafe source URLs', () => {
  assert.equal(normalizeSourceUrl('2835').toString(), bookUrl);
  assert.deepEqual(sourceIds(new URL(indexUrl)), { kind: 'index', bookId: '2835', categoryId: '2' });
  assert.throws(() => normalizeSourceUrl('https://example.com/book/2835.htm'), /只接受/);
  assert.throws(() => normalizeSourceUrl('https://www.wenku8.net/cdn-cgi/challenge-platform/x'), /Cloudflare/);
  assert.throws(() => sourceIds(new URL('https://www.wenku8.net/novel/2/2835/113354.htm')), /目录页/);
});

test('parses the saved book and index fixtures', () => {
  const { book, index } = parsedFixtures();
  assert.equal(book.title, '欢迎光临流放者食堂！');
  assert.equal(book.author, '君川优树');
  assert.equal(book.status, '连载中');
  assert.match(book.summary, /银翼大队/);
  assert.equal(book.coverUrl, 'http://img.wenku8.com/image/2/2835/2835s.jpg');
  assert.equal(index.bookId, '2835');
  assert.equal(index.chapters.length, 25);
  assert.equal(index.chapters[0].id, '113354');
  assert.equal(index.chapters.at(-1).isIllustration, true);
});

test('parses ordinary and illustration chapter fixtures', () => {
  const { index } = parsedFixtures();
  const ordinary = parseChapterHtml(read('chapter.html'), index.chapters[0], index.chapters[0].url);
  assert.equal(ordinary.textLength > 1000, true);
  assert.equal(ordinary.imageUrls.length, 0);
  assert.equal(ordinary.blocks.some((block) => block.type === 'text'), true);

  const illustration = parseChapterHtml(read('illustration.html'), index.chapters.at(-1), index.chapters.at(-1).url);
  assert.equal(illustration.textLength, 0);
  assert.equal(illustration.imageUrls.length, 12);
  assert.deepEqual(illustration.blocks.map((block) => block.index), Array.from({ length: 12 }, (_, index) => index));
});

test('resolves relative lazy image URLs against the chapter URL', () => {
  const chapterUrl = 'https://www.wenku8.net/novel/2/2835/113354.htm';
  const html = '<div id="content"><p>这是足够长的章节正文内容。</p><img data-base="/image-root/" data-src="cover/a.jpg"><p>后续正文仍然存在。</p></div>';
  const parsed = parseChapterHtml(html, { id: 'x', title: '测试', url: chapterUrl, order: 1, volume: '正文' }, chapterUrl);
  assert.deepEqual(parsed.imageUrls, ['https://www.wenku8.net/image-root/cover/a.jpg']);
  assert.deepEqual(parsed.blocks.filter((block) => block.type === 'image'), [{ type: 'image', index: 0 }]);
});

test('honors numeric and HTTP-date Retry-After values', () => {
  assert.equal(parseRetryAfter('2'), 2_000);
  assert.equal(parseRetryAfter('0'), 0);
  const now = Date.parse('2026-01-01T00:00:00.000Z');
  assert.equal(parseRetryAfter('Thu, 01 Jan 2026 00:00:05 GMT', now), 5_000);
  assert.equal(parseRetryAfter('not-a-delay', now), 0);
});

test('classifies HTTP 429 as a rate-limit response', async () => {
  await assert.rejects(
    fetchResource('https://8.8.8.8/test', {
      fetchImpl: async () => new Response('busy', { status: 429, headers: { 'retry-after': '0' } }),
      retries: 0,
      requestIntervalMs: 0,
    }),
    (error) => error.code === 'UPSTREAM_RATE_LIMIT' && error.status === 502,
  );
});

test('rejects challenge pages instead of attempting access-control bypass', () => {
  assert.throws(
    () => parseBookHtml('<html><head><title>Just a moment...</title></head><body>cf-chl-test</body></html>', { bookUrl }),
    (error) => error.code === 'UPSTREAM_CHALLENGE',
  );
});

test('decodes GBK HTML and blocks private network destinations', async () => {
  const gbk = iconv.encode('<meta charset="gb2312"><p>中文正文</p>', 'gbk');
  assert.match(decodeHtml(gbk, 'text/html; charset=gb2312'), /中文正文/);
  await assert.rejects(assertSafeHttpUrl('http://127.0.0.1:3210/'), (error) => error.code === 'PRIVATE_ADDRESS');
  await assert.rejects(assertSafeHttpUrl('file:///etc/passwd'), (error) => error.code === 'UNSUPPORTED_PROTOCOL');
  await assert.rejects(assertSafeHttpUrl('https://localhost/'), (error) => ['PRIVATE_ADDRESS', 'DNS_ERROR'].includes(error.code));
});

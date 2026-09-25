'use strict';

const fsp = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');
const { once } = require('node:events');
const { contentDisposition, createApp } = require('../src/app');

test('creates UTF-8 and ASCII-safe Content-Disposition headers', () => {
  const header = contentDisposition('欢迎光临.epub');
  assert.match(header, /^attachment; filename="/);
  assert.match(header, /filename\*=UTF-8''/);
  assert.match(header, /%E6%AC%A2%E8%BF%8E%E5%85%89%E4%B8%B4\.epub/);
  const asciiFilename = header.match(/filename="([^"]*)"/)[1];
  assert.doesNotMatch(asciiFilename, /[\r\n"\\]/);
});

test('serves the local UI and basic API responses', async () => {
  const temporaryDirectory = await fsp.mkdtemp(path.join(os.tmpdir(), 'wenku8-app-test-'));
  const app = createApp({
    rootDirectory: path.resolve(__dirname, '..'),
    dataDirectory: path.join(temporaryDirectory, 'data'),
    outputDirectory: path.join(temporaryDirectory, 'output'),
  });
  const server = app.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const address = server.address();
  const base = `http://127.0.0.1:${address.port}`;
  try {
    const health = await fetch(`${base}/api/health`);
    assert.equal(health.status, 200);
    assert.equal((await health.json()).ok, true);
    assert.match(health.headers.get('content-security-policy'), /default-src 'self'/);

    const page = await fetch(base);
    assert.equal(page.status, 200);
    assert.match(await page.text(), /文库 EPUB 工坊/);

    const font = await fetch(`${base}/fonts/MiSansVF.ttf`);
    assert.equal(font.status, 200);
    assert.match(font.headers.get('content-type'), /font|application\/octet-stream/);
    await font.body?.cancel();

    const jobs = await (await fetch(`${base}/api/jobs`)).json();
    assert.deepEqual(jobs, { jobs: [] });

    const invalid = await fetch(`${base}/api/jobs`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({}),
    });
    assert.equal(invalid.status, 400);
    assert.equal((await invalid.json()).error.code, 'BOOK_REQUIRED');

    const missing = await fetch(`${base}/api/jobs/does-not-exist`);
    assert.equal(missing.status, 404);
    assert.equal((await missing.json()).error.code, 'JOB_NOT_FOUND');
  } finally {
    await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
    await fsp.rm(temporaryDirectory, { recursive: true, force: true });
  }
});

test('streams live job progress updates', async () => {
  const temporaryDirectory = await fsp.mkdtemp(path.join(os.tmpdir(), 'wenku8-events-test-'));
  const app = createApp({
    rootDirectory: path.resolve(__dirname, '..'),
    dataDirectory: path.join(temporaryDirectory, 'data'),
    outputDirectory: path.join(temporaryDirectory, 'output'),
  });
  const server = app.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const address = server.address();
  const manager = app.locals.jobManager;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 3000);
  let reader;
  try {
    await app.locals.ready;
    const now = new Date().toISOString();
    manager.jobs.set('stream-test', {
      id: 'stream-test',
      status: 'running',
      book: { id: '1', title: '事件流测试', author: '测试作者' },
      chapterCount: 2,
      options: { includeCover: false },
      progress: { phase: 'fetching', percent: 10, completed: 0, total: 2, imageCompleted: 0, message: '处理中', currentTitle: '' },
      createdAt: now,
      updatedAt: now,
      error: null,
      warnings: [],
      outputPath: null,
    });

    const base = `http://127.0.0.1:${address.port}`;
    const response = await fetch(`${base}/api/jobs/stream-test/events`, { signal: controller.signal });
    assert.match(response.headers.get('content-type'), /^text\/event-stream/);
    assert.match(response.headers.get('cache-control'), /no-cache/);
    reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    const nextJob = async () => {
      for (;;) {
        const boundary = buffer.indexOf('\n\n');
        if (boundary >= 0) {
          const event = buffer.slice(0, boundary);
          buffer = buffer.slice(boundary + 2);
          const data = event.split('\n').find((line) => line.startsWith('data: '));
          if (data) return JSON.parse(data.slice(6));
          continue;
        }
        const chunk = await reader.read();
        if (chunk.done) throw new Error('事件流在收到任务更新前结束。');
        buffer += decoder.decode(chunk.value, { stream: true });
      }
    };

    assert.equal((await nextJob()).progress.percent, 10);
    await manager.update(manager.jobs.get('stream-test'), {
      progress: { percent: 55, completed: 1, message: '已处理 1/2 个章节' },
    });
    const update = await nextJob();
    assert.equal(update.progress.percent, 55);
    assert.equal(update.progress.completed, 1);
  } finally {
    clearTimeout(timeout);
    controller.abort();
    if (reader) await reader.cancel().catch(() => {});
    await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
    await fsp.rm(temporaryDirectory, { recursive: true, force: true });
  }
});

test('frontend source never assigns properties to the volume sentinel', async () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const source = fs.readFileSync(path.join(__dirname, '..', 'public', 'app.js'), 'utf8');
  assert.doesNotMatch(source, /Symbol\(['"]none['"]\)/);
  assert.doesNotMatch(source, /lastVolume\.value\s*=/);
  assert.match(source, /let lastVolume;/);
  assert.match(source, /lastVolume\s*=\s*chapter\.volume;/);
  assert.doesNotMatch(source, /\.each\(/);
});

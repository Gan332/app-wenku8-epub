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

'use strict';

const fsp = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { once } = require('node:events');
const { createApp } = require('../src/app');
const { inspectEpub } = require('./epub-check');

const SOURCE_URL = process.env.WENKU8_LIVE_URL || 'https://www.wenku8.net/novel/2/2835/index.htm';
const POLL_INTERVAL_MS = 500;
const JOB_TIMEOUT_MS = Number(process.env.WENKU8_LIVE_TIMEOUT_MS || 180_000);

async function jsonRequest(baseUrl, route, options = {}) {
  const response = await fetch(`${baseUrl}${route}`, {
    ...options,
    headers: {
      ...(options.body ? { 'content-type': 'application/json' } : {}),
      ...(options.headers || {}),
    },
  });
  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    const message = payload?.error?.message || `HTTP ${response.status}`;
    throw new Error(`${options.method || 'GET'} ${route}: ${message}`);
  }
  return payload;
}

async function waitForJob(baseUrl, jobId) {
  const deadline = Date.now() + JOB_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const { job } = await jsonRequest(baseUrl, `/api/jobs/${encodeURIComponent(jobId)}`);
    if (!['queued', 'running'].includes(job.status)) return job;
    await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS));
  }
  throw new Error(`任务等待超时（${JOB_TIMEOUT_MS} ms）`);
}

(async () => {
  const temporaryDirectory = await fsp.mkdtemp(path.join(os.tmpdir(), 'wenku8-live-test-'));
  const app = createApp({
    rootDirectory: path.resolve(__dirname, '..'),
    dataDirectory: path.join(temporaryDirectory, 'data'),
    outputDirectory: path.join(temporaryDirectory, 'output'),
  });
  const server = app.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const address = server.address();
  const baseUrl = `http://127.0.0.1:${address.port}`;

  try {
    console.log(`[live] source: ${SOURCE_URL}`);
    const [{ book }, { index }] = await Promise.all([
      jsonRequest(baseUrl, '/api/parse/book', { method: 'POST', body: JSON.stringify({ url: SOURCE_URL }) }),
      jsonRequest(baseUrl, '/api/parse/index', { method: 'POST', body: JSON.stringify({ url: SOURCE_URL }) }),
    ]);
    const ordinary = index.chapters.find((chapter) => !chapter.isIllustration);
    const illustration = index.chapters.find((chapter) => chapter.isIllustration);
    if (!ordinary || !illustration) throw new Error('公开目录中没有同时找到普通章节和插图章节');

    const { job: created } = await jsonRequest(baseUrl, '/api/jobs', {
      method: 'POST',
      body: JSON.stringify({
        book,
        chapters: [ordinary, illustration],
        options: { includeCover: false },
      }),
    });
    const job = await waitForJob(baseUrl, created.id);
    if (job.status !== 'completed') {
      throw new Error(`导出任务未完成：${job.status} ${job.error?.message || ''}`);
    }

    const download = await fetch(`${baseUrl}/api/jobs/${encodeURIComponent(job.id)}/download`);
    if (!download.ok) throw new Error(`下载接口返回 HTTP ${download.status}`);
    if (!download.headers.get('content-type')?.includes('application/epub+zip')) {
      throw new Error('下载接口没有返回 EPUB Content-Type');
    }
    if (!download.headers.get('content-disposition')?.includes('attachment')) {
      throw new Error('下载接口没有返回附件下载头');
    }

    const outputPath = path.join(temporaryDirectory, 'live.epub');
    await fsp.writeFile(outputPath, Buffer.from(await download.arrayBuffer()));
    const inspected = await inspectEpub(outputPath, { chapters: 2, minImages: 1 });
    console.log(JSON.stringify({
      source: SOURCE_URL,
      book: book.title,
      directoryChapters: index.chapters.length,
      selectedChapters: [ordinary.title, illustration.title],
      warnings: job.warnings,
      outputBytes: job.file?.size,
      epubEntries: inspected.names.length,
      epubImages: inspected.imageNames.length,
    }, null, 2));
  } finally {
    await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
    await fsp.rm(temporaryDirectory, { recursive: true, force: true });
  }
})().catch((error) => {
  console.error(`[live] 验收失败：${error.message}`);
  process.exitCode = 1;
});

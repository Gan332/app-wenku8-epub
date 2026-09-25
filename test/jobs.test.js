'use strict';

const fsp = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');
const { JobManager, validateBook, validateChapters } = require('../src/jobs');

const book = {
  id: '2835',
  title: '测试书',
  author: '测试作者',
  sourceUrl: 'https://www.wenku8.net/book/2835.htm',
  bookUrl: 'https://www.wenku8.net/book/2835.htm',
  directoryUrl: 'https://www.wenku8.net/novel/2/2835/index.htm',
  coverUrl: null,
};
const chapter = (id, order, title = `第 ${order} 章`) => ({
  id,
  title,
  url: `https://www.wenku8.net/novel/2/2835/${id}.htm`,
  volume: '正文',
  order,
  isIllustration: false,
});

function parsed(id, order) {
  return {
    id,
    title: `第 ${order} 章`,
    volume: '正文',
    order,
    sourceUrl: `https://www.wenku8.net/novel/2/2835/${id}.htm`,
    imageUrls: [],
    blocks: [{ type: 'text', value: '这是用于测试的章节正文。' }],
    textLength: 12,
  };
}

async function waitFor(manager, id) {
  const deadline = Date.now() + 3000;
  while (Date.now() < deadline) {
    const job = manager.get(id);
    if (!['queued', 'running'].includes(job.status)) return job;
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  throw new Error(`等待任务超时：${id}`);
}

function createManager(temporaryDirectory, overrides = {}) {
  return new JobManager({
    dataDirectory: path.join(temporaryDirectory, 'data'),
    outputDirectory: path.join(temporaryDirectory, 'output'),
    parseChapter: async (item) => parsed(item.id, item.order),
    collectChapterImages: async () => [],
    fetchCover: async () => null,
    buildEpub: async ({ parsedChapters }) => ({ outputPath: 'test.epub', bytes: 10, chapters: parsedChapters.length, images: 0 }),
    ...overrides,
  });
}

test('validates book and chapter payloads', () => {
  assert.equal(validateBook(book).title, '测试书');
  assert.throws(() => validateBook({ ...book, sourceUrl: 'https://example.com/book/2835.htm' }), /wenku8/);
  assert.equal(validateChapters([chapter('2', 2), chapter('1', 1)])[0].id, '1');
  assert.throws(() => validateChapters([chapter('1', 1), chapter('1', 2)]), (error) => error.code === 'DUPLICATE_CHAPTER');
  assert.throws(() => validateChapters([chapter('1', 1), { ...chapter('2', 2), url: 'http://127.0.0.1/2.htm' }]), /只接受 wenku8/);
});

test('continues after a chapter failure and records a warning', async () => {
  const temporaryDirectory = await fsp.mkdtemp(path.join(os.tmpdir(), 'wenku8-job-test-'));
  const manager = createManager(temporaryDirectory, {
    parseChapter: async (item) => {
      if (item.id === 'bad') throw new Error('章节暂时不可用');
      return parsed(item.id, item.order);
    },
  });
  try {
    await manager.init();
    const created = await manager.create({ book, chapters: [chapter('bad', 1), chapter('good', 2)], options: { includeCover: false } });
    const job = await waitFor(manager, created.id);
    assert.equal(job.status, 'completed');
    assert.equal(job.progress.completed, 2);
    assert.equal(job.warnings.length, 1);
    assert.match(job.warnings[0], /章节暂时不可用/);
  } finally {
    await fsp.rm(temporaryDirectory, { recursive: true, force: true });
  }
});

test('fails without producing an EPUB when every chapter fails', async () => {
  const temporaryDirectory = await fsp.mkdtemp(path.join(os.tmpdir(), 'wenku8-job-test-'));
  const manager = createManager(temporaryDirectory, {
    parseChapter: async () => { throw new Error('源站不可用'); },
  });
  try {
    await manager.init();
    const created = await manager.create({ book, chapters: [chapter('bad-1', 1), chapter('bad-2', 2)], options: { includeCover: false } });
    const job = await waitFor(manager, created.id);
    assert.equal(job.status, 'failed');
    assert.equal(job.error.code, 'NO_CHAPTERS_PARSED');
    assert.equal(job.file, null);
  } finally {
    await fsp.rm(temporaryDirectory, { recursive: true, force: true });
  }
});

test('cancels an active task', async () => {
  const temporaryDirectory = await fsp.mkdtemp(path.join(os.tmpdir(), 'wenku8-job-test-'));
  let started;
  const manager = createManager(temporaryDirectory, {
    parseChapter: async (item, { signal }) => {
      started?.();
      return new Promise((resolve, reject) => {
        const abort = () => reject(signal.reason || new DOMException('取消', 'AbortError'));
        if (signal.aborted) abort();
        else signal.addEventListener('abort', abort, { once: true });
        setTimeout(() => resolve(parsed(item.id, item.order)), 1000);
      });
    },
  });
  try {
    await manager.init();
    const created = await manager.create({ book, chapters: [chapter('slow', 1)], options: { includeCover: false } });
    await new Promise((resolve) => { started = resolve; setTimeout(resolve, 100); });
    await manager.cancel(created.id);
    const job = await waitFor(manager, created.id);
    assert.equal(job.status, 'canceled');
    assert.equal(job.error.code, 'CANCELED');
  } finally {
    await fsp.rm(temporaryDirectory, { recursive: true, force: true });
  }
});

test('fills missing progress fields when loading legacy job records', async () => {
  const temporaryDirectory = await fsp.mkdtemp(path.join(os.tmpdir(), 'wenku8-job-test-'));
  const dataDirectory = path.join(temporaryDirectory, 'data');
  const jobsDirectory = path.join(dataDirectory, 'jobs');
  await fsp.mkdir(jobsDirectory, { recursive: true });
  await fsp.writeFile(path.join(jobsDirectory, 'legacy.json'), JSON.stringify({
    id: 'legacy',
    status: 'failed',
    book,
    chapterCount: 2,
    imageCount: 3,
    options: { includeCover: false },
    progress: { phase: 'failed', percent: 50, completed: 1, message: '旧任务' },
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    error: { code: 'OLD_FAILURE', message: '旧任务失败' },
    warnings: [],
    outputPath: null,
  }), 'utf8');
  const manager = new JobManager({
    dataDirectory,
    outputDirectory: path.join(temporaryDirectory, 'output'),
  });
  try {
    await manager.init();
    const job = manager.get('legacy');
    assert.equal(job.progress.total, 2);
    assert.equal(job.progress.imageCompleted, 3);
    assert.equal(job.progress.completed, 1);
  } finally {
    await fsp.rm(temporaryDirectory, { recursive: true, force: true });
  }
});

test('marks unfinished tasks as failed when the manager restarts', async () => {
  const temporaryDirectory = await fsp.mkdtemp(path.join(os.tmpdir(), 'wenku8-job-test-'));
  const dataDirectory = path.join(temporaryDirectory, 'data');
  const jobsDirectory = path.join(dataDirectory, 'jobs');
  await fsp.mkdir(jobsDirectory, { recursive: true });
  await fsp.writeFile(path.join(jobsDirectory, 'interrupted.json'), JSON.stringify({
    id: 'interrupted',
    status: 'running',
    book,
    chapterCount: 1,
    options: { includeCover: false },
    progress: { phase: 'fetching', percent: 10, completed: 0, total: 1, imageCompleted: 0, message: '处理中', currentTitle: '' },
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    error: null,
    warnings: [],
    outputPath: null,
  }), 'utf8');
  const manager = new JobManager({
    dataDirectory,
    outputDirectory: path.join(temporaryDirectory, 'output'),
  });
  try {
    await manager.init();
    const job = manager.get('interrupted');
    assert.equal(job.status, 'failed');
    assert.equal(job.error.code, 'SERVER_RESTARTED');
    const persisted = JSON.parse(await fsp.readFile(path.join(jobsDirectory, 'interrupted.json'), 'utf8'));
    assert.equal(persisted.status, 'failed');
    assert.equal(persisted.error.code, 'SERVER_RESTARTED');
  } finally {
    await fsp.rm(temporaryDirectory, { recursive: true, force: true });
  }
});

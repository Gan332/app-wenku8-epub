'use strict';

const crypto = require('node:crypto');
const { EventEmitter } = require('node:events');
const fsp = require('node:fs/promises');
const path = require('node:path');
const { AppError, isAbortError } = require('./errors');
const { assertWenkuUrl, cleanInline, parseChapter } = require('./wenku8');
const { buildEpub, collectChapterImages, fetchCover, safeName } = require('./epub');

const MAX_CHAPTERS = 2000;
const ACTIVE_STATES = new Set(['queued', 'running']);

function publicId() {
  return `${Date.now().toString(36)}-${crypto.randomBytes(4).toString('hex')}`;
}

function percentage(current, total) {
  if (!total) return 0;
  return Math.max(0, Math.min(100, Math.round((current / total) * 100)));
}

function count(value, fallback = 0) {
  if (value === undefined || value === null || value === '') return fallback;
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? Math.floor(number) : fallback;
}

function normalizeProgress(job) {
  const progress = job.progress && typeof job.progress === 'object' ? job.progress : {};
  return {
    ...progress,
    phase: progress.phase || 'queued',
    percent: Math.max(0, Math.min(100, Math.round(Number(progress.percent) || 0))),
    completed: count(progress.completed),
    total: count(progress.total, count(job.chapterCount)),
    imageCompleted: count(progress.imageCompleted, count(job.imageCount)),
    message: progress.message || '',
    currentTitle: progress.currentTitle || '',
  };
}

function validateBook(raw) {
  if (!raw || typeof raw !== 'object') throw new AppError('缺少书籍信息。', { code: 'BOOK_REQUIRED' });
  const title = cleanInline(raw.title);
  if (!title) throw new AppError('书籍标题不能为空。', { code: 'BOOK_TITLE_REQUIRED' });
  let sourceUrl;
  try {
    sourceUrl = new URL(raw.sourceUrl || raw.bookUrl).toString();
    assertWenkuUrl(sourceUrl);
  } catch (error) {
    if (error instanceof AppError) throw error;
    throw new AppError('书籍来源网址无效。', { code: 'INVALID_BOOK_URL' });
  }
  return {
    id: String(raw.id || '').replace(/\D/g, '') || null,
    title: title.slice(0, 200),
    author: cleanInline(raw.author || '未知作者').slice(0, 200),
    category: cleanInline(raw.category || '轻小说').slice(0, 100),
    status: cleanInline(raw.status || '').slice(0, 100),
    updatedAt: cleanInline(raw.updatedAt || '').slice(0, 100),
    tags: Array.isArray(raw.tags) ? raw.tags.map(cleanInline).filter(Boolean).slice(0, 30) : [],
    summary: cleanInline(raw.summary || '').slice(0, 12_000),
    coverUrl: typeof raw.coverUrl === 'string' && /^https?:\/\//i.test(raw.coverUrl) ? raw.coverUrl : null,
    sourceUrl,
    bookUrl: typeof raw.bookUrl === 'string' && /^https?:\/\//i.test(raw.bookUrl) ? raw.bookUrl : sourceUrl,
    directoryUrl: typeof raw.directoryUrl === 'string' && /^https?:\/\//i.test(raw.directoryUrl) ? raw.directoryUrl : null,
  };
}

function validateChapters(raw) {
  if (!Array.isArray(raw) || !raw.length) throw new AppError('请至少选择一个章节。', { code: 'NO_CHAPTER_SELECTED' });
  if (raw.length > MAX_CHAPTERS) throw new AppError(`单次最多支持 ${MAX_CHAPTERS} 个章节。`, { code: 'TOO_MANY_CHAPTERS' });
  const seen = new Set();
  return raw.map((item, index) => {
    if (!item || typeof item !== 'object') throw new AppError('章节数据无效。', { code: 'INVALID_CHAPTER' });
    let url;
    try {
      url = new URL(item.url).toString();
      assertWenkuUrl(url);
    } catch (error) {
      if (error instanceof AppError) throw error;
      throw new AppError(`第 ${index + 1} 个章节网址无效。`, { code: 'INVALID_CHAPTER_URL' });
    }
    if (seen.has(url)) throw new AppError(`章节重复：${cleanInline(item.title) || index + 1}`, { code: 'DUPLICATE_CHAPTER' });
    seen.add(url);
    return {
      id: String(item.id || index + 1).slice(0, 80),
      title: cleanInline(item.title || `第 ${index + 1} 章`).slice(0, 500),
      url,
      volume: cleanInline(item.volume || '正文').slice(0, 500),
      order: Number(item.order || index + 1),
      isIllustration: Boolean(item.isIllustration),
    };
  }).sort((a, b) => a.order - b.order);
}

class JobManager extends EventEmitter {
  constructor({
    dataDirectory,
    outputDirectory,
    parseChapter: parseChapterImpl = parseChapter,
    buildEpub: buildEpubImpl = buildEpub,
    collectChapterImages: collectChapterImagesImpl = collectChapterImages,
    fetchCover: fetchCoverImpl = fetchCover,
  }) {
    super();
    this.dataDirectory = dataDirectory;
    this.jobsDirectory = path.join(dataDirectory, 'jobs');
    this.outputDirectory = outputDirectory;
    this.jobs = new Map();
    this.controllers = new Map();
    this.persistence = new Map();
    this.queue = [];
    this.activeRun = null;
    this.dependencies = {
      parseChapter: parseChapterImpl,
      buildEpub: buildEpubImpl,
      collectChapterImages: collectChapterImagesImpl,
      fetchCover: fetchCoverImpl,
    };
  }

  async init() {
    await fsp.mkdir(this.jobsDirectory, { recursive: true });
    await fsp.mkdir(this.outputDirectory, { recursive: true });
    const files = await fsp.readdir(this.jobsDirectory, { withFileTypes: true });
    for (const entry of files) {
      if (!entry.isFile() || !entry.name.endsWith('.json')) continue;
      try {
        const job = JSON.parse(await fsp.readFile(path.join(this.jobsDirectory, entry.name), 'utf8'));
        job.progress = normalizeProgress(job);
        if (ACTIVE_STATES.has(job.status)) {
          job.status = 'failed';
          job.error = { code: 'SERVER_RESTARTED', message: '上次任务因应用重启而中断，请重新生成。' };
          job.updatedAt = new Date().toISOString();
          await this.persist(job);
        }
        this.jobs.set(job.id, job);
        if (job.outputPath) job.outputPath = path.resolve(job.outputPath);
      } catch (error) {
        console.warn(`忽略损坏的任务文件 ${entry.name}: ${error.message}`);
      }
    }
  }

  async create(payload) {
    const book = validateBook(payload.book);
    const chapters = validateChapters(payload.chapters);
    const id = publicId();
    const now = new Date().toISOString();
    const job = {
      id,
      status: 'queued',
      book,
      chapterCount: chapters.length,
      options: {
        includeCover: payload.options?.includeCover !== false,
      },
      progress: {
        phase: 'queued',
        percent: 0,
        completed: 0,
        total: chapters.length,
        imageCompleted: 0,
        message: '等待开始…',
        currentTitle: '',
      },
      createdAt: now,
      updatedAt: now,
      error: null,
      warnings: [],
      outputPath: null,
    };
    this.jobs.set(id, job);
    this.controllers.set(id, new AbortController());
    await this.persist(job);
    this.queue.push({ job, chapters });
    this.drainQueue();
    return this.publicJob(job);
  }

  drainQueue() {
    if (this.activeRun || !this.queue.length) return;
    const task = this.queue.shift();
    this.activeRun = task;
    queueMicrotask(() => {
      this.run(task.job, task.chapters)
        .catch((error) => console.error(`任务执行异常（${task.job.id}）：${error.message || error}`))
        .finally(() => {
          this.activeRun = null;
          this.drainQueue();
        });
    });
  }

  get(id) {
    const job = this.jobs.get(id);
    if (!job) throw new AppError('任务不存在。', { status: 404, code: 'JOB_NOT_FOUND' });
    return this.publicJob(job);
  }

  list() {
    return [...this.jobs.values()]
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .slice(0, 50)
      .map((job) => this.publicJob(job));
  }

  async cancel(id) {
    const job = this.jobs.get(id);
    if (!job) throw new AppError('任务不存在。', { status: 404, code: 'JOB_NOT_FOUND' });
    if (!ACTIVE_STATES.has(job.status)) return this.publicJob(job);
    const controller = this.controllers.get(id);
    controller?.abort(new DOMException('用户取消了任务', 'AbortError'));
    if (job.status === 'queued' && this.activeRun?.job.id !== id) {
      const pendingIndex = this.queue.findIndex((task) => task.job.id === id);
      if (pendingIndex >= 0) this.queue.splice(pendingIndex, 1);
      this.controllers.delete(id);
      await this.update(job, {
        status: 'canceled',
        error: { code: 'CANCELED', message: '任务已取消。' },
        finishedAt: new Date().toISOString(),
        progress: { phase: 'canceled', message: '任务已取消' },
      });
      return this.publicJob(job);
    }
    await this.update(job, { progress: { message: '正在取消…' } });
    return this.publicJob(job);
  }

  getOutputPath(id) {
    const job = this.jobs.get(id);
    if (!job) throw new AppError('任务不存在。', { status: 404, code: 'JOB_NOT_FOUND' });
    if (job.status !== 'completed' || !job.outputPath) throw new AppError('EPUB 尚未生成完成。', { status: 409, code: 'EPUB_NOT_READY' });
    return job;
  }

  publicJob(job) {
    return {
      id: job.id,
      status: job.status,
      book: {
        id: job.book.id,
        title: job.book.title,
        author: job.book.author,
      },
      chapterCount: job.chapterCount,
      progress: normalizeProgress(job),
      createdAt: job.createdAt,
      updatedAt: job.updatedAt,
      finishedAt: job.finishedAt || null,
      error: job.error,
      warnings: Array.isArray(job.warnings) ? job.warnings : [],
      file: job.status === 'completed' ? {
        name: path.basename(job.outputPath),
        size: job.outputSize,
        downloadUrl: `/api/jobs/${job.id}/download`,
      } : null,
    };
  }

  async update(job, patch) {
    Object.assign(job, patch, { updatedAt: new Date().toISOString() });
    if (patch.progress) Object.assign(job.progress, patch.progress);
    this.emit('update', this.publicJob(job));
    await this.persist(job);
  }

  async persist(job) {
    const previous = this.persistence.get(job.id) || Promise.resolve();
    const operation = previous.catch(() => {}).then(async () => {
      const target = path.join(this.jobsDirectory, `${job.id}.json`);
      const temporary = `${target}.${crypto.randomUUID()}.tmp`;
      await fsp.writeFile(temporary, JSON.stringify(job, null, 2), 'utf8');
      try {
        await fsp.rename(temporary, target);
      } catch (error) {
        await fsp.rm(temporary, { force: true }).catch(() => {});
        throw error;
      }
    });
    this.persistence.set(job.id, operation);
    try {
      await operation;
    } finally {
      if (this.persistence.get(job.id) === operation) this.persistence.delete(job.id);
    }
  }

  async run(job, requestedChapters) {
    const signal = this.controllers.get(job.id)?.signal;
    const parsedChapters = [];
    const images = [];
    const warnings = [];
    let globalImageIndex = 0;
    let completed = 0;

    try {
      if (signal?.aborted) throw signal.reason || new DOMException('任务已取消', 'AbortError');
      await this.update(job, { status: 'running', progress: { phase: 'fetching', percent: 1, message: '开始获取章节…' } });
      for (const chapter of requestedChapters) {
        if (signal.aborted) throw signal.reason;
        const baseline = completed / requestedChapters.length;
        await this.update(job, {
          progress: {
            phase: 'fetching',
            percent: percentage(completed, requestedChapters.length),
            completed,
            currentTitle: chapter.title,
            message: `正在下载：${chapter.title}`,
          },
        });
        let parsed;
        try {
          parsed = await this.dependencies.parseChapter(chapter, { signal });
          parsed.sourceId = chapter.id;
          parsedChapters.push(parsed);
          const chapterImages = await this.dependencies.collectChapterImages(parsed, {
            outputDirectory: this.outputDirectory,
            globalOffset: globalImageIndex,
            signal,
            onImage: ({ index, total, status, error }) => {
              if (status === 'failed') {
                warnings.push(`插图 ${index + 1}/${total}：${error?.message || '下载失败'}`);
                return;
              }
              const imageProgress = total ? index / total : 0;
              job.progress.phase = 'images';
              job.progress.percent = Math.min(92, Math.round(baseline * 92 + imageProgress * 92 / requestedChapters.length));
              job.progress.message = `正在下载插图 ${index + 1}/${total}`;
              job.progress.imageCompleted = images.length + index;
              this.emit('update', this.publicJob(job));
            },
          });
          images.push(...chapterImages);
          globalImageIndex += parsed.imageUrls.length;
        } catch (error) {
          if (signal.aborted || isAbortError(error)) throw error;
          warnings.push(`${chapter.title || `第 ${chapter.order} 章`}：${error.message || '章节处理失败'}`);
          console.warn(`章节处理失败（已跳过）：${chapter.title} - ${error.message || error}`);
        }
        completed += 1;
        await this.update(job, {
          warnings: [...warnings],
          progress: {
            phase: 'fetching',
            percent: Math.min(94, percentage(completed, requestedChapters.length) * 0.94),
            completed,
            currentTitle: chapter.title,
            message: `已处理 ${completed}/${requestedChapters.length} 个章节`,
          },
        });
      }

      if (!parsedChapters.length) {
        throw new AppError('所有章节都未能成功读取，请检查源站访问状态后重试。', { code: 'NO_CHAPTERS_PARSED' });
      }

      let cover = null;
      if (job.options.includeCover && !signal.aborted) {
        await this.update(job, { progress: { phase: 'cover', percent: 94, message: '正在下载书籍封面…' } });
        cover = await this.dependencies.fetchCover(job.book, this.outputDirectory, { signal });
        if (!cover && job.book.coverUrl) warnings.push('封面：下载失败，已跳过');
      }

      const fileBase = `${safeName(job.book.title, '轻小说')}-${job.id}`;
      const outputPath = path.join(this.outputDirectory, `${fileBase}.epub`);
      const result = await this.dependencies.buildEpub({
        book: job.book,
        parsedChapters,
        images,
        cover,
        outputPath,
        signal,
        onProgress: ({ percent, message }) => {
          job.progress = { ...job.progress, phase: 'packaging', percent, message };
          this.emit('update', this.publicJob(job));
        },
      });

      await this.update(job, {
        status: 'completed',
        outputPath: result.outputPath,
        outputSize: result.bytes,
        imageCount: result.images,
        warnings: [...warnings],
        finishedAt: new Date().toISOString(),
        progress: { phase: 'completed', percent: 100, completed, imageCompleted: images.length, currentTitle: '', message: 'EPUB 已生成' },
      });
    } catch (error) {
      const canceled = isAbortError(error) || signal.aborted;
      await this.update(job, {
        status: canceled ? 'canceled' : 'failed',
        finishedAt: new Date().toISOString(),
        error: canceled
          ? { code: 'CANCELED', message: '任务已取消。' }
          : {
              code: error.code || 'EXPORT_FAILED',
              message: error.message || '生成 EPUB 时发生错误。',
            },
        warnings: [...warnings],
        progress: {
          ...job.progress,
          phase: canceled ? 'canceled' : 'failed',
          message: canceled ? '任务已取消' : (error.message || '生成失败'),
        },
      });
    } finally {
      this.controllers.delete(job.id);
    }
  }
}

module.exports = { JobManager, validateBook, validateChapters };

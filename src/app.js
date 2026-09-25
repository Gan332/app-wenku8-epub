'use strict';

const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const express = require('express');
const { version: appVersion } = require('../package.json');
const { AppError, publicError } = require('./errors');
const { JobManager } = require('./jobs');
const { parseBook, parseIndex } = require('./wenku8');

function asyncRoute(handler) {
  return (req, res, next) => Promise.resolve(handler(req, res, next)).catch(next);
}

function contentDisposition(fileName) {
  const safeName = String(fileName || 'book.epub')
    .replace(/[\r\n"\\]/g, '_')
    .replace(/[^\x20-\x7e]/g, '_')
    .trim() || 'book.epub';
  const encoded = encodeURIComponent(String(fileName || 'book.epub')).replace(/[!'()*]/g, (character) =>
    `%${character.charCodeAt(0).toString(16).toUpperCase()}`
  );
  return `attachment; filename="${safeName}"; filename*=UTF-8''${encoded}`;
}

function createApp(options = {}) {
  const rootDirectory = options.rootDirectory || path.resolve(__dirname, '..');
  const publicDirectory = path.join(rootDirectory, 'public');
  const dataDirectory = options.dataDirectory || path.join(rootDirectory, 'data');
  const outputDirectory = options.outputDirectory || path.join(rootDirectory, 'output');
  const jobs = new JobManager({ dataDirectory, outputDirectory });
  const ready = jobs.init();

  const app = express();
  app.disable('x-powered-by');
  app.use((req, res, next) => {
    res.set({
      'X-Content-Type-Options': 'nosniff',
      'Referrer-Policy': 'no-referrer',
      'X-Frame-Options': 'DENY',
      'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
      'Content-Security-Policy': "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'",
    });
    next();
  });
  app.use(express.json({ limit: '4mb', strict: true }));

  app.get('/api/health', (req, res) => res.json({ ok: true, name: 'Wenku8 EPUB Studio', version: appVersion }));
  app.post('/api/parse/book', asyncRoute(async (req, res) => {
    await ready;
    res.json({ book: await parseBook(req.body?.url) });
  }));
  app.post('/api/parse/index', asyncRoute(async (req, res) => {
    await ready;
    res.json({ index: await parseIndex(req.body?.url) });
  }));
  app.get('/api/jobs', asyncRoute(async (req, res) => {
    await ready;
    res.json({ jobs: jobs.list() });
  }));
  app.post('/api/jobs', asyncRoute(async (req, res) => {
    await ready;
    res.status(201).json({ job: await jobs.create(req.body || {}) });
  }));
  app.get('/api/jobs/:id', asyncRoute(async (req, res) => {
    await ready;
    res.json({ job: jobs.get(req.params.id) });
  }));
  app.get('/api/jobs/:id/events', asyncRoute(async (req, res) => {
    await ready;
    const id = req.params.id;
    let closed = false;
    let heartbeat;

    const cleanup = () => {
      if (closed) return;
      closed = true;
      clearInterval(heartbeat);
      jobs.off('update', sendJob);
      res.off('close', cleanup);
    };
    const write = (chunk) => {
      if (closed) return;
      try {
        res.write(chunk);
      } catch {
        cleanup();
        res.end();
      }
    };
    function sendJob(job) {
      if (job.id !== id) return;
      const eventId = job.updatedAt || Date.now();
      write(`id: ${eventId}\nevent: job\ndata: ${JSON.stringify(job)}\n\n`);
    }

    jobs.on('update', sendJob);
    res.once('close', cleanup);
    try {
      const initialJob = jobs.get(id);
      res.set({
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-cache, no-transform',
        'Connection': 'keep-alive',
        'X-Accel-Buffering': 'no',
      });
      res.flushHeaders();
      write('retry: 2000\n\n');
      sendJob(initialJob);
      if (!closed) heartbeat = setInterval(() => write(': keep-alive\n\n'), 15_000);
    } catch (error) {
      cleanup();
      if (!res.headersSent) throw error;
      res.end();
    }
  }));
  app.post('/api/jobs/:id/cancel', asyncRoute(async (req, res) => {
    await ready;
    res.json({ job: await jobs.cancel(req.params.id) });
  }));
  app.get('/api/jobs/:id/download', asyncRoute(async (req, res) => {
    await ready;
    const job = jobs.getOutputPath(req.params.id);
    const stat = await fsp.stat(job.outputPath);
    res.set('Content-Type', 'application/epub+zip');
    res.set('Content-Length', String(stat.size));
    res.set('Content-Disposition', contentDisposition(path.basename(job.outputPath)));
    fs.createReadStream(job.outputPath).on('error', (error) => {
      if (!res.headersSent) nextError(error, res);
      else res.end();
    }).pipe(res);
  }));

  app.use(express.static(publicDirectory, { index: 'index.html', maxAge: 0 }));
  app.get('*path', (req, res) => res.sendFile(path.join(publicDirectory, 'index.html')));

  app.use((error, req, res, next) => {
    if (res.headersSent) return next(error);
    const normalized = error instanceof SyntaxError && error.status === 400 && 'body' in error
      ? new AppError('请求数据不是有效的 JSON。', { code: 'INVALID_JSON' })
      : error;
    const result = publicError(normalized);
    res.status(result.status).json(result.body);
  });

  app.locals.jobManager = jobs;
  app.locals.ready = ready;
  return app;
}

function nextError(error, res) {
  const result = publicError(error);
  res.status(result.status).json(result.body);
}

module.exports = { contentDisposition, createApp };

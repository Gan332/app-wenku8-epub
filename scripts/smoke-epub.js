'use strict';

const fs = require('node:fs');
const fsp = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { parseBookHtml, parseIndexHtml, parseChapterHtml } = require('../src/wenku8');
const { buildEpub } = require('../src/epub');
const { inspectEpub } = require('./epub-check');

const fixture = (name) => path.join(__dirname, '..', 'test', 'fixtures', name);
const dummyImage = Buffer.alloc(96, 0x42);

(async () => {
  const temporaryDirectory = await fsp.mkdtemp(path.join(os.tmpdir(), 'wenku8-epub-smoke-'));
  const outputPath = path.join(temporaryDirectory, 'smoke.epub');
  try {
    const bookUrl = 'https://www.wenku8.net/book/2835.htm';
    const indexUrl = 'https://www.wenku8.net/novel/2/2835/index.htm';
    const book = parseBookHtml(fs.readFileSync(fixture('book.html'), 'utf8'), {
      bookUrl,
      requestedDirectoryUrl: indexUrl,
    });
    const index = parseIndexHtml(fs.readFileSync(fixture('ccindex.html'), 'utf8'), { indexUrl });
    const ordinary = parseChapterHtml(fs.readFileSync(fixture('chapter.html'), 'utf8'), index.chapters[0], index.chapters[0].url);
    const illustration = parseChapterHtml(fs.readFileSync(fixture('illustration.html'), 'utf8'), index.chapters.at(-1), index.chapters.at(-1).url);
    ordinary.sourceId = ordinary.id;
    illustration.sourceId = illustration.id;

    const images = illustration.imageUrls.map((_, chapterIndex) => ({
      chapterId: illustration.id,
      sourceId: illustration.sourceId,
      chapterIndex,
      globalIndex: chapterIndex,
      fileName: `image-${String(chapterIndex + 1).padStart(4, '0')}.jpg`,
      manifestId: `image-${chapterIndex + 1}`,
      mime: 'image/jpeg',
      buffer: dummyImage,
    }));
    const cover = {
      buffer: dummyImage,
      ext: 'jpg',
      mime: 'image/jpeg',
      fileName: 'cover.jpg',
      manifestId: 'cover-image',
      isCover: true,
    };
    const result = await buildEpub({
      book,
      parsedChapters: [ordinary, illustration],
      images,
      cover,
      outputPath,
    });
    const inspected = await inspectEpub(outputPath, { chapters: 2, images: 13, cover: true });
    const temporaryEntries = (await fsp.readdir(temporaryDirectory)).filter((name) => name.endsWith('.tmp'));
    if (temporaryEntries.length) throw new Error(`发现临时文件：${temporaryEntries.join(', ')}`);
    console.log(JSON.stringify({ result, entries: inspected.names.length, chapters: inspected.chapterNames.length, images: inspected.imageNames.length }, null, 2));
  } finally {
    await fsp.rm(temporaryDirectory, { recursive: true, force: true });
  }
})().catch((error) => {
  console.error(`EPUB 冒烟检查失败：${error.message}`);
  process.exitCode = 1;
});

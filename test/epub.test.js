'use strict';

const fs = require('node:fs');
const fsp = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');
const { parseBookHtml, parseChapterHtml, parseIndexHtml } = require('../src/wenku8');
const { attachImageFiles, buildEpub, detectImage } = require('../src/epub');
const { inspectEpub } = require('../scripts/epub-check');

const fixture = (name) => path.join(__dirname, 'fixtures', name);
const read = (name) => fs.readFileSync(fixture(name), 'utf8');
const dummyJpeg = Buffer.alloc(96, 0x42);

function buildFixtureChapters() {
  const indexUrl = 'https://www.wenku8.net/novel/2/2835/index.htm';
  const bookUrl = 'https://www.wenku8.net/book/2835.htm';
  const book = parseBookHtml(read('book.html'), { bookUrl, requestedDirectoryUrl: indexUrl });
  const index = parseIndexHtml(read('ccindex.html'), { indexUrl, bookId: '2835' });
  const ordinary = parseChapterHtml(read('chapter.html'), index.chapters[0], index.chapters[0].url);
  const illustration = parseChapterHtml(read('illustration.html'), index.chapters.at(-1), index.chapters.at(-1).url);
  ordinary.sourceId = ordinary.id;
  illustration.sourceId = illustration.id;
  return { book, chapters: [ordinary, illustration] };
}

test('detects supported image signatures', () => {
  assert.deepEqual(detectImage(Buffer.from([0xff, 0xd8, 0xff, ...Array(40).fill(0)]), ''), { ext: 'jpg', mime: 'image/jpeg' });
  assert.deepEqual(detectImage(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, ...Array(40).fill(0)]), ''), { ext: 'png', mime: 'image/png' });
  assert.equal(detectImage(Buffer.from('not an image'), 'text/plain'), null);
});

test('keeps image blocks aligned when an earlier image is missing', () => {
  const chapters = [{
    sourceId: 'chapter-1',
    blocks: [
      { type: 'image', index: 0 },
      { type: 'image', index: 1 },
      { type: 'image', index: 2 },
    ],
  }];
  const images = [
    { chapterId: 'chapter-1', sourceId: 'chapter-1', chapterIndex: 1, globalIndex: 1, fileName: 'image-0002.jpg' },
    { chapterId: 'chapter-1', sourceId: 'chapter-1', chapterIndex: 2, globalIndex: 2, fileName: 'image-0003.jpg' },
  ];
  attachImageFiles(chapters, images);
  assert.deepEqual(chapters[0].blocks, [
    { type: 'image', fileName: 'image-0002.jpg', number: 1 },
    { type: 'image', fileName: 'image-0003.jpg', number: 2 },
  ]);
});

test('builds an EPUB 3 archive with local images and valid XML', async () => {
  const temporaryDirectory = await fsp.mkdtemp(path.join(os.tmpdir(), 'wenku8-epub-test-'));
  const outputPath = path.join(temporaryDirectory, 'fixture.epub');
  try {
    const { book, chapters } = buildFixtureChapters();
    const images = chapters[1].imageUrls.map((_, index) => ({
      chapterId: chapters[1].id,
      sourceId: chapters[1].sourceId,
      chapterIndex: index,
      globalIndex: index,
      fileName: `image-${String(index + 1).padStart(4, '0')}.jpg`,
      manifestId: `image-${index + 1}`,
      mime: 'image/jpeg',
      buffer: dummyJpeg,
    }));
    const cover = {
      buffer: dummyJpeg,
      ext: 'jpg',
      mime: 'image/jpeg',
      fileName: 'cover.jpg',
      manifestId: 'cover-image',
      isCover: true,
    };
    const result = await buildEpub({ book, parsedChapters: chapters, images, cover, outputPath });
    assert.equal(result.chapters, 2);
    assert.equal(result.images, 13);
    const inspected = await inspectEpub(outputPath, { chapters: 2, images: 13, cover: true });
    assert.equal(inspected.firstEntry.fileName, 'mimetype');
    assert.equal(inspected.firstEntry.compressionMethod, 0);
    assert.equal((await fsp.readdir(temporaryDirectory)).some((name) => name.endsWith('.tmp')), false);
  } finally {
    await fsp.rm(temporaryDirectory, { recursive: true, force: true });
  }
});

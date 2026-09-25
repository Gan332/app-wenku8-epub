'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const archiver = require('archiver');
const { fetchResource, readLimited, sleep } = require('./http');
const { xhtmlEscape } = require('./wenku8');

const MAX_IMAGE_BYTES = 30 * 1024 * 1024;
const IMAGE_TYPES = new Map([
  ['jpg', 'image/jpeg'],
  ['jpeg', 'image/jpeg'],
  ['png', 'image/png'],
  ['gif', 'image/gif'],
  ['webp', 'image/webp'],
]);

function safeName(value, fallback = 'light-novel') {
  const cleaned = String(value || '')
    .normalize('NFKC')
    .replace(/[<>:"/\\|?*\u0000-\u001F]/g, '_')
    .replace(/\s+/g, ' ')
    .replace(/[. ]+$/g, '')
    .trim();
  const reserved = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])$/i;
  return cleaned && !reserved.test(cleaned) ? cleaned.slice(0, 90) : fallback;
}

function itemId(value, index) {
  const cleaned = String(value || '').toLowerCase().replace(/[^a-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 50);
  return `${cleaned || 'item'}-${index + 1}`;
}

function detectImage(buffer, contentType) {
  if (buffer.length >= 3 && buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff) return { ext: 'jpg', mime: 'image/jpeg' };
  if (buffer.length >= 8 && buffer.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return { ext: 'png', mime: 'image/png' };
  if (buffer.length >= 6 && ['GIF87a', 'GIF89a'].includes(buffer.subarray(0, 6).toString('ascii'))) return { ext: 'gif', mime: 'image/gif' };
  if (buffer.length >= 12 && buffer.subarray(0, 4).toString('ascii') === 'RIFF' && buffer.subarray(8, 12).toString('ascii') === 'WEBP') return { ext: 'webp', mime: 'image/webp' };
  const normalized = String(contentType || '').split(';')[0].trim().toLowerCase();
  const extension = normalized.split('/')[1];
  const mime = IMAGE_TYPES.get(extension);
  if (!mime) return null;
  return { ext: extension === 'jpeg' ? 'jpg' : extension, mime };
}

async function downloadImage(sourceUrl, { referer, signal } = {}) {
  const { response } = await fetchResource(sourceUrl, {
    referer,
    signal,
    accept: 'image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8',
    timeoutMs: 30_000,
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  const buffer = await readLimited(response, MAX_IMAGE_BYTES);
  if (buffer.length < 32) throw new Error('图片内容为空');
  const type = detectImage(buffer, response.headers.get('content-type'));
  if (!type) throw new Error(`不支持的图片格式：${response.headers.get('content-type') || '未知'}`);
  return { buffer, ...type };
}

function renderParagraphs(blocks) {
  return blocks.map((block) => {
    if (block.type === 'text') return `      <p>${xhtmlEscape(block.value).replace(/\n/g, '<br/>')}</p>`;
    return [
      '      <figure>',
      `        <img src="../images/${xhtmlEscape(block.fileName)}" alt="插图 ${block.number}"/>`,
      '      </figure>',
    ].join('\n');
  }).join('\n');
}

function chapterXhtml(chapter, blocks) {
  return `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN" lang="zh-CN">
  <head>
    <meta charset="utf-8"/>
    <title>${xhtmlEscape(chapter.title)}</title>
    <link rel="stylesheet" type="text/css" href="style.css"/>
  </head>
  <body>
    <section epub:type="chapter" xmlns:epub="http://www.idpf.org/2007/ops">
      <h1>${xhtmlEscape(chapter.title)}</h1>
${renderParagraphs(blocks)}
    </section>
  </body>
</html>
`;
}

function titleXhtml(book, cover) {
  return `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN" lang="zh-CN">
  <head>
    <meta charset="utf-8"/>
    <title>${xhtmlEscape(book.title)}</title>
    <link rel="stylesheet" type="text/css" href="style.css"/>
  </head>
  <body>
    <section class="title-page" xmlns:epub="http://www.idpf.org/2007/ops">
      ${cover ? `<img class="cover" src="../images/${xhtmlEscape(cover.fileName)}" alt="${xhtmlEscape(book.title)} 封面"/>` : ''}
      <h1>${xhtmlEscape(book.title)}</h1>
      ${book.author ? `<p class="author">${xhtmlEscape(book.author)}</p>` : ''}
      ${book.summary ? `<section class="summary"><h2>内容简介</h2>${book.summary.split(/\n+/).filter(Boolean).map((line) => `<p>${xhtmlEscape(line)}</p>`).join('\n      ')}</section>` : ''}
      <p class="generated">由 Wenku8 EPUB Studio 于 ${xhtmlEscape(new Date().toLocaleDateString('zh-CN'))} 整理</p>
    </section>
  </body>
</html>
`;
}

function navXhtml(chapters) {
  return `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-CN" lang="zh-CN">
  <head><meta charset="utf-8"/><title>目录</title><link rel="stylesheet" type="text/css" href="text/style.css"/></head>
  <body>
    <nav epub:type="toc" id="toc" role="doc-toc">
      <h1>目录</h1>
      <ol>
        <li><a href="text/title.xhtml">书籍信息</a></li>
${chapters.map((chapter) => `        <li><a href="text/${xhtmlEscape(chapter.fileName)}">${xhtmlEscape(chapter.title)}</a></li>`).join('\n')}
      </ol>
    </nav>
  </body>
</html>
`;
}

function ncxXhtml(book, chapters) {
  return `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" xmlns:dtb="http://purl.org/dc/terms/" version="2005-1">
<ncx>
  <head><meta name="dtb:uid" content="urn:uuid:${xhtmlEscape(crypto.randomUUID())}"/></head>
  <docTitle><text>${xhtmlEscape(book.title)}</text></docTitle>
  <navMap>
    <navPoint id="title-page" playOrder="1"><navLabel><text>书籍信息</text></navLabel><content src="text/title.xhtml"/></navPoint>
${chapters.map((chapter, index) => `    <navPoint id="nav-${index + 1}" playOrder="${index + 2}"><navLabel><text>${xhtmlEscape(chapter.title)}</text></navLabel><content src="text/${xhtmlEscape(chapter.fileName)}"/></navPoint>`).join('\n')}
  </navMap>
</ncx>
`;
}

function packageXml(book, manifest, spine) {
  const modified = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z');
  const keywords = (book.tags || []).filter(Boolean).join('、');
  return `<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id" xml:lang="zh-CN" prefix="dcterms: http://purl.org/dc/terms/">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="book-id">urn:uuid:${crypto.randomUUID()}</dc:identifier>
    <dc:title id="title">${xhtmlEscape(book.title)}</dc:title>
    <dc:creator id="creator">${xhtmlEscape(book.author || '未知作者')}</dc:creator>
    <dc:language>zh-CN</dc:language>
    <dc:description>${xhtmlEscape(book.summary || '')}</dc:description>
    ${keywords ? `<dc:subject>${xhtmlEscape(keywords)}</dc:subject>` : ''}
    <dc:source>${xhtmlEscape(book.sourceUrl || book.bookUrl || '')}</dc:source>
    <meta property="dcterms:modified">${modified}</meta>
    <meta refines="#creator" property="role" scheme="marc:relators">aut</meta>
    <meta refines="#creator" property="file-as">${xhtmlEscape(book.author || '未知作者')}</meta>
  </metadata>
  <manifest>
${manifest.map((entry) => `    <item id="${xhtmlEscape(entry.id)}" href="${xhtmlEscape(entry.href)}" media-type="${xhtmlEscape(entry.mediaType)}"${entry.properties ? ` properties="${xhtmlEscape(entry.properties)}"` : ''}/>`).join('\n')}
  </manifest>
  <spine toc="ncx">
${spine.map((entry) => `    <itemref idref="${xhtmlEscape(entry.id)}" linear="yes"/>`).join('\n')}
  </spine>
</package>
`;
}

const CSS = `@charset "utf-8";
html { font-family: "Noto Serif CJK SC", "Source Han Serif SC", "Songti SC", SimSun, serif; }
body { margin: 0; color: #272522; line-height: 1.85; }
section { padding: 1.2em 1.35em; }
h1 { font-size: 1.55em; line-height: 1.4; margin: .4em 0 1.1em; text-align: center; }
h2 { font-size: 1.15em; }
p { margin: .65em 0; text-indent: 2em; text-align: justify; }
.title-page { min-height: 85vh; display: flex; flex-direction: column; justify-content: center; align-items: center; text-align: center; page-break-after: always; }
.title-page h1 { font-size: 2em; margin-bottom: .25em; }
.title-page .author { text-indent: 0; font-size: 1.1em; color: #655f56; }
.title-page .cover { max-width: 78%; max-height: 46vh; object-fit: contain; margin-bottom: 1.6em; }
.title-page .summary { width: 100%; text-align: left; margin-top: 2.5em; page-break-before: always; }
.title-page .summary p { font-size: .88em; color: #5f5951; }
.title-page .generated { margin-top: 3em; text-indent: 0; color: #948c81; font-size: .72em; }
figure { margin: 1.4em auto; text-align: center; page-break-inside: avoid; }
figure img { max-width: 100%; max-height: 90vh; height: auto; object-fit: contain; }
`;

function buildNavigation(chapters) {
  return chapters.map((chapter, index) => ({
    ...chapter,
    id: itemId('chapter', index),
    fileName: `chapter-${String(index + 1).padStart(4, '0')}.xhtml`,
  }));
}

function attachImageFiles(chapters, images) {
  const grouped = new Map();
  for (const image of images) {
    if (!grouped.has(image.chapterId)) grouped.set(image.chapterId, []);
    grouped.get(image.chapterId).push(image);
  }
  for (const chapter of chapters) {
    const imagesForChapter = grouped.get(chapter.sourceId) || grouped.get(chapter.id) || [];
    chapter.blocks = chapter.blocks.map((block) => {
      if (block.type !== 'image') return block;
      // Image downloads are allowed to fail independently. Match the
      // original chapter-local index instead of using the compacted array
      // position, otherwise a failed image makes every later image shift.
      const image = imagesForChapter.find((item) => item.chapterIndex === block.index);
      if (!image) return null;
      return { type: 'image', fileName: image.fileName, number: image.globalIndex };
    }).filter(Boolean);
  }
  return chapters;
}

async function buildEpub({ book, parsedChapters, images, cover, outputPath, signal, onProgress = () => {} }) {
  const chapters = buildNavigation(parsedChapters);
  attachImageFiles(chapters, images);

  const manifest = [
    { id: 'nav', href: 'nav.xhtml', mediaType: 'application/xhtml+xml', properties: 'nav' },
    { id: 'ncx', href: 'toc.ncx', mediaType: 'application/x-dtbncx+xml' },
    { id: 'style', href: 'text/style.css', mediaType: 'text/css' },
    { id: 'title-page', href: 'text/title.xhtml', mediaType: 'application/xhtml+xml' },
  ];
  const spine = [{ id: 'title-page' }];
  for (const chapter of chapters) {
    manifest.push({ id: chapter.id, href: `text/${chapter.fileName}`, mediaType: 'application/xhtml+xml' });
    spine.push({ id: chapter.id });
  }
  for (const image of [...(cover ? [cover] : []), ...images]) {
    manifest.push({ id: image.manifestId, href: `images/${image.fileName}`, mediaType: image.mime, properties: image.isCover ? 'cover-image' : undefined });
  }

  await fsp.mkdir(path.dirname(outputPath), { recursive: true });
  const tempPath = `${outputPath}.${crypto.randomUUID()}.tmp`;
  await new Promise((resolve, reject) => {
    const output = fs.createWriteStream(tempPath);
    const archive = archiver('zip', { zlib: { level: 9 } });
    output.on('close', resolve);
    output.on('error', reject);
    archive.on('warning', reject);
    archive.on('error', reject);
    archive.pipe(output);
    archive.append('application/epub+zip', { name: 'mimetype', store: true, date: new Date('2000-01-01T00:00:00Z') });
    const add = (name, content) => archive.append(Buffer.isBuffer(content) ? content : Buffer.from(content, 'utf8'), { name });
    add('META-INF/container.xml', `<?xml version="1.0" encoding="UTF-8"?>\n<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">\n  <rootfiles>\n    <rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/>\n  </rootfiles>\n</container>`);
    add('EPUB/package.opf', packageXml(book, manifest, spine));
    add('EPUB/nav.xhtml', navXhtml(chapters));
    add('EPUB/toc.ncx', ncxXhtml(book, chapters));
    add('EPUB/text/style.css', CSS);
    add('EPUB/text/title.xhtml', titleXhtml(book, cover));
    for (const chapter of chapters) add(`EPUB/text/${chapter.fileName}`, chapterXhtml(chapter, chapter.blocks));
    for (const image of [...(cover ? [cover] : []), ...images]) archive.append(image.buffer, { name: `EPUB/images/${image.fileName}`, date: new Date('2000-01-01T00:00:00Z') });
    onProgress({ phase: 'packaging', percent: 97, message: '正在写入 EPUB 容器…' });
    archive.finalize().catch(reject);
  });

  if (signal?.aborted) {
    await fsp.rm(tempPath, { force: true });
    throw signal.reason || new DOMException('任务已取消', 'AbortError');
  }
  await fsp.rm(outputPath, { force: true });
  await fsp.rename(tempPath, outputPath);
  const stat = await fsp.stat(outputPath);
  return { outputPath, bytes: stat.size, chapters: chapters.length, images: images.length + (cover ? 1 : 0) };
}

async function fetchCover(book, outputDirectory, { signal } = {}) {
  if (!book.coverUrl) return null;
  try {
    const image = await downloadImage(book.coverUrl, { referer: book.bookUrl || book.sourceUrl, signal });
    return {
      ...image,
      buffer: image.buffer,
      fileName: `cover.${image.ext}`,
      manifestId: 'cover-image',
      isCover: true,
    };
  } catch (error) {
    console.warn(`封面下载失败（已跳过）：${error.message}`);
    return null;
  }
}

async function collectChapterImages(
  chapter,
  { outputDirectory, globalOffset, signal, onImage, downloadImage: downloadImageImpl = downloadImage },
) {
  const downloaded = [];
  for (let index = 0; index < chapter.imageUrls.length; index += 1) {
    if (signal?.aborted) throw signal.reason || new DOMException('任务已取消', 'AbortError');
    const sourceUrl = chapter.imageUrls[index];
    const globalIndex = globalOffset + index;
    onImage?.({ chapter, index, total: chapter.imageUrls.length, sourceUrl, status: 'started' });
    try {
      const image = await downloadImageImpl(sourceUrl, { referer: chapter.sourceUrl, signal });
      downloaded.push({
        ...image,
        chapterId: chapter.id,
        sourceId: chapter.sourceId,
        chapterIndex: index,
        globalIndex,
        fileName: `image-${String(globalIndex + 1).padStart(4, '0')}.${image.ext}`,
        manifestId: `image-${globalIndex + 1}`,
        isCover: false,
      });
    } catch (error) {
      if (signal?.aborted || error?.name === 'AbortError' || error?.code === 'ABORT_ERR') throw error;
      onImage?.({ chapter, index, total: chapter.imageUrls.length, sourceUrl, status: 'failed', error });
      console.warn(`图片下载失败（已跳过）：${sourceUrl} - ${error.message}`);
    }
    await sleep(120, signal);
  }
  return downloaded;
}

module.exports = {
  CSS,
  attachImageFiles,
  buildNavigation,
  buildEpub,
  collectChapterImages,
  detectImage,
  downloadImage,
  fetchCover,
  itemId,
  safeName,
};

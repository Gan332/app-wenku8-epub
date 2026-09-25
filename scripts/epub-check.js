'use strict';

const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const yauzl = require('yauzl');
const { SaxesParser } = require('saxes');

function readZip(filePath) {
  return new Promise((resolve, reject) => {
    yauzl.open(filePath, { lazyEntries: true }, (openError, zip) => {
      if (openError) return reject(openError);
      const entries = new Map();
      let firstEntry = null;
      let settled = false;
      const fail = (error) => {
        if (settled) return;
        settled = true;
        try { zip.close(); } catch {}
        reject(error);
      };
      zip.on('error', fail);
      zip.on('entry', (entry) => {
        if (!firstEntry) {
          firstEntry = {
            fileName: entry.fileName,
            compressionMethod: entry.compressionMethod,
            uncompressedSize: entry.uncompressedSize,
          };
        }
        zip.openReadStream(entry, (streamError, stream) => {
          if (streamError) return fail(streamError);
          const chunks = [];
          stream.on('data', (chunk) => chunks.push(Buffer.from(chunk)));
          stream.on('error', fail);
          stream.on('end', () => {
            entries.set(entry.fileName, Buffer.concat(chunks));
            zip.readEntry();
          });
        });
      });
      zip.on('end', () => {
        if (settled) return;
        settled = true;
        resolve({ firstEntry, entries });
      });
      zip.readEntry();
    });
  });
}

function assertXml(buffer, label) {
  const parser = new SaxesParser({ xmlns: false });
  let parseError = null;
  parser.on('error', (error) => { parseError = error; });
  parser.write(buffer.toString('utf8')).close();
  if (parseError) throw new Error(`${label} 不是有效 XML: ${parseError.message}`);
}

async function inspectEpub(filePath, options = {}) {
  const { firstEntry, entries } = await readZip(filePath);
  const names = [...entries.keys()];
  const required = [
    'mimetype',
    'META-INF/container.xml',
    'EPUB/package.opf',
    'EPUB/nav.xhtml',
    'EPUB/toc.ncx',
    'EPUB/text/style.css',
    'EPUB/text/title.xhtml',
  ];
  for (const name of required) {
    if (!entries.has(name)) throw new Error(`EPUB 缺少 ${name}`);
  }
  if (firstEntry?.fileName !== 'mimetype' || firstEntry.compressionMethod !== 0) {
    throw new Error('EPUB 的第一个条目必须是不压缩的 mimetype');
  }
  if (entries.get('mimetype').toString('utf8') !== 'application/epub+zip') {
    throw new Error('EPUB mimetype 内容不正确');
  }
  for (const name of ['META-INF/container.xml', 'EPUB/package.opf', 'EPUB/nav.xhtml', 'EPUB/toc.ncx', 'EPUB/text/title.xhtml']) {
    assertXml(entries.get(name), name);
  }

  const chapterNames = names.filter((name) => /^EPUB\/text\/chapter-\d+\.xhtml$/.test(name));
  if (options.chapters !== undefined && chapterNames.length !== options.chapters) {
    throw new Error(`章节数量错误：期望 ${options.chapters}，实际 ${chapterNames.length}`);
  }
  for (const name of chapterNames) assertXml(entries.get(name), name);

  const imageNames = names.filter((name) => name.startsWith('EPUB/images/'));
  if (options.images !== undefined && imageNames.length !== options.images) {
    throw new Error(`图片数量错误：期望 ${options.images}，实际 ${imageNames.length}`);
  }
  if (options.minImages !== undefined && imageNames.length < options.minImages) {
    throw new Error(`图片数量不足：期望至少 ${options.minImages}，实际 ${imageNames.length}`);
  }

  const packageText = entries.get('EPUB/package.opf').toString('utf8');
  const titleText = entries.get('EPUB/text/title.xhtml').toString('utf8');
  if (options.cover) {
    if (!imageNames.includes('EPUB/images/cover.jpg')) throw new Error('封面图片未写入 EPUB');
    if (!titleText.includes('src="../images/cover.jpg"')) throw new Error('标题页封面相对路径错误');
    if (!packageText.includes('properties="cover-image"')) throw new Error('封面未标记为 cover-image');
  }
  for (const name of chapterNames) {
    const text = entries.get(name).toString('utf8');
    if (/<img\b[^>]+src="https?:\/\//i.test(text)) throw new Error(`${name} 仍引用远程图片`);
  }
  return { firstEntry, names, chapterNames, imageNames, entries };
}

module.exports = { inspectEpub, readZip, assertXml };

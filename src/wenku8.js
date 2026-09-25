'use strict';

const cheerio = require('cheerio');
const { AppError } = require('./errors');
const { fetchHtml } = require('./http');

const WENKU_HOSTS = new Set(['wenku8.net', 'www.wenku8.net', 'wenku8.cc', 'www.wenku8.cc', 'wenku8.com', 'www.wenku8.com']);

function cleanText(value = '') {
  return String(value)
    .replace(/\u00a0/g, ' ')
    .replace(/[ \t]+/g, ' ')
    .replace(/\s*\n\s*/g, '\n')
    .trim();
}

function cleanInline(value = '') {
  return cleanText(value).replace(/\s+/g, ' ');
}

function assertWenkuUrl(value) {
  const url = new URL(value);
  if (!WENKU_HOSTS.has(url.hostname.toLowerCase())) {
    throw new AppError('目前只接受 wenku8.net、wenku8.cc 或 wenku8.com 页面。', { code: 'UNSUPPORTED_HOST' });
  }
  return url;
}

function normalizeSourceUrl(input) {
  const raw = String(input || '').trim();
  if (!raw) throw new AppError('请输入 wenku8 书籍或目录网址。', { code: 'URL_REQUIRED' });
  const withProtocol = /^\d+$/.test(raw) ? `https://www.wenku8.net/book/${raw}.htm` : raw;
  let url;
  try {
    url = new URL(withProtocol);
  } catch {
    throw new AppError('网址格式无效，请粘贴完整网址。', { code: 'INVALID_URL' });
  }
  if (!['http:', 'https:'].includes(url.protocol)) throw new AppError('只支持 HTTP/HTTPS 网址。', { code: 'UNSUPPORTED_PROTOCOL' });
  if (url.pathname.startsWith('/cdn-cgi/')) {
    throw new AppError('这是 Cloudflare 验证链接，不是书籍页面。请重新复制 wenku8 书籍链接。', { code: 'CHALLENGE_URL' });
  }
  assertWenkuUrl(url);
  url.hash = '';
  return url;
}

function sourceIds(url) {
  let bookId = null;
  let categoryId = null;
  let kind = null;

  const novel = url.pathname.match(/\/novel\/(\d+)\/(\d+)\/(?:index\.html?|(\d+)\.html?)$/i);
  if (novel) {
    kind = novel[3] ? 'chapter' : 'index';
    categoryId = novel[1];
    bookId = novel[2];
  } else if (/\/book\/\d+\.html?$/i.test(url.pathname)) {
    kind = 'book';
    bookId = url.pathname.match(/\/book\/(\d+)\.html?$/i)?.[1];
  } else if (/\/modules\/article\/articleinfo\.php$/i.test(url.pathname)) {
    kind = 'book';
    bookId = url.searchParams.get('id');
  } else {
    throw new AppError('无法从网址识别书籍编号，请使用 /book/编号.htm 或 /novel/分类/编号/index.htm 链接。', { code: 'UNRECOGNIZED_SOURCE' });
  }

  if (bookId && !/^\d+$/.test(bookId)) throw new AppError('网址中的书籍编号无效。', { code: 'INVALID_BOOK_ID' });
  if (kind === 'chapter') throw new AppError('请提供书籍页或目录页，而不是单个章节页。', { code: 'CHAPTER_URL_NOT_ALLOWED' });
  return { kind, bookId, categoryId };
}

function looksLikeChallenge(html) {
  return /<title[^>]*>\s*(Just a moment|Attention Required)|Checking your browser|cf-chl-|__cf_chl/i.test(html.slice(0, 20_000));
}

function fieldValue($, selector, prefix) {
  const element = $(selector).filter((_, node) => cleanInline($(node).text()).startsWith(prefix)).first();
  if (!element.length) return '';
  return cleanInline(element.text().replace(/^.*?内容/, prefix === '内容简介：' ? '内容' : prefix).replace(prefix, '').trim());
}

function parseDescription($) {
  const descriptionNode = $('span.hottext').filter((_, node) => {
    const text = cleanInline($(node).text());
    return text.startsWith('内容简介') && text !== '内容简介：';
  }).first();
  if (descriptionNode.length) {
    return cleanText(descriptionNode.text().replace(/^内容简介[：:]?\s*/, ''));
  }

  const marker = $('span.hottext').filter((_, node) => cleanInline($(node).text()) === '内容简介：').first();
  if (marker.length) {
    // The source page places a <br> between the label and the description
    // span, so next('span') is not sufficient here.
    const sibling = marker.nextAll('span').filter((_, node) => cleanInline($(node).text())).first();
    if (sibling.length) return cleanText(sibling.text());
  }

  const body = cleanText($('body').text());
  return body.match(/内容简介[：:]\s*([\s\S]{0,3000}?)(?:最近章节|点击阅读|TXT|下载)/)?.[1]?.trim() || '';
}

function parseBookHtml(html, { bookUrl, requestedDirectoryUrl = null }) {
  if (looksLikeChallenge(html)) {
    throw new AppError('源站要求浏览器验证。请稍后重试，或在浏览器中打开书籍页确认可以正常访问。', { status: 502, code: 'UPSTREAM_CHALLENGE' });
  }
  const $ = cheerio.load(html);
  const content = $('#content').first();
  if (!content.length) throw new AppError('未能识别书籍页面，源站结构可能已变化。', { code: 'BOOK_PARSE_FAILED' });

  const titleNode = content.find('td[colspan="5"] b').first();
  const titleFromPage = cleanInline(titleNode.text());
  const documentTitle = cleanInline($('title').first().text());
  const title = titleFromPage || documentTitle.split(/\s+-+\s+/)[0] || '未命名轻小说';
  const author = fieldValue($, 'td', '小说作者：') || fieldValue($, 'td', '小说作者:');
  const status = fieldValue($, 'td', '文章状态：') || fieldValue($, 'td', '文章状态:');
  const updatedAt = fieldValue($, 'td', '最后更新：') || fieldValue($, 'td', '最后更新:');

  const breadcrumbCategory = content.find('a[href*="articlelist.php"]').first().text().trim();
  const tagText = content.find('.hottext').filter((_, node) => $(node).text().includes('作品Tags')).first().text();
  const tags = cleanInline(tagText).replace(/^作品Tags[：:]?/, '').split(/\s+/).filter(Boolean);
  const category = breadcrumbCategory || '轻小说';
  const bookId = new URL(bookUrl).pathname.match(/\/book\/(\d+)\.html?$/i)?.[1];
  const directoryLink = content.find('a[href*="/novel/"]').filter((_, node) => /\/index\.html?$/i.test($(node).attr('href') || '')).first().attr('href');
  const directoryUrl = new URL(requestedDirectoryUrl || directoryLink, bookUrl).toString();
  const coverNode = content.find('img[src*="/image/"]').first();
  const coverUrl = coverNode.attr('src') ? new URL(coverNode.attr('src'), bookUrl).toString() : null;
  const pathIds = sourceIds(new URL(directoryUrl));

  return {
    id: bookId || pathIds.bookId,
    title,
    author: author || '未知作者',
    category,
    status: status || '未知',
    updatedAt: updatedAt || '',
    tags,
    summary: parseDescription($),
    coverUrl,
    sourceUrl: bookUrl,
    bookUrl,
    directoryUrl,
    categoryId: pathIds.categoryId,
  };
}

async function parseBook(input) {
  const requested = normalizeSourceUrl(input);
  const ids = sourceIds(requested);
  const bookUrl = `https://www.wenku8.net/book/${ids.bookId}.htm`;
  const page = await fetchHtml(bookUrl);
  return parseBookHtml(page.html, {
    bookUrl: page.finalUrl || bookUrl,
    requestedDirectoryUrl: ids.kind === 'index' ? requested.toString() : null,
  });
}

function extractFieldFromTd($, td, label) {
  const text = cleanInline(td.text());
  if (!text.startsWith(label)) return '';
  return text.slice(label.length).trim();
}

function resolveUrl(value, baseUrl) {
  if (!value) return null;
  const raw = String(value).trim();
  if (!raw || /^data:/i.test(raw) || /^javascript:/i.test(raw)) return null;
  try {
    return new URL(raw, baseUrl).toString();
  } catch {
    return null;
  }
}

function looksLikeSpacerImage($, node) {
  const src = $(node).attr('src') || '';
  const width = Number($(node).attr('width') || 0);
  const height = Number($(node).attr('height') || 0);
  return (width > 0 && width <= 3) || (height > 0 && height <= 3) || /spacer|blank\.(gif|png)|pixel/i.test(src);
}

function imageSource($, node, baseUrl) {
  const dataBase = resolveUrl($(node).attr('data-base'), baseUrl) || baseUrl;
  for (const name of ['data-original', 'data-src', 'data-lazy-src', 'src']) {
    const value = resolveUrl($(node).attr(name), dataBase);
    if (value) return value;
  }
  return null;
}

function xhtmlEscape(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

function normalizeBlocks(text, imageCount) {
  const lines = String(text).replace(/\r/g, '').replace(/\u00a0/g, ' ').split('\n');
  const blocks = [];
  let paragraph = [];

  const flush = () => {
    const value = paragraph.join(' ').replace(/[ \t]+/g, ' ').trim();
    if (value) blocks.push({ type: 'text', value });
    paragraph = [];
  };

  for (const rawLine of lines) {
    const line = rawLine.replace(/[ \t]+/g, ' ').trim();
    const token = line.match(/^@@WENKU8_IMAGE_(\d+)@@$/);
    if (token) {
      flush();
      const index = Number(token[1]);
      if (index < imageCount) blocks.push({ type: 'image', index });
      continue;
    }
    if (!line) {
      flush();
      continue;
    }
    paragraph.push(line);
  }
  flush();
  return blocks;
}

function parseIndexHtml(html, { indexUrl, finalUrl = indexUrl, bookId = null }) {
  if (looksLikeChallenge(html)) {
    throw new AppError('源站要求浏览器验证，暂时无法读取目录。', { status: 502, code: 'UPSTREAM_CHALLENGE' });
  }
  const url = new URL(finalUrl);
  const $ = cheerio.load(html);
  const chapters = [];
  const seen = new Set();
  let currentVolume = '正文';

  $('table tr').each((_, row) => {
    const rowNode = $(row);
    const volumeNode = rowNode.find('td.vcss').first();
    if (volumeNode.length) {
      const volume = cleanInline(volumeNode.text());
      if (volume) currentVolume = volume;
      return;
    }
    rowNode.find('td.ccss a[href]').each((__, anchor) => {
      const title = cleanInline($(anchor).text());
      if (!title || /^(上一页|下一页|返回目录)$/.test(title)) return;
      let chapterUrl;
      try {
        chapterUrl = new URL($(anchor).attr('href'), finalUrl).toString();
        assertWenkuUrl(chapterUrl);
      } catch {
        return;
      }
      if (seen.has(chapterUrl)) return;
      seen.add(chapterUrl);
      const chapterId = new URL(chapterUrl).pathname.match(/\/(\d+)\.html?$/i)?.[1] || String(chapters.length + 1);
      chapters.push({
        id: chapterId,
        title,
        url: chapterUrl,
        volume: currentVolume,
        order: chapters.length + 1,
        isIllustration: /插图|插畫|彩页|彩頁/.test(title),
      });
    });
  });

  if (!chapters.length) {
    throw new AppError('目录中没有识别到章节。请确认该书确实有可访问的目录。', { code: 'NO_CHAPTERS' });
  }

  const documentTitle = cleanInline($('title').first().text());
  const title = documentTitle.split(/\s+-+\s+/)[0] || '未命名轻小说';
  const ids = sourceIds(url);
  return { title, url: finalUrl, bookId: bookId || ids.bookId, chapters };
}

async function parseIndex(input) {
  let url = normalizeSourceUrl(input);
  let ids = sourceIds(url);
  if (ids.kind !== 'index') {
    const book = await parseBook(url);
    if (!book.directoryUrl) {
      throw new AppError('书籍页没有提供目录链接。', { code: 'INDEX_URL_REQUIRED' });
    }
    url = normalizeSourceUrl(book.directoryUrl);
    ids = sourceIds(url);
  }
  const page = await fetchHtml(url.toString());
  return parseIndexHtml(page.html, { indexUrl: url.toString(), finalUrl: page.finalUrl || url.toString(), bookId: ids.bookId });
}
function parseChapterHtml(html, chapter, pageUrl = chapter.url) {
  if (!chapter?.url) throw new AppError('章节缺少网址。', { code: 'CHAPTER_URL_REQUIRED' });
  const chapterUrl = new URL(chapter.url);
  assertWenkuUrl(chapterUrl);
  if (looksLikeChallenge(html)) {
    throw new AppError('源站要求浏览器验证，暂时无法读取章节。', { status: 502, code: 'UPSTREAM_CHALLENGE' });
  }

  const $ = cheerio.load(html);
  const source = $('#content').first();
  if (!source.length) {
    throw new AppError(`未能识别章节正文：${chapter.title || chapterUrl.pathname}`, { code: 'CHAPTER_PARSE_FAILED' });
  }
  const root = source.clone();
  root.find('#contentdp, script, style, iframe, object, embed, form, input, button, noscript, link, meta').remove();
  root.find('[id^="adv"], [class*="advert"], [class*="banner"]').remove();

  const imageUrls = [];
  root.find('img').each((index, node) => {
    const url = imageSource($, node, pageUrl);
    if (!url || looksLikeSpacerImage($, node)) {
      $(node).remove();
      return;
    }
    const imageIndex = imageUrls.length;
    imageUrls.push(url);
    $(node).replaceWith(`\n@@WENKU8_IMAGE_${imageIndex}@@\n`);
  });
  root.find('br').replaceWith('\n');
  root.find('p').each((_, node) => {
    $(node).append('\n');
  });

  const title = cleanInline(chapter.title) || cleanInline($('title').first().text()).split(/\s+-\s+/).pop() || '未命名章节';
  const blocks = normalizeBlocks(root.text(), imageUrls.length);
  const plainText = blocks.filter((block) => block.type === 'text').map((block) => block.value).join('');
  if (plainText.length < 10 && !imageUrls.length) {
    throw new AppError(`章节正文为空：${title}`, { code: 'EMPTY_CHAPTER' });
  }

  return {
    id: chapter.id || String(chapter.order || ''),
    title,
    volume: cleanInline(chapter.volume || '正文'),
    order: Number(chapter.order || 0),
    sourceUrl: pageUrl,
    imageUrls,
    blocks,
    textLength: plainText.length,
  };

}

async function parseChapter(chapter, { signal } = {}) {
  if (!chapter?.url) throw new AppError('章节缺少网址。', { code: 'CHAPTER_URL_REQUIRED' });
  const chapterUrl = new URL(chapter.url);
  assertWenkuUrl(chapterUrl);
  const page = await fetchHtml(chapterUrl.toString(), { signal });
  return parseChapterHtml(page.html, chapter, page.finalUrl || chapterUrl.toString());
}
module.exports = {
  WENKU_HOSTS,
  assertWenkuUrl,
  cleanInline,
  cleanText,
  normalizeSourceUrl,
  parseBook,
  parseBookHtml,
  parseChapter,
  parseChapterHtml,
  parseIndex,
  parseIndexHtml,
  sourceIds,
  xhtmlEscape,
};

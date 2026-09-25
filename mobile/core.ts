import { registerPlugin } from '@capacitor/core';
import { Directory, Encoding, Filesystem } from '@capacitor/filesystem';
import { strToU8, Zip, ZipDeflate, ZipPassThrough } from 'fflate';

const WENKU_DOMAINS = ['wenku8.net', 'wenku8.cc', 'wenku8.com'] as const;
const MAX_CHAPTERS = 2000;
const ACTIVE_STATES = new Set(['queued', 'running']);
const JOB_RETENTION = 50;
const DEFAULT_BOOK_URL = 'https://www.wenku8.net/book/';

type JobStatus = 'queued' | 'running' | 'completed' | 'failed' | 'canceled';
type JobPhase = 'queued' | 'fetching' | 'images' | 'cover' | 'packaging' | 'completed' | 'failed' | 'canceled';

export interface Book {
  id: string | null;
  title: string;
  author: string;
  category: string;
  status: string;
  updatedAt: string;
  tags: string[];
  summary: string;
  coverUrl: string | null;
  sourceUrl: string;
  bookUrl: string;
  directoryUrl: string | null;
}

export interface Chapter {
  id: string;
  title: string;
  url: string;
  volume: string;
  order: number;
  isIllustration: boolean;
}

export interface BookIndex {
  title: string;
  url: string;
  bookId: string | null;
  chapters: Chapter[];
}

export type ContentBlock =
  | { type: 'text'; value: string }
  | { type: 'image'; index: number };

export interface ParsedChapter {
  id: string;
  title: string;
  volume: string;
  order: number;
  sourceUrl: string;
  imageUrls: string[];
  blocks: ContentBlock[];
  textLength: number;
}

export interface JobProgress {
  phase: JobPhase;
  percent: number;
  completed: number;
  total: number;
  imageCompleted: number;
  message: string;
  currentTitle: string;
}

export interface PublicJob {
  id: string;
  status: JobStatus;
  book: Pick<Book, 'id' | 'title' | 'author'>;
  chapterCount: number;
  progress: JobProgress;
  createdAt: string;
  updatedAt: string;
  finishedAt: string | null;
  error: { code: string; message: string } | null;
  warnings: string[];
  file: null | {
    name: string;
    size: number;
    uri: string;
    downloadUrl: string;
  };
}

interface StoredJob extends Omit<PublicJob, 'file' | 'book'> {
  book: Book;
  options: { includeCover: boolean };
  outputUri: string | null;
  outputSize: number | null;
  imageCount: number;
}

interface DownloadedImage {
  chapterId: string;
  sourceId: string;
  chapterIndex: number;
  globalIndex: number;
  fileName: string;
  manifestId: string;
  mime: string;
  localPath: string;
  localUri: string;
  ext: string;
  bytes: number;
  isCover: boolean;
}

interface TextResult {
  html: string;
  finalUrl: string;
  contentType: string;
  status: number;
}

interface DownloadResult {
  path: string;
  uri: string;
  mime: string;
  ext: string;
  bytes: number;
}

interface Wenku8HttpPlugin {
  fetchText(options: { url: string; referer?: string; accept?: string; jobId: string }): Promise<TextResult>;
  downloadFile(options: { url: string; referer: string; jobId: string; fileName?: string }): Promise<DownloadResult>;
  cancelJob(options: { jobId: string }): Promise<void>;
}

interface TaskGuardPlugin {
  start(options: { jobId: string; title: string; message: string }): Promise<void>;
  update(options: { jobId: string; message: string; percent: number }): Promise<void>;
  stop(options: { jobId: string }): Promise<void>;
  requestNotificationPermission(): Promise<{ granted: boolean }>;
}

interface EpubFilePlugin {
  save(options: { jobId: string; sourceUri: string; fileName: string }): Promise<{ uri: string; bytes: number; location: string }>;
  share(options: { jobId: string; sourceUri: string; fileName: string; mimeType?: string }): Promise<void>;
}

interface TaskEntry {
  job: StoredJob;
  chapters: Chapter[];
}

const NativeHttp = registerPlugin<Wenku8HttpPlugin>('Wenku8Http');
const NativeTaskGuard = registerPlugin<TaskGuardPlugin>('TaskGuard');
const NativeEpubFile = registerPlugin<EpubFilePlugin>('EpubFile');

class MobileError extends Error {
  code: string;
  status: number;

  constructor(message: string, code = 'MOBILE_ERROR', status = 400) {
    super(message);
    this.name = 'MobileError';
    this.code = code;
    this.status = status;
  }
}

function toError(error: unknown): MobileError {
  if (error instanceof MobileError) return error;
  if (error && typeof error === 'object') {
    const value = error as { message?: string; code?: string; status?: number };
    return new MobileError(value.message || '操作失败。', value.code || 'MOBILE_ERROR', value.status || 500);
  }
  return new MobileError(String(error || '操作失败。'));
}

function publicId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `${Date.now().toString(36)}-${crypto.randomUUID().slice(0, 8)}`;
  }
  return `${Date.now().toString(36)}-${Math.random().toString(16).slice(2, 10)}`;
}

function count(value: unknown, fallback = 0): number {
  if (value === undefined || value === null || value === '') return fallback;
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? Math.floor(number) : fallback;
}

function clampPercent(value: unknown): number {
  const number = Number(value);
  if (!Number.isFinite(number)) return 0;
  return Math.max(0, Math.min(100, Math.round(number)));
}

function percentage(current: number, total: number): number {
  if (!total) return 0;
  return clampPercent((current / total) * 100);
}

function cleanText(value: unknown): string {
  return String(value ?? '')
    .replace(/\u00a0/g, ' ')
    .replace(/[ \t]+/g, ' ')
    .replace(/\s*\n\s*/g, '\n')
    .trim();
}

function cleanInline(value: unknown): string {
  return cleanText(value).replace(/\s+/g, ' ');
}

function isWenkuHost(hostname: string): boolean {
  const host = hostname.toLowerCase();
  return WENKU_DOMAINS.some((domain) => host === domain || host.endsWith(`.${domain}`));
}

function assertWenkuUrl(value: string | URL): URL {
  let url: URL;
  try {
    url = new URL(String(value));
  } catch {
    throw new MobileError('网址格式无效。', 'INVALID_URL');
  }
  if (!['http:', 'https:'].includes(url.protocol)) {
    throw new MobileError('只支持 HTTP/HTTPS 网址。', 'UNSUPPORTED_PROTOCOL');
  }
  if (url.username || url.password) {
    throw new MobileError('网址不能包含用户名或密码。', 'URL_CREDENTIALS');
  }
  if (!isWenkuHost(url.hostname)) {
    throw new MobileError('目前只接受 wenku8.net、wenku8.cc 或 wenku8.com 页面。', 'UNSUPPORTED_HOST');
  }
  if (url.protocol === 'http:') url.protocol = 'https:';
  url.hash = '';
  return url;
}

function normalizeSourceUrl(input: string): URL {
  const raw = String(input || '').trim();
  if (!raw) throw new MobileError('请输入 wenku8 书籍或目录网址。', 'URL_REQUIRED');
  return assertWenkuUrl(/^\d+$/.test(raw) ? `${DEFAULT_BOOK_URL}${raw}.htm` : raw);
}

function sourceIds(url: URL): { kind: 'book' | 'index'; bookId: string; categoryId: string | null } {
  const novel = url.pathname.match(/\/novel\/(\d+)\/(\d+)\/(?:index\.html?)$/i);
  if (novel) return { kind: 'index', categoryId: novel[1], bookId: novel[2] };
  const book = url.pathname.match(/\/book\/(\d+)\.html?$/i);
  if (book) return { kind: 'book', categoryId: null, bookId: book[1] };
  const article = url.pathname.match(/\/modules\/article\/articleinfo\.php$/i);
  if (article) return { kind: 'book', categoryId: null, bookId: url.searchParams.get('id') || '' };
  throw new MobileError('无法从网址识别书籍编号。', 'UNRECOGNIZED_SOURCE');
}

function resolveUrl(value: string | null | undefined, baseUrl: string): string | null {
  if (!value) return null;
  const raw = String(value).trim();
  if (!raw || /^(data|javascript|blob):/i.test(raw)) return null;
  try {
    return new URL(raw, baseUrl).toString();
  } catch {
    return null;
  }
}

function parseHtml(html: string): Document {
  return new DOMParser().parseFromString(html, 'text/html');
}

function looksLikeChallenge(html: string): boolean {
  return /<title[^>]*>\s*(Just a moment|Attention Required)|Checking your browser|cf-chl-|__cf_chl/i.test(html.slice(0, 20_000));
}

function assertNotChallenge(html: string): void {
  if (looksLikeChallenge(html)) {
    throw new MobileError('源站要求浏览器验证，应用不会尝试绕过。', 'UPSTREAM_CHALLENGE', 502);
  }
}

function documentTitle(document: Document): string {
  return cleanInline(document.querySelector('title')?.textContent || '');
}

function findFieldValue(document: Document, labels: string[]): string {
  const cells = Array.from(document.querySelectorAll('td'));
  for (const label of labels) {
    const cell = cells.find((item) => cleanInline(item.textContent).startsWith(label));
    if (cell) return cleanInline(cell.textContent?.replace(new RegExp(`^.*?${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`), '') || '');
  }
  return '';
}

function hotTextValue(document: Document, label: string): string {
  const nodes = Array.from(document.querySelectorAll('.hottext'));
  const exact = nodes.find((item) => cleanInline(item.textContent) === label);
  if (exact) {
    const sibling = Array.from(exact.parentElement?.children || []).find((item) => item !== exact && cleanInline(item.textContent));
    if (sibling) return cleanText(sibling.textContent);
  }
  const value = nodes.find((item) => cleanInline(item.textContent).includes(label));
  return value ? cleanText(value.textContent) : '';
}

function parseBookHtml(html: string, bookUrl: string, requestedDirectoryUrl: string | null = null): Book {
  assertNotChallenge(html);
  const document = parseHtml(html);
  const content = document.querySelector('#content');
  if (!content) throw new MobileError('未能识别书籍页面，源站结构可能已变化。', 'BOOK_PARSE_FAILED', 502);
  const titleNode = content.querySelector('td[colspan="5"] b');
  const title = cleanInline(titleNode?.textContent) || documentTitle(document).split(/\s+-+\s+/)[0] || '未命名轻小说';
  const author = findFieldValue(document, ['小说作者：', '小说作者:']) || '未知作者';
  const status = findFieldValue(document, ['文章状态：', '文章状态:']) || '未知';
  const updatedAt = findFieldValue(document, ['最后更新：', '最后更新:']);
  const category = cleanInline(content.querySelector('a[href*="articlelist.php"]')?.textContent) || '轻小说';
  const tags = hotTextValue(document, '作品Tags').split(/\s+/).filter(Boolean).slice(0, 30);
  const summaryNode = Array.from(document.querySelectorAll('.hottext')).find((item) => cleanInline(item.textContent).includes('内容简介'));
  const directoryNode = content.querySelector('a[href*="/novel/"][href*="index.htm" i]');
  const directoryUrl = requestedDirectoryUrl || (directoryNode ? new URL(directoryNode.getAttribute('href') || '', bookUrl).toString() : null);
  const coverNode = content.querySelector('img[src*="/image/"]');
  const coverUrl = coverNode ? resolveUrl(coverNode.getAttribute('src'), bookUrl) : null;
  return {
    id: sourceIds(new URL(directoryUrl || bookUrl)).bookId || null,
    title,
    author,
    category,
    status,
    updatedAt,
    tags,
    summary: summaryNode ? cleanText(summaryNode.textContent).replace(/^内容简介\s*[：:]\s*/, '') : '',
    coverUrl,
    sourceUrl: bookUrl,
    bookUrl,
    directoryUrl,
  };
}

function parseIndexHtml(html: string, finalUrl: string, bookId: string | null = null): BookIndex {
  assertNotChallenge(html);
  const url = assertWenkuUrl(finalUrl);
  const document = parseHtml(html);
  const chapters: Chapter[] = [];
  const seen = new Set<string>();
  let currentVolume = '正文';
  for (const row of Array.from(document.querySelectorAll('table tr'))) {
    const volumeNode = row.querySelector('td.vcss');
    if (volumeNode) {
      const volume = cleanInline(volumeNode.textContent);
      if (volume) currentVolume = volume;
      continue;
    }
    for (const anchor of Array.from(row.querySelectorAll('td.ccss a[href]'))) {
      const title = cleanInline(anchor.textContent);
      if (!title || /^(上一页|下一页|返回目录)$/.test(title)) continue;
      const chapterUrl = resolveUrl(anchor.getAttribute('href'), finalUrl);
      if (!chapterUrl) continue;
      try {
        assertWenkuUrl(chapterUrl);
      } catch {
        continue;
      }
      if (seen.has(chapterUrl)) continue;
      seen.add(chapterUrl);
      const chapter = new URL(chapterUrl);
      chapters.push({
        id: chapter.pathname.match(/\/(\d+)\.html?$/i)?.[1] || String(chapters.length + 1),
        title,
        url: chapterUrl,
        volume: currentVolume,
        order: chapters.length + 1,
        isIllustration: /插图|插畫|彩页|彩頁/.test(title),
      });
    }
  }
  if (!chapters.length) throw new MobileError('目录中没有识别到章节。', 'NO_CHAPTERS', 502);
  return {
    title: documentTitle(document).split(/\s+-+\s+/)[0] || '未命名轻小说',
    url: finalUrl,
    bookId: bookId || sourceIds(url).bookId,
    chapters,
  };
}

function looksLikeSpacerImage(image: Element): boolean {
  const source = image.getAttribute('src') || '';
  const width = Number(image.getAttribute('width') || 0);
  const height = Number(image.getAttribute('height') || 0);
  return (width > 0 && width <= 3) || (height > 0 && height <= 3) || /spacer|blank\.(gif|png)|pixel/i.test(source);
}

function imageSource(image: Element, baseUrl: string): string | null {
  for (const name of ['data-original', 'data-src', 'data-lazy-src', 'src']) {
    const value = resolveUrl(image.getAttribute(name), baseUrl);
    if (value) return value;
  }
  return null;
}

function normalizeBlocks(text: string, imageCount: number): ContentBlock[] {
  const blocks: ContentBlock[] = [];
  let paragraph: string[] = [];
  const flush = () => {
    const value = paragraph.join(' ').replace(/\s+/g, ' ').trim();
    if (value) blocks.push({ type: 'text', value });
    paragraph = [];
  };
  for (const line of String(text).replace(/\r/g, '').replace(/\u00a0/g, ' ').split('\n')) {
    const token = line.trim().match(/^@@WENKU8_IMAGE_(\d+)@@$/);
    if (token) {
      flush();
      const index = Number(token[1]);
      if (index < imageCount) blocks.push({ type: 'image', index });
      continue;
    }
    if (!line.trim()) {
      flush();
      continue;
    }
    paragraph.push(line.trim());
  }
  flush();
  return blocks;
}

function parseChapterHtml(html: string, chapter: Chapter, pageUrl: string): ParsedChapter {
  assertWenkuUrl(chapter.url);
  assertNotChallenge(html);
  const document = parseHtml(html);
  const source = document.querySelector('#content');
  if (!source) throw new MobileError(`未能识别章节正文：${chapter.title}`, 'CHAPTER_PARSE_FAILED', 502);
  const root = source.cloneNode(true) as Element;
  root.querySelectorAll('#contentdp,script,style,iframe,object,embed,form,input,button,noscript,link,meta').forEach((node) => node.remove());
  root.querySelectorAll('[id^="adv"],[class*="advert"],[class*="banner"]').forEach((node) => node.remove());
  const imageUrls: string[] = [];
  for (const image of Array.from(root.querySelectorAll('img'))) {
    const sourceUrl = imageSource(image, pageUrl);
    if (!sourceUrl || looksLikeSpacerImage(image)) {
      image.remove();
      continue;
    }
    const imageIndex = imageUrls.length;
    imageUrls.push(sourceUrl);
    image.replaceWith(document.createTextNode(`\n@@WENKU8_IMAGE_${imageIndex}@@\n`));
  }
  root.querySelectorAll('br').forEach((node) => node.replaceWith('\n'));
  root.querySelectorAll('p').forEach((node) => node.append('\n'));
  const title = cleanInline(chapter.title) || documentTitle(document).split(/\s+-+\s+/).pop() || '未命名章节';
  const blocks = normalizeBlocks(root.textContent || '', imageUrls.length);
  const plainText = blocks.filter((block) => block.type === 'text').map((block) => block.value).join('');
  if (plainText.length < 10 && !imageUrls.length) {
    throw new MobileError(`章节正文为空：${title}`, 'EMPTY_CHAPTER', 502);
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

function validateBook(raw: Book): Book {
  if (!raw || typeof raw !== 'object') throw new MobileError('缺少书籍信息。', 'BOOK_REQUIRED');
  const title = cleanInline(raw.title);
  if (!title) throw new MobileError('书籍标题不能为空。', 'BOOK_TITLE_REQUIRED');
  const sourceUrl = assertWenkuUrl(raw.sourceUrl || raw.bookUrl).toString();
  return {
    ...raw,
    id: raw.id ? String(raw.id).replace(/\D/g, '') || null : null,
    title: title.slice(0, 200),
    author: cleanInline(raw.author || '未知作者').slice(0, 200),
    category: cleanInline(raw.category || '轻小说').slice(0, 100),
    status: cleanInline(raw.status || '').slice(0, 100),
    updatedAt: cleanInline(raw.updatedAt || '').slice(0, 100),
    tags: Array.isArray(raw.tags) ? raw.tags.map(cleanInline).filter(Boolean).slice(0, 30) : [],
    summary: cleanInline(raw.summary || '').slice(0, 12_000),
    coverUrl: raw.coverUrl ? resolveUrl(raw.coverUrl, sourceUrl) : null,
    sourceUrl,
    bookUrl: raw.bookUrl ? assertWenkuUrl(raw.bookUrl).toString() : sourceUrl,
    directoryUrl: raw.directoryUrl ? assertWenkuUrl(raw.directoryUrl).toString() : null,
  };
}

function validateChapters(raw: Chapter[]): Chapter[] {
  if (!Array.isArray(raw) || !raw.length) throw new MobileError('请至少选择一个章节。', 'NO_CHAPTER_SELECTED');
  if (raw.length > MAX_CHAPTERS) throw new MobileError(`单次最多支持 ${MAX_CHAPTERS} 个章节。`, 'TOO_MANY_CHAPTERS');
  const seen = new Set<string>();
  return raw.map((item, index) => {
    if (!item || typeof item !== 'object') throw new MobileError('章节数据无效。', 'INVALID_CHAPTER');
    const url = assertWenkuUrl(item.url).toString();
    if (seen.has(url)) throw new MobileError(`章节重复：${cleanInline(item.title) || index + 1}`, 'DUPLICATE_CHAPTER');
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

function xmlEscape(value: unknown): string {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

function safeName(value: string, fallback = '轻小说'): string {
  const cleaned = String(value || '')
    .normalize('NFKC')
    .replace(/[<>:"/\\|?*\u0000-\u001f]/g, '_')
    .replace(/\s+/g, ' ')
    .replace(/[. ]+$/g, '')
    .trim();
  return cleaned && !/^(con|prn|aux|nul|com[1-9]|lpt[1-9])$/i.test(cleaned) ? cleaned.slice(0, 90) : fallback;
}

function itemId(value: string, index: number): string {
  const cleaned = value.toLowerCase().replace(/[^a-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 50);
  return `${cleaned || 'item'}-${index + 1}`;
}

const EPUB_CSS = `@charset "utf-8";
html { font-family: "MiSans", "Noto Sans CJK SC", sans-serif; }
body { margin: 0; color: #272522; line-height: 1.85; }
section { padding: 1.2em 1.35em; }
h1 { font-size: 1.55em; line-height: 1.4; text-align: center; }
h2 { font-size: 1.15em; }
p { font-size: 1em; text-indent: 2em; text-align: justify; }
.title-page { min-height: 85vh; display: flex; flex-direction: column; justify-content: center; align-items: center; text-align: center; page-break-after: always; }
.title-page h1 { font-size: 1.8em; }
.title-page .author { font-size: 1.1em; color: #655f56; }
.title-page .summary { width: 100%; text-align: left; margin-top: 2.5em; page-break-before: always; }
.title-page .generated { margin-top: 3em; color: #948c81; font-size: .72em; }
figure { margin: 1.4em auto; text-align: center; page-break-inside: avoid; }
figure img { max-width: 100%; max-height: 90vh; height: auto; object-fit: contain; }
`;

function chapterXhtml(chapter: ParsedChapter, blocks: Array<ContentBlock & { fileName?: string; number?: number; ext?: string }>): string {
  const content = blocks.map((block) => {
    if (block.type === 'text') return `      <p>${xmlEscape(block.value).replace(/\n/g, '<br/>')}</p>`;
    const fileName = block.fileName || `image-${String(block.index + 1).padStart(4, '0')}.${block.ext || 'jpg'}`;
    const number = block.number !== undefined ? block.number + 1 : block.index + 1;
    return [
      '      <figure>',
      `        <img src="../images/${xmlEscape(fileName)}" alt="插图 ${number}"/>`,
      '      </figure>',
    ].join('\n');
  }).join('\n');
  return `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-CN" lang="zh-CN">
  <head><meta charset="utf-8"/><title>${xmlEscape(chapter.title)}</title><link rel="stylesheet" href="style.css"/></head>
  <body><section epub:type="chapter"><h1>${xmlEscape(chapter.title)}</h1>
${content}
  </section></body>
</html>`;
}

function titleXhtml(book: Book, cover: DownloadedImage | null): string {
  return `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-CN" lang="zh-CN">
  <head><meta charset="utf-8"/><title>${xmlEscape(book.title)}</title><link rel="stylesheet" href="style.css"/></head>
  <body><section class="title-page">
    ${cover ? `<img class="cover" src="../images/${xmlEscape(cover.fileName)}" alt="${xmlEscape(book.title)} 封面"/>` : ''}
    <h1>${xmlEscape(book.title)}</h1>
    ${book.author ? `<p class="author">${xmlEscape(book.author)}</p>` : ''}
    ${book.summary ? `<section class="summary"><h2>内容简介</h2>${book.summary.split(/\n+/).filter(Boolean).map((line) => `<p>${xmlEscape(line)}</p>`).join('\n')}</section>` : ''}
    <p class="generated">由 Wenku8 EPUB Studio for Android 于 ${xmlEscape(new Date().toLocaleDateString('zh-CN'))} 整理</p>
  </section></body>
</html>`;
}

function navXhtml(chapters: Array<ParsedChapter & { fileName: string }>): string {
  return `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-CN" lang="zh-CN">
<head><meta charset="utf-8"/><title>目录</title><link rel="stylesheet" href="text/style.css"/></head>
<body><nav epub:type="toc" id="toc" role="doc-toc"><h1>目录</h1><ol>
<li><a href="text/title.xhtml">书籍信息</a></li>
${chapters.map((chapter) => `<li><a href="text/${xmlEscape(chapter.fileName)}">${xmlEscape(chapter.title)}</a></li>`).join('\n')}
</ol></nav></body></html>`;
}

function ncxXhtml(book: Book, chapters: Array<ParsedChapter & { fileName: string }>): string {
  return `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<head><meta name="dtb:uid" content="urn:uuid:${xmlEscape(publicId())}"/></head>
<docTitle><text>${xmlEscape(book.title)}</text></docTitle>
<navMap>
<navPoint id="title-page" playOrder="1"><navLabel><text>书籍信息</text></navLabel><content src="text/title.xhtml"/></navPoint>
${chapters.map((chapter, index) => `<navPoint id="nav-${index + 1}" playOrder="${index + 2}"><navLabel><text>${xmlEscape(chapter.title)}</text></navLabel><content src="text/${xmlEscape(chapter.fileName)}"/></navPoint>`).join('\n')}
</navMap></ncx>`;
}

function packageXml(book: Book, manifest: Array<{ id: string; href: string; mediaType: string; properties?: string }>, spine: string[]): string {
  const keywords = book.tags.filter(Boolean).join('、');
  return `<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id" xml:lang="zh-CN">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="book-id">urn:uuid:${publicId()}</dc:identifier>
<dc:title>${xmlEscape(book.title)}</dc:title>
<dc:creator>${xmlEscape(book.author || '未知作者')}</dc:creator>
<dc:language>zh-CN</dc:language>
<dc:description>${xmlEscape(book.summary || '')}</dc:description>
${keywords ? `<dc:subject>${xmlEscape(keywords)}</dc:subject>` : ''}
<dc:source>${xmlEscape(book.sourceUrl || book.bookUrl)}</dc:source>
<meta property="dcterms:modified">${new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')}</meta>
</metadata>
<manifest>
${manifest.map((entry) => `<item id="${xmlEscape(entry.id)}" href="${xmlEscape(entry.href)}" media-type="${xmlEscape(entry.mediaType)}"${entry.properties ? ` properties="${xmlEscape(entry.properties)}"` : ''}/>`).join('\n')}
</manifest>
<spine toc="ncx">${spine.map((id) => `<itemref idref="${xmlEscape(id)}" linear="yes"/>`).join('\n')}</spine>
</package>`;
}

function attachImages(chapters: ParsedChapter[], images: DownloadedImage[]): Array<ParsedChapter & { fileName: string; blocks: Array<ContentBlock & { fileName?: string; number?: number; ext?: string }> }> {
  return chapters.map((chapter, chapterIndex) => {
    const chapterImages = images.filter((image) => image.sourceId === chapter.id);
    const blocks = chapter.blocks.map((block) => {
      if (block.type !== 'image') return block;
      const image = chapterImages.find((item) => item.chapterIndex === block.index);
      if (!image) return null;
      return { type: 'image' as const, fileName: image.fileName, number: image.globalIndex, ext: image.ext };
    }).filter(Boolean) as Array<ContentBlock & { fileName?: string; number?: number; ext?: string }>;
    return { ...chapter, fileName: `chapter-${String(chapterIndex + 1).padStart(4, '0')}.xhtml`, blocks };
  });
}

function base64ToBytes(value: string): Uint8Array {
  const binary = atob(value.replace(/\s/g, ''));
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

function concatBytes(chunks: Uint8Array[]): Uint8Array {
  const total = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
  const result = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.length;
  }
  return result;
}

async function readLocalFile(path: string): Promise<Uint8Array> {
  const result = await Filesystem.readFile({ path });
  if (typeof result.data === 'string') return base64ToBytes(result.data);
  return new Uint8Array(await (result.data as Blob).arrayBuffer());
}

async function buildEpubBytes(book: Book, parsedChapters: ParsedChapter[], images: DownloadedImage[], cover: DownloadedImage | null): Promise<Uint8Array> {
  const chapters = attachImages(parsedChapters, images);
  const manifest = [
    { id: 'nav', href: 'nav.xhtml', mediaType: 'application/xhtml+xml', properties: 'nav' },
    { id: 'ncx', href: 'toc.ncx', mediaType: 'application/x-dtbncx+xml' },
    { id: 'style', href: 'text/style.css', mediaType: 'text/css' },
    { id: 'title-page', href: 'text/title.xhtml', mediaType: 'application/xhtml+xml' },
  ];
  const spine = ['title-page'];
  chapters.forEach((chapter, index) => {
    const id = itemId('chapter', index);
    manifest.push({ id, href: `text/${chapter.fileName}`, mediaType: 'application/xhtml+xml' });
    spine.push(id);
  });
  for (const image of [...(cover ? [cover] : []), ...images]) {
    manifest.push({ id: image.manifestId, href: `images/${image.fileName}`, mediaType: image.mime, properties: image.isCover ? 'cover-image' : undefined });
  }

  const zip = new Zip() as any;
  const chunks: Uint8Array[] = [];
  let resolveDone: (value: Uint8Array) => void = () => {};
  let rejectDone: (reason?: unknown) => void = () => {};
  const done = new Promise<Uint8Array>((resolve, reject) => {
    resolveDone = resolve;
    rejectDone = reject;
  });
  zip.on('data', (chunk: Uint8Array) => chunks.push(chunk));
  zip.on('end', () => resolveDone(concatBytes(chunks)));
  zip.on('error', rejectDone);
  zip.add(strToU8('application/epub+zip'), new ZipPassThrough('mimetype'));
  zip.add(strToU8('<?xml version="1.0" encoding="UTF-8"?>\n<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>'), new ZipDeflate('META-INF/container.xml'));
  zip.add(strToU8(packageXml(book, manifest, spine)), new ZipDeflate('EPUB/package.opf'));
  zip.add(strToU8(navXhtml(chapters)), new ZipDeflate('EPUB/nav.xhtml'));
  zip.add(strToU8(ncxXhtml(book, chapters)), new ZipDeflate('EPUB/toc.ncx'));
  zip.add(strToU8(EPUB_CSS), new ZipDeflate('EPUB/text/style.css'));
  zip.add(strToU8(titleXhtml(book, cover)), new ZipDeflate('EPUB/text/title.xhtml'));
  for (const chapter of chapters) {
    zip.add(strToU8(chapterXhtml(chapter, chapter.blocks)), new ZipDeflate(`EPUB/text/${chapter.fileName}`));
  }
  for (const image of [...(cover ? [cover] : []), ...images]) {
    zip.add(await readLocalFile(image.localPath), new ZipDeflate(`EPUB/images/${image.fileName}`));
  }
  zip.end();
  return done;
}

function normalizeStoredProgress(job: Partial<StoredJob>): JobProgress {
  const progress = job.progress && typeof job.progress === 'object' ? job.progress : ({} as Partial<JobProgress>);
  return {
    phase: (progress.phase || 'queued') as JobPhase,
    percent: clampPercent(progress.percent),
    completed: count(progress.completed),
    total: count(progress.total, count(job.chapterCount)),
    imageCompleted: count(progress.imageCompleted, count(job.imageCount)),
    message: progress.message || '',
    currentTitle: progress.currentTitle || '',
  };
}

class MobileJobManager {
  private jobs = new Map<string, StoredJob>();
  private tasks = new Map<string, TaskEntry>();
  private controllers = new Map<string, AbortController>();
  private listeners = new Set<(job: PublicJob) => void>();
  private queue: string[] = [];
  private activeId: string | null = null;
  private initialized = false;

  async init(): Promise<void> {
    if (this.initialized) return;
    await Filesystem.mkdir({ path: 'jobs', directory: Directory.Data, recursive: true });
    await Filesystem.mkdir({ path: 'wenku8', directory: Directory.Cache, recursive: true });
    const listing = await Filesystem.readdir({ path: 'jobs', directory: Directory.Data });
    for (const entry of listing.files) {
      if (!entry.name.endsWith('.json')) continue;
      try {
        const path = `jobs/${entry.name}`;
        const result = await Filesystem.readFile({ path, directory: Directory.Data, encoding: Encoding.UTF8 });
        const job = JSON.parse(result.data as string) as StoredJob;
        job.progress = normalizeStoredProgress(job);
        if (ACTIVE_STATES.has(job.status)) {
          job.status = 'failed';
          job.error = { code: 'APP_INTERRUPTED', message: '上次任务因应用被系统终止而中断，请重新生成。' };
          job.progress.phase = 'failed';
          job.progress.message = '任务已中断';
          job.finishedAt = new Date().toISOString();
          await this.persist(job);
        }
        this.jobs.set(job.id, job);
      } catch {
        // Ignore damaged task records; the rest of the app remains usable.
      }
    }
    this.trimHistory();
    this.initialized = true;
  }

  subscribe(listener: (job: PublicJob) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private emit(job: StoredJob): void {
    const value = this.publicJob(job);
    for (const listener of this.listeners) listener(value);
  }

  private publicJob(job: StoredJob): PublicJob {
    return {
      id: job.id,
      status: job.status,
      book: { id: job.book.id, title: job.book.title, author: job.book.author },
      chapterCount: job.chapterCount,
      progress: normalizeStoredProgress(job),
      createdAt: job.createdAt,
      updatedAt: job.updatedAt,
      finishedAt: job.finishedAt || null,
      error: job.error,
      warnings: Array.isArray(job.warnings) ? job.warnings : [],
      file: job.status === 'completed' && job.outputUri
        ? { name: `${safeName(job.book.title, '轻小说')}-${job.id}.epub`, size: count(job.outputSize), uri: job.outputUri, downloadUrl: '' }
        : null,
    };
  }

  private async persist(job: StoredJob): Promise<void> {
    job.updatedAt = new Date().toISOString();
    const target = `jobs/${job.id}.json`;
    try {
      await Filesystem.writeFile({ path: target, data: JSON.stringify(job, null, 2), directory: Directory.Data, encoding: Encoding.UTF8 });
    } catch {
      throw new MobileError('任务记录保存失败。', 'PERSIST_FAILED', 500);
    }
  }

  private async update(job: StoredJob, patch: Partial<StoredJob>, progress?: Partial<JobProgress>): Promise<void> {
    Object.assign(job, patch);
    if (progress) job.progress = { ...job.progress, ...progress };
    job.updatedAt = new Date().toISOString();
    this.emit(job);
    if (ACTIVE_STATES.has(job.status)) {
      void NativeTaskGuard.update({ jobId: job.id, message: job.progress.message, percent: job.progress.percent }).catch(() => undefined);
    }
    await this.persist(job);
  }

  async create(payload: { book: Book; chapters: Chapter[]; options?: { includeCover?: boolean } }): Promise<PublicJob> {
    await this.init();
    const book = validateBook(payload.book);
    const chapters = validateChapters(payload.chapters);
    const now = new Date().toISOString();
    const job: StoredJob = {
      id: publicId(),
      status: 'queued',
      book,
      chapterCount: chapters.length,
      options: { includeCover: payload.options?.includeCover !== false },
      progress: { phase: 'queued', percent: 0, completed: 0, total: chapters.length, imageCompleted: 0, message: '等待开始…', currentTitle: '' },
      createdAt: now,
      updatedAt: now,
      finishedAt: null,
      error: null,
      warnings: [],
      outputUri: null,
      outputSize: null,
      imageCount: 0,
    };
    this.jobs.set(job.id, job);
    this.tasks.set(job.id, { job, chapters });
    await this.persist(job);
    this.queue.push(job.id);
    void NativeTaskGuard.requestNotificationPermission().catch(() => undefined);
    this.drain();
    return this.publicJob(job);
  }

  get(id: string): PublicJob {
    const job = this.jobs.get(id);
    if (!job) throw new MobileError('任务不存在。', 'JOB_NOT_FOUND', 404);
    return this.publicJob(job);
  }

  list(): PublicJob[] {
    return [...this.jobs.values()]
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .slice(0, JOB_RETENTION)
      .map((job) => this.publicJob(job));
  }

  async cancel(id: string): Promise<PublicJob> {
    await this.init();
    const job = this.jobs.get(id);
    if (!job) throw new MobileError('任务不存在。', 'JOB_NOT_FOUND', 404);
    if (!ACTIVE_STATES.has(job.status)) return this.publicJob(job);
    this.controllers.get(id)?.abort(new DOMException('用户取消了任务', 'AbortError'));
    await NativeHttp.cancelJob({ jobId: id }).catch(() => undefined);
    if (job.status === 'queued') {
      this.queue = this.queue.filter((value) => value !== id);
      await this.update(job, {
        status: 'canceled',
        error: { code: 'CANCELED', message: '任务已取消。' },
        finishedAt: new Date().toISOString(),
      }, { phase: 'canceled', message: '任务已取消', currentTitle: '' });
      await NativeTaskGuard.stop({ jobId: id }).catch(() => undefined);
      return this.publicJob(job);
    }
    await this.update(job, {}, { message: '正在取消…' });
    return this.publicJob(job);
  }

  private drain(): void {
    if (this.activeId || !this.queue.length) return;
    const id = this.queue.shift();
    if (!id) return;
    const task = this.tasks.get(id);
    if (!task || !ACTIVE_STATES.has(task.job.status)) {
      this.drain();
      return;
    }
    this.activeId = id;
    void this.run(task).finally(() => {
      this.activeId = null;
      this.drain();
    });
  }

  private async run(task: TaskEntry): Promise<void> {
    const { job, chapters } = task;
    const controller = new AbortController();
    this.controllers.set(job.id, controller);
    const parsedChapters: ParsedChapter[] = [];
    const images: DownloadedImage[] = [];
    const warnings: string[] = [];
    let globalImageIndex = 0;
    let completed = 0;
    try {
      await NativeTaskGuard.start({ jobId: job.id, title: job.book.title, message: '开始处理任务' });
      await this.update(job, { status: 'running' }, { phase: 'fetching', percent: 1, message: '开始获取章节…' });
      for (const chapter of chapters) {
        this.throwIfAborted(controller.signal);
        const baseline = completed / chapters.length;
        await this.update(job, {}, { phase: 'fetching', percent: percentage(completed, chapters.length), completed, currentTitle: chapter.title, message: `正在下载：${chapter.title}` });
        try {
          const page = await NativeHttp.fetchText({ url: chapter.url, jobId: job.id, accept: 'text/html,application/xhtml+xml' });
          const parsed = parseChapterHtml(page.html, chapter, page.finalUrl);
          parsedChapters.push(parsed);
          for (let index = 0; index < parsed.imageUrls.length; index += 1) {
            this.throwIfAborted(controller.signal);
            const imageProgress = index / Math.max(1, parsed.imageUrls.length);
            await this.update(job, {}, {
              phase: 'images',
              percent: Math.min(92, Math.round(baseline * 92 + imageProgress * 92 / chapters.length)),
              imageCompleted: images.length,
              message: `正在下载插图 ${index + 1}/${parsed.imageUrls.length}`,
            });
            try {
              const result = await NativeHttp.downloadFile({ url: parsed.imageUrls[index], referer: parsed.sourceUrl, jobId: job.id, fileName: `chapter-${globalImageIndex + index + 1}` });
              images.push({
                chapterId: parsed.id,
                sourceId: parsed.id,
                chapterIndex: index,
                globalIndex: globalImageIndex + index,
                fileName: `image-${String(globalImageIndex + index + 1).padStart(4, '0')}.${result.ext}`,
                manifestId: `image-${globalImageIndex + index + 1}`,
                mime: result.mime,
                localPath: result.path,
                localUri: result.uri,
                ext: result.ext,
                bytes: result.bytes,
                isCover: false,
              });
            } catch (error) {
              this.throwIfAborted(controller.signal);
              warnings.push(`插图 ${index + 1}/${parsed.imageUrls.length}：${toError(error).message}`);
            }
          }
          globalImageIndex += parsed.imageUrls.length;
        } catch (error) {
          this.throwIfAborted(controller.signal);
          warnings.push(`${chapter.title || `第 ${chapter.order} 章`}：${toError(error).message}`);
        }
        completed += 1;
        await this.update(job, { warnings: [...warnings] }, {
          phase: 'fetching',
          percent: Math.min(94, percentage(completed, chapters.length) * 0.94),
          completed,
          message: `已处理 ${completed}/${chapters.length} 个章节`,
        });
      }
      if (!parsedChapters.length) throw new MobileError('所有章节都未能成功读取。', 'NO_CHAPTERS_PARSED', 502);
      let cover: DownloadedImage | null = null;
      if (job.options.includeCover && job.book.coverUrl) {
        await this.update(job, {}, { phase: 'cover', percent: 94, message: '正在下载书籍封面…' });
        try {
          const result = await NativeHttp.downloadFile({ url: job.book.coverUrl, referer: job.book.bookUrl, jobId: job.id, fileName: 'cover' });
          cover = {
            chapterId: '', sourceId: '', chapterIndex: 0, globalIndex: 0,
            fileName: `cover.${result.ext}`, manifestId: 'cover-image', mime: result.mime,
            localPath: result.path, localUri: result.uri, ext: result.ext, bytes: result.bytes, isCover: true,
          };
        } catch (error) {
          warnings.push(`封面：${toError(error).message}`);
        }
      }
      await this.update(job, {}, { phase: 'packaging', percent: 97, message: '正在写入 EPUB 容器…' });
      const epubBytes = await buildEpubBytes(job.book, parsedChapters, images, cover);
      const fileName = `${safeName(job.book.title, '轻小说')}-${job.id}.epub`;
      const relativeOutput = `wenku8/${fileName}`;
      const written = await Filesystem.writeFile({ path: relativeOutput, data: new Blob([epubBytes.buffer as ArrayBuffer]), directory: Directory.Cache });
      const saved = await NativeEpubFile.save({ jobId: job.id, sourceUri: written.uri, fileName });
      await this.update(job, {
        status: 'completed',
        finishedAt: new Date().toISOString(),
        warnings: [...warnings],
        outputUri: written.uri,
        outputSize: epubBytes.length,
        imageCount: images.length + (cover ? 1 : 0),
      }, { phase: 'completed', percent: 100, completed, imageCompleted: images.length, currentTitle: '', message: 'EPUB 已生成' });
      await NativeTaskGuard.stop({ jobId: job.id }).catch(() => undefined);
      await this.cleanTaskCache(job.id).catch(() => undefined);
      void saved;
    } catch (error) {
      this.throwIfAborted(controller.signal, false);
      const normalized = toError(error);
      const canceled = normalized.code === 'CANCELED' || controller.signal.aborted;
      await this.update(job, {
        status: canceled ? 'canceled' : 'failed',
        finishedAt: new Date().toISOString(),
        error: canceled ? { code: 'CANCELED', message: '任务已取消。' } : { code: normalized.code, message: normalized.message },
        warnings: [...warnings],
      }, { ...job.progress, phase: canceled ? 'canceled' : 'failed', message: canceled ? '任务已取消' : normalized.message });
      await NativeTaskGuard.stop({ jobId: job.id }).catch(() => undefined);
      await this.cleanTaskCache(job.id).catch(() => undefined);
    } finally {
      this.controllers.delete(job.id);
      this.tasks.delete(job.id);
    }
  }

  private throwIfAborted(signal: AbortSignal, throwError = true): void {
    if (!signal.aborted) return;
    if (throwError) throw signal.reason || new DOMException('任务已取消', 'AbortError');
  }

  private async cleanTaskCache(jobId: string): Promise<void> {
    const safe = jobId.replace(/[^a-z0-9-]/gi, '');
    await Filesystem.rmdir({ path: `wenku8/${safe}`, directory: Directory.Cache, recursive: true }).catch(() => undefined);
    await Filesystem.deleteFile({ path: `wenku8/${safe}`, directory: Directory.Cache }).catch(() => undefined);
  }

  private trimHistory(): void {
    const jobs = [...this.jobs.values()].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    for (const job of jobs.slice(JOB_RETENTION)) this.jobs.delete(job.id);
  }

  async saveFile(id: string): Promise<{ uri: string; bytes: number; location: string }> {
    const job = this.jobs.get(id);
    if (!job || job.status !== 'completed' || !job.outputUri) throw new MobileError('EPUB 尚未生成完成。', 'EPUB_NOT_READY', 409);
    return NativeEpubFile.save({ jobId: id, sourceUri: job.outputUri, fileName: `${safeName(job.book.title, '轻小说')}-${id}.epub` });
  }

  async shareFile(id: string): Promise<void> {
    const job = this.jobs.get(id);
    if (!job || job.status !== 'completed' || !job.outputUri) throw new MobileError('EPUB 尚未生成完成。', 'EPUB_NOT_READY', 409);
    await NativeEpubFile.share({ jobId: id, sourceUri: job.outputUri, fileName: `${safeName(job.book.title, '轻小说')}-${id}.epub`, mimeType: 'application/epub+zip' });
  }
}

const manager = new MobileJobManager();

async function parseBook(input: string): Promise<{ book: Book }> {
  const requested = normalizeSourceUrl(input);
  const ids = sourceIds(requested);
  const bookUrl = ids.kind === 'index' ? `${DEFAULT_BOOK_URL}${ids.bookId}.htm` : requested.toString();
  const result = await NativeHttp.fetchText({ url: bookUrl, jobId: `parse-${publicId()}`, accept: 'text/html,application/xhtml+xml' });
  const directoryUrl = ids.kind === 'index' ? requested.toString() : null;
  return { book: parseBookHtml(result.html, result.finalUrl || bookUrl, directoryUrl) };
}

async function parseIndex(input: string): Promise<{ index: BookIndex }> {
  let url = normalizeSourceUrl(input);
  let ids = sourceIds(url);
  if (ids.kind !== 'index') {
    const { book } = await parseBook(input);
    if (!book.directoryUrl) throw new MobileError('书籍页没有提供目录链接。', 'INDEX_URL_REQUIRED');
    url = assertWenkuUrl(book.directoryUrl);
    ids = sourceIds(url);
  }
  const result = await NativeHttp.fetchText({ url: url.toString(), jobId: `parse-${publicId()}`, accept: 'text/html,application/xhtml+xml' });
  return { index: parseIndexHtml(result.html, result.finalUrl || url.toString(), ids.bookId) };
}

export const Wenku8Core = {
  native: true,
  init: () => manager.init(),
  parseBook,
  parseIndex,
  createJob: (payload: { book: Book; chapters: Chapter[]; options?: { includeCover?: boolean } }) => manager.create(payload),
  getJob: async (id: string) => manager.get(id),
  listJobs: () => manager.list(),
  cancelJob: (id: string) => manager.cancel(id),
  subscribeJob: (listener: (job: PublicJob) => void) => manager.subscribe(listener),
  saveFile: (id: string) => manager.saveFile(id),
  shareFile: (id: string) => manager.shareFile(id),
};

if (typeof window !== 'undefined') {
  (window as Window & { Wenku8Core?: typeof Wenku8Core }).Wenku8Core = Wenku8Core;
}

export type { DownloadedImage, StoredJob };
export { parseBookHtml, parseChapterHtml, parseIndexHtml, validateBook, validateChapters };
export default Wenku8Core;

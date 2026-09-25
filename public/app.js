'use strict';

const state = {
  book: null,
  index: null,
  selected: new Set(),
  currentJobId: null,
  pollTimer: null,
  progressSource: null,
  progressUnsubscribe: null,
  progressGeneration: 0,
  currentJobFile: null,
  downloadController: null,
};

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const mobileCore = () => window.Wenku8Core?.native ? window.Wenku8Core : null;
const panels = $$('.step-panel');
const sourceForm = $('#source-form');
const sourceInput = $('#source-url');
const sourceMessage = $('#source-message');
const chapterList = $('#chapter-list');
const chapterSearch = $('#chapter-search');
const chapterMessage = $('#chapter-message');
const toExportButton = $('#to-export-button');
const exportButton = $('#export-button');
const exportMessage = $('#export-message');
const jobSection = $('#job-section');
const currentJobStatus = $('#job-status');
const historyList = $('#history-list');
const downloadButton = $('#download-button');
const shareButton = $('#share-button');

const STATUS_LABELS = {
  queued: '等待中',
  running: '处理中',
  completed: '已完成',
  failed: '失败',
  canceled: '已取消',
};

function showPanel(number) {
  for (const panel of panels) panel.hidden = Number(panel.dataset.step) !== number;
}

function setMessage(element, text, type = '') {
  element.textContent = text;
  element.classList.toggle('error', type === 'error');
  element.classList.toggle('success', type === 'success');
}

function setBusy(button, busy, label) {
  button.disabled = busy;
  if (busy) {
    button.dataset.previousHtml = button.innerHTML;
    button.innerHTML = `<span class="loading-dots">${label || '处理中'}</span>`;
  } else if (button.dataset.previousHtml) {
    button.innerHTML = button.dataset.previousHtml;
    delete button.dataset.previousHtml;
  }
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: {
      ...(options.body ? { 'content-type': 'application/json' } : {}),
      ...(options.headers || {}),
    },
  });
  let payload = null;
  try {
    payload = await response.json();
  } catch {
    payload = null;
  }
  if (!response.ok) {
    const message = payload?.error?.message || `请求失败（HTTP ${response.status}）。`;
    const error = new Error(message);
    error.code = payload?.error?.code;
    error.status = response.status;
    throw error;
  }
  return payload;
}

function toast(message, type = '') {
  const node = document.createElement('div');
  node.className = `toast ${type}`.trim();
  node.textContent = message;
  $('#toast-region').append(node);
  setTimeout(() => node.remove(), 4200);
}

function showError(error) {
  console.error(error);
  toast(error.message || '操作失败，请重试。', 'error');
}

function setSourceBusy(busy) {
  const button = sourceForm.querySelector('button[type="submit"]');
  setBusy(button, busy, '正在解析');
}

function renderBook() {
  const book = state.book;
  $('#book-title').textContent = book.title;
  $('#book-author').textContent = `作者：${book.author || '未知'}`;
  $('#book-status').textContent = book.status ? `状态：${book.status}` : '';
  $('#book-category').textContent = `${book.category || '轻小说'}${book.updatedAt ? ` · 更新于 ${book.updatedAt}` : ''}`;
  $('#export-title').textContent = book.title;
}

function makeChapterRow(chapter) {
  const label = document.createElement('label');
  label.className = 'chapter-row';
  label.dataset.volume = chapter.volume || '正文';
  label.dataset.title = `${chapter.title} ${chapter.volume}`.toLocaleLowerCase('zh-CN');
  const checkbox = document.createElement('input');
  checkbox.type = 'checkbox';
  checkbox.value = chapter.id;
  checkbox.checked = true;
  checkbox.setAttribute('aria-label', `选择 ${chapter.title}`);
  const check = document.createElement('span');
  check.className = 'custom-check';
  check.setAttribute('aria-hidden', 'true');
  const index = document.createElement('span');
  index.className = 'chapter-index';
  index.textContent = String(chapter.order).padStart(2, '0');
  const name = document.createElement('span');
  name.className = 'chapter-name';
  name.title = chapter.title;
  name.textContent = chapter.title;
  const marker = document.createElement('span');
  marker.className = 'chapter-marker';
  if (chapter.isIllustration) {
    const illustration = document.createElement('em');
    illustration.className = 'illustration-label';
    illustration.textContent = '插图';
    marker.append(illustration);
  }
  // Keep the checkbox immediately before its custom indicator so the
  // adjacent-sibling CSS selector remains valid in every browser.
  label.append(checkbox, check, index, name, marker);
  chapterList.append(label);

  checkbox.addEventListener('change', () => {
    if (checkbox.checked) state.selected.add(chapter.id);
    else state.selected.delete(chapter.id);
    updateSelection();
  });
}

function escapeHtml(value) {
  const node = document.createElement('span');
  node.textContent = value || '';
  return node.innerHTML;
}

function renderChapters() {
  chapterList.replaceChildren();
  let lastVolume;
  for (const chapter of state.index.chapters) {
    if (chapter.volume !== lastVolume) {
      lastVolume = chapter.volume;
      const heading = document.createElement('h3');
      heading.className = 'volume-heading';
      heading.textContent = chapter.volume;
      chapterList.append(heading);
    }
    makeChapterRow(chapter);
  }
  state.selected = new Set(state.index.chapters.map((chapter) => chapter.id));
  updateSelection();
}

function updateSelection() {
  $('#selected-count').textContent = state.selected.size;
  $('#total-count').textContent = state.index?.chapters.length || 0;
  for (const checkbox of $$('.chapter-row input', chapterList)) {
    checkbox.checked = state.selected.has(checkbox.value);
  }
  const selectedChapters = selectedChaptersInOrder();
  $('#export-count').textContent = `${selectedChapters.length} 章`;
  if (selectedChapters.length) setMessage(chapterMessage, `已准备 ${selectedChapters.length} 个章节。`, 'success');
  else setMessage(chapterMessage, '请至少选择一个章节。', 'error');
}

function selectedChaptersInOrder() {
  if (!state.index) return [];
  return state.index.chapters.filter((chapter) => state.selected.has(chapter.id));
}

function filterChapters(query) {
  const needle = query.trim().toLocaleLowerCase('zh-CN');
  for (const row of $$('.chapter-row', chapterList)) {
    row.hidden = Boolean(needle) && !row.dataset.title.includes(needle);
  }
  for (const heading of $$('.volume-heading', chapterList)) {
    let next = heading.nextElementSibling;
    let visible = false;
    while (next && !next.classList.contains('volume-heading')) {
      if (!next.hidden) visible = true;
      next = next.nextElementSibling;
    }
    heading.hidden = !visible;
  }
}

function selectAll(value) {
  for (const row of $$('.chapter-row', chapterList)) {
    const checkbox = $('input', row);
    checkbox.checked = value;
    const chapter = state.index.chapters.find((item) => item.id === checkbox.value);
    if (value) state.selected.add(chapter.id);
    else state.selected.delete(chapter.id);
  }
  updateSelection();
}

async function parseSource() {
  const input = sourceInput.value.trim();
  if (!input) {
    setMessage(sourceMessage, '请先输入书籍网址或书籍 ID。', 'error');
    sourceInput.focus();
    return;
  }
  setSourceBusy(true);
  setMessage(sourceMessage, '正在读取书籍信息…');
  try {
    const runtime = mobileCore();
    const [{ book }, { index }] = runtime
      ? await Promise.all([runtime.parseBook(input), runtime.parseIndex(input)])
      : await Promise.all([
          api('/api/parse/book', { method: 'POST', body: JSON.stringify({ url: input }) }),
          api('/api/parse/index', { method: 'POST', body: JSON.stringify({ url: input }) }),
        ]);
    if (book.title && index.title && book.title !== index.title) {
      console.warn('书籍页和目录页标题不同，使用书籍页标题。', book.title, index.title);
    }
    state.book = book;
    state.index = index;
    stopProgressTracking();
    state.currentJobId = null;
    renderBook();
    renderChapters();
    showPanel(2);
    setMessage(sourceMessage, '解析完成，请选择需要导出的章节。', 'success');
    window.scrollTo({ top: document.querySelector('.workspace').offsetTop - 18, behavior: 'smooth' });
  } catch (error) {
    setMessage(sourceMessage, error.message, 'error');
    showError(error);
  } finally {
    setSourceBusy(false);
  }
}

function prepareExport() {
  const selected = selectedChaptersInOrder();
  if (!selected.length) {
    toast('请至少选择一个章节。', 'error');
    return;
  }
  $('#export-count').textContent = `${selected.length} 章`;
  setMessage(exportMessage, '');
  showPanel(3);
  jobSection.hidden = true;
  window.scrollTo({ top: document.querySelector('.workspace').offsetTop - 18, behavior: 'smooth' });
}

async function createJob() {
  const selected = selectedChaptersInOrder();
  if (!selected.length) return;
  setBusy(exportButton, true, '正在创建任务');
  setMessage(exportMessage, '');
  try {
    const runtime = mobileCore();
    const payload = runtime
      ? { job: await runtime.createJob({
          book: state.book,
          chapters: selected,
          options: { includeCover: $('#include-cover').checked },
        }) }
      : await api('/api/jobs', {
          method: 'POST',
          body: JSON.stringify({
            book: state.book,
            chapters: selected,
            options: { includeCover: $('#include-cover').checked },
          }),
        });
    state.currentJobId = payload.job.id;
    renderJob(payload.job);
    jobSection.hidden = false;
    startProgressTracking(payload.job.id);
    setMessage(exportMessage, '任务已创建，正在后台处理。', 'success');
    window.scrollTo({ top: jobSection.offsetTop - 24, behavior: 'smooth' });
  } catch (error) {
    setMessage(exportMessage, error.message, 'error');
    showError(error);
  } finally {
    setBusy(exportButton, false);
  }
}

function progressNumber(value, fallback = 0) {
  if (value === undefined || value === null || value === '') return fallback;
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? Math.floor(number) : fallback;
}

function normalizeJobProgress(job) {
  const progress = job.progress && typeof job.progress === 'object' ? job.progress : {};
  return {
    ...progress,
    phase: progress.phase || 'queued',
    percent: Math.max(0, Math.min(100, Math.round(Number(progress.percent) || 0))),
    completed: progressNumber(progress.completed),
    total: progressNumber(progress.total, progressNumber(job.chapterCount)),
    imageCompleted: progressNumber(progress.imageCompleted, progressNumber(job.imageCount)),
    message: progress.message || '',
    currentTitle: progress.currentTitle || '',
  };
}

function renderJob(job) {
  const progress = normalizeJobProgress(job);
  const active = job.status === 'queued' || job.status === 'running';
  $('#job-title').textContent = job.book.title;
  $('#job-percent').textContent = `${progress.percent}%`;
  $('#progress-bar').style.width = `${progress.percent}%`;
  $('.progress-track').setAttribute('aria-valuenow', String(progress.percent));
  $('#job-message').textContent = progress.message || phaseLabel(progress.phase);
  $('#job-chapters').textContent = `${progress.completed} / ${progress.total}`;
  $('#job-images').textContent = String(progress.imageCompleted);
  $('#job-status').textContent = STATUS_LABELS[job.status] || job.status;
  currentJobStatus.className = `status-pill ${job.status}`;
  $('#cancel-button').hidden = !active;
  $('#share-button').hidden = job.status !== 'completed' || !mobileCore();
  $('#download-button').hidden = job.status !== 'completed';
  $('#job-location').textContent = mobileCore() ? '手机 Download/EPUB' : '本机 output 文件夹';
  if (job.status === 'completed' && job.file) {
    state.currentJobFile = job.file;
    downloadButton.href = job.file.downloadUrl || '#';
    downloadButton.setAttribute('download', job.file.name);
    downloadButton.dataset.bytes = String(job.file.size || 0);
    const label = downloadButton.querySelector('span');
    if (label) label.textContent = mobileCore() ? '保存 EPUB' : '下载 EPUB';
  }
  $('#job-message-error').textContent = job.error?.message || '';
  $('#job-message-error').classList.toggle('error', Boolean(job.error));
  const warning = $('#job-warning');
  const warnings = Array.isArray(job.warnings) ? job.warnings : [];
  warning.hidden = warnings.length === 0;
  warning.textContent = warnings.length
    ? `${warnings.length} 个章节或资源被跳过：${warnings.slice(0, 3).join('；')}${warnings.length > 3 ? '…' : ''}`
    : '';
}

function phaseLabel(phase) {
  return ({ fetching: '正在下载章节', images: '正在下载插图', cover: '正在下载封面', packaging: '正在打包 EPUB' })[phase] || '准备中…';
}

function clearProgressPolling() {
  clearInterval(state.pollTimer);
  state.pollTimer = null;
}

function stopProgressTracking() {
  state.progressGeneration += 1;
  clearProgressPolling();
  state.progressUnsubscribe?.();
  state.progressUnsubscribe = null;
  state.progressSource?.close();
  state.progressSource = null;
}

function startProgressPolling(id, generation) {
  if (generation !== state.progressGeneration || state.pollTimer !== null) return;
  state.pollTimer = setInterval(() => pollNow(id, generation), 1200);
}

function finishProgressTracking(job) {
  stopProgressTracking();
  loadHistory();
  if (job.status === 'completed') toast('EPUB 已生成，可以下载。', 'success');
  if (job.status === 'failed') toast(job.error?.message || '导出任务失败。', 'error');
}

async function getJobPayload(id) {
  const runtime = mobileCore();
  if (runtime) return { job: await runtime.getJob(id) };
  return api(`/api/jobs/${encodeURIComponent(id)}`);
}

async function pollNow(id, generation = state.progressGeneration) {
  if (!id || generation !== state.progressGeneration) return;
  try {
    const { job } = await getJobPayload(id);
    if (generation !== state.progressGeneration) return;
    renderJob(job);
    if (job.status !== 'queued' && job.status !== 'running') finishProgressTracking(job);
  } catch (error) {
    if (generation !== state.progressGeneration) return;
    if (error.status === 404) {
      stopProgressTracking();
      loadHistory();
    } else showError(error);
  }
}

function startProgressTracking(id) {
  stopProgressTracking();
  const generation = state.progressGeneration;
  const runtime = mobileCore();
  if (runtime) {
    state.progressUnsubscribe = runtime.subscribeJob((job) => {
      if (generation !== state.progressGeneration || job.id !== id) return;
      renderJob(job);
      if (job.status !== 'queued' && job.status !== 'running') finishProgressTracking(job);
    });
    runtime.getJob(id).then((job) => {
      if (generation !== state.progressGeneration || job.id !== id) return;
      renderJob(job);
      if (job.status !== 'queued' && job.status !== 'running') finishProgressTracking(job);
    }).catch((error) => {
      if (generation === state.progressGeneration) showError(error);
    });
    return;
  }

  pollNow(id, generation);
  if (typeof EventSource !== 'function') {
    startProgressPolling(id, generation);
    return;
  }

  let source;
  try {
    source = new EventSource(`/api/jobs/${encodeURIComponent(id)}/events`);
  } catch {
    startProgressPolling(id, generation);
    return;
  }
  state.progressSource = source;
  source.addEventListener('job', (event) => {
    if (generation !== state.progressGeneration) return;
    let job;
    try {
      job = JSON.parse(event.data);
    } catch {
      startProgressPolling(id, generation);
      return;
    }
    if (!job || job.id !== id) return;
    clearProgressPolling();
    renderJob(job);
    if (job.status !== 'queued' && job.status !== 'running') finishProgressTracking(job);
  });
  source.addEventListener('error', () => {
    if (generation === state.progressGeneration) startProgressPolling(id, generation);
  });
}

async function cancelJob() {
  if (!state.currentJobId) return;
  $('#cancel-button').disabled = true;
  try {
    const runtime = mobileCore();
    const { job } = runtime
      ? { job: await runtime.cancelJob(state.currentJobId) }
      : await api(`/api/jobs/${state.currentJobId}/cancel`, { method: 'POST' });
    renderJob(job);
  } catch (error) {
    showError(error);
  } finally {
    $('#cancel-button').disabled = false;
  }
}

function formatBytes(bytes) {
  if (!Number.isFinite(bytes)) return '';
  const units = ['B', 'KB', 'MB', 'GB'];
  let size = bytes;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) { size /= 1024; unit += 1; }
  return `${size.toFixed(unit ? 1 : 0)} ${unit}`;
}

function formatDate(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function filenameFromDisposition(header, fallback = 'book.epub') {
  const encoded = header?.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  if (encoded) {
    try { return decodeURIComponent(encoded); } catch {}
  }
  const plain = header?.match(/filename="([^"]+)"/i)?.[1];
  return plain || fallback;
}

function setDownloadProgress({ percent = 0, loaded = 0, total = 0, status = '', state = '' } = {}) {
  const safeLoaded = Number(loaded) || 0;
  const safeTotal = Number(total) || 0;
  const safePercent = Math.max(0, Math.min(100, Math.round(Number(percent) || 0)));
  $('#download-progress-bar').style.width = `${safePercent}%`;
  $('#download-percent').textContent = `${safePercent}%`;
  $('#download-progress').setAttribute('aria-valuenow', String(safePercent));
  $('#download-progress').className = `download-progress-track ${state}`.trim();
  $('#download-bytes').textContent = safeTotal > 0
    ? `${formatBytes(safeLoaded)} / ${formatBytes(safeTotal)}`
    : `${formatBytes(safeLoaded)} / 未知大小`;
  if (status) $('#download-file-status').textContent = status;
}

function setDownloadControls(active) {
  const closeButton = $('#close-download-panel');
  closeButton.disabled = active;
  closeButton.textContent = active ? '下载中…' : '关闭';
  $('#cancel-download-button').hidden = !active;
}

function showDownloadPanel(name) {
  $('#download-file-name').textContent = name || 'book.epub';
  $('#download-panel').hidden = false;
  setDownloadControls(true);
  setDownloadProgress({ percent: 0, loaded: 0, total: 0, status: '正在连接…' });
}

function closeDownloadPanel() {
  if (state.downloadController) return;
  $('#download-panel').hidden = true;
}

function triggerBrowserDownload(blob, fileName) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName || 'book.epub';
  anchor.style.display = 'none';
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

async function startFileDownload(job) {
  const file = job?.file || state.currentJobFile;
  const runtime = mobileCore();
  if (runtime) {
    const jobId = job?.id || state.currentJobId;
    if (!jobId || !file) {
      toast('没有可保存的 EPUB 文件。', 'error');
      return;
    }
    if (state.downloadController) {
      toast('已有文件操作正在进行。');
      return;
    }
    const controller = new AbortController();
    state.downloadController = controller;
    showDownloadPanel(file.name);
    setDownloadControls(true);
    try {
      const result = await runtime.saveFile(jobId);
      setDownloadProgress({ percent: 100, loaded: result.bytes, total: result.bytes, status: `已保存到 ${result.location}`, state: 'complete' });
      setDownloadControls(false);
      toast('EPUB 已保存到下载目录。', 'success');
    } catch (error) {
      setDownloadProgress({ percent: 0, loaded: 0, total: file.size, status: error.message || '保存失败。', state: 'error' });
      setDownloadControls(false);
      showError(error);
    } finally {
      state.downloadController = null;
    }
    return;
  }
  if (!file?.downloadUrl) {
    toast('没有可下载的 EPUB 文件。', 'error');
    return;
  }
  if (state.downloadController) {
    toast('已有下载正在进行。');
    return;
  }

  const controller = new AbortController();
  state.downloadController = controller;
  showDownloadPanel(file.name);
  let total = Number(file.size) || 0;
  let loaded = 0;
  let lastRenderedPercent = -1;

  try {
    const response = await fetch(file.downloadUrl, {
      signal: controller.signal,
      cache: 'no-store',
    });
    if (!response.ok) throw new Error(`下载请求失败（HTTP ${response.status}）。`);
    const headerLength = Number(response.headers.get('content-length')) || 0;
    if (headerLength > 0) total = headerLength;
    if (!response.body) throw new Error('当前浏览器不支持流式下载。');

    const reader = response.body.getReader();
    const chunks = [];
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      loaded += value.byteLength;
      const percent = total > 0 ? (loaded / total) * 100 : 0;
      const rounded = Math.min(100, Math.round(percent));
      if (rounded !== lastRenderedPercent || (total > 0 && loaded === total)) {
        lastRenderedPercent = rounded;
        setDownloadProgress({ percent, loaded, total, status: '正在下载 EPUB…' });
      }
    }

    if (total > 0 && loaded !== total) throw new Error('下载数据不完整，请重试。');
    setDownloadProgress({ percent: 100, loaded, total: total || loaded, status: '下载完成，正在交给浏览器保存。', state: 'complete' });
    setDownloadControls(false);
    triggerBrowserDownload(
      new Blob(chunks, { type: 'application/epub+zip' }),
      filenameFromDisposition(response.headers.get('content-disposition'), file.name),
    );
    toast('EPUB 下载完成。', 'success');
  } catch (error) {
    if (error.name === 'AbortError') {
      setDownloadProgress({ percent: 0, loaded: 0, total, status: '下载已取消。', state: 'canceled' });
      setDownloadControls(false);
      toast('下载已取消。');
      return;
    }
    setDownloadProgress({ percent: 0, loaded: 0, total, status: error.message || '下载失败。', state: 'error' });
    setDownloadControls(false);
    showError(error);
  } finally {
    state.downloadController = null;
  }
}

async function startFileShare() {
  const runtime = mobileCore();
  if (!runtime || !state.currentJobId) {
    toast('当前平台不支持分享。', 'error');
    return;
  }
  try {
    await runtime.shareFile(state.currentJobId);
  } catch (error) {
    showError(error);
  }
}

function cancelFileDownload() {
  state.downloadController?.abort(new DOMException('用户取消了下载', 'AbortError'));
}

async function loadHistory() {
  try {
    const runtime = mobileCore();
    const { jobs } = runtime
      ? { jobs: runtime.listJobs() }
      : await api('/api/jobs');
    const historySection = $('#history-section');
    historySection.hidden = jobs.length === 0;
    const rows = document.createDocumentFragment();
    for (const job of jobs) {
      const row = document.createElement('div');
      row.className = 'history-item';
      row.dataset.jobId = job.id;
      const detail = `${job.chapterCount} 章 · ${formatDate(job.createdAt)}${job.warnings?.length ? ` · ${job.warnings.length} 条警告` : ''}`;
      row.innerHTML = `
        <div><strong>${escapeHtml(job.book.title)}</strong><small>${escapeHtml(detail)}</small></div>
        <span class="status-pill ${job.status}">${STATUS_LABELS[job.status] || job.status}</span>
        ${job.file ? `<a class="text-button" href="${job.file.downloadUrl || '#'}" download="${escapeHtml(job.file.name)}">${mobileCore() ? '保存' : '下载'}</a>` : ''}
      `;
      rows.append(row);
    }
    historyList.replaceChildren(rows);
    for (const link of $('.text-button', historyList)) {
      link.addEventListener('click', (event) => {
        event.preventDefault();
        const row = link.closest('.history-item');
        const job = jobs.find((item) => item.file && `${item.id}` === row?.dataset.jobId);
        if (job) startFileDownload(job);
      });
    }
  } catch (error) {
    console.warn('任务历史读取失败。', error);
  }
}

function wireEvents() {
  sourceForm.addEventListener('submit', (event) => { event.preventDefault(); parseSource(); });
  $('#example-button').addEventListener('click', () => {
    sourceInput.value = 'https://www.wenku8.net/novel/2/2835/index.htm';
    sourceInput.focus();
  });
  chapterSearch.addEventListener('input', () => filterChapters(chapterSearch.value));
  for (const button of $$('.text-button[data-select]')) {
    button.addEventListener('click', () => selectAll(button.dataset.select !== 'none'));
  }
  toExportButton.addEventListener('click', prepareExport);
  for (const button of $$('[data-back]')) button.addEventListener('click', () => {
    const step = button.closest('.step-panel').dataset.step;
    showPanel(Number(step) === 3 ? 2 : 1);
  });
  exportButton.addEventListener('click', createJob);
  downloadButton.addEventListener('click', (event) => {
    event.preventDefault();
    startFileDownload({ file: state.currentJobFile });
  });
  $('#close-download-panel').addEventListener('click', closeDownloadPanel);
  $('#cancel-download-button').addEventListener('click', cancelFileDownload);
  shareButton.addEventListener('click', startFileShare);
  $('#cancel-button').addEventListener('click', cancelJob);
  $('#refresh-history').addEventListener('click', loadHistory);
  window.addEventListener('beforeunload', stopProgressTracking);
}

document.addEventListener('DOMContentLoaded', () => {
  wireEvents();
  const runtime = mobileCore();
  if (runtime) {
    runtime.init().then(loadHistory).catch((error) => {
      showError(error);
      setMessage(sourceMessage, 'Android 本地运行时初始化失败。', 'error');
    });
  } else loadHistory();
});

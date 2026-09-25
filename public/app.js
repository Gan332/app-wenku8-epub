'use strict';

const state = {
  book: null,
  index: null,
  selected: new Set(),
  currentJobId: null,
  pollTimer: null,
};

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
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
  const lastVolume = Symbol('none');
  for (const chapter of state.index.chapters) {
    if (chapter.volume !== lastVolume.value) {
      lastVolume.value = chapter.volume;
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
    const [{ book }, { index }] = await Promise.all([
      api('/api/parse/book', { method: 'POST', body: JSON.stringify({ url: input }) }),
      api('/api/parse/index', { method: 'POST', body: JSON.stringify({ url: input }) }),
    ]);
    if (book.title && index.title && book.title !== index.title) {
      console.warn('书籍页和目录页标题不同，使用书籍页标题。', book.title, index.title);
    }
    state.book = book;
    state.index = index;
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
    const payload = await api('/api/jobs', {
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
    startPolling(payload.job.id);
    setMessage(exportMessage, '任务已创建，正在后台处理。', 'success');
    window.scrollTo({ top: jobSection.offsetTop - 24, behavior: 'smooth' });
  } catch (error) {
    setMessage(exportMessage, error.message, 'error');
    showError(error);
  } finally {
    setBusy(exportButton, false);
  }
}

function renderJob(job) {
  const active = job.status === 'queued' || job.status === 'running';
  $('#job-title').textContent = job.book.title;
  $('#job-percent').textContent = `${job.progress.percent}%`;
  $('#progress-bar').style.width = `${job.progress.percent}%`;
  $('.progress-track').setAttribute('aria-valuenow', String(job.progress.percent));
  $('#job-message').textContent = job.progress.message || phaseLabel(job.progress.phase);
  $('#job-chapters').textContent = `${job.progress.completed} / ${job.progress.total}`;
  $('#job-images').textContent = String(job.progress.imageCompleted);
  $('#job-status').textContent = STATUS_LABELS[job.status] || job.status;
  currentJobStatus.className = `status-pill ${job.status}`;
  $('#cancel-button').hidden = !active;
  $('#download-button').hidden = job.status !== 'completed';
  if (job.status === 'completed') {
    $('#download-button').href = job.file.downloadUrl;
    $('#download-button').setAttribute('download', job.file.name);
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

function startPolling(id) {
  clearInterval(state.pollTimer);
  pollNow(id);
  state.pollTimer = setInterval(() => pollNow(id), 800);
}

async function pollNow(id) {
  if (!id) return;
  try {
    const { job } = await api(`/api/jobs/${encodeURIComponent(id)}`);
    renderJob(job);
    if (job.status !== 'queued' && job.status !== 'running') {
      clearInterval(state.pollTimer);
      loadHistory();
      if (job.status === 'completed') toast('EPUB 已生成，可以下载。', 'success');
      if (job.status === 'failed') toast(job.error?.message || '导出任务失败。', 'error');
    }
  } catch (error) {
    if (error.status === 404) {
      clearInterval(state.pollTimer);
      loadHistory();
    } else showError(error);
  }
}

async function cancelJob() {
  if (!state.currentJobId) return;
  $('#cancel-button').disabled = true;
  try {
    const { job } = await api(`/api/jobs/${state.currentJobId}/cancel`, { method: 'POST' });
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

async function loadHistory() {
  try {
    const { jobs } = await api('/api/jobs');
    const historySection = $('#history-section');
    historySection.hidden = jobs.length === 0;
    const rows = document.createDocumentFragment();
    for (const job of jobs) {
      const row = document.createElement('div');
      row.className = 'history-item';
      const detail = `${job.chapterCount} 章 · ${formatDate(job.createdAt)}${job.warnings?.length ? ` · ${job.warnings.length} 条警告` : ''}`;
      row.innerHTML = `
        <div><strong>${escapeHtml(job.book.title)}</strong><small>${escapeHtml(detail)}</small></div>
        <span class="status-pill ${job.status}">${STATUS_LABELS[job.status] || job.status}</span>
        ${job.file ? `<a class="text-button" href="${job.file.downloadUrl}" download="${escapeHtml(job.file.name)}">下载</a>` : ''}
      `;
      rows.append(row);
    }
    historyList.replaceChildren(rows);
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
  $$('.text-button[data-select]').each((button) => button.addEventListener('click', () => selectAll(button.dataset.select !== 'none')));
  toExportButton.addEventListener('click', prepareExport);
  $$('[data-back]').each((button) => button.addEventListener('click', () => {
    const step = button.closest('.step-panel').dataset.step;
    showPanel(Number(step) === 3 ? 2 : 1);
  }));
  exportButton.addEventListener('click', createJob);
  $('#cancel-button').addEventListener('click', cancelJob);
  $('#refresh-history').addEventListener('click', loadHistory);
  window.addEventListener('beforeunload', () => clearInterval(state.pollTimer));
}

document.addEventListener('DOMContentLoaded', () => {
  wireEvents();
  loadHistory();
});

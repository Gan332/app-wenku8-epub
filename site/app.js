(() => {
  'use strict';

  const REPO = 'Gan332/app-wenku8-epub';
  const root = document.documentElement;
  const themeMeta = document.querySelector('meta[name="theme-color"]');
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');

  /* ---------- 主题 ---------- */

  const THEME_KEY = 'wenku8-theme';
  const themeToggle = document.getElementById('theme-toggle');

  function syncThemeChrome() {
    const dark = root.dataset.theme === 'dark';
    if (themeMeta) themeMeta.setAttribute('content', dark ? '#141110' : '#F7F1E7');
    if (themeToggle) themeToggle.setAttribute('aria-pressed', String(dark));
  }

  function setTheme(theme, persist) {
    root.dataset.theme = theme;
    if (persist) {
      try {
        localStorage.setItem(THEME_KEY, theme);
      } catch (e) {
        /* 隐私模式下忽略 */
      }
    }
    syncThemeChrome();
  }

  syncThemeChrome();

  if (themeToggle) {
    themeToggle.addEventListener('click', () => {
      setTheme(root.dataset.theme === 'dark' ? 'light' : 'dark', true);
    });
  }

  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (event) => {
    let saved = null;
    try {
      saved = localStorage.getItem(THEME_KEY);
    } catch (e) {
      /* ignore */
    }
    if (!saved) setTheme(event.matches ? 'dark' : 'light', false);
  });

  /* ---------- 顶栏状态 ---------- */

  const header = document.querySelector('.site-header');
  if (header) {
    const onScroll = () => header.classList.toggle('scrolled', window.scrollY > 8);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
  }

  /* ---------- 入场动画 ---------- */

  const revealItems = Array.from(document.querySelectorAll('.reveal'));
  revealItems.forEach((el) => {
    const siblings = el.parentElement ? Array.from(el.parentElement.children).filter((n) => n.classList.contains('reveal')) : [];
    const index = siblings.indexOf(el);
    if (index > 0) el.style.transitionDelay = `${Math.min(index, 6) * 55}ms`;
  });

  if ('IntersectionObserver' in window && !reduceMotion.matches) {
    const revealObserver = new IntersectionObserver((entries, observer) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add('in');
          observer.unobserve(entry.target);
        }
      });
    }, { threshold: 0.12, rootMargin: '0px 0px -40px 0px' });
    revealItems.forEach((el) => revealObserver.observe(el));
  } else {
    revealItems.forEach((el) => el.classList.add('in'));
  }

  /* ---------- 导航高亮 ---------- */

  const navLinks = Array.from(document.querySelectorAll('.nav-links a'));
  const sections = navLinks
    .map((link) => document.querySelector(link.getAttribute('href')))
    .filter(Boolean);

  if ('IntersectionObserver' in window && sections.length) {
    const sectionObserver = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        navLinks.forEach((link) => {
          link.classList.toggle('active', link.getAttribute('href') === `#${entry.target.id}`);
        });
      });
    }, { rootMargin: '-40% 0px -55% 0px', threshold: 0 });
    sections.forEach((section) => sectionObserver.observe(section));
  }

  /* ---------- 最新版本 ---------- */

  const downloadLinks = [
    document.getElementById('download-apk'),
    document.getElementById('download-apk-2'),
  ].filter(Boolean);

  const pill = document.getElementById('release-pill');
  const versionLabel = document.getElementById('download-version');
  const dateLabel = document.getElementById('release-date');
  const dlVersion = document.getElementById('dl-version');
  const dlSize = document.getElementById('dl-size');
  const dlSummary = document.getElementById('dl-summary');

  function safeUrl(url) {
    return typeof url === 'string' && (
      url.startsWith('https://github.com/') ||
      url.startsWith('https://objects.githubusercontent.com/')
    ) ? url : null;
  }

  function formatDate(value) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '';
    return date.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' });
  }

  function applyRelease(release) {
    if (!release) return;

    const tag = release.tag_name || '';
    const prerelease = Boolean(release.prerelease);
    const published = formatDate(release.published_at);
    const assets = Array.isArray(release.assets) ? release.assets : [];
    const apk = assets.find((asset) => /release\.apk$/i.test(asset.name))
      || assets.find((asset) => /\.apk$/i.test(asset.name));
    const apkUrl = safeUrl(apk && apk.browser_download_url) || safeUrl(release.html_url);

    if (apkUrl) downloadLinks.forEach((link) => { link.href = apkUrl; });
    if (tag) {
      if (versionLabel) versionLabel.textContent = tag;
      if (dlVersion) dlVersion.textContent = tag;
    }
    if (pill) pill.textContent = prerelease ? '预发布' : '稳定版';
    if (dateLabel && published) dateLabel.textContent = published;

    if (apk && apk.size > 0 && dlSize) {
      dlSize.textContent = `${(apk.size / 1024 / 1024).toFixed(1)} MB`;
    }

    if (dlSummary) {
      const kind = prerelease ? '预发布' : '稳定版';
      const when = published ? `，发布于 ${published}` : '';
      dlSummary.textContent = `当前最新${kind} ${tag}${when}。安装包托管在 GitHub Releases，下载后即可覆盖安装。`;
    }
  }

  if (downloadLinks.length || pill) {
    fetch(`https://api.github.com/repos/${REPO}/releases?per_page=10`, {
      headers: { Accept: 'application/vnd.github+json' },
    })
      .then((response) => {
        if (!response.ok) throw new Error(`GitHub API ${response.status}`);
        return response.json();
      })
      .then((list) => {
        if (!Array.isArray(list)) return;
        const release = list.find((item) => item && !item.draft);
        applyRelease(release);
      })
      .catch(() => {
        /* 离线或限流时保留静态链接与版本信息 */
      });
  }

  /* ---------- 年份 ---------- */

  const year = document.getElementById('year');
  if (year) year.textContent = String(new Date().getFullYear());
})();

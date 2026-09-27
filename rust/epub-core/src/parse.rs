//! EPUB 结构解析（手写，语义窄）。
//!
//! 只做四件简单事：`container.xml` → 包路径、`OPF` → manifest/spine、
//! `nav.xhtml`/`toc.ncx` → 章节标题映射、按 spine 读出每章 HTML。
//!
//! **HTML → 内容块不在此处**：仍由 Kotlin 侧 Jsoup（`parseBlocks`）处理，
//! 保证两条路径行为一致、现有测试全部有效。
//!
//! 解析目标语义与 Kotlin `EpubReaderRepository` 的 legacy 实现逐点对齐
//! （路径归一化、toc 查找、标题 fallback），并有等价性测试锁定。
//! 任何解析失败都返回 `Err` → JNI 层返回 null → Kotlin 回退 legacy 路径。

use serde::Serialize;
use std::collections::HashMap;
use std::fs::File;
use std::io::Read;
use zip::ZipArchive;

#[derive(Debug, Serialize)]
pub struct NativeEpub {
    /// OPF 所在目录（如 `EPUB` 或 `OEBPS`）—— Kotlin 侧用它解析图片相对路径。
    #[serde(rename = "packageDir")]
    pub package_dir: String,
    /// 书名（dc:title 缺失时与 legacy 相同回退 "EPUB 阅读"）。
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub author: String,
    #[serde(default)]
    pub language: String,
    pub chapters: Vec<NativeChapter>,
}

#[derive(Debug, Serialize)]
pub struct NativeChapter {
    pub id: String,
    pub title: String,
    /// 相对 zip 根的完整章节路径（已归一化），如 `EPUB/text/chapter-0001.xhtml`。
    pub href: String,
    /// 章节原始 HTML（不解析，交给 Kotlin Jsoup）。
    pub html: String,
}

pub fn parse_epub(path: &str) -> Result<NativeEpub, String> {
    let file = File::open(path).map_err(|e| format!("open: {e}"))?;
    let mut zip = ZipArchive::new(file).map_err(|e| format!("zip: {e}"))?;

    // 1. container.xml → OPF 路径
    let mut budget = 0usize;
    let container = read_entry(&mut zip, "META-INF/container.xml", &mut budget)?;
    let package_path = attr_of_tag(&container, "rootfile", "full-path")
        .ok_or_else(|| "container: missing full-path".to_string())?;
    let package_dir = dir_of(&package_path);

    // 2. OPF → metadata / manifest / spine / nav / ncx
    let opf = read_entry(&mut zip, &package_path, &mut budget)?;
    let manifest = parse_manifest(&opf);
    let spine = parse_spine(&opf);
    if manifest.is_empty() || spine.is_empty() {
        return Err("opf: empty manifest or spine".into());
    }
    // 与 legacy 相同的元数据语义：title 缺失回退 "EPUB 阅读"，language 回退 zh-CN
    let title = meta_text(&opf, "title").unwrap_or_else(|| "EPUB 阅读".to_string());
    let author = meta_text(&opf, "creator").unwrap_or_default();
    let language = meta_text(&opf, "language").unwrap_or_else(|| "zh-CN".to_string());
    let manifest_by_id: HashMap<&str, &ManifestItem> =
        manifest.iter().map(|m| (m.id.as_str(), m)).collect();
    let nav_path = manifest
        .iter()
        .find(|m| m.properties.contains("nav"))
        .map(|m| resolve_path(&package_dir, &m.href));
    let ncx_path = manifest
        .iter()
        .find(|m| m.media_type.contains("dtbncx"))
        .map(|m| resolve_path(&package_dir, &m.href));

    // 3. 目录标题映射（href → title）；nav 优先，缺失回退 NCX
    let mut toc: HashMap<String, String> = HashMap::new();
    if let Some(nav) = &nav_path {
        if let Ok(nav_html) = read_entry(&mut zip, nav, &mut budget) {
            toc_titles_from_nav(&nav_html, &package_dir, &mut toc);
        }
    }
    if toc.is_empty() {
        if let Some(ncx) = &ncx_path {
            if let Ok(ncx_xml) = read_entry(&mut zip, ncx, &mut budget) {
                toc_titles_from_ncx(&ncx_xml, &package_dir, &mut toc);
            }
        }
    }

    // 4. 按 spine 顺序读出章节 HTML（与 Kotlin 现状一致：全部章节一次性驻留内存）
    let mut chapters = Vec::with_capacity(spine.len());
    for idref in &spine {
        let item = manifest_by_id
            .get(idref.as_str())
            .ok_or_else(|| format!("spine: unknown idref {idref}"))?;
        if !item.media_type.contains("html") && !item.media_type.is_empty() {
            // 非 HTML 资源（封面等）不进章节列表 —— 与 Kotlin 只收 html 的行为一致
            if !item.href.ends_with(".xhtml") && !item.href.ends_with(".html") && !item.href.ends_with(".htm") {
                continue;
            }
        }
        let href = resolve_path(&package_dir, &item.href);
        let html = read_entry(&mut zip, &href, &mut budget)?;
        let title = toc.get(&href)
            .cloned()
            .unwrap_or_else(|| basename(&href));
        chapters.push(NativeChapter {
            id: item.id.clone(),
            title,
            href,
            html,
        });
    }
    if chapters.is_empty() {
        return Err("spine: no html chapters".into());
    }
    Ok(NativeEpub { package_dir, title, author, language, chapters })
}

/// `dc:title` / `dc:creator` / `dc:language`（或无前缀同名）元素文本。
/// 与 legacy 的 `metadata > *` local-name 匹配语义一致：按 前缀变体依次尝试。
fn meta_text(opf: &str, local: &str) -> Option<String> {
    for name in [format!("dc:{local}"), format!("opf:{local}"), local.to_string()] {
        for frag in tag_fragments(opf, &name) {
            if let Some(text) = between(&frag, ">", "</") {
                let text = decode_entities(&text).trim().to_string();
                if !text.is_empty() {
                    return Some(text);
                }
            }
        }
    }
    None
}

// ---------- OPF ----------

struct ManifestItem {
    id: String,
    href: String,
    media_type: String,
    properties: String,
}

fn parse_manifest(opf: &str) -> Vec<ManifestItem> {
    tag_fragments(opf, "item")
        .into_iter()
        .filter_map(|tag| {
            Some(ManifestItem {
                id: attr(&tag, "id")?,
                href: attr(&tag, "href")?,
                media_type: attr(&tag, "media-type").unwrap_or_default(),
                properties: attr(&tag, "properties").unwrap_or_default(),
            })
        })
        .collect()
}

fn parse_spine(opf: &str) -> Vec<String> {
    tag_fragments(opf, "itemref")
        .into_iter()
        .filter_map(|tag| attr(&tag, "idref"))
        .collect()
}

// ---------- toc ----------

/// nav.xhtml：收集所有 `<a href="…">标题</a>`（nav 文件只有 toc 链接）。
fn toc_titles_from_nav(nav_html: &str, package_dir: &str, out: &mut HashMap<String, String>) {
    for (href, title) in anchor_pairs(nav_html) {
        let resolved = resolve_path(package_dir, &href);
        let title = decode_entities(&title).trim().to_string();
        if !title.is_empty() {
            out.insert(resolved, title);
        }
    }
}

/// toc.ncx：navPoint 内 `navLabel <text>` 紧跟 `content src` —— 按出现顺序平行配对
/// （嵌套 navPoint 也保持 label→content 的顺序，与标准 NCX 写法一致）。
fn toc_titles_from_ncx(ncx: &str, package_dir: &str, out: &mut HashMap<String, String>) {
    let labels: Vec<String> = between_all(ncx, "<navLabel>", "</navLabel>")
        .into_iter()
        .filter_map(|seg| between(&seg, "<text>", "</text>"))
        .map(|t| decode_entities(&t).trim().to_string())
        .collect();
    let sources: Vec<String> = attrs_of_tag(ncx, "content", "src");
    for (title, src) in labels.into_iter().zip(sources) {
        if title.is_empty() || src.is_empty() {
            continue;
        }
        out.insert(resolve_path(package_dir, &src), title);
    }
}

// ---------- 路径（与 Kotlin normalizePath/resolvePath 等价） ----------

fn normalize(path: &str) -> String {
    let mut out: Vec<&str> = Vec::new();
    for part in path.replace('\\', "/").split('/') {
        match part {
            "" | "." => {}
            ".." => {
                out.pop();
            }
            p => out.push(p),
        }
    }
    out.join("/")
}

fn resolve_path(base_dir: &str, href: &str) -> String {
    if href.starts_with('/') {
        return normalize(href);
    }
    normalize(&format!("{base_dir}/{href}"))
}

fn dir_of(path: &str) -> String {
    match path.rfind('/') {
        Some(i) => path[..i].to_string(),
        None => String::new(),
    }
}

fn basename(path: &str) -> String {
    path.rsplit('/').next().unwrap_or(path).to_string()
}

// ---------- 极简 XML/HTML 片段工具 ----------

/// 读条目并执行与 legacy 相同的解压限额：单条目 ≤30MB、累计 ≤200MB
///（AGENTS：EPUB 解析必须限制解压大小）。`budget` 为累计已读字节数。
fn read_entry(zip: &mut ZipArchive<File>, name: &str, budget: &mut usize) -> Result<String, String> {
    let mut file = zip.by_name(name).map_err(|e| format!("missing {name}: {e}"))?;
    let size = file.size() as usize;
    if size > MAX_ENTRY_BYTES {
        return Err(format!("entry too large: {name}"));
    }
    *budget += size;
    if budget > &MAX_TOTAL_BYTES {
        return Err("uncompressed epub exceeds limit".into());
    }
    let mut buf = Vec::with_capacity(size);
    file.read_to_end(&mut buf)
        .map_err(|e| format!("read {name}: {e}"))?;
    Ok(String::from_utf8_lossy(&buf).into_owned())
}

const MAX_ENTRY_BYTES: usize = 30 * 1024 * 1024;
const MAX_TOTAL_BYTES: usize = 200 * 1024 * 1024;

/// 取 `<tag …>` 开标签里的 `attr="value"`（双/单引号皆可）。
fn attr(tag: &str, name: &str) -> Option<String> {
    let mut cursor = 0usize;
    while let Some(found) = tag[cursor..].find(name) {
        let start = cursor + found + name.len();
        let rest = &tag[start..];
        // 属性名后必须是 =（避免 href 误配 hreflang）
        let after = rest.trim_start();
        if !after.starts_with('=') {
            cursor = start;
            continue;
        }
        let after = after.trim_start_matches('=').trim_start();
        let quote = after.chars().next()?;
        if quote != '"' && quote != '\'' {
            cursor = start;
            continue;
        }
        let end = after[1..].find(quote)?;
        return Some(decode_entities(&after[1..1 + end]));
    }
    None
}

/// 返回文档中每个 `<name …>` 开标签片段（到 `>` 为止）。
fn tag_fragments(xml: &str, name: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cursor = 0usize;
    let pattern = format!("<{name}");
    while let Some(found) = xml[cursor..].find(&pattern) {
        let start = cursor + found;
        let rest = &xml[start..];
        // 标签名后必须是空白或 '>'（避免 <items 误配 <item）
        let after = &rest[pattern.len()..];
        let boundary = after
            .chars()
            .next()
            .map(|c| c == '>' || c.is_whitespace() || c == '/')
            .unwrap_or(false);
        if boundary {
            if let Some(end) = rest.find('>') {
                out.push(rest[..=end].to_string());
                cursor = start + pattern.len();
                continue;
            }
        }
        cursor = start + pattern.len();
    }
    out
}

/// 每个 `<name …>` 的指定属性（用于 content src 等成组属性）。
fn attrs_of_tag(xml: &str, name: &str, attr_name: &str) -> Vec<String> {
    tag_fragments(xml, name)
        .into_iter()
        .filter_map(|tag| attr(&tag, attr_name))
        .collect()
}

/// 专用：container 的 rootfile full-path。
fn attr_of_tag(xml: &str, name: &str, attr_name: &str) -> Option<String> {
    attrs_of_tag(xml, name, attr_name).into_iter().next()
}

/// `<a href="x">text</a>` 对（text 取到下一个 `<` 为止 —— 目录标题为纯文本）。
fn anchor_pairs(html: &str) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let mut cursor = 0usize;
    while let Some(found) = html[cursor..].find("<a ") {
        let start = cursor + found;
        let rest = &html[start..];
        let Some(tag_end) = rest.find('>') else { break };
        let tag = &rest[..tag_end];
        let Some(href) = attr(tag, "href") else {
            cursor = start + 3;
            continue;
        };
        let content = &rest[tag_end + 1..];
        let text_end = content.find("</a>").unwrap_or(content.len());
        let text = &content[..text_end];
        let text = text.split('<').next().unwrap_or("");
        out.push((href, text.to_string()));
        cursor = start + tag_end + 1;
    }
    out
}

/// 两标记之间的内容（含嵌套时取到**第一个**闭合 —— navLabel/text 无嵌套）。
fn between(text: &str, open: &str, close: &str) -> Option<String> {
    let start = text.find(open)? + open.len();
    let end = text[start..].find(close)?;
    Some(text[start..start + end].to_string())
}

fn between_all(text: &str, open: &str, close: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cursor = 0usize;
    while let Some(found) = text[cursor..].find(open) {
        let start = cursor + found + open.len();
        let Some(end) = text[start..].find(close) else { break };
        out.push(text[start..start + end].to_string());
        cursor = start + end + close.len();
    }
    out
}

fn decode_entities(text: &str) -> String {
    text.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

/// 供 lib.rs 的路径语义对齐测试使用（等价于内部 [resolve_path]）。
#[cfg(test)]
pub(crate) fn resolve_path_for_test(base: &str, href: &str) -> String {
    resolve_path(base, href)
}

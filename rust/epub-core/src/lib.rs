//! `epub-core`：EPUB 结构解析的 Rust 核心 + JNI 入口。
//!
//! 职责边界（与 Kotlin 侧的契约）：
//! - **Rust**：zip 遍历、container/OPF/nav/NCX 结构解析、按 spine 读出章节 HTML
//! - **Kotlin**：HTML → 内容块（Jsoup，现有测试原样有效）、图片字节、渲染
//!
//! JNI 入口 `EpubNative.parse` 返回 JSON（`{"packageDir", "chapters":[…]}`）；
//! 任何失败返回 null，Kotlin 侧自动回退 legacy zip+Jsoup 路径 ——
//! native 是加速路径，不是单点依赖。

mod parse;

pub use parse::{parse_epub, NativeChapter, NativeEpub};

use jni::objects::{JClass, JString};
use jni::JNIEnv;

/// JNI：`com.example.hyperreader.reader.EpubNative.parse(path): String?`
#[no_mangle]
pub extern "system" fn Java_com_example_hyperreader_reader_EpubNative_parse(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jni::sys::jstring {
    let Ok(path) = env.get_string(&path) else {
        return std::ptr::null_mut();
    };
    let path: String = path.into();
    let json = parse_epub(&path).and_then(|v| {
        serde_json::to_string(&v).map_err(|e| format!("serialize: {e}"))
    });
    match json {
        Ok(json) => match env.new_string(json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::io::Write;
    use std::path::PathBuf;
    use zip::write::SimpleFileOptions;
    use zip::CompressionMethod;

    /// 最小合法 EPUB fixture：mimetype(STORED) + container + opf + 可选 nav/ncx + 章节。
    struct Fixture {
        dir: PathBuf,
    }

    impl Fixture {
        fn new(name: &str) -> Self {
            let dir = std::env::temp_dir().join(format!("epub-core-{name}-{}", std::process::id()));
            let _ = fs::remove_dir_all(&dir);
            fs::create_dir_all(&dir).unwrap();
            Fixture { dir }
        }

        fn write(&self, files: &[(&str, &str)], stored_first: bool) -> PathBuf {
            let out = self.dir.join("book.epub");
            let file = fs::File::create(&out).unwrap();
            let mut zip = zip::ZipWriter::new(file);
            for (name, content) in files {
                let options = if stored_first && *name == "mimetype" {
                    SimpleFileOptions::default().compression_method(CompressionMethod::Stored)
                } else {
                    SimpleFileOptions::default()
                };
                zip.start_file(*name, options).unwrap();
                zip.write_all(content.as_bytes()).unwrap();
            }
            zip.finish().unwrap();
            out
        }
    }

    const CONTAINER: &str = r#"<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"#;

    const OPF: &str = r#"<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
<manifest>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
<item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
<item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
</manifest>
<spine><itemref idref="c1"/><itemref idref="c2"/></spine>
</package>"#;

    const OPF_WITH_NCX: &str = r#"<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0">
<manifest>
<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
<item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
</manifest>
<spine toc="ncx"><itemref idref="c1"/></spine>
</package>"#;

    const NAV: &str = r#"<?xml version="1.0"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<nav epub:type="toc"><ol><li><a href="text/ch1.xhtml">第一章　开始</a></li></ol></nav></html>"#;

    const NCX: &str = r#"<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<navMap>
<navPoint id="n1" playOrder="1"><navLabel><text>来自NCX的标题</text></navLabel><content src="text/ch1.xhtml"/></navPoint>
</navMap></ncx>"#;

    const CH1: &str = "<html><body><p>正文一</p></body></html>";
    const CH2: &str = "<html><body><p>正文二</p></body></html>";

    fn base_files<'a>() -> Vec<(&'a str, &'a str)> {
        vec![
            ("mimetype", "application/epub+zip"),
            ("META-INF/container.xml", CONTAINER),
            ("EPUB/package.opf", OPF),
            ("EPUB/nav.xhtml", NAV),
            ("EPUB/text/ch1.xhtml", CH1),
            ("EPUB/text/ch2.xhtml", CH2),
        ]
    }

    #[test]
    fn parses_structure_toc_and_chapters() {
        let f = Fixture::new("basic");
        let path = f.write(&base_files(), true);
        let epub = parse_epub(path.to_str().unwrap()).expect("parse should succeed");

        assert_eq!(epub.package_dir, "EPUB");
        assert_eq!(epub.chapters.len(), 2);
        let c1 = &epub.chapters[0];
        assert_eq!(c1.id, "c1");
        // nav 标题（含全角空格）解析进 toc；href 为归一化全路径
        assert_eq!(c1.title, "第一章　开始");
        assert_eq!(c1.href, "EPUB/text/ch1.xhtml");
        assert!(c1.html.contains("正文一"));
        // 第二章无 nav 条目 → fallback 文件名
        assert_eq!(epub.chapters[1].title, "ch2.xhtml");
        assert!(epub.chapters[1].html.contains("正文二"));
    }

    #[test]
    fn falls_back_to_ncx_when_nav_missing() {
        let f = Fixture::new("ncx");
        let files: Vec<(&str, &str)> = vec![
            ("mimetype", "application/epub+zip"),
            ("META-INF/container.xml", CONTAINER),
            ("EPUB/package.opf", OPF_WITH_NCX),
            ("EPUB/toc.ncx", NCX),
            ("EPUB/text/ch1.xhtml", CH1),
        ];
        let path = f.write(&files, true);
        let epub = parse_epub(path.to_str().unwrap()).expect("parse should succeed");
        assert_eq!(epub.chapters.len(), 1);
        assert_eq!(epub.chapters[0].title, "来自NCX的标题");
    }

    #[test]
    fn rejects_broken_archive() {
        let f = Fixture::new("broken");
        let path = f.dir.join("bad.epub");
        fs::write(&path, "not a zip").unwrap();
        assert!(parse_epub(path.to_str().unwrap()).is_err());
        // 缺 container → Err（上层回退 Kotlin legacy）
        let files: Vec<(&str, &str)> = vec![("mimetype", "application/epub+zip")];
        let path = f.write(&files, true);
        assert!(parse_epub(path.to_str().unwrap()).is_err());
    }

    #[test]
    fn json_shape_matches_kotlin_contract() {
        let f = Fixture::new("json");
        let path = f.write(&base_files(), true);
        let epub = parse_epub(path.to_str().unwrap()).unwrap();
        let json = serde_json::to_string(&epub).unwrap();
        // Kotlin NativeEpubJson 依赖这两个字段名
        assert!(json.contains("\"packageDir\":\"EPUB\""));
        assert!(json.contains("\"chapters\""));
        assert!(json.contains("\"href\":\"EPUB/text/ch1.xhtml\""));
    }

    #[test]
    fn path_resolution_matches_legacy_semantics() {
        // 与 Kotlin normalizePath/resolvePath 对齐：.. 归一、根路径、无 base
        let p = |base: &str, href: &str| parse::resolve_path_for_test(base, href);
        assert_eq!(p("EPUB", "text/ch1.xhtml"), "EPUB/text/ch1.xhtml");
        assert_eq!(p("EPUB/text", "../images/a.jpg"), "EPUB/images/a.jpg");
        assert_eq!(p("EPUB", "/abs/x.xhtml"), "abs/x.xhtml");
        assert_eq!(p("EPUB", "./sub/./y.xhtml"), "EPUB/sub/y.xhtml");
    }
}

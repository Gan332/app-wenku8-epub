package com.example.hyperreader.epub

import com.example.hyperreader.model.Book
import com.example.hyperreader.model.DownloadedImage
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * EPUB 3 文档生成：容器、OPF、目录、NCX 与章节 XHTML 全部是纯字符串函数。
 *
 * 与归档写出（[EpubArchiveWriter]）分离后，这些结构可以在 JVM 单测里直接断言，
 * 不用先落盘再解压；转义、清单顺序、卷级目录等回归点也更集中。
 */
internal object EpubDocumentWriter {

    fun containerXml() = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

    fun packageXml(
        book: Book,
        manifest: List<ManifestItem>,
        spine: List<String>,
    ): String = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id" xml:lang="zh-CN"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book-id">urn:uuid:${UUID.randomUUID()}</dc:identifier><dc:title>${escape(book.title)}</dc:title><dc:creator>${escape(book.author)}</dc:creator><dc:language>zh-CN</dc:language><dc:description>${escape(book.summary)}</dc:description><dc:source>${escape(book.sourceUrl)}</dc:source><meta property="dcterms:modified">${Instant.now().truncatedTo(ChronoUnit.SECONDS)}</meta></metadata><manifest>${manifest.joinToString("") { "<item id=\"${escape(it.id)}\" href=\"${escape(it.href)}\" media-type=\"${escape(it.mediaType)}\"${it.properties?.let { p -> " properties=\"${escape(p)}\"" } ?: ""}/>" }}</manifest><spine toc="ncx">${spine.joinToString("") { "<itemref idref=\"${escape(it)}\" linear=\"yes\"/>" }}</spine></package>"""

    fun chapterXml(chapter: ResolvedChapter): String {
        val content = chapter.blocks.joinToString("\n") { block ->
            when (block) {
                is ResolvedBlock.Text -> "      <p>${escape(block.value).replace("\n", "<br/>")}</p>"
                is ResolvedBlock.Rich -> "      <p>${block.html}</p>"
                is ResolvedBlock.Image -> "      <figure><img src=\"../images/${escape(block.image.fileName)}\" alt=\"插图 ${block.image.globalIndex + 1}\"/></figure>"
            }
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-CN" lang="zh-CN">
<head><meta charset="utf-8"/><title>${escape(chapter.title)}</title><link rel="stylesheet" href="style.css"/></head>
<body><section epub:type="chapter"><h1>${escape(chapter.title)}</h1>
$content
</section></body>
</html>"""
    }

    fun titleXml(book: Book, cover: DownloadedImage?): String {
        val image = cover?.let { "<img src=\"../images/${escape(it.fileName)}\" alt=\"${escape(book.title)} 封面\"/>" } ?: ""
        val summary = book.summary.lines().filter(String::isNotBlank).joinToString("\n") { "    <p>${escape(it)}</p>" }
        return """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-CN" lang="zh-CN">
<head><meta charset="utf-8"/><title>${escape(book.title)}</title><link rel="stylesheet" href="style.css"/></head>
<body><section class="title-page">$image<h1>${escape(book.title)}</h1><p class="author">${escape(book.author)}</p><section><h2>内容简介</h2>$summary</section></section></body>
</html>"""
    }

    /**
     * 目录导航。多卷时按**卷分组**（EPUB3 标准嵌套 `<ol>`，卷名用 `<span>` 不产生链接），
     * 单卷时保持平铺。
     *
     * 回读端（EpubReaderRepository）只取 `nav a[href]`，卷名是 span 无链接 → 链接数恒等于
     * 「书籍信息 + 章节数」，层级不影响应用内目录。
     */
    fun navXml(chapters: List<ResolvedChapter>): String {
        val head = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><meta charset="utf-8"/><title>目录</title></head><body><nav epub:type="toc"><h1>目录</h1><ol><li><a href="text/title.xhtml">书籍信息</a></li>"""
        val grouped = chapters.groupBy { it.volume.ifBlank { "正文" } }
        val body = if (grouped.size <= 1) {
            chapters.joinToString("") { "<li><a href=\"text/${escape(it.fileName)}\">${escape(it.title)}</a></li>" }
        } else {
            grouped.entries.joinToString("") { (volume, list) ->
                "<li><span>${escape(volume)}</span><ol>" +
                    list.joinToString("") { "<li><a href=\"text/${escape(it.fileName)}\">${escape(it.title)}</a></li>" } +
                    "</ol></li>"
            }
        }
        return head + body + "</ol></nav></body></html>"
    }

    fun ncxXml(book: Book, chapters: List<ResolvedChapter>): String = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd"><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="urn:uuid:${UUID.randomUUID()}"/></head><docTitle><text>${escape(book.title)}</text></docTitle><navMap><navPoint id="title" playOrder="1"><navLabel><text>书籍信息</text></navLabel><content src="text/title.xhtml"/></navPoint>${chapters.mapIndexed { index, chapter -> "<navPoint id=\"nav-${index + 1}\" playOrder=\"${index + 2}\"><navLabel><text>${escape(chapter.title)}</text></navLabel><content src=\"text/${escape(chapter.fileName)}\"/></navPoint>" }.joinToString("")}</navMap></ncx>"""

    fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")

    /**
     * 导出样式。**不写死任何颜色**（body 不出现 color/background 属性）：
     * 0.9.1 前是 `body{color:#272522}`，阅读器开深色模式时黑底黑字看不清 —— 文字颜色交给阅读器控。
     * 段落给间距+首行缩进+孤行控制；图片 `height:auto` 防止阅读器拉伸变形。
     *
     * `internal` 供单元测试断言关键排版规则不被回退。
     */
    internal val CSS = """
        @charset "utf-8";
        html{font-family:"MiSans","Source Han Sans SC","Noto Sans CJK SC","PingFang SC",sans-serif;-webkit-text-size-adjust:100%}
        body{margin:0;line-height:1.8}
        section{padding:1.2em}
        h1{font-size:1.35em;line-height:1.4;text-align:center;margin:0 0 1em}
        h2{font-size:1.15em;margin:1.2em 0 .6em}
        p{margin:.55em 0;text-indent:2em;text-align:justify;orphans:2;widows:2}
        b,strong{font-weight:700}
        i,em{font-style:italic}
        u{text-decoration:underline}
        s{text-decoration:line-through}
        sup{font-size:.75em;vertical-align:super}
        sub{font-size:.75em;vertical-align:sub}
        figure{margin:1.2em auto;text-align:center;max-width:100%}
        figure img{max-width:100%;height:auto;max-height:85vh}
        .title-page{text-align:center;min-height:85vh}
        .title-page img{max-width:70%;height:auto;margin:0 auto 1em}
        .title-page h1{margin-top:.8em}
        .title-page h2{text-align:center}
        .title-page .author{text-indent:0;text-align:center;letter-spacing:.15em;margin:.4em 0 1.6em}
    """.trimIndent()
}

internal data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val properties: String? = null,
)

/**
 * manifest 与 spine 的派生规则。两者必须严格对应：manifest 少一条 → 阅读器打不开，
 * spine 少一条 → 章节静默消失，因此放在一起生成并单独覆盖测试。
 */
internal object EpubPackageBuilder {
    fun manifest(
        chapters: List<ResolvedChapter>,
        cover: DownloadedImage?,
        images: List<DownloadedImage>,
    ): List<ManifestItem> = buildList {
        add(ManifestItem("nav", "nav.xhtml", "application/xhtml+xml", "nav"))
        add(ManifestItem("ncx", "toc.ncx", "application/x-dtbncx+xml"))
        add(ManifestItem("style", "text/style.css", "text/css"))
        add(ManifestItem("title-page", "text/title.xhtml", "application/xhtml+xml"))
        chapters.forEachIndexed { index, chapter ->
            add(ManifestItem("chapter-${index + 1}", "text/${chapter.fileName}", "application/xhtml+xml"))
        }
        (listOfNotNull(cover) + images).forEach {
            add(ManifestItem(it.manifestId, "images/${it.fileName}", it.mime, if (it.isCover) "cover-image" else null))
        }
    }

    fun spine(chapters: List<ResolvedChapter>): List<String> = buildList {
        add("title-page")
        chapters.forEachIndexed { index, _ -> add("chapter-${index + 1}") }
    }
}

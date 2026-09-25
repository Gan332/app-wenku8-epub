package com.wenku8.epubstudio.epub

import com.wenku8.epubstudio.core.Wenku8Exception
import com.wenku8.epubstudio.model.Book
import com.wenku8.epubstudio.model.ContentBlock
import com.wenku8.epubstudio.model.DownloadedImage
import com.wenku8.epubstudio.model.OutputFile
import com.wenku8.epubstudio.model.ParsedChapter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubBuilder {
    fun build(book: Book, chapters: List<ParsedChapter>, images: List<DownloadedImage>, cover: DownloadedImage?, output: File): OutputFile {
        val resolved = resolveChapters(chapters, images)
        val manifest = mutableListOf<ManifestItem>()
        val spine = mutableListOf("title-page")
        manifest += ManifestItem("nav", "nav.xhtml", "application/xhtml+xml", "nav")
        manifest += ManifestItem("ncx", "toc.ncx", "application/x-dtbncx+xml")
        manifest += ManifestItem("style", "text/style.css", "text/css")
        manifest += ManifestItem("title-page", "text/title.xhtml", "application/xhtml+xml")
        resolved.forEachIndexed { index, chapter ->
            val id = "chapter-${index + 1}"
            manifest += ManifestItem(id, "text/${chapter.fileName}", "application/xhtml+xml")
            spine += id
        }
        (listOfNotNull(cover) + images).forEach { manifest += ManifestItem(it.manifestId, "images/${it.fileName}", it.mime, if (it.isCover) "cover-image" else null) }

        val temporary = File(output.parentFile, "${output.name}.tmp")
        ZipOutputStream(FileOutputStream(temporary), StandardCharsets.UTF_8).use { zip ->
            writeStored(zip, "mimetype", "application/epub+zip".toByteArray(StandardCharsets.UTF_8))
            writeText(zip, "META-INF/container.xml", containerXml())
            writeText(zip, "EPUB/package.opf", packageXml(book, manifest, spine))
            writeText(zip, "EPUB/nav.xhtml", navXml(resolved))
            writeText(zip, "EPUB/toc.ncx", ncxXml(book, resolved))
            writeText(zip, "EPUB/text/style.css", CSS)
            writeText(zip, "EPUB/text/title.xhtml", titleXml(book, cover))
            resolved.forEach { writeText(zip, "EPUB/text/${it.fileName}", chapterXml(it)) }
            (listOfNotNull(cover) + images).forEach { image ->
                val source = File(image.localPath)
                if (!source.exists()) throw Wenku8Exception("图片缓存不存在：${image.fileName}", "IMAGE_CACHE_MISSING")
                zip.putNextEntry(ZipEntry("EPUB/images/${image.fileName}"))
                FileInputStream(source).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        if (output.exists() && !output.delete()) throw Wenku8Exception("无法覆盖已有 EPUB。", "EPUB_REPLACE_FAILED")
        if (!temporary.renameTo(output)) {
            temporary.copyTo(output, overwrite = true)
            temporary.delete()
        }
        return OutputFile(output.name, output.length(), output.toURI().toString(), output.absolutePath)
    }

    private fun writeStored(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        val crc = CRC32().apply { update(bytes) }
        entry.crc = crc.value
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }

    private fun resolveChapters(chapters: List<ParsedChapter>, images: List<DownloadedImage>): List<ResolvedChapter> = chapters.mapIndexed { index, chapter ->
        val byChapter = images.filter { it.sourceId == chapter.id }
        val blocks = chapter.blocks.mapNotNull { block ->
            when (block) {
                is ContentBlock.Text -> ResolvedBlock.Text(block.value)
                is ContentBlock.Image -> byChapter.firstOrNull { it.chapterIndex == block.index }?.let { ResolvedBlock.Image(it) }
            }
        }
        ResolvedChapter(chapter.id, chapter.title, "chapter-${String.format("%04d", index + 1)}.xhtml", blocks)
    }

    private fun chapterXml(chapter: ResolvedChapter): String {
        val content = chapter.blocks.joinToString("\n") { block ->
            when (block) {
                is ResolvedBlock.Text -> "      <p>${escape(block.value).replace("\n", "<br/>")}</p>"
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

    private fun titleXml(book: Book, cover: DownloadedImage?): String {
        val image = cover?.let { "<img src=\"../images/${escape(it.fileName)}\" alt=\"${escape(book.title)} 封面\"/>" } ?: ""
        val summary = book.summary.lines().filter(String::isNotBlank).joinToString("\n") { "    <p>${escape(it)}</p>" }
        return """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-CN" lang="zh-CN">
<head><meta charset="utf-8"/><title>${escape(book.title)}</title><link rel="stylesheet" href="style.css"/></head>
<body><section class="title-page">$image<h1>${escape(book.title)}</h1><p>${escape(book.author)}</p><section><h2>内容简介</h2>$summary</section></section></body>
</html>"""
    }

    private fun navXml(chapters: List<ResolvedChapter>): String = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><meta charset="utf-8"/><title>目录</title></head><body><nav epub:type="toc"><h1>目录</h1><ol><li><a href="text/title.xhtml">书籍信息</a></li>${chapters.joinToString("") { "<li><a href=\"text/${escape(it.fileName)}\">${escape(it.title)}</a></li>" }}</ol></nav></body></html>"""

    private fun ncxXml(book: Book, chapters: List<ResolvedChapter>): String = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd"><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="urn:uuid:${java.util.UUID.randomUUID()}"/></head><docTitle><text>${escape(book.title)}</text></docTitle><navMap><navPoint id="title" playOrder="1"><navLabel><text>书籍信息</text></navLabel><content src="text/title.xhtml"/></navPoint>${chapters.mapIndexed { index, chapter -> "<navPoint id=\"nav-${index + 1}\" playOrder=\"${index + 2}\"><navLabel><text>${escape(chapter.title)}</text></navLabel><content src=\"text/${escape(chapter.fileName)}\"/></navPoint>" }.joinToString("")}</navMap></ncx>"""

    private fun packageXml(book: Book, manifest: List<ManifestItem>, spine: List<String>): String = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id" xml:lang="zh-CN"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book-id">urn:uuid:${java.util.UUID.randomUUID()}</dc:identifier><dc:title>${escape(book.title)}</dc:title><dc:creator>${escape(book.author)}</dc:creator><dc:language>zh-CN</dc:language><dc:description>${escape(book.summary)}</dc:description><dc:source>${escape(book.sourceUrl)}</dc:source><meta property="dcterms:modified">${java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)}</meta></metadata><manifest>${manifest.joinToString("") { "<item id=\"${escape(it.id)}\" href=\"${escape(it.href)}\" media-type=\"${escape(it.mediaType)}\"${it.properties?.let { p -> " properties=\"${escape(p)}\"" } ?: ""}/>" }}</manifest><spine toc="ncx">${spine.joinToString("") { "<itemref idref=\"${escape(it)}\" linear=\"yes\"/>" }}</spine></package>"""

    private fun containerXml() = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private data class ManifestItem(val id: String, val href: String, val mediaType: String, val properties: String? = null)
    private data class ResolvedChapter(val id: String, val title: String, val fileName: String, val blocks: List<ResolvedBlock>)
    private sealed class ResolvedBlock {
        data class Text(val value: String) : ResolvedBlock()
        data class Image(val image: DownloadedImage) : ResolvedBlock()
    }

    companion object {
        private val CSS = """@charset "utf-8";html{font-family:"MiSans",sans-serif}body{margin:0;color:#272522;line-height:1.8}section{padding:1.2em}h1{text-align:center}p{text-indent:2em;text-align:justify}figure{text-align:center;margin:1.4em 0}figure img{max-width:100%;max-height:90vh}.title-page{text-align:center;min-height:85vh}"""
    }
}

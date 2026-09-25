package com.wenku8.epubstudio.reader

import android.content.Context
import android.net.Uri
import com.wenku8.epubstudio.core.Wenku8Exception
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipFile

class EpubReaderRepository(private val context: Context? = null) {
    fun open(bookId: String, uri: Uri): ReaderBook {
        val appContext = context?.applicationContext ?: throw Wenku8Exception("阅读器上下文不可用。", "EPUB_CONTEXT_MISSING")
        val file = copyToCache(appContext, uri)
        return runCatching { parseArchive(bookId, file) }.getOrElse { error ->
            if (error is Wenku8Exception) throw error
            throw Wenku8Exception("无法打开 EPUB：${error.message ?: "文件格式无效"}", "EPUB_PARSE_FAILED", error)
        }
    }

    private fun copyToCache(context: Context, uri: Uri): File {
        val directory = File(context.cacheDir, "reader").apply { mkdirs() }
        val target = File(directory, "${uri.toString().hashCode().toUInt().toString(16)}.epub")
        if (target.exists() && target.length() in 1..MAX_EPUB_BYTES) return target
        val input = if (uri.scheme == "file") FileInputStream(File(requireNotNull(uri.path))) else context.contentResolver.openInputStream(uri)
            ?: throw Wenku8Exception("无法读取 EPUB 文件。", "EPUB_OPEN_FAILED")
        input.use { source -> target.outputStream().use { output -> source.copyTo(output) } }
        if (target.length() > MAX_EPUB_BYTES) {
            target.delete()
            throw Wenku8Exception("EPUB 文件过大。", "EPUB_TOO_LARGE")
        }
        return target
    }

    internal fun parseArchive(bookId: String, file: File): ReaderBook {
        ZipFile(file).use { zip ->
            val entries = linkedMapOf<String, ByteArray>()
            var total = 0L
            val iterator = zip.entries().asSequence()
            for (entry in iterator) {
                if (entry.isDirectory) continue
                if (entry.size > MAX_ENTRY_BYTES) throw Wenku8Exception("EPUB 内部文件过大。", "EPUB_ENTRY_TOO_LARGE")
                val bytes = zip.getInputStream(entry).use { readLimited(it, MAX_ENTRY_BYTES) }
                total += bytes.size
                if (total > MAX_EPUB_BYTES) throw Wenku8Exception("EPUB 解压后过大。", "EPUB_UNZIP_LIMIT")
                entries[entry.name] = bytes
            }
            val container = parseXml(text(entries, "META-INF/container.xml"))
            val packagePath = container.selectFirst("rootfile[full-path]")?.attr("full-path")
                ?: throw Wenku8Exception("EPUB 缺少 container.xml。", "EPUB_CONTAINER_MISSING")
            val packageDoc = parseXml(text(entries, normalizePath(packagePath)))
            val packageDir = packagePath.substringBeforeLast('/', "")
            val manifest = packageDoc.select("manifest item").associate { item ->
                val id = item.attr("id")
                id to ReaderManifestItem(id, resolvePath(packageDir, item.attr("href")), item.attr("media-type"), item.attr("properties"))
            }
            val title = packageDoc.select("metadata > *").firstOrNull { it.tagName().substringAfter(':').equals("title", ignoreCase = true) }?.text()?.trim().orEmpty().ifBlank { "EPUB 阅读" }
            val author = packageDoc.select("metadata > *").firstOrNull { it.tagName().substringAfter(':').equals("creator", ignoreCase = true) }?.text()?.trim().orEmpty()
            val language = packageDoc.select("metadata > *").firstOrNull { it.tagName().substringAfter(':').equals("language", ignoreCase = true) }?.text()?.trim().orEmpty().ifBlank { "zh-CN" }
            val toc = readToc(entries, packageDir, manifest)
            val chapters = packageDoc.select("spine itemref").mapNotNull { ref ->
                val item = manifest[ref.attr("idref")] ?: return@mapNotNull null
                if (!item.mediaType.contains("html", true)) return@mapNotNull null
                val chapterTitle = toc[item.href] ?: item.href.substringAfterLast('/')
                val html = text(entries, item.href)
                ReaderChapter(item.id, chapterTitle, item.href, parseBlocks(html, item.href, packageDir))
            }
            if (chapters.isEmpty()) throw Wenku8Exception("EPUB 没有可阅读章节。", "EPUB_NO_CHAPTERS")
            return ReaderBook(bookId, title, author, language, chapters, file.absolutePath)
        }
    }

    private fun readToc(entries: Map<String, ByteArray>, packageDir: String, manifest: Map<String, ReaderManifestItem>): Map<String, String> {
        val nav = manifest.values.firstOrNull { it.properties.split(Regex("\\s+")).contains("nav") }
        if (nav != null) {
            val doc = Jsoup.parse(text(entries, nav.href), nav.href, Parser.htmlParser())
            return doc.select("nav[epub:type=toc] a[href], nav a[href]").associate { anchor ->
                resolvePath(packageDir, anchor.attr("href")) to anchor.text().trim()
            }
        }
        val ncx = manifest.values.firstOrNull { it.mediaType.contains("dtbncx", true) } ?: return emptyMap()
        val doc = parseXml(text(entries, ncx.href))
        return doc.select("navPoint").associate { point ->
            val href = point.selectFirst("content")?.attr("src").orEmpty()
            resolvePath(packageDir, href) to point.selectFirst("navLabel text")?.text()?.trim().orEmpty()
        }
    }

    private fun parseBlocks(html: String, chapterPath: String, packageDir: String): List<ReaderBlock> {
        val document = Jsoup.parse(html, chapterPath, Parser.htmlParser())
        document.select("script,style,noscript,iframe,object,embed").remove()
        val body = document.body() ?: return emptyList()
        val blocks = mutableListOf<ReaderBlock>()
        for (element in body.children()) {
            when (element.tagName().lowercase()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> blocks += ReaderBlock.Heading(element.tagName().removePrefix("h").toIntOrNull() ?: 1, element.text().trim())
                "p" -> blocks += ReaderBlock.Paragraph(element.text().trim())
                "img" -> {
                    val src = element.attr("src")
                    if (src.isNotBlank() && !src.startsWith("data:")) blocks += ReaderBlock.Image(resolvePath(packageDir, resolveRelative(chapterPath, src)), element.attr("alt"))
                }
                else -> {
                    val text = element.text().trim()
                    if (text.isNotBlank()) blocks += ReaderBlock.Paragraph(text)
                }
            }
        }
        if (blocks.isEmpty()) {
            val text = body.text().trim()
            if (text.isNotBlank()) blocks += ReaderBlock.Paragraph(text)
        }
        return blocks.filter { it !is ReaderBlock.Paragraph || it.text.isNotBlank() }
    }

    private fun text(entries: Map<String, ByteArray>, path: String): String = entries[normalizePath(path)]?.toString(Charsets.UTF_8)
        ?: throw Wenku8Exception("EPUB 缺少文件：$path", "EPUB_ENTRY_MISSING")

    private fun parseXml(value: String): Document = Jsoup.parse(value, "", Parser.xmlParser())

    private fun normalizePath(path: String): String {
        val segments = mutableListOf<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeLast()
                else -> segments += part
            }
        }
        return segments.joinToString("/")
    }

    private fun resolvePath(baseDir: String, href: String): String {
        if (href.startsWith("/")) return normalizePath(href)
        return normalizePath("$baseDir/$href")
    }

    private fun resolveRelative(chapterPath: String, href: String): String {
        val base = chapterPath.substringBeforeLast('/', "")
        return "$base/$href"
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var count = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            count += read
            if (count > maxBytes) throw Wenku8Exception("EPUB 内部文件过大。", "EPUB_ENTRY_TOO_LARGE")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_EPUB_BYTES = 200L * 1024 * 1024
        const val MAX_ENTRY_BYTES = 30 * 1024 * 1024
    }
}

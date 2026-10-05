package com.example.hyperreader.reader

import android.content.Context
import android.net.Uri
import com.example.hyperreader.core.Wenku8Exception
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
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
        // 1) 优先走 Rust 结构解析（快路径）：so 存在且 JSON 解析成功才用；
        //    任何一步失败（so 缺失/结构异常/解码失败/限额拒绝）都回退 legacy ——
        //    native 是加速路径，不是单点依赖。
        if (EpubNative.available) {
            val json = runCatching { EpubNative.parse(file.absolutePath) }.getOrNull()
            if (json != null) {
                runCatching { fromNativeJson(bookId, json, file) }.getOrNull()?.let { return it }
            }
        }
        return parseArchiveLegacy(bookId, file)
    }

    /** native JSON → ReaderBook；HTML→blocks 复用同一套 Jsoup 逻辑，与 legacy 行为一致。 */
    internal fun fromNativeJson(bookId: String, json: String, file: File): ReaderBook {
        val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(NativeEpubJson.serializer(), json)
        if (parsed.chapters.isEmpty()) throw Wenku8Exception("EPUB 没有可阅读章节。", "EPUB_NO_CHAPTERS")
        val chapters = parsed.chapters.map { chapter ->
            ReaderChapter(chapter.id, chapter.title, chapter.href, parseBlocks(chapter.html, chapter.href))
        }
        return ReaderBook(bookId, parsed.title, parsed.author, parsed.language, chapters, file.absolutePath)
    }

    private fun parseArchiveLegacy(bookId: String, file: File): ReaderBook {
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
                ReaderChapter(item.id, chapterTitle, item.href, parseBlocks(html, item.href))
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

    /**
     * 章节 HTML → 内容块。
     *
     * **递归下降**，不是「只扫 body 的直接子元素」：本应用自己导出的 EPUB 必然套一层容器
     * （CLASSIC 的 `<body><section epub:type="chapter">`、POTATO 的 `<div id="content">`），
     * 图片又额外包一层 `<figure>` / `<div class="div_image">`。只扫一层的后果是整章塌成
     * 一个大段落、**插图全部消失**、扉页封面也不显示。
     *
     * 规则：
     * - `img` 在任意深度都产出 [ReaderBlock.Image]；
     * - 容器元素（[CONTAINER_TAGS]）与「后代含 img」「直接子元素是容器」的元素递归下钻，
     *   下钻时把散落在子元素之间的文本按出现顺序攒成段落，不丢也不乱序；
     * - 其余（`p` / `span` / `b` / `a` …）按整体文本并入当前段落，与原行为一致。
     */
    private fun parseBlocks(html: String, chapterPath: String): List<ReaderBlock> {
        val document = Jsoup.parse(html, chapterPath, Parser.htmlParser())
        document.select("script,style,noscript,iframe,object,embed").remove()
        val body = document.body() ?: return emptyList()
        val blocks = mutableListOf<ReaderBlock>()
        collectBlocks(body, chapterPath, blocks)
        if (blocks.isEmpty()) {
            // 结构化解析一无所获（纯文本章节）：退回整体文本，至少正文可读
            val text = body.text().trim()
            if (text.isNotBlank()) blocks += ReaderBlock.Paragraph(text)
        }
        return blocks.filter { it !is ReaderBlock.Paragraph || it.text.isNotBlank() }
    }

    private fun collectBlocks(parent: Element, chapterPath: String, out: MutableList<ReaderBlock>) {
        val pending = StringBuilder()

        fun flush() {
            val text = pending.toString().replace(WHITESPACE_RUN, " ").trim()
            pending.setLength(0)
            if (text.isNotEmpty()) out += ReaderBlock.Paragraph(text)
        }

        for (node in parent.childNodes()) {
            when (node) {
                is TextNode -> pending.append(node.text())
                is Element -> when (node.tagName().lowercase()) {
                    "br" -> pending.append(' ')
                    // 分隔线没有对应块类型，直接断开当前段落即可
                    "hr" -> flush()
                    "img" -> {
                        flush()
                        imageSource(node)?.let { out += ReaderBlock.Image(resolveImage(chapterPath, it), node.attr("alt")) }
                    }
                    else ->
                        if (needsRecursion(node)) {
                            flush()
                            collectBlocks(node, chapterPath, out)
                        } else {
                            // 行内元素（span / a / b / em …）：文本并入当前段落，顺序不丢
                            pending.append(node.text())
                        }
                }
                else -> Unit
            }
        }
        flush()
    }

    /** 是否需要下钻：容器标签、后代含 img、或直接子元素里有容器。 */
    private fun needsRecursion(node: Element): Boolean =
        node.tagName().lowercase() in CONTAINER_TAGS ||
            node.select("img").isNotEmpty() ||
            node.children().any { it.tagName().lowercase() in CONTAINER_TAGS }

    /**
     * 图片地址：优先 `src`，懒加载格式回退到 `data-src` / `data-original`。
     *
     * 本应用导出的 EPUB 用 `src`；外部 EPUB（尤其是网页另存的）常用懒加载属性，
     * 只认 `src` 会读到空串而整张丢图。`data:` 与空值一律跳过。
     */
    private fun imageSource(node: Element): String? =
        listOf("src", "data-src", "data-original", "data-lazy-src")
            .map { node.attr(it).trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("data:") }

    /**
     * 图片条目路径：`chapterPath` 已是 zip 根相对全路径，src 相对它归一即可。
     *
     * 0.9.x 曾在 resolveRelative 之后**再**拼一次 packageDir，产出
     * `EPUB/EPUB/images/…` 双重前缀 —— zip 查不到条目，
     * 表现为「导出的 EPUB 在阅读器里图片不显示」。
     */
    private fun resolveImage(chapterPath: String, src: String): String {
        if (src.startsWith("/")) return normalizePath(src)
        val base = chapterPath.substringBeforeLast('/', "")
        return normalizePath("$base/$src")
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

        /** 需要下钻的容器标签：正文骨架、图片包裹层与常见块级结构。 */
        private val CONTAINER_TAGS = setOf(
            "p", "div", "section", "article", "main", "aside", "header", "footer",
            "figure", "figcaption", "ul", "ol", "li", "dl", "dt", "dd",
            "table", "thead", "tbody", "tfoot", "tr", "td", "th",
            "blockquote", "pre", "center", "h1", "h2", "h3", "h4", "h5", "h6", "body",
        )

        /** 段落内的连续空白（含源码缩进与换行）压成单个空格。 */
        private val WHITESPACE_RUN = Regex("\\s+")
    }
}

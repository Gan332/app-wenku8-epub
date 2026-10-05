package com.example.hyperreader.core

import com.example.hyperreader.model.Book
import com.example.hyperreader.model.BookIndex
import com.example.hyperreader.model.Chapter
import com.example.hyperreader.model.ContentBlock
import com.example.hyperreader.model.SearchBook
import com.example.hyperreader.model.SearchField
import com.example.hyperreader.model.ParsedChapter
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

object Wenku8Parser {
    /** `em#pagestats` 里的「当前页/总页数」形式，如 `2/5`、`2 / 5`。 */
    private val PAGE_SLASH = Regex("(\\d+)\\s*/\\s*(\\d+)")

    /** `em#pagestats` 里的「共N页」形式。 */
    private val PAGE_TOTAL = Regex("(?:共|計|计)\\s*(\\d+)\\s*页?")

    data class SearchPageData(
        val books: List<SearchBook>,
        val page: Int,
        val pageCount: Int?,
        val hasNextPage: Boolean,
    )

    fun looksLikeChallenge(html: String): Boolean = Regex("<title[^>]*>\\s*(Just a moment|Attention Required)|Checking your browser|cf-chl-|__cf_chl", RegexOption.IGNORE_CASE).containsMatchIn(html.take(20_000))

    /**
     * 解析出口统一把 URL 还原成 wenku8 原域。
     *
     * 启用第三方中继时，页面里的链接会带着中继 host（见 [Wenku8Endpoint]）；
     * 书架、EPUB 内链与再次抓取都必须使用 wenku8 原域，否则导出文件会指向第三方。
     */
    private fun direct(url: String?): String? = url?.let { Wenku8Endpoint.restoreToDirect(it) }

    fun parseBook(html: String, bookUrl: String, requestedDirectoryUrl: String? = null): Book {
        if (looksLikeChallenge(html)) throw Wenku8Exception("源站要求浏览器验证。", "UPSTREAM_CHALLENGE")
        val document = Jsoup.parse(html, bookUrl)
        val content = document.selectFirst("#content") ?: throw Wenku8Exception("未能识别书籍页面。", "BOOK_PARSE_FAILED")
        val title = cleanInline(content.selectFirst("td[colspan=5] b")?.text())
            .ifBlank { document.title().substringBefore(" - ").trim() }
            .ifBlank { "未命名轻小说" }
        val directoryNode = content.selectFirst("a[href*=/novel/][href*=index.htm]")
        val directoryUrl = requestedDirectoryUrl ?: directoryNode?.let { Wenku8Url.resolve(bookUrl, it.attr("href")) }
        val coverNode = content.selectFirst("img[src*=/image/]")
        val summary = document.select(".hottext").firstOrNull { cleanInline(it.text()).contains("内容简介") }?.text()
        val tags = document.select(".hottext").firstOrNull { cleanInline(it.text()) == "作品Tags" }?.text().orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() }
        val ids = directoryUrl?.let { Wenku8Url.sourceIds(java.net.URI(it)) } ?: Wenku8Url.sourceIds(java.net.URI(bookUrl))
        return Book(
            id = ids.bookId,
            title = title,
            author = fieldValue(document, "小说作者", "小说作者：", "小说作者:").ifBlank { "未知作者" },
            category = cleanInline(content.selectFirst("a[href*=articlelist.php]")?.text()).ifBlank { "轻小说" },
            status = fieldValue(document, "文章状态", "文章状态：", "文章状态:"),
            updatedAt = fieldValue(document, "最后更新", "最后更新：", "最后更新:"),
            wordCount = parseWordCount(document),
            latestChapter = fieldValue(document, "最新章节", "最新章节：", "最新章节:").ifBlank { content.selectFirst("a[href*=/novel/][href$=index.htm]")?.text().orEmpty() },
            isComplete = fieldValue(document, "文章状态", "文章状态：", "文章状态:").contains("完结"),
            tags = tags,
            summary = cleanText(summary).replace(Regex("^内容简介\\s*[：:]\\s*"), ""),
            coverUrl = direct(Wenku8Url.resolve(bookUrl, coverNode?.attr("src"))),
            sourceUrl = direct(bookUrl) ?: bookUrl,
            bookUrl = direct(bookUrl) ?: bookUrl,
            directoryUrl = direct(directoryUrl),
        )
    }

    fun parseIndex(html: String, finalUrl: String, bookId: String? = null): BookIndex {
        if (looksLikeChallenge(html)) throw Wenku8Exception("源站要求浏览器验证。", "UPSTREAM_CHALLENGE")
        val document = Jsoup.parse(html, finalUrl)
        val chapters = mutableListOf<Chapter>()
        val seen = mutableSetOf<String>()
        var volume = "正文"
        for (row in document.select("table tr")) {
            val volumeNode = row.selectFirst("td.vcss")
            if (volumeNode != null) {
                cleanInline(volumeNode.text()).ifBlank { "正文" }.let { volume = it }
                continue
            }
            for (anchor in row.select("td.ccss a[href]")) {
                val title = cleanInline(anchor.text())
                if (title.isBlank() || title in setOf("上一页", "下一页", "返回目录")) continue
                val url = direct(Wenku8Url.resolve(finalUrl, anchor.attr("href"))) ?: continue
                runCatching { Wenku8Url.assertAllowed(url) }
                if (!seen.add(url)) continue
                val id = Regex("/(\\d+)\\.html?$", RegexOption.IGNORE_CASE).find(java.net.URI(url).path.orEmpty())?.groupValues?.get(1) ?: (chapters.size + 1).toString()
                chapters += Chapter(id, title, url, volume, chapters.size + 1, Regex("插图|插畫|彩页|彩頁").containsMatchIn(title))
            }
        }
        if (chapters.isEmpty()) throw Wenku8Exception("目录中没有识别到章节。", "NO_CHAPTERS")
        return BookIndex(document.title().substringBefore(" - ").trim().ifBlank { "未命名轻小说" }, finalUrl, bookId ?: Wenku8Url.sourceIds(java.net.URI(finalUrl)).bookId, chapters)
    }

    /**
     * @param preserveInlineFormat true 时保留正文**内联强调**（粗/斜/下划线等，
     *   见 [ContentBlock.Rich]），仅导出链路使用；在线阅读保持默认 false，
     *   行为与旧版完全一致（纯文本），零回归风险。
     */
    fun parseChapter(html: String, chapter: Chapter, pageUrl: String, preserveInlineFormat: Boolean = false): ParsedChapter {
        Wenku8Url.assertAllowed(chapter.url)
        if (looksLikeChallenge(html)) throw Wenku8Exception("源站要求浏览器验证。", "UPSTREAM_CHALLENGE")
        val document = Jsoup.parse(html, pageUrl)
        val source = document.selectFirst("#content") ?: throw Wenku8Exception("未能识别章节正文：${chapter.title}", "CHAPTER_PARSE_FAILED")
        val root = source.clone()
        root.select("#contentdp,script,style,iframe,object,embed,form,input,button,noscript,link,meta").remove()
        root.select("[id^=adv],[class*=advert],[class*=banner]").remove()
        val imageUrls = mutableListOf<String>()
        for (image in root.select("img").toList()) {
            val url = direct(imageSource(image, pageUrl))
            val width = image.attr("width").toIntOrNull() ?: 0
            val height = image.attr("height").toIntOrNull() ?: 0
            if (url == null || (width in 1..3) || (height in 1..3) || Regex("spacer|blank\\.(gif|png)|pixel", RegexOption.IGNORE_CASE).containsMatchIn(image.attr("src"))) {
                image.remove()
                continue
            }
            val index = imageUrls.size
            imageUrls += url
            image.before(TextNode("\n@@WENKU8_IMAGE_$index@@\n"))
            image.remove()
        }
        root.select("br").forEach { it.before(TextNode("\n")); it.remove() }
        root.select("p").forEach { it.append("\n") }
        val blocks = if (preserveInlineFormat) {
            normalizeRichLines(extractRichLines(root), imageUrls.size)
        } else {
            normalizeBlocks(root.text(), imageUrls.size)
        }
        val plain = blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.value } +
            blocks.filterIsInstance<ContentBlock.Rich>().joinToString("") { stripRichHtml(it.html) }
        if (plain.length < 10 && imageUrls.isEmpty()) throw Wenku8Exception("章节正文为空：${chapter.title}", "EMPTY_CHAPTER")
        return ParsedChapter(chapter.id, chapter.title, chapter.volume, chapter.order, pageUrl, imageUrls, blocks, plain.length)
    }

    /**
     * 图片地址：先算懒加载基准，再按 `data-original > data-src > data-lazy-src > src` 取值。
     *
     * `data-base` 是源站给懒加载图用的**基准目录**：彩页常用
     * `data-base="/image-root/" data-src="cover/a.jpg"`，此时相对地址必须以 `data-base`
     * 为基准而非章节 URL。少了这一步这类插图**在导出期就解析不出来**，成品 EPUB 里整章无图，
     * 而告警只会含糊地说「未能下载」，用户无法判断是源站问题还是解析错了。
     *
     * 与 Web 版 `src/wenku8.js` 的 `imageSource` 同源，回归见
     * `ExportPipelineTest.resolvesLazyImageAgainstDataBase`。
     */
    private fun imageSource(image: Element, pageUrl: String): String? {
        val dataBase = Wenku8Url.resolve(pageUrl, image.attr("data-base")) ?: pageUrl
        return sequenceOf("data-original", "data-src", "data-lazy-src", "src")
            .mapNotNull { Wenku8Url.resolve(dataBase, image.attr(it)) }
            .firstOrNull()
    }

    /** 强调白名单：输出的标签**全部由代码生成**，结构上不存在属性注入面。 */
    private val EMPHASIS_TAGS = setOf("b", "strong", "i", "em", "u", "s", "sup", "sub")

    /**
     * 把清理后的正文 DOM 提取为「`\n` 分隔的行，行内为转义文本 + 白名单强调标签」。
     *
     * - 文本节点：空白折叠 + `&<>` 转义（后续原样写入 XHTML，安全）
     * - 强调标签（b/i/…）与带强调 style 的元素（`font-weight:bold→b` 等）保留
     * - 其余元素一律**展开**（a/span/font 等只留内容，属性全部丢弃）
     * - 块级（p/div/li/标题）展开内容并断行，还原网页段落结构
     */
    private fun extractRichLines(root: Element): String {
        val builder = StringBuilder()
        fun appendText(text: String) {
            builder.append(text.replace(Regex("\\s+"), " ").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
        }
        fun walk(node: org.jsoup.nodes.Node) {
            when (node) {
                is TextNode -> appendText(node.text())
                is Element -> {
                    val tag = node.tagName().lowercase()
                    val styleTags = styleEmphasisTags(node.attr("style"))
                    when {
                        tag == "br" -> builder.append('\n')
                        tag in EMPHASIS_TAGS -> {
                            builder.append('<').append(tag).append('>')
                            node.childNodes().forEach { walk(it) }
                            builder.append("</").append(tag).append('>')
                        }
                        styleTags.isNotEmpty() -> {
                            styleTags.forEach { builder.append('<').append(it).append('>') }
                            node.childNodes().forEach { walk(it) }
                            styleTags.asReversed().forEach { builder.append("</").append(it).append('>') }
                        }
                        tag in setOf("p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6") -> {
                            node.childNodes().forEach { walk(it) }
                            // 双换行 = 段落边界（normalizeRichLines 按空行 flush）；
                            // 单换行只表示块内软换行，与原版 root.text() 的行为对齐
                            builder.append("\n\n")
                        }
                        else -> node.childNodes().forEach { walk(it) }
                    }
                }
                else -> Unit // Comment / 其他节点忽略
            }
        }
        root.childNodes().forEach { walk(it) }
        return builder.toString()
    }

    /** style 属性 → 强调标签（按 权重/斜体/下划线 顺序，可嵌套）。 */
    private fun styleEmphasisTags(style: String): List<String> {
        if (style.isBlank()) return emptyList()
        val s = style.lowercase()
        val tags = mutableListOf<String>()
        if ("font-weight" in s && Regex("bold|[7-9]00|bolder").containsMatchIn(s)) tags += "b"
        if ("font-style" in s && "italic" in s) tags += "i"
        if ("text-decoration" in s && "underline" in s) tags += "u"
        return tags
    }

    /** 与 [normalizeBlocks] 同构，但行内保留白名单 HTML；含标签的段落产出 [ContentBlock.Rich]。 */
    private fun normalizeRichLines(lines: String, imageCount: Int): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        val paragraph = StringBuilder()
        fun flush() {
            val value = paragraph.toString().trim()
            if (value.isNotEmpty()) {
                blocks += if ('<' in value) ContentBlock.Rich(value) else ContentBlock.Text(value)
            }
            paragraph.setLength(0)
        }
        lines.replace("\r", "").replace(' ', ' ').split('\n').forEach { raw ->
            val line = raw.trim()
            val marker = Regex("^@@WENKU8_IMAGE_(\\d+)@@$").matchEntire(line)
            if (marker != null) {
                flush()
                marker.groupValues[1].toInt().takeIf { it < imageCount }?.let { blocks += ContentBlock.Image(it) }
            } else if (line.isBlank()) flush() else {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line)
            }
        }
        flush()
        return blocks
    }

    /** 去标签取纯文本（在线端降级与字数统计共用）。 */
    internal fun stripRichHtml(html: String): String = Jsoup.parseBodyFragment(html).text().trim()

    private fun normalizeBlocks(text: String, imageCount: Int): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        val paragraph = StringBuilder()
        fun flush() {
            val value = paragraph.toString().replace(Regex("\\s+"), " ").trim()
            if (value.isNotEmpty()) blocks += ContentBlock.Text(value)
            paragraph.setLength(0)
        }
        text.replace("\r", "").replace('\u00a0', ' ').split('\n').forEach { raw ->
            val line = raw.trim()
            val marker = Regex("^@@WENKU8_IMAGE_(\\d+)@@$").matchEntire(line)
            if (marker != null) {
                flush()
                marker.groupValues[1].toInt().takeIf { it < imageCount }?.let { blocks += ContentBlock.Image(it) }
            } else if (line.isBlank()) flush() else paragraph.append(line).append(' ')
        }
        flush()
        return blocks
    }

    fun looksLikeLoginPage(html: String): Boolean {
        val head = html.take(30_000)
        return Regex("<title[^>]*>[^<]*(登录|login)|name=[\"']username[\"']|action=[\"'][^\"']*login", RegexOption.IGNORE_CASE).containsMatchIn(head)
    }

    fun parseSearchResults(html: String, finalUrl: String): List<SearchBook> {
        if (looksLikeLoginPage(html)) throw Wenku8Exception("搜索需要登录轻小说文库。", "AUTH_REQUIRED")
        val document = Jsoup.parse(html, finalUrl)
        val results = mutableListOf<SearchBook>()
        val seen = mutableSetOf<String>()
        for (anchor in document.select("a[href*=/book/],a[href*=articleinfo.php]")) {
            val href = anchor.attr("href")
            val id = Regex("/book/(\\d+)\\.htm", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1)
                ?: Regex("[?&]id=(\\d+)", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1)
                ?: continue
            if (id in seen) continue
            val container = anchor.parents().firstOrNull { it.tagName() in setOf("tr", "li", "div") } ?: anchor.parent() ?: continue
            val raw = cleanInline(container.text())
            val title = cleanInline(anchor.text()).ifBlank { cleanInline(container.selectFirst("a[href*=/book/]")?.text()) }.ifBlank { continue }
            // 标题取到后才占位：tags.php / toplist.php 的卡片先出现无文字的封面链接，
            // 若此时就记 id，后面真正带标题的链接会被当成重复跳过，整本书丢失
            seen.add(id)
            val image = (container.selectFirst("img[src]")
                ?: anchor.parents().take(COVER_ANCESTOR_LIMIT).firstNotNullOfOrNull { it.selectFirst("img[src]") })
                ?.let { Wenku8Url.resolve(finalUrl, it.attr("src")) }
            results += SearchBook(
                id = id,
                title = title,
                author = Regex("作者\\s*[：:]\\s*([^\\s/]+)").find(raw)?.groupValues?.get(1).orEmpty(),
                category = Regex("分类\\s*[：:]\\s*([^\\s/]+)").find(raw)?.groupValues?.get(1).orEmpty(),
                status = Regex("状态\\s*[：:]\\s*([^\\s/]+)").find(raw)?.groupValues?.get(1).orEmpty(),
                updatedAt = Regex("(?:更新|最后更新)\\s*[：:]\\s*(\\d{4}-\\d{2}-\\d{2})").find(raw)?.groupValues?.get(1).orEmpty(),
                wordCount = Regex("(?:字数|全文长度)\\s*[：:]\\s*([\\d,，]+)\\s*字").find(raw)?.groupValues?.get(1)?.replace(",", "")?.replace("，", "")?.toLongOrNull(),
                coverUrl = direct(image),
                latestChapter = Regex("最新章节\\s*[：:]\\s*(.+)").find(raw)?.groupValues?.get(1).orEmpty(),
                sourceUrl = Wenku8Urls.book(id),
            )
        }
        return results
    }

    /**
     * 解析登录态站内搜索结果及 `em#pagestats` 中的分页状态。
     *
     * **页码以请求参数 [requestedPage] 为准**（`page=` 是服务端分页的真相），`em#pagestats`
     * 的文案随站点主题变化、格式并不稳定，因此只用来推断**总页数**；拿不到总页数时退回
     * 「下一页」锚点判断，两条路都拿不到才算末页。登录页在 [parseSearchResults] 就抛 `AUTH_REQUIRED`。
     */
    fun parseSearchPage(html: String, finalUrl: String, requestedPage: Int = 1): SearchPageData {
        require(requestedPage >= 1) { "搜索页码必须从 1 开始。" }
        val books = parseSearchResults(html, finalUrl)
        val document = Jsoup.parse(html, finalUrl)
        val pageCount = totalPages(document.selectFirst("em#pagestats")?.text().orEmpty())
        val hasNextPage = pageCount?.let { requestedPage < it }
            ?: document.select("a[href]").any { anchor ->
                anchor.text().contains("下一页") ||
                    Regex("[?&]page=${requestedPage + 1}(\\D|$)").containsMatchIn(anchor.attr("href"))
            }
        return SearchPageData(
            books = books,
            page = requestedPage,
            pageCount = pageCount,
            hasNextPage = hasNextPage,
        )
    }

    /**
     * 从 `em#pagestats` 文案里取**总页数**：`2/5`、`2 / 5`、`共5页` 都认。
     *
     * 斜杠形式要求「前面的当前页 ≤ 总页数」，否则视为文案不是分页状态（如单纯的年份数字），
     * 返回 null 让调用方退回锚点判断。
     */
    private fun totalPages(stats: String): Int? {
        if (stats.isBlank()) return null
        PAGE_SLASH.find(stats)?.let { slash ->
            val current = slash.groupValues[1].toIntOrNull()
            val total = slash.groupValues[2].toIntOrNull()
            if (current != null && total != null && current >= 1 && total >= current) return total
        }
        return PAGE_TOTAL.find(stats)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 1 }
    }

    /**
     * 解析 `tags.php` 的官方标签链接：`<a href="tags.php?t=%C1%B5%B0%AE">恋爱</a>`。
     *
     * `t` 参数是百分号编码的 GBK 字节（源站声明 gb2312、实际 GB18030，GBK 解码兼容），
     * 按 GBK 解回标签名；没有 `t` 参数、解码失败或重复的锚点直接跳过，
     * 返回顺序与页面出现顺序一致。登录页在此显式抛出，由调用方转成登录引导。
     */
    fun parseTagList(html: String, finalUrl: String): List<String> {
        if (looksLikeLoginPage(html)) throw Wenku8Exception("浏览标签需要登录轻小说文库。", "AUTH_REQUIRED")
        val document = Jsoup.parse(html, finalUrl)
        val tags = LinkedHashSet<String>()
        for (anchor in document.select("a[href*=tags.php]")) {
            val encoded = TAG_PARAM.find(anchor.attr("href"))?.groupValues?.get(1).orEmpty()
            if (encoded.isBlank()) continue
            val tag = runCatching { java.net.URLDecoder.decode(encoded, "GBK") }.getOrDefault(encoded).trim()
            if (tag.isNotBlank()) tags += tag
        }
        return tags.toList()
    }

    /** `tags.php?t=...` 的 t 参数；同时排除后续参数与锚点。 */
    private val TAG_PARAM = Regex("[?&]t=([^&#]+)")

    /** 封面不在文字容器内时，向上最多找这么多个祖先（卡片把标题与封面放在兄弟 div 里）。 */
    private const val COVER_ANCESTOR_LIMIT = 4

    private fun parseWordCount(document: org.jsoup.nodes.Document): Long? = Regex("全文长度\\s*[：:]\\s*([\\d,，]+)\\s*字").find(document.text())?.groupValues?.get(1)?.replace(",", "")?.replace("，", "")?.toLongOrNull()

    // ---- 公开书目索引（免登录）----

    /**
     * 从任意公开页面提取书籍链接。只接受形如 /book/{id}.htm 的真实链接，
     * 自动跳过导航、占位（href="#"）与重复项。
     */
    fun parseBookLinks(html: String, baseUrl: String): List<BookLink> {
        if (looksLikeChallenge(html)) throw Wenku8Exception("源站要求浏览器验证。", "UPSTREAM_CHALLENGE")
        val document = Jsoup.parse(html, baseUrl)
        val seen = mutableSetOf<String>()
        val links = mutableListOf<BookLink>()
        for (anchor in document.select("a[href*=/book/]")) {
            val href = anchor.attr("href")
            if (href == "#" || href.isBlank()) continue
            val id = Regex("/book/(\\d+)\\.htm", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1) ?: continue
            if (!seen.add(id)) continue
            val title = cleanInline(anchor.attr("title")).ifBlank { cleanInline(anchor.text()) }
            links += BookLink(id, title)
        }
        return links
    }

    /** 提取页面的「同作者作品」公开链接；不存在时返回 null。 */
    fun parseAuthorLink(html: String, baseUrl: String): String? {
        if (looksLikeChallenge(html)) throw Wenku8Exception("源站要求浏览器验证。", "UPSTREAM_CHALLENGE")
        val document = Jsoup.parse(html, baseUrl)
        return document.selectFirst("a[href*=authorarticle.php]")
            ?.let { Wenku8Url.resolve(baseUrl, it.attr("href")) }
    }

    /** 解析年度精选榜 / 月度新书榜，返回书籍引用列表。 */
    fun parseSeedList(html: String, baseUrl: String, listName: String): List<CatalogSeedRef> {
        return parseBookLinks(html, baseUrl).map { CatalogSeedRef(it.id, it.title, listName) }
    }

    /** 解析单本详情页为索引条目；复用 parseBook 的字段抽取。 */
    fun parseCatalogEntry(html: String, bookUrl: String, firstSeenAt: Long = System.currentTimeMillis()): CatalogEntry {
        val book = parseBook(html, bookUrl)
        val id = book.id ?: Regex("/book/(\\d+)\\.htm", RegexOption.IGNORE_CASE)
            .find(java.net.URI(bookUrl).path.orEmpty())?.groupValues?.get(1)
            ?: throw Wenku8Exception("未能识别书籍 ID。", "BOOK_PARSE_FAILED")
        return CatalogEntry(
            id = id,
            title = book.title,
            author = book.author,
            category = book.category,
            status = book.status,
            wordCount = book.wordCount,
            updatedAt = book.updatedAt,
            tags = book.tags,
            summary = book.summary,
            coverUrl = book.coverUrl,
            sourceUrl = book.sourceUrl,
            firstSeenAt = firstSeenAt,
        )
    }

    private fun fieldValue(document: org.jsoup.nodes.Document, vararg labels: String): String {
        for (label in labels) {
            val cell = document.select("td").firstOrNull { cleanInline(it.text()).startsWith(label) } ?: continue
            return cleanInline(cell.wholeText().removePrefix(label).removePrefix("：").removePrefix(":"))
        }
        return ""
    }

    fun cleanText(value: String?): String = value.orEmpty().replace('\u00a0', ' ').replace(Regex("[ \\t]+"), " ").replace(Regex("\\s*\\n\\s*"), "\n").trim()
    fun cleanInline(value: String?): String = cleanText(value).replace(Regex("\\s+"), " ")
}

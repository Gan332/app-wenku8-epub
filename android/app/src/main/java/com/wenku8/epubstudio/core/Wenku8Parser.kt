package com.wenku8.epubstudio.core

import com.wenku8.epubstudio.model.Book
import com.wenku8.epubstudio.model.BookIndex
import com.wenku8.epubstudio.model.Chapter
import com.wenku8.epubstudio.model.ContentBlock
import com.wenku8.epubstudio.model.ParsedChapter
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

object Wenku8Parser {
    fun looksLikeChallenge(html: String): Boolean = Regex("<title[^>]*>\\s*(Just a moment|Attention Required)|Checking your browser|cf-chl-|__cf_chl", RegexOption.IGNORE_CASE).containsMatchIn(html.take(20_000))

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
            tags = tags,
            summary = cleanText(summary).replace(Regex("^内容简介\\s*[：:]\\s*"), ""),
            coverUrl = Wenku8Url.resolve(bookUrl, coverNode?.attr("src")),
            sourceUrl = bookUrl,
            bookUrl = bookUrl,
            directoryUrl = directoryUrl,
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
                val url = Wenku8Url.resolve(finalUrl, anchor.attr("href")) ?: continue
                runCatching { Wenku8Url.assertAllowed(url) }
                if (!seen.add(url)) continue
                val id = Regex("/(\\d+)\\.html?$", RegexOption.IGNORE_CASE).find(java.net.URI(url).path.orEmpty())?.groupValues?.get(1) ?: (chapters.size + 1).toString()
                chapters += Chapter(id, title, url, volume, chapters.size + 1, Regex("插图|插畫|彩页|彩頁").containsMatchIn(title))
            }
        }
        if (chapters.isEmpty()) throw Wenku8Exception("目录中没有识别到章节。", "NO_CHAPTERS")
        return BookIndex(document.title().substringBefore(" - ").trim().ifBlank { "未命名轻小说" }, finalUrl, bookId ?: Wenku8Url.sourceIds(java.net.URI(finalUrl)).bookId, chapters)
    }

    fun parseChapter(html: String, chapter: Chapter, pageUrl: String): ParsedChapter {
        Wenku8Url.assertAllowed(chapter.url)
        if (looksLikeChallenge(html)) throw Wenku8Exception("源站要求浏览器验证。", "UPSTREAM_CHALLENGE")
        val document = Jsoup.parse(html, pageUrl)
        val source = document.selectFirst("#content") ?: throw Wenku8Exception("未能识别章节正文：${chapter.title}", "CHAPTER_PARSE_FAILED")
        val root = source.clone()
        root.select("#contentdp,script,style,iframe,object,embed,form,input,button,noscript,link,meta").remove()
        root.select("[id^=adv],[class*=advert],[class*=banner]").remove()
        val imageUrls = mutableListOf<String>()
        for (image in root.select("img").toList()) {
            val url = sequenceOf("data-original", "data-src", "data-lazy-src", "src").mapNotNull { Wenku8Url.resolve(pageUrl, image.attr(it)) }.firstOrNull()
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
        root.select("p").forEach { it.append(TextNode("\n")) }
        val blocks = normalizeBlocks(root.text(), imageUrls.size)
        val plain = blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.value }
        if (plain.length < 10 && imageUrls.isEmpty()) throw Wenku8Exception("章节正文为空：${chapter.title}", "EMPTY_CHAPTER")
        return ParsedChapter(chapter.id, chapter.title, chapter.volume, chapter.order, pageUrl, imageUrls, blocks, plain.length)
    }

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

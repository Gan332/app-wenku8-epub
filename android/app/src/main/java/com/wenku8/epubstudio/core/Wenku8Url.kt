package com.wenku8.epubstudio.core

import com.wenku8.epubstudio.model.Book
import java.net.URI

class Wenku8Exception(message: String, val code: String = "WENKU8_ERROR", cause: Throwable? = null) : Exception(message, cause)

enum class SourceKind { book, index }

data class SourceIds(val kind: SourceKind, val bookId: String, val categoryId: String? = null)

object Wenku8Url {
    private val domains = listOf("wenku8.net", "wenku8.cc", "wenku8.com")

    fun isAllowedHost(host: String?): Boolean {
        val value = host?.lowercase() ?: return false
        return domains.any { value == it || value.endsWith(".$it") }
    }

    fun assertAllowed(value: String): URI {
        val uri = try { URI(value) } catch (error: Exception) {
            throw Wenku8Exception("网址格式无效。", "INVALID_URL", error)
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw Wenku8Exception("只支持 HTTP/HTTPS 网址。", "UNSUPPORTED_PROTOCOL")
        if (uri.userInfo != null) throw Wenku8Exception("网址不能包含用户名或密码。", "URL_CREDENTIALS")
        if (!isAllowedHost(uri.host)) throw Wenku8Exception("只允许访问 wenku8 来源。", "UNSUPPORTED_HOST")
        return uri
    }

    fun normalizeSource(input: String): URI {
        val raw = input.trim()
        if (raw.isEmpty()) throw Wenku8Exception("请输入书籍或目录网址。", "URL_REQUIRED")
        return assertAllowed(if (raw.all(Char::isDigit)) "https://www.wenku8.net/book/$raw.htm" else raw)
    }

    fun sourceIds(uri: URI): SourceIds {
        val path = uri.path.orEmpty()
        val novel = Regex("/novel/(\\d+)/(\\d+)/(?:index\\.html?)", RegexOption.IGNORE_CASE).find(path)
        if (novel != null) return SourceIds(SourceKind.index, novel.groupValues[2], novel.groupValues[1])
        val book = Regex("/book/(\\d+)\\.html?", RegexOption.IGNORE_CASE).find(path)
        if (book != null) return SourceIds(SourceKind.book, book.groupValues[1])
        if (path.endsWith("/modules/article/articleinfo.php", ignoreCase = true)) {
            val query = uri.rawQuery.orEmpty().split("&").firstOrNull { it.startsWith("id=") }?.substringAfter("=")
            if (!query.isNullOrBlank()) return SourceIds(SourceKind.book, query)
        }
        throw Wenku8Exception("无法识别书籍编号。", "UNRECOGNIZED_SOURCE")
    }

    fun resolve(base: String, value: String?): String? {
        if (value.isNullOrBlank() || value.startsWith("data:", true) || value.startsWith("javascript:", true) || value.startsWith("blob:", true)) return null
        return try { URI(base).resolve(value).toString() } catch (_: Exception) { null }
    }

    fun validateBook(book: Book): Book {
        if (book.title.isBlank()) throw Wenku8Exception("书籍标题不能为空。", "BOOK_TITLE_REQUIRED")
        val source = assertAllowed(book.sourceUrl.ifBlank { book.bookUrl }).toString()
        return book.copy(
            id = book.id?.filter(Char::isDigit)?.ifBlank { null },
            title = book.title.trim().take(200),
            author = book.author.trim().ifBlank { "未知作者" }.take(200),
            sourceUrl = source,
            bookUrl = assertAllowed(book.bookUrl.ifBlank { source }).toString(),
            directoryUrl = book.directoryUrl?.let { assertAllowed(it).toString() },
        )
    }

    fun validateChapters(chapters: List<com.wenku8.epubstudio.model.Chapter>): List<com.wenku8.epubstudio.model.Chapter> {
        if (chapters.isEmpty()) throw Wenku8Exception("请至少选择一个章节。", "NO_CHAPTER_SELECTED")
        if (chapters.size > 2000) throw Wenku8Exception("单次最多支持 2000 个章节。", "TOO_MANY_CHAPTERS")
        val seen = mutableSetOf<String>()
        return chapters.mapIndexed { index, chapter ->
            val url = assertAllowed(chapter.url).toString()
            if (!seen.add(url)) throw Wenku8Exception("章节重复：${chapter.title}", "DUPLICATE_CHAPTER")
            chapter.copy(id = chapter.id.ifBlank { (index + 1).toString() }, title = chapter.title.trim().ifBlank { "第 ${index + 1} 章" }, url = url)
        }.sortedBy { it.order }
    }
}

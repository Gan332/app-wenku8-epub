package com.example.hyperreader.core

import com.example.hyperreader.model.Book
import java.net.URI

class Wenku8Exception(message: String, val code: String = "WENKU8_ERROR", cause: Throwable? = null) : Exception(message, cause)

enum class SourceKind { book, index }

data class SourceIds(val kind: SourceKind, val bookId: String, val categoryId: String? = null)

object Wenku8Url {
    private val domains = listOf("wenku8.net", "wenku8.cc", "wenku8.com")

    /** wenku8 自身域名（直连链路）。中继端点会被拒绝，见 [Wenku8Endpoint.normalizeRelayBase]。 */
    fun isDirectHost(host: String?): Boolean {
        val value = host?.lowercase() ?: return false
        return domains.any { value == it || value.endsWith(".$it") }
    }

    /**
     * 该 host 是否**允许携带用户的会话 Cookie**。
     *
     * 只有 wenku8 自身域名为true：即便公开端点走了第三方中继（[Wenku8Endpoint]），
     * 用户本人的 `jieqiUserInfo` / `PHPSESSID` 也不会发给中继方。
     * `Wenku8SessionStore.cookieJar()` 的存取两侧都用它把关。
     */
    fun carriesSession(host: String?): Boolean = isDirectHost(host)

    /**
     * 允许的 host：wenku8 自身，**外加**用户已配置并启用的中继域（AGENTS §4.11）。
     *
     * 中继域只有开关打开且端点合法时才放行，关着时与未配置时行为完全不变；
     * IP / 内网拦截、scheme 与凭据校验均不受影响。
     */
    fun isAllowedHost(host: String?): Boolean {
        val value = host?.lowercase() ?: return false
        if (isDirectHost(value)) return true
        return Wenku8Endpoint.isRelayActive() && value == Wenku8Endpoint.relayHost()
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
        // 粘贴中继 URL 时先还原，避免把第三方地址带进解析管线
        val restored = Wenku8Endpoint.restoreToDirect(raw)
        return assertAllowed(if (restored.all(Char::isDigit)) "https://www.wenku8.net/book/$restored.htm" else restored)
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
        // 入库前统一还原：即使上游页面或用户粘贴带来中继域，落库与 EPUB 内链也只用wenku8 原域
        val source = assertAllowed(Wenku8Endpoint.restoreToDirect(book.sourceUrl.ifBlank { book.bookUrl })).toString()
        return book.copy(
            id = book.id?.filter(Char::isDigit)?.ifBlank { null },
            title = book.title.trim().take(200),
            author = book.author.trim().ifBlank { "未知作者" }.take(200),
            sourceUrl = source,
            bookUrl = assertAllowed(Wenku8Endpoint.restoreToDirect(book.bookUrl.ifBlank { source })).toString(),
            directoryUrl = book.directoryUrl?.let { assertAllowed(Wenku8Endpoint.restoreToDirect(it)).toString() },
        )
    }

    fun validateChapters(chapters: List<com.example.hyperreader.model.Chapter>): List<com.example.hyperreader.model.Chapter> {
        if (chapters.isEmpty()) throw Wenku8Exception("请至少选择一个章节。", "NO_CHAPTER_SELECTED")
        if (chapters.size > 2000) throw Wenku8Exception("单次最多支持 2000 个章节。", "TOO_MANY_CHAPTERS")
        val seen = mutableSetOf<String>()
        return chapters.mapIndexed { index, chapter ->
            val url = assertAllowed(Wenku8Endpoint.restoreToDirect(chapter.url)).toString()
            if (!seen.add(url)) throw Wenku8Exception("章节重复：${chapter.title}", "DUPLICATE_CHAPTER")
            chapter.copy(id = chapter.id.ifBlank { (index + 1).toString() }, title = chapter.title.trim().ifBlank { "第 ${index + 1} 章" }, url = url)
        }.sortedBy { it.order }
    }
}

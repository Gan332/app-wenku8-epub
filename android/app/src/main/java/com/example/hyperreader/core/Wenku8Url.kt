package com.example.hyperreader.core

import com.example.hyperreader.model.Book
import java.net.URI

class Wenku8Exception(message: String, val code: String = "WENKU8_ERROR", cause: Throwable? = null) : Exception(message, cause)

enum class SourceKind { book, index }

data class SourceIds(val kind: SourceKind, val bookId: String, val categoryId: String? = null)

object Wenku8Url {
    private val domains = listOf("wenku8.net", "wenku8.cc", "wenku8.com")

    /** 镜像入口主机（同 [domains]，去掉可能存在的 `www.` 前缀后比较）。 */
    private val ENTRY_HOSTS = domains.toSet()

    /** 封面与插图 CDN；`pic.777743.xyz` 是站点 2026 年启用的新图床（非 wenku8 域名，只承载图片）。 */
    private val imageHosts = listOf("img.wenku8.com", "pic.wenku8.com", "pic.777743.xyz")

    /** 封面 CDN（源站相对路径 `/image/…` 落在这里）。 */
    private const val IMAGE_CDN = "https://img.wenku8.com"

    /** 旧插图 CDN（相对路径 `/pictures/…` 的规范主机；新图床按 [imageHosts] 另行放行）。 */
    private const val PICTURE_CDN = "https://pic.wenku8.com"

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

    /**
     * 镜像**入口**主机：只有 `wenku8.net / .cc / .com` 的 www 形态参与入口轮换与中继改写。
     *
     * 与 [isDirectHost] 的区别：`img.wenku8.com`、`pic.wenku8.com` 属于直连域但不是入口——
     * 把图片 CDN 主机改写成入口会让请求落到主站并不存在的 `/image/…`（实测 404）。
     */
    fun isEntryHost(host: String?): Boolean {
        val value = host?.lowercase()?.removePrefix("www.") ?: return false
        return ENTRY_HOSTS.contains(value)
    }

    fun assertAllowed(value: String): URI {
        val uri = parseUrl(value)
        if (!isAllowedHost(uri.host)) throw Wenku8Exception("只允许访问 wenku8 来源。", "UNSUPPORTED_HOST")
        return uri
    }

    /**
     * 图片下载专用白名单（AGENTS §4.2）：wenku8 自身域名 **外加**站点自有图床。
     *
     * 插图与封面托管在与主站不同的 CDN 上（`img.wenku8.com`、`pic.wenku8.com`，
     * 以及站点 2026 年启用的新图床 `pic.777743.xyz`）；后者不是 wenku8 域名，
     * 但它只承载图片，**不参与页面、接口与会话链路**——那些一律仍走 [assertAllowed]。
     */
    fun assertAllowedImage(value: String): URI {
        val uri = parseUrl(value)
        if (!isAllowedImageHost(uri.host)) throw Wenku8Exception("只允许访问 wenku8 来源。", "UNSUPPORTED_HOST")
        return uri
    }

    fun isAllowedImageHost(host: String?): Boolean {
        val value = host?.lowercase() ?: return false
        return isAllowedHost(value) || imageHosts.any { value == it }
    }

    private fun parseUrl(value: String): URI {
        val uri = try { URI(value.trim()) } catch (error: Exception) {
            throw Wenku8Exception("网址格式无效。", "INVALID_URL", error)
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw Wenku8Exception("只支持 HTTP/HTTPS 网址。", "UNSUPPORTED_PROTOCOL")
        if (uri.userInfo != null) throw Wenku8Exception("网址不能包含用户名或密码。", "URL_CREDENTIALS")
        return uri
    }

    /**
     * 图片地址归一：解析出口与真正下载前都要过一遍。
     *
     * 三件事，缺一样图片就会静默丢失：
     * 1. **相对路径钉回 CDN**：源站页面常给 `/image/…`（封面）或 `/pictures/…`（插图）这样的
     *    相对路径，按页面地址解析会变成 `www.wenku8.net/image/…`——该路径在主站并不存在（404）；
     * 2. **协议升级**：站点给的是明文 `http://`，而应用禁止明文流量（`network_security_config`），
     *    原样交给 OkHttp 会被系统直接拦掉；
     * 3. **中继域还原**：解析出口不能把第三方中继 host 写进书目（见 [Wenku8Endpoint.restoreToDirect]）。
     *
     * 其它主机（含未知第三方）原样返回，仍由图片白名单把关。
     */
    fun canonicalizeImage(url: String): String {
        val trimmed = url.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
        val host = uri.host?.lowercase() ?: return trimmed
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        if (isEntryHost(host)) {
            return when {
                path.startsWith("/image/") -> "$IMAGE_CDN$path$query"
                path.startsWith("/pictures/") -> "$PICTURE_CDN$path$query"
                else -> trimmed
            }
        }
        if (imageHosts.any { host == it }) {
            return if (uri.scheme.equals("http", ignoreCase = true)) "https://$host$path$query" else trimmed
        }
        return trimmed
    }

    /** 相对/协议相对图片地址 → 先按 [pageUrl] 解析，再 [canonicalizeImage]。 */
    fun normalizeImage(value: String?, pageUrl: String): String? {
        val resolved = resolve(pageUrl, value) ?: return null
        return canonicalizeImage(Wenku8Endpoint.restoreToDirect(resolved))
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

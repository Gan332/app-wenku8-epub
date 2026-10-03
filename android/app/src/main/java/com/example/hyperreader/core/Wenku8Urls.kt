package com.example.hyperreader.core

import com.example.hyperreader.model.SearchField
import java.net.URLEncoder
import java.nio.charset.Charset

object Wenku8Urls {
    /** 直连基址。会话链路（登录/搜索/榜单/标签/正文）**只用**这个，不受中继影响。 */
    const val BASE = "https://www.wenku8.net"

    /**
     * 公开页基址：开关打开时是用户配置的中继，否则直连（见 [Wenku8Endpoint]）。
     *
     * 仅用于免登录公开端点：[articleInfo]、[index]、[sugoi]、[booklist]、[authorArticle]。
     * [book] 是「来源地址」，会写进书架与 EPUB，**必须**保持直连。
     */
    fun publicBase(): String = Wenku8Endpoint.publicBase()

    const val LOGIN = "$BASE/login.php"
    const val SEARCH = "$BASE/modules/article/search.php"
    const val TOP_LIST = "$BASE/modules/article/toplist.php"
    const val ARTICLE_LIST = "$BASE/modules/article/articlelist.php"
    const val TAGS = "$BASE/modules/article/tags.php"

    fun book(bookId: String): String = "$BASE/book/${bookId.filter(Char::isDigit)}.htm"

    fun articleInfo(bookId: String): String = "${publicBase()}/modules/article/articleinfo.php?id=${bookId.filter(Char::isDigit)}"

    /**
     * 目录页地址。`category` 是源站的分类号（如 `2`）。
     *
     * 偏好来自 `articleinfo.php` 解析出的 `directoryUrl`；拿不到时才用这个兜底
     * （分类号缺失时按 `2` 轻小说拼，源站会对错误分类重定向，重定向后仍能拿到目录）。
     *
     * 返回值可能落在中继域上，解析出口会用 [Wenku8Endpoint.restoreToDirect] 还原再落库。
     */
    fun index(bookId: String, category: String? = null): String {
        val id = bookId.filter(Char::isDigit)
        val group = category?.filter(Char::isDigit)?.ifBlank { null } ?: DEFAULT_NOVEL_CATEGORY
        return "${publicBase()}/novel/$group/$id/index.htm"
    }

    /** wenku8 的轻小说分类号，绝大多数条目都在这里。 */
    const val DEFAULT_NOVEL_CATEGORY = "2"

    fun search(keyword: String, field: SearchField, page: Int = 1): String {
        require(page >= 1) { "搜索页码必须从 1 开始。" }
        val encoded = runCatching { URLEncoder.encode(keyword.trim(), Charset.forName("GBK")) }.getOrElse { URLEncoder.encode(keyword.trim(), Charsets.UTF_8) }
        val type = if (field == SearchField.AUTHOR) "author" else "articlename"
        return "$SEARCH?searchtype=$type&searchkey=$encoded&page=$page"
    }

    fun toplist(sort: String = "lastupdate") = "$TOP_LIST?sort=$sort"
    fun tag(tag: String) = "$TAGS?t=${gbk(tag)}"

    /** 公开年度精选榜，无需登录；可走中继。 */
    fun sugoi(year: Int): String = "${publicBase()}/zt/sugoi/$year.php"

    /** 公开月度新书榜，无需登录；可走中继。 */
    fun booklist(yearMonth: String): String = "${publicBase()}/zt/booklist/$yearMonth.php"

    /** 公开的按作者作品列表，无需登录；author 需 GBK 编码；可走中继。 */
    fun authorArticle(author: String, page: Int = 1): String =
        "${publicBase()}/modules/article/authorarticle.php?author=${gbk(author)}&page=$page"

    private fun gbk(value: String): String = runCatching { URLEncoder.encode(value, Charset.forName("GBK")) }.getOrElse { URLEncoder.encode(value, Charsets.UTF_8) }
}

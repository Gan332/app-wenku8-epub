package com.wenku8.epubstudio.core

import com.wenku8.epubstudio.model.SearchField
import java.net.URLEncoder
import java.nio.charset.Charset

object Wenku8Urls {
    const val BASE = "https://www.wenku8.net"
    const val LOGIN = "$BASE/login.php"
    const val SEARCH = "$BASE/modules/article/search.php"
    const val ARTICLE_INFO = "$BASE/modules/article/articleinfo.php"
    const val TOP_LIST = "$BASE/modules/article/toplist.php"
    const val ARTICLE_LIST = "$BASE/modules/article/articlelist.php"
    const val TAGS = "$BASE/modules/article/tags.php"

    fun book(bookId: String): String = "$BASE/book/${bookId.filter(Char::isDigit)}.htm"

    fun articleInfo(bookId: String): String = "$ARTICLE_INFO?id=${bookId.filter(Char::isDigit)}"

    fun search(keyword: String, field: SearchField): String {
        val encoded = runCatching { URLEncoder.encode(keyword.trim(), Charset.forName("GBK")) }.getOrElse { URLEncoder.encode(keyword.trim(), Charsets.UTF_8) }
        val type = if (field == SearchField.AUTHOR) "author" else "articlename"
        return "$SEARCH?searchtype=$type&searchkey=$encoded"
    }

    fun toplist(sort: String = "lastupdate") = "$TOP_LIST?sort=$sort"
    fun tag(tag: String) = "$TAGS?t=${runCatching { URLEncoder.encode(tag, Charset.forName("GBK")) }.getOrElse { URLEncoder.encode(tag, Charsets.UTF_8) }}"
}

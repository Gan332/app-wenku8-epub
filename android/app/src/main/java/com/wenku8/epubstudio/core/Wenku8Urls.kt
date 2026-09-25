package com.wenku8.epubstudio.core

import com.wenku8.epubstudio.model.SearchField
import java.net.URLEncoder
import java.nio.charset.Charset

object Wenku8Urls {
    const val BASE = "https://www.wenku8.net"
    const val LOGIN = "$BASE/login.php"
    const val SEARCH = "$BASE/modules/article/search.php"
    const val ARTICLE_INFO = "$BASE/modules/article/articleinfo.php"

    fun book(bookId: String): String = "$BASE/book/${bookId.filter(Char::isDigit)}.htm"

    fun articleInfo(bookId: String): String = "$ARTICLE_INFO?id=${bookId.filter(Char::isDigit)}"

    fun search(keyword: String, field: SearchField): String {
        val encoded = runCatching { URLEncoder.encode(keyword.trim(), Charset.forName("GBK")) }.getOrElse { URLEncoder.encode(keyword.trim(), Charsets.UTF_8) }
        val type = if (field == SearchField.AUTHOR) "author" else "articlename"
        return "$SEARCH?searchtype=$type&searchkey=$encoded"
    }
}

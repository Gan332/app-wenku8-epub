package com.wenku8.epubstudio.core

import com.wenku8.epubstudio.model.SearchBook
import com.wenku8.epubstudio.model.SearchField

class Wenku8SearchProvider(
    private val http: Wenku8HttpClient,
    private val sessionStore: Wenku8SessionStore,
) {
    fun isLoggedIn(): Boolean = sessionStore.hasSession()

    suspend fun search(keyword: String, field: SearchField): List<SearchBook> {
        val value = keyword.trim()
        if (value.isEmpty()) return emptyList()
        if (!sessionStore.hasSession()) throw Wenku8Exception("请先登录轻小说文库后搜索。", "AUTH_REQUIRED")
        val page = http.fetchText(Wenku8Urls.search(value, field), "search", Wenku8Urls.BASE)
        if (Wenku8Parser.looksLikeLoginPage(page.html)) {
            sessionStore.clear()
            throw Wenku8Exception("搜索登录已过期，请重新登录。", "AUTH_REQUIRED")
        }
        return Wenku8Parser.parseSearchResults(page.html, page.finalUrl)
    }
}

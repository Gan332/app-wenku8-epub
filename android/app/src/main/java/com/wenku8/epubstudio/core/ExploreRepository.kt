package com.wenku8.epubstudio.core

import com.wenku8.epubstudio.model.SearchBook

class Wenku8DataSource(
    private val http: Wenku8HttpClient,
    private val sessionStore: Wenku8SessionStore,
) : NovelDataSource {
    override val id = "wenku8"
    override val displayName = "Wenku8 轻小说文库"

    override suspend fun explore(page: ExplorePage): List<SearchBook> {
        if (page.requiresAuth && !sessionStore.hasSession()) {
            throw Wenku8Exception("探索该分类需要登录 wenku8。", "AUTH_REQUIRED")
        }
        val result = http.fetchText(page.url, "explore:${page.id}", Wenku8Urls.BASE)
        if (Wenku8Parser.looksLikeLoginPage(result.html)) {
            sessionStore.clear()
            throw Wenku8Exception("wenku8 登录已过期，请重新登录。", "AUTH_REQUIRED")
        }
        return Wenku8Parser.parseSearchResults(result.html, result.finalUrl)
    }
}

class ExploreRepository(private val dataSource: NovelDataSource) {
    val sourceId = dataSource.id
    val sourceName = dataSource.displayName
    suspend fun load(page: ExplorePage) = dataSource.explore(page)
}

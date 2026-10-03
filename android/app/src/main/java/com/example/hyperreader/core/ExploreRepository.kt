package com.example.hyperreader.core

import com.example.hyperreader.model.SearchBook

class Wenku8DataSource(
    private val http: Wenku8HttpClient,
    private val sessionStore: SessionGate,
) : NovelDataSource {
    override val id = "wenku8"
    override val displayName = "Wenku8 轻小说文库"

    override suspend fun explore(page: ExplorePage): List<SearchBook> {
        if (page.requiresAuth && !sessionStore.hasSession()) {
            throw Wenku8Exception("该榜单需要登录 wenku8 后浏览（排行榜与官方标签同样需要登录）。", "AUTH_REQUIRED")
        }
        val result = http.fetchText(page.url, "explore:${page.id}", Wenku8Urls.BASE)
        requireLoggedInPage(result.html)
        return Wenku8Parser.parseSearchResults(result.html, result.finalUrl)
    }

    /**
     * 官方标签列表（`tags.php` 首页的标签锚点）。只在用户**已登录**时抓取——
     * 该接口匿名会被 302 到登录页；无会话时返回空，由本地书目索引兜底。
     */
    override suspend fun officialTags(): List<String> {
        if (!sessionStore.hasSession()) return emptyList()
        val result = http.fetchTextInteractive(Wenku8Urls.TAGS, "explore:tags", Wenku8Urls.BASE)
        requireLoggedInPage(result.html)
        return Wenku8Parser.parseTagList(result.html, result.finalUrl)
    }

    /** 单个标签的书（`tags.php?t=X` 第 1 页）。需要用户自己的会话，不内置任何凭据。 */
    override suspend fun tagBooks(tag: String): List<SearchBook> {
        if (!sessionStore.hasSession()) throw Wenku8Exception("浏览标签需要登录 wenku8。", "AUTH_REQUIRED")
        val result = http.fetchTextInteractive(Wenku8Urls.tag(tag), "explore:tag", Wenku8Urls.BASE)
        requireLoggedInPage(result.html)
        return Wenku8Parser.parseSearchResults(result.html, result.finalUrl)
    }

    /** 会话失效时清本地会话并抛出登录提示；**不尝试绕过**登录墙。 */
    private fun requireLoggedInPage(html: String) {
        if (Wenku8Parser.looksLikeLoginPage(html)) {
            sessionStore.clear()
            throw Wenku8Exception("wenku8 登录已过期，请重新登录。", "AUTH_REQUIRED")
        }
    }
}

class ExploreRepository(private val dataSource: NovelDataSource) {
    val sourceId = dataSource.id
    val sourceName = dataSource.displayName
    suspend fun load(page: ExplorePage) = dataSource.explore(page)

    /** 官方标签列表；匿名源返回空列表，由本地索引兜底。 */
    suspend fun officialTags(): List<String> = dataSource.officialTags()

    /** 官方标签下的书；登录过期由数据源抛 AUTH_REQUIRED，调用方回落本地结果。 */
    suspend fun tagBooks(tag: String): List<SearchBook> = dataSource.tagBooks(tag)
}

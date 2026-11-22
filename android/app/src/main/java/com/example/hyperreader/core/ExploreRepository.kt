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

/**
 * 匿名公开榜单的传输层。
 *
 * 与 [Wenku8DataSource] 分开：它**不带会话 Cookie**，只允许 `cf_clearance` 这类
 * 人机验证凭证（由 Application 注入 `clearanceCookieJar()`）。这样即使用户已登录，
 * 公开榜也不会把 `jieqiUserInfo` / `PHPSESSID` 发到匿名页面。
 */
class ExplorePublicSource(private val http: Wenku8HttpClient) {
    suspend fun load(page: ExplorePage): List<String> {
        require(!page.requiresAuth) { "公开榜单不接受登录墙内页面。" }
        val result = http.fetchText(page.url, "explore-public:${page.id}", Wenku8Urls.BASE)
        // 正常匿名榜单不会跳登录页；若源站把公开页也重定向过去，给出登录提示而不是空结果。
        if (Wenku8Parser.looksLikeLoginPage(result.html)) {
            throw Wenku8Exception("公开榜单需要先完成站点登录或浏览器验证。", "AUTH_REQUIRED")
        }
        return ExploreFeed.parsePublicIds(result.html, result.finalUrl)
    }

    /**
     * 公开书籍卡片：`articleinfo.php` 是免登录端点，但详情页仍可能返回登录/风控页，
     * 因此解析前显式识别，避免把错误 HTML 当成“空卡片”缓存下来。
     *
     * 这里只返回展示字段，不碰目录/正文；探索详情仍由 `ExploreDetailRepository` 负责。
     */
    suspend fun loadBook(bookId: String): SearchBook {
        val id = bookId.filter(Char::isDigit)
        require(id.isNotBlank()) { "书籍 ID 无效。" }
        val url = Wenku8Urls.articleInfo(id)
        val result = http.fetchText(url, "explore-public:book:$id", Wenku8Urls.BASE)
        if (Wenku8Parser.looksLikeLoginPage(result.html)) {
            throw Wenku8Exception("公开书籍详情需要先完成站点登录或浏览器验证。", "AUTH_REQUIRED")
        }
        val entry = Wenku8Parser.parseCatalogEntry(result.html, result.finalUrl)
        // 解析失败时 id 会为空；不能把空 key 写进跨轮缓存，否则该 ID 永远补不上。
        if (entry.id.isBlank()) throw Wenku8Exception("公开书籍详情解析失败。", "PARSE_FAILED")
        return SearchBook(
            id = entry.id,
            title = entry.title,
            author = entry.author,
            category = entry.category,
            status = entry.status,
            updatedAt = entry.updatedAt,
            wordCount = entry.wordCount,
            coverUrl = entry.coverUrl,
            sourceUrl = entry.sourceUrl,
        )
    }
}

class ExploreRepository(
    private val dataSource: NovelDataSource,
    private val publicSource: ExplorePublicSource? = null,
) {
    val sourceId = dataSource.id
    val sourceName = dataSource.displayName
    suspend fun load(page: ExplorePage) = dataSource.explore(page)

    /** 匿名公开榜：只返回书籍 ID，卡片由本地索引与逐本补全提供。 */
    suspend fun loadPublic(page: ExplorePage): List<String> =
        requireNotNull(publicSource) { "公开榜单数据源未装配。" }.load(page)

    /** 匿名公开榜缺失卡片的补全：只走无会话客户端，绝不回落到带用户 Cookie 的书源。 */
    suspend fun loadPublicBook(bookId: String): SearchBook =
        requireNotNull(publicSource) { "公开榜单数据源未装配。" }.loadBook(bookId)

    /** 官方标签列表；匿名源返回空列表，由本地索引兜底。 */
    suspend fun officialTags(): List<String> = dataSource.officialTags()

    /** 官方标签下的书；登录过期由数据源抛 AUTH_REQUIRED，调用方回落本地结果。 */
    suspend fun tagBooks(tag: String): List<SearchBook> = dataSource.tagBooks(tag)
}

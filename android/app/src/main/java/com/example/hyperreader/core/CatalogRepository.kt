package com.example.hyperreader.core

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CatalogState(
    val stats: CatalogStats = CatalogStats(),
    val loading: Boolean = false,
    val message: String? = null,
    val progress: Pair<Int, Int>? = null,
    /** 0.18.0：上次失败是否为 Cloudflare 拦截（UI 据此弹验证窗口）。 */
    val challengeSuspended: Boolean = false,
)

/**
 * 免登录书目仓库：协调抓取、持久化与内存索引。
 * 使用独立的无**会话** Cookie 客户端（只可能携带 cf_clearance 人机验证凭证）。
 */
class CatalogRepository(private val context: Context) {
    private val crawler = CatalogCrawler(context)
    private val http = Wenku8HttpClient(
        File(context.cacheDir, "wenku8-catalog"),
        (context.applicationContext as com.example.hyperreader.Wenku8Application).sessionStore.clearanceCookieJar(),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val entries = HashMap<String, CatalogEntry>()
    private var cursor = CatalogCursor()
    private var index = CatalogIndex(emptyList())

    private val mutable = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = mutable.asStateFlow()

    init {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { crawler.loadEntries() to crawler.loadCursor() }
            lock.withLock {
                entries.clear()
                entries.putAll(loaded.first)
                cursor = loaded.second
                index = CatalogIndex(entries.values)
                mutable.value = CatalogState(stats = crawler.stats(entries, cursor))
            }
        }
    }

    fun currentStats(): CatalogStats = crawler.stats(entries, cursor)
    fun tagList(): List<String> = index.allTags
    fun cachedSize(): Int = index.size
    fun get(id: String): CatalogEntry? = index.get(id)

    /** 本地搜索，免登录。 */
    fun search(query: String, field: CatalogSearchField, limit: Int = 60): List<CatalogEntry> = index.search(query, field, limit)
    fun searchTag(tag: String): List<CatalogEntry> = index.searchTag(tag)

    /** 触发一次增量抓取。 */
    fun update(detailBudget: Int = 200) {
        scope.launch {
            lock.withLock {
                if (mutable.value.loading) return@launch
                mutable.value = mutable.value.copy(loading = true, message = null, progress = 0 to detailBudget)
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val newEntries = HashMap(entries)
                    val newCursor = crawler.crawlOnce(newEntries, cursor, detailBudget) { fetched, budget ->
                        mutable.value = mutable.value.copy(progress = fetched to budget)
                    }
                    newEntries to newCursor
                }
            }
            lock.withLock {
                result.onSuccess { (newEntries, newCursor) ->
                    entries.clear()
                    entries.putAll(newEntries)
                    cursor = newCursor
                    index = CatalogIndex(entries.values)
                    mutable.value = CatalogState(stats = crawler.stats(entries, cursor))
                }.onFailure { error ->
                    val challenged = (error as? Wenku8Exception)?.code == "UPSTREAM_CHALLENGE"
                    mutable.value = mutable.value.copy(
                        loading = false,
                        message = if (challenged) "被 Cloudflare 拦截，完成人机验证后请重新更新。" else (error.message ?: "更新失败。"),
                        progress = null,
                        challengeSuspended = challenged,
                    )
                }
            }
        }
    }

    /** 按需补齐单本详情（打开书籍时用）。 */
    fun ensureBook(id: String, onDone: (CatalogEntry?) -> Unit) {
        scope.launch {
            val existing = index.get(id)
            if (existing != null) {
                onDone(existing)
                return@launch
            }
            val entry = withContext(Dispatchers.IO) {
                runCatching {
                    val page = http.fetchText(Wenku8Urls.articleInfo(id), "catalog-one", Wenku8Urls.BASE)
                    Wenku8Parser.parseCatalogEntry(page.html, page.finalUrl)
                }.getOrNull()
            }
            if (entry != null) {
                lock.withLock {
                    entries[entry.id] = entry
                    index = CatalogIndex(entries.values)
                    mutable.value = mutable.value.copy(stats = crawler.stats(entries, cursor))
                }
            }
            onDone(entry)
        }
    }

    /**
     * 显式抓取某书的同作者公开作品（用户点击才调用）。
     *
     * 只用白名单端点：`articleinfo.php` 解析作者，`authorarticle.php` 分页取作品，
     * 再逐本 `articleinfo.php` 补全封面/字数。全程走无会话 Cookie 客户端与
     * 全局限流（AGENTS §4.2 / §4.5），不尝试规避登录墙。
     *
     * 回调是**增量**的：先给作者名，再给作品链接（只有标题），
     * 最后逐本给补全后的条目——界面不必等全部请求完成才显示第一本书。
     */
    suspend fun expandAuthor(
        bookId: String,
        onAuthor: (String) -> Unit = {},
        onLinks: (List<BookLink>) -> Unit = {},
        onEntry: (CatalogEntry) -> Unit = {},
    ): List<CatalogEntry> = withContext(Dispatchers.IO) {
        val id = bookId.filter(Char::isDigit)
        require(id.isNotBlank()) { "书籍 ID 无效。" }

        val info = http.fetchTextInteractive(Wenku8Urls.articleInfo(id), "catalog-author", Wenku8Urls.BASE)
        val self = Wenku8Parser.parseCatalogEntry(info.html, info.finalUrl)
        val author = self.author.trim().takeIf { it.isNotBlank() && it != "未知作者" }
            ?: throw Wenku8Exception("未能识别该书的作者。", "AUTHOR_NOT_FOUND")
        onAuthor(author)

        // 作者页地址优先用详情页给出的链接（作者名已按源站编码规则转义），
        // 缺失时才按作者名重建。翻页时补上 page 参数，而不是套用「必须已存在 page」的替换。
        val authorPage = Wenku8Parser.parseAuthorLink(info.html, info.finalUrl)
            ?: Wenku8Urls.authorArticle(author, 1)
        val links = LinkedHashMap<String, BookLink>()
        var pageNo = 1
        while (pageNo <= MAX_AUTHOR_PAGES) {
            val page = http.fetchTextInteractive(
                withPage(authorPage, pageNo),
                "catalog-author",
                Wenku8Urls.BASE,
            )
            val pageLinks = Wenku8Parser.parseBookLinks(page.html, page.finalUrl)
            // 保留当前书：作者页是「这位作者的全部作品」的浏览视图，
            // 源站只收录一本时也能看到结果，而不是一屏空态。
            pageLinks.forEach { links.putIfAbsent(it.id, it) }
            val pageCount = Wenku8Parser.parsePageCount(page.html)
            onLinks(links.values.toList())
            // 有 pagestats 就按总页数停；没有就退回「本页是否满 20 条」判断。
            // 空页一律停，避免重复请求同一页。
            val hasMore = pageCount?.let { pageNo < it } ?: (pageLinks.size >= AUTHOR_PAGE_SIZE)
            if (!hasMore || pageLinks.isEmpty()) break
            pageNo++
        }

        val collected = mutableListOf<CatalogEntry>()
        for (link in links.values.take(MAX_AUTHOR_ENTRIES)) {
            val cached = index.get(link.id)
            if (cached != null) {
                collected += cached
                onEntry(cached)
                continue
            }
            val fetched = runCatching {
                val detail = http.fetchTextInteractive(Wenku8Urls.articleInfo(link.id), "catalog-author", Wenku8Urls.BASE)
                Wenku8Parser.parseCatalogEntry(detail.html, detail.finalUrl)
            }.getOrNull()
            if (fetched == null) {
                // 详情补全失败（限流/挑战/单页解析失败）时先给书名占位；
                // **不写入索引**，否则下次 ensureBook 会把这个残缺条目当成已有数据。
                val fallback = CatalogEntry(
                    id = link.id,
                    title = link.title.ifBlank { "未命名作品" },
                    author = author,
                    sourceUrl = Wenku8Urls.book(link.id),
                )
                collected += fallback
                onEntry(fallback)
                continue
            }
            collected += fetched
            lock.withLock {
                entries[fetched.id] = fetched
                index = CatalogIndex(entries.values)
                mutable.value = mutable.value.copy(stats = crawler.stats(entries, cursor))
            }
            onEntry(fetched)
        }
        collected
    }

    private fun withPage(url: String, page: Int): String {
        if (page <= 1) return url
        // 用 lambda 替换而不是 "$1$page"：页码 ≥ 10 时 "$1" + "12" 会被
        // 正则引擎误读成第 12 组。
        val pageParam = Regex("([?&])page=\\d+")
        return if (pageParam.containsMatchIn(url)) {
            url.replace(pageParam) { "${it.groupValues[1]}page=$page" }
        } else {
            "$url${if (url.contains('?')) "&" else "?"}page=$page"
        }
    }

    private companion object {
        /** 作者作品列表每页条数（源站固定 20），用于 pagestats 缺失时判断末页。 */
        const val AUTHOR_PAGE_SIZE = 20

        /** 作者作品列表最多翻的页数（每页 20 本）：足够覆盖高产作者，又不会无限请求。 */
        const val MAX_AUTHOR_PAGES = 10

        /** 单次补全详情的上限：再多就该走书目缓存批量更新，避免用户干等。 */
        const val MAX_AUTHOR_ENTRIES = 60
    }

    fun clear() {
        scope.launch {
            lock.withLock {
                entries.clear()
                cursor = CatalogCursor()
                index = CatalogIndex(emptyList())
                mutable.value = CatalogState()
            }
            withContext(Dispatchers.IO) {
                crawler.clear()
            }
        }
    }

    fun clearMessage() { mutable.value = mutable.value.copy(message = null) }
}

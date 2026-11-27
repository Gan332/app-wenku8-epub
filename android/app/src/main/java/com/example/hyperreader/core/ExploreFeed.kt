package com.example.hyperreader.core

import com.example.hyperreader.model.SearchBook
import java.time.YearMonth

/**
 * 探索页的榜单定义与取数。
 *
 * 唯一入口（AGENTS §4.5）：**匿名公开**的年度精选（`/zt/sugoi/`），走**无会话**客户端。
 * `toplist.php` / `tags.php` 由源站登录墙控制，探索页不挂载，也不做任何登录规避。
 *
 * 这里只负责“榜单 → 卡片 ID / 卡片”的取数，不含 UI 状态；[com.example.hyperreader.ui.StudioViewModel]
 * 负责把结果合并进页面状态。公开页解析与 [Wenku8DataSource] 保持同一出口，避免两套解析规则。
 */
object ExploreFeed {
    /** 公开年度精选榜的年份跨度：当前年份往前共 10 年。 */
    private const val PUBLIC_SUGOI_YEARS = 10

    /**
     * 探索页榜单：**全部来自书源的匿名公开页**（年度精选 `/zt/sugoi/`）。
     *
     * `toplist.php` / `tags.php` 由源站登录墙控制，匿名访问会被 302 到登录页，
     * 因此不再出现在探索页；这里只保留无需登录即可浏览的年度榜单。
     * `/zt/booklist/` 只有 2015–2016 的历史存档，作为书目缓存种子更合适，
     * 不再占探索页 tab。
     */
    fun pages(today: YearMonth = YearMonth.now()): List<ExplorePage> = buildList {
        val year = today.year
        (0 until PUBLIC_SUGOI_YEARS).forEach { offset ->
            val value = year - offset
            add(ExplorePage("sugoi-$value", "$value 年度精选", Wenku8Urls.sugoi(value), requiresAuth = false))
        }
    }

    /**
     * 公开榜单只取书籍 ID：卡片字段由本地索引或后续补全提供，避免把“匿名页的链接”
     * 误当成完整书目。解析出口与导出链路共用 [Wenku8Parser.parseBookLinks]。
     */
    fun parsePublicIds(html: String, finalUrl: String): List<String> =
        Wenku8Parser.parseBookLinks(html, finalUrl).map { it.id }

    /**
     * 合并榜单顺序与已有卡片：**保留榜单顺序**，只把有卡片的数据补进去。
     *
     * 登录墙榜单自带完整卡片，本地索引命中时仍优先本地（字段更全）；公开榜只有 ID，
     * 本地索引没有的条目先留空，等待后续逐本补全，而不是把整行打乱。
     */
    fun mergeCards(
        ids: List<String>,
        catalog: (String) -> SearchBook?,
        remote: Map<String, SearchBook> = emptyMap(),
    ): List<SearchBook> = ids.distinct().mapNotNull { id -> catalog(id) ?: remote[id] }

    /** 登录墙榜单在未登录时不应发起请求；公开榜在未登录时照常取数。 */
    fun canLoad(page: ExplorePage, loggedIn: Boolean): Boolean = !page.requiresAuth || loggedIn

    /**
     * 当前登录态下可展示的榜单。
     *
     * 当前探索页只挂匿名公开榜（年度精选），因此登录与未登录看到的是同一组 tab；
     * 保留过滤逻辑，便于将来加入需要会话的榜单时不把登录墙暴露给匿名用户。
     */
    fun visiblePages(pages: List<ExplorePage>, loggedIn: Boolean): List<ExplorePage> =
        if (loggedIn) pages else pages.filter { !it.requiresAuth }

    /**
     * 本页应该逐本补全的 ID：仅匿名公开榜且本地/榜单卡片都没有的条目。
     *
     * 登录墙榜单首屏已带完整卡片，不再逐本补；同一 ID 只补一次。
     */
    fun missingIds(
        page: ExplorePage,
        ids: List<String>,
        catalog: (String) -> SearchBook?,
        remote: Map<String, SearchBook> = emptyMap(),
        limit: Int = Int.MAX_VALUE,
    ): List<String> =
        if (page.requiresAuth) {
            emptyList()
        } else {
            ids.distinct()
                .filter { catalog(it) == null && remote[it] == null }
                .take(limit)
        }
}

/**
 * 公开榜卡片的进程内缓存。
 *
 * 公开榜单首屏只取 ID，卡片需要逐本请求 `articleinfo.php`；[com.example.hyperreader.ui.StudioViewModel]
 * 每轮只补 [com.example.hyperreader.ui.StudioViewModel.EXPLORE_AUTO_FETCH_LIMIT] 本。
 * 若不保留已补结果，下一次刷新会从同一批缺失项重新开始，第 21 本之后永远轮不到。
 *
 * 这里只缓存**展示字段**，不参与会话、目录或导出；容量上限按 12 个榜单的常见规模取值，
 * 超出后淘汰最早写入的条目，避免长时间使用无限增长。
 */
class ExploreCardCache(private val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0) { "缓存容量必须大于 0。" }
    }

    private val cards = object : LinkedHashMap<String, SearchBook>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchBook>?): Boolean =
            size > capacity
    }

    fun get(id: String): SearchBook? = cards[id]

    fun put(book: SearchBook) {
        cards[book.id] = book
    }

    fun putAll(books: Collection<SearchBook>) {
        books.forEach(::put)
    }

    fun snapshot(): Map<String, SearchBook> = LinkedHashMap(cards)

    fun size(): Int = cards.size

    companion object {
        const val DEFAULT_CAPACITY = 600
    }
}

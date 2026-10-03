package com.example.hyperreader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hyperreader.Wenku8Application
import com.example.hyperreader.core.CatalogEntry
import com.example.hyperreader.core.CatalogSearchField
import com.example.hyperreader.core.ExploreBooksRow
import com.example.hyperreader.core.ExplorePage
import com.example.hyperreader.core.Wenku8Endpoint
import com.example.hyperreader.core.Wenku8Exception
import com.example.hyperreader.core.Wenku8HttpClient
import com.example.hyperreader.core.Wenku8Parser
import com.example.hyperreader.core.Wenku8Urls
import com.example.hyperreader.model.Book
import com.example.hyperreader.model.BookIndex
import com.example.hyperreader.model.BookshelfEntry
import com.example.hyperreader.model.BookshelfSource
import com.example.hyperreader.model.ExportJob
import com.example.hyperreader.model.JobStatus
import com.example.hyperreader.model.ReadingStats
import android.net.Uri
import com.example.hyperreader.model.SearchBook
import com.github.michaelbull.result.get
import io.nightfish.lightnovelreader.api.book.BookInformation
import java.time.LocalDateTime
import com.example.hyperreader.model.SearchField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import com.example.hyperreader.BuildConfig
import com.example.hyperreader.settings.ConfigTransfer
import com.example.hyperreader.ui.ConfigTransferFile
import com.example.hyperreader.ui.ConfigUiState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 把本地索引条目转成统一的书籍卡片模型。 */
internal fun CatalogEntry.toSearchBook(): SearchBook = SearchBook(
    id = id,
    title = title,
    author = author,
    category = category,
    status = status,
    updatedAt = updatedAt,
    wordCount = wordCount,
    coverUrl = coverUrl,
    sourceUrl = sourceUrl,
)

/** 底部主导航（0.17.0 起只有三项，导出入口下沉到书籍菜单）。 */
enum class StudioTab { BOOKSHELF, EXPLORE, SETTINGS }

/**
 * 导出向导的步骤。
 *
 * 0.17.0 起「创建」tab 被移除，导出降级为从书架卡片或探索详情发起的二级页向导：
 * 先解析目录（[RESOLVING]，失败可重试），再选章节、选引擎打包、看进度。
 * 目录解析成功后自动进入 [CHAPTERS]，不再有单独的「详情」步骤。
 */
enum class ExportStep { RESOLVING, CHAPTERS, PACKAGING, PROGRESS }

/** 站内搜索的跨页追加、去重及过期响应判定；与 Android 生命周期解耦以便单测。 */
internal object SearchPager {
    fun append(current: List<SearchBook>, incoming: List<SearchBook>): List<SearchBook> {
        val seen = current.mapTo(mutableSetOf()) { it.id }
        return current + incoming.filter { seen.add(it.id) }
    }

    fun isCurrent(requestId: Long, currentRequestId: Long): Boolean = requestId == currentRequestId

    fun firstPage(state: StudioUiState, page: Wenku8Parser.SearchPageData, loggedIn: Boolean): StudioUiState =
        state.copy(
            searchBusy = false,
            searchResults = page.books,
            searchPage = page.page,
            searchHasNextPage = page.hasNextPage,
            searchLoadingMore = false,
            searchMessage = null,
            loggedIn = loggedIn,
        )

    fun morePage(state: StudioUiState, page: Wenku8Parser.SearchPageData, loggedIn: Boolean): StudioUiState {
        // 拿到空页就收手：源站翻过头时不会给 `hasNextPage`，但空列表继续翻页只会空转
        val hasNextPage = page.books.isNotEmpty() && page.hasNextPage
        return state.copy(
            searchResults = append(state.searchResults, page.books),
            searchPage = maxOf(page.page, state.searchPage),
            searchHasNextPage = hasNextPage,
            searchLoadingMore = false,
            searchMessage = null,
            loggedIn = loggedIn,
        )
    }
}

/**
 * 导出向导的状态机（0.17.0）。
 *
 * 纯函数，不碰 Android 生命周期：把「发起 → 解析 → 选章节 → 打包 → 进度」
 * 的状态迁移从 [StudioViewModel] 里拆出来，以便单测直接断言每一步的字段变化。
 * 关键不变量：
 * - 向导是**二级页覆盖层**（`exportStep != null`），不切 tab；
 * - `begin` 必清book/index/selectedIds，避免上一轮残留章节被误打包；
 * - 解析成功默认全选并进入 [ExportStep.CHAPTERS]；失败留在 [ExportStep.RESOLVING] 供重试。
 */
internal object ExportWizard {
    fun begin(state: StudioUiState, sourceUrl: String): StudioUiState = state.copy(
        tab = StudioTab.BOOKSHELF,
        sourceUrl = sourceUrl,
        exportStep = ExportStep.RESOLVING,
        busy = false,
        detailError = null,
        book = null,
        index = null,
        selectedIds = emptySet(),
        message = null,
    )

    fun resolved(state: StudioUiState, book: com.example.hyperreader.model.Book, index: com.example.hyperreader.model.BookIndex): StudioUiState =
        state.copy(
            busy = false,
            detailError = null,
            book = book,
            index = index,
            selectedIds = index.chapters.map { it.id }.toSet(),
            exportStep = ExportStep.CHAPTERS,
        )

    fun failed(state: StudioUiState, message: String): StudioUiState =
        state.copy(busy = false, exportStep = ExportStep.RESOLVING, detailError = message)

    fun close(state: StudioUiState): StudioUiState = state.copy(exportStep = null, busy = false, detailError = null)

    /**
     * 书架远程书的详情：只改探索详情的四个字段。
     *
     * 刻意**不碰** `book` / `index` / `exportStep`——保持探索详情与导出向导两条链路隔离（AGENTS §4.6）。
     */
    fun openShelfDetail(state: StudioUiState, seed: com.example.hyperreader.core.ExploreBookSeed): StudioUiState = state.copy(
        exploreDetailId = seed.id,
        exploreDetail = null,
        exploreDetailLoading = true,
        exploreDetailError = null,
        exploreDetailSeed = seed,
    )
}

/** 设置二级界面分区。0.18.0：新增 ACCOUNT（账号）与 NETWORK（网络/中继）两类。 */
enum class SettingsSection { OVERVIEW, ACCOUNT, APPEARANCE, READER, NETWORK, STATISTICS, CATALOG, CONFIG, ABOUT }

data class StudioUiState(
    val tab: StudioTab = StudioTab.BOOKSHELF,
    val exportStep: ExportStep? = null,
    val sourceUrl: String = "https://www.wenku8.net/novel/2/2835/index.htm",
    val book: Book? = null,
    val index: BookIndex? = null,
    val selectedIds: Set<String> = emptySet(),
    val search: String = "",
    val includeCover: Boolean = true,
    val busy: Boolean = false,
    val detailError: String? = null,
    val message: String? = null,
    val activeJobId: String? = null,
    val showJobHistory: Boolean = false,
    /** 0.18.0：探索页的搜索移到这里（LNR 式 TopBar 搜索入口），false 表示不在搜索页。 */
    val searchPageOpen: Boolean = false,
    /** 0.18.0：全屏榜单展开页（对应 LNR 的 `ExpandedPage`）。 */
    val exploreExpanded: Boolean = false,
    /** 展开页对应的榜单；刷新时按它重新抓取。 */
    val activeExplorePage: ExplorePage? = null,
    /** 0.18.0：待完成的 Cloudflare 验证地址；非空时 UI 自动弹验证窗口。 */
    val challengeUrl: String? = null,
    val jobs: List<ExportJob> = emptyList(),
    val searchQuery: String = "",
    val searchField: SearchField = SearchField.TITLE,
    val searchResults: List<SearchBook> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val searchBusy: Boolean = false,
    val searchPage: Int = 0,
    val searchHasNextPage: Boolean = false,
    val searchLoadingMore: Boolean = false,
    val searchMessage: String? = null,
    val loggedIn: Boolean = false,
    val bookshelf: List<BookshelfEntry> = emptyList(),
    val readingStats: ReadingStats = ReadingStats(),
    val exploreRows: List<ExploreBooksRow> = emptyList(),
    val exploreBusy: Boolean = false,
    val exploreMessage: String? = null,
    val catalogSize: Int = 0,
    /**
     * 标签区展示的标签：官方标签（登录后从 `tags.php` 拉取，见 §4.5.2）在前，
     * 本地书目索引里已抓到的标签在后；未登录时只有本地标签。
     */
    val exploreTags: List<String> = emptyList(),
    /** 当前选中的标签；null 表示未进入标签浏览。 */
    val activeTag: String? = null,
    /** 当前标签下的书（同样来自本地索引）。 */
    val tagResults: List<SearchBook> = emptyList(),
    val catalogLoading: Boolean = false,
    val catalogProgress: Pair<Int, Int>? = null,
    val catalogUpdatedAt: Long = 0L,
    val localResults: List<CatalogEntry> = emptyList(),
    val settingsSection: SettingsSection = SettingsSection.OVERVIEW,
    val readerSettings: com.example.hyperreader.settings.ReaderSettings = com.example.hyperreader.settings.ReaderSettings(),
    /**
     * 探索页书籍详情（全屏独立页面）。
     *
     * 与创建流程的 [book]/[index] **完全分开**：这条链路只走公开 API
     * （[com.example.hyperreader.core.ExploreDetailRepository]），不经过解析管线，
     * 因此打开探索详情不会触碰 `book` / `index`，也不会改动导出向导里的半成品状态。
     */
    val exploreDetailId: String? = null,
    val exploreDetail: com.example.hyperreader.core.ExploreBookDetail? = null,
    /** 进入详情页时手头已有的字段（榜单/本地索引），用于接口回来前先渲染一屏。 */
    val exploreDetailSeed: com.example.hyperreader.core.ExploreBookSeed? = null,
    val exploreDetailLoading: Boolean = false,
    val exploreDetailError: String? = null,
)

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as Wenku8Application
    private val manager = app.jobManager
    private val searchProvider = app.searchProvider
    private val settingsRepository = app.settingsRepository
    private val bookshelfRepository = app.bookshelfRepository
    private val readingStatsRepository = app.readingStatsRepository
    private val exploreRepository = app.exploreRepository
    private val catalogRepository = app.catalogRepository
    /** 探索详情专用：只走公开 API，不经过解析管线。 */
    private val exploreDetailRepository = app.exploreDetailRepository
    /**
     * 搜索请求的单调序号：只有与当前值一致的那个响应能写状态，
     * 用户中途改关键词/切字段/连点「加载更多」时，旧响应直接丢弃。
     */
    private var searchRequestId = 0L
    private val mutable = MutableStateFlow(StudioUiState())
    val state: StateFlow<StudioUiState> = mutable.asStateFlow()
    val appTheme = settingsRepository.appTheme

    /** EPUB 导出引擎（设置页可切换）；导出任务在打包时读取当前值。 */
    val exportEngine: kotlinx.coroutines.flow.Flow<com.example.hyperreader.settings.EpubEngine> =
        settingsRepository.exportEngine

    /** 第三方中继开关（仅公开页，AGENTS §4.11）。 */
    val relayEnabled: kotlinx.coroutines.flow.Flow<Boolean> = settingsRepository.relayEnabled

    /** 中继端点原文；空串表示未配置。 */
    val relayBase: kotlinx.coroutines.flow.Flow<String> = settingsRepository.relayBase

    /**
     * 官方标签（登录后从 `tags.php` 拉取）；未登录或抓取失败时为空，标签区只显示本地内容。
     * 声明必须在 `init` 之前：冷启动的 collector 会经 [mergedTags] 读到它。
     */
    private var officialTags: List<String> = emptyList()

    fun setExportEngine(engine: com.example.hyperreader.settings.EpubEngine) {
        viewModelScope.launch { runCatching { settingsRepository.setExportEngine(engine) } }
    }

    /**
     * 开关中继：需要先填合法端点，否则拒绝并说明原因。
     *
     * 真正生效由 [com.example.hyperreader.core.Wenku8Endpoint] 决定（Application 监听设置流应用），
     * 这里只做入参校验，不自己改运行态。
     */
    fun setRelayEnabled(enabled: Boolean) {
        if (!enabled) {
            viewModelScope.launch { runCatching { settingsRepository.setRelayEnabled(false) } }
            return
        }
        viewModelScope.launch {
            val current = settingsRepository.relayBase.first()
            when {
                current.isBlank() -> mutable.update { it.copy(message = "请先填写中继端点再开启。") }
                Wenku8Endpoint.normalizeRelayBase(current) == null ->
                    mutable.update { it.copy(message = RELAY_BASE_INVALID) }

                else -> runCatching { settingsRepository.setRelayEnabled(true) }
            }
        }
    }

    /**
     * 保存中继端点：非法地址直接拒绝并提示，**不**写库、不改动运行中的端点。
     * 清空则同时关闭开关，避免留下「开着但没端点」的死配置。
     */
    fun setRelayBase(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            viewModelScope.launch {
                Wenku8Endpoint.applyRelay(null)
                runCatching { settingsRepository.setRelayEnabled(false) }
                runCatching { settingsRepository.setRelayBase("") }
            }
            return
        }
        if (Wenku8Endpoint.normalizeRelayBase(trimmed) == null) {
            mutable.update { it.copy(message = RELAY_BASE_INVALID) }
            return
        }
        viewModelScope.launch { runCatching { settingsRepository.setRelayBase(trimmed) } }
    }

    /** 书架「读到哪了」：bookId → 阅读断点。 */
    val readingProgress = settingsRepository.allProgress()

    init {
        viewModelScope.launch {
            manager.jobs.collect { jobs ->
                mutable.update { current ->
                    val active = current.activeJobId?.let { id -> jobs.values.firstOrNull { it.id == id } }
                    current.copy(jobs = jobs.values.sortedByDescending { it.createdAt }, exportStep = if (active != null && current.exportStep == ExportStep.PROGRESS) ExportStep.PROGRESS else current.exportStep)
                }
            }
        }
        mutable.update { it.copy(loggedIn = app.sessionStore.hasSession()) }
        // 冷启动已有会话时拉取官方标签（tags.php，登录墙内）；失败静默，本地索引标签兜底
        if (app.sessionStore.hasSession()) loadOfficialTags()
        viewModelScope.launch {
            settingsRepository.searchHistory.collect { history -> mutable.update { it.copy(searchHistory = history) } }
        }
        viewModelScope.launch {
            bookshelfRepository.entries.collect { entries -> mutable.update { it.copy(bookshelf = entries) } }
        }
        viewModelScope.launch {
            readingStatsRepository.stats.collect { stats -> mutable.update { it.copy(readingStats = stats) } }
        }
        viewModelScope.launch {
            settingsRepository.readerSettings.collect { settings ->
                mutable.update { it.copy(readerSettings = settings) }
            }
        }
        viewModelScope.launch {
            catalogRepository.state.collect { catalog ->
                val stats = catalog.stats
                mutable.update {
                    it.copy(
                        catalogSize = stats.count,
                        // 标签区 = 官方标签（登录后从 tags.php 拉取）+ 本地索引标签
                        exploreTags = mergedTags(catalogRepository.tagList()),
                        catalogUpdatedAt = stats.lastUpdatedAt,
                        catalogLoading = catalog.loading,
                        catalogProgress = catalog.progress,
                        exploreMessage = catalog.message ?: it.exploreMessage,
                    )
                }
            }
        }
    }

    fun setTab(tab: StudioTab) = mutable.update { it.copy(tab = tab, message = null) }

    fun openSettingsSection(section: SettingsSection) = mutable.update { it.copy(settingsSection = section) }
    fun backSettings() = mutable.update { it.copy(settingsSection = SettingsSection.OVERVIEW) }

    /**
     * 探索榜单两类入口：
     * - **登录墙内**（`requiresAuth = true`）：`toplist.php` 的四个排序榜，
     *   走用户**自己的**会话 Cookie（AGENTS §4.5.3：本人登录后按账号权限访问不算规避）；
     * - **匿名可访问**：年度精选榜与月度新书榜（`/zt/` 公开页）。
     *
     * 未登录点登录墙榜单只会得到登录提示，不做任何规避；`articlelist.php` 仍不使用。
     */
    val explorePages: List<ExplorePage> = buildList {
        listOf(
            "lastupdate" to "今日更新",
            "allvisit" to "热门轻小说",
            "postdate" to "新书一览",
            "anime" to "动画化作品",
        ).forEach { (sort, title) ->
            add(ExplorePage("toplist-$sort", title, Wenku8Urls.toplist(sort), requiresAuth = true))
        }
        val thisYear = java.time.Year.now().value
        (0 until 5).forEach { offset ->
            val year = thisYear - offset
            add(ExplorePage("sugoi-$year", "$year 年度精选", Wenku8Urls.sugoi(year), requiresAuth = false))
        }
        val now = java.time.YearMonth.now()
        (0 until 3).forEach { offset ->
            val ym = now.minusMonths(offset.toLong())
            val stamp = "${ym.year}${ym.monthValue.toString().padStart(2, '0')}"
            add(ExplorePage("booklist-$stamp", "${ym.monthValue} 月新书", Wenku8Urls.booklist(stamp), requiresAuth = false))
        }
    }

    /**
     * 加载探索页。
     *
     * 0.14.0 起改为**自动获取**（原 AGENTS §4.5「被动触发」已废止）：本地索引命中的书先
     * 铺上去让界面立刻可见，随后缺失的书**自动**走书源（`articleinfo.php`）补全并逐个追加，
     * 不再要求用户去设置页手动点「更新书目缓存」。
     *
     * 补全上限 [EXPLORE_AUTO_FETCH_LIMIT]：全局限流是 1 秒/请求，一次榜单全抓会让用户
     * 干等；超过上限的部分留到用户手动刷新或进入详情时再取。
     */
    /**
     * 0.18.0：Cloudflare 验证窗口。
     *
     * [challengeUrl] 非空时 UI 自动打开验证窗口（用户在 WebView 里亲手完成交互）；
     * [onChallengeVerified] 在窗口关闭且通过后重试失败的请求。
     */
    fun openChallengeVerification(url: String) {
        mutable.update { it.copy(challengeUrl = url) }
    }

    fun dismissChallengeVerification() = mutable.update { it.copy(challengeUrl = null) }

    fun onChallengeVerified() {
        val current = state.value
        mutable.update { it.copy(challengeUrl = null, message = "验证完成，正在重试…") }
        current.activeExplorePage?.let { page -> loadExplore(page) }
        current.exploreDetailId?.let { id -> loadExploreDetail(id, current.exploreDetailSeed) }
    }

    /** 判断错误是否为 Cloudflare 拦截页（解析层已把挑战页转成该错误码）。 */
    private fun isChallenge(error: Throwable): Boolean =
        (error as? Wenku8Exception)?.code == "UPSTREAM_CHALLENGE"

    /** 0.18.0：进入/离开独立搜索页（探索页 TopBar 搜索入口）。 */
    fun openSearchPage() = mutable.update { it.copy(searchPageOpen = true) }
    fun closeSearchPage() = mutable.update { it.copy(searchPageOpen = false) }

    /** 0.18.0：打开某榜单的全屏展开页并立即拉取；[refreshExplore] 按同一榜单重抓。 */
    fun openExploreExpanded(page: ExplorePage) {
        mutable.update { it.copy(exploreExpanded = true, activeExplorePage = page) }
        loadExplore(page)
    }

    fun closeExploreExpanded() = mutable.update { it.copy(exploreExpanded = false) }

    fun refreshExplore() {
        state.value.activeExplorePage?.let { page -> loadExplore(page) }
    }

    fun loadExplore(page: ExplorePage = explorePages.first()) {
        viewModelScope.launch {
            mutable.update { it.copy(exploreBusy = true, exploreMessage = null, activeExplorePage = page) }
            var cards: List<SearchBook> = emptyList()
            val ids = runCatching {
                if (page.requiresAuth) {
                    // 登录墙内榜单：走共享限流客户端 + 用户会话 Cookie，
                    // 登录页识别与会话清理在数据源里完成（不尝试绕过）
                    cards = exploreRepository.load(page)
                    cards.map { it.id }
                } else {
                    // 公开页客户端：不带会话 Cookie，但带 cf_clearance（否则验证过后依然403）
                    val publicClient = Wenku8HttpClient(File(app.cacheDir, "wenku8-explore"), app.sessionStore.clearanceCookieJar())
                    val resource = publicClient.fetchText(page.url, "explore-public", Wenku8Urls.BASE)
                    Wenku8Parser.parseBookLinks(resource.html, resource.finalUrl).map { it.id }
                }
            }.getOrElse { error ->
                mutable.update { it.copy(exploreBusy = false, exploreMessage = error.message ?: "加载失败。") }
                // Cloudflare 拦截：弹验证窗口让用户本人完成交互，通过后自动重试（0.18.0）
                if (isChallenge(error)) mutable.update { it.copy(challengeUrl = page.url) }
                return@launch
            }
            if (ids.isEmpty()) {
                mutable.update {
                    it.copy(exploreBusy = false, exploreRows = emptyList(), exploreMessage = "该榜单暂时没有内容。")
                }
                return@launch
            }

            // 登录墙榜单自带完整卡片（标题/作者/封面），本地索引条目字段更全时优先本地，
            // 其余直接用卡片，不必等逐本补全就能显示
            val cardById = cards.associateBy { it.id }
            val collected = ids.mapNotNull { id -> catalogRepository.get(id)?.toSearchBook() ?: cardById[id] }.toMutableList()
            publishExploreRows(page, collected)
            mutable.update { it.copy(exploreBusy = false) }

            val missing = ids.filter { catalogRepository.get(it) == null }.take(EXPLORE_AUTO_FETCH_LIMIT)
            if (missing.isEmpty()) return@launch
            missing.forEachIndexed { index, id ->
                val info = app.bookSource.getBookInformation(id).get() ?: return@forEachIndexed
                collected += info.toSearchBook()
                publishExploreRows(page, collected)
                mutable.update { it.copy(exploreMessage = "正在补全 ${index + 1}/${missing.size}…") }
            }
            mutable.update { it.copy(exploreMessage = null) }
        }
    }

    private fun publishExploreRows(page: ExplorePage, books: List<SearchBook>) {
        mutable.update {
            it.copy(
                exploreRows = if (books.isEmpty()) {
                    emptyList()
                } else {
                    listOf(ExploreBooksRow(page.title, books, page.id))
                },
            )
        }
    }

    /** 书源详情 → 探索页卡片。字段缺失时给空值，由 UI 兜底显示。 */
    internal fun BookInformation.toSearchBook(): SearchBook = SearchBook(
        id = id,
        title = title,
        author = author,
        category = publishingHouse,
        status = if (isComplete) "已完结" else "连载中",
        updatedAt = if (lastUpdated == LocalDateTime.MIN) "" else lastUpdated.toLocalDate().toString(),
        wordCount = wordCount.count.takeIf { it > 0 }?.toLong(),
        coverUrl = coverUri.takeIf { it != Uri.EMPTY }?.toString(),
        sourceUrl = Wenku8Urls.articleInfo(id),
    )

    /** 免登录本地搜索。 */
    fun searchLocal() {
        val query = state.value.searchQuery.trim()
        if (query.isEmpty()) {
            mutable.update { it.copy(localResults = emptyList(), searchMessage = null) }
            return
        }
        val field = if (state.value.searchField == SearchField.AUTHOR) CatalogSearchField.AUTHOR else CatalogSearchField.TITLE
        viewModelScope.launch {
            val results = withContext(Dispatchers.Default) { catalogRepository.search(query, field) }
            mutable.update {
                it.copy(
                    localResults = results,
                    searchMessage = if (results.isEmpty() && it.catalogSize == 0) "本地书目为空，请先到设置页更新书目缓存。" else null,
                )
            }
        }
    }

    /**
     * 标签浏览：本地索引**立即**出结果（零网络、断网可用）；已登录时再用官方
     * `tags.php?t=X` 的结果补齐（站点全量，含尚未抓进索引的书），合并去重后覆盖显示。
     * 服务端失败（断网/登录过期）保留本地结果，只提示原因。
     */
    fun selectTag(tag: String) {
        val local = catalogRepository.searchTag(tag).map { it.toSearchBook() }
        mutable.update { it.copy(activeTag = tag, tagResults = local) }
        if (!app.sessionStore.hasSession()) return
        viewModelScope.launch {
            runCatching { exploreRepository.tagBooks(tag) }
                .onSuccess { server ->
                    if (server.isEmpty()) return@onSuccess
                    val serverIds = server.map { it.id }.toSet()
                    mutable.update {
                        it.copy(
                            // 官方结果在前，本地索引里多出的书补在后面
                            tagResults = server + local.filter { book -> book.id !in serverIds },
                            exploreMessage = null,
                            loggedIn = app.sessionStore.hasSession(),
                        )
                    }
                }
                .onFailure { error ->
                    mutable.update {
                        it.copy(
                            exploreMessage = error.message ?: "标签浏览失败，已显示本地结果。",
                            loggedIn = app.sessionStore.hasSession(),
                        )
                    }
                }
        }
    }

    /** 退出标签浏览。 */
    fun clearTag() = mutable.update { it.copy(activeTag = null, tagResults = emptyList()) }

    /** 官方标签在前（站点规范词表），本地索引里多出的标签跟在后面。 */
    private fun mergedTags(local: List<String>): List<String> = (officialTags + local).distinct()

    private fun loadOfficialTags() {
        viewModelScope.launch {
            runCatching { exploreRepository.officialTags() }.onSuccess { tags ->
                if (tags.isEmpty()) return@onSuccess
                officialTags = tags
                mutable.update { it.copy(exploreTags = mergedTags(catalogRepository.tagList())) }
            }
        }
    }

    fun updateCatalog(budget: Int = 200) = catalogRepository.update(budget)    fun clearCatalog() = catalogRepository.clear()

    /** 由用户在书架操作界面显式触发：抓取该作者的作品。被动触发，不由打开页面自动执行。 */
    fun expandAuthor(bookId: String) {
        val before = catalogRepository.cachedSize()
        viewModelScope.launch {
            mutable.update { it.copy(exploreMessage = "正在抓取同作者作品…") }
            catalogRepository.expandAuthor(bookId) { progress ->
                mutable.update { it.copy(exploreMessage = progress) }
            }
            val added = catalogRepository.cachedSize() - before
            mutable.update { it.copy(exploreMessage = "同作者作品已补充 $added 本") }
        }
    }

    /** 显式加载单本完整详情（仅由用户点击触发）。 */
    fun loadFullDetail(bookId: String, onDone: () -> Unit = {}) = catalogRepository.ensureBook(bookId) {
        onDone()
    }

    fun catalogTagList(): List<String> = catalogRepository.tagList()

    fun addToShelf(book: Book, chapterCount: Int = state.value.index?.chapters?.size ?: 0) {
        val id = book.id ?: book.bookUrl.substringAfterLast('/').removeSuffix(".htm")
        viewModelScope.launch {
            bookshelfRepository.add(
                BookshelfEntry(
                    id = "wenku8:$id",
                    bookId = id,
                    title = book.title,
                    author = book.author,
                    source = BookshelfSource.WENKU8,
                    sourceUrl = book.sourceUrl,
                    coverUrl = book.coverUrl,
                    chapterCount = chapterCount,
                    wordCount = book.wordCount,
                )
            )
            mutable.update { it.copy(message = "已加入书架") }
        }
    }

    fun addSearchToShelf(book: SearchBook) {
        viewModelScope.launch {
            bookshelfRepository.add(BookshelfEntry(id = "wenku8:${book.id}", bookId = book.id, title = book.title, author = book.author, sourceUrl = book.sourceUrl, coverUrl = book.coverUrl, wordCount = book.wordCount))
            mutable.update { it.copy(message = "已加入书架") }
        }
    }

    fun addLocalEpub(uri: String, title: String = "本地 EPUB") {
        val id = "local:${uri.hashCode()}"
        viewModelScope.launch {
            bookshelfRepository.add(BookshelfEntry(id = id, bookId = id, title = title, source = BookshelfSource.LOCAL_EPUB, localUri = uri))
        }
    }

    fun removeFromShelf(id: String) { viewModelScope.launch { bookshelfRepository.remove(id) } }
    fun setPinned(id: String, pinned: Boolean) { viewModelScope.launch { bookshelfRepository.setPinned(id, pinned) } }
    fun markShelfRead(id: String) { viewModelScope.launch { bookshelfRepository.recordRead(id) } }
    /**
     * 从书架卡片发起导出向导：先解析目录，成功后直接进入选章节。
     * 失败时停在 [ExportStep.RESOLVING] 并给出可重试的错误文案。
     */
    fun startExportFromShelf(entry: BookshelfEntry) {
        mutable.update { ExportWizard.begin(it, entry.sourceUrl) }
        resolveForExport()
    }

    /** 关闭向导，回到发起前的 tab（向导只是全屏覆盖层，底栏始终可见）。 */
    fun closeExport() = mutable.update { ExportWizard.close(it) }

    /**
     * 书架远程书的「详情」：复用探索详情那条公开数据通路（`ExploreDetailRepository`），
     * 不重新引入创建流程的解析管线，也不碰 [StudioUiState.book]。
     */
    fun openShelfBookDetail(entry: BookshelfEntry) {
        val seed = com.example.hyperreader.core.ExploreBookSeed(
            id = entry.bookId,
            title = entry.title,
            author = entry.author,
            coverUrl = entry.coverUrl,
            sourceUrl = entry.sourceUrl,
        )
        mutable.update { ExportWizard.openShelfDetail(it, seed) }
        loadExploreDetail(entry.bookId, seed)
    }
    fun clearReadingStats() { viewModelScope.launch { readingStatsRepository.clear() } }

    fun setSearch(value: String) = mutable.update { it.copy(search = value) }
    fun setCover(value: Boolean) = mutable.update { it.copy(includeCover = value) }
    fun clearMessage() = mutable.update { it.copy(message = null) }

    fun setSearchQuery(value: String) = startNewSearch { it.copy(searchQuery = value) }
    fun setSearchField(value: SearchField) = startNewSearch { it.copy(searchField = value) }

    /**
     * 换查询条件：作废在途请求（靠 [searchRequestId]）并清掉分页状态。
     *
     * 已加载的结果保留在屏幕上（不会闪空），下一次点「搜索」整体覆盖。
     */
    private inline fun startNewSearch(transform: (StudioUiState) -> StudioUiState) {
        searchRequestId += 1
        mutable.update {
            transform(it).copy(
                searchMessage = null,
                searchBusy = false,
                searchLoadingMore = false,
                searchHasNextPage = false,
                searchPage = 0,
            )
        }
    }
    /** 0.18.0：退出登录 = 只清本地会话（不碰服务器），并同步顶栏与各处登录态。 */
    fun logout() {
        app.sessionStore.clear()
        refreshSession()
    }

    /**
     * 0.18.0：测试中继连通性。
     *
     * 拿一个**匿名公开页**（`articleinfo.php`）去请求当前公开端点：
     * 中继关闭时测的就是直连；请求不带会话 Cookie（`carriesSession` 只允许 wenku8 域携带）。
     */
    fun testRelay() {
        viewModelScope.launch {
            mutable.update { it.copy(message = "正在测试公开端点…") }
            val url = com.example.hyperreader.core.Wenku8Urls.articleInfo("2365")
            runCatching { manager.httpClient().fetchTextInteractive(url, "relay-test", com.example.hyperreader.core.Wenku8Urls.BASE) }
                .onSuccess { response ->
                    val ok = !com.example.hyperreader.core.Wenku8Parser.looksLikeChallenge(response.html)
                    mutable.update {
                        it.copy(
                            message = if (ok) "公开端点可用（${com.example.hyperreader.core.Wenku8Endpoint.publicBase()}）" else "源站返回了浏览器验证页，当前网络下直连/中继都无法匿名取数。"
                        )
                    }
                }
                .onFailure { error ->
                    mutable.update { it.copy(message = "公开端点不可用：${error.message ?: "请求失败"}") }
                }
        }
    }

    fun refreshSession() {
        val loggedIn = app.sessionStore.hasSession()
        mutable.update { it.copy(loggedIn = loggedIn) }
        if (loggedIn && officialTags.isEmpty()) loadOfficialTags()
    }
    fun clearSearchHistory() { viewModelScope.launch { settingsRepository.clearSearchHistory() } }
    fun searchBooks() {
        val current = state.value
        if (current.searchQuery.isBlank()) return
        val requestId = ++searchRequestId
        mutable.update {
            it.copy(
                searchBusy = true,
                searchLoadingMore = false,
                searchHasNextPage = false,
                searchPage = 0,
                searchMessage = null,
            )
        }
        viewModelScope.launch {
            runCatching { searchProvider.search(current.searchQuery, current.searchField, 1) }
                .onSuccess { page ->
                    if (SearchPager.isCurrent(requestId, searchRequestId)) {
                        mutable.update { SearchPager.firstPage(it, page, app.sessionStore.hasSession()) }
                        settingsRepository.recordSearch(current.searchQuery)
                    }
                }
                .onFailure { error ->
                    if (SearchPager.isCurrent(requestId, searchRequestId)) {
                        mutable.update { state ->
                            state.copy(
                                searchBusy = false,
                                searchLoadingMore = false,
                                searchMessage = error.message ?: "搜索失败。",
                                loggedIn = app.sessionStore.hasSession(),
                            )
                        }
                        if ((error as? Wenku8Exception)?.code == "AUTH_REQUIRED") refreshSession()
                    }
                }
        }
    }

    fun loadMoreSearchResults() {
        val current = state.value
        if (current.searchQuery.isBlank() || current.searchBusy || current.searchLoadingMore || !current.searchHasNextPage) return
        val requestId = ++searchRequestId
        val nextPage = current.searchPage + 1
        mutable.update { it.copy(searchLoadingMore = true, searchMessage = null) }
        viewModelScope.launch {
            runCatching { searchProvider.search(current.searchQuery, current.searchField, nextPage) }
                .onSuccess { page ->
                    if (SearchPager.isCurrent(requestId, searchRequestId)) {
                        mutable.update { SearchPager.morePage(it, page, app.sessionStore.hasSession()) }
                    }
                }
                .onFailure { error ->
                    if (SearchPager.isCurrent(requestId, searchRequestId)) {
                        mutable.update { state ->
                            state.copy(
                                searchLoadingMore = false,
                                searchMessage = error.message ?: "加载更多失败。",
                                loggedIn = app.sessionStore.hasSession(),
                            )
                        }
                        if ((error as? Wenku8Exception)?.code == "AUTH_REQUIRED") refreshSession()
                    }
                }
        }
    }
    /**
     * 探索页点书：打开**独立的书籍详情页**。
     *
     * 刻意**不走** `resolveForExport()` / 导出向导：那条链路会做导出前置校验并吃满 1 秒/请求 的批量节流，
     * 点一下要等很久。
     * 这里改由 [exploreDetailRepository] 直接调公开 API（`articleinfo.php` + 目录页），
     * 交互档节流 + 内存缓存，且不改动导出向导的任何状态。
     */
    fun openSearchBook(book: SearchBook) {
        val seed = com.example.hyperreader.core.ExploreBookSeed(
            id = book.id,
            title = book.title,
            author = book.author,
            category = book.category,
            status = book.status,
            updatedAt = book.updatedAt,
            wordCount = book.wordCount,
            coverUrl = book.coverUrl,
            sourceUrl = book.sourceUrl,
        )
        // 先进入详情页并置 loading：页面用 seed 立刻渲染出标题/作者，接口回来再补齐
        mutable.update {
            it.copy(
                exploreDetailId = book.id,
                exploreDetail = null,
                exploreDetailLoading = true,
                exploreDetailError = null,
                exploreDetailSeed = seed,
            )
        }
        loadExploreDetail(book.id, seed)
    }

    private fun loadExploreDetail(bookId: String, seed: com.example.hyperreader.core.ExploreBookSeed? = null) {
        viewModelScope.launch {
            mutable.update { it.copy(exploreDetailLoading = true, exploreDetailError = null) }
            runCatching { exploreDetailRepository.load(bookId, seed) }
                .onSuccess { detail ->
                    mutable.update { it.copy(exploreDetailLoading = false, exploreDetail = detail, exploreDetailError = detail.indexError) }
                }
                .onFailure { error ->
                    mutable.update { it.copy(exploreDetailLoading = false, exploreDetailError = error.message ?: "详情加载失败。") }
                }
        }
    }

    /** 详情页「重试」：先清缓存再拉，避免拿到失败前的旧结果。 */
    fun retryExploreDetail() {
        val id = state.value.exploreDetailId ?: return
        viewModelScope.launch {
            exploreDetailRepository.invalidate(id)
            loadExploreDetail(id, state.value.exploreDetailSeed)
        }
    }

    fun closeExploreDetail() = mutable.update {
        it.copy(exploreDetailId = null, exploreDetail = null, exploreDetailLoading = false, exploreDetailError = null, exploreDetailSeed = null)
    }

    /** 详情页「加入书架」（探索来源）。 */
    fun addExploreDetailToShelf() {
        val detail = state.value.exploreDetail ?: return
        val book = detail.book
        viewModelScope.launch {
            bookshelfRepository.add(
                BookshelfEntry(
                    id = "wenku8:${book.id}",
                    bookId = book.id.orEmpty(),
                    title = book.title,
                    author = book.author,
                    source = BookshelfSource.WENKU8,
                    sourceUrl = book.sourceUrl,
                    coverUrl = book.coverUrl,
                    chapterCount = detail.chapterCount,
                    wordCount = book.wordCount,
                )
            )
            mutable.update { it.copy(message = "已加入书架") }
        }
    }

    fun isOnShelf(bookId: String): Boolean = state.value.bookshelf.any { it.bookId == bookId }
    fun setThemeMode(mode: com.example.hyperreader.settings.AppThemeMode) { viewModelScope.launch { settingsRepository.setThemeMode(mode) } }
    fun setDynamicColor(enabled: Boolean) { viewModelScope.launch { settingsRepository.setDynamicColor(enabled) } }
    fun setAccentColor(color: Int) { viewModelScope.launch { settingsRepository.setAccentColor(color) } }

    // 阅读器设置：与阅读器共用同一 DataStore，改动实时生效
    fun setReaderFontSize(value: Float) { viewModelScope.launch { settingsRepository.setReaderFontSize(value) } }
    fun setReaderFontWeight(value: Int) { viewModelScope.launch { settingsRepository.setReaderFontWeight(value) } }
    fun setReaderLineHeight(value: Float) { viewModelScope.launch { settingsRepository.setReaderLineHeight(value) } }
    fun setReaderSpacing(value: Int) { viewModelScope.launch { settingsRepository.setReaderParagraphSpacing(value) } }
    fun setReaderPadding(value: Int) { viewModelScope.launch { settingsRepository.setReaderHorizontalPadding(value) } }
    fun setReaderBackground(value: com.example.hyperreader.settings.ReaderBackground) { viewModelScope.launch { settingsRepository.setReaderBackground(value) } }
    fun setReaderBackgroundColor(value: Int) { viewModelScope.launch { settingsRepository.setReaderCustomBackground(value) } }
    fun setReaderTextColor(value: Int) { viewModelScope.launch { settingsRepository.setReaderTextColor(value) } }
    fun setReaderPageMode(value: com.example.hyperreader.settings.ReaderPageTurnMode) { viewModelScope.launch { settingsRepository.setReaderPageTurn(value) } }
    fun setReaderKeepScreenOn(value: Boolean) { viewModelScope.launch { settingsRepository.setReaderKeepScreenOn(value) } }
    fun setReaderImmersive(value: Boolean) { viewModelScope.launch { settingsRepository.setReaderImmersive(value) } }
    fun resetReaderFont() { viewModelScope.launch { settingsRepository.setReaderFontUri(null) } }
    fun applyImportedFont(path: String) { viewModelScope.launch { settingsRepository.setReaderFontUri(path) } }

    /**
     * 导出向导的目录解析（[ExportStep.RESOLVING] → [ExportStep.CHAPTERS]）。
     *
     * 来源固定为发起导出的那本书的 `sourceUrl`（由 [startExportFromShelf] 写入）；
     * 解析成功默认全选并进入章节页，失败则留在解析态供重试。
     */
    fun resolveForExport() {
        val value = state.value.sourceUrl.trim()
        if (value.isEmpty()) { mutable.update { it.copy(busy = false, detailError = "缺少书籍来源网址。") }; return }
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, detailError = null) }
            runCatching { manager.parseSource(value) }
                .onSuccess { (book, index) -> mutable.update { ExportWizard.resolved(it, book, index) } }
                .onFailure { error -> mutable.update { ExportWizard.failed(it, error.message ?: "目录解析失败。") } }
        }
    }

    /** 解析失败后的重试（同一本书、同一目录）。 */
    fun retryResolve() = resolveForExport()

    fun selectAll() = mutable.update { current -> current.copy(selectedIds = current.index?.chapters?.map { it.id }?.toSet() ?: emptySet()) }
    fun clearSelection() = mutable.update { it.copy(selectedIds = emptySet()) }
    fun toggleChapter(id: String) = mutable.update { current -> current.copy(selectedIds = if (id in current.selectedIds) current.selectedIds - id else current.selectedIds + id) }
    fun toExport() {
        if (state.value.selectedIds.isEmpty()) { mutable.update { it.copy(message = "请至少选择一个章节。") }; return }
        mutable.update { it.copy(exportStep = ExportStep.PACKAGING, message = null) }
    }
    fun backToChapters() = mutable.update { it.copy(exportStep = ExportStep.CHAPTERS, message = null) }

    fun startExport() {
        val current = state.value
        val book = current.book ?: return
        val chapters = current.index?.chapters?.filter { it.id in current.selectedIds } ?: return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, message = null) }
            runCatching { manager.create(book, chapters, current.includeCover) }
                .onSuccess { job -> mutable.update { it.copy(busy = false, activeJobId = job.id, exportStep = ExportStep.PROGRESS) } }
                .onFailure { error -> mutable.update { it.copy(busy = false, message = error.message ?: "无法创建任务。") } }
        }
    }

    fun cancel(id: String) = manager.cancel(id)

    /**
     * 从探索详情页直接导出：用该页已加载的目录建任务，**不经过创建流程的选章步骤**。
     *
     * @param engine 指定导出引擎（两个按钮各一个），不受全局设置影响；
     *   传 null 则用设置里的全局选择。
     */
    fun exportExploreDetail(engine: com.example.hyperreader.settings.EpubEngine? = null) {
        val detail = state.value.exploreDetail
        val book = detail?.book
        if (book == null) {
            mutable.update { it.copy(exploreDetailError = "书籍信息尚未加载完成，暂时无法导出。") }
            return
        }
        if (detail.chapters.isEmpty()) {
            mutable.update { it.copy(exploreDetailError = "目录尚未就绪，无法导出；请先重试加载目录。") }
            return
        }
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, message = null) }
            runCatching { manager.create(book, detail.chapters, includeCover = true, engine = engine) }
                .onSuccess { job ->
                    mutable.update {
                        it.copy(
                            busy = false,
                            activeJobId = job.id,
                            exportStep = ExportStep.PROGRESS,
                            exploreDetailId = null,
                        )
                    }
                }
                .onFailure { error -> mutable.update { it.copy(busy = false, message = error.message ?: "无法创建任务。") } }
        }
    }

    // ---- 配置导入导出（凭据安全模型见 settings/ConfigTransfer.kt）----

    private val configMutable = MutableStateFlow(ConfigUiState())
    val configUi: StateFlow<ConfigUiState> = configMutable.asStateFlow()

    fun exportConfigTo(uri: android.net.Uri) = viewModelScope.launch {
        configMutable.update { it.copy(busy = true, message = null, isError = false) }
        val theme = settingsRepository.appTheme.first()
        val reader = settingsRepository.readerSettings.first()
        val text = ConfigTransfer.encode(theme, reader, BuildConfig.VERSION_NAME, System.currentTimeMillis())
        ConfigTransferFile.write(app, uri, text).fold(
            onSuccess = { configMutable.update { it.copy(busy = false, isError = false, message = "已导出主题与阅读器设置") } },
            onFailure = { error -> configMutable.update { it.copy(busy = false, isError = true, message = "导出失败：${error.message ?: "未知错误"}") } },
        )
    }

    fun importConfigFrom(uri: android.net.Uri) = viewModelScope.launch {
        configMutable.update { it.copy(busy = true, message = null, isError = false) }
        ConfigTransferFile.read(app, uri).fold(
            onSuccess = { text ->
                when (val decoded = ConfigTransfer.decode(text)) {
                    is ConfigTransfer.DecodeResult.Rejected ->
                        configMutable.update { it.copy(busy = false, isError = true, message = decoded.reason) }
                    is ConfigTransfer.DecodeResult.Success -> {
                        val plan = ConfigTransfer.plan(decoded.document)
                        val result = ConfigTransfer.apply(settingsRepository, plan)
                        val tail = if (result.failed > 0) "，${result.failed} 项写入失败" else ""
                        configMutable.update { it.copy(busy = false, isError = false, message = "${plan.summary()}$tail\n${plan.details()}") }
                    }
                }
            },
            onFailure = { error -> configMutable.update { it.copy(busy = false, isError = true, message = "读取失败：${error.message ?: "未知错误"}") } },
        )
    }

    fun setShowJobHistory(show: Boolean) = mutable.update { it.copy(showJobHistory = show) }

    /**
     * 通知栏点击路由。未知或缺失的路由一律忽略，幂等：
     * 同一个 Intent 重复投递不会产生额外跳转。
     */
    fun route(route: String?, jobId: String?) {
        when (val target = NotificationRoute.resolve(route, jobId, state.value.jobs)) {
            null -> Unit
            is RouteTarget.Progress -> mutable.update {
                it.copy(exportStep = ExportStep.PROGRESS, activeJobId = target.jobId, showJobHistory = false)
            }
            RouteTarget.History -> mutable.update { it.copy(tab = StudioTab.BOOKSHELF, showJobHistory = true) }
            RouteTarget.Bookshelf -> mutable.update { it.copy(tab = StudioTab.BOOKSHELF) }
        }
    }

    companion object {
        const val ROUTE_EXPORT_PROGRESS = "export_progress"
        const val ROUTE_JOB_HISTORY = "history"
        const val ROUTE_BOOKSHELF = "bookshelf"
        const val EXTRA_ROUTE = "route"
        const val EXTRA_JOB_ID = "job_id"

        /**
         * 探索页单次自动补全的书目上限。全局限流是 1 秒/请求，一次榜单动辄 30+ 本，
         * 全抓会让用户干等 30 秒以上；超出的部分留给手动刷新或进入详情时再取。
         */
        const val EXPLORE_AUTO_FETCH_LIMIT = 20

        /** 中继端点非法时的统一提示（要求 https 域名，禁止 IP 与 wenku8 自身域名）。 */
        private const val RELAY_BASE_INVALID = "中继端点无效：需形如 https://relay.example.com，不能用 IP 或 wenku8 自身域名。"
    }

    fun save(id: String) {
        runCatching { manager.save(id) }
            .onFailure { error -> mutable.update { it.copy(message = error.message ?: "保存失败。") } }
    }
    fun share(id: String) = manager.share(id)
}

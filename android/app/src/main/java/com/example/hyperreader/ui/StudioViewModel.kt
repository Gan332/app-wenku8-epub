package com.example.hyperreader.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hyperreader.Wenku8Application
import com.example.hyperreader.core.CatalogEntry
import com.example.hyperreader.core.AppUpdateInstaller
import com.example.hyperreader.core.BookshelfUpdateChecker
import com.example.hyperreader.core.UpdateCheckOutcome
import com.example.hyperreader.core.CatalogSearchField
import com.example.hyperreader.core.ExploreBooksRow
import com.example.hyperreader.core.ExplorePage
import com.example.hyperreader.core.ExploreFeed
import com.example.hyperreader.core.ExploreCardCache
import com.example.hyperreader.core.Wenku8Endpoint
import com.example.hyperreader.core.Wenku8Exception
import com.example.hyperreader.core.Wenku8HttpClient
import com.example.hyperreader.core.Wenku8Parser
import com.example.hyperreader.core.Wenku8Urls
import com.example.hyperreader.model.Book
import com.example.hyperreader.model.BookIndex
import com.example.hyperreader.model.BookshelfEntry
import com.example.hyperreader.model.BookshelfSource
import com.example.hyperreader.model.normalizeShelfTag
import com.example.hyperreader.model.ExportJob
import com.example.hyperreader.model.JobStatus
import com.example.hyperreader.model.ReadingStats
import com.example.hyperreader.reader.OnlineCacheJob
import android.net.Uri
import com.example.hyperreader.model.SearchBook
import com.github.michaelbull.result.get
import io.nightfish.lightnovelreader.api.book.BookInformation
import java.time.LocalDateTime
import com.example.hyperreader.model.SearchField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import kotlinx.coroutines.flow.update
import com.example.hyperreader.BuildConfig
import com.example.hyperreader.settings.ConfigTransfer
import com.example.hyperreader.settings.BackupTransfer
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

/**
 * 底部主导航（0.19.0 起四项，对齐 LNR `MainDestination`）。
 *
 * 顺序与上游一致：阅读(0) / 书架(1) / 探索(2) / 设置(3)，**阅读是默认首屏**
 * ——`MainDestination.Reading(0, Route.Main.Reading.Home)` 就是 `entries[0]`，
 * LNR 的 `NavHost` 以它作为起始目的。
 *
 * 「导出」**不在**一级导航里：它是书架卡片菜单与探索详情页里的次级动作
 * （AGENTS §4.6.1 第 1 条的立法意图是防止导出占一级入口，本条保留该意图）。
 */
enum class StudioTab { READING, BOOKSHELF, EXPLORE, SETTINGS }

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
        // 上一轮的章节筛选不能带到新书：否则章节列表被过滤成空，看起来像「目录没解析出来」
        search = "",
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
        // 与 openSearchBook 同一套序号：上一本的迟到响应不能落到这本书上。
        exploreDetailRequestId = state.exploreDetailRequestId + 1,
    )
}

/**
 * 公开端点诊断结果（0.18.0）。
 *
 * @param status 简短结论：`OK 200·1.2s·25KB` / `HTTP 403` / 异常信息
 */
data class EndpointProbe(val label: String, val clientKind: String, val url: String, val status: String)

/** 设置二级界面分区。0.18.0：新增 ACCOUNT（账号）与 NETWORK（网络/中继）两类。 */
enum class SettingsSection { OVERVIEW, ACCOUNT, APPEARANCE, READER, NETWORK, STATISTICS, CATALOG, CONFIG, ABOUT }

data class StudioUiState(
    val tab: StudioTab = StudioTab.READING,
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
    /** 探索请求序号：旧响应返回时据此丢弃，避免快速切榜时后发先至。 */
    val exploreRequestId: Long = 0,
    /** 0.18.0：待完成的 Cloudflare 验证地址；非空时 UI 自动弹验证窗口。 */
    val clearancePresent: Boolean = false,
    /** 0.18.0：公开端点诊断矩阵（设置 → 网络 → 深度诊断）。 */
    val endpointProbes: List<EndpointProbe> = emptyList(),
    val jobs: List<ExportJob> = emptyList(),
    /** 在线阅读「缓存全书」任务（书架顶部进度区，不占导出记录）。 */
    val cacheJobs: List<OnlineCacheJob> = emptyList(),
    /** 每本书已缓存的章节数（书架卡片显示 n / 总章数）。 */
    val cacheCounts: Map<String, Int> = emptyMap(),
    /** 正在检查书架更新。 */
    val checkingUpdates: Boolean = false,
    /** 书架批量导出：准备阶段（逐本解析目录并加入队列）。 */
    val batchExportBusy: Boolean = false,
    val batchExportDone: Int = 0,
    val batchExportTotal: Int = 0,
    /** 正在测速直连入口。 */
    val endpointProbing: Boolean = false,
    /** 启动时自动检查书架更新（设置项）。 */
    val autoCheckUpdates: Boolean = true,
    /** 每日阅读目标（分钟）；0 = 不设目标。 */
    val readingGoalMinutes: Int = 30,
    /** 「缓存全书」仅 WiFi（设置项）。 */
    val cacheWifiOnly: Boolean = false,
    /** 离线模式：阅读器只读缓存，不发任何网络请求（设置项）。 */
    val offlineMode: Boolean = false,
    /** 在线阅读正文 + 目录快照的磁盘占用（设置 → 书目缓存）。 */
    val onlineCacheBytes: Long = 0L,
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
    /**
     * 书架是否已从 DataStore 读到首帧。
     *
     * [bookshelf] 初值是空列表，而 DataStore 首次 emit 之前 UI 会直接渲染空态卡——
     * 冷启动先闪一屏「书架还是空的」，再被真实列表整体替换。区分「还没读到」与
     * 「确实是空的」，让首屏渲染骨架而不是误报空态。
     */
    val bookshelfLoaded: Boolean = false,
    /** 书架排序方式（持久化设置；0.19.0-alpha03 起用户可选）。 */
    val bookshelfSort: com.example.hyperreader.settings.BookshelfSort = com.example.hyperreader.settings.BookshelfSort.RecentRead,
    val readingStats: ReadingStats = ReadingStats(),
    val exploreRows: List<ExploreBooksRow> = emptyList(),
    val exploreBusy: Boolean = false,
    /** 下拉刷新/首次加载的指示器状态；后台逐本补全不算刷新。 */
    val exploreRefreshing: Boolean = false,
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
    /** 标签请求的独立提示（服务端失败时保留本地结果）。 */
    val tagMessage: String? = null,
    /** 标签服务端补齐是否仍在进行（本地结果已有时用于区分“空”与“加载中”）。 */
    val tagBusy: Boolean = false,
    /**
     * 探索页是否已经触发过自动加载。
     *
     * 切回 tab 时不再重复请求；下拉刷新/切换榜单仍可显式重抓。
     */
    val exploreLoadedOnce: Boolean = false,
    val catalogLoading: Boolean = false,
    val catalogProgress: Pair<Int, Int>? = null,
    val catalogUpdatedAt: Long = 0L,
    /** 书目缓存操作的独立提示（不再混进探索页消息）。 */
    val catalogMessage: String? = null,
    /** 应用更新检查/下载状态（仅「设置 → 关于」使用，与书架更新检查无关）。 */
    val appUpdateChecking: Boolean = false,
    val appUpdateDownloading: Boolean = false,
    val appUpdateProgress: Int = 0,
    val appUpdateAvailable: com.example.hyperreader.core.AppRelease? = null,
    /** 已下载并通过校验的 APK 路径；非空时 UI 显示「安装更新」。 */
    val appUpdateFile: String? = null,
    val appUpdateMessage: String? = null,
    val coverCacheBytes: Long = 0L,
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
    /** 详情请求序号：同一本书的重试也可能先发后至，只比较 bookId 不够。 */
    val exploreDetailRequestId: Long = 0,
    /** 0.19.0：跨书书签中心是否打开（全屏页，不占一级导航，AGENTS §4.6.1）。 */
    val bookmarkCenterOpen: Boolean = false,
    /**
     * 同作者作品页（全屏覆盖层）。
     *
     * 从详情页或书架菜单点击「同作者」后打开；[authorWorksBooks] 先由
     * `authorarticle.php` 的书名占位，再逐本被 `articleinfo.php` 的完整条目替换。
     */
    val authorWorksOpen: Boolean = false,
    val authorWorksAnchorId: String = "",
    val authorWorksName: String = "",
    val authorWorksBooks: List<SearchBook> = emptyList(),
    val authorWorksLoading: Boolean = false,
    val authorWorksMessage: String? = null,
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
    /** 跨书书签中心（0.19.0）：读两个阅读器 Bridge 共用的 `xy_reader_bookmarks` 键。 */
    private val bookmarkCenterRepository = app.bookmarkCenterRepository
    /**
     * 搜索请求的单调序号：只有与当前值一致的那个响应能写状态，
     * 用户中途改关键词/切字段/连点「加载更多」时，旧响应直接丢弃。
     */
    private var searchRequestId = 0L
    /**
     * 同作者抓取的单调序号：连续点「同作者」或刷新时，旧请求的增量回调
     * 不得覆盖新结果（与 [searchRequestId] 同一思路）。
     */
    @Volatile
    private var authorWorksRequestId = 0L
    /** 标签请求序号：快速点选标签时旧响应不得覆盖新标签。 */
    private var tagRequestId = 0L
    /** 官方标签请求序号：登出后迟到的响应不得把官方标签又写回来。 */
    private var officialTagsRequestId = 0L
    /**
     * 公开榜已补全的卡片。
     *
     * 每轮只补 [EXPLORE_AUTO_FETCH_LIMIT] 本，已补结果必须跨轮保留，否则刷新后
     * 仍从同一批缺失项开始，超出上限的书永远轮不到。缓存只属于展示层，不写书目索引。
     */
    private val exploreCardCache = ExploreCardCache()
    private val mutable = MutableStateFlow(StudioUiState())
    val state: StateFlow<StudioUiState> = mutable.asStateFlow()
    val appTheme = settingsRepository.appTheme

    /** EPUB 导出引擎（设置页可切换）；导出任务在打包时读取当前值。 */
    val exportEngine: kotlinx.coroutines.flow.Flow<com.example.hyperreader.settings.EpubEngine> =
        settingsRepository.exportEngine

    /** 是否抹掉汉字之间的多余空格（0.19.0-alpha05；解析 EPUB 时读取）。 */
    val stripCjkGaps: kotlinx.coroutines.flow.Flow<Boolean> = settingsRepository.stripCjkGaps

    fun setStripCjkGaps(value: Boolean) = viewModelScope.launch { catching { settingsRepository.setStripCjkGaps(value) } }

    /** 第三方中继开关（仅公开页，AGENTS §4.11）。 */
    val relayEnabled: kotlinx.coroutines.flow.Flow<Boolean> = settingsRepository.relayEnabled

    /** 中继端点原文；空串表示未配置。 */
    val relayBase: kotlinx.coroutines.flow.Flow<String> = settingsRepository.relayBase

    /**
     * 首选直连入口（设置 → 网络，0.19.0）。
     *
     * 只决定**第一次**请求打哪个镜像；规范 URL、书架与 EPUB 内链仍是 `www.wenku8.net`。
     * 运行态由 [com.example.hyperreader.core.Wenku8Endpoint] 决定（Application 监听设置流应用），
     * 这里只负责读写设置。
     */
    val directEntry: kotlinx.coroutines.flow.Flow<com.example.hyperreader.settings.DirectEntry> = settingsRepository.directEntry

    /** 切换首选直连入口；下一次请求即生效（Application 的 collector 会重排入口）。 */
    fun setDirectEntry(entry: com.example.hyperreader.settings.DirectEntry) {
        viewModelScope.launch { catching { settingsRepository.setDirectEntry(entry) } }
    }

    /** 在线阅读数据源：内置解析或 LNR 书源；下次打开阅读器时生效。 */
    val onlineSource: kotlinx.coroutines.flow.Flow<com.example.hyperreader.settings.OnlineSource> =
        settingsRepository.onlineSource

    fun setOnlineSource(source: com.example.hyperreader.settings.OnlineSource) {
        viewModelScope.launch { catching { settingsRepository.setOnlineSource(source) } }
    }

    /** 屏幕刷新率策略（自适应 / 60 / 90 / 120）；主界面与阅读器窗口各自应用。 */
    val refreshRate: kotlinx.coroutines.flow.Flow<com.example.hyperreader.settings.RefreshRate> =
        settingsRepository.refreshRate

    fun setRefreshRate(rate: com.example.hyperreader.settings.RefreshRate) {
        viewModelScope.launch { catching { settingsRepository.setRefreshRate(rate) } }
    }

    /**
     * 官方标签（登录后从 `tags.php` 拉取）；未登录或抓取失败时为空，标签区只显示本地内容。
     * 声明必须在 `init` 之前：冷启动的 collector 会经 [mergedTags] 读到它。
     */
    private var officialTags: List<String> = emptyList()

    fun setExportEngine(engine: com.example.hyperreader.settings.EpubEngine) {
        viewModelScope.launch { catching { settingsRepository.setExportEngine(engine) } }
    }

    /**
     * 开关中继：需要先填合法端点，否则拒绝并说明原因。
     *
     * 真正生效由 [com.example.hyperreader.core.Wenku8Endpoint] 决定（Application 监听设置流应用），
     * 这里只做入参校验，不自己改运行态。
     */
    fun setRelayEnabled(enabled: Boolean) {
        if (!enabled) {
            viewModelScope.launch { catching { settingsRepository.setRelayEnabled(false) } }
            return
        }
        viewModelScope.launch {
            val current = settingsRepository.relayBase.first()
            when {
                current.isBlank() -> mutable.update { it.copy(message = "请先填写中继端点再开启。") }
                Wenku8Endpoint.normalizeRelayBase(current) == null ->
                    mutable.update { it.copy(message = RELAY_BASE_INVALID) }

                else -> catching { settingsRepository.setRelayEnabled(true) }
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
                catching { settingsRepository.setRelayEnabled(false) }
                catching { settingsRepository.setRelayBase("") }
            }
            return
        }
        if (Wenku8Endpoint.normalizeRelayBase(trimmed) == null) {
            mutable.update { it.copy(message = RELAY_BASE_INVALID) }
            return
        }
        viewModelScope.launch { catching { settingsRepository.setRelayBase(trimmed) } }
    }

    /** 书架「读到哪了」：bookId → 阅读断点。 */
    val readingProgress = settingsRepository.allProgress()

    /** 与 [runCatching] 相同，但协程取消必须继续向上抛，不能被当成业务失败。 */
    private suspend fun <T> catching(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }

    init {
        refreshClearanceState()
        viewModelScope.launch {
            manager.jobs.collect { jobs ->
                mutable.update { current ->
                    val active = current.activeJobId?.let { id -> jobs.values.firstOrNull { it.id == id } }
                    current.copy(jobs = jobs.values.sortedByDescending { it.createdAt }, exportStep = if (active != null && current.exportStep == ExportStep.PROGRESS) ExportStep.PROGRESS else current.exportStep)
                }
            }
        }
        // 缓存全书任务：进度区跟着任务状态走；任务结束后顺手量一次磁盘占用。
        viewModelScope.launch {
            app.onlineBookCacheManager.jobs.collect { jobs ->
                mutable.update { it.copy(cacheJobs = jobs.values.sortedBy { job -> job.title }) }
                if (jobs.values.any { !it.status.active }) refreshOnlineCacheSize()
            }
        }
        refreshOnlineCacheSize()
        // 更新检查相关设置（自动检查、仅 WiFi 缓存）
        viewModelScope.launch {
            settingsRepository.autoCheckUpdates.collect { value -> mutable.update { it.copy(autoCheckUpdates = value) } }
        }
        viewModelScope.launch {
            settingsRepository.cacheOnWifiOnly.collect { value -> mutable.update { it.copy(cacheWifiOnly = value) } }
        }
        // 必须独立 collect：Flow.collect 不会正常返回，写在同一 launch 里的第二个
        // collect 永远执行不到，离线模式开关就会与持久化设置脱节。
        viewModelScope.launch {
            settingsRepository.offlineMode.collect { value -> mutable.update { it.copy(offlineMode = value) } }
        }
        // 冷启动自动检查更新：12 小时节流，仅在设置开启且书架有远程书时执行。
        viewModelScope.launch { maybeAutoCheckUpdates() }
        mutable.update { it.copy(loggedIn = app.sessionStore.hasSession()) }
        // 冷启动已有会话时拉取官方标签（tags.php，登录墙内）；失败静默，本地索引标签兜底
        if (app.sessionStore.hasSession()) loadOfficialTags()
        viewModelScope.launch {
            settingsRepository.searchHistory.collect { history -> mutable.update { it.copy(searchHistory = history) } }
        }
        viewModelScope.launch {
            bookshelfRepository.entries.collect { entries ->
                // 仓库只给存储顺序，排序在这里按当前设置应用（两个 collector 都写 bookshelf，
                // 因此各自都要维持「已排序」不变量，否则后到的原始列表会覆盖排序结果）
                mutable.update { state ->
                    state.copy(
                        bookshelf = sortBookshelf(entries, state.bookshelfSort),
                        bookshelfLoaded = true,
                    )
                }
            }
        }
        viewModelScope.launch {
            readingStatsRepository.stats.collect { stats -> mutable.update { it.copy(readingStats = stats) } }
        }
        viewModelScope.launch {
            settingsRepository.readerSettings.collect { settings ->
                mutable.update { it.copy(readerSettings = settings) }
            }
        }
        // 书架排序是持久化设置：与书目数据分开收集，两者任一变化都重排（见下方 bookshelf 收集）
        viewModelScope.launch {
            settingsRepository.bookshelfSort.collect { sort ->
                mutable.update { state -> state.copy(bookshelfSort = sort, bookshelf = sortBookshelf(state.bookshelf, sort)) }
            }
        }
        viewModelScope.launch {
            settingsRepository.readingGoalMinutes.collect { minutes ->
                mutable.update { it.copy(readingGoalMinutes = minutes) }
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
                        // 书目缓存消息只给设置页用；混进 exploreMessage 会让探索页底部
                        // 莫名出现「书目更新失败」之类的提示。
                        catalogMessage = catalog.message,
                    )
                }
            }
        }
    }

    fun setTab(tab: StudioTab) = mutable.update { it.copy(tab = tab, message = null) }

    fun openSettingsSection(section: SettingsSection) = mutable.update { it.copy(settingsSection = section) }
    fun backSettings() = mutable.update { it.copy(settingsSection = SettingsSection.OVERVIEW) }

    /**
     * 统一的向上导航：顶栏返回按钮与系统返回键都走这里（0.19.0-alpha05）。
     *
     * 目标由纯函数 [upTarget] 决定，这里只负责执行；[UpTarget.NONE] 什么也不做
     * （调用方据此不拦截返回键，交回系统退出 App）。
     *
     * 与直接调 `closeXxx()` 的区别：导出向导的返回是**逐级后退**——打包页退回选章页，
     * 已选章节不丢；其余步骤才关闭向导。
     */
    fun navigateUp(target: UpTarget) {
        when (target) {
            UpTarget.NONE -> Unit
            UpTarget.CLOSE_AUTHOR -> closeAuthorWorks()
            UpTarget.CLOSE_DETAIL -> closeExploreDetail()
            UpTarget.CLOSE_HISTORY -> setShowJobHistory(false)
            UpTarget.CLOSE_EXPANDED -> closeExploreExpanded()
            UpTarget.CLOSE_SEARCH -> closeSearchPage()
            UpTarget.CLOSE_BOOKMARKS -> closeBookmarkCenter()
            UpTarget.CLOSE_EXPORT -> if (state.value.exportStep == ExportStep.PACKAGING) {
                backToChapters()
            } else {
                closeExport()
            }
            UpTarget.CLOSE_SETTINGS -> backSettings()
        }
    }

    /**
     * 探索榜单两类入口：
     * - **登录墙内**（`requiresAuth = true`）：`toplist.php` 的四个排序榜，
     *   走用户**自己的**会话 Cookie（AGENTS §4.5.3：本人登录后按账号权限访问不算规避）；
     * - **匿名可访问**：年度精选榜与月度新书榜（`/zt/` 公开页）。
     *
     * 未登录点登录墙榜单只会得到登录提示，不做任何规避；`articlelist.php` 仍不使用。
     */
    val explorePages: List<ExplorePage> = ExploreFeed.pages()

    /** 进入探索页时调用一次；已加载过则不重复打源站。 */
    fun ensureExploreLoaded() {
        val current = state.value
        if (current.exploreLoadedOnce) return
        // 首屏只在“还没有当前榜单”时选默认榜；若当前榜单仍在上一个登录态的墙上，
        // 必须按新的登录态重选，否则匿名用户会落到「今日更新」并只看到一条登录提示。
        val loggedIn = app.sessionStore.hasSession()
        val page = current.activeExplorePage
            ?.takeIf { ExploreFeed.canLoad(it, loggedIn) }
            ?: defaultExplorePage()
        loadExplore(page)
    }

    /**
     * 未登录时的首屏榜单：公开榜（年度精选）不需要会话；登录后再从「今日更新」开始。
     * 不能把首个登录墙榜单当默认值，否则冷启动首屏只会出现一条登录提示。
     */
    private fun defaultExplorePage(): ExplorePage = defaultExplorePage(explorePages, app.sessionStore.hasSession())

    /**
     * 0.18.0：Cloudflare 验证完成后的重试。
     *
     * 模型（实测）：CF 对非浏览器客户端启用**需要执行 JS 的挑战**——WebView 能跑JS 所以
     * 能过（登录、验证窗都正常），OkHttp 跑不了就直接 403。浏览器通过后拿到的 `cf_clearance`
     * 与 UA/IP 绑定，因此复用同一份 UA 的 OkHttp 带上 clearance 后即可放行。
     *
     * 验证窗口**不自动弹**（403 是常态策略、不是需要点击的挑战，自动打断没有意义），
     * 只在设置 → 网络手动打开；通过后调用本方法重试失败的请求。
     */
    fun onChallengeVerified() {
        val current = state.value
        mutable.update { it.copy(clearancePresent = app.sessionStore.hasClearance(), message = "验证完成，正在重试…") }
        current.activeExplorePage?.let { page -> loadExplore(page, refreshing = true) }
        current.exploreDetailId?.let { id -> loadExploreDetail(id, current.exploreDetailSeed) }
    }

    /** 刷新验证状态读数（是否存在 `cf_clearance`）。 */
    fun refreshClearanceState() = mutable.update { it.copy(clearancePresent = app.sessionStore.hasClearance()) }

    /**
     * 弃用当前验证凭证并重试公开页。
     *
     * 与「打开验证页」的区别：验证页只在 Cloudflare 真的下发新 token 时有用；
     * 若旧 token 已失效（换网络/UA 后常见），带着它反而被硬拦，清掉后请求立刻恢复正常。
     */
    fun clearClearance() {
        app.sessionStore.removeClearance()
        mutable.update {
            it.copy(
                clearancePresent = false,
                message = "已清除验证凭证，正在重试公开页…",
            )
        }
        state.value.activeExplorePage?.let { page -> loadExplore(page) }
    }

    /** 403 / 挑战页时的统一提示：说明这是「浏览器 JS 挑战」而非可点击的验证。 */
    fun challengeHint(): String =
        if (state.value.clearancePresent) "当前网络下 Cloudflare 仍在拦截本客户端。" else "Cloudflare 要求浏览器执行验证；验证一次后即可继续。"

    /** 判断错误是否为 Cloudflare 拦截页（解析层已把挑战页转成该错误码）。 */
    private fun isChallenge(error: Throwable): Boolean =
        (error as? Wenku8Exception)?.code == "UPSTREAM_CHALLENGE"

    /**
     * 0.18.0：**深度诊断**——对每个匿名公开端点，分别用「会话客户端」与「公开客户端
     * （只带 cf_clearance）」各请求一次，输出矩阵。
     *
     * 用途：定位 403 是端点级、客户端级还是链路级差异（用户实测「测试端点通、探索/在线阅读不通」
     * 正是靠这个区分）。请求严格走全局限流（1 秒/请求），因此约 10 次需要十几秒。
     */
    fun diagnosePublicEndpoints() {
        viewModelScope.launch {
            val endpoints = listOf(
                "书籍详情" to Wenku8Urls.articleInfo("2365"),
                "章节目录" to Wenku8Urls.index("2365"),
                "年度榜单" to Wenku8Urls.sugoi(java.time.Year.now().value),
                "月度新书" to Wenku8Urls.booklist(java.time.Year.now().toString().replace("-", "")),
                "同作者作品" to Wenku8Urls.authorArticle("伏濑"),
            )
            mutable.update { it.copy(endpointProbes = emptyList(), message = "正在诊断公开端点…") }
            val publicClient = Wenku8HttpClient(File(app.cacheDir, "wenku8-diagnose"), app.sessionStore.clearanceCookieJar())
            val sessionClient = manager.httpClient()
            val results = buildList {
                endpoints.forEach { (label, url) ->
                    add(probe(label, "公开客户端", url, publicClient))
                    add(probe(label, "会话客户端", url, sessionClient))
                }
            }
            val blocked = results.count { it.status.contains("403") }
            mutable.update {
                it.copy(
                    endpointProbes = results,
                    message = if (blocked == 0) "全部公开端点可用。" else "$blocked / ${results.size} 次探测被拦截（403）。",
                )
            }
        }
    }

    /** 单次探测：只记录状态摘要，不抛异常。 */
    private suspend fun probe(label: String, clientKind: String, url: String, client: Wenku8HttpClient): EndpointProbe {
        val started = System.currentTimeMillis()
        val status = catching {
            val resource = client.fetchText(url, "diagnose", Wenku8Urls.BASE)
            "OK 200·${(System.currentTimeMillis() - started) / 1000}s·${resource.html.length / 1024}KB"
        }.getOrElse { error ->
            val message = error.message ?: "失败"
            when {
                (error as? Wenku8Exception)?.code == "UPSTREAM_CHALLENGE" ->
                    message.substringAfter("（", "").substringBefore("）")
                        .takeIf { it.isNotBlank() }
                        ?.let { "CF 拦截·$it" }
                        ?: "Cloudflare 拦截（挑战页）"
                message.contains("403") -> "HTTP 403"
                message.contains("429") -> "HTTP 429（被限速）"
                else -> message.take(40)
            }
        }
        return EndpointProbe(label = label, clientKind = clientKind, url = url, status = status)
    }

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
        val loggedIn = app.sessionStore.hasSession()
        val page = state.value.activeExplorePage
            ?.takeIf { ExploreFeed.canLoad(it, loggedIn) }
            ?: defaultExplorePage()
        loadExplore(page, refreshing = true)
    }

    fun loadExplore(page: ExplorePage = defaultExplorePage(), refreshing: Boolean = false) {
        if (!ExploreFeed.canLoad(page, app.sessionStore.hasSession())) {
            mutable.update { ExploreState.reject(it, page, "登录后可查看该榜单。") }
            return
        }
        // 序号由 ExploreState.begin 统一分配（只依赖 state，不依赖协程外计数器）。
        // 这样登录/登出先清状态、随后立即重载时，新请求不会被自己的序号判成旧响应。
        var requestId = 0L
        mutable.update {
            ExploreState.begin(it, page, refreshing).also { next -> requestId = next.exploreRequestId }
        }
        viewModelScope.launch {
            var cards: List<SearchBook> = emptyList()
            val ids = catching {
                if (page.requiresAuth) {
                    // 登录墙内榜单：走共享限流客户端 + 用户会话 Cookie，
                    // 登录页识别与会话清理在数据源里完成（不尝试绕过）
                    cards = exploreRepository.load(page)
                    cards.map { it.id }
                } else {
                    // 公开页客户端由 Application 注入：不带会话 Cookie，只带 cf_clearance
                    exploreRepository.loadPublic(page)
                }
            }.getOrElse { error ->
                // 用户已切榜/登录态已变化时，这个请求的失败也不能再写页面。
                // 例如登出后旧“今日更新”请求返回 AUTH_REQUIRED：若继续走下面的
                // refreshSession()，会把刚重载好的公开榜再次清空。
                if (!ExploreState.isCurrent(requestId, state.value.exploreRequestId)) return@launch
                mutable.update {
                    ExploreState.failed(
                        it,
                        if (isChallenge(error)) "被 Cloudflare 拦截：${challengeHint()}" else (error.message ?: "加载失败。"),
                        requestId,
                    )
                }
                // 只有**登录墙内**的当前请求失败才刷新登录态。旧请求（用户已切榜）迟到时
                // 不应改写页面；公开榜的 AUTH_REQUIRED 来自匿名页被重定向到登录页，
                // 不代表用户会话失效，若也 refreshSession() 会立刻重载公开榜并可能无限循环。
                if (page.requiresAuth &&
                    (error as? Wenku8Exception)?.code == "AUTH_REQUIRED"
                ) {
                    refreshSession()
                }
                return@launch
            }
            if (ids.isEmpty()) {
                mutable.update { ExploreState.failed(it, "该榜单暂时没有内容。", requestId) }
                return@launch
            }

            // 登录墙榜单自带完整卡片（标题/作者/封面），本地索引条目字段更全时优先本地，
            // 其余直接用卡片，不必等逐本补全就能显示。公开榜先叠上跨轮缓存，
            // 这样上一轮补过的书不会在刷新后重新变成缺失项。
            val fetched = exploreCardCache.snapshot().toMutableMap()
            cards.forEach { fetched[it.id] = it }
            val collected = ExploreFeed.mergeCards(ids, { id -> catalogRepository.get(id)?.toSearchBook() }, fetched)
            mutable.update { ExploreState.rows(it, page, collected, requestId, busy = true) }

            // 只有公开榜需要逐本补全；登录墙榜单首屏已带完整卡片。
            val missing = ExploreFeed.missingIds(
                page = page,
                ids = ids,
                catalog = { id -> catalogRepository.get(id)?.toSearchBook() },
                remote = fetched,
                limit = EXPLORE_AUTO_FETCH_LIMIT,
            )
            if (missing.isEmpty()) {
                mutable.update { ExploreState.rows(it, page, collected, requestId) }
                mutable.update { ExploreState.completed(it, requestId) }
                return@launch
            }
            var failures = 0
            var consecutiveFailures = 0
            var processed = 0
            for ((index, id) in missing.withIndex()) {
                // 切榜后立即停止抓取剩余条目，别让旧榜单继续占用全局限流。
                if (!ExploreState.isCurrent(requestId, state.value.exploreRequestId)) return@launch
                // 公开榜必须继续走无会话客户端：这里若回落到 app.bookSource，
                // 已登录用户的 jieqiUserInfo / PHPSESSID 会被补全请求带出去。
                val info = catching { exploreRepository.loadPublicBook(id) }
                info.getOrNull()?.let { book ->
                    fetched[book.id] = book
                    // 立即写回跨轮缓存；不能等整轮结束，否则用户中途切榜会把已花掉的请求丢掉。
                    exploreCardCache.put(book)
                } ?: run {
                    failures += 1
                }
                processed = index + 1
                // 同一本连续失败达到阈值就不再尝试后续条目：公开链路被 Cloudflare 拦时，
                // 继续逐本请求只会把整轮都拖进 1 秒/次的限流，页面却始终没有新卡片。
                // 这里不改写失败文案，最终统一由 completionWarning 给出提示。
                consecutiveFailures = if (info.isSuccess) 0 else consecutiveFailures + 1
                if (shouldStopPublicFetch(consecutiveFailures)) {
                    break
                }
                val merged = ExploreFeed.mergeCards(ids, { key -> catalogRepository.get(key)?.toSearchBook() }, fetched)
                mutable.update { ExploreState.rows(it, page, merged, requestId, busy = true) }
                mutable.update { ExploreState.progress(it, processed, missing.size, requestId, failures) }
            }
            if (!ExploreState.isCurrent(requestId, state.value.exploreRequestId)) return@launch
            val merged = ExploreFeed.mergeCards(ids, { key -> catalogRepository.get(key)?.toSearchBook() }, fetched)
            if (merged.isEmpty()) {
                mutable.update {
                    ExploreState.failed(
                        it,
                        ExploreState.emptyPublicResult(
                            total = ids.distinct().size,
                            attempted = processed,
                            failures = failures,
                        ),
                        requestId,
                    )
                }
            } else {
                mutable.update { ExploreState.rows(it, page, merged, requestId) }
                val completionNotice = ExploreState.completionWarning(
                    total = ids.distinct().size,
                    shown = merged.size,
                    attempted = processed,
                    failures = failures,
                )
                mutable.update { ExploreState.completed(it, requestId, completionNotice) }
            }
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
        tagRequestId += 1
        val requestId = tagRequestId
        val loggedIn = app.sessionStore.hasSession()
        mutable.update {
            ExploreState.selectTag(
                state = it,
                tag = tag,
                local = local,
                loggedIn = loggedIn,
                awaitingServer = loggedIn,
            )
        }
        if (!loggedIn) return
        viewModelScope.launch {
            catching { exploreRepository.tagBooks(tag) }
                .onSuccess { server ->
                    if (requestId == tagRequestId) mutable.update { ExploreState.mergeTag(it, tag, server, local) }
                }
                .onFailure { error ->
                    if (requestId == tagRequestId) {
                        mutable.update {
                            ExploreState.tagFailed(it, tag, error.message ?: "标签浏览失败，已显示本地结果。")
                        }
                    }
                }
        }
    }

    /** 退出标签浏览。 */
    fun clearTag() {
        tagRequestId += 1
        mutable.update { ExploreState.clearTag(it) }
    }

    /** 官方标签在前（站点规范词表），本地索引里多出的标签跟在后面。 */
    private fun mergedTags(local: List<String>): List<String> = (officialTags + local).distinct()

    private fun loadOfficialTags() {
        val requestId = ++officialTagsRequestId
        viewModelScope.launch {
            catching { exploreRepository.officialTags() }.onSuccess { tags ->
                if (tags.isEmpty() || requestId != officialTagsRequestId || !app.sessionStore.hasSession()) return@onSuccess
                officialTags = tags
                mutable.update { it.copy(exploreTags = mergedTags(catalogRepository.tagList())) }
            }
        }
    }

    fun updateCatalog(budget: Int = 200) = catalogRepository.update(budget)
    fun clearCatalog() = catalogRepository.clear()

    /** 读取封面/插图磁盘缓存占用；进入「书目缓存」页时刷新。 */
    fun refreshCoverCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = runCatching { app.coverRepository.diskUsageBytes() }.getOrDefault(0L)
            mutable.update { it.copy(coverCacheBytes = bytes) }
        }
    }

    fun clearCoverCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val cleared = runCatching { app.coverRepository.clear() }
            // 清完重新量一次：在途下载可能已经又写回了文件，直接写 0 会与磁盘不符。
            val bytes = runCatching { app.coverRepository.diskUsageBytes() }.getOrDefault(0L)
            mutable.update {
                it.copy(
                    coverCacheBytes = bytes,
                    message = if (cleared.isSuccess) "封面缓存已清理" else "清理失败，请稍后重试",
                )
            }
        }
    }

    /** 书架菜单「缓存全书（离线）」：逐章下载，已缓存的章节自动跳过。 */
    fun startOnlineCache(entry: BookshelfEntry) {
        if (mutable.value.offlineMode) {
            viewModelScope.launch { mutable.update { it.copy(message = "离线模式下不会下载新章节，请先关闭离线模式。") } }
            return
        }
        app.onlineBookCacheManager.start(entry.bookId, entry.title, entry.author)
    }

    fun cancelOnlineCache(bookId: String) = app.onlineBookCacheManager.cancel(bookId)

    fun dismissOnlineCache(bookId: String) = app.onlineBookCacheManager.dismiss(bookId)

    /** 删除单本书的离线缓存（正文 + 目录快照）。 */
    fun clearOnlineBookCache(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val cleared = catching { app.onlineBookCacheManager.deleteCache(bookId) }
            val bytes = runCatching { app.onlineBookCacheManager.diskUsageBytes() }.getOrDefault(0L)
            val counts = runCatching { app.onlineBookCacheManager.cachedCounts() }.getOrDefault(emptyMap())
            mutable.update {
                it.copy(
                    onlineCacheBytes = bytes,
                    cacheCounts = counts,
                    message = if (cleared.isSuccess) "离线缓存已删除" else "删除失败，请稍后重试",
                )
            }
        }
    }

    fun hasOnlineCache(bookId: String): Boolean = app.onlineBookCacheManager.hasAnyCache(bookId)

    fun refreshOnlineCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = runCatching { app.onlineBookCacheManager.diskUsageBytes() }.getOrDefault(0L)
            val counts = runCatching { app.onlineBookCacheManager.cachedCounts() }.getOrDefault(emptyMap())
            mutable.update { it.copy(onlineCacheBytes = bytes, cacheCounts = counts) }
        }
    }

    // ---- 书架更新检查 ----

    /** 手动检查全部远程书（书架页 / 设置页入口）。 */
    fun checkAllUpdates() {
        viewModelScope.launch {
            if (mutable.value.offlineMode) {
                mutable.update { it.copy(message = "离线模式下不会联网检查更新，请先关闭离线模式。") }
                return@launch
            }
            val entries = bookshelfRepository.entries.first()
            settingsRepository.setLastUpdateCheckAt(System.currentTimeMillis())
            runUpdateCheck(entries, manual = true)
        }
    }

    /** 单本书「检查更新」（书架菜单）。 */
    fun checkUpdate(entry: BookshelfEntry) {
        viewModelScope.launch {
            if (mutable.value.checkingUpdates) return@launch
            if (mutable.value.offlineMode) {
                mutable.update { it.copy(message = "离线模式下不会联网检查更新，请先关闭离线模式。") }
                return@launch
            }
            mutable.update { it.copy(checkingUpdates = true) }
            val outcome = catching { app.bookshelfUpdateChecker.check(entry) }.getOrNull()
            val text = when (outcome) {
                is UpdateCheckOutcome.Checked ->
                    if (outcome.newChapters > 0) {
                        "《${entry.title}》有 ${outcome.newChapters} 章新内容"
                    } else {
                        "《${entry.title}》暂无更新"
                    }
                is UpdateCheckOutcome.Skipped -> "《${entry.title}》检查失败：${outcome.reason}"
                null -> "检查失败，请稍后重试"
            }
            mutable.update { it.copy(checkingUpdates = false, message = text) }
        }
    }

    private suspend fun maybeAutoCheckUpdates() {
        if (!settingsRepository.autoCheckUpdates.first()) return
        // 离线模式下不发任何请求：自动检查属于后台行为，不应破坏用户的零流量选择。
        if (settingsRepository.offlineMode.first()) return
        val entries = bookshelfRepository.entries.first()
        if (entries.none { it.isRemoteSource }) return
        val last = settingsRepository.lastUpdateCheckAt.first()
        val now = System.currentTimeMillis()
        if (last > 0L && now - last < BookshelfUpdateChecker.AUTO_CHECK_INTERVAL_MS) return
        settingsRepository.setLastUpdateCheckAt(now)
        runUpdateCheck(entries, manual = false, now = now)
    }

    private suspend fun runUpdateCheck(
        entries: List<BookshelfEntry>,
        manual: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        if (mutable.value.checkingUpdates) return
        mutable.update { it.copy(checkingUpdates = true) }
        val outcomes = catching { app.bookshelfUpdateChecker.checkAll(entries, now) }.getOrDefault(emptyList())
        val updated = outcomes.filterIsInstance<UpdateCheckOutcome.Checked>().count { it.newChapters > 0 }
        mutable.update {
            it.copy(
                checkingUpdates = false,
                message = if (!manual) {
                    it.message
                } else when {
                    outcomes.isEmpty() -> "没有可检查的远程书籍"
                    updated > 0 -> "检查完成：$updated 本有新章节"
                    else -> "检查完成：暂无更新"
                },
            )
        }
    }

    /**
     * 检查应用自身更新（GitHub Release）。
     *
     * 与书架章节更新完全分开：不经过 [Wenku8HttpClient]，也不受书架设置影响。
     */
    fun checkAppUpdate() {
        val current = state.value
        if (current.appUpdateChecking || current.appUpdateDownloading) return
        if (current.offlineMode) {
            mutable.update { it.copy(appUpdateMessage = "离线模式下不会检查应用更新，请先关闭离线模式。") }
            return
        }
        mutable.update {
            it.copy(
                appUpdateChecking = true,
                appUpdateProgress = 0,
                appUpdateAvailable = null,
                appUpdateFile = null,
                appUpdateMessage = null,
            )
        }
        viewModelScope.launch {
            catching { app.appUpdateRepository.check(BuildConfig.VERSION_NAME) }
                .onSuccess { release ->
                    mutable.update {
                        it.copy(
                            appUpdateChecking = false,
                            appUpdateAvailable = release,
                            appUpdateMessage = if (release == null) "已是最新版本。" else null,
                        )
                    }
                }
                .onFailure { error ->
                    mutable.update {
                        it.copy(
                            appUpdateChecking = false,
                            appUpdateMessage = error.message ?: "检查应用更新失败。",
                        )
                    }
                }
        }
    }

    /** 下载当前发现的新版本；进度只写 [StudioUiState.appUpdateProgress]。 */
    fun downloadAppUpdate() {
        val release = state.value.appUpdateAvailable ?: return
        if (state.value.appUpdateDownloading) return
        mutable.update {
            it.copy(
                appUpdateDownloading = true,
                appUpdateProgress = 0,
                appUpdateFile = null,
                appUpdateMessage = null,
            )
        }
        viewModelScope.launch {
            catching {
                app.appUpdateRepository.download(release) { percent ->
                    mutable.update { it.copy(appUpdateProgress = percent) }
                }
            }.onSuccess { file ->
                mutable.update {
                    it.copy(
                        appUpdateDownloading = false,
                        appUpdateProgress = 100,
                        appUpdateFile = file.absolutePath,
                        appUpdateMessage = "下载完成，可安装更新。",
                    )
                }
            }.onFailure { error ->
                mutable.update {
                    it.copy(
                        appUpdateDownloading = false,
                        appUpdateProgress = 0,
                        appUpdateMessage = error.message ?: "下载应用更新失败。",
                    )
                }
            }
        }
    }

    /** 把已下载的 APK 交给系统安装器；需要「安装未知应用」权限时跳系统设置。 */
    fun installAppUpdate(context: Context) {
        val path = state.value.appUpdateFile ?: return
        val file = File(path)
        if (!file.isFile) {
            mutable.update { it.copy(appUpdateFile = null, appUpdateMessage = "安装包已失效，请重新下载。") }
            return
        }
        val result = AppUpdateInstaller.install(context, file)
        mutable.update {
            it.copy(
                appUpdateMessage = when (result) {
                    AppUpdateInstaller.Result.STARTED -> "已打开系统安装器。"
                    AppUpdateInstaller.Result.NEEDS_PERMISSION -> "请允许本应用安装未知应用，然后返回再次点击安装。"
                    AppUpdateInstaller.Result.FAILED -> "无法启动安装器，请检查系统权限。"
                },
            )
        }
    }

    fun clearOnlineCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val cleared = catching { app.onlineBookCacheManager.clearAll() }
            // 清完重新量一次，与封面缓存同样的理由。
            val bytes = runCatching { app.onlineBookCacheManager.diskUsageBytes() }.getOrDefault(0L)
            mutable.update {
                it.copy(
                    onlineCacheBytes = bytes,
                    cacheCounts = emptyMap(),
                    message = if (cleared.isSuccess) "在线阅读缓存已清理" else "清理失败，请稍后重试",
                )
            }
        }
    }

    fun setAutoCheckUpdates(value: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoCheckUpdates(value) }
    }

    fun setCacheWifiOnly(value: Boolean) {
        viewModelScope.launch { settingsRepository.setCacheOnWifiOnly(value) }
    }

    fun setOfflineMode(value: Boolean) {
        viewModelScope.launch { settingsRepository.setOfflineMode(value) }
    }

    /**
     * 打开「同作者作品」页并抓取该书的公开作品列表。
     *
     * 只由用户在详情页/书架菜单显式点击触发，不在页面打开时自动调用。
     * 先按书名占位、再逐本补全封面与字数，因此大字作者也不会长时间空白。
     */
    fun expandAuthor(bookId: String) {
        val id = bookId.filter(Char::isDigit)
        if (id.isBlank()) return
        // 手头已有的作者名先填进顶栏，避免第一请求回来前标题是空的
        val knownAuthor = state.value.exploreDetail?.book?.takeIf { it.id == id }?.author
            ?: state.value.bookshelf.firstOrNull { it.bookId == id }?.author
            ?: catalogRepository.get(id)?.author
            ?: ""
        val requestId = ++authorWorksRequestId
        viewModelScope.launch {
            mutable.update {
                it.copy(
                    authorWorksOpen = true,
                    authorWorksAnchorId = id,
                    authorWorksName = knownAuthor.trim(),
                    authorWorksBooks = emptyList(),
                    authorWorksLoading = true,
                    authorWorksMessage = null,
                )
            }
            val loaded = LinkedHashMap<String, SearchBook>()
            catching {
                catalogRepository.expandAuthor(
                    bookId = id,
                    onAuthor = { author ->
                        if (requestId == authorWorksRequestId) {
                            mutable.update { it.copy(authorWorksName = author) }
                        }
                    },
                    onLinks = { links ->
                        if (requestId == authorWorksRequestId) {
                            // 先用列表页的书名占位；已补全的条目继续用完整数据
                            links.forEach { link ->
                                if (loaded[link.id] == null) {
                                    loaded[link.id] = SearchBook(
                                        id = link.id,
                                        title = link.title.ifBlank { "未命名作品" },
                                        author = state.value.authorWorksName,
                                        sourceUrl = Wenku8Urls.book(link.id),
                                    )
                                }
                            }
                            mutable.update { it.copy(authorWorksBooks = loaded.values.toList()) }
                        }
                    },
                    onEntry = { entry ->
                        if (requestId == authorWorksRequestId) {
                            loaded[entry.id] = entry.toSearchBook()
                            mutable.update { it.copy(authorWorksBooks = loaded.values.toList()) }
                        }
                    },
                )
            }.onSuccess {
                if (requestId == authorWorksRequestId) {
                    mutable.update {
                        it.copy(
                            authorWorksLoading = false,
                            authorWorksMessage = null,
                        )
                    }
                }
            }.onFailure { error ->
                if (requestId == authorWorksRequestId) {
                    mutable.update {
                        it.copy(
                            authorWorksLoading = false,
                            authorWorksMessage = if (isChallenge(error)) {
                                "被 Cloudflare 拦截：${challengeHint()}"
                            } else {
                                error.message ?: "抓取同作者作品失败。"
                            },
                        )
                    }
                }
            }
        }
    }

    /** 关闭同作者作品页（返回详情或书架）。 */
    fun closeAuthorWorks() {
        authorWorksRequestId++
        mutable.update { it.copy(authorWorksOpen = false) }
    }

    /** 重试/刷新同作者作品页。 */
    fun refreshAuthorWorks() {
        val anchor = state.value.authorWorksAnchorId
        if (anchor.isNotBlank()) expandAuthor(anchor)
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

        // ---- 跨书书签中心（0.19.0）----

    /**
     * 按书分组的书签；每次书签增删都会随 DataStore 流重算。
     *
     * 分组是纯函数（[com.example.hyperreader.reader.groupBookmarks]），
     * 这样排序与展示规则可以脱离 DataStore 单测。
     */
    val bookmarkGroups: kotlinx.coroutines.flow.Flow<List<com.example.hyperreader.reader.BookmarkGroup>> =
        bookmarkCenterRepository.bookmarks.map { com.example.hyperreader.reader.groupBookmarks(it) }

    fun openBookmarkCenter() = mutable.update { it.copy(bookmarkCenterOpen = true) }

    fun closeBookmarkCenter() = mutable.update { it.copy(bookmarkCenterOpen = false) }

    fun removeBookmark(id: Long) { viewModelScope.launch { bookmarkCenterRepository.remove(id) } }

    fun setBookmarkNote(id: Long, note: String) {
        viewModelScope.launch { bookmarkCenterRepository.updateNote(id, note) }
    }

    fun clearBookBookmarks(hostBookId: String) {
        viewModelScope.launch { bookmarkCenterRepository.removeAllForBook(hostBookId) }
    }

    fun removeFromShelf(id: String) { viewModelScope.launch { bookshelfRepository.remove(id) } }

    /** 批量置顶 / 取消置顶；单本失败只计数，不中断其余条目。 */
    fun setPinnedBatch(entries: List<BookshelfEntry>, pinned: Boolean) {
        if (entries.isEmpty()) return
        viewModelScope.launch {
            var failed = 0
            entries.forEach { entry ->
                catching { bookshelfRepository.setPinned(entry.id, pinned) }.onFailure { failed += 1 }
            }
            mutable.update {
                it.copy(
                    message = buildString {
                        append(if (pinned) "已置顶 " else "已取消置顶 ")
                        append(entries.size - failed)
                        append(" 本")
                        if (failed > 0) append("，").append(failed).append(" 本失败")
                        append("。")
                    },
                )
            }
        }
    }

    /** 批量移出书架；单本失败只计数。 */
    fun removeBatch(entries: List<BookshelfEntry>) {
        if (entries.isEmpty()) return
        viewModelScope.launch {
            var failed = 0
            entries.forEach { entry ->
                catching { bookshelfRepository.remove(entry.id) }.onFailure { failed += 1 }
            }
            mutable.update {
                it.copy(
                    message = buildString {
                        append("已从书架移除 ")
                        append(entries.size - failed)
                        append(" 本")
                        if (failed > 0) append("，").append(failed).append(" 本失败")
                        append("。")
                    },
                )
            }
        }
    }

    fun setPinned(id: String, pinned: Boolean) { viewModelScope.launch { bookshelfRepository.setPinned(id, pinned) } }

    /** 覆盖一本书的标签列表（标签编辑页的开关与删除都走这里）。 */
    fun setShelfTags(id: String, tags: List<String>) {
        viewModelScope.launch { bookshelfRepository.setTags(id, tags) }
    }

    /** 给一本书加标签；输入规范化在仓库层统一处理。 */
    fun addShelfTag(id: String, tag: String) {
        viewModelScope.launch { bookshelfRepository.addTag(id, tag) }
    }

    /** 从一本书移除标签。 */
    fun removeShelfTag(id: String, tag: String) {
        viewModelScope.launch { bookshelfRepository.removeTag(id, tag) }
    }

    /** 批量给选中的书加同一个标签；单本失败只计数。 */
    fun addShelfTagBatch(entries: List<BookshelfEntry>, tag: String) {
        val normalized = normalizeShelfTag(tag)
        if (normalized == null || entries.isEmpty()) return
        viewModelScope.launch {
            var failed = 0
            entries.forEach { entry ->
                catching { bookshelfRepository.addTag(entry.id, normalized) }.onFailure { failed += 1 }
            }
            mutable.update {
                it.copy(
                    message = buildString {
                        append("已给 ")
                        append(entries.size - failed)
                        append(" 本书加上标签 #")
                        append(normalized)
                        if (failed > 0) append("，").append(failed).append(" 本失败")
                        append("。")
                    },
                )
            }
        }
    }

    fun markShelfRead(id: String) { viewModelScope.launch { bookshelfRepository.recordRead(id) } }

    /** 切换书架排序；只改展示顺序，下次启动仍生效（DataStore）。 */
    fun setBookshelfSort(sort: com.example.hyperreader.settings.BookshelfSort) {
        viewModelScope.launch { settingsRepository.setBookshelfSort(sort) }
    }
    /**
     * 从书架卡片发起导出向导：先解析目录，成功后直接进入选章节。
     * 失败时停在 [ExportStep.RESOLVING] 并给出可重试的错误文案。
     */
    fun startExportFromShelf(entry: BookshelfEntry) {
        mutable.update { ExportWizard.begin(it, entry.sourceUrl) }
        resolveForExport()
    }

    /**
     * 书架批量导出：逐本解析目录后加入现有导出队列。
     *
     * 只处理远程 Wenku8 书；每本都用当前全局导出引擎、全章节与封面。解析失败只计数，
     * 不中断其余书籍——批量任务的成功率比单本失败原因更重要，失败数在结束时汇总提示。
     */
    fun startBatchExport(entries: List<BookshelfEntry>) {
        if (state.value.batchExportBusy) return
        val remote = entries
            .filter { it.isRemoteSource && it.sourceUrl.isNotBlank() }
            .distinctBy { it.id }
        if (remote.isEmpty()) {
            mutable.update { it.copy(message = "所选书籍里没有可导出的 Wenku8 书。") }
            return
        }
        viewModelScope.launch {
            mutable.update {
                it.copy(
                    batchExportBusy = true,
                    batchExportDone = 0,
                    batchExportTotal = remote.size,
                    message = null,
                )
            }
            var queued = 0
            var failed = 0
            for (entry in remote) {
                try {
                    val (book, index) = manager.parseSource(entry.sourceUrl)
                    if (index.chapters.isEmpty()) error("目录为空")
                    manager.create(book, index.chapters, includeCover = true)
                    queued += 1
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    failed += 1
                }
                mutable.update { it.copy(batchExportDone = it.batchExportDone + 1) }
            }
            mutable.update {
                it.copy(
                    batchExportBusy = false,
                    message = buildString {
                        append("已加入 ")
                        append(queued)
                        append(" 个导出任务")
                        if (failed > 0) append("，").append(failed).append(" 本解析失败")
                        append("。可在书架顶部查看进度。")
                    },
                )
            }
        }
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

    /** 设置每日阅读目标（分钟）；0 = 关闭。 */
    fun setReadingGoalMinutes(minutes: Int) {
        viewModelScope.launch { settingsRepository.setReadingGoalMinutes(minutes) }
    }

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
            catching { manager.httpClient().fetchTextInteractive(url, "relay-test", com.example.hyperreader.core.Wenku8Urls.BASE) }
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

    /**
     * 依次测速两个直连镜像，切到响应更快的那个。
     *
     * 只探测匿名 `articleinfo.php`，每个镜像一次，仍走全局限流；测速失败不会改变当前入口。
     * 中继开启时也照常测直连（测速结果只写 `direct_entry`，不影响中继状态）。
     */
    fun autoSelectDirectEntry() {
        if (state.value.endpointProbing) return
        viewModelScope.launch {
            mutable.update { it.copy(endpointProbing = true, message = "正在测速直连入口…") }
            val results = mutableListOf<Pair<com.example.hyperreader.settings.DirectEntry, Long?>>()
            for (base in com.example.hyperreader.core.Wenku8Endpoint.DIRECT_HOSTS) {
                val entry = com.example.hyperreader.settings.DirectEntry.entries.firstOrNull { it.base == base } ?: continue
                val started = System.currentTimeMillis()
                val ok = catching {
                    manager.httpClient().fetchTextFromHost(
                        base = base,
                        path = "/modules/article/articleinfo.php?id=2365",
                        jobId = "direct-entry-probe",
                    )
                }.isSuccess
                results += entry to (if (ok) System.currentTimeMillis() - started else null)
            }
            val best = results.filter { it.second != null }.minByOrNull { it.second ?: Long.MAX_VALUE }
            if (best != null) {
                catching { settingsRepository.setDirectEntry(best.first) }
                mutable.update {
                    it.copy(
                        endpointProbing = false,
                        message = "已切换到 ${best.first.label}（${best.second} ms）",
                    )
                }
            } else {
                mutable.update {
                    it.copy(
                        endpointProbing = false,
                        message = "两个直连入口都不可用，请检查网络或配置第三方中继。",
                    )
                }
            }
        }
    }

    fun refreshSession() {
        val loggedIn = app.sessionStore.hasSession()
        if (!loggedIn) {
            officialTagsRequestId += 1
            tagRequestId += 1
            officialTags = emptyList()
        }
        // 登录/登出都作废在途的探索请求：旧请求可能带着旧会话或匿名身份返回。
        // 序号在状态迁移里递增，且后续 loadExplore 会再开新一轮。
        val exploreTags = if (loggedIn) mergedTags(catalogRepository.tagList()) else catalogRepository.tagList()
        mutable.update {
            // 只清掉上一登录态的探索内容；请求序号在这里递增，随后 loadExplore 再开新一轮。
            // 旧请求返回时会因序号不匹配而被丢弃。
            val requestId = it.exploreRequestId + 1
            if (loggedIn) ExploreState.signedIn(it, exploreTags, requestId)
            else ExploreState.signedOut(it, exploreTags, requestId)
        }
        if (loggedIn && officialTags.isEmpty()) loadOfficialTags()
        // 登录/登出会清空探索状态；若用户当前就停在探索页，必须立刻按新登录态
        // 重选默认榜单，否则 ExploreScreen 的 LaunchedEffect(Unit) 不会因状态变化重跑。
        if (state.value.tab == StudioTab.EXPLORE) {
            loadExplore(defaultExplorePage())
        }
    }
    fun clearSearchHistory() { viewModelScope.launch { settingsRepository.clearSearchHistory() } }
    fun searchBooks() {
        val current = state.value
        if (current.searchQuery.isBlank()) return
        // 未登录不再被登录墙挡住：直接用书源的本地书目索引搜索。
        if (!app.sessionStore.hasSession()) {
            searchLocalCatalog(current)
            return
        }
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
            catching { searchProvider.search(current.searchQuery, current.searchField, 1) }
                .onSuccess { page ->
                    if (SearchPager.isCurrent(requestId, searchRequestId)) {
                        mutable.update { SearchPager.firstPage(it, page, app.sessionStore.hasSession()) }
                        settingsRepository.recordSearch(current.searchQuery)
                    }
                }
                .onFailure { error ->
                    if (SearchPager.isCurrent(requestId, searchRequestId)) {
                        if ((error as? Wenku8Exception)?.code == "AUTH_REQUIRED") {
                            // 会话过期：退回本地书目，保证搜索仍然可用。
                            refreshSession()
                            searchLocalCatalog(current)
                        } else {
                            mutable.update { state ->
                                state.copy(
                                    searchBusy = false,
                                    searchLoadingMore = false,
                                    searchMessage = error.message ?: "搜索失败。",
                                    loggedIn = app.sessionStore.hasSession(),
                                )
                            }
                        }
                    }
                }
        }
    }

    /**
     * 免登录搜索：查询本地书目索引（由书源公开页抓取），不触碰登录墙内的 `search.php`。
     * 本地结果一次返回，不再分页。
     */
    private fun searchLocalCatalog(current: StudioUiState) {
        val query = current.searchQuery.trim()
        if (query.isEmpty()) return
        val requestId = ++searchRequestId
        val field = if (current.searchField == SearchField.AUTHOR) CatalogSearchField.AUTHOR else CatalogSearchField.TITLE
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
            val results = withContext(Dispatchers.Default) { catalogRepository.search(query, field) }
            if (!SearchPager.isCurrent(requestId, searchRequestId)) return@launch
            val books = results.map { it.toSearchBook() }
            mutable.update {
                it.copy(
                    searchBusy = false,
                    searchResults = books,
                    searchPage = 1,
                    searchHasNextPage = false,
                    searchLoadingMore = false,
                    searchMessage = if (books.isEmpty() && it.catalogSize == 0) {
                        "本地书目为空，请到「设置 → 书目缓存」更新后再搜索。"
                    } else {
                        null
                    },
                    loggedIn = app.sessionStore.hasSession(),
                )
            }
            if (books.isNotEmpty()) settingsRepository.recordSearch(query)
        }
    }

    fun loadMoreSearchResults() {
        val current = state.value
        if (current.searchQuery.isBlank() || current.searchBusy || current.searchLoadingMore || !current.searchHasNextPage) return
        // 本地搜索一次返回全部结果，没有“下一页”；未登录时不需要再打站内搜索。
        if (!app.sessionStore.hasSession()) return
        val requestId = ++searchRequestId
        val nextPage = current.searchPage + 1
        mutable.update { it.copy(searchLoadingMore = true, searchMessage = null) }
        viewModelScope.launch {
            catching { searchProvider.search(current.searchQuery, current.searchField, nextPage) }
                .onSuccess { page ->
                    if (SearchPager.isCurrent(requestId, searchRequestId)) {
                        mutable.update { SearchPager.morePage(it, page, app.sessionStore.hasSession()) }
                    }
                }
                .onFailure { error ->
                    if (SearchPager.isCurrent(requestId, searchRequestId)) {
                        if ((error as? Wenku8Exception)?.code == "AUTH_REQUIRED") {
                            refreshSession()
                            searchLocalCatalog(state.value)
                        } else {
                            mutable.update { state ->
                                state.copy(
                                    searchLoadingMore = false,
                                    searchMessage = error.message ?: "加载更多失败。",
                                    loggedIn = app.sessionStore.hasSession(),
                                )
                            }
                        }
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
        mutable.update { ExploreState.beginDetail(it, book.id, seed) }
        loadExploreDetail(book.id, seed)
    }

    private fun loadExploreDetail(bookId: String, seed: com.example.hyperreader.core.ExploreBookSeed? = null) {
        var requestId = 0L
        mutable.update { state ->
            val next = ExploreState.detailLoading(state, bookId)
            if (next.exploreDetailRequestId != state.exploreDetailRequestId) {
                requestId = next.exploreDetailRequestId
            }
            next
        }
        // 用户已经关掉详情或打开别的书：不发起请求，也不留下 loading 态。
        if (requestId == 0L) return
        viewModelScope.launch {
            val result = catching { exploreDetailRepository.load(bookId, seed) }
            // 等待网络期间用户可能已关闭详情、打开别的书或点了重试；
            // 只让「当前这本书的最新一次请求」写状态。
            result.onSuccess { detail ->
                mutable.update { ExploreState.detailLoaded(it, bookId, requestId, detail) }
            }.onFailure { error ->
                mutable.update {
                    ExploreState.detailFailed(it, bookId, requestId, error.message ?: "详情加载失败。")
                }
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
    fun applyThemePreset(preset: com.example.hyperreader.settings.AppThemeSettings) {
        viewModelScope.launch { settingsRepository.applyTheme(preset) }
    }

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
            catching { manager.parseSource(value) }
                .onSuccess { (book, index) -> mutable.update { ExportWizard.resolved(it, book, index) } }
                .onFailure { error -> mutable.update { ExportWizard.failed(it, error.message ?: "目录解析失败。") } }
        }
    }

    /** 解析失败后的重试（同一本书、同一目录）。 */
    fun retryResolve() = resolveForExport()

    fun selectAll() = mutable.update { current -> current.copy(selectedIds = current.index?.chapters?.map { it.id }?.toSet() ?: emptySet()) }
    fun clearSelection() = mutable.update { it.copy(selectedIds = emptySet()) }

    /**
     * 选中给定章节（章节筛选后的「全选」用）。
     *
     * 与 [selectAll] 的区别：只动传进来的这批 id，已选的其它章不受影响。
     * 筛选为空时调用方应改用 [selectAll]，避免点了没反应。
     */
    fun selectChapters(ids: Collection<String>) = mutable.update { current ->
        current.copy(selectedIds = current.selectedIds + ids)
    }

    /** 取消给定章节（筛选后的「清空」用）；同样不碰筛选外的已选章节。 */
    fun deselectChapters(ids: Collection<String>) = mutable.update { current ->
        current.copy(selectedIds = current.selectedIds - ids.toSet())
    }
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
            catching { manager.create(book, chapters, current.includeCover) }
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
            catching { manager.create(book, detail.chapters, includeCover = true, engine = engine) }
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

    // ---- 书架与阅读数据备份（与配置导入导出分开，见 settings/BackupTransfer.kt）----

    private val backupMutable = MutableStateFlow(BackupUiState())
    val backupUi: StateFlow<BackupUiState> = backupMutable.asStateFlow()

    fun exportBackupTo(uri: android.net.Uri) = viewModelScope.launch {
        backupMutable.update { it.copy(busy = true, message = null, isError = false) }
        val document = BackupTransfer.documentOf(
            bookshelf = bookshelfRepository.entries.first(),
            preferences = settingsRepository.backupPreferences(),
            stats = readingStatsRepository.snapshot(),
            appVersion = BuildConfig.VERSION_NAME,
            exportedAt = System.currentTimeMillis(),
        )
        ConfigTransferFile.write(app, uri, BackupTransfer.encode(document)).fold(
            onSuccess = {
                backupMutable.update {
                    it.copy(
                        busy = false,
                        isError = false,
                        message = "已导出 ${document.bookshelf.size} 本书与 ${document.preferences.size} 项阅读数据",
                    )
                }
            },
            onFailure = { error ->
                backupMutable.update { it.copy(busy = false, isError = true, message = "导出失败：${error.message ?: "未知错误"}") }
            },
        )
    }

    fun importBackupFrom(uri: android.net.Uri) = viewModelScope.launch {
        backupMutable.update { it.copy(busy = true, message = null, isError = false) }
        // 备份可能比配置文件大得多（书签 + 阅读断点 + 统计），单独放宽上限。
        ConfigTransferFile.read(app, uri, maxBytes = 8 * 1024 * 1024).fold(
            onSuccess = { text ->
                when (val decoded = BackupTransfer.decode(text)) {
                    is BackupTransfer.DecodeResult.Rejected ->
                        backupMutable.update { it.copy(busy = false, isError = true, message = decoded.reason) }
                    is BackupTransfer.DecodeResult.Success -> {
                        val document = decoded.document
                        catching {
                            bookshelfRepository.replaceAll(document.bookshelf)
                            settingsRepository.restorePreferences(document.preferences)
                            document.stats?.let { readingStatsRepository.replace(it) }
                        }.fold(
                            onSuccess = {
                                backupMutable.update {
                                    it.copy(
                                        busy = false,
                                        isError = false,
                                        message = "已恢复 ${document.bookshelf.size} 本书与 ${document.preferences.size} 项阅读数据",
                                    )
                                }
                            },
                            onFailure = { error ->
                                backupMutable.update { it.copy(busy = false, isError = true, message = "恢复失败：${error.message ?: "未知错误"}") }
                            },
                        )
                    }
                }
            },
            onFailure = { error ->
                backupMutable.update { it.copy(busy = false, isError = true, message = "读取失败：${error.message ?: "未知错误"}") }
            },
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

        /**
         * 公开榜补全的连续失败阈值。
         *
         * 一次 Cloudflare 拦截通常会影响整条匿名链路；连续 [EXPLORE_PUBLIC_FAILURE_STREAK]
         * 本失败就停止本轮剩余请求，避免 20 次请求全部撞在限流与风控上。
         */
        const val EXPLORE_PUBLIC_FAILURE_STREAK = 3

        /** 公开榜补全是否应停止本轮：连续失败达到阈值即停，避免把整轮都撞进风控。 */
        internal fun shouldStopPublicFetch(consecutiveFailures: Int): Boolean =
            consecutiveFailures >= EXPLORE_PUBLIC_FAILURE_STREAK

        /** 纯函数版本，便于验证匿名/登录两种情况都不会把首屏落到不可加载的榜单。 */
        internal fun defaultExplorePage(pages: List<ExplorePage>, loggedIn: Boolean): ExplorePage =
            if (loggedIn) {
                pages.firstOrNull { it.requiresAuth } ?: pages.first()
            } else {
                pages.firstOrNull { !it.requiresAuth } ?: pages.first()
            }

        /** 中继端点非法时的统一提示（要求 https 域名，禁止 IP 与 wenku8 自身域名）。 */
        private const val RELAY_BASE_INVALID = "中继端点无效：需形如 https://relay.example.com，不能用 IP 或 wenku8 自身域名。"
    }

    fun save(id: String) {
        viewModelScope.launch {
            val job = manager.get(id)
            val output = job?.output
            if (job == null || output == null) {
                mutable.update { it.copy(message = "EPUB 尚未生成完成。") }
                return@launch
            }
            // 文件拷贝可能几十 MB，放到 IO 线程；完成/失败都要给回执，否则用户会反复点。
            catching { withContext(Dispatchers.IO) { manager.save(id) } }
                .onSuccess { mutable.update { it.copy(message = "已保存《${job.book.title}》到下载/EPUB。") } }
                .onFailure { error -> mutable.update { it.copy(message = error.message ?: "保存失败。") } }
        }
    }
    fun share(id: String) = manager.share(id)

    fun cancelJob(id: String) = manager.cancel(id)

    fun deleteJob(id: String) {
        viewModelScope.launch { manager.delete(id) }
    }
}

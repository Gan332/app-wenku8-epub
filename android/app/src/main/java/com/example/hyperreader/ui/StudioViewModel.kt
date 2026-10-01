package com.example.hyperreader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hyperreader.Wenku8Application
import com.example.hyperreader.core.CatalogEntry
import com.example.hyperreader.core.CatalogSearchField
import com.example.hyperreader.core.ExploreBooksRow
import com.example.hyperreader.core.ExplorePage
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

enum class StudioTab { BOOKSHELF, EXPLORE, CREATE, SETTINGS }
enum class CreateStep { SOURCE, DETAIL, CHAPTERS, EXPORT, PROGRESS }

/** 设置二级界面分区。 */
enum class SettingsSection { OVERVIEW, APPEARANCE, READER, STATISTICS, CATALOG, CONFIG, ABOUT }

data class StudioUiState(
    val tab: StudioTab = StudioTab.BOOKSHELF,
    val step: CreateStep = CreateStep.SOURCE,
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
    val jobs: List<ExportJob> = emptyList(),
    val searchQuery: String = "",
    val searchField: SearchField = SearchField.TITLE,
    val searchResults: List<SearchBook> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val searchBusy: Boolean = false,
    val searchMessage: String? = null,
    val loggedIn: Boolean = false,
    val bookshelf: List<BookshelfEntry> = emptyList(),
    val readingStats: ReadingStats = ReadingStats(),
    val exploreRows: List<ExploreBooksRow> = emptyList(),
    val exploreBusy: Boolean = false,
    val exploreMessage: String? = null,
    val catalogSize: Int = 0,
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
     * 因此打开探索详情不会触碰 `CreateStep`、也不会改动创建流程里的半成品状态。
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
    private val mutable = MutableStateFlow(StudioUiState())
    val state: StateFlow<StudioUiState> = mutable.asStateFlow()
    val appTheme = settingsRepository.appTheme

    /** EPUB 导出引擎（设置页可切换）；导出任务在打包时读取当前值。 */
    val exportEngine: kotlinx.coroutines.flow.Flow<com.example.hyperreader.settings.EpubEngine> =
        settingsRepository.exportEngine

    fun setExportEngine(engine: com.example.hyperreader.settings.EpubEngine) {
        viewModelScope.launch { runCatching { settingsRepository.setExportEngine(engine) } }
    }

    /** 书架「读到哪了」：bookId → 阅读断点。 */
    val readingProgress = settingsRepository.allProgress()

    init {
        viewModelScope.launch {
            manager.jobs.collect { jobs ->
                mutable.update { current ->
                    val active = current.activeJobId?.let { id -> jobs.values.firstOrNull { it.id == id } }
                    current.copy(jobs = jobs.values.sortedByDescending { it.createdAt }, step = if (active != null && current.step == CreateStep.PROGRESS) CreateStep.PROGRESS else current.step)
                }
            }
        }
        mutable.update { it.copy(loggedIn = app.sessionStore.hasSession()) }
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
     * 公开探索来源：年度精选榜与月度新书榜，均为匿名可访问页面。
     * 不再使用 toplist.php / tags.php / articlelist.php（由站点控制登录）。
     */
    val explorePages: List<ExplorePage> = buildList {
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
    fun loadExplore(page: ExplorePage = explorePages.first()) {
        viewModelScope.launch {
            mutable.update { it.copy(exploreBusy = true, exploreMessage = null) }
            val ids = runCatching {
                val publicClient = com.example.hyperreader.core.Wenku8HttpClient(java.io.File(app.cacheDir, "wenku8-explore"))
                val resource = publicClient.fetchText(page.url, "explore-public", Wenku8Urls.BASE)
                Wenku8Parser.parseBookLinks(resource.html, resource.finalUrl).map { it.id }
            }.getOrElse { error ->
                mutable.update { it.copy(exploreBusy = false, exploreMessage = error.message ?: "加载失败。") }
                return@launch
            }
            if (ids.isEmpty()) {
                mutable.update {
                    it.copy(exploreBusy = false, exploreRows = emptyList(), exploreMessage = "该榜单暂时没有内容。")
                }
                return@launch
            }

            val known = ids.mapNotNull { catalogRepository.get(it) }
            val collected = known.map { it.toSearchBook() }.toMutableList()
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

    fun updateCatalog(budget: Int = 200) = catalogRepository.update(budget)
    fun clearCatalog() = catalogRepository.clear()

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
    fun openShelfRemote(entry: BookshelfEntry) {
        mutable.update { it.copy(sourceUrl = entry.sourceUrl, tab = StudioTab.CREATE, step = CreateStep.SOURCE, book = null, index = null) }
        parseSource()
    }
    fun clearReadingStats() { viewModelScope.launch { readingStatsRepository.clear() } }

    fun setSource(value: String) = mutable.update { it.copy(sourceUrl = value, message = null) }
    fun setSearch(value: String) = mutable.update { it.copy(search = value) }
    fun setCover(value: Boolean) = mutable.update { it.copy(includeCover = value) }
    fun clearMessage() = mutable.update { it.copy(message = null) }

    fun setSearchQuery(value: String) = mutable.update { it.copy(searchQuery = value, searchMessage = null) }
    fun setSearchField(value: SearchField) = mutable.update { it.copy(searchField = value) }
    fun refreshSession() = mutable.update { it.copy(loggedIn = app.sessionStore.hasSession()) }
    fun clearSearchHistory() { viewModelScope.launch { settingsRepository.clearSearchHistory() } }
    fun searchBooks() {
        val current = state.value
        if (current.searchQuery.isBlank()) return
        viewModelScope.launch {
            mutable.update { it.copy(searchBusy = true, searchMessage = null) }
            runCatching { searchProvider.search(current.searchQuery, current.searchField) }
                .onSuccess { results -> mutable.update { it.copy(searchBusy = false, searchResults = results, loggedIn = app.sessionStore.hasSession()) }; settingsRepository.recordSearch(current.searchQuery) }
                .onFailure { error -> mutable.update { it.copy(searchBusy = false, searchMessage = error.message ?: "搜索失败。", loggedIn = app.sessionStore.hasSession()) } }
        }
    }
    /**
     * 探索页点书：打开**独立的书籍详情页**。
     *
     * 刻意**不走** `parseSource()` / `CreateStep`：那条链路属于「创建导出」流程，
     * 会做导出前置校验并吃满 1 秒/请求 的批量节流，点一下要等很久。
     * 这里改由 [exploreDetailRepository] 直接调公开 API（`articleinfo.php` + 目录页），
     * 交互档节流 + 内存缓存，且不改动创建流程的任何状态。
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

    fun parseSource() {
        val value = state.value.sourceUrl.trim()
        if (value.isEmpty()) { mutable.update { it.copy(message = "请输入书籍或目录网址。") }; return }
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, message = null, detailError = null) }
            runCatching { manager.parseSource(value) }
                .onSuccess { (book, index) ->
                    mutable.update { it.copy(busy = false, detailError = null, book = book, index = index, selectedIds = index.chapters.map { chapter -> chapter.id }.toSet(), step = CreateStep.DETAIL) }
                }
                .onFailure { error ->
                    // 详情页已经用预览数据渲染出来了，失败时就地提示，不要退回解析页
                    mutable.update { it.copy(busy = false, detailError = error.message ?: "解析失败。") }
                }
        }
    }

    /** 详情页目录加载失败后的重试。 */
    fun retryLoadIndex() {
        if (state.value.sourceUrl.isNotBlank()) parseSource()
    }

    fun selectAll() = mutable.update { current -> current.copy(selectedIds = current.index?.chapters?.map { it.id }?.toSet() ?: emptySet()) }
    fun toChapters() {
        if (state.value.index?.chapters.isNullOrEmpty()) { mutable.update { it.copy(message = "未找到目录章节。") }; return }
        mutable.update { it.copy(step = CreateStep.CHAPTERS, message = null) }
    }
    fun clearSelection() = mutable.update { it.copy(selectedIds = emptySet()) }
    fun toggleChapter(id: String) = mutable.update { current -> current.copy(selectedIds = if (id in current.selectedIds) current.selectedIds - id else current.selectedIds + id) }
    fun backToSource() = mutable.update { it.copy(step = CreateStep.SOURCE, message = null) }
    fun toExport() {
        if (state.value.selectedIds.isEmpty()) { mutable.update { it.copy(message = "请至少选择一个章节。") }; return }
        mutable.update { it.copy(step = CreateStep.EXPORT, message = null) }
    }
    fun backToChapters() = mutable.update { it.copy(step = CreateStep.CHAPTERS, message = null) }

    fun startExport() {
        val current = state.value
        val book = current.book ?: return
        val chapters = current.index?.chapters?.filter { it.id in current.selectedIds } ?: return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, message = null) }
            runCatching { manager.create(book, chapters, current.includeCover) }
                .onSuccess { job -> mutable.update { it.copy(busy = false, activeJobId = job.id, step = CreateStep.PROGRESS, tab = StudioTab.CREATE) } }
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
                            step = CreateStep.PROGRESS,
                            tab = StudioTab.CREATE,
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
                it.copy(tab = StudioTab.CREATE, step = CreateStep.PROGRESS, activeJobId = target.jobId, showJobHistory = false)
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
    }

    fun save(id: String) {
        runCatching { manager.save(id) }
            .onFailure { error -> mutable.update { it.copy(message = error.message ?: "保存失败。") } }
    }
    fun share(id: String) = manager.share(id)
}

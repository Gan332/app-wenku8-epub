package com.wenku8.epubstudio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wenku8.epubstudio.Wenku8Application
import com.wenku8.epubstudio.core.CatalogEntry
import com.wenku8.epubstudio.core.CatalogSearchField
import com.wenku8.epubstudio.core.ExploreBooksRow
import com.wenku8.epubstudio.core.ExplorePage
import com.wenku8.epubstudio.core.Wenku8HttpClient
import com.wenku8.epubstudio.core.Wenku8Parser
import com.wenku8.epubstudio.core.Wenku8Urls
import com.wenku8.epubstudio.model.Book
import com.wenku8.epubstudio.model.BookIndex
import com.wenku8.epubstudio.model.BookshelfEntry
import com.wenku8.epubstudio.model.BookshelfSource
import com.wenku8.epubstudio.model.ExportJob
import com.wenku8.epubstudio.model.JobStatus
import com.wenku8.epubstudio.model.ReadingStats
import com.wenku8.epubstudio.model.SearchBook
import com.wenku8.epubstudio.model.SearchField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
enum class SettingsSection { OVERVIEW, APPEARANCE, READER, STATISTICS, CATALOG, ABOUT }

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
    val message: String? = null,
    val activeJobId: String? = null,
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
    val readerSettings: com.wenku8.epubstudio.settings.ReaderSettings = com.wenku8.epubstudio.settings.ReaderSettings(),
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
    private val mutable = MutableStateFlow(StudioUiState())
    val state: StateFlow<StudioUiState> = mutable.asStateFlow()
    val appTheme = settingsRepository.appTheme

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

    fun loadExplore(page: ExplorePage = explorePages.first()) {
        viewModelScope.launch {
            mutable.update { it.copy(exploreBusy = true, exploreMessage = null) }
            runCatching {
                val publicClient = com.wenku8.epubstudio.core.Wenku8HttpClient(java.io.File(app.cacheDir, "wenku8-explore"))
                val resource = publicClient.fetchText(page.url, "explore-public", Wenku8Urls.BASE)
                Wenku8Parser.parseBookLinks(resource.html, resource.finalUrl).map { it.id }
            }.onSuccess { ids ->
                // 公开页只给出 ID，用本地索引补全元数据；缺失的排队下次抓取
                val known = ids.mapNotNull { catalogRepository.get(it) }
                val pending = ids.size - known.size
                val rows = if (known.isEmpty()) emptyList() else listOf(ExploreBooksRow(page.title, known.map { it.toSearchBook() }, page.id))
                mutable.update {
                    it.copy(
                        exploreBusy = false,
                        exploreRows = rows,
                        exploreMessage = if (pending > 0) "已缓存 ${known.size} 本，另有 $pending 本可在「更新书目」时补全。" else null,
                    )
                }
            }.onFailure { error ->
                mutable.update { it.copy(exploreBusy = false, exploreMessage = error.message ?: "加载失败。") }
            }
        }
    }

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
    fun openSearchBook(book: SearchBook) {
        mutable.update { it.copy(sourceUrl = book.sourceUrl, tab = StudioTab.CREATE, step = CreateStep.SOURCE, book = null, index = null) }
        parseSource()
    }
    fun setThemeMode(mode: com.wenku8.epubstudio.settings.AppThemeMode) { viewModelScope.launch { settingsRepository.setThemeMode(mode) } }
    fun setDynamicColor(enabled: Boolean) { viewModelScope.launch { settingsRepository.setDynamicColor(enabled) } }
    fun setAccentColor(color: Int) { viewModelScope.launch { settingsRepository.setAccentColor(color) } }

    // 阅读器设置：与阅读器共用同一 DataStore，改动实时生效
    fun setReaderFontSize(value: Float) { viewModelScope.launch { settingsRepository.setReaderFontSize(value) } }
    fun setReaderFontWeight(value: Int) { viewModelScope.launch { settingsRepository.setReaderFontWeight(value) } }
    fun setReaderLineHeight(value: Float) { viewModelScope.launch { settingsRepository.setReaderLineHeight(value) } }
    fun setReaderSpacing(value: Int) { viewModelScope.launch { settingsRepository.setReaderParagraphSpacing(value) } }
    fun setReaderPadding(value: Int) { viewModelScope.launch { settingsRepository.setReaderHorizontalPadding(value) } }
    fun setReaderBackground(value: com.wenku8.epubstudio.settings.ReaderBackground) { viewModelScope.launch { settingsRepository.setReaderBackground(value) } }
    fun setReaderBackgroundColor(value: Int) { viewModelScope.launch { settingsRepository.setReaderCustomBackground(value) } }
    fun setReaderTextColor(value: Int) { viewModelScope.launch { settingsRepository.setReaderTextColor(value) } }
    fun setReaderPageMode(value: com.wenku8.epubstudio.settings.ReaderPageTurnMode) { viewModelScope.launch { settingsRepository.setReaderPageTurn(value) } }
    fun setReaderKeepScreenOn(value: Boolean) { viewModelScope.launch { settingsRepository.setReaderKeepScreenOn(value) } }
    fun setReaderImmersive(value: Boolean) { viewModelScope.launch { settingsRepository.setReaderImmersive(value) } }
    fun resetReaderFont() { viewModelScope.launch { settingsRepository.setReaderFontUri(null) } }
    fun applyImportedFont(path: String) { viewModelScope.launch { settingsRepository.setReaderFontUri(path) } }

    fun parseSource() {
        val value = state.value.sourceUrl.trim()
        if (value.isEmpty()) { mutable.update { it.copy(message = "请输入书籍或目录网址。") }; return }
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, message = null) }
            runCatching { manager.parseSource(value) }
                .onSuccess { (book, index) -> mutable.update { it.copy(busy = false, book = book, index = index, selectedIds = index.chapters.map { chapter -> chapter.id }.toSet(), step = CreateStep.DETAIL) } }
                .onFailure { error -> mutable.update { it.copy(busy = false, message = error.message ?: "解析失败。") } }
        }
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
    fun save(id: String) {
        runCatching { manager.save(id) }
            .onFailure { error -> mutable.update { it.copy(message = error.message ?: "保存失败。") } }
    }
    fun share(id: String) = manager.share(id)
}

package com.wenku8.epubstudio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wenku8.epubstudio.Wenku8Application
import com.wenku8.epubstudio.core.ExploreBooksRow
import com.wenku8.epubstudio.core.ExplorePage
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class StudioTab { BOOKSHELF, EXPLORE, CREATE, STATS, SETTINGS }
enum class CreateStep { SOURCE, DETAIL, CHAPTERS, EXPORT, PROGRESS }

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
)

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as Wenku8Application
    private val manager = app.jobManager
    private val searchProvider = app.searchProvider
    private val settingsRepository = app.settingsRepository
    private val bookshelfRepository = app.bookshelfRepository
    private val readingStatsRepository = app.readingStatsRepository
    private val exploreRepository = app.exploreRepository
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
    }

    fun setTab(tab: StudioTab) = mutable.update { it.copy(tab = tab, message = null) }

    val explorePages: List<ExplorePage> = listOf(
        ExplorePage("lastupdate", "今日更新", Wenku8Urls.toplist("lastupdate"), requiresAuth = false),
        ExplorePage("allvisit", "热门轻小说", Wenku8Urls.toplist("allvisit"), requiresAuth = false),
        ExplorePage("postdate", "新书一览", Wenku8Urls.toplist("postdate"), requiresAuth = false),
        ExplorePage("anime", "动画化作品", Wenku8Urls.toplist("anime"), requiresAuth = false),
        ExplorePage("all", "全部轻小说", "${Wenku8Urls.ARTICLE_LIST}?s=0", requiresAuth = false),
        ExplorePage("校园", "校园", Wenku8Urls.tag("校园"), requiresAuth = false),
        ExplorePage("恋爱", "恋爱", Wenku8Urls.tag("恋爱"), requiresAuth = false),
        ExplorePage("奇幻", "奇幻", Wenku8Urls.tag("奇幻"), requiresAuth = false),
        ExplorePage("科幻", "科幻", Wenku8Urls.tag("科幻"), requiresAuth = false),
    )

    fun loadExplore(page: ExplorePage = explorePages.first()) {
        viewModelScope.launch {
            mutable.update { it.copy(exploreBusy = true, exploreMessage = null) }
            runCatching { exploreRepository.load(page) }
                .onSuccess { books -> mutable.update { it.copy(exploreBusy = false, exploreRows = listOf(ExploreBooksRow(page.title, books, page.id))) } }
                .onFailure { error -> mutable.update { it.copy(exploreBusy = false, exploreMessage = error.message ?: "探索失败。") } }
        }
    }

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

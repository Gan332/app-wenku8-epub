package com.wenku8.epubstudio.reader

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wenku8.epubstudio.Wenku8Application
import com.wenku8.epubstudio.file.FontStore
import com.wenku8.epubstudio.settings.ReadingProgress
import com.wenku8.epubstudio.settings.ReaderBackground
import com.wenku8.epubstudio.settings.ReaderPageTurnMode
import com.wenku8.epubstudio.ui.AppMiuixTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 在线阅读的状态持有者。
 *
 * 它实现 [ReaderActions]——与 `ReaderViewModel` **同一个接口**，
 * 因此在线与 EPUB 两种模式共用同一份渲染界面（`ReaderScreenCore`），
 * 字体、背景、沉浸模式、目录面板、阅读进度、时长统计的行为完全一致。
 *
 * 与 EPUB 的唯一差别是数据来源：目录来自 wenku8 的公开目录页，正文按需抓取并落盘缓存。
 */
class OnlineReaderViewModel(application: Application) : AndroidViewModel(application), ReaderActions {
    private val app = application as Wenku8Application
    private val source = app.onlineReaderSource
    private val settingsRepository = app.settingsRepository
    private val mutable = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = mutable.asStateFlow()

    private var bookId: String = ""
    private var bookshelfId: String = ""
    private var bookTitle: String = ""
    private var catalog: List<com.wenku8.epubstudio.model.Chapter> = emptyList()
    private var sessionStartedAt: Long? = null
    private var chapterJob: Job? = null

    init {
        viewModelScope.launch {
            val settings = settingsRepository.readerSettings.first()
            mutable.update { it.copy(settings = settings, isImmersive = settings.immersiveMode, controlsVisible = !settings.immersiveMode) }
        }
    }

    fun load(bookId: String, title: String, author: String, bookshelfId: String) {
        this.bookId = bookId
        this.bookTitle = title
        this.bookshelfId = bookshelfId
        viewModelScope.launch {
            mutable.update { it.copy(loading = true, error = null, notice = null) }
            // 进入在线阅读时在书架打点
            runCatching { app.bookshelfRepository.recordRead(bookshelfId) }
            when (val result = source.loadIndex(bookId, title = title, author = author)) {
                is OnlineReaderResult.Ready -> {
                    catalog = result.value.catalog.chapters
                    val progress = settingsRepository.progress(bookId).first()
                    val index = progress?.chapterIndex?.coerceIn(0, (catalog.size - 1).coerceAtLeast(0)) ?: 0
                    bookTitle = result.value.book.title
                    mutable.update {
                        it.copy(
                            loading = false,
                            error = null,
                            book = result.value.book,
                            chapterIndex = index,
                            paragraphIndex = progress?.paragraphIndex ?: 0,
                        )
                    }
                    loadCurrentChapter()
                }
                is OnlineReaderResult.NeedsLogin -> mutable.update { it.copy(loading = false, error = result.message) }
                is OnlineReaderResult.Failed -> mutable.update { it.copy(loading = false, error = result.message) }
            }
        }
    }

    /**
     * 抓取当前章节。**串行**：一次只跑一个任务，切换章节会取消上一个，
     * 不做任何并发预取——预取会打爆共享的 1 秒/请求限流器。
     */
    fun loadCurrentChapter() {
        val index = state.value.chapterIndex
        val target = catalog.getOrNull(index) ?: return
        chapterJob?.cancel()
        chapterJob = viewModelScope.launch {
            mutable.update { it.copy(chapterLoading = true, notice = null) }
            when (val result = source.loadChapter(bookId, target, catalog)) {
                is OnlineReaderResult.Ready -> {
                    // 只有当前章被填充正文；其它章保持空块，**不做预取**
                    val book = state.value.book
                    val chapters = book?.chapters?.toMutableList()
                    if (chapters != null) {
                        chapters.getOrNull(index)?.let { chapters[index] = it.copy(blocks = result.value.blocks) }
                    }
                    mutable.update {
                        it.copy(chapterLoading = false, error = null, book = if (chapters == null) it.book else it.book?.copy(chapters = chapters))
                    }
                }
                is OnlineReaderResult.NeedsLogin ->
                    // 只如实提示，绝不绕过登录
                    mutable.update { it.copy(chapterLoading = false, notice = result.message) }
                is OnlineReaderResult.Failed -> {
                    val total = state.value.book?.chapters?.size ?: 0
                    if (result.code == OnlineReaderResult.CODE_CHAPTER_GONE) {
                        // 章节被源站删除：跳到相邻章节
                        val next = source.nearestChapterIndex(index, total, step = 1)
                        if (next != index) {
                            mutable.update { it.copy(notice = result.message, chapterLoading = false) }
                            selectChapter(next)
                        } else {
                            mutable.update { it.copy(chapterLoading = false, error = result.message) }
                        }
                    } else {
                        mutable.update { it.copy(chapterLoading = false, error = result.message) }
                    }
                }
            }
        }
    }

    fun clearNotice() = mutable.update { it.copy(notice = null) }

    // ---- ReaderActions：与 ReaderViewModel 同名同签名，实现见下 ----

    override fun selectChapter(index: Int) {
        val book = state.value.book ?: return
        val safe = index.coerceIn(0, book.chapters.lastIndex.coerceAtLeast(0))
        mutable.update { it.copy(chapterIndex = safe, paragraphIndex = 0, showToc = false, error = null) }
        persistProgress()
        loadCurrentChapter()
    }

    override fun nextChapter() = selectChapter(state.value.chapterIndex + 1)
    override fun previousChapter() = selectChapter(state.value.chapterIndex - 1)
    override fun setParagraph(index: Int) {
        mutable.update { it.copy(paragraphIndex = index.coerceAtLeast(0)) }
        persistProgress()
    }

    override fun toggleControls() = mutable.update { current ->
        val next = !current.isImmersive
        current.copy(isImmersive = next, controlsVisible = !next)
    }

    override fun setImmersive(value: Boolean) = mutable.update { it.copy(isImmersive = value, controlsVisible = !value) }
    override fun showSettings(show: Boolean) = mutable.update { it.copy(showSettings = show, isImmersive = false) }
    override fun showToc(show: Boolean) = mutable.update { it.copy(showToc = show, isImmersive = false) }
    override fun closeOverlays() = mutable.update { it.copy(showSettings = false, showToc = false) }

    // ReaderActions 声明返回 Unit，因此这里必须用块体；表达式体会把 launch 的 Job 暴露成返回类型
    override fun updateFontSize(value: Float) { viewModelScope.launch { settingsRepository.setReaderFontSize(value); refreshSettings() } }
    override fun updateFontWeight(value: Int) { viewModelScope.launch { settingsRepository.setReaderFontWeight(value); refreshSettings() } }
    override fun updateLineHeight(value: Float) { viewModelScope.launch { settingsRepository.setReaderLineHeight(value); refreshSettings() } }
    override fun updateSpacing(value: Int) { viewModelScope.launch { settingsRepository.setReaderParagraphSpacing(value); refreshSettings() } }
    override fun updatePadding(value: Int) { viewModelScope.launch { settingsRepository.setReaderHorizontalPadding(value); refreshSettings() } }
    override fun updateBackground(value: ReaderBackground) { viewModelScope.launch { settingsRepository.setReaderBackground(value); refreshSettings() } }
    override fun updateBackgroundColor(value: Int) { viewModelScope.launch { settingsRepository.setReaderCustomBackground(value); refreshSettings() } }
    override fun updateTextColor(value: Int) { viewModelScope.launch { settingsRepository.setReaderTextColor(value); refreshSettings() } }
    override fun updatePageMode(value: ReaderPageTurnMode) { viewModelScope.launch { settingsRepository.setReaderPageTurn(value); refreshSettings() } }
    override fun updateKeepScreenOn(value: Boolean) { viewModelScope.launch { settingsRepository.setReaderKeepScreenOn(value); refreshSettings() } }
    override fun updateImmersive(value: Boolean) { viewModelScope.launch { settingsRepository.setReaderImmersive(value); setImmersive(value); refreshSettings() } }
    override fun updateFontUri(value: String?) { viewModelScope.launch { settingsRepository.setReaderFontUri(value) } }

    fun startSession() {
        if (sessionStartedAt == null) sessionStartedAt = SystemClock.elapsedRealtime()
    }

    fun stopSession() {
        val started = sessionStartedAt ?: return
        sessionStartedAt = null
        val seconds = ((SystemClock.elapsedRealtime() - started) / 1000L).coerceIn(0L, 1800L)
        if (seconds > 0 && bookId.isNotBlank()) {
            viewModelScope.launch { app.readingStatsRepository.recordSession(bookId, bookTitle, seconds) }
        }
    }

    override fun onCleared() {
        stopSession()
        super.onCleared()
    }

    private fun refreshSettings() {
        viewModelScope.launch { mutable.update { it.copy(settings = settingsRepository.readerSettings.first()) } }
    }

    private fun persistProgress() {
        val current = state.value
        val book = current.book ?: return
        val chapter = book.chapters.getOrNull(current.chapterIndex) ?: return
        if (bookId.isBlank()) return
        viewModelScope.launch {
            settingsRepository.saveProgress(
                ReadingProgress(bookId, chapter.id, current.chapterIndex, current.paragraphIndex, System.currentTimeMillis()),
            )
        }
    }
}

/**
 * 在线阅读 Activity。
 *
 * 与 [ReaderActivity] 的区别只有数据来源：这里不需要 `epub_uri`，
 * 只需要 wenku8 的书籍编号。渲染界面通过 `ReaderScreenCore` 与 EPUB 完全共用。
 */
class OnlineReaderActivity : ComponentActivity() {
    private val viewModel: OnlineReaderViewModel by viewModels()
    private val fontPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        FontStore(this).import(uri)?.let { viewModel.updateFontUri(it) }
    }

    override fun onStart() {
        super.onStart()
        viewModel.startSession()
    }

    override fun onStop() {
        viewModel.stopSession()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty().filter(Char::isDigit)
        if (bookId.isBlank()) {
            finish()
            return
        }
        viewModel.load(
            bookId = bookId,
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            author = intent.getStringExtra(EXTRA_AUTHOR).orEmpty(),
            bookshelfId = intent.getStringExtra(EXTRA_BOOKSHELF_ID)?.takeIf { it.isNotBlank() } ?: "wenku8:$bookId",
        )
        setContent {
            AppMiuixTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                ReaderScreenCore(
                    state = state,
                    actions = viewModel,
                    imageResolver = { block -> rememberRemoteImage(block.path) },
                    onImportFont = { fontPicker.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream")) },
                    // 在线模式没有 EPUB 可导入
                    onImportEpub = {},
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_BOOK_ID = "online_book_id"
        const val EXTRA_TITLE = "online_title"
        const val EXTRA_AUTHOR = "online_author"
        const val EXTRA_BOOKSHELF_ID = "online_bookshelf_id"
    }
}

/** 供书架侧复用的启动片段：避免各处重复拼 Intent。 */
fun onlineReaderIntent(
    context: android.content.Context,
    bookId: String,
    title: String,
    author: String,
    bookshelfId: String = "wenku8:$bookId",
): Intent = Intent(context, OnlineReaderActivity::class.java)
    .putExtra(OnlineReaderActivity.EXTRA_BOOK_ID, bookId)
    .putExtra(OnlineReaderActivity.EXTRA_TITLE, title)
    .putExtra(OnlineReaderActivity.EXTRA_AUTHOR, author)
    .putExtra(OnlineReaderActivity.EXTRA_BOOKSHELF_ID, bookshelfId)

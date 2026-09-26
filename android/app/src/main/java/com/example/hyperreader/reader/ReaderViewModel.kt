package com.example.hyperreader.reader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hyperreader.Wenku8Application
import com.example.hyperreader.settings.ReaderPageTurnMode
import com.example.hyperreader.settings.ReaderSettings
import com.example.hyperreader.settings.ReadingProgress
import com.example.hyperreader.settings.ReaderBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

 data class ReaderUiState(
    val loading: Boolean = true,
    val book: ReaderBook? = null,
    val chapterIndex: Int = 0,
    val paragraphIndex: Int = 0,
    val settings: ReaderSettings = ReaderSettings(),
    val error: String? = null,
    val showSettings: Boolean = false,
    val showToc: Boolean = false,
    val isImmersive: Boolean = true,
    val controlsVisible: Boolean = false,
    /** 在线模式：正在抓取当前章节正文。EPUB 模式恒为 false。 */
    val chapterLoading: Boolean = false,
    /** 在线模式：一次性提示（如「该内容需要登录」）。不打断已渲染的正文。 */
    val notice: String? = null,
)

/** [resolveBack] 的返回值：返回键该做什么。 */
enum class BackAction { CLOSE_SETTINGS, CLOSE_TOC, SHOW_CONTROLS, EXIT }

/**
 * 顶部/底部菜单栏是否可见。
 *
 * 曾经 UI 只看 `isImmersive`、`controlsVisible` 成了只写不读的死状态，
 * 导致点正文呼出菜单栏失效（默认沉浸态下菜单栏永远出不来）。
 * 面板打开时菜单栏也必须在（关掉面板后菜单栏还在）。
 */
fun ReaderUiState.controlsShown(): Boolean = controlsVisible || showSettings || showToc

/**
 * 返回键的决策。纯函数，可单测。
 *
 * 规则与 0.8.x 行为等价：面板打开先关面板；菜单栏可见则退出；
 * 菜单栏隐藏则先呼出——**不修改 `isImmersive` 持久设置**，
 * 也不会出现「隐藏→显示→隐藏」的死循环。
 */
fun ReaderUiState.resolveBack(): BackAction = when {
    showSettings -> BackAction.CLOSE_SETTINGS
    showToc -> BackAction.CLOSE_TOC
    controlsVisible -> BackAction.EXIT
    else -> BackAction.SHOW_CONTROLS
}

/**
 * 阅读界面用到的**纯 UI 回调**。
 *
 * EPUB（`ReaderViewModel`）与在线（`OnlineReaderViewModel`）各自持有状态，但共用同一份渲染界面，
 * 因此把界面所需的回调抽成这个接口，渲染层只依赖接口而不是具体 ViewModel。
 *
 * 刻意**不包含** `load(uri, id)`（EPUB 专属装配）、`startSession()` / `stopSession()`
 * （`AndroidViewModel` 生命周期职责），也不包含任何网络或缓存逻辑。
 */
interface ReaderActions {
    fun selectChapter(index: Int)
    fun nextChapter()
    fun previousChapter()
    fun setParagraph(index: Int)
    fun toggleControls()
    fun setImmersive(value: Boolean)
    fun showSettings(show: Boolean)
    fun showToc(show: Boolean)
    fun closeOverlays()
    fun updateFontSize(value: Float)
    fun updateFontWeight(value: Int)
    fun updateLineHeight(value: Float)
    fun updateSpacing(value: Int)
    fun updatePadding(value: Int)
    fun updateBackground(value: ReaderBackground)
    fun updateBackgroundColor(value: Int)
    fun updateTextColor(value: Int)
    fun updatePageMode(value: ReaderPageTurnMode)
    fun updateKeepScreenOn(value: Boolean)
    fun updateImmersive(value: Boolean)
    fun updateFontUri(value: String?)
}

class ReaderViewModel(application: Application) : AndroidViewModel(application), ReaderActions {
    private val app = application as Wenku8Application
    private val repository = EpubReaderRepository(application)
    private val settingsRepository = app.settingsRepository
    private val mutable = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = mutable.asStateFlow()
    private var bookId: String = ""
    private var bookTitle: String = ""
    private var sessionStartedAt: Long? = null

    init {
        viewModelScope.launch {
            val settings = settingsRepository.readerSettings.first()
            mutable.update { it.copy(settings = settings, isImmersive = settings.immersiveMode, controlsVisible = !settings.immersiveMode) }
        }
    }

    fun load(uri: Uri, id: String) {
        bookId = id
        viewModelScope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            runCatching { withContext(Dispatchers.IO) { repository.open(id, uri) } }
                .onSuccess { book ->
                    bookTitle = book.title
                    val progress = settingsRepository.progress(id).first()
                    val index = progress?.chapterIndex?.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0)) ?: 0
                    mutable.update { it.copy(loading = false, book = book, chapterIndex = index, paragraphIndex = progress?.paragraphIndex ?: 0) }
                }
                .onFailure { error -> mutable.update { it.copy(loading = false, error = error.message ?: "无法打开 EPUB。") } }
        }
    }

    override fun selectChapter(index: Int) {
        val book = state.value.book ?: return
        val safe = index.coerceIn(0, book.chapters.lastIndex)
        mutable.update { it.copy(chapterIndex = safe, paragraphIndex = 0, showToc = false) }
        persistProgress()
    }

    override fun nextChapter() = selectChapter(state.value.chapterIndex + 1)
    override fun previousChapter() = selectChapter(state.value.chapterIndex - 1)
    override fun setParagraph(index: Int) { mutable.update { it.copy(paragraphIndex = index.coerceAtLeast(0)) }; persistProgress() }
    /**
     * 点击正文只切换控件显隐，**不改 isImmersive**。
     * 之前翻转 isImmersive（持久设置）会让系统栏跟着反复 hide/show，
     * 与 Scaffold 底栏互相打架，表现为控件闪现、进得去出不来。
     */
    override fun toggleControls() = mutable.update { it.copy(controlsVisible = !it.controlsVisible) }
    override fun setImmersive(value: Boolean) = mutable.update { it.copy(isImmersive = value, controlsVisible = !value) }
    override fun showSettings(show: Boolean) = mutable.update { it.copy(showSettings = show, controlsVisible = true) }
    override fun showToc(show: Boolean) = mutable.update { it.copy(showToc = show, controlsVisible = true) }
    override fun closeOverlays() = mutable.update { it.copy(showSettings = false, showToc = false) }

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
    override fun updateFontUri(value: String?) { viewModelScope.launch { settingsRepository.setReaderFontUri(value); refreshSettings() } }

    fun startSession() {
        if (sessionStartedAt == null) {
            sessionStartedAt = android.os.SystemClock.elapsedRealtime()
            // 更新书架「上次阅读时间」：之前只有在线阅读在记录，EPUB 从不更新。
            // bookId 不在书架时 recordRead 是无操作，安全。
            if (bookId.isNotBlank()) viewModelScope.launch { app.bookshelfRepository.recordRead(bookId) }
        }
    }

    fun stopSession() {
        // 退出前兜底保存断点：正常滚动都会落盘，这里防止最后一次位置没有滚动事件可触发。
        persistProgress()
        val started = sessionStartedAt ?: return
        sessionStartedAt = null
        val seconds = ((android.os.SystemClock.elapsedRealtime() - started) / 1000L).coerceIn(0L, 1800L)
        if (seconds > 0) viewModelScope.launch { app.readingStatsRepository.recordSession(bookId, bookTitle, seconds) }
    }

    override fun onCleared() {
        stopSession()
        super.onCleared()
    }

    private fun refreshSettings() { viewModelScope.launch { mutable.update { it.copy(settings = settingsRepository.readerSettings.first()) } } }

    private fun persistProgress() {
        val current = state.value
        val book = current.book ?: return
        val chapter = book.chapters.getOrNull(current.chapterIndex) ?: return
        viewModelScope.launch {
            settingsRepository.saveProgress(ReadingProgress(bookId, chapter.id, current.chapterIndex, current.paragraphIndex, System.currentTimeMillis()))
        }
    }
}

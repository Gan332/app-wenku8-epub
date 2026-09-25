package com.wenku8.epubstudio.reader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wenku8.epubstudio.Wenku8Application
import com.wenku8.epubstudio.settings.ReaderPageTurnMode
import com.wenku8.epubstudio.settings.ReaderSettings
import com.wenku8.epubstudio.settings.ReadingProgress
import com.wenku8.epubstudio.settings.ReaderBackground
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
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
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

    fun selectChapter(index: Int) {
        val book = state.value.book ?: return
        val safe = index.coerceIn(0, book.chapters.lastIndex)
        mutable.update { it.copy(chapterIndex = safe, paragraphIndex = 0, showToc = false) }
        persistProgress()
    }

    fun nextChapter() = selectChapter(state.value.chapterIndex + 1)
    fun previousChapter() = selectChapter(state.value.chapterIndex - 1)
    fun setParagraph(index: Int) { mutable.update { it.copy(paragraphIndex = index.coerceAtLeast(0)) }; persistProgress() }
    fun toggleControls() = mutable.update { current ->
        val next = !current.isImmersive
        current.copy(isImmersive = next, controlsVisible = !next)
    }
    fun setImmersive(value: Boolean) = mutable.update { it.copy(isImmersive = value, controlsVisible = !value) }
    fun showSettings(show: Boolean) = mutable.update { it.copy(showSettings = show, isImmersive = false) }
    fun showToc(show: Boolean) = mutable.update { it.copy(showToc = show, isImmersive = false) }
    fun closeOverlays() = mutable.update { it.copy(showSettings = false, showToc = false) }

    fun updateFontSize(value: Float) { viewModelScope.launch { settingsRepository.setReaderFontSize(value); refreshSettings() } }
    fun updateFontWeight(value: Int) { viewModelScope.launch { settingsRepository.setReaderFontWeight(value); refreshSettings() } }
    fun updateLineHeight(value: Float) { viewModelScope.launch { settingsRepository.setReaderLineHeight(value); refreshSettings() } }
    fun updateSpacing(value: Int) { viewModelScope.launch { settingsRepository.setReaderParagraphSpacing(value); refreshSettings() } }
    fun updatePadding(value: Int) { viewModelScope.launch { settingsRepository.setReaderHorizontalPadding(value); refreshSettings() } }
    fun updateBackground(value: ReaderBackground) { viewModelScope.launch { settingsRepository.setReaderBackground(value); refreshSettings() } }
    fun updateBackgroundColor(value: Int) { viewModelScope.launch { settingsRepository.setReaderCustomBackground(value); refreshSettings() } }
    fun updateTextColor(value: Int) { viewModelScope.launch { settingsRepository.setReaderTextColor(value); refreshSettings() } }
    fun updatePageMode(value: ReaderPageTurnMode) { viewModelScope.launch { settingsRepository.setReaderPageTurn(value); refreshSettings() } }
    fun updateKeepScreenOn(value: Boolean) { viewModelScope.launch { settingsRepository.setReaderKeepScreenOn(value); refreshSettings() } }
    fun updateImmersive(value: Boolean) { viewModelScope.launch { settingsRepository.setReaderImmersive(value); setImmersive(value); refreshSettings() } }
    fun updateFontUri(value: String?) { viewModelScope.launch { settingsRepository.setReaderFontUri(value); refreshSettings() } }

    fun startSession() {
        if (sessionStartedAt == null) sessionStartedAt = android.os.SystemClock.elapsedRealtime()
    }

    fun stopSession() {
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

package com.wenku8.epubstudio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wenku8.epubstudio.Wenku8Application
import com.wenku8.epubstudio.model.Book
import com.wenku8.epubstudio.model.BookIndex
import com.wenku8.epubstudio.model.ExportJob
import com.wenku8.epubstudio.model.JobStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class StudioTab { CREATE, HISTORY }
enum class CreateStep { SOURCE, CHAPTERS, EXPORT, PROGRESS }

data class StudioUiState(
    val tab: StudioTab = StudioTab.CREATE,
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
)

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val manager = (application as Wenku8Application).jobManager
    private val mutable = MutableStateFlow(StudioUiState())
    val state: StateFlow<StudioUiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            manager.jobs.collect { jobs ->
                mutable.update { current ->
                    val active = current.activeJobId?.let { id -> jobs.firstOrNull { it.id == id } }
                    current.copy(jobs = jobs.values.sortedByDescending { it.createdAt }, step = if (active != null && current.step == CreateStep.PROGRESS) CreateStep.PROGRESS else current.step)
                }
            }
        }
    }

    fun setTab(tab: StudioTab) = mutable.update { it.copy(tab = tab, message = null) }
    fun setSource(value: String) = mutable.update { it.copy(sourceUrl = value, message = null) }
    fun setSearch(value: String) = mutable.update { it.copy(search = value) }
    fun setCover(value: Boolean) = mutable.update { it.copy(includeCover = value) }
    fun clearMessage() = mutable.update { it.copy(message = null) }

    fun parseSource() {
        val value = state.value.sourceUrl.trim()
        if (value.isEmpty()) { mutable.update { it.copy(message = "请输入书籍或目录网址。") }; return }
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, message = null) }
            runCatching { manager.parseSource(value) }
                .onSuccess { (book, index) -> mutable.update { it.copy(busy = false, book = book, index = index, selectedIds = index.chapters.map { chapter -> chapter.id }.toSet(), step = CreateStep.CHAPTERS) } }
                .onFailure { error -> mutable.update { it.copy(busy = false, message = error.message ?: "解析失败。") } }
        }
    }

    fun selectAll() = mutable.update { current -> current.copy(selectedIds = current.index?.chapters?.map { it.id }?.toSet() ?: emptySet()) }
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

package com.example.hyperreader.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.hyperreader.Wenku8Application
import com.example.hyperreader.data.appDataStore
import com.example.hyperreader.data.ReadingStatsRepository
import com.example.hyperreader.ui.AppMiuixTheme
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentComponentRepository
import indi.dmzz_yyhyy.lightnovelreader.data.plugin.injector.PluginInjectorProvider
import indi.dmzz_yyhyy.lightnovelreader.ui.LnrAppTheme
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ReaderScreen
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.ReaderViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * LNR 阅读器宿主（P4）。
 *
 * 入口契约与旧 [ReaderActivity] 一致（`EXTRA_URI` / `EXTRA_BOOK_ID`），切换只需把
 * 启动方指向本 Activity；旧壳暂留作为已知可用的对照路径（R8 / 首帧 / 手势类问题
 * 只能在真机暴露），真机冒烟通过后删除。
 */
class LnrReaderActivity : ComponentActivity() {

    private val viewModel: LnrReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = Uri.parse(intent.getStringExtra(EXTRA_URI).orEmpty())
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
        if (uri.scheme.isNullOrBlank()) {
            finish()
            return
        }
        viewModel.load(uri, bookId)

        setContent {
            AppMiuixTheme {
                // LNR 壳依赖 LocalAppTheme（MiuiX 投影）+ LocalSnackbarHost，
                // LnrAppTheme 两者都注入；MiuiX 本体主题仍由 AppMiuixTheme 提供。
                val uiState by viewModel.readerUiState.collectAsStateWithLifecycle()
                LnrAppTheme {
                    ReaderScreen(
                        readingScreenUiState = uiState,
                        settingState = viewModel.reader.settingState,
                        onClickBackButton = { finish() },
                        accumulateReadTime = { id, seconds -> viewModel.reader.accumulateReadingTime(id, seconds) },
                        updateTotalReadingTime = { id, seconds -> viewModel.reader.updateTotalReadingTime(id, seconds) },
                        onClickPrevChapter = { viewModel.reader.prevChapter() },
                        onClickNextChapter = { viewModel.reader.nextChapter() },
                        onChangeChapter = { chapterId -> viewModel.reader.changeChapter(chapterId) },
                        // 阅读器样式在独立二级页（本工程无该页）；返回即退出阅读器。
                        onClickReaderStyleSettings = { finish() },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.startSession()
    }

    override fun onStop() {
        viewModel.stopSession()
        super.onStop()
    }

    companion object {
        const val EXTRA_URI = "epub_uri"
        const val EXTRA_BOOK_ID = "book_id"

        /** 从任意 Activity 打开 LNR 阅读器（集中入口，避免调用方散拼 Intent）。 */
        fun intent(context: android.content.Context, uri: String, bookId: String): Intent =
            Intent(context, LnrReaderActivity::class.java)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_BOOK_ID, bookId)
    }
}

/**
 * LNR 阅读器的 VM 宿主：装配 4 个数据桥 + 组件注册表，
 * 并把加载、会话统计、既有断点接到既有设施（AGENTS 4.3 口径不变）。
 *
 * 装配关系：
 * ```
 * EpubReaderRepository.open(uri) → ReaderBook（native 结构解析 + Jsoup 块解析）
 *        └─ LnrBookRepository（卷结构 / 章节组件 JSON / UserReadingData 断点）
 *               └─ ContentComponentRepository（paragraph + image 渲染器注册）
 * LnrUserDataRepository(DataStoreUserDataDao(appDataStore)) → LNR 的 8 类 UserData
 * LnrStatsRepository → ReadingStatsRepository（会话时长）
 * ```
 */
class LnrReaderViewModel(application: android.app.Application) : AndroidViewModel(application) {

    private val app = application as Wenku8Application
    private val epubRepository = EpubReaderRepository(application)
    private val readingStats: ReadingStatsRepository get() = app.readingStatsRepository

    private var currentBook: ReaderBook? = null

    private val userDataRepository =
        LnrUserDataRepository(DataStoreUserDataDao(application.appDataStore))

    private val bookRepository = LnrBookRepository(application) { currentBook }

    private val contentComponentRepository = ContentComponentRepository(PluginInjectorProvider()).apply {
        initRegister()
    }

    val reader = ReaderViewModel(
        statsRepository = LnrStatsRepository(application),
        bookRepository = bookRepository,
        userDataRepository = userDataRepository,
        contentComponentRepository = contentComponentRepository,
    )

    /**
     * LNR 的 [ReaderViewModel.uiState] 是可变对象（非 StateFlow），这里包一层 StateFlow
     * 供 Compose 收集；`bookId` 赋值与章节切换都会重发。
     */
    private val _readerUiState = MutableStateFlow(reader.uiState)
    val readerUiState = _readerUiState

    private var sessionStartedAt: Long? = null

    fun load(uri: Uri, bookId: String) {
        viewModelScope.launch {
            val book = runCatching { epubRepository.open(bookId, uri) }.getOrElse {
                _readerUiState.value = reader.uiState
                return@launch
            }
            currentBook = book

            // 既有 DataStore 断点 → LNR 进度：LNR 读的是 lastReadChapterId，
            // 这里从本工程的 ReadingProgress 换算并写入（幂等）。
            val progress = runCatching { app.settingsRepository.progress(bookId).first() }.getOrNull()
            val restoredChapterId = progress
                ?.chapterIndex
                ?.let { book.chapters.getOrNull(it) }
                ?.id

            reader.bookId = bookId
            // 先写进度再切换章节：LNR 的 changeChapter 只投递请求，首帧不会回读进度。
            runCatching {
                bookRepository.updateUserReadingData(bookId) { data ->
                    data.copy(
                        lastReadChapterId = restoredChapterId,
                        lastReadTime = java.time.LocalDateTime.now(),
                    )
                }
            }

            val targetChapterId = restoredChapterId
                ?: book.chapters.firstOrNull()?.id.orEmpty()
            if (targetChapterId.isNotEmpty()) reader.changeChapter(targetChapterId)

            _readerUiState.value = reader.uiState
        }
    }

    fun startSession() {
        if (sessionStartedAt == null) sessionStartedAt = android.os.SystemClock.elapsedRealtime()
    }

    /** 会话收尾：统计时长（AGENTS 4.3：单次 ≤30 分钟），并把 LNR 侧进度落回既有 DataStore。 */
    fun stopSession() {
        val started = sessionStartedAt ?: return
        sessionStartedAt = null
        val seconds = ((android.os.SystemClock.elapsedRealtime() - started) / 1000L).coerceIn(0L, 1800L)
        val bookId = reader.bookId
        val title = currentBook?.title.orEmpty()
        viewModelScope.launch {
            if (seconds > 0) readingStats.recordSession(bookId, title, seconds)
            persistProgressToStore(bookId)
        }
    }

    /** 把 LNR 的 UserReadingData（章 id + 章内进度）换算回本工程的 ReadingProgress。 */
    private suspend fun persistProgressToStore(bookId: String) {
        if (bookId.isBlank()) return
        val book = currentBook ?: return
        runCatching {
            val data = bookRepository.getUserReadingData(bookId)
            val chapterId = data.lastReadChapterId ?: return@runCatching
            val index = book.chapters.indexOfFirst { it.id == chapterId }
            if (index < 0) return@runCatching
            // LNR 用「章内阅读位置 hash + 章节内比例」，本工程用段落序号：
            // 以比例换算为段落号（每章段落数已知），保持既有进度语义。
            val paragraphCount = book.chapters[index].blocks.count { it is ReaderBlock.Paragraph }
            val paragraph = ((data.readingProgress.coerceIn(0f, 1f)) * paragraphCount).toInt()
            app.settingsRepository.saveProgress(
                com.example.hyperreader.settings.ReadingProgress(
                    bookId = bookId,
                    chapterId = chapterId,
                    chapterIndex = index,
                    paragraphIndex = paragraph,
                ),
            )
            app.bookshelfRepository.recordRead(bookId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSession()
    }
}
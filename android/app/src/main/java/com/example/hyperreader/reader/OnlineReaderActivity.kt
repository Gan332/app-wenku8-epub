package com.example.hyperreader.reader

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.hyperreader.Wenku8Application
import com.example.hyperreader.ui.AppMiuixTheme
import com.xyreader.core.BookEntity
import com.xyreader.core.BookFormat
import com.xyreader.core.ReaderGraph
import com.xyreader.reader.ReaderScreen
import com.xyreader.ui.ReaderConfigScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * wenku8 在线阅读宿主。
 *
 * 0.14.0 的 step 3/3：目录先在 IO 协程预取，然后安装在线专用 [OnlineReaderRepository]，
 * 把 [OnlinePageSourceOpener] 注入 xy-reader 的 [ReaderScreen]。在线与本地 EPUB 因此共用
 * 同一套位图排版、手势、目录、书签与进度链路；网络仍全部经共享限流客户端。
 */
class OnlineReaderActivity : ComponentActivity() {

    private var sessionStartedAt: Long? = null
    private var hostBookId: String = ""
    private var bookTitle: String = ""
    private var bookshelfId: String = ""

    /** Cloudflare 验证窗口（0.18.0）：通过后重试目录加载，取消则关闭阅读器。 */
    private val challengeLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) loadIndexAndOpen() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hostBookId = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty().filter(Char::isDigit)
        bookTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        bookshelfId = intent.getStringExtra(EXTRA_BOOKSHELF_ID)
            ?.takeIf(String::isNotBlank)
            ?: "wenku8:$hostBookId"
        if (hostBookId.isBlank()) {
            finish()
            return
        }
        lifecycleScope.launch {
            (application as Wenku8Application).bookshelfRepository.recordRead(bookshelfId)
        }

        loadIndexAndOpen()
    }

    /**
     * 拉目录并打开阅读器（0.18.0：验证通过后需可重入）。
     *
     * Cloudflare 拦截（`UPSTREAM_CHALLENGE`）时不直接报错，而是弹验证窗口让用户完成官方交互，
     * 通过后自动重试——目录页是公开页，验证一次即可恢复。
     */
    private fun loadIndexAndOpen() {
        lifecycleScope.launch {
            val app = application as Wenku8Application
            val result = withContext(Dispatchers.IO) {
                app.onlineReaderSource.loadIndex(
                    hostBookId,
                    title = bookTitle,
                    author = intent.getStringExtra(EXTRA_AUTHOR).orEmpty(),
                )
            }
            when (result) {
                is OnlineReaderResult.Ready -> {
                    val index = result.value
                    bookTitle = index.book.title
                    val readerBookId = xyBookIdOf(hostBookId)
                    val book = BookEntity(
                        id = readerBookId,
                        title = bookTitle.ifBlank { "未命名轻小说" },
                        uri = "wenku8://" + index.catalog.bookId,
                        format = BookFormat.UNKNOWN.name,
                    )
                    val repository = OnlineReaderRepository(
                        context = this@OnlineReaderActivity,
                        book = book,
                        hostBookId = hostBookId,
                        bookshelfId = bookshelfId,
                    )
                    if (!repository.hasStoredReaderPrefs()) {
                        runCatching {
                            repository.importLegacySettings(app.settingsRepository.readerSettings.first())
                        }
                    }
                    val opener = OnlinePageSourceOpener(app.onlineReaderSource, app, hostBookId)
                    opener.prepare(index.catalog.chapters)
                    val stored = repository.currentProgress()
                    // 起始章与起始页一并决定：显式指定章时页码归零（页进度与页轴配对，AGENTS §4.8）
                    val start = StartPosition.resolve(
                        requestedChapterId = intent.getStringExtra(EXTRA_START_CHAPTER)?.takeIf { it.isNotBlank() },
                        catalog = index.catalog.chapters,
                        rememberedChapterId = repository.resolveStartChapterId(index.catalog.chapters),
                        rememberedPage = stored?.page,
                    )
                    val initialPage = start.page
                    opener.setStartChapterId(start.chapterId)
                    ReaderGraph.install(repository)
                    setContent {
                        AppMiuixTheme {
                            var showReaderConfig by rememberSaveable { mutableStateOf(false) }
                            if (showReaderConfig) {
                                ReaderConfigScreen(
                                    repository = repository,
                                    onBack = { showReaderConfig = false },
                                )
                            } else {
                                ReaderScreen(
                                    bookId = readerBookId,
                                    onBack = { finish() },
                                    initialPage = initialPage,
                                    sourceOpener = { context, readerBook, style ->
                                        opener.open(context, readerBook, style).also(repository::attach)
                                    },
                                    onOpenReaderConfig = { showReaderConfig = true },
                                )
                            }
                        }
                    }
                }
                is OnlineReaderResult.NeedsLogin -> showOpenError(result.message)
                is OnlineReaderResult.Failed ->
                    if (result.code == "UPSTREAM_CHALLENGE") {
                        // 不自动弹窗：403 是常态策略而非可点击的挑战，自动打断没有意义；
                        // 只给错误页加一个「打开验证页」按钮，由用户自己决定何时验证。
                        showOpenError(result.message, canVerify = true)
                    } else {
                        showOpenError(result.message)
                    }
            }
        }
    }

    private fun showOpenError(message: String, canVerify: Boolean = false) {
        setContent {
            AppMiuixTheme {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.padding(UiDimens.spaceXL),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.body1,
                        )
                        if (canVerify) {
                            Button(
                                onClick = {
                                    challengeLauncher.launch(
                                        com.example.hyperreader.auth.CfChallengeActivity.intent(
                                            this@OnlineReaderActivity,
                                            com.example.hyperreader.core.Wenku8Urls.index(hostBookId, null),
                                        ),
                                    )
                                },
                                modifier = Modifier.heightIn(min = UiDimens.touchMin),
                            ) { Text("打开验证页（浏览器）") }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (sessionStartedAt == null) sessionStartedAt = SystemClock.elapsedRealtime()
    }

    override fun onStop() {
        val started = sessionStartedAt
        sessionStartedAt = null
        if (started != null && hostBookId.isNotBlank()) {
            val seconds = ((SystemClock.elapsedRealtime() - started) / 1000L).coerceIn(0L, 1800L)
            if (seconds > 0) {
                val stats = (application as Wenku8Application).readingStatsRepository
                lifecycleScope.launch {
                    runCatching { stats.recordSession(hostBookId, bookTitle, seconds) }
                }
            }
        }
        super.onStop()
    }

    companion object {
        const val EXTRA_BOOK_ID = "online_book_id"
        const val EXTRA_TITLE = "online_title"
        const val EXTRA_AUTHOR = "online_author"
        const val EXTRA_BOOKSHELF_ID = "online_bookshelf_id"
        /** 详情页目录里点某一章时指定的起始章 id（0.18.0）；空则按已存进度续读。 */
        const val EXTRA_START_CHAPTER = "online_start_chapter"
    }
}

/** 供书架侧复用的启动片段：避免各处重复拼 Intent。 */
fun onlineReaderIntent(
    context: android.content.Context,
    bookId: String,
    title: String,
    author: String,
    bookshelfId: String = "wenku8:$bookId",
    /** 指定起始章（详情页目录点击）；给定后从该章第一页开始，旧页进度不再适用。 */
    startChapterId: String? = null,
): Intent = Intent(context, OnlineReaderActivity::class.java)
    .putExtra(OnlineReaderActivity.EXTRA_BOOK_ID, bookId)
    .putExtra(OnlineReaderActivity.EXTRA_TITLE, title)
    .putExtra(OnlineReaderActivity.EXTRA_AUTHOR, author)
    .putExtra(OnlineReaderActivity.EXTRA_BOOKSHELF_ID, bookshelfId)
    .putExtra(OnlineReaderActivity.EXTRA_START_CHAPTER, startChapterId)

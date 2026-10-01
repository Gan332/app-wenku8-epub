package com.example.hyperreader.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.example.hyperreader.Wenku8Application
import com.example.hyperreader.data.appDataStore
import com.example.hyperreader.ui.AppMiuixTheme
import com.xyreader.core.BookEntity
import com.xyreader.core.BookFormat
import com.xyreader.core.ReaderGraph
import com.xyreader.reader.ReaderScreen
import com.xyreader.ui.ReaderConfigScreen
import kotlinx.coroutines.launch

/**
 * xy-reader 阅读器宿主（0.13.0）。
 *
 * 入口契约与旧壳（`ReaderActivity` / `LnrReaderActivity`）保持一致：
 * `EXTRA_URI` + `EXTRA_BOOK_ID`，因此切换只需改启动方，调用方不必散拼 Intent。
 *
 * 装配关系：
 * ```
 * intent(uri, bookId) ─→ BookEntity(id = bookId 的稳定哈希, format = EPUB)
 *      ├─ XyReaderRepository  → ReaderGraph.install(...)   // 设置/书签/进度/收藏 → 既有 DataStore
 *      ├─ XyEpubPageSourceOpener                            // 复用本工程 EPUB 解析管线
 *      └─ ReaderScreen(bookId, initialPage = 上次页进度)
 * ```
 *
 * 阅读统计口径不变（AGENTS §4.3）：只在 `onStart` → `onStop` 之间累计，单次上限 30 分钟。
 */
class XyReaderActivity : ComponentActivity() {

    private var sessionStartedAt: Long? = null
    private var hostBookId: String = ""
    private var bookTitle: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.getStringExtra(EXTRA_URI).orEmpty()
        hostBookId = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
        bookTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        // 没有可打开的 EPUB 或没有书标识（进度/书签无从归属）直接退出，不留半残界面
        if (uri.isBlank() || hostBookId.isBlank()) {
            finish()
            return
        }

        val readerBookId = xyBookIdOf(hostBookId)
        val book = BookEntity(
            id = readerBookId,
            title = bookTitle.ifBlank { "未命名" },
            uri = uri,
            format = BookFormat.EPUB.name,
        )

        // 阅读器内部通过 ReaderGraph 取仓库，必须在 setContent 之前安装
        val repository = XyReaderRepository(this, book, hostBookId)
        ReaderGraph.install(repository)
        val opener = XyEpubPageSourceOpener(hostBookId, Uri.parse(uri))

        // 页进度存在 DataStore，读出来才能定位续读页；读完再挂内容，避免首帧跳页
        lifecycleScope.launch {
            val initialPage = readXyPageProgress(applicationContext.appDataStore, hostBookId)
            setContent {
                AppMiuixTheme {
                    // 完整阅读配置管理页（屏幕方向 / 点击翻页 / 字体导入等只在该页可改），
                    // 与阅读界面在同一 Activity 内切换；ReaderViewModel 挂在 Activity 的
                    // ViewModelStore 上，来回切换不会丢阅读位置。
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
                            sourceOpener = opener::open,
                            onOpenReaderConfig = { showReaderConfig = true },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (sessionStartedAt == null) sessionStartedAt = SystemClock.elapsedRealtime()
    }

    /** 会话收尾：统计时长（单次 ≤30 分钟，AGENTS §4.3），进度由阅读器自身防抖落库。 */
    override fun onStop() {
        val started = sessionStartedAt
        sessionStartedAt = null
        if (started != null && hostBookId.isNotBlank()) {
            val seconds = ((SystemClock.elapsedRealtime() - started) / 1000L).coerceIn(0L, MAX_SESSION_SECONDS)
            if (seconds > 0) {
                val stats = (application as Wenku8Application).readingStatsRepository
                lifecycleScope.launch { runCatching { stats.recordSession(hostBookId, bookTitle, seconds) } }
            }
        }
        super.onStop()
    }

    companion object {
        const val EXTRA_URI = "epub_uri"
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_TITLE = "book_title"

        /** 单次阅读计时上限（秒）：与 AGENTS §4.3 的 30 分钟口径一致。 */
        private const val MAX_SESSION_SECONDS = 1800L

        /** 从任意 Activity 打开阅读器（集中入口，避免调用方散拼 Intent）。 */
        fun intent(context: Context, uri: String, bookId: String, title: String = ""): Intent =
            Intent(context, XyReaderActivity::class.java)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_TITLE, title)
    }
}

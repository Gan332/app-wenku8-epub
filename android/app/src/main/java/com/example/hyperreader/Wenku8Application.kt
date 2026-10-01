package com.example.hyperreader

import android.app.Application
import com.example.hyperreader.core.CatalogSearchField
import com.example.hyperreader.core.Wenku8BookSource
import com.example.hyperreader.core.Wenku8SessionStore
import com.example.hyperreader.core.Wenku8SearchProvider
import com.example.hyperreader.service.ExportJobManager
import com.example.hyperreader.data.BookshelfRepository
import com.example.hyperreader.data.ReadingStatsRepository
import com.example.hyperreader.core.ExploreRepository
import com.example.hyperreader.core.Wenku8DataSource
import com.example.hyperreader.settings.SettingsRepository
import com.example.hyperreader.ui.toSearchBook
import java.io.File

class Wenku8Application : Application() {
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val sessionStore: Wenku8SessionStore by lazy { Wenku8SessionStore(this) }
    val jobManager: ExportJobManager by lazy { ExportJobManager(this, sessionStore) }
    val searchProvider: Wenku8SearchProvider by lazy { Wenku8SearchProvider(jobManager.httpClient(), sessionStore) }
    val bookshelfRepository: BookshelfRepository by lazy { BookshelfRepository(this) }
    val readingStatsRepository: ReadingStatsRepository by lazy { ReadingStatsRepository(this) }
    val exploreRepository: ExploreRepository by lazy { ExploreRepository(Wenku8DataSource(jobManager.httpClient(), sessionStore)) }

    /**
     * wenku8 书源（LNR 书源体系的本工程落地）。
     *
     * 取数走 [jobManager] 的共享限流客户端与 [sessionStore] 的会话 Cookie；
     * 搜索回调接到本地书目索引上——**不打源站的 `search.php`**（该接口由站点控制登录）。
     */
    val bookSource: Wenku8BookSource by lazy {
        Wenku8BookSource(
            http = jobManager.httpClient(),
            session = sessionStore,
            catalogSearch = { field, keyword ->
                catalogRepository.search(
                    keyword,
                    if (field == SEARCH_FIELD_AUTHOR) CatalogSearchField.AUTHOR else CatalogSearchField.TITLE,
                ).map { it.toSearchBook() }
            },
        )
    }
    val catalogRepository: com.example.hyperreader.core.CatalogRepository by lazy { com.example.hyperreader.core.CatalogRepository(this) }

    /**
     * 探索页书籍详情：**只走公开 API**，与创建/导出的解析管线（[jobManager]）完全分开。
     * 见 `ExploreDetailRepository` 的类注释。
     */
    val exploreDetailRepository: com.example.hyperreader.core.ExploreDetailRepository by lazy { com.example.hyperreader.core.ExploreDetailRepository(this) }
    val coverRepository: com.example.hyperreader.ui.cover.CoverRepository by lazy { com.example.hyperreader.ui.cover.CoverRepository(this) }
    val onlineReaderSource: com.example.hyperreader.reader.OnlineReaderSource by lazy { com.example.hyperreader.reader.OnlineReaderSource(File(filesDir, "online-cache"), jobManager.httpClient()) }
}

/**
 * 书源搜索的字段标识（`SearchType.type`），与 `Wenku8BookSource` 的 `searchTypes` 对齐。
 * 只区分「按作者」与「按书名」，其余一律按书名走。
 */
private const val SEARCH_FIELD_AUTHOR = "author"

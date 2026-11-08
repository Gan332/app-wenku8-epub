package com.example.hyperreader

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.hyperreader.core.BookshelfUpdateChecker
import com.example.hyperreader.core.CatalogSearchField
import com.example.hyperreader.core.Wenku8BookSource
import com.example.hyperreader.core.Wenku8Endpoint
import com.example.hyperreader.core.Wenku8SessionStore
import com.example.hyperreader.core.Wenku8SearchProvider
import com.example.hyperreader.reader.LnrOnlineReadingSource
import com.example.hyperreader.reader.OnlineBookCacheManager
import com.example.hyperreader.reader.OnlineChapterCache
import com.example.hyperreader.reader.OnlineIndexStore
import com.example.hyperreader.reader.OnlineReaderSource
import com.example.hyperreader.reader.Wenku8TextFetcher
import com.example.hyperreader.service.ExportJobManager
import com.example.hyperreader.data.BookshelfRepository
import com.example.hyperreader.data.ReadingStatsRepository
import com.example.hyperreader.core.ExploreRepository
import com.example.hyperreader.core.Wenku8DataSource
import com.example.hyperreader.settings.SettingsRepository
import com.example.hyperreader.ui.toSearchBook
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class Wenku8Application : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 把设置里的中继开关、端点与首选直连入口应用到 [Wenku8Endpoint]。
     *
     * 公开页抓取（书目索引、公开榜单、封面）会经它改写端点；会话链路不受影响。
     * 端点非法时 [Wenku8Endpoint.applyRelay] 直接清空，等价于关闭。
     */
    override fun onCreate() {
        super.onCreate()
        // 每次启动从默认入口开始，避免上一次会话的轮换结果影响本次
        Wenku8Endpoint.resetHosts()
        appScope.launch {
            combine(
                settingsRepository.relayBase,
                settingsRepository.relayEnabled,
                settingsRepository.directEntry,
            ) { base, enabled, entry -> Triple(base, enabled, entry.base) }
                .collect { (base, enabled, preferred) ->
                    Wenku8Endpoint.applyRelay(base)
                    Wenku8Endpoint.setRelayEnabled(enabled)
                    Wenku8Endpoint.applyPreferredHost(preferred)
                }
        }
    }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val sessionStore: Wenku8SessionStore by lazy { Wenku8SessionStore(this) }
    val jobManager: ExportJobManager by lazy { ExportJobManager(this, sessionStore) }
    val searchProvider: Wenku8SearchProvider by lazy { Wenku8SearchProvider(jobManager.httpClient(), sessionStore) }
    val bookshelfRepository: BookshelfRepository by lazy { BookshelfRepository(this) }
    /** 跨书书签中心（0.19.0）；只读两个阅读器 Bridge 共用的 `xy_reader_bookmarks` 键。 */
    val bookmarkCenterRepository: com.example.hyperreader.reader.BookmarkCenterRepository by lazy {
        com.example.hyperreader.reader.BookmarkCenterRepository(this)
    }
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

    /**
     * 在线阅读正文缓存：内置解析与 LNR 书源**共用**一份。
     * 两条链路都基于同一份 `parseChapter` 结果装配 `ReaderBlock`，块结构一致，
     * 共用后「缓存全书」下载的内容不因切换数据源而失效（离线阅读才有意义）。
     */
    val onlineChapterCache: OnlineChapterCache by lazy { OnlineChapterCache(File(filesDir, "online-cache")) }

    /** 目录快照：断网时仍能打开阅读器并展示全书目录。 */
    val onlineIndexStore: OnlineIndexStore by lazy { OnlineIndexStore(File(filesDir, "online-index")) }

    val onlineReaderSource: OnlineReaderSource by lazy {
        OnlineReaderSource(onlineChapterCache, Wenku8TextFetcher(jobManager.httpClient()), onlineIndexStore)
    }

    /** 实际联网的在线阅读源（忽略离线模式）。缓存任务与更新检查必须用它。 */
    suspend fun networkOnlineReadingSource(): com.example.hyperreader.reader.OnlineReadingSource =
        if (settingsRepository.onlineSource.first() == com.example.hyperreader.settings.OnlineSource.LNR) {
            lnrOnlineReaderSource
        } else {
            onlineReaderSource
        }

    /**
     * 阅读器使用的在线阅读源。
     *
     * 离线模式开启时返回只读缓存的包装，**连目录都不会先打源站**，
     * 保证用户主动选择的「零流量」真的成立。
     */
    suspend fun currentOnlineReadingSource(): com.example.hyperreader.reader.OnlineReadingSource =
        if (settingsRepository.offlineMode.first()) {
            com.example.hyperreader.reader.OfflineOnlineReadingSource(onlineChapterCache, onlineIndexStore)
        } else {
            networkOnlineReadingSource()
        }

    /** 书架更新检查：抓公开目录，受全局限流约束。 */
    val bookshelfUpdateChecker: BookshelfUpdateChecker by lazy {
        BookshelfUpdateChecker(bookshelfRepository, { networkOnlineReadingSource() })
    }

    /**
     * 当前网络是否计费（移动数据 / 热点）。用于「仅 WiFi 缓存全书」。
     * 无法判定时按计费处理：宁可少下载，也不要在用户流量上偷偷跑几百章。
     */
    fun isMeteredNetwork(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return true
        val caps = manager.getNetworkCapabilities(network) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** LNR 书源版在线阅读；正文与目录缓存与内置源共用。 */
    val lnrOnlineReaderSource: LnrOnlineReadingSource by lazy {
        LnrOnlineReadingSource(
            bookSource = bookSource,
            cache = onlineChapterCache,
            indexStore = onlineIndexStore,
        )
    }

    /**
     * 「缓存全书」任务：按当前设置的在线阅读数据源逐章下载。
     *
     * 数据源在任务开始时解析一次；阅读器打开时也按同一设置取源，
     * 因此缓存下来的正文在下次打开时直接命中。
     */
    val onlineBookCacheManager: OnlineBookCacheManager by lazy {
        OnlineBookCacheManager(
            sourceProvider = { networkOnlineReadingSource() },
            cache = onlineChapterCache,
            indexStore = onlineIndexStore,
            canDownload = {
                if (!settingsRepository.cacheOnWifiOnly.first()) {
                    true
                } else {
                    !isMeteredNetwork()
                }
            },
        )
    }
}

/**
 * 书源搜索的字段标识（`SearchType.type`），与 `Wenku8BookSource` 的 `searchTypes` 对齐。
 * 只区分「按作者」与「按书名」，其余一律按书名走。
 */
private const val SEARCH_FIELD_AUTHOR = "author"

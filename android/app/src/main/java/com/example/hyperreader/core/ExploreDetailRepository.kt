package com.example.hyperreader.core

import com.example.hyperreader.model.Book
import com.example.hyperreader.model.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 探索页书籍详情：**只走公开 API，不经过书目解析管线**。
 *
 * 与「创建/导出流程」的分工：
 *
 * | | 探索详情（本类） | 创建导出流程 |
 * | --- | --- | --- |
 * | 入口 | 探索页点书 | 粘贴 URL / 书架在线阅读 → 导出 |
 * | 数据 | `articleinfo.php` + `novel/{cat}/{id}/index.htm` | 同上，但走 `ExportJobManager.parseSource` |
 * | 节奏 | 交互档（允许短时突刺） | 批量档（1 秒/请求） |
 * | 产物 | 页面展示用的 [ExploreBookDetail] | `Book` + `BookIndex` 供选章导出 |
 *
 * 刻意**不复用** `ExportJobManager`：
 * `parseSource` 承担校验、章节装配与导出前置检查（`Wenku8Url.validateBook` /
 * `validateChapters`），对「只想看一眼详情」是多余的路径，
 * 而且它走批量节流，点一下要等满 1 秒。这里只做两次只读请求 + 解析。
 *
 * 安全边界与全站一致：仅 wenku8 公开页、复用 `Wenku8HttpClient`（白名单 + 内网拦截 +
 * 429 退避），不携带登录 Cookie，不触碰受登录控制的端点。
 */
class ExploreDetailRepository(context: android.content.Context) {
    private val http = Wenku8HttpClient(File(context.cacheDir, "wenku8-detail"))
    private val lock = Mutex()

    /**
     * 内存缓存：同一次会话里重复打开同一本书 **0 请求**。
     * 上限 [MAX_CACHED] 条，超出按插入顺序淘汰最旧的（详情页数据小，无需真 LRU）。
     */
    private val cache = LinkedHashMap<String, ExploreBookDetail>()

    /**
     * 拉取一本书的详情。
     *
     * 两次请求都用交互档节流，串行发出（源站不接受并发节奏）：
     * 1. `articleinfo.php?id=` → 标题/作者/封面/简介/标签/字数/状态
     * 2. `novel/{cat}/{id}/index.htm` → 章节目录（用于章节数与选章）
     *
     * 目录页失败**不致命**：详情页仍可展示书籍信息，
     * 只是章节数未知（[ExploreBookDetail.chapters] 为空 + [ExploreBookDetail.indexError] 有值）。
     */
    suspend fun load(bookId: String, fallback: ExploreBookSeed? = null): ExploreBookDetail = withContext(Dispatchers.IO) {
        val id = bookId.filter(Char::isDigit)
        require(id.isNotBlank()) { "书籍 ID 无效。" }

        lock.withLock { cache[id] }?.let { return@withContext it }

        val info = http.fetchTextInteractive(Wenku8Urls.articleInfo(id), "detail-info-$id", Wenku8Urls.BASE)
        val entry = Wenku8Parser.parseCatalogEntry(info.html, info.finalUrl)
        val book = entry.toBook(fallback)

        // 目录页：拿章节列表。优先用详情页解析出的真实目录地址，缺失才按分类号兜底。
        // 失败时降级为「只有书籍信息」，不阻断详情展示。
        val directory = book.directoryUrl?.takeIf { it.isNotBlank() } ?: Wenku8Urls.index(id, fallback?.category)
        val index = runCatching {
            http.fetchTextInteractive(directory, "detail-index-$id", Wenku8Urls.BASE)
        }.mapCatching { page ->
            Wenku8Parser.parseIndex(page.html, page.finalUrl, id)
        }

        val detail = ExploreBookDetail(
            book = book,
            chapters = index.getOrNull()?.chapters.orEmpty(),
            indexError = index.exceptionOrNull()?.message,
            cachedAt = System.currentTimeMillis(),
        )
        lock.withLock {
            cache[id] = detail
            while (cache.size > MAX_CACHED) {
                val oldest = cache.keys.firstOrNull() ?: break
                cache.remove(oldest)
            }
        }
        detail
    }

    /** 清掉缓存：用户在详情页点「刷新」时用。 */
    suspend fun invalidate(bookId: String) {
        lock.withLock { cache.remove(bookId.filter(Char::isDigit)) }
    }

    private companion object {
        const val MAX_CACHED = 32
    }
}

/**
 * 探索页点书时手头已有的信息（来自榜单/本地索引）。
 * 详情接口拿到数据后覆盖它，接口失败时回退显示这些字段。
 */
data class ExploreBookSeed(
    val id: String,
    val title: String = "",
    val author: String = "",
    val category: String = "",
    val status: String = "",
    val updatedAt: String = "",
    val wordCount: Long? = null,
    val coverUrl: String? = null,
    val sourceUrl: String = "",
)

/** 探索详情页的完整数据。 */
data class ExploreBookDetail(
    val book: Book,
    val chapters: List<Chapter> = emptyList(),
    /** 目录页单独失败时的原因；书籍信息仍可用。 */
    val indexError: String? = null,
    val cachedAt: Long = 0L,
) {
    val chapterCount: Int get() = chapters.size
}

/** [CatalogEntry] → 页面用 [Book]；接口缺字段时用 [fallback] 兜底。 */
internal fun CatalogEntry.toBook(fallback: ExploreBookSeed? = null): Book = Book(
    id = id,
    title = title.ifBlank { fallback?.title.orEmpty() },
    author = author.ifBlank { fallback?.author.orEmpty() }.ifBlank { "未知作者" },
    category = category.ifBlank { fallback?.category.orEmpty() }.ifBlank { "轻小说" },
    status = status.ifBlank { fallback?.status.orEmpty() },
    updatedAt = updatedAt.ifBlank { fallback?.updatedAt.orEmpty() },
    wordCount = wordCount ?: fallback?.wordCount,
    coverUrl = coverUrl ?: fallback?.coverUrl,
    summary = summary,
    tags = tags,
    sourceUrl = sourceUrl.ifBlank { fallback?.sourceUrl.orEmpty() },
)

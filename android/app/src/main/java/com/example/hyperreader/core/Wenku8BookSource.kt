package com.example.hyperreader.core

import android.net.Uri
import androidx.core.net.toUri
import com.example.hyperreader.model.Chapter
import com.example.hyperreader.model.ContentBlock
import com.example.hyperreader.model.SearchBook
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.content.builder.buildContent
import io.nightfish.lightnovelreader.api.content.builder.image
import io.nightfish.lightnovelreader.api.content.builder.paragraph
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.explore.ExploreBooksRow
import io.nightfish.lightnovelreader.api.explore.ExploreDisplayBook
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.util.Cache
import io.nightfish.lightnovelreader.api.util.local
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebBookDataSourceManagerApi
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.explore.AbstractDefaultExplorePageProvider
import io.nightfish.lightnovelreader.api.web.explore.ExplorePageProvider
import io.nightfish.lightnovelreader.api.web.explore.ExploreTapPageDataSource
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import io.nightfish.lightnovelreader.api.web.search.SearchType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * wenku8 书源：实现 LightNovelReader 的 [WebBookDataSource] 契约，但**整条链路跑在
 * 本工程的合规基础设施上**。
 *
 * == 为什么不是照搬上游实现 ==
 * 上游的 `defaultplugin/wenku8`（见 `THIRD_PARTY_NOTICES.md`）有三处不能沿用：
 * 1. `Wenku8Api` 硬编码了上游作者的 wenku8 账号 Cookie（含口令哈希）并随每个请求发送；
 * 2. 其 HTTP 栈是 Ktor + 自建信号量，会绕过本项目的全局限流与 429 退避；
 * 3. 它抓取 `search.php` / `articlelist.php` / `toplist.php` / `tags.php`，
 *    这些接口由站点控制登录，按 AGENTS §4.2 保持原样、不得规避。
 *
 * 因此这里只借用上游的**体系**（书源契约、探索页/搜索提供者模型），取数一律走：
 * - [Wenku8HttpClient]：全局 1 秒限流 + 429 `Retry-After` 退避 + 重定向复校验；
 * - [Wenku8SessionStore]：用户**自己**登录后的会话 Cookie（Keystore 加密），无会话时按匿名取公开页；
 * - 端点只用 AGENTS §4.2 的匿名白名单：`articleinfo.php`、`/novel/{cat}/{id}/index.htm`、
 *   章节页、`/zt/sugoi/{year}.php`、`/zt/booklist/{yyyyMM}.php`。
 */
class Wenku8BookSource(
    private val http: Wenku8HttpClient,
    private val session: Wenku8SessionStore,
    /**
     * 本地书目索引查询（字段, 关键词）→ 命中书目。由宿主注入 `CatalogIndex`，
     * 避免书源反向依赖上层；缺省为空实现（搜索退化为无结果）。
     */
    private val catalogSearch: suspend (String, String) -> List<SearchBook> = { _, _ -> emptyList() },
) : WebBookDataSource {

    override val id: Identifier = Identifier(NAMESPACE, "Wenku8")

    /**
     * 书源侧并发上限。上游默认 64，这里压到 2 —— 真正的节奏由 [HttpRateLimiter]
     * 决定（滑动窗口 + 429 退避），放大并发只会让更多请求排在限流器前面，没有收益。
     */
    override val permits: Int = 2

    override val cache: Cache = Cache(maxCountEachType = CACHE_MAX_COUNT, timeout = CACHE_TTL_MS)

    private val offlineFlow = MutableStateFlow(false)
    override val isOffLineFlow: StateFlow<Boolean> = offlineFlow.asStateFlow()

    override var offLine: Boolean = false
        private set

    /**
     * 探活：打一个白名单内的公开页面（年度精选榜），不引入额外端点。
     * 失败即视为离线；结果同步进 [offLine] 与 [isOffLineFlow]。
     */
    override suspend fun isOffLine(): Boolean {
        val down = runCatching {
            http.fetchTextInteractive(Wenku8Urls.sugoi(currentYear()), PROBE_JOB_ID, Wenku8Urls.BASE)
        }.isFailure
        offLine = down
        offlineFlow.value = down
        return down
    }

    override suspend fun getBookInformation(id: String): Result<BookInformation, WebRequestError> =
        withContext(Dispatchers.IO) {
            val bookId = id.filter(Char::isDigit)
            if (bookId.isBlank()) return@withContext err("参数错误", "书本编号无效：$id")
            runCatching {
                val url = Wenku8Urls.articleInfo(bookId)
                val page = http.fetchTextInteractive(url, "source:info:$bookId", Wenku8Urls.BASE)
                // 复用导出链路的解析器：它已处理 GBK 解码后的结构与登录页/风控页识别
                val book = Wenku8Parser.parseBook(page.html, url)
                Ok(
                    BookInformation(
                        id = bookId,
                        title = book.title,
                        author = book.author,
                        coverUri = book.coverUrl?.toUriOrNull() ?: Uri.EMPTY,
                        description = book.summary,
                        tags = book.tags,
                        publishingHouse = book.category,
                        wordCount = book.wordCount?.let { WordCount(it.toInt()) } ?: WordCount(-1),
                        lastUpdated = parseUpdatedAt(book.updatedAt),
                        isComplete = book.isComplete,
                    ),
                )
            }.getOrElse { err("获取书本详情失败", it.message ?: "网络请求时出现了错误", it) }
        }

    /**
     * 卷目录：`/novel/{cat}/{id}/index.htm`。
     *
     * 上游用 `/book/{id}.htm` 拿详情、再拼目录页；本工程改用 `articleinfo.php`
     * 拿 `directoryUrl`（其中带正确分类号），分类号缺失时由 [Wenku8Urls.index] 兜底。
     */
    override suspend fun getBookVolumes(id: String): Result<BookVolumes, WebRequestError> =
        withContext(Dispatchers.IO) {
            val bookId = id.filter(Char::isDigit)
            if (bookId.isBlank()) return@withContext err("参数错误", "书本编号无效：$id")
            runCatching {
                val directoryUrl = directoryUrlOf(bookId)
                val page = http.fetchTextInteractive(directoryUrl, "source:index:$bookId", Wenku8Urls.BASE)
                val index = Wenku8Parser.parseIndex(page.html, page.finalUrl, bookId)
                val volumes = index.chapters
                    .groupBy { it.volume.ifBlank { DEFAULT_VOLUME_TITLE } }
                    .map { (title, chapters) ->
                        Volume(
                            volumeId = title,
                            volumeTitle = title,
                            chapters = chapters.map { ChapterInformation(it.id, it.title) },
                        )
                    }
                Ok(BookVolumes(bookId, volumes))
            }.getOrElse { err("获取目录失败", it.message ?: "网络请求时出现了错误", it) }
        }

    /**
     * 章节正文：章节页 → 上游的组件 JSON（`components` 数组）。
     *
     * 段落与插图的解析仍走本工程 [Wenku8Parser.parseChapter]；这里只把它的
     * `ContentBlock` 映射成上游的组件模型，插图 URL 取 `ParsedChapter.imageUrls`。
     */
    override suspend fun getChapterContent(
        chapterId: String,
        bookId: String,
    ): Result<ChapterContent, WebRequestError> = withContext(Dispatchers.IO) {
        val chapter = chapterId.filter { it.isDigit() }
        val book = bookId.filter(Char::isDigit)
        if (chapter.isBlank() || book.isBlank()) {
            return@withContext err("参数错误", "章节或书本编号无效：$bookId/$chapterId")
        }
        runCatching {
            val url = chapterUrl(book, chapter)
            val page = http.fetchTextInteractive(url, "source:chapter:$chapter", Wenku8Urls.BASE)
            if (Wenku8Parser.looksLikeLoginPage(page.html)) {
                // 会话过期时清理本地会话，让上层提示重新登录（不尝试绕过）
                session.clear()
                throw Wenku8Exception("wenku8 登录已过期，请重新登录。", "AUTH_REQUIRED")
            }
            val parsed = Wenku8Parser.parseChapter(
                html = page.html,
                chapter = Chapter(id = chapter, title = "", url = url, order = 0),
                pageUrl = page.finalUrl,
            )
            val content = buildContent {
                parsed.blocks.forEach { block ->
                    when (block) {
                        is ContentBlock.Text -> paragraph { text(block.value) }
                        is ContentBlock.Rich -> paragraph { text(block.html) }
                        is ContentBlock.Image ->
                            parsed.imageUrls.getOrNull(block.index)?.toUriOrNull()?.let { image(it) }
                    }
                }
            }
            val neighbours = neighbourChapters(book, chapter)
            Ok(
                ChapterContent(
                    id = chapter,
                    title = parsed.title,
                    content = content,
                    prevChapter = neighbours.first,
                    nextChapter = neighbours.second,
                ),
            )
        }.getOrElse { err("获取章节失败", it.message ?: "网络请求时出现了错误", it) }
    }

    override val searchProvider: SearchProvider = Wenku8CatalogSearchProvider(catalogSearch)

    override val explorePageProvider: ExplorePageProvider = Wenku8ExplorePageProvider(http)

    // ---- 内部工具 ----

    /** `articleinfo.php` 解析出的目录页地址；拿不到就按默认分类号兜底。 */
    private suspend fun directoryUrlOf(bookId: String): String {
        val cached = cache.getCache<String>(bookId.hashCode())
        if (cached != null) return cached
        val url = Wenku8Urls.articleInfo(bookId)
        val resolved = runCatching {
            val page = http.fetchTextInteractive(url, "source:dir:$bookId", Wenku8Urls.BASE)
            Wenku8Parser.parseBook(page.html, url).directoryUrl
        }.getOrNull()
        val target = resolved?.takeIf { it.isNotBlank() } ?: Wenku8Urls.index(bookId)
        cache.cache(bookId.hashCode(), target)
        return target
    }

    /**
     * 章节页地址：`/novel/{cat}/{bookId}/{chapterId}.htm`。
     * 分类号从目录页地址里取（`/novel/2/123/index.htm` → `2`），取不到按默认值。
     */
    private suspend fun chapterUrl(bookId: String, chapterId: String): String {
        val category = Regex("/novel/(\\d+)/").find(directoryUrlOf(bookId))?.groupValues?.get(1)
        return "${Wenku8Urls.BASE}/novel/${category ?: Wenku8Urls.DEFAULT_NOVEL_CATEGORY}/$bookId/$chapterId.htm"
    }

    /**
     * 上一/下一章：从（已缓存的）目录里找相邻章节。
     * 目录页本身有"上一章/下一章"链接，但那要多一次请求；这里用目录推算，零额外请求。
     */
    private suspend fun neighbourChapters(bookId: String, chapterId: String): Pair<String?, String?> {
        val volumes = getBookVolumes(bookId).getOrNull() ?: return null to null
        val flat = volumes.volumes.flatMap { it.chapters }
        val index = flat.indexOfFirst { it.id == chapterId }
        if (index < 0) return null to null
        return flat.getOrNull(index - 1)?.id to flat.getOrNull(index + 1)?.id
    }

    private fun err(title: String, message: String, cause: Throwable? = null): Result<Nothing, WebRequestError> =
        Err(WebRequestError(title, message, cause))

    private fun String.toUriOrNull(): Uri? = runCatching { toUri() }.getOrNull()

    private fun parseUpdatedAt(raw: String): LocalDateTime =
        runCatching { LocalDate.parse(raw.trim(), DATE_FORMATTER).atStartOfDay() }
            .getOrElse { LocalDateTime.MIN }

    private fun currentYear(): Int = LocalDate.now().year

    companion object {
        const val NAMESPACE = "lightnovelreader"
        private const val DEFAULT_VOLUME_TITLE = "正文"
        private const val PROBE_JOB_ID = "source:probe"
        private const val CACHE_MAX_COUNT = 32
        private const val CACHE_TTL_MS = 30 * 60 * 1000
        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}

/**
 * 探索页：只挂**匿名可访问**的公开榜单（AGENTS §4.2 白名单内的 `/zt/` 页）。
 *
 * 上游的探索页用的是 `articlelist.php` / `toplist.php` / `tags.php`，这三个由站点
 * 控制登录，本工程不碰；因此这里换成年度精选榜与月度新书榜两个公开入口。
 */
private class Wenku8ExplorePageProvider(
    private val http: Wenku8HttpClient,
) : AbstractDefaultExplorePageProvider() {

    init {
        val year = LocalDate.now().year
        registerTapPage(
            "sugoi",
            Wenku8ListTapPage(
                http = http,
                tapTitle = "年度精选",
                urls = (year downTo year - 4).map { Wenku8Urls.sugoi(it) },
                jobPrefix = "explore:sugoi",
            ),
        )
        registerTapPage(
            "booklist",
            Wenku8ListTapPage(
                http = http,
                tapTitle = "月度新书",
                urls = (0 until 3).map { offset ->
                    Wenku8Urls.booklist(LocalDate.now().minusMonths(offset.toLong()).format(MONTH_FORMATTER))
                },
                jobPrefix = "explore:booklist",
            ),
        )
    }

    private companion object {
        val MONTH_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")
    }
}

/**
 * 单个探索页：把若干公开榜单 URL 抓下来，合成一行书。
 *
 * 解析复用 [Wenku8Parser.parseSearchResults]——导出链路与旧的探索页都走它，
 * 对 `/zt/` 榜单页的卡片结构已经验证过。
 */
private class Wenku8ListTapPage(
    private val http: Wenku8HttpClient,
    private val tapTitle: String,
    private val urls: List<String>,
    private val jobPrefix: String,
) : ExploreTapPageDataSource {

    override val title: String = tapTitle

    override fun getRowsFlow(): Flow<List<ExploreBooksRow>> = flow {
        val books = mutableListOf<SearchBook>()
        val seen = mutableSetOf<String>()
        urls.forEachIndexed { index, url ->
            runCatching {
                val page = http.fetchText(url, "$jobPrefix:$index", Wenku8Urls.BASE)
                Wenku8Parser.parseSearchResults(page.html, page.finalUrl)
            }.getOrDefault(emptyList()).forEach { book ->
                if (book.id.isNotBlank() && seen.add(book.id)) books += book
            }
        }
        if (books.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        emit(
            listOf(
                ExploreBooksRow(
                    title = tapTitle,
                    bookList = books.map {
                        ExploreDisplayBook(
                            id = it.id,
                            title = it.title,
                            author = it.author,
                            coverUri = it.coverUrl?.let { url -> runCatching { url.toUri() }.getOrNull() } ?: Uri.EMPTY,
                        )
                    },
                ),
            ),
        )
    }.flowOn(Dispatchers.IO)
}

/**
 * 搜索：走**本地书目索引**，不打源站的 `search.php`（该接口由站点控制登录）。
 *
 * 本地索引由 [CatalogCrawler] 从公开榜单与书籍详情页构建，断网可用；
 * 命中结果以 [SearchResult.MultipleBook] 逐个吐出，与上游的流式契约一致。
 */
private class Wenku8CatalogSearchProvider(
    private val lookup: suspend (String, String) -> List<SearchBook>,
) : SearchProvider {

    override val searchTypes: List<SearchType> = listOf(
        SearchType("title", "按书名".local(), "输入书名关键词".local()),
        SearchType("author", "按作者".local(), "输入作者名".local()),
    )

    override fun search(searchType: SearchType, keyword: String): Flow<SearchResult> = flow {
        val query = keyword.trim()
        if (query.isBlank()) {
            emit(SearchResult.Empty())
            return@flow
        }
        val hits = runCatching { lookup(searchType.type, query) }.getOrElse {
            emit(SearchResult.Error(it))
            return@flow
        }
        if (hits.isEmpty()) {
            emit(SearchResult.Empty())
            return@flow
        }
        hits.forEach { emit(SearchResult.MultipleBook(it.id)) }
        emit(SearchResult.End())
    }.flowOn(Dispatchers.IO)
}

/**
 * 书源注册表：本工程只注册 wenku8 一个源。
 * 上游靠插件系统动态加载，这里用最朴素的单例注册，够用且无插件安装器的复杂度。
 */
class Wenku8BookSourceManager(
    private val source: Wenku8BookSource,
) : WebBookDataSourceManagerApi {

    private var current: WebBookDataSource = source

    override fun registerWebDataSource(
        webBookDataSource: WebBookDataSource,
        webDataSourceItem: WebDataSourceItem,
    ) {
        current = webBookDataSource
    }

    override fun unregisterWebDataSource(webDataSourceId: Identifier) {
        // 单源应用：注销即回落默认源，不做动态卸载
    }

    override fun getWebDataSource(): WebBookDataSource = current
}

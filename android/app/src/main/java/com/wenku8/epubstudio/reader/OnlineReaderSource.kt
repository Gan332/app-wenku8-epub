package com.wenku8.epubstudio.reader

import com.wenku8.epubstudio.core.Wenku8Exception
import com.wenku8.epubstudio.core.Wenku8HttpClient
import com.wenku8.epubstudio.core.Wenku8Parser
import com.wenku8.epubstudio.core.Wenku8Urls
import com.wenku8.epubstudio.model.BookIndex
import com.wenku8.epubstudio.model.Chapter
import com.wenku8.epubstudio.model.ContentBlock
import com.wenku8.epubstudio.model.ParsedChapter
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 抓取结果。抽成接口是为了在单元测试里注入假 fetcher，验证「命中缓存不发网络」。 */
data class FetchedText(val html: String, val finalUrl: String, val contentType: String = "text/html")

fun interface OnlineTextFetcher {
    suspend fun fetch(url: String, referer: String?): FetchedText
}

/**
 * 走共享限流客户端的真实抓取实现。
 *
 * 复用 [Wenku8HttpClient] 意味着在线阅读和导出流程共享同一个全局 1 秒限流器与
 * HTTP 429 退避；**不新建 OkHttp 客户端**，否则会绕过限流把请求打到源站。
 */
class Wenku8TextFetcher(private val http: Wenku8HttpClient) : OnlineTextFetcher {
    override suspend fun fetch(url: String, referer: String?): FetchedText {
        val resource = http.fetchText(url, JOB_ID, referer)
        return FetchedText(resource.html, resource.finalUrl, resource.contentType)
    }

    companion object {
        const val JOB_ID = "online-reader"
    }
}

/**
 * 在线阅读的结果类型。
 *
 * [NeedsLogin] 是**独立分支而不是异常**：源站对某些内容要求登录，
 * 按 AGENTS.md 4.2 我们只如实提示，**绝不做任何绕过**（不换 IP、不补 Cookie、不模拟登录）。
 */
sealed class OnlineReaderResult<out T> {
    data class Ready<T>(val value: T, val fromCache: Boolean = false) : OnlineReaderResult<T>()

    data class NeedsLogin(val message: String = LOGIN_MESSAGE) : OnlineReaderResult<Nothing>()

    data class Failed(val message: String, val code: String = "ONLINE_READ_FAILED") : OnlineReaderResult<Nothing>()

    companion object {
        const val LOGIN_MESSAGE = "该内容需要登录，请在应用内登录文库后重试。"
        const val CODE_CHAPTER_GONE = "CHAPTER_GONE"
        const val CODE_OFFLINE = "OFFLINE_NO_CACHE"
    }
}

inline fun <T, R> OnlineReaderResult<T>.flatMap(transform: (T) -> OnlineReaderResult<R>): OnlineReaderResult<R> =
    when (this) {
        is OnlineReaderResult.Ready -> transform(value)
        is OnlineReaderResult.NeedsLogin -> this
        is OnlineReaderResult.Failed -> this
    }

fun <T> OnlineReaderResult<T>.valueOrNull(): T? = (this as? OnlineReaderResult.Ready)?.value

fun <T> OnlineReaderResult<T>.errorOrNull(): String? = when (this) {
    is OnlineReaderResult.Failed -> message
    is OnlineReaderResult.NeedsLogin -> message
    is OnlineReaderResult.Ready -> null
}

/** 目录加载结果：同时保留阅读器用的 [ReaderBook] 和 `parseChapter` 需要的原始 [BookIndex]。 */
data class OnlineIndex(
    val book: ReaderBook,
    val catalog: BookIndex,
) {
    val sourceUrl: String get() = catalog.url

    val size: Int get() = catalog.chapters.size

    fun chapterAt(index: Int): Chapter? = catalog.chapters.getOrNull(index)
}

/**
 * 把 wenku8 的目录与正文装配成现有阅读器的 [ReaderBook] / [ReaderChapter] / [ReaderBlock]。
 *
 * **不新造阅读器**：这里只负责「抓取 + 装配」，渲染交给现有 `ReaderScreen`，
 * 于是字体、背景、沉浸模式、目录、阅读进度、时长统计在在线与 EPUB 两种模式下完全一致。
 *
 * 边界（AGENTS.md 4.2 / 4.5）：
 * - 章节请求**串行**（[chapterMutex]），不做任何并发预取——并发会打爆 1 秒/请求的全局限流器。
 * - 命中缓存不发网络，因此断网可读已读过的章节。
 * - 目录与章节只访问免登录公开页 `/novel/2/{id}/index.htm` 与 `/novel/2/{id}/{cid}.htm`。
 * - 遇到登录页只提示，不绕过。
 *
 * 本类不引用任何 Android 类型，可在纯 JVM 单元测试里直接构造。
 */
class OnlineReaderSource(
    private val cache: OnlineChapterCache,
    private val fetcher: OnlineTextFetcher,
) {
    constructor(cacheRoot: File, http: Wenku8HttpClient) : this(OnlineChapterCache(cacheRoot), Wenku8TextFetcher(http))

    private val chapterMutex = Mutex()

    val chapterCache: OnlineChapterCache get() = cache

    /** 公开目录页：匿名访客可访问，无需登录。 */
    fun indexUrl(bookId: String): String =
        "${Wenku8Urls.BASE}/novel/2/${bookId.filter(Char::isDigit)}/index.htm"

    // ---- 目录 ----

    suspend fun loadIndex(
        bookId: String,
        title: String = "",
        author: String = "",
    ): OnlineReaderResult<OnlineIndex> = withContext(Dispatchers.IO) {
        val id = bookId.filter(Char::isDigit)
        if (id.isBlank()) return@withContext OnlineReaderResult.Failed("书籍编号无效。", "INVALID_BOOK_ID")
        fetch(indexUrl(id), referer = null).flatMap { fetched ->
            decodeIndex(fetched, id, title, author)
        }
    }

    /** 只要 ReaderBook 的便捷入口。 */
    suspend fun loadBook(bookId: String, title: String = "", author: String = ""): OnlineReaderResult<ReaderBook> =
        when (val result = loadIndex(bookId, title, author)) {
            is OnlineReaderResult.Ready -> OnlineReaderResult.Ready(result.value.book, result.fromCache)
            is OnlineReaderResult.NeedsLogin -> result
            is OnlineReaderResult.Failed -> result
        }

    // ---- 章节 ----

    /**
     * 读取单章。命中缓存直接返回，**不触发网络**。
     *
     * @param catalog 可选；提供后能保留卷名用于标题层级。
     */
    suspend fun loadChapter(
        bookId: String,
        chapter: Chapter,
        catalog: List<Chapter> = emptyList(),
    ): OnlineReaderResult<ReaderChapter> = withContext(Dispatchers.IO) {
        if (cache.isGone(bookId, chapter.id)) {
            return@withContext OnlineReaderResult.Failed(goneMessage(chapter), OnlineReaderResult.CODE_CHAPTER_GONE)
        }
        cache.read(bookId, chapter.id)?.let { cached ->
            return@withContext OnlineReaderResult.Ready(
                ReaderChapter(chapter.id, chapter.title, chapter.url, cached.blocks),
                fromCache = true,
            )
        }
        // 串行闸门：一次只允许一个章节请求在飞，不做预取。
        chapterMutex.withLock {
            val outcome = fetch(chapter.url, referer = indexUrl(bookId)).flatMap { fetched ->
                decodeChapter(fetched, chapter)
            }
            // 缓存写入与失效标记刻意放在 flatMap **之外**：
            // HTTP 404 是在 fetch() 里被映射成 CHAPTER_GONE 的，那种情况下
            // flatMap 的变换根本不会执行——若把 markGone 写在变换里，
            // 已删除的章节就永远打不上墓碑，每次翻页都会重新去打扰源站。
            when (outcome) {
                is OnlineReaderResult.Ready -> cache.write(
                    OnlineChapterCacheEntry(
                        bookId = bookId,
                        chapterId = chapter.id,
                        title = chapter.title,
                        volume = chapter.volume,
                        sourceUrl = outcome.value.href,
                        blocks = outcome.value.blocks,
                    ),
                )
                is OnlineReaderResult.Failed -> if (outcome.code == OnlineReaderResult.CODE_CHAPTER_GONE) {
                    // 源站已删除：打失效标记，之后翻到这里直接短路
                    cache.markGone(bookId, chapter.id)
                }
                is OnlineReaderResult.NeedsLogin -> Unit
            }
            outcome
        }
    }

    /** 只要 [ReaderBook] 时可用的便捷重载。 */
    suspend fun loadChapter(
        bookId: String,
        chapterIndex: Int,
        book: ReaderBook,
        catalog: List<Chapter> = emptyList(),
    ): OnlineReaderResult<ReaderChapter> {
        val raw = catalog.getOrNull(chapterIndex)
        val chapter = raw ?: book.chapters.getOrNull(chapterIndex)?.let {
            Chapter(
                id = it.id,
                title = it.title,
                url = it.href,
                volume = DEFAULT_VOLUME,
                order = chapterIndex + 1,
            )
        } ?: return OnlineReaderResult.Failed("章节不存在。", "CHAPTER_NOT_FOUND")
        return loadChapter(bookId, chapter, catalog)
    }

    /** 章节确认失效后跳到相邻章节；已在边界则停在原地。 */
    fun nearestChapterIndex(from: Int, total: Int, step: Int = 1): Int {
        if (total <= 0) return 0
        val current = from.coerceIn(0, total - 1)
        val neighbour = (current + (if (step >= 0) 1 else -1)).coerceIn(0, total - 1)
        return neighbour
    }

    /** 失效章节应当被记住，避免每次翻页都去打扰源站。 */
    fun markChapterGone(bookId: String, chapterId: String) = cache.markGone(bookId, chapterId)

    // ---- 内部：抓取与解析 ----

    private suspend fun fetch(url: String, referer: String?): OnlineReaderResult<FetchedText> = try {
        OnlineReaderResult.Ready(fetcher.fetch(url, referer))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        error.toFailed()
    }

    private fun decodeIndex(
        fetched: FetchedText,
        bookId: String,
        title: String = "",
        author: String = "",
    ): OnlineReaderResult<OnlineIndex> {
        // 登录页绝不能被当成目录解析，更不能被当成正文。
        if (isAuthWall(fetched.html)) return OnlineReaderResult.NeedsLogin()
        return try {
            val index = Wenku8Parser.parseIndex(fetched.html, fetched.finalUrl, bookId)
            OnlineReaderResult.Ready(OnlineIndex(index.toReaderBook(title, author), index))
        } catch (error: Wenku8Exception) {
            // 目录页识别不到章节时，若 HTML 其实是一堵登录墙，如实报「需要登录」。
            if (error.code == "NO_CHAPTERS" && isAuthWall(fetched.html)) {
                OnlineReaderResult.NeedsLogin()
            } else {
                OnlineReaderResult.Failed(error.message ?: "目录读取失败。", error.code)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            OnlineReaderResult.Failed(error.message ?: "目录读取失败。", "INDEX_PARSE_FAILED")
        }
    }

    private fun decodeChapter(fetched: FetchedText, chapter: Chapter): OnlineReaderResult<ReaderChapter> {
        if (isAuthWall(fetched.html)) return OnlineReaderResult.NeedsLogin()
        return try {
            val parsed = Wenku8Parser.parseChapter(fetched.html, chapter, fetched.finalUrl)
            OnlineReaderResult.Ready(parsed.toReaderChapter())
        } catch (error: Wenku8Exception) {
            error.toFailed()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            OnlineReaderResult.Failed(error.message ?: "章节读取失败。", "CHAPTER_PARSE_FAILED")
        }
    }

    companion object {
        const val DEFAULT_VOLUME = "正文"
    }
}

private fun goneMessage(chapter: Chapter): String = "章节已被源站删除或不可用：${chapter.title}"

/** 章节级失败不总是「删除」，只有 404/410 才是；其余保持原错误码。 */
private fun Throwable.toFailed(): OnlineReaderResult.Failed {
    val wenku8 = this as? Wenku8Exception
    val message = wenku8?.message ?: "源站暂时不可用。"
    val code = when {
        wenku8?.code == "UPSTREAM_HTTP_ERROR" && Regex("HTTP (404|410)").containsMatchIn(message) ->
            OnlineReaderResult.CODE_CHAPTER_GONE
        else -> wenku8?.code ?: "ONLINE_READ_FAILED"
    }
    return OnlineReaderResult.Failed(message, code)
}

/**
 * 登录墙识别。**只用于如实提示，绝不用于绕过**（AGENTS.md 4.2）。
 * 任何被判定为登录墙的 HTML 都不会进入正文解析路径。
 */
fun isAuthWall(html: String): Boolean {
    val head = html.take(30_000)
    if (Wenku8Parser.looksLikeLoginPage(head)) return true
    if (Wenku8Parser.looksLikeChallenge(head)) return true
    return AUTH_WALL_MARKERS.containsMatchIn(head)
}

private val AUTH_WALL_MARKERS = Regex(
    "login\\.php|name\\s*=\\s*[\"']?username[\"']?|请先登录|需要登录|请登录後|請先登入|訪客請登入|權限不足|无权限",
    RegexOption.IGNORE_CASE,
)

// ---- 装配：BookIndex / Chapter / ParsedChapter → ReaderBook ----

/** 目录此时还没有正文，`blocks` 留空；进入某一章时再补。 */
fun BookIndex.toReaderBook(fallbackTitle: String = "", fallbackAuthor: String = ""): ReaderBook = ReaderBook(
    id = bookId.orEmpty(),
    title = title.trim().ifBlank { fallbackTitle.trim() }.ifBlank { "未命名轻小说" },
    author = fallbackAuthor.trim(),
    language = "zh-CN",
    chapters = chapters.map { it.toReaderChapter() },
    archivePath = "",
)

fun Chapter.toReaderChapter(blocks: List<ReaderBlock> = emptyList()): ReaderChapter =
    ReaderChapter(id = id, title = title, href = url, blocks = blocks)

fun ParsedChapter.toReaderChapter(): ReaderChapter = ReaderChapter(
    id = id,
    title = title,
    href = sourceUrl,
    blocks = toReaderBlocks(),
)

/**
 * 正文装配：卷名 → 一级标题，章节名 → 二级标题，`ContentBlock.Text` → 段落，
 * `ContentBlock.Image(index)` → 用 `imageUrls[index]` 的远程图片。
 * 远程 URL 交给 `RemoteImage` 渲染，下载同样走限流客户端。
 */
fun ParsedChapter.toReaderBlocks(): List<ReaderBlock> {
    val result = ArrayList<ReaderBlock>(blocks.size + 2)
    val heading = volume.trim()
    if (heading.isNotEmpty() && heading != OnlineReaderSource.DEFAULT_VOLUME) {
        result += ReaderBlock.Heading(1, heading)
    }
    if (title.isNotBlank()) result += ReaderBlock.Heading(2, title.trim())
    for (block in blocks) {
        when (block) {
            is ContentBlock.Text -> block.value.trim().takeIf { it.isNotEmpty() }?.let { result += ReaderBlock.Paragraph(it) }
            is ContentBlock.Image -> imageUrls.getOrNull(block.index)?.let {
                result += ReaderBlock.Image(path = it, alt = "插图 ${block.index + 1}")
            }
        }
    }
    return result
}

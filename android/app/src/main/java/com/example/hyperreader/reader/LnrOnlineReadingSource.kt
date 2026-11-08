package com.example.hyperreader.reader

import com.example.hyperreader.core.Wenku8BookSource
import com.example.hyperreader.core.Wenku8Exception
import com.example.hyperreader.core.Wenku8Urls
import com.example.hyperreader.model.BookIndex
import com.example.hyperreader.model.Chapter
import com.example.hyperreader.model.ContentBlock
import com.example.hyperreader.model.ParsedChapter
import com.github.michaelbull.result.getOrElse
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * LightNovelReader 书源版的在线阅读数据源。
 *
 * 与 [OnlineReaderSource] 共用同一套阅读器与页轴，只把「目录 / 章节正文」的取数换成
 * LNR 的 [io.nightfish.lightnovelreader.api.web.WebBookDataSource] 契约
 * （本工程实现是 [Wenku8BookSource]）。可以把它理解为书源体系的「另一条装配线」：
 *
 * - 目录：`getBookVolumes` → [BookIndex]
 * - 正文：`getChapterContent` 的组件 JSON → `ParsedChapter` → `ReaderChapter`
 *
 * 两条链路都仍由 `Wenku8BookSource` 走共享的 [com.example.hyperreader.core.Wenku8HttpClient]
 * （全局 1 秒限流 + 429 退避），不新建客户端、不碰登录门禁端点。
 *
 * 缓存与内置源使用同一套 [OnlineChapterCache] 结构；传 `null` 时不做磁盘缓存。
 */
class LnrOnlineReadingSource(
    private val bookSource: Wenku8BookSource,
    private val cache: OnlineChapterCache? = null,
    /** 目录快照：网络失败时用它打开离线阅读（「缓存全书」写入）。 */
    private val indexStore: OnlineIndexStore? = null,
) : OnlineReadingSource {

    override suspend fun loadIndex(
        bookId: String,
        title: String,
        author: String,
    ): OnlineReaderResult<OnlineIndex> = withContext(Dispatchers.IO) {
        val id = bookId.filter(Char::isDigit)
        if (id.isBlank()) {
            return@withContext OnlineReaderResult.Failed("书籍编号无效。", "INVALID_BOOK_ID")
        }
        val volumes = bookSource.getBookVolumes(id).getOrElse { error ->
            indexStore?.read(id)?.let { cached ->
                return@withContext OnlineReaderResult.Ready(
                    OnlineIndex(cached.toReaderBook(title, author), cached),
                    fromCache = true,
                )
            }
            return@withContext error.toOnlineFailure()
        }
        val chapters = volumes.volumes.flatMap { volume ->
            volume.chapters.map { chapter ->
                Chapter(
                    id = chapter.id,
                    title = chapter.title,
                    url = "${Wenku8Urls.BASE}/novel/${Wenku8Urls.DEFAULT_NOVEL_CATEGORY}/$id/${chapter.id}.htm",
                    volume = volume.volumeTitle.ifBlank { OnlineReaderSource.DEFAULT_VOLUME },
                    order = 0,
                )
            }
        }.mapIndexed { index, chapter -> chapter.copy(order = index + 1) }
        if (chapters.isEmpty()) {
            return@withContext OnlineReaderResult.Failed("目录中没有识别到章节。", "NO_CHAPTERS")
        }
        val index = BookIndex(
            title = title.trim().ifBlank { "未命名轻小说" },
            url = Wenku8Urls.index(id),
            bookId = id,
            chapters = chapters,
        )
        indexStore?.write(index)
        OnlineReaderResult.Ready(OnlineIndex(index.toReaderBook(title, author), index))
    }

    override suspend fun loadChapter(
        bookId: String,
        chapter: Chapter,
        catalog: List<Chapter>,
    ): OnlineReaderResult<ReaderChapter> = withContext(Dispatchers.IO) {
        if (cache?.isGone(bookId, chapter.id) == true) {
            return@withContext OnlineReaderResult.Failed(
                "章节已被源站删除或不可用：${chapter.title}",
                OnlineReaderResult.CODE_CHAPTER_GONE,
            )
        }
        cache?.read(bookId, chapter.id)?.let { cached ->
            return@withContext OnlineReaderResult.Ready(
                ReaderChapter(chapter.id, chapter.title, chapter.url, cached.blocks),
                fromCache = true,
            )
        }
        val content = bookSource.getChapterContent(chapter.id, bookId).getOrElse { error ->
            val failure = error.toOnlineFailure()
            // 404/410 打失效标记：之后不再重复打扰源站（与内置源一致）。
            if (failure is OnlineReaderResult.Failed && failure.code == OnlineReaderResult.CODE_CHAPTER_GONE) {
                cache?.markGone(bookId, chapter.id)
            }
            return@withContext failure
        }
        val parsed = content.toParsedChapter(chapter)
        val reader = parsed.toReaderChapter()
        cache?.write(
            OnlineChapterCacheEntry(
                bookId = bookId,
                chapterId = chapter.id,
                title = reader.title,
                volume = chapter.volume,
                sourceUrl = chapter.url,
                blocks = reader.blocks,
            ),
        )
        OnlineReaderResult.Ready(reader)
    }
}

/**
 * LNR 章节目录 JSON → 本工程的 [ParsedChapter]。
 *
 * 纯函数，便于单测：只认识 `paragraph` / `image` 两种组件，其余（自定义书源组件）
 * 安全跳过。段落文本按 `textNodes` 原样拼接；插图按出现顺序写入 `imageUrls`，
 * 并用 [ContentBlock.Image] 标记插入位置。
 */
internal fun ChapterContent.toParsedChapter(chapter: Chapter): ParsedChapter {
    val blocks = mutableListOf<ContentBlock>()
    val images = mutableListOf<String>()
    val components = (content["components"] as? JsonArray).orEmpty()
    for (component in components) {
        val item = component as? JsonObject ?: continue
        val id = item["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val data = item["data"] as? JsonObject ?: continue
        when (id) {
            PARAGRAPH_COMPONENT_ID -> {
                val text = data["paragraph"]
                    ?.jsonObject
                    ?.get("textNodes")
                    ?.jsonArray
                    .orEmpty()
                    .joinToString("") { node ->
                        (node as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                    }
                if (text.isNotBlank()) blocks += ContentBlock.Text(text)
            }
            IMAGE_COMPONENT_ID -> {
                val uri = data["uri"]?.jsonPrimitive?.contentOrNull
                if (!uri.isNullOrBlank()) {
                    blocks += ContentBlock.Image(images.size)
                    images += uri
                }
            }
            else -> Unit
        }
    }
    return ParsedChapter(
        id = id.ifBlank { chapter.id },
        title = title.ifBlank { chapter.title },
        volume = chapter.volume,
        order = chapter.order,
        sourceUrl = chapter.url,
        imageUrls = images,
        blocks = blocks,
        textLength = blocks.filterIsInstance<ContentBlock.Text>().sumOf { it.value.length },
    )
}

/** 书源错误 → 阅读器既能提示也能识别的结果；登录墙与 Cloudflare 挑战继续按原语义上报。 */
private fun WebRequestError.toOnlineFailure(): OnlineReaderResult<Nothing> {
    val code = (throwable as? Wenku8Exception)?.code
    val gone = code == "UPSTREAM_HTTP_ERROR" && Regex("HTTP (404|410)").containsMatchIn(message)
    return when {
        gone -> OnlineReaderResult.Failed(
            message.ifBlank { "章节已被源站删除或不可用。" },
            OnlineReaderResult.CODE_CHAPTER_GONE,
        )
        code == "AUTH_REQUIRED" || title.contains("登录") || message.contains("登录") ->
            OnlineReaderResult.NeedsLogin()
        code == "UPSTREAM_CHALLENGE" ->
            OnlineReaderResult.Failed(message.ifBlank { "源站要求浏览器验证。" }, "UPSTREAM_CHALLENGE")
        else -> OnlineReaderResult.Failed(
            message.ifBlank { title.ifBlank { "书源请求失败。" } },
            code ?: "LNR_SOURCE_FAILED",
        )
    }
}

private const val PARAGRAPH_COMPONENT_ID = "lightnovelreader:paragraph"
private const val IMAGE_COMPONENT_ID = "lightnovelreader:image"

package com.example.hyperreader.reader

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.hyperreader.Wenku8Application
import com.example.hyperreader.data.appDataStore
import com.xyreader.archive.ChapterMark
import com.xyreader.archive.EpubImageGroup
import com.xyreader.archive.NovelPageSource
import com.xyreader.archive.NovelStyle
import com.xyreader.archive.Paragraph
import com.xyreader.core.BookEntity
import com.xyreader.core.BookmarkEntity
import com.xyreader.core.PageSource
import com.xyreader.core.ReaderPrefs
import com.xyreader.core.ReaderRepository
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * xy-reader 阅读器与本工程数据层之间的**唯一适配层**。
 *
 * 移植进来的 `com.xyreader` 只认 [ReaderRepository] 这一个窄接口（见其 KDoc）；
 * 本文件把本工程既有的 DataStore 仓储、EPUB 解析管线接到该接口上，遵守：
 *
 * - **单一 `preferencesDataStore("wenku8_settings")`**（AGENTS §4.3）：不新建 DataStore
 *   实例，全部键以 `xy_reader_` 前缀与既有键隔离；
 * - **不引入 Room / Coil**：书签、阅读配置、页进度都手写 JSON 存同一 DataStore；
 * - **EPUB 仍走本工程的解析管线**（Rust 原生结构解析 + Jsoup 块解析 + 回退链），
 *   不交给上游的 `NovelTextExtractor.parseEpub`——见 [XyEpubPageSourceOpener]。
 */

// ---------------------------------------------------------------------------
// 1. 阅读配置：ReaderPrefs ↔ DataStore（单键 JSON）
// ---------------------------------------------------------------------------

private val xyJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    // 枚举值被删掉时（如 0.14.0 移除的内置字体）回退到默认值，而不是抛异常让整份配置作废
    coerceInputValues = true
}

/** 阅读配置在 DataStore 里的键；整份 [ReaderPrefs] 以一个 JSON 字符串存取。 */
private val KEY_READER_PREFS = stringPreferencesKey("xy_reader_prefs")

/**
 * 阅读配置的持久化。上游用 8 个独立枚举键（`ReaderPrefsStore`）；这里改成单键 JSON——
 * 字段数量与耦合都更少，且 `ReaderPrefs` 新增字段时不必再改读写两处。
 * 任何解析失败一律回退全默认值，绝不把脏数据带进排版管线。
 */
class XyReaderPrefsStore(private val data: DataStore<Preferences>) {

    val prefs: Flow<ReaderPrefs> = data.safeData().map { p ->
        p[KEY_READER_PREFS]
            ?.let { raw -> runCatching { xyJson.decodeFromString(ReaderPrefs.serializer(), raw) }.getOrNull() }
            ?: ReaderPrefs()
    }

    /** 是否已有 xy 阅读器自己的配置；用于在线阅读只在首次打开时导入旧设置。 */
    suspend fun hasStored(): Boolean = data.safeData().map { it.contains(KEY_READER_PREFS) }.first()

    suspend fun set(prefs: ReaderPrefs) {
        data.edit { p ->
            p[KEY_READER_PREFS] = xyJson.encodeToString(ReaderPrefs.serializer(), prefs)
        }
    }
}

// ---------------------------------------------------------------------------
// 2. 书签 / 收藏 / 页进度：同一 DataStore，xy_reader_ 前缀
// ---------------------------------------------------------------------------

private val KEY_BOOKMARKS = stringPreferencesKey("xy_reader_bookmarks")
private val KEY_FAVORITES = stringPreferencesKey("xy_reader_favorites")

/** 页进度键：`xy_reader_page_<本工程 bookId>`，值形如 `"当前页/总页数"`。 */
private fun pageKey(hostBookId: String) = stringPreferencesKey("xy_reader_page_$hostBookId")

/** 已存页进度（阅读器续读用）；未读过返回 0。 */
suspend fun readXyPageProgress(data: DataStore<Preferences>, hostBookId: String): Int =
    data.safeData().map { p ->
        p[pageKey(hostBookId)]?.substringBefore('/')?.toIntOrNull() ?: 0
    }.first()

// ---------------------------------------------------------------------------
// 3. ReaderRepository 实现
// ---------------------------------------------------------------------------

/**
 * 阅读器仓库实现：一次阅读会话一个实例（`books` 只暴露当前这本书）。
 *
 * @param book 阅读器视角的书（`id` 已映射为 Long，见 [xyBookIdOf]）
 * @param hostBookId 本工程的书 id（字符串），用于书架、统计与 DataStore 键
 */
class XyReaderRepository(
    context: Context,
    private val book: BookEntity,
    private val hostBookId: String,
) : ReaderRepository {

    private val app = context.applicationContext
    private val data = app.appDataStore
    private val prefsStore = XyReaderPrefsStore(data)

    private val wenku8 = app as Wenku8Application

    /** 上游 `ReaderViewModel` 会等这本书出现；这里直接给出，避免它走 8 秒超时兜底。 */
    override val books: StateFlow<List<BookEntity>> = MutableStateFlow(listOf(book))

    override val bookmarks: Flow<List<BookmarkEntity>> = data.safeData().map { p ->
        decodeBookmarks(p[KEY_BOOKMARKS])
            .filter { it.bookId == book.id }
            .sortedByDescending { it.createdAt }
    }

    override val readerPrefs: Flow<ReaderPrefs> = prefsStore.prefs

    override suspend fun setReaderPrefs(prefs: ReaderPrefs) = prefsStore.set(prefs)

    /**
     * 保存阅读进度。
     *
     * 上游是「页 / 总页数」模型，本工程既有 [com.example.hyperreader.settings.ReadingProgress]
     * 是「章 + 段」模型，两者口径不同（分页结果依赖字号/边距，无法稳定换算），因此
     * **页进度单独存**（`xy_reader_page_*`，阅读器续读的唯一依据），同时刷新书架的
     * 「上次阅读」时间，让书架排序与「继续阅读」入口仍然可用。
     */
    override suspend fun saveProgress(bookId: Long, page: Int, totalPages: Int) {
        data.edit { p ->
            p[pageKey(hostBookId)] = "$page/$totalPages"
        }
        runCatching { wenku8.bookshelfRepository.recordRead(hostBookId) }
    }

    /**
     * 收藏开关。本工程书架模型没有「收藏」位（只有置顶 `isPinned`），
     * 因此收藏状态独立存一份集合，不借用置顶语义。
     */
    override suspend fun toggleFavorite(bookId: Long) {
        data.edit { p ->
            val current = decodeFavorites(p[KEY_FAVORITES])
            val next = if (hostBookId in current) current - hostBookId else current + hostBookId
            p[KEY_FAVORITES] = xyJson.encodeToString(SetSerializer(String.serializer()), next)
        }
    }

    override suspend fun addBookmark(bookId: Long, pageIndex: Int) {
        data.edit { p ->
            val current = decodeBookmarks(p[KEY_BOOKMARKS])
            val nextId = (current.maxOfOrNull { it.id } ?: 0L) + 1L
            val next = current + BookmarkEntity(
                id = nextId,
                bookId = book.id,
                pageIndex = pageIndex,
                createdAt = System.currentTimeMillis(),
            )
            p[KEY_BOOKMARKS] = xyJson.encodeToString(ListSerializer(BookmarkEntity.serializer()), next)
        }
    }

    override suspend fun removeBookmark(bookmarkId: Long) {
        data.edit { p ->
            val next = decodeBookmarks(p[KEY_BOOKMARKS]).filterNot { it.id == bookmarkId }
            p[KEY_BOOKMARKS] = xyJson.encodeToString(ListSerializer(BookmarkEntity.serializer()), next)
        }
    }

    /** 当前是否已收藏（阅读器工具栏的收藏态用）。 */
    suspend fun isFavorite(): Boolean = data.safeData().map { p ->
        hostBookId in decodeFavorites(p[KEY_FAVORITES])
    }.first()

    private fun decodeBookmarks(raw: String?): List<BookmarkEntity> =
        raw?.let {
            runCatching {
                xyJson.decodeFromString(ListSerializer(BookmarkEntity.serializer()), it)
            }.getOrNull()
        } ?: emptyList()

    private fun decodeFavorites(raw: String?): Set<String> =
        raw?.let {
            runCatching { xyJson.decodeFromString(SetSerializer(String.serializer()), it) }.getOrNull()
        } ?: emptySet()
}

/**
 * 本工程的字符串 bookId → 阅读器需要的 Long id。
 *
 * 上游用 Room 自增 Long 主键；本工程的书 id 是字符串（书籍编号 / 导出任务 id）。
 * 取 32 位哈希并掩掉符号位，保证稳定且非负——同一本书每次进阅读器得到同一个 id，
 * 书签与页进度的键因此不会漂移。
 */
fun xyBookIdOf(hostBookId: String): Long = hostBookId.hashCode().toLong() and 0xFFFFFFFFL

// ---------------------------------------------------------------------------
// 4. EPUB → NovelPageSource：复用本工程的解析管线
// ---------------------------------------------------------------------------

/** 解析结果缓存：与排版样式无关，样式重排（改字号/边距）时不必重新解包 EPUB。 */
private class ParsedEpub(
    val paragraphs: List<Paragraph>,
    val marks: List<ChapterMark>,
    val imageGroups: List<EpubImageGroup>,
)

/**
 * 把本工程解析出的 [ReaderBook] 适配成上游排版引擎 [NovelPageSource] 需要的输入。
 *
 * 为什么不直接用上游的 `NovelTextExtractor.parseEpub`：
 * 本工程的 EPUB 解析是「Rust 原生结构解析（container/OPF/nav/NCX）+ Jsoup 块解析 +
 * 失败回退 Kotlin 路径」的既有资产，阅读器换壳不应把它一并换掉。这里只做**结构映射**，
 * 解析仍走 [EpubReaderRepository]。
 *
 * 映射规则：
 * - 每个 [ReaderChapter] 产出一个 [ChapterMark]（章首段落下标）；上游据此按页区间建目录；
 * - `Heading` / `Paragraph` 一律转 [Paragraph]（上游排版引擎只认段落，标题文字不丢）；
 * - `Image` 从 EPUB 包内抽出字节，作为**整页插图**插在当前位置（[EpubImageGroup]）。
 */
class XyEpubPageSourceOpener(
    private val hostBookId: String,
    private val uri: Uri,
) {
    private var cached: ParsedEpub? = null

    /** 单张插图字节上限：超过即跳过，避免图片版 EPUB 把整包拉进内存。 */
    private val maxImageBytes = 8L * 1024L * 1024L

    /**
     * 页面源打开策略，签名与 `ReaderViewModel.sourceOpener` 对齐。
     * 解析结果按实例缓存，样式变化触发的重排只重新分页、不重新解包。
     */
    fun open(context: Context, book: BookEntity, style: NovelStyle?): PageSource {
        val parsed = cached ?: parse(context).also { cached = it }
        return NovelPageSource.open(
            context,
            parsed.paragraphs,
            parsed.marks,
            style,
            null,
            parsed.imageGroups,
        )
    }

    private fun parse(context: Context): ParsedEpub {
        val readerBook = EpubReaderRepository(context).open(hostBookId, uri)
        val paragraphs = mutableListOf<Paragraph>()
        val marks = mutableListOf<ChapterMark>()
        val imageGroups = mutableListOf<EpubImageGroup>()

        readerBook.chapters.forEachIndexed { chapterIndex, chapter ->
            // 章首标记指向本章第一段；空章也留标记，目录条目才不会少
            marks += ChapterMark(chapter.title, paragraphs.size)
            chapter.blocks.forEach { block ->
                when (block) {
                    is ReaderBlock.Heading -> paragraphs += Paragraph(block.text, chapterIndex)
                    is ReaderBlock.Paragraph -> paragraphs += Paragraph(block.text, chapterIndex)
                    is ReaderBlock.Image -> {
                        val bytes = readImageEntry(readerBook.archivePath, block.path) ?: return@forEach
                        imageGroups += EpubImageGroup(paragraphs.size, listOf(bytes))
                    }
                }
            }
        }
        return ParsedEpub(paragraphs, marks, imageGroups)
    }

    /** 从 EPUB 包内读一条图片条目；路径可能被百分号编码，两种形态都试。 */
    private fun readImageEntry(archivePath: String, entryPath: String): ByteArray? {
        if (archivePath.isBlank() || entryPath.isBlank()) return null
        val archive = File(archivePath)
        if (!archive.isFile) return null
        return runCatching {
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry(entryPath)
                    ?: zip.getEntry(decodePercent(entryPath))
                    ?: return null
                if (entry.size > maxImageBytes) return null
                zip.getInputStream(entry).use { input -> input.readBytes() }
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() && it.size.toLong() <= maxImageBytes }
    }

    private fun decodePercent(path: String): String =
        if ('%' in path) runCatching { java.net.URLDecoder.decode(path, "UTF-8") }.getOrNull() ?: path else path
}

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

/** DataStore 读流兜底：损坏/IO 异常时给出空 Preferences，而不是让阅读器直接崩。 */
private fun DataStore<Preferences>.safeData(): Flow<Preferences> = data.catch { emit(emptyPreferences()) }

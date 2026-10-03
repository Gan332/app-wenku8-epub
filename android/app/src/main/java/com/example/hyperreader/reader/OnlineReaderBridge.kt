package com.example.hyperreader.reader

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.hyperreader.Wenku8Application
import com.example.hyperreader.data.appDataStore
import com.example.hyperreader.settings.ReadingProgress
import com.example.hyperreader.settings.ReaderBackground
import com.example.hyperreader.settings.ReaderPageTurnMode
import com.example.hyperreader.settings.ReaderSettings
import com.xyreader.archive.NovelStyle
import com.xyreader.core.BookEntity
import com.xyreader.core.BookmarkEntity
import com.xyreader.core.NovelFontWeight
import com.xyreader.core.PageMode
import com.xyreader.core.ReadBackground
import com.xyreader.core.ReaderPrefs
import com.xyreader.core.ReaderRepository
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** 在线阅读进度：页码与页轴起始章、当前章必须一起保存，避免下次页码错位。 */
@Serializable
data class OnlineReaderProgress(
    val page: Int,
    val totalPages: Int,
    val startChapterId: String,
    val startChapterIndex: Int,
    val currentChapterId: String,
    val currentChapterIndex: Int,
)

internal val onlineProgressJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private fun onlineProgressKey(bookId: String) =
    stringPreferencesKey("xy_reader_online_progress_$bookId")

fun decodeOnlineReaderProgress(raw: String?): OnlineReaderProgress? = raw?.let {
    runCatching {
        onlineProgressJson.decodeFromString<OnlineReaderProgress>(it)
    }.getOrNull()
}

suspend fun readOnlineReaderProgress(data: DataStore<Preferences>, bookId: String): OnlineReaderProgress? =
    data.safeData().map { prefs -> decodeOnlineReaderProgress(prefs[onlineProgressKey(bookId)]) }.first()

data class OnlineLoadedChapter(
    val id: String,
    val index: Int,
    val startPage: Int,
    val endPageInclusive: Int,
)

fun onlineChapterAtPage(chapters: List<OnlineLoadedChapter>, page: Int): OnlineLoadedChapter? =
    chapters.firstOrNull { page >= it.startPage && page <= it.endPageInclusive } ?: chapters.firstOrNull()

/** 在线阅读专用 xy-reader 数据桥；共用配置/书签键，页进度额外保存起始章。 */
class OnlineReaderRepository(
    context: Context,
    private val book: BookEntity,
    private val hostBookId: String,
    private val bookshelfId: String,
) : ReaderRepository {
    private val app = context.applicationContext as Wenku8Application
    private val data = app.appDataStore
    private val prefsStore = XyReaderPrefsStore(data)
    private val attachedSource = MutableStateFlow<OnlinePageSource?>(null)

    override val books: StateFlow<List<BookEntity>> = MutableStateFlow(listOf(book))
    override val bookmarks: Flow<List<BookmarkEntity>> = data.safeData().map { prefs ->
        decodeBookmarks(prefs[KEY_BOOKMARKS]).filter { it.bookId == book.id }.sortedByDescending { it.createdAt }
    }
    override val readerPrefs: Flow<ReaderPrefs> = prefsStore.prefs
    override suspend fun setReaderPrefs(prefs: ReaderPrefs) = prefsStore.set(prefs)

    fun attach(pageSource: OnlinePageSource) { attachedSource.value = pageSource }
    suspend fun currentProgress(): OnlineReaderProgress? = readOnlineReaderProgress(data, hostBookId)

    suspend fun hasStoredReaderPrefs(): Boolean = prefsStore.hasStored()

    /**
     * 本章插入后的页轴位置。样式变化会关闭并重建页面源，保存的是重建前的进度；
     * 因此每次重开都按最新进度重新定位，不能复用 Activity 只读一次的旧页码。
     */
    suspend fun resolveStartChapterId(catalog: List<com.example.hyperreader.model.Chapter>): String? {
        val progress = currentProgress() ?: runCatching { legacyProgress(hostBookId) }.getOrNull()
            ?: return catalog.firstOrNull()?.id
        return when {
            progress is OnlineReaderProgress -> catalog.firstOrNull { it.id == progress.startChapterId }?.id
                ?: catalog.firstOrNull { it.id == progress.currentChapterId }?.id
                ?: catalog.firstOrNull()?.id
            else -> catalog.getOrNull((progress as ReadingProgress).chapterIndex)?.id
                ?: catalog.firstOrNull()?.id
        }
    }

    override suspend fun saveProgress(bookId: Long, page: Int, totalPages: Int) {
        val chapters = attachedSource.value?.loadedChapterProgress.orEmpty()
        val current = onlineChapterAtPage(chapters, page)
        if (current != null) {
            val start = chapters.firstOrNull() ?: current
            val progress = OnlineReaderProgress(
                page.coerceAtLeast(0), totalPages.coerceAtLeast(0),
                start.id, start.index, current.id, current.index,
            )
            data.edit { prefs ->
                prefs[onlineProgressKey(hostBookId)] =
                    onlineProgressJson.encodeToString<OnlineReaderProgress>(progress)
            }
        }
        runCatching { app.bookshelfRepository.recordRead(bookshelfId) }
    }

    override suspend fun toggleFavorite(bookId: Long) {
        data.edit { prefs ->
            val current = decodeFavorites(prefs[KEY_FAVORITES])
            val next = if (hostBookId in current) current - hostBookId else current + hostBookId
            prefs[KEY_FAVORITES] = onlineProgressJson.encodeToString(SetSerializer(String.serializer()), next)
        }
    }

    override suspend fun addBookmark(bookId: Long, pageIndex: Int) {
        data.edit { prefs ->
            val current = decodeBookmarks(prefs[KEY_BOOKMARKS])
            val nextId = (current.maxOfOrNull { it.id } ?: 0L) + 1L
            val next = current + BookmarkEntity(nextId, book.id, pageIndex, System.currentTimeMillis())
            prefs[KEY_BOOKMARKS] =
                onlineProgressJson.encodeToString(ListSerializer(BookmarkEntity.serializer()), next)
        }
    }

    override suspend fun removeBookmark(bookmarkId: Long) {
        data.edit { prefs ->
            val next = decodeBookmarks(prefs[KEY_BOOKMARKS]).filterNot { it.id == bookmarkId }
            prefs[KEY_BOOKMARKS] =
                onlineProgressJson.encodeToString(ListSerializer(BookmarkEntity.serializer()), next)
        }
    }

    suspend fun legacyProgress(bookId: String): ReadingProgress? =
        app.settingsRepository.progress(bookId).first()

    suspend fun importLegacySettings(settings: ReaderSettings) {
        prefsStore.set(
            ReaderPrefs(
                pageMode = when (settings.pageTurnMode) {
                    ReaderPageTurnMode.HORIZONTAL -> PageMode.LEFT_RIGHT
                    ReaderPageTurnMode.VERTICAL -> PageMode.UP_DOWN
                },
                keepScreenOn = settings.keepScreenOn,
                readBackground = when (settings.background) {
                    ReaderBackground.PAPER, ReaderBackground.LIGHT -> ReadBackground.WHITE
                    ReaderBackground.GREEN, ReaderBackground.CUSTOM -> ReadBackground.SEPIA
                    ReaderBackground.DARK, ReaderBackground.OLED -> ReadBackground.BLACK
                },
                novelFontSizeSp = settings.fontSizeSp,
                novelFontWeight = if (settings.fontWeight >= 600) NovelFontWeight.BOLD else NovelFontWeight.NORMAL,
                novelLineSpacingMultiplier = settings.lineHeight,
                novelMarginLeftPx = settings.horizontalPaddingDp * app.resources.displayMetrics.density,
                novelMarginRightPx = settings.horizontalPaddingDp * app.resources.displayMetrics.density,
                novelCustomFont = settings.fontUri?.let { File(it).name }?.takeIf(String::isNotBlank),
            ),
        )
    }

    private fun decodeBookmarks(raw: String?): List<BookmarkEntity> = raw?.let {
        runCatching { onlineProgressJson.decodeFromString(ListSerializer(BookmarkEntity.serializer()), it) }.getOrNull()
    } ?: emptyList()

    private fun decodeFavorites(raw: String?): Set<String> = raw?.let {
        runCatching { onlineProgressJson.decodeFromString(SetSerializer(String.serializer()), it) }.getOrNull()
    } ?: emptySet()
}

/** 同步 sourceOpener 的在线适配器；目录与起始章在 Activity 中预先加载。 */
class OnlinePageSourceOpener(
    private val source: OnlineReaderSource,
    private val app: Wenku8Application,
    private val bookId: String,
) {
    @Volatile
    private var catalog: List<com.example.hyperreader.model.Chapter> = emptyList()
    private val loaded = java.util.concurrent.ConcurrentHashMap<String, ReaderChapter>()
    private val imageBytes = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** 本次阅读会话的起始章；页进度和旧章进度都先归一到这里，避免页码跨页轴复用。 */
    @Volatile
    private var startChapterId: String? = null

    fun setStartChapterId(chapterId: String?) {
        startChapterId = chapterId
        loaded.clear()
        imageBytes.clear()
    }

    suspend fun prepare(catalog: List<com.example.hyperreader.model.Chapter>) {
        this.catalog = catalog
        loaded.clear()
        imageBytes.clear()
    }

    fun open(c: Context, book: BookEntity, style: NovelStyle?): OnlinePageSource = kotlinx.coroutines.runBlocking {
        if (catalog.isEmpty()) throw java.io.IOException("目录尚未加载")
        val startId = startChapterId ?: catalog.firstOrNull()?.id
        OnlinePageSource.open(
            context = c,
            catalog = catalog,
            startChapterId = startId,
            style = style,
            fetchContent = { chapter -> fetch(chapter) },
        )
    }

    private suspend fun fetch(chapter: com.example.hyperreader.model.Chapter): OnlineChapterContent {
        val result = loaded[chapter.id] ?: when (val value = source.loadChapter(bookId, chapter, catalog)) {
            is OnlineReaderResult.Ready -> value.value.also { loaded[chapter.id] = it }
            is OnlineReaderResult.NeedsLogin -> throw java.io.IOException(value.message)
            is OnlineReaderResult.Failed -> throw java.io.IOException(value.message)
        }
        val paragraphs = mutableListOf<String>()
        val images = mutableListOf<Pair<Int, ByteArray>>()
        result.blocks.forEach { block ->
            when (block) {
                is ReaderBlock.Heading -> block.text.trim().takeIf(String::isNotBlank)?.let(paragraphs::add)
                is ReaderBlock.Paragraph -> block.text.trim().takeIf(String::isNotBlank)?.let(paragraphs::add)
                is ReaderBlock.Image -> imageBytes[block.path]?.let { bytes ->
                    images += paragraphs.size to bytes
                } ?: runCatching {
                    app.coverRepository.loadBytes(block.path)?.also { bytes ->
                        imageBytes[block.path] = bytes
                        images += paragraphs.size to bytes
                    }
                }
            }
        }
        return OnlineChapterContent(
            paragraphs,
            images.filter { (index, bytes) -> index in 0..paragraphs.size && bytes.isNotEmpty() },
        )
    }
}

/** 一章的排版输入：纯文本段落 + 按段落流位置插入的整页插图字节。 */
data class OnlineChapterContent(
    val paragraphs: List<String>,
    val images: List<Pair<Int, ByteArray>> = emptyList(),
)

private val KEY_BOOKMARKS = stringPreferencesKey("xy_reader_bookmarks")
private val KEY_FAVORITES = stringPreferencesKey("xy_reader_favorites")

private fun DataStore<Preferences>.safeData(): Flow<Preferences> =
    data.catch { emit(emptyPreferences()) }

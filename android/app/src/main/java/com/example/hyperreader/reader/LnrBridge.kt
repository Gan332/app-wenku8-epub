package com.example.hyperreader.reader

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.hyperreader.Wenku8Application
import com.example.hyperreader.data.appDataStore
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.content.component.data.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.data.ParagraphComponentData
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.text.ParagraphNode
import io.nightfish.lightnovelreader.api.text.TextNode
import io.nightfish.lightnovelreader.api.userdata.BooleanUserData
import io.nightfish.lightnovelreader.api.userdata.ColorUserData
import io.nightfish.lightnovelreader.api.userdata.FloatUserData
import io.nightfish.lightnovelreader.api.userdata.IntListUserData
import io.nightfish.lightnovelreader.api.userdata.IntUserData
import io.nightfish.lightnovelreader.api.userdata.StringListUserData
import io.nightfish.lightnovelreader.api.userdata.StringUserData
import io.nightfish.lightnovelreader.api.userdata.UriUserData
import io.nightfish.lightnovelreader.api.userdata.UserDataDaoApi
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.statistics.ReadingStatsUpdate
import indi.dmzz_yyhyy.lightnovelreader.data.statistics.StatsRepository
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * LNR 阅读器的**数据桥**（P3）：把本工程既有数据层适配到 LNR reader 的四个接口。
 *
 * 全部实现遵守 AGENTS 硬约束：
 * - **单一 `preferencesDataStore("wenku8_settings")`**（不新建 DataStore 实例），
 *   以 `lnr_userdata_*` / `lnr_reading_*` 键前缀与既有键隔离；
 * - **不引入 Room / Coil / WorkManager**：统计走 `ReadingStatsRepository`、
 *   图片字节走 zip 解包落 `cacheDir/lnr-images`、进度手写 JSON 存同一 DataStore；
 * - 接口的 `priority` 参数显式忽略（限流由 `HttpRateLimiter.Mode` 决定，见接口注释）。
 */

// ---------------------------------------------------------------------------
// 1. UserDataDaoApi → DataStore：SettingState 的 8 类 UserData 都经此读写
// ---------------------------------------------------------------------------

class DataStoreUserDataDao(private val data: DataStore<Preferences>) : UserDataDaoApi {

    private fun key(path: String) = stringPreferencesKey("lnr_userdata_$path")

    override suspend fun insert(path: String, group: String, type: String, value: String) {
        data.edit { prefs -> prefs[key(path)] = value }
    }

    override suspend fun get(path: String): String? = data.data.map { prefs -> prefs[key(path)] }.first()

    override fun getFlow(path: String): Flow<String?> = data.data.map { prefs -> prefs[key(path)] }

    override suspend fun remove(path: String) {
        data.edit { prefs -> prefs.remove(key(path)) }
    }
}

/** 8 类 UserData 工厂：LNR 包装类原样保留，仅把底层从 Room DAO 换成本 DataStore dao。 */
class LnrUserDataRepository(private val dao: UserDataDaoApi) : UserDataRepositoryApi {
    override fun stringUserData(path: String): StringUserData = StringUserData(path, dao)
    override fun floatUserData(path: String): FloatUserData = FloatUserData(path, dao)
    override fun intUserData(path: String): IntUserData = IntUserData(path, dao)
    override fun booleanUserData(path: String): BooleanUserData = BooleanUserData(path, dao)
    override fun intListUserData(path: String): IntListUserData = IntListUserData(path, dao)
    override fun stringListUserData(path: String): StringListUserData = StringListUserData(path, dao)
    override fun colorUserData(path: String): ColorUserData = ColorUserData(path, dao)
    override fun uriUserData(path: String): UriUserData = UriUserData(path, dao)
    override suspend fun remove(path: String) = dao.remove(path)
}

// ---------------------------------------------------------------------------
// 2. BookRepository → ReaderBook：卷结构、章节组件 JSON、断点进度
// ---------------------------------------------------------------------------

/**
 * 内容与进度桥：
 * - 卷结构：`ReaderBook.chapters` 收敛为单卷（章节顺序即阅读顺序）；
 * - 章节内容：`ReaderBlock` → `:api` 组件 JSON（`ChapterContent.content`），
 *   id 直接取组件自身 `Identifier.toString()`，与 `ContentComponentRepository`
 *   注册表的 key 严格一致（不手写前缀，避免 app id 不匹配导致全部渲染成 Error）；
 * - 断点进度：`UserReadingData` 无 `@Serializable`，手写 JSON 存同一 DataStore。
 *
 * @param currentBook 当前打开的书，由入口装配处提供；null 时返回可诊断的 Err。
 */
class LnrBookRepository(
    context: Context,
    private val currentBook: () -> ReaderBook?,
) : BookRepository {

    private val data = context.applicationContext.appDataStore
    private val imageCacheDir =
        File(context.applicationContext.cacheDir, "lnr-images").apply { mkdirs() }

    override fun getBookVolumesFlow(
        id: String,
        priority: WebDataSourcePriority,
    ): Flow<Result<BookVolumes, WebRequestError>> = flow {
        val book = currentBook()
        if (book == null) {
            emit(Err(WebRequestError("书籍未打开", "ReaderBook 尚未装载，无法提供卷结构")))
            return@flow
        }
        val volumes = listOf(
            Volume(
                volumeId = MAIN_VOLUME_ID,
                volumeTitle = book.title,
                chapters = book.chapters.map { ChapterInformation(it.id, it.title) },
            ),
        )
        emit(Ok(BookVolumes(id, volumes)))
    }.flowOn(Dispatchers.IO)

    override fun getChapterContentFlow(
        chapterId: String,
        bookId: String,
        priority: WebDataSourcePriority,
    ): Flow<Result<ChapterContent, WebRequestError>> = flow {
        val book = currentBook()
        if (book == null) {
            emit(Err(WebRequestError("书籍未打开", "ReaderBook 尚未装载")))
            return@flow
        }
        val index = book.chapters.indexOfFirst { it.id == chapterId }
        if (index < 0) {
            emit(Err(WebRequestError("章节不存在", "chapterId=$chapterId")))
            return@flow
        }
        emit(Ok(buildChapterContent(book, index)))
    }.flowOn(Dispatchers.IO)

    override suspend fun preloadChapterContent(
        chapterId: String,
        bookId: String,
        priority: WebDataSourcePriority,
    ) {
        // EPUB 条目已在内存映射内、在线源按需经限流客户端拉取 —— 无可预取之物，保留接口位。
    }

    // ---- 断点进度（UserReadingData 无 @Serializable，手写 JSON 存取） ----

    override suspend fun getUserReadingData(bookId: String): UserReadingData {
        val raw = data.data.map { prefs -> prefs[readingKey(bookId)] }.first()
            ?: return UserReadingData(id = bookId)
        return runCatching { decodeReadingData(bookId, raw) }.getOrElse { UserReadingData(id = bookId) }
    }

    override suspend fun updateUserReadingData(
        id: String,
        update: (UserReadingData) -> UserReadingData,
    ) {
        val next = update(getUserReadingData(id))
        data.edit { prefs -> prefs[readingKey(id)] = encodeReadingData(next) }
    }

    private fun readingKey(bookId: String) = stringPreferencesKey("lnr_reading_$bookId")

    private fun encodeReadingData(d: UserReadingData): String = buildJsonObject {
        d.lastReadTime?.let { put("lastReadTime", JsonPrimitive(it.format(ISO_LOCAL_DATE_TIME))) }
        d.lastReadChapterId?.let { put("lastReadChapterId", JsonPrimitive(it)) }
        d.lastReadChapterTitle?.let { put("lastReadChapterTitle", JsonPrimitive(it)) }
        put("totalReadTime", JsonPrimitive(d.totalReadTime))
        put("readingProgress", JsonPrimitive(d.readingProgress))
        put("currentChapterReadingProgressMap", buildJsonObject {
            d.currentChapterReadingProgressMap.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        })
        put("maxChapterReadingProgressMap", buildJsonObject {
            d.maxChapterReadingProgressMap.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        })
    }.toString()

    private fun decodeReadingData(bookId: String, raw: String): UserReadingData {
        val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return UserReadingData(id = bookId)
        fun stringOf(name: String): String? = (obj[name] as? JsonPrimitive)?.content
        fun floatOf(name: String): Float? = stringOf(name)?.toFloatOrNull()
        fun mapOf(name: String): Map<String, Float> =
            (obj[name] as? JsonObject)?.entries
                ?.associate { it.key to (it.value as JsonPrimitive).content.toFloat() }
                ?: emptyMap()
        return UserReadingData(
            id = bookId,
            lastReadTime = stringOf("lastReadTime")
                ?.let { runCatching { LocalDateTime.parse(it, ISO_LOCAL_DATE_TIME) }.getOrNull() },
            totalReadTime = stringOf("totalReadTime")?.toIntOrNull() ?: 0,
            readingProgress = floatOf("readingProgress") ?: 0f,
            lastReadChapterId = stringOf("lastReadChapterId"),
            lastReadChapterTitle = stringOf("lastReadChapterTitle"),
            currentChapterReadingProgressMap = mapOf("currentChapterReadingProgressMap"),
            maxChapterReadingProgressMap = mapOf("maxChapterReadingProgressMap"),
        )
    }

    // ---- 章节内容 → 组件 JSON ----

    private fun buildChapterContent(book: ReaderBook, index: Int): ChapterContent {
        val chapter = book.chapters[index]
        val prev = book.chapters.getOrNull(index - 1)?.id
        val next = book.chapters.getOrNull(index + 1)?.id
        val components = buildJsonArray {
            for (block in chapter.blocks) {
                when (block) {
                    // LNR 组件体系没有「标题」组件：转段落保留文字，内容不丢
                    is ReaderBlock.Heading -> add(paragraphComponent(listOf(block.text)))
                    is ReaderBlock.Paragraph -> add(paragraphComponent(listOf(block.text)))
                    is ReaderBlock.Image -> {
                        val uri = resolveImageUri(book, block.path)
                        if (uri != null) {
                            add(
                                componentJson(
                                    ImageComponentData.id.toString(),
                                    ImageComponentData.serializer(),
                                    ImageComponentData(uri),
                                ),
                            )
                        }
                    }
                }
            }
            // 空章节防御：翻页渲染器对空组件列表不友好，给占位段落
            if (isEmpty()) add(paragraphComponent(listOf(chapter.title)))
        }
        return ChapterContent(
            id = chapter.id,
            title = chapter.title,
            content = buildJsonObject { put("components", components) },
            prevChapter = prev,
            nextChapter = next,
        )
    }

    private fun paragraphComponent(texts: List<String>): JsonObject = componentJson(
        ParagraphComponentData.id.toString(),
        ParagraphComponentData.serializer(),
        ParagraphComponentData(paragraph = ParagraphNode(texts.map { TextNode(it) })),
    )

    private fun <T> componentJson(id: String, serializer: KSerializer<T>, data: T): JsonObject =
        buildJsonObject {
            // id 取组件自身 Identifier 字符串，与 ContentComponentRepository 注册表 key 严格一致
            put("id", JsonPrimitive(id))
            put("data", Json.encodeToJsonElement(serializer, data))
        }

    /**
     * 图片 URI 解析（`ReaderImageLoader` 注释约定的 P3 职责）：
     * - `http(s)` → 交给 CoverRepository 限流链；
     * - zip 内条目 → 解出字节落 `cacheDir/lnr-images/`（sha1 寻址、临时文件+rename 原子写）→ `file://`。
     */
    private fun resolveImageUri(book: ReaderBook, path: String): Uri? {
        if (path.isBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return Uri.parse(path)
        val archive = book.archivePath
        if (archive.isBlank()) return null
        val ext = path.substringAfterLast('.', "jpg").take(8).ifBlank { "jpg" }
        val target = File(imageCacheDir, "${sha1Of(path)}.$ext")
        if (target.isFile && target.length() > 0) return Uri.fromFile(target)
        return runCatching {
            ZipFile(File(archive)).use { zip ->
                val entry = zip.getEntry(path) ?: zip.getEntry(decodePercent(path))
                    ?: return null
                val temp = File(imageCacheDir, "${sha1Of(path)}.tmp")
                zip.getInputStream(entry).use { input -> temp.outputStream().use { input.copyTo(it) } }
                if (!temp.renameTo(target)) {
                    target.delete()
                    temp.renameTo(target)
                }
            }
            Uri.fromFile(target)
        }.getOrNull()
    }

    private fun sha1Of(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun decodePercent(path: String): String =
        if ('%' in path) runCatching { URLDecoder.decode(path, "UTF-8") }.getOrNull() ?: path else path

    private companion object {
        const val MAIN_VOLUME_ID = "main"
        val ISO_LOCAL_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    }
}

// ---------------------------------------------------------------------------
// 3. StatsRepository：3 方法映射到 ReadingStatsRepository 的既有口径
// ---------------------------------------------------------------------------

/**
 * LNR 统计事件 → 本工程会话统计：
 * - `accumulateBookReadTime` 有真实语义（秒数累加）→ `recordSession`（`seconds<=0` 内部安全跳过）；
 * - `updateReadingStatistics` / `markBookFinished` 是 LNR 的事件流/完读率模型，
 *   与 AGENTS 4.3 的口径（前台 onStart→onStop 累计、单次 ≤30 分钟）不同源 ——
 *   本工程的阅读会话由阅读器自身记录，这里显式 no-op，避免统计双写打架。
 */
class LnrStatsRepository(context: Context) : StatsRepository {

    private val statsRepo =
        (context.applicationContext as Wenku8Application).readingStatsRepository

    override suspend fun accumulateBookReadTime(bookId: String, seconds: Int) {
        if (seconds > 0) statsRepo.recordSession(bookId, "", seconds.toLong())
    }

    override suspend fun updateReadingStatistics(update: ReadingStatsUpdate) {
        // no-op：见 KDoc
    }

    override suspend fun markBookFinished(bookId: String) {
        // no-op：见 KDoc
    }
}

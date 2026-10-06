package com.example.hyperreader.reader

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.hyperreader.data.appDataStore
import com.xyreader.core.BookmarkEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

/**
 * 跨书书签中心的数据层（0.19.0）。
 *
 * 书签本身由两个阅读器 Bridge（`XyReaderRepository` / `OnlineReaderRepository`）
 * 写入同一个 `xy_reader_bookmarks` 键（AGENTS §4.8 的 `xy_reader_` 键前缀约定不变）。
 * 本类只**读**那份数据并在需要时删除条目，不另起一套存储。
 *
 * 为什么不能按阅读器侧的 `bookId`（Long）聚合：它是 `xyBookIdOf` 的 32 位哈希，
 * 不可逆，反查不回宿主书。跨书展示改用书签自带的 `hostBookId` + `bookTitle` +
 * `bookSource`（见 `BookmarkEntity` KDoc）。
 *
 * 解码复用 [onlineProgressJson]——它与本地 Bridge 的 `xyJson` 对书签字段的行为一致
 * （`ignoreUnknownKeys` + 字段默认值），因此老数据在两边都能读出来。
 */
class BookmarkCenterRepository(context: Context) {

    private val data = context.applicationContext.appDataStore

    private val serializer = ListSerializer(BookmarkEntity.serializer())

    /** 全部书签，按添加时间倒序；损坏数据回退空列表而不是让整页崩。 */
    val bookmarks: Flow<List<BookmarkEntity>> = data.safeData().map { prefs ->
        decode(prefs[KEY_BOOKMARKS]).sortedByDescending { it.createdAt }
    }

    suspend fun remove(bookmarkId: Long) = data.edit { prefs ->
        prefs[KEY_BOOKMARKS] = onlineProgressJson.encodeToString(
            serializer,
            decode(prefs[KEY_BOOKMARKS]).filterNot { it.id == bookmarkId },
        )
    }

    /** 清空某一本书的全部书签。 */
    suspend fun removeAllForBook(hostBookId: String) = data.edit { prefs ->
        prefs[KEY_BOOKMARKS] = onlineProgressJson.encodeToString(
            serializer,
            decode(prefs[KEY_BOOKMARKS]).filterNot { it.hostBookId == hostBookId },
        )
    }

    private fun decode(raw: String?): List<BookmarkEntity> = raw?.let {
        runCatching { onlineProgressJson.decodeFromString(serializer, it) }.getOrNull()
    } ?: emptyList()

    private companion object {
        val KEY_BOOKMARKS = stringPreferencesKey("xy_reader_bookmarks")
    }
}

/** 分组键：同一本书的 hostBookId / 书名 / 来源在书签冻结时一致。 */
data class BookmarkGroupKey(val hostBookId: String, val bookTitle: String, val bookSource: String)

/** 一本书下的书签分组。 */
data class BookmarkGroup(
    val hostBookId: String,
    val bookTitle: String,
    val bookSource: String,
    val bookmarks: List<BookmarkEntity>,
    val latestAt: Long,
) {
    /** 展示用书名：老数据（0.19.0 之前写入）没有冻结书名，退回可读占位。 */
    val displayTitle: String
        get() = bookTitle.ifBlank { if (hostBookId.isBlank()) "未知书籍" else "书籍 $hostBookId" }

    /** 点击书签时是否走本地 EPUB 阅读器。 */
    val isLocal: Boolean get() = bookSource == BOOK_SOURCE_LOCAL
}

/**
 * 按书分组书签。组内与组间都按时间倒序，使列表顶部始终是最近添加的。
 *
 * 纯函数（便于单测）：不依赖 DataStore，UI 与测试都能直接构造入参调用。
 */
fun groupBookmarks(bookmarks: List<BookmarkEntity>): List<BookmarkGroup> =
    bookmarks.groupBy { BookmarkGroupKey(it.hostBookId, it.bookTitle, it.bookSource) }
        .map { (key, items) ->
            BookmarkGroup(
                hostBookId = key.hostBookId,
                bookTitle = key.bookTitle,
                bookSource = key.bookSource,
                bookmarks = items.sortedByDescending { it.createdAt },
                latestAt = items.maxOfOrNull { it.createdAt } ?: 0L,
            )
        }
        .sortedByDescending { it.latestAt }

private fun DataStore<Preferences>.safeData(): Flow<Preferences> =
    data.catch { emit(emptyPreferences()) }
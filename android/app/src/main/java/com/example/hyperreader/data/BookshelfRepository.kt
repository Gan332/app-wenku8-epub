package com.example.hyperreader.data

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.example.hyperreader.model.BookshelfEntry
import com.example.hyperreader.model.normalizeShelfTags
import com.example.hyperreader.model.withShelfTag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class BookshelfRepository(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val data = context.applicationContext.appDataStore
    private val serializer = ListSerializer(BookshelfEntry.serializer())

    /**
     * 书架条目。
     *
     * **不再在这里排序**：排序方式自 0.19.0-alpha03 起是用户设置
     * （`BookshelfSort`），由展示层用 `ui/sortBookshelf` 应用。
     * 仓库保留存储顺序，避免「设置排序」与「仓库排序」两个真相来源打架
     * （此前这里硬编码「置顶 → 最近阅读 → 标题」，用户无从选择）。
     */
    val entries: Flow<List<BookshelfEntry>> = data.safeData().map { prefs ->
        decode(prefs[BOOKSHELF])
    }

    suspend fun add(entry: BookshelfEntry) = data.edit { prefs ->
        val current = decode(prefs[BOOKSHELF]).toMutableList()
        val index = current.indexOfFirst { it.id == entry.id || it.bookId == entry.bookId }
        if (index >= 0) {
            val old = current[index]
            current[index] = entry.copy(
                isPinned = old.isPinned,
                lastReadAt = maxOf(old.lastReadAt, entry.lastReadAt),
                addedAt = minOf(old.addedAt, entry.addedAt),
                // 从探索页再次加入时保留用户已经打好的标签，不被空标签覆盖
                tags = normalizeShelfTags(old.tags + entry.tags),
            )
        } else {
            current += entry
        }
        prefs[BOOKSHELF] = json.encodeToString(serializer, current)
    }

    suspend fun remove(id: String) = data.edit { prefs ->
        prefs[BOOKSHELF] = json.encodeToString(serializer, decode(prefs[BOOKSHELF]).filterNot { it.id == id })
    }

    /** 整体替换书架（备份恢复用）。重复 id 只保留第一条。 */
    suspend fun replaceAll(entries: List<BookshelfEntry>) = data.edit { prefs ->
        prefs[BOOKSHELF] = json.encodeToString(
            serializer,
            entries.distinctBy { it.id }.map { it.copy(tags = normalizeShelfTags(it.tags)) },
        )
    }

    suspend fun setPinned(id: String, pinned: Boolean) = data.edit { prefs ->
        val next = decode(prefs[BOOKSHELF]).map { if (it.id == id) it.copy(isPinned = pinned) else it }
        prefs[BOOKSHELF] = json.encodeToString(serializer, next)
    }

    /** 覆盖一本书的标签列表（标签页的开关与删除都走这里）。 */
    suspend fun setTags(id: String, tags: List<String>) = data.edit { prefs ->
        val next = decode(prefs[BOOKSHELF]).map {
            if (it.id == id) it.copy(tags = normalizeShelfTags(tags)) else it
        }
        prefs[BOOKSHELF] = json.encodeToString(serializer, next)
    }

    /** 给一本书加标签；已存在时按规范化后的写法保留第一个。 */
    suspend fun addTag(id: String, tag: String) = data.edit { prefs ->
        val next = decode(prefs[BOOKSHELF]).map {
            if (it.id == id) it.withShelfTag(tag, enabled = true) else it
        }
        prefs[BOOKSHELF] = json.encodeToString(serializer, next)
    }

    /** 从一本书移除标签（大小写不敏感）。 */
    suspend fun removeTag(id: String, tag: String) = data.edit { prefs ->
        val next = decode(prefs[BOOKSHELF]).map {
            if (it.id == id) it.withShelfTag(tag, enabled = false) else it
        }
        prefs[BOOKSHELF] = json.encodeToString(serializer, next)
    }

    suspend fun recordRead(id: String, timestamp: Long = System.currentTimeMillis()) = data.edit { prefs ->
        val next = decode(prefs[BOOKSHELF]).map {
            if (it.id == id) {
                // 打开书即视为「已看过当前所有章节」：把基线抬到最近一次检查到的章数，
                // 清掉「+N 新章」角标。不这么做的话角标会一直挂着直到下次检查。
                it.copy(
                    lastReadAt = timestamp,
                    chapterCount = maxOf(it.chapterCount, it.lastCheckedChapterCount),
                )
            } else {
                it
            }
        }
        prefs[BOOKSHELF] = json.encodeToString(serializer, next)
    }

    /**
     * 写入一次更新检查的结果。
     *
     * [adoptAsBaseline] 用于 `chapterCount` 未知（0）的老条目：首次检查时把它当作基线，
     * 否则会把「全书都是新章」误报成几百章未读。
     */
    suspend fun recordUpdateCheck(
        id: String,
        serverChapterCount: Int,
        latestChapter: String,
        checkedAt: Long = System.currentTimeMillis(),
        adoptAsBaseline: Boolean = false,
    ) = data.edit { prefs ->
        val next = decode(prefs[BOOKSHELF]).map { entry ->
            if (entry.id != id) {
                entry
            } else {
                entry.copy(
                    lastCheckedChapterCount = serverChapterCount,
                    latestChapter = latestChapter,
                    lastCheckedAt = checkedAt,
                    chapterCount = if (adoptAsBaseline) serverChapterCount else entry.chapterCount,
                )
            }
        }
        prefs[BOOKSHELF] = json.encodeToString(serializer, next)
    }

    private fun decode(value: String?): List<BookshelfEntry> = runCatching {
        if (value.isNullOrBlank()) emptyList() else json.decodeFromString(serializer, value)
    }.getOrDefault(emptyList())

    private companion object {
        val BOOKSHELF = stringPreferencesKey("bookshelf_entries")
    }
}

private fun androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>.safeData() = data.catch { emit(emptyPreferences()) }

package com.example.hyperreader.data

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.example.hyperreader.model.BookshelfEntry
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
            )
        } else {
            current += entry
        }
        prefs[BOOKSHELF] = json.encodeToString(serializer, current)
    }

    suspend fun remove(id: String) = data.edit { prefs ->
        prefs[BOOKSHELF] = json.encodeToString(serializer, decode(prefs[BOOKSHELF]).filterNot { it.id == id })
    }

    suspend fun setPinned(id: String, pinned: Boolean) = data.edit { prefs ->
        val next = decode(prefs[BOOKSHELF]).map { if (it.id == id) it.copy(isPinned = pinned) else it }
        prefs[BOOKSHELF] = json.encodeToString(serializer, next)
    }

    suspend fun recordRead(id: String, timestamp: Long = System.currentTimeMillis()) = data.edit { prefs ->
        val next = decode(prefs[BOOKSHELF]).map { if (it.id == id) it.copy(lastReadAt = timestamp) else it }
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

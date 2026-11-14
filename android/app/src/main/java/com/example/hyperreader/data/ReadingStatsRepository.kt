package com.example.hyperreader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.hyperreader.model.ReadingSession
import com.example.hyperreader.model.ReadingStats
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class ReadingStatsRepository(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val data = context.applicationContext.appDataStore

    /**
     * 阅读统计。**每次读取都按当前日期重算派生字段**
     * （`todaySeconds` / `currentStreak` / `longestStreak`，见 [withCurrentDay]）：
     * 否则隔天打开应用、还没读书时「今日」会显示昨天的时长。
     */
    val stats: Flow<ReadingStats> = data.safeData().map { prefs -> decode(prefs[STATS]).withCurrentDay() }

    suspend fun recordSession(bookId: String, bookTitle: String, seconds: Long, now: Long = System.currentTimeMillis()) {
        if (seconds <= 0) return
        data.edit { prefs ->
            val current = decode(prefs[STATS])
            val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
            val daily = current.dailySeconds.toMutableMap().apply { this[day] = (this[day] ?: 0L) + seconds }
            val book = current.bookSeconds.toMutableMap().apply { this[bookId] = (this[bookId] ?: 0L) + seconds }
            val titles = current.bookTitles.toMutableMap().apply { if (bookTitle.isNotBlank()) this[bookId] = bookTitle }
            val sessions = appendReadingSession(
                current.recentSessions,
                ReadingSession(bookId = bookId, title = bookTitle, seconds = seconds, endedAt = now),
            )
            // todaySeconds / currentStreak 是派生值，**不在这里预存**：
            // 存下来就会停在写入那一刻，直到下次阅读才刷新（见 withCurrentDay 的说明）。
            val updated = current.copy(
                totalSeconds = current.totalSeconds + seconds,
                totalSessions = current.totalSessions + 1,
                lastReadAt = now,
                dailySeconds = daily,
                bookSeconds = book,
                bookTitles = titles,
                recentSessions = sessions,
            )
            prefs[STATS] = json.encodeToString(ReadingStats.serializer(), updated)
        }
    }

    suspend fun clear() = data.edit { it.remove(STATS) }

    /** 备份用：当前统计快照（含派生字段，恢复时再按当天重算）。 */
    suspend fun snapshot(): ReadingStats = stats.first()

    /** 恢复备份：整份覆盖统计。 */
    suspend fun replace(stats: ReadingStats) = data.edit {
        it[STATS] = json.encodeToString(ReadingStats.serializer(), stats)
    }

    private fun decode(value: String?): ReadingStats = runCatching {
        if (value.isNullOrBlank()) ReadingStats() else json.decodeFromString(ReadingStats.serializer(), value)
    }.getOrDefault(ReadingStats())

    private companion object {
        val STATS = stringPreferencesKey("reading_stats")
    }
}

private fun androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>.safeData() = data.catch { emit(emptyPreferences()) }

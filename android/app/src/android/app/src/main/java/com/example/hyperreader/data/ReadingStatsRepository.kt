package com.example.hyperreader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.hyperreader.model.ReadingStats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class ReadingStatsRepository(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val data = context.applicationContext.appDataStore

    val stats: Flow<ReadingStats> = data.safeData().map { prefs -> decode(prefs[STATS]) }

    suspend fun recordSession(bookId: String, bookTitle: String, seconds: Long, now: Long = System.currentTimeMillis()) {
        if (seconds <= 0) return
        data.edit { prefs ->
            val current = decode(prefs[STATS])
            val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
            val daily = current.dailySeconds.toMutableMap().apply { this[day] = (this[day] ?: 0L) + seconds }
            val book = current.bookSeconds.toMutableMap().apply { this[bookId] = (this[bookId] ?: 0L) + seconds }
            val titles = current.bookTitles.toMutableMap().apply { if (bookTitle.isNotBlank()) this[bookId] = bookTitle }
            val total = current.totalSeconds + seconds
            val today = daily[day] ?: 0L
            val updated = current.copy(
                totalSeconds = total,
                todaySeconds = today,
                currentStreak = streak(daily.keys),
                longestStreak = maxOf(current.longestStreak, streak(daily.keys)),
                totalSessions = current.totalSessions + 1,
                lastReadAt = now,
                dailySeconds = daily,
                bookSeconds = book,
                bookTitles = titles,
            )
            prefs[STATS] = json.encodeToString(ReadingStats.serializer(), updated)
        }
    }

    suspend fun clear() = data.edit { it.remove(STATS) }

    private fun decode(value: String?): ReadingStats = runCatching {
        if (value.isNullOrBlank()) ReadingStats() else json.decodeFromString(ReadingStats.serializer(), value)
    }.getOrDefault(ReadingStats())

    private fun streak(days: Set<String>): Int {
        val date = LocalDate.now()
        var cursor = if (days.contains(date.toString())) date else date.minusDays(1)
        var count = 0
        while (days.contains(cursor.toString())) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    private companion object {
        val STATS = stringPreferencesKey("reading_stats")
    }
}

private fun androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>.safeData() = data.catch { emit(emptyPreferences()) }

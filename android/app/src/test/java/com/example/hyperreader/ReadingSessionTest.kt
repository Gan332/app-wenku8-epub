package com.example.hyperreader

import com.example.hyperreader.data.appendReadingSession
import com.example.hyperreader.model.BookshelfEntry
import com.example.hyperreader.model.ReadingSession
import com.example.hyperreader.model.ReadingStats
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingSessionTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun appendingSessionKeepsNewestAndRespectsLimit() {
        val sessions = (1..5).map {
            ReadingSession(bookId = "b$it", title = "书 $it", seconds = it.toLong(), endedAt = it.toLong())
        }
        val result = appendReadingSession(sessions.take(2), sessions[2], limit = 3)
        assertEquals(listOf("b1", "b2", "b3"), result.map { it.bookId })

        val capped = appendReadingSession(result, sessions[3], limit = 3)
        assertEquals(listOf("b2", "b3", "b4"), capped.map { it.bookId })
    }

    @Test
    fun zeroLimitDropsEverything() {
        val session = ReadingSession(bookId = "b1", seconds = 10, endedAt = 1)
        assertTrue(appendReadingSession(listOf(session), session, limit = 0).isEmpty())
    }

    @Test
    fun oldStatsJsonWithoutSessionsStillDecodes() {
        val old = """{"totalSeconds":120,"dailySeconds":{"2026-11-14":120}}"""
        val stats = json.decodeFromString(ReadingStats.serializer(), old)
        assertEquals(120L, stats.totalSeconds)
        assertTrue(stats.recentSessions.isEmpty())
    }

    @Test
    fun oldBookshelfEntryJsonWithoutTagsStillDecodes() {
        val old = """{"id":"shelf-1","bookId":"2835","title":"测试书"}"""
        val entry = json.decodeFromString(BookshelfEntry.serializer(), old)
        assertEquals("测试书", entry.title)
        assertTrue(entry.tags.isEmpty())
    }

    @Test
    fun roundTripKeepsTagsAndRecentSessions() {
        val stats = ReadingStats(
            totalSeconds = 600,
            recentSessions = listOf(
                ReadingSession(bookId = "b1", title = "书 1", seconds = 300, endedAt = 1_700_000_000_000),
            ),
        )
        val restored = json.decodeFromString(ReadingStats.serializer(), json.encodeToString(ReadingStats.serializer(), stats))
        assertEquals(stats.recentSessions, restored.recentSessions)

        val entry = BookshelfEntry(id = "s", bookId = "b", title = "t", tags = listOf("战斗", "日常"))
        val restoredEntry = json.decodeFromString(BookshelfEntry.serializer(), json.encodeToString(BookshelfEntry.serializer(), entry))
        assertEquals(entry.tags, restoredEntry.tags)
    }
}

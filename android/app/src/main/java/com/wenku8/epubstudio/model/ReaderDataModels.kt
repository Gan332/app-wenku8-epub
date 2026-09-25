package com.wenku8.epubstudio.model

import kotlinx.serialization.Serializable

@Serializable
enum class BookshelfSource { WENKU8, LOCAL_EPUB }

@Serializable
data class BookshelfEntry(
    val id: String,
    val bookId: String,
    val title: String,
    val author: String = "未知作者",
    val source: BookshelfSource = BookshelfSource.WENKU8,
    val sourceUrl: String = "",
    val localUri: String? = null,
    val coverUrl: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = 0L,
    val chapterCount: Int = 0,
    val wordCount: Long? = null,
    val isPinned: Boolean = false,
)

@Serializable
data class ReadingStats(
    val totalSeconds: Long = 0L,
    val todaySeconds: Long = 0L,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val totalSessions: Int = 0,
    val lastReadAt: Long = 0L,
    val dailySeconds: Map<String, Long> = emptyMap(),
    val bookSeconds: Map<String, Long> = emptyMap(),
    val bookTitles: Map<String, String> = emptyMap(),
)

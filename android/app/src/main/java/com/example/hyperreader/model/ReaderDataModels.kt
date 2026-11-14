package com.example.hyperreader.model

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
    /** 最近一次更新检查时源站返回的章节总数（0 = 尚未检查过）。 */
    val lastCheckedChapterCount: Int = 0,
    /** 最近一次检查时的最新章节标题（书架展示用）。 */
    val latestChapter: String = "",
    /** 最近一次检查的时间戳；用于跳过刚检查过的书。 */
    val lastCheckedAt: Long = 0L,
    /** 用户自定义标签；由 `normalizeShelfTags` 去重、限长后写入。 */
    val tags: List<String> = emptyList(),
) {
    /**
     * 未读新章节数。
     *
     * 口径：`lastCheckedChapterCount`（源站当前章数）减去 `chapterCount`（用户上次打开时的章数）。
     * 打开书时 [com.example.hyperreader.data.BookshelfRepository.recordRead] 会把基线抬高到
     * 已检查章数，因此「新章」只在**检查到之后、打开之前**为正数，不需要额外的已读表。
     */
    val unreadChapters: Int get() = (lastCheckedChapterCount - chapterCount).coerceAtLeast(0)

    val hasUnreadChapters: Boolean get() = unreadChapters > 0

    /** 远程 Wenku8 书才能检查更新；本地 EPUB 没有可比的源站目录。 */
    val isRemoteSource: Boolean get() = localUri == null && bookId.isNotBlank()
}

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
    /** 最近结束的阅读会话，仅供统计页展示；上限见 `MAX_RECENT_READING_SESSIONS`。 */
    val recentSessions: List<ReadingSession> = emptyList(),
)

@Serializable
data class ReadingSession(
    val bookId: String,
    val title: String = "",
    val seconds: Long,
    val endedAt: Long,
)

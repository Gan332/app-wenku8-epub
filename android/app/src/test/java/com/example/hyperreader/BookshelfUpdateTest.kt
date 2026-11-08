package com.example.hyperreader

import com.example.hyperreader.core.applyUpdate
import com.example.hyperreader.core.entriesDueForCheck
import com.example.hyperreader.model.BookshelfEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 书架更新检查的纯逻辑：未读口径、首次基线、检查节流。 */
class BookshelfUpdateTest {

    private fun entry(
        id: String = "a",
        bookId: String = "2835",
        chapterCount: Int = 0,
        lastChecked: Int = 0,
        lastCheckedAt: Long = 0L,
        localUri: String? = null,
    ) = BookshelfEntry(
        id = id,
        bookId = bookId,
        title = "书 $id",
        chapterCount = chapterCount,
        lastCheckedChapterCount = lastChecked,
        lastCheckedAt = lastCheckedAt,
        localUri = localUri,
    )

    @Test
    fun unreadChaptersNeverGoNegative() {
        assertEquals(0, entry(chapterCount = 120, lastChecked = 120).unreadChapters)
        assertEquals(0, entry(chapterCount = 120, lastChecked = 100).unreadChapters)
        assertEquals(3, entry(chapterCount = 100, lastChecked = 103).unreadChapters)
        assertTrue(entry(chapterCount = 100, lastChecked = 103).hasUnreadChapters)
    }

    @Test
    fun firstCheckAdoptsServerCountAsBaseline() {
        // 老条目 chapterCount = 0：首次检查不能把整本书报成「全部未读」。
        val updated = applyUpdate(
            entry = entry(chapterCount = 0),
            serverChapterCount = 320,
            latestChapter = "第320章",
            checkedAt = 1_000L,
            adoptAsBaseline = true,
        )
        assertEquals(320, updated.chapterCount)
        assertEquals(320, updated.lastCheckedChapterCount)
        assertEquals("第320章", updated.latestChapter)
        assertEquals(0, updated.unreadChapters)
    }

    @Test
    fun knownBaselineReportsOnlyNewChapters() {
        val updated = applyUpdate(
            entry = entry(chapterCount = 100, lastChecked = 100),
            serverChapterCount = 104,
            latestChapter = "第104章",
            checkedAt = 1_000L,
            adoptAsBaseline = false,
        )
        assertEquals(100, updated.chapterCount)
        assertEquals(4, updated.unreadChapters)
    }

    @Test
    fun openingTheBookClearsUnreadWithoutAnExtraCheck() {
        val checked = entry(chapterCount = 100, lastChecked = 104)
        // recordRead 的等价写入：把基线抬到已检查章数。
        val opened = checked.copy(chapterCount = maxOf(checked.chapterCount, checked.lastCheckedChapterCount))
        assertEquals(0, opened.unreadChapters)
    }

    @Test
    fun dueForCheckSkipsLocalAndRecentlyCheckedBooks() {
        val now = 100L * 60 * 60 * 1000
        val due = entriesDueForCheck(
            listOf(
                entry(id = "never", lastCheckedAt = 0L),
                entry(id = "stale", lastCheckedAt = now - 13L * 60 * 60 * 1000),
                entry(id = "fresh", lastCheckedAt = now - 1L * 60 * 60 * 1000),
                entry(id = "local", localUri = "content://book", bookId = ""),
            ),
            now = now,
        )
        assertEquals(listOf("never", "stale"), due.map { it.id })
    }

    @Test
    fun remoteSourceFlagExcludesLocalBooks() {
        assertTrue(entry().isRemoteSource)
        assertFalse(entry(localUri = "content://book", bookId = "").isRemoteSource)
    }
}

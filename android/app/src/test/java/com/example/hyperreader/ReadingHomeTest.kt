package com.example.hyperreader

import com.example.hyperreader.settings.ReadingProgress
import com.example.hyperreader.ui.readingPercent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 阅读首页进度百分比（`readingPercent`）的回归守卫。
 *
 * 这段逻辑原先直接埋在 Composable 的插值表达式里，改版时只能靠肉眼确认；
 * 两个边界（刚开始读、章节总数未知）都曾把「读了一点」显示成 0% 或 100%。
 */
class ReadingHomeTest {

    private fun progress(chapterIndex: Int) = ReadingProgress(
        bookId = "book-a",
        chapterId = "chapter-$chapterIndex",
        chapterIndex = chapterIndex,
    )

    @Test
    fun percentIsZeroWithoutProgress() {
        assertEquals(0, readingPercent(null, chapterCount = 100))
    }

    @Test
    fun percentNeverShowsZeroForAStartedBook() {
        assertEquals(1, readingPercent(progress(chapterIndex = 0), chapterCount = 100))
    }

    @Test
    fun percentUsesChapterPosition() {
        assertEquals(50, readingPercent(progress(chapterIndex = 49), chapterCount = 100))
        assertEquals(100, readingPercent(progress(chapterIndex = 99), chapterCount = 100))
    }

    @Test
    fun percentHandlesUnknownAndStaleChapterCounts() {
        // 章节总数还没抓到时不能算成 100%（会被误读为已读完）。
        assertEquals(1, readingPercent(progress(chapterIndex = 0), chapterCount = 0))
        // 索引里的章节数落后于实际进度时，进度按兜底总数封顶到 100%。
        assertEquals(100, readingPercent(progress(chapterIndex = 199), chapterCount = 100))
    }
}

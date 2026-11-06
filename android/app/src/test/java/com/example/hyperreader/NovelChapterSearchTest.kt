package com.example.hyperreader

import com.xyreader.reader.searchNovelChapters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelChapterSearchTest {
    private val chapters = listOf(
        "第一章" to listOf("开场白", "这里没有目标词"),
        "" to listOf("alpha beta", "gamma TARGET delta"),
        "第三章" to listOf("结尾"),
    )

    @Test
    fun hitsCarryChapterIndexTitleAndOffset() {
        val hits = searchNovelChapters(chapters, "target")

        assertEquals(1, hits.size)
        val hit = hits.single()
        assertEquals(1, hit.chapterIndex)
        assertEquals("第 2 节", hit.chapterTitle)
        assertEquals("gamma TARGET delta".indexOf("TARGET") + "alpha beta\n".length, hit.charOffset)
        assertTrue(hit.snippet.contains("target", ignoreCase = true))
    }

    @Test
    fun blankQueryAndBlankBookYieldNothing() {
        assertTrue(searchNovelChapters(chapters, "   ").isEmpty())
        assertTrue(searchNovelChapters(emptyList(), "target").isEmpty())
    }

    @Test
    fun noMatchIsEmpty() {
        assertTrue(searchNovelChapters(chapters, "不存在的词").isEmpty())
    }

    @Test
    fun queryIsTrimmedBeforeMatching() {
        val hits = searchNovelChapters(chapters, "  gamma  ")
        assertEquals(1, hits.size)
        assertEquals(1, hits.single().chapterIndex)
    }

    @Test
    fun progressAdvancesThroughEveryScannedChapter() {
        val progress = mutableListOf<Pair<Int, Int>>()
        searchNovelChapters(chapters, "target") { scanned, total -> progress += scanned to total }

        // 命中与未命中的章都要推进进度，否则界面会卡在中间一章
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), progress)
    }

    @Test
    fun cancelledSearchStopsBeforeScanning() {
        val hits = searchNovelChapters(chapters, "target", isActive = { false })

        assertTrue(hits.isEmpty())
    }
}

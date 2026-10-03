package com.example.hyperreader

import com.example.hyperreader.model.Chapter
import com.example.hyperreader.ui.ChapterPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情页目录预览（0.18.0）的夹具：折叠截断、搜索过滤、展开态全量。
 */
class ChapterPreviewTest {

    private fun chapters(count: Int) = (1..count).map { index ->
        Chapter(
            id = index.toString(),
            title = "第${index}章",
            url = "https://www.wenku8.net/novel/2/2365/1000$index.htm",
            volume = if (index <= 30) "第一卷" else "第二卷",
        )
    }

    @Test
    fun collapsedPreviewCapsAtTwenty() {
        val all = chapters(120)
        val preview = ChapterPreview.preview(all, query = "", expanded = false)
        assertEquals(ChapterPreview.PREVIEW_LIMIT, preview.size)
        assertEquals("1", preview.first().id)
        assertEquals(ChapterPreview.PREVIEW_LIMIT.toString(), preview.last().id)
        assertTrue(ChapterPreview.canExpand(all, query = "", expanded = false))
    }

    @Test
    fun expandedPreviewReturnsEverything() {
        val all = chapters(120)
        val expanded = ChapterPreview.preview(all, query = "", expanded = true)
        assertEquals(120, expanded.size)
        assertTrue(ChapterPreview.canExpand(all, query = "", expanded = true))
    }

    @Test
    fun searchMatchesTitleAndVolumeIgnoringCase() {
        val all = chapters(120)
        assertEquals(listOf("119"), ChapterPreview.filter(all, "第119").map { it.id })
        assertEquals((31..120).map { it.toString() }, ChapterPreview.filter(all, "第二卷").map { it.id })
        assertTrue(ChapterPreview.filter(all, "不存在的章节").isEmpty())
        // 空查询等于不过滤
        assertEquals(all.size, ChapterPreview.filter(all, "   ").size)
    }

    @Test
    fun searchHappensBeforeTruncation() {
        val all = chapters(120)
        // 折叠状态下命中在第 100 章：过滤后只剩 1 条，不再被 20 条上限截断
        val preview = ChapterPreview.preview(all, query = "第100章", expanded = false)
        assertEquals(listOf("100"), preview.map { it.id })
    }

    @Test
    fun shortBookNeverShowsExpandButton() {
        val all = chapters(12)
        assertEquals(12, ChapterPreview.preview(all, query = "", expanded = false).size)
        assertFalse(ChapterPreview.canExpand(all, query = "", expanded = false))
        // 已展开但总数不超过上限时也不显示按钮
        assertFalse(ChapterPreview.canExpand(all, query = "", expanded = true))
    }
}
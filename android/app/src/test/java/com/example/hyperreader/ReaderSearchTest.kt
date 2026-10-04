package com.example.hyperreader

import com.xyreader.reader.SNIPPET_MARK
import com.xyreader.reader.SearchHit
import com.xyreader.reader.contextSnippet
import com.xyreader.reader.searchResultPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 页内搜索的纯逻辑：上下文摘要与逐页匹配。 */
class ReaderSearchTest {

    @Test
    fun snippetMarksMatchWithSeparator() {
        val text = "前面的文字，然后是目标词，最后收尾。"
        val snippet = contextSnippet(text, "目标词", radius = 3)
        // radius=3 时命中前只留 3 个字（「，然后是」的前 3 字）→「…然后是‖目标词‖，最后…」
        assertEquals("…然后是" + SNIPPET_MARK + "目标词" + SNIPPET_MARK + "，最后…", snippet)
    }

    @Test
    fun snippetAddsLeadingEllipsisWhenMatchIsNearEnd() {
        val text = "很长很长很长很长很长很长很长的开头，目标词"
        val snippet = contextSnippet(text, "目标词", radius = 5)
        assertTrue("命中靠后应带前省略号", snippet!!.startsWith("…"))
    }

    @Test
    fun snippetHasNoEllipsisWhenMatchFillsShortPage() {
        // 全文就等于命中：前后都没有可截的内容，不该出现悬空的「…」。
        val snippet = contextSnippet("目标词", "目标词")
        assertEquals(SNIPPET_MARK + "目标词" + SNIPPET_MARK, snippet)
    }

    @Test
    fun snippetIgnoresCase() {
        val snippet = contextSnippet("Hello World", "hello")
        // 匹配到的是原文里的 “Hello”（不因大小写而改写命中段），故只标记命中本身
        assertEquals(SNIPPET_MARK + "Hello" + SNIPPET_MARK + " World", snippet)
    }

    @Test
    fun snippetReturnsNullWhenNoMatch() {
        assertNull(contextSnippet("完全无关的正文", "目标词"))
    }

    @Test
    fun snippetIgnoresBlankQuery() {
        assertNull(contextSnippet("正文", "   "))
    }

    @Test
    fun snippetHandlesNegativeRadiusWithoutCrashing() {
        // 负半径在去负保护下退化成只显示命中段，不能因 coerceIn 反向边界而抛异常。
        val snippet = contextSnippet("前缀目标词后缀", "目标词", radius = -5)
        // 半径 0 时前后无内容可截，但前后仍有未覆盖文字，故两侧都带省略号
        assertEquals("…" + SNIPPET_MARK + "目标词" + SNIPPET_MARK + "…", snippet)
    }

    @Test
    fun searchFindsHitsAcrossPagesInPageOrder() {
        val pages = mapOf(
            2 to "第三页有目标词",
            0 to "第一页没有",
            1 to "第二页也有目标词，另一处",
        )
        val hits = searchResultPages(pages, "目标词")
        assertEquals(listOf(1, 2), hits.map { it.page })
        assertTrue("摘要应含命中段", hits[0].snippet.contains(SNIPPET_MARK))
    }

    @Test
    fun searchSkipsPagesWithoutText() {
        // 图片页 pageText 返回 null，应跳过而不是当成「无命中的空页」。
        val pages = mapOf(0 to "有目标词的文字页", 1 to null, 2 to "目标词又出现")
        val hits = searchResultPages(pages, "目标词")
        assertEquals(listOf(0, 2), hits.map { it.page })
    }

    @Test
    fun searchReturnsEmptyWhenNothingMatches() {
        val pages = mapOf(0 to "甲", 1 to "乙", 2 to null)
        assertTrue(searchResultPages(pages, "丙").isEmpty())
    }

    @Test
    fun searchIgnoresBlankQuery() {
        assertTrue(searchResultPages(mapOf(0 to "正文"), "  ").isEmpty())
    }

    @Test
    fun searchHitCarriesPageAndSnippet() {
        val hit = SearchHit(page = 7, snippet = "摘要")
        assertEquals(7, hit.page)
        assertEquals("摘要", hit.snippet)
    }
}

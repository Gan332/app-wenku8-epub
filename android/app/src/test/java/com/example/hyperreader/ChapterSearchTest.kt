package com.example.hyperreader

import com.xyreader.reader.SNIPPET_MARK
import com.xyreader.reader.matchChapterBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节级全量搜索（0.19.0）的纯逻辑测试。
 *
 * 被测的 [matchChapterBody] 是 `OnlinePageSource.searchChapters` 的匹配内核；
 * 页面源本身需要 Context 与排版引擎，纯 JVM 构造不出来，所以把匹配规则
 * 单独抽成纯函数来锁。循环与取消的行为由实现方保证（见 OnlinePageSource KDoc）。
 */
class ChapterSearchTest {

    @Test
    fun matchChapterBodyFindsHitAcrossParagraphs() {
        val paragraphs = listOf("第一章的开头。", "中间这段提到关键词在这里。", "结尾。")

        val (offset, snippet) = matchChapterBody(paragraphs, "关键词")!!

        // 偏移是**整章拼接后**的下标，不是章内段落下标
        val body = paragraphs.joinToString("\n")
        assertEquals(body.indexOf("关键词"), offset)
        // 命中段被 SNIPPET_MARK 包裹，供界面上强调色
        assertTrue("摘要应标记命中段: $snippet", snippet.contains(SNIPPET_MARK + "关键词" + SNIPPET_MARK))
    }

    @Test
    fun matchChapterBodyIgnoresCaseForLatinQueries() {
        val (offset, snippet) = matchChapterBody(listOf("Hello World"), "hello")!!
        assertEquals(0, offset)
        assertTrue(snippet.contains("hello"))
    }

    @Test
    fun matchChapterBodyReturnsNullWhenAbsentOrInputInvalid() {
        assertNull(matchChapterBody(listOf("完全没有"), "关键词"))
        assertNull(matchChapterBody(emptyList(), "关键词"))
        // 空白关键词不该匹配到任何东西
        assertNull(matchChapterBody(listOf("正文"), "   "))
        assertNull(matchChapterBody(listOf("正文"), ""))
    }

    @Test
    fun matchChapterBodyKeepsFirstHitOnly() {
        // 一章多次出现时只报第一处；界面按章列条目，重复列同一章没有意义
        val paragraphs = listOf("关键词在这里，后面又出现关键词")
        val (offset, _) = matchChapterBody(paragraphs, "关键词")!!
        assertEquals(0, offset)
    }

    @Test
    fun matchChapterBodyWorksOnSingleParagraphWithoutMarks() {
        val (offset, snippet) = matchChapterBody(listOf("开头有命中关键词"), "命中")!!
        assertEquals(3, offset)
        // 命中在页首时不应产生悬空的前置分隔符
        assertTrue(!snippet.startsWith(SNIPPET_MARK))
    }
}
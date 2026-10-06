package com.example.hyperreader

import com.example.hyperreader.reader.BOOK_SOURCE_LOCAL
import com.example.hyperreader.reader.BOOK_SOURCE_ONLINE
import com.example.hyperreader.reader.groupBookmarks
import com.xyreader.core.BookmarkEntity
import com.xyreader.reader.bookmarkSnippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨书书签中心（0.19.0）的纯逻辑测试。
 *
 * 两块内容都不依赖 DataStore / Android 运行时，因此可以直接在 JVM 上跑：
 * 书签摘录的空白折叠与截断，以及按书分组的排序规则。
 */
class BookmarkCenterTest {

    private fun bookmark(
        id: Long,
        hostBookId: String,
        page: Int,
        createdAt: Long,
        title: String = "测试书",
        source: String = BOOK_SOURCE_LOCAL,
    ) = BookmarkEntity(
        id = id,
        bookId = 100L + id,
        pageIndex = page,
        createdAt = createdAt,
        snippet = "正文摘录 $id",
        hostBookId = hostBookId,
        bookTitle = title,
        bookSource = source,
    )

    // ---- 摘录 ----

    @Test
    fun bookmarkSnippetCollapsesWhitespaceAndTruncates() {
        val raw = "  第一段\n\n\t第二段   有多余空白  \n第三段" + "补".repeat(80)
        val snippet = bookmarkSnippet(raw, limit = 40)

        // 换行与连续空白折叠成单个空格，不残留换行符
        assertTrue(snippet!!.startsWith("第一段 第二段 有多余空白 第三段"))
        assertFalse(snippet.contains('\n'))
        assertFalse(snippet.contains("  "))
        // 超长时截断并加省略号，且长度受限
        assertTrue(snippet.endsWith("…"))
        assertTrue("截断后长度应受 limit 约束: $snippet", snippet.length <= 41)
    }

    @Test
    fun bookmarkSnippetReturnsNullForBlankOrEmptyText() {
        assertNull(bookmarkSnippet(null))
        assertNull(bookmarkSnippet(""))
        // 纯空白（含换行/制表符）折叠后为空，不该存成一段空白摘录
        assertNull(bookmarkSnippet("  \n\t  \r\n "))
    }

    @Test
    fun bookmarkSnippetKeepsShortTextIntact() {
        assertEquals("很短的一段", bookmarkSnippet("很短的一段"))
    }

    @Test
    fun bookmarkSnippetTreatsNegativeLimitAsZero() {
        // 长度传负数时退化成「只留省略号」，而不是抛 coerceIn 之类的异常
        assertEquals("…", bookmarkSnippet("字数很多", limit = -5))
    }

    // ---- 分组 ----

    @Test
    fun groupBookmarksGroupsByBookAndSortsNewestFirst() {
        val groups = groupBookmarks(
            listOf(
                bookmark(id = 1, hostBookId = "a", page = 0, createdAt = 100),
                bookmark(id = 2, hostBookId = "b", page = 3, createdAt = 300),
                bookmark(id = 3, hostBookId = "a", page = 7, createdAt = 200),
            ),
        )

        assertEquals(2, groups.size)
        // 组间：最近添加的书在前（b 的最新是 300 > a 的 200）
        assertEquals("b", groups[0].hostBookId)
        assertEquals("a", groups[1].hostBookId)

        val a = groups[1]
        assertEquals(2, a.bookmarks.size)
        // 组内：同样按时间倒序
        assertEquals(listOf(3L, 1L), a.bookmarks.map { it.id })
        assertEquals(200L, a.latestAt)
    }

    @Test
    fun groupBookmarksSeparatesSameTitleBySourceAndId() {
        // 标题相同但来源不同的两本书不得并成一组，否则点击书签会开错阅读器
        val groups = groupBookmarks(
            listOf(
                bookmark(id = 1, hostBookId = "1", page = 0, createdAt = 100, title = "同名书", source = BOOK_SOURCE_LOCAL),
                bookmark(id = 2, hostBookId = "1", page = 0, createdAt = 90, title = "同名书", source = BOOK_SOURCE_ONLINE),
            ),
        )
        assertEquals(2, groups.size)
        assertTrue(groups.any { it.isLocal })
        assertFalse(groups.first { it.bookSource == BOOK_SOURCE_ONLINE }.isLocal)
    }

    @Test
    fun groupBookmarksFallsBackToReadableTitleForLegacyData() {
        // 0.19.0 之前写入的书签没有冻结书名/来源，不能显示成空白标题
        val legacy = BookmarkEntity(id = 1, bookId = 7L, pageIndex = 0, createdAt = 1)
        val group = groupBookmarks(listOf(legacy)).single()

        assertEquals("书籍 7", group.displayTitle)
        // 老数据没有来源标记，按在线处理会误导，按本地处理要求 URI 存在——
        // 这里只锁定「不空且可读」，来源由界面另行判断
        assertEquals("", group.bookSource)
    }

    @Test
    fun groupBookmarksHandlesEmptyInput() {
        assertTrue(groupBookmarks(emptyList()).isEmpty())
    }
}
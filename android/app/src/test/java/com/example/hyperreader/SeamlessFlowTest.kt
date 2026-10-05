package com.example.hyperreader

import com.xyreader.archive.PageFlowGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无缝流版面几何（上下滚动）回归基线。
 *
 * 缺陷：过去每页位图都是整屏高，而页容量按 `floor((屏高 - 上边距 - 下边距) / 行高)`
 * 计算，取整剩下的死区连同页边距一起构成页底空白带——上下滚动时页与页之间明显断开，
 * 与代码里「页页相接」的约定相反。
 *
 * 这里锁三件事：整屏页（左右翻页）行为不变、无缝流页间零空白、页边距只落首页/末页。
 *
 * 取一组能整除出整行数的参数：屏高 2400、行高 57（42 行 = 2394，剩 6px 取整死区）。
 */
class SeamlessFlowTest {

    private val pageHeight = 2400
    private val lineHeight = 57
    private val padding = 64f
    private val pageCount = 12

    private fun heights(count: Int = pageCount): List<Int> =
        (0 until count).map {
            PageFlowGeometry.flowPageHeight(pageHeight, it, count - 1, padding, padding, lineHeight)
        }

    private fun lines(index: Int): Int = PageFlowGeometry.linesOnPage(
        pageHeight,
        PageFlowGeometry.pageTop(true, index, padding),
        lineHeight,
    )

    @Test
    fun fullPageModeKeepsScreenHeight() {
        // 左右翻页：位图高度恒为屏高、四边页边距逐页生效——修复不得改变这条路径
        assertEquals(pageHeight, PageFlowGeometry.fullPageHeight(pageHeight))
        assertEquals(padding, PageFlowGeometry.pageTop(false, 0, padding), 0f)
        assertEquals(padding, PageFlowGeometry.pageTop(false, 7, padding), 0f)
        assertEquals(padding, PageFlowGeometry.pageBottom(false, 0, pageCount - 1, padding), 0f)
        assertEquals(padding, PageFlowGeometry.pageBottom(false, pageCount - 1, pageCount - 1, padding), 0f)
    }

    @Test
    fun seamlessMarginsOnlyOnFirstAndLastPage() {
        assertEquals("首页应保留上边距", padding, PageFlowGeometry.pageTop(true, 0, padding), 0f)
        assertEquals(0f, PageFlowGeometry.pageTop(true, 1, padding), 0f)
        assertEquals(0f, PageFlowGeometry.pageTop(true, pageCount - 1, padding), 0f)
        assertEquals("末页应保留下边距", padding, PageFlowGeometry.pageBottom(true, pageCount - 1, pageCount - 1, padding), 0f)
        assertEquals(0f, PageFlowGeometry.pageBottom(true, 0, pageCount - 1, padding), 0f)
    }

    @Test
    fun seamlessInteriorPagesHaveNoGapBetweenThem() {
        val h = heights()
        // 只有中间页彼此相接：首页之下、末页之上各有一处页边距（设计如此）
        for (i in 1 until pageCount - 2) {
            val lines = (lines(i) + lines(i + 1)) * lineHeight
            assertEquals("第 ${i + 1}/${i + 2} 页之间出现 ${h[i] + h[i + 1] - lines}px 空白", lines, h[i] + h[i + 1])
        }
    }

    @Test
    fun seamlessInteriorPagesShareOneHeight() {
        val interior = heights().subList(1, pageCount - 1).distinct()
        assertEquals("中间页高度必须一致，否则整列忽长忽短", 1, interior.size)
        assertEquals("42 行 × 57px = 2394px", 2394, interior.single())
    }

    @Test
    fun firstPageYieldsTopMarginAndLastPageGainsBottomMargin() {
        val h = heights()
        // 首页让出 64px 上边距 → 只排得下 40 行；末页多出 64px 下边距
        assertEquals(40, lines(0))
        assertEquals(64 + 40 * lineHeight, h[0])
        assertEquals(42 * lineHeight, h[1])
        assertEquals(42 * lineHeight + 64, h[pageCount - 1])
    }

    @Test
    fun oldLayoutLeftADeadBandThatThisFixRemoves() {
        // 旧公式：整屏页高 + floor((屏高 - 上下边距) / 行高) 的容量
        val oldCapacity = ((pageHeight - padding - padding).toInt() / lineHeight)
        assertEquals(39, oldCapacity)
        assertEquals("旧算法每页底部 177px 死区，就是断开的来源", 177, pageHeight - oldCapacity * lineHeight)

        // 新算法：中间页 = 0 上边距 + 42 行 × 57px + 0 下边距，整除且不超屏高
        val interior = heights()[5]
        assertEquals(0, interior % lineHeight)
        assertTrue("新算法页高不应超过屏高", interior <= pageHeight)
        assertEquals("屏高取整剩 6px 不再变成空白带", 6, pageHeight - interior)
    }

    @Test
    fun zeroMarginsMakeEveryPageAFullScreenMultiple() {
        val capacity = PageFlowGeometry.linesOnPage(pageHeight, 0f, lineHeight)
        assertEquals(42, capacity)
        val flow = PageFlowGeometry.flowPageHeight(pageHeight, 3, pageCount - 1, 0f, 0f, lineHeight)
        assertEquals(capacity * lineHeight, flow)
    }

    @Test
    fun tinyScreenStillFitsOneLine() {
        assertEquals(1, PageFlowGeometry.linesOnPage(3, padding, lineHeight))
        assertTrue(PageFlowGeometry.flowPageHeight(3, 0, 0, padding, padding, lineHeight) >= 1)
    }
}

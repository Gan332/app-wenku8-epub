package com.xyreader.archive

import kotlin.math.roundToInt

/**
 * 页面流几何：单页的上下内边距、容量行数与位图高度。
 *
 * 从 [NovelPageSource] 抽成纯函数是为了让「页与页之间不得出现空白带」这条
 * 不变量可被 JVM 单测锁定——它是渲染层（Bitmap/Canvas）算不出来的东西。
 *
 * 两种版面：
 * - **整屏页**（左右翻页，`seamless = false`）：每页都是一屏，四边页边距逐页生效，
 *   位图高度恒为屏高。
 * - **无缝流**（上下滚动，`seamless = true`）：整列页首尾相接。上边距只在首页、
 *   下边距只在末页生效，其余页上下内边距为 0；位图高度取该页**实际占用**高度，
 *   于是既没有「上一页下边距 + 下一页上边距」的叠加，也没有向下取整剩下的页底死区。
 *
 * 所有参数单位均为 px，行高为正整数。
 */
internal object PageFlowGeometry {

    /** 第 [index] 页的上内边距：无缝流下只在首页出现。 */
    fun pageTop(seamless: Boolean, index: Int, paddingTop: Float): Float =
        if (seamless && index != 0) 0f else paddingTop

    /** 第 [index] 页的下内边距：无缝流下只在末页（[lastIndex]）出现。 */
    fun pageBottom(seamless: Boolean, index: Int, lastIndex: Int, paddingBottom: Float): Float =
        if (seamless && index != lastIndex) 0f else paddingBottom

    /** 第 [index] 页容量行数：按该页实际可用高度向下取整；极端小屏至少 1 行。 */
    fun linesOnPage(pageHeightPx: Int, pageTop: Float, lineHeightPx: Int): Int =
        (((pageHeightPx - pageTop) / lineHeightPx).toInt()).coerceAtLeast(1)

    /** 整屏页位图高度（左右翻页）：恒为屏高。 */
    fun fullPageHeight(pageHeightPx: Int): Int = pageHeightPx

    /**
     * 无缝流页位图高度：该页实际占用高度（上内边距 + 整数行 + 下内边距）。
     *
     * 相邻两页的高度之和恰好等于两页的行数之和乘行高，因此纵向排列时页边界处
     * 没有任何空白像素。
     */
    fun flowPageHeight(
        pageHeightPx: Int,
        index: Int,
        lastIndex: Int,
        paddingTop: Float,
        paddingBottom: Float,
        lineHeightPx: Int,
    ): Int {
        val top = pageTop(true, index, paddingTop)
        val bottom = pageBottom(true, index, lastIndex, paddingBottom)
        val lines = linesOnPage(pageHeightPx, top, lineHeightPx)
        return (top + lines * lineHeightPx + bottom).roundToInt().coerceAtLeast(1)
    }
}

package com.xyreader.core

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 阅读器尺寸令牌。
 *
 * 数值刻意与宿主应用的 `com.example.hyperreader.ui.UiDimens` **完全一致**，让阅读器
 * 弹层（目录、设置面板、复制文字）与 App 其它页面的内外边距看起来是同一套规格。
 *
 * 这里必须独立声明而不能直接复用 `UiDimens`：阅读器保留上游包名 `com.xyreader`，
 * 而 `com.xyreader` 不得反向依赖 `com.example.hyperreader`（AGENTS §4.8 第 1 条）。
 * 两处数值由 `DesignTokenTest.readerTokensMatchAppTokens` 逐项比对锁定，
 * `DesignTokenTest.readerUiHasNoHardcodedSizes` 则禁止阅读器界面再出现裸 `dp` / `sp`。
 */
object ReaderDimens {
    // —— 触控与行高 ——
    /** 最小触摸目标（AGENTS §4.4：按钮不小于 48dp）。 */
    val touchMin: Dp = 48.dp

    // —— 间距六档（与 UiDimens 同值，只允许用这六档表达元素间距）——
    val spaceXXS: Dp = 2.dp
    val spaceXS: Dp = 6.dp
    val spaceS: Dp = 10.dp
    val spaceM: Dp = 14.dp
    val spaceL: Dp = 18.dp
    val spaceXL: Dp = 26.dp

    // —— 页面结构 ——
    /** 弹层/全屏页的内容内边距（目录行、设置分组、复制文字面板共用）。 */
    val pagePadding: Dp = 16.dp

    /** 浮层圆角：工具栏胶囊与弹层面板共用一个值，避免两处各写 20dp。 */
    val panelCorner: Dp = 20.dp

    // —— 装饰与结构常量（与令牌刻度无关，单独命名以便成组调整）——
    /** 发丝描边宽度（未选中的胶囊、背景色块）。 */
    val hairline: Dp = 1.dp

    /** 选中态描边宽度。 */
    val borderSelected: Dp = 2.dp

    /** 工具栏图标尺寸。 */
    val iconSm: Dp = 18.dp
    val iconMd: Dp = 20.dp

    /** 阅读背景色块尺寸。 */
    val swatch: Dp = 36.dp

    /** 单选圆点尺寸（阅读配置里的方向 / 模式选择）。 */
    val indicatorDot: Dp = 22.dp

    // —— 字号 ——
    val body = 14.sp

    /** Snackbar 抬离底部的距离：避开底部工具栏。 */
    val snackbarBottomInset: Dp = 88.dp

    /** 「复制文字」面板正文区高度（载入态最小高度 / 有内容时的滚动上限）。 */
    val copyBodyMinHeight: Dp = 120.dp
    val copyBodyMaxHeight: Dp = 360.dp

    /** 阅读设置面板分页区高度：容纳最高的字体组。 */
    val sheetPagerHeight: Dp = 340.dp
}

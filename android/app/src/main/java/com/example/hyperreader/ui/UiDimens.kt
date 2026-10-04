package com.example.hyperreader.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 全局尺寸与字号令牌（0.18.0 起）。
 *
 * 之前这些值散在各处硬编码：行高 `48.dp` 在五个文件各写一遍，字号出现 11–25sp 共 15 种，
 * 间距从 2dp 到 24dp 混用 12 种。控件大小因此无法统一调整，动效时长也难以对齐。
 *
 * 使用约定（AGENTS §4.4）：
 * - 控件最小高度、页面边距、字号一律用这里的令牌，不再写裸 `dp` / `sp`；
 * - 装饰性小值（进度圈直径 22.dp、圆角 8.dp 等）与令牌无关的数值允许直接写。
 */
object UiDimens {
    // —— 触控与行高 ——
    /** 最小触摸目标（AGENTS §4.4：按钮不小于 48dp）。 */
    val touchMin: Dp = 48.dp

    /** 紧凑信息行的最小高度（详情页字段卡、设置摘要行）。 */
    val rowMin: Dp = 48.dp

    // —— 间距六档（只允许用这六档表达元素间距）——
    // 0.18.1 起整体放宽一档：原先 4/8/12 在高密度屏上偏紧，控件之间挤在一起。
    val spaceXXS: Dp = 2.dp
    val spaceXS: Dp = 6.dp
    val spaceS: Dp = 10.dp
    val spaceM: Dp = 14.dp
    val spaceL: Dp = 18.dp
    val spaceXL: Dp = 26.dp

    // —— 页面结构 ——
    /** 页面左右内边距。 */
    val pagePadding: Dp = 16.dp

    /** Card 内边距（MiuiX `insideMargin`）。 */
    val cardInset: Dp = 16.dp

    /** 列表项之间的间距。 */
    val listGap: Dp = spaceS

    /** 详情页封面宽度。 */
    val coverWidth: Dp = 160.dp

    // —— 字号七档 ——
    /** 页面大标题。 */
    val display = 25.sp

    /** 页面/弹层标题。 */
    val title = 20.sp

    /** 区块标题。 */
    val section = 16.sp

    /** 正文强调（数值、书名）。 */
    val bodyStrong = 15.sp

    /** 正文。 */
    val body = 14.sp

    /** 次要说明。 */
    val caption = 13.sp

    /** 弱化说明。 */
    val captionSmall = 12.sp

    /** 徽标（角标、置顶标签）——七档之外的极小字号，仅角标使用。 */
    val badge = 10.sp

    // —— 动效时长（毫秒，参考系统 150/200/300ms 节奏）——
    const val MOTION_FAST = 150
    const val MOTION_MEDIUM = 220
    const val MOTION_SLOW = 320

    /** 列表交错入场的单项延迟步长（毫秒），乘以索引得到该项延迟。 */
    const val STAGGER_STEP = 28
}

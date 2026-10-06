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

    // —— 跨页复用的装饰值 ——
    // 封面圆角此前在书架、探索列表、探索大图三处各写一遍 8dp；
    // 加载指示器此前有 16 / 22 / 24dp 三种尺寸，同一屏里粗细不一。
    val cardCorner: Dp = 8.dp

    val indicator: Dp = 24.dp

    /** 线性进度条厚度（阅读统计按书籍时长共用）。 */
    val progressThickness: Dp = 8.dp

    /** 取色圆点（强调色、背景色板）。 */
    val swatch: Dp = 36.dp

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
/**
 * LNR 主界面排版规格（0.19.0 起对齐 LightNovelReader）。
 *
 * 这些值来自 LNR 上游源码的**字面数字**，不是目测近似：
 * - 书架卡 146dp / 94×144dp 封面 / 12dp 卡圆角 / 4dp 卡内边距 → `BookCardContent`
 * - 文字栏左内边距 12dp、作者与元信息间距 8dp / 6dp → `BookCardContent`
 * - 设置分组 16dp 圆角、16dp 外边距、16dp 下间距、2dp 项间距、24dp 分组标题边距
 *   → `SettingsScreen.SettingsCategory`
 * - 顶栏标题 `typography.displayLarge` → 本项目 [display]
 *
 * 单一来源：屏幕上的尺寸一律从这里取（AGENTS §4.4）。改这里等于改全应用排版。
 */
object LnrDimens {
    // —— 书籍卡片（`BookCardContent`）——

    /** 卡片总高。 */
    val cardHeight = 146.dp

    /** 卡片内边距（四边）。 */
    val cardPadding = 4.dp

    /** 卡片圆角。 */
    val cardCorner = 12.dp

    /** 封面圆角。 */
    val coverCorner = 8.dp

    /** 封面宽。 */
    val coverWidth = 94.dp

    /** 封面高。 */
    val coverHeight = 144.dp

    /** 文字栏左内边距（封面与正文之间）。 */
    val textGutter = 12.dp

    /** 作者行元素间距。 */
    val authorGap = 8.dp

    /** 元信息行（图标 + 文字）间距。 */
    val metaGap = 6.dp

    /** 元信息图标底板圆角（`TagChip`）。 */
    val metaChipCorner = 4.dp

    /** 元信息图标底板内边距。 */
    val metaChipPadding = 4.dp

    /** 元信息图标尺寸。 */
    val metaChipIcon = 15.dp

    /** 元信息图标底板图标缩放后视觉尺寸（`TagChip` 内 Icon 无固定尺寸时的占位）。 */
    val metaChipIconBox = 22.dp

    // —— 顶栏（`MediumTopAppBar` / `TopAppBar`）——

    /**
     * 顶栏标题栏高度。
     *
     * LNR 用 Material 的 `MediumTopAppBar`（默认 112dp：64dp 内容区 + 标题下留白）。
     * 本项目用 MiuiX `TopAppBar`，高度由组件内部决定，不便逐像素对齐；
     * 这里声明的是**页面自己画的标题区**（书架 / 探索的页内大标题）应占的高度。
     */
    val topBarContentHeight = 64.dp

    /** 顶栏导航图标 / 操作图标按钮的占位方块边长（LNR `Box(Modifier.size(48.dp))`）。 */
    val topBarIconSlot = 48.dp

    // —— 设置页（`SettingsScreen.SettingsCategory`）——

    /** 设置分组卡的圆角。 */
    val settingsGroupCorner = 16.dp

    /** 设置分组卡左右外边距。 */
    val settingsGroupMargin = 16.dp

    /** 设置分组卡下外边距。 */
    val settingsGroupBottom = 16.dp

    /** 设置分组内条目间距。 */
    val settingsItemGap = 2.dp

    /** 设置分组标题（`SectionHeader`）左右外边距。 */
    val settingsHeaderMarginH = 24.dp

    /** 设置分组标题上下外边距。 */
    val settingsHeaderMarginV = 10.dp

    // —— 标签指示器（`TabRowDefaults.SecondaryIndicator`）——

    /** 标签指示器高度。 */
    val tabIndicatorHeight = 4.dp

    /** 标签指示器上圆角（顶角 3dp，底角方角）。 */
    val tabIndicatorCorner = 3.dp

    // —— 探索列表（`ExploreHomeScreen.ExplorePage`）——

    /**
     * 探索列表分区标题高度的换算基数。
     *
     * LNR 写 `with(density) { (16.sp * 2.2f).toDp() }`，此处只保留那个系数，
     * 由调用方乘以自己的标题字号。
     */
    const val EXPLORE_TITLE_HEIGHT_FACTOR = 2.2f

    // —— 纵向书卡（`ExploreScreen` 的横向书卡 / 阅读首页小卡）——

    /** 探索页横向书卡宽度。 */
    val posterCardWidth = 132.dp

    /** 探索页横向书卡圆角。 */
    val posterCardCorner = 6.dp

    /** 阅读首页「继续阅读」大卡封面宽度（比小卡宽，突出主入口）。 */
    val continueCoverWidth = 72.dp

    /** 阅读首页「最近在读」小卡宽度。 */
    val recentCardWidth = 104.dp

    /** 竖版封面比例（宽 : 高），LNR 用 94:144 之外的近似值，此处统一 3:4。 */
    const val POSTER_ASPECT = 3f / 4f
}


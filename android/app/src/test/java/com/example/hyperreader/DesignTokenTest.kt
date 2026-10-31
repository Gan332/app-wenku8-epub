package com.example.hyperreader

import androidx.compose.ui.unit.dp
import com.example.hyperreader.ui.LnrDimens
import com.example.hyperreader.ui.StudioTab
import com.example.hyperreader.ui.StudioUiState
import com.example.hyperreader.ui.UiDimens
import com.xyreader.core.ReaderDimens
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 尺寸令牌的回归守卫。
 *
 * 阅读器保留上游包名 `com.xyreader`，不能反向依赖宿主（AGENTS §4.8 第 1 条），
 * 因此它有一份自己的 `ReaderDimens`。代价是两份数值可能各自漂移——一旦漂移，
 * 阅读器弹层（目录 / 设置面板 / 复制文字）就会与 App 其它页面看起来不是同一套规格，
 * 也就是 0.18.0 引入令牌本来要解决的问题。
 */
class DesignTokenTest {

    @Test
    fun readerTokensMatchAppTokens() {
        // 间距六档 + 页面内边距 + 最小触摸目标 + 取色圆点必须逐项相等。
        assertEquals("spaceXXS 漂移", UiDimens.spaceXXS, ReaderDimens.spaceXXS)
        assertEquals("spaceXS 漂移", UiDimens.spaceXS, ReaderDimens.spaceXS)
        assertEquals("spaceS 漂移", UiDimens.spaceS, ReaderDimens.spaceS)
        assertEquals("spaceM 漂移", UiDimens.spaceM, ReaderDimens.spaceM)
        assertEquals("spaceL 漂移", UiDimens.spaceL, ReaderDimens.spaceL)
        assertEquals("spaceXL 漂移", UiDimens.spaceXL, ReaderDimens.spaceXL)
        assertEquals("pagePadding 漂移", UiDimens.pagePadding, ReaderDimens.pagePadding)
        assertEquals("touchMin 漂移", UiDimens.touchMin, ReaderDimens.touchMin)
        assertEquals("swatch 漂移", UiDimens.swatch, ReaderDimens.swatch)
    }

    @Test
    fun readerUiHasNoHardcodedSizes() {
        // 阅读器界面只允许通过 ReaderDimens 表达尺寸；裸 `12.dp` 一律视为回归。
        // 唯一豁免是 `0.dp`（页与页零间距，表达「不要间距」而非某个设计值）。
        val sourceRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .flatMap { dir -> sequenceOf(File(dir, "src/main/java"), File(dir, "app/src/main/java")) }
            .firstOrNull { it.isDirectory }
        requireNotNull(sourceRoot) { "未找到 src/main/java（user.dir=${requireNotNull(System.getProperty("user.dir"))}）" }

        val readerRoot = File(sourceRoot, "com/xyreader")
        assertTrue("未找到 com/xyreader 源码目录", readerRoot.isDirectory)

        val pattern = Regex("(?<![A-Za-z0-9_.])(\\d+\\.(dp|sp))")
        val offenders = mutableListOf<String>()
        readerRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "ReaderDimens.kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    pattern.findAll(line)
                        .map { it.groupValues[1] }
                        .filter { it != "0.dp" }
                        .forEach { offenders += "${file.name}:${index + 1}: $it（${line.trim()}）" }
                }
            }

        assertTrue(
            "阅读器界面出现硬编码尺寸，请改用 ReaderDimens：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * 页名只能有一个来源：顶栏（`PageChrome.chromeTitle`，0.19.0-alpha05）。
     *
     * 0.19.0 之前书架、阅读首页、探索、设置四个一级页，以及搜索页与设置二级页，
     * 都在顶栏下方又画了一遍同名大标题（`fontSize = UiDimens.display` + Bold），
     * 同一屏出现两个标题：一个在 52dp 的顶栏里，一个在内容区首行。滚动时页面
     * 还少了一行可用高度。
     *
     * 这里用「文件 + 页面名」的精确组合锁定，而不是禁止所有 `UiDimens.display`：
     * 探索详情里的书名、阅读器设置里的示例正文等仍然需要大字号的正文。
     */
    @Test
    fun pageTitlesAreOnlyRenderedByTheTopBar() {
        val sourceRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .flatMap { dir -> sequenceOf(File(dir, "src/main/java"), File(dir, "app/src/main/java")) }
            .firstOrNull { it.isDirectory }
        requireNotNull(sourceRoot) { "未找到 src/main/java（user.dir=${System.getProperty("user.dir")}）" }

        // 页面名 → 不该再出现在页内的那个字面量
        val forbidden = mapOf(
            "BookshelfScreen.kt" to "\"书架\"",
            "ReadingHomeScreen.kt" to "\"阅读\"",
            "ExploreScreen.kt" to "\"探索\"",
            "SearchScreen.kt" to "\"搜索轻小说\"",
        )
        val offenders = mutableListOf<String>()
        forbidden.forEach { (fileName, literal) ->
            val file = File(sourceRoot, "com/example/hyperreader/ui/$fileName")
            assertTrue("未找到 $fileName", file.isFile)
            file.readLines().forEachIndexed { index, line ->
                // 只看「画标题」的写法：display 字号与字面量同行
                if (line.contains(literal) && line.contains("UiDimens.display")) {
                    offenders += "$fileName:${index + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue(
            "页内重复了大标题（页名只由顶栏给，见 PageChrome）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * 设置二级页不再自带返回按钮：返回走顶栏图标与系统返回键。
     *
     * `SettingsScaffold` 曾经把「‹ 返回设置」文字按钮和页名一起画在页内，
     * 与顶栏形成两套返回。这条断言把该写法钉死。
     */
    @Test
    fun settingsSubPagesHaveNoInlineBackButton() {
        val sourceRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .flatMap { dir -> sequenceOf(File(dir, "src/main/java"), File(dir, "app/src/main/java")) }
            .firstOrNull { it.isDirectory }
        requireNotNull(sourceRoot) { "未找到 src/main/java" }

        val settings = File(sourceRoot, "com/example/hyperreader/ui/SettingsScreen.kt")
        assertTrue("未找到 SettingsScreen.kt", settings.isFile)
        val source = settings.readText()
        assertTrue(
            "设置页又出现了页内返回文字按钮（应由顶栏 + BackHandler 承担）",
            !source.contains("‹ 返回设置"),
        )
    }

    /**
     * 带 `weight(1f)` 的页面根容器必须有高度预算。
     *
     * Column 只有在**有界高度**下才会给 weight 子项分配空间；根 Column 只写
     * `fillMaxWidth()` 时，列表/操作区在横屏与小屏上可能被裁掉。用文件级断言锁定
     * 搜索、书签中心与导出向导这几条长列表路径。
     */
    @Test
    fun weightedScrollAreasHaveBoundedParentHeight() {
        val sourceRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .flatMap { dir -> sequenceOf(File(dir, "src/main/java"), File(dir, "app/src/main/java")) }
            .firstOrNull { it.isDirectory }
        requireNotNull(sourceRoot) { "未找到 src/main/java" }
        val files = listOf("SearchScreen.kt", "BookmarkCenterScreen.kt", "StudioApp.kt")
        val offenders = mutableListOf<String>()
        files.forEach { name ->
            val source = File(sourceRoot, "com/example/hyperreader/ui/$name").readText()
            assertTrue("$name 缺少 fillMaxSize 根布局", source.contains("fillMaxSize()"))
            assertTrue("$name 的滚动区缺少 weight", source.contains("weight(1f)"))
        }
        assertTrue("长列表页面缺少有界高度：${offenders.joinToString()}", offenders.isEmpty())
    }

    /**
     * LNR 排版规格的回归守卫（0.19.0）。
     *
     * `LnrDimens` 里的数字全部来自 LNR 上游源码的字面值（`BookCardContent` /
     * `SettingsScreen.SettingsCategory` / `TagChip` / `MediumTopAppBar`），
     * 目的是让主界面排版与上游一致。它们很容易在「顺手改成 8dp」时被无声改掉，
     * 因此在这里逐项钉死。
     */
    @Test
    fun lnrLayoutSpecsAreLocked() {
        assertEquals("卡片高度", 146.dp, LnrDimens.cardHeight)
        assertEquals("卡片内边距", 4.dp, LnrDimens.cardPadding)
        assertEquals("卡片圆角", 12.dp, LnrDimens.cardCorner)
        assertEquals("封面圆角", 8.dp, LnrDimens.coverCorner)
        assertEquals("封面宽", 94.dp, LnrDimens.coverWidth)
        assertEquals("封面高", 144.dp, LnrDimens.coverHeight)
        assertEquals("文字栏左内边距", 12.dp, LnrDimens.textGutter)
        assertEquals("作者行间距", 8.dp, LnrDimens.authorGap)
        assertEquals("元信息行间距", 6.dp, LnrDimens.metaGap)
        assertEquals("元信息底板圆角", 4.dp, LnrDimens.metaChipCorner)
        assertEquals("元信息图标尺寸", 15.dp, LnrDimens.metaChipIcon)
        assertEquals("设置组卡圆角", 16.dp, LnrDimens.settingsGroupCorner)
        assertEquals("设置组卡外边距", 16.dp, LnrDimens.settingsGroupMargin)
        assertEquals("设置组卡下外边距", 16.dp, LnrDimens.settingsGroupBottom)
        assertEquals("设置组内条目间距", 2.dp, LnrDimens.settingsItemGap)
        assertEquals("设置分组标题左右外边距", 24.dp, LnrDimens.settingsHeaderMarginH)
        assertEquals("设置分组标题上下外边距", 10.dp, LnrDimens.settingsHeaderMarginV)
        assertEquals("顶栏内容区高度", 64.dp, LnrDimens.topBarContentHeight)
        assertEquals("顶栏图标槽位", 48.dp, LnrDimens.topBarIconSlot)
        // —— 探索页（`ExploreHomeScreen.ExploreRowSection` / `ExploreBookCard`）——
        assertEquals("探索书卡封面宽", 98.dp, LnrDimens.exploreCoverWidth)
        assertEquals("探索书卡封面高", 138.dp, LnrDimens.exploreCoverHeight)
        assertEquals("探索书卡封面内缩", 4.dp, LnrDimens.exploreCoverInset)
        assertEquals("探索书卡间距", 4.dp, LnrDimens.exploreCardGap)
        assertEquals("探索书行下留白", 12.dp, LnrDimens.exploreRowBottomPadding)
        assertEquals("探索展开页单元最小宽", 132.dp, LnrDimens.exploreExpandedCellMin)
    }

    /**
     * 封面比例与书卡标题预留高度必须来自令牌，不允许各处自己写一个数。
     *
     * 3:4 用于阅读首页小卡与探索展开页网格；探索**横排书卡**改用 LNR 自己的 98:138
     * （见 [LnrDimens.exploreCoverWidth] / [LnrDimens.exploreCoverHeight]）。
     * 标题预留系数 2.2 来自 LNR 的 `(16.sp * 2.2f).toDp()`，横排书卡与展开页网格共用。
     */
    @Test
    fun posterAspectIsShared() {
        assertEquals("封面比例", 3f / 4f, LnrDimens.POSTER_ASPECT, 0f)
        assertEquals("探索页标题预留系数", 2.2f, LnrDimens.exploreTitleHeightFactor, 0f)
    }

    /**
     * 主导航必须保持四项且顺序与 LNR `MainDestination` 一致（AGENTS §4.6.1）。
     *
     * 0.19.0 之前是三项（书架/探索/设置），对齐上游后加入「阅读」并置为默认首屏。
     * 这里用名字比对而不是只比 `size`，避免顺序被无意调换。
     */
    @Test
    fun mainNavigationMatchesLnrOrder() {
        val expected = listOf("READING", "BOOKSHELF", "EXPLORE", "SETTINGS")
        assertEquals(
            "一级导航必须是阅读/书架/探索/设置四项且顺序固定（AGENTS §4.6.1）",
            expected,
            StudioTab.entries.map { it.name },
        )
        assertEquals("默认首屏必须是「阅读」（LNR MainDestination.Reading 是 entries[0]）", StudioTab.READING, StudioUiState().tab)
    }
}


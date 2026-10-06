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
        assertEquals("标签指示器高度", 4.dp, LnrDimens.tabIndicatorHeight)
        assertEquals("标签指示器圆角", 3.dp, LnrDimens.tabIndicatorCorner)
        assertEquals("顶栏图标槽位", 48.dp, LnrDimens.topBarIconSlot)
    }

    /**
     * 封面比例必须与 LNR 书架卡一致（94:144 ≈ 0.6528，LNR 探索页另用近似 3:4）。
     *
     * 这里钉的是本工程内部一致性：书架卡、探索海报卡、阅读首页小卡共用同一个
     * [LnrDimens.POSTER_ASPECT]，避免三处各写一个比例。
     */
    @Test
    fun posterAspectIsShared() {
        assertEquals("封面比例", 3f / 4f, LnrDimens.POSTER_ASPECT, 0f)
        assertEquals("探索页标题高度系数", 2.2f, LnrDimens.EXPLORE_TITLE_HEIGHT_FACTOR, 0f)
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


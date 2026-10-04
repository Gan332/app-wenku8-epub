package com.example.hyperreader

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
        val sourceRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .flatMap { dir -> sequenceOf(File(dir, "src/main/java"), File(dir, "app/src/main/java")) }
            .firstOrNull { it.isDirectory }
        requireNotNull(sourceRoot) { "未找到 src/main/java（user.dir=${System.getProperty("user.dir")}）" }

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
}

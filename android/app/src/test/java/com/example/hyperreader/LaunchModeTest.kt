package com.example.hyperreader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 启动 Activity 的 launchMode 回归守卫。
 *
 * `MainActivity` 曾声明 `launchMode="singleTask"`。用户停留在阅读器
 * （`XyReaderActivity` / `OnlineReaderActivity`）时退到桌面，再从桌面图标进入 App，
 * 系统会把任务栈截断到位于栈底的 `MainActivity`，栈顶的阅读器一并被清掉，
 * 表现为「重新进入 App 却退出了阅读器」，与 AGENTS §4.6.1「点书即读」的体验前提冲突。
 *
 * 通知路由并不需要 singleTask：`ExportNotificationService` 的 PendingIntent
 * 自带 `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP | FLAG_ACTIVITY_CLEAR_TOP`，
 * 标准启动模式下同样会清掉阅读器并把 intent 交给已有的 MainActivity。
 */
class LaunchModeTest {

    @Test
    fun mainActivityDoesNotResetTaskOnRelaunch() {
        val block = Regex("""<activity\b[^>]*android:name="\.MainActivity"[^>]*>""")
            .find(findManifest().readText())
        requireNotNull(block) { "AndroidManifest.xml 中没有 .MainActivity 声明" }
        assertFalse(
            "MainActivity 不能是 singleTask / singleInstance：从桌面重新进入会清掉栈顶的阅读器；" +
                "通知路由由 PendingIntent 的 NEW_TASK|CLEAR_TOP|SINGLE_TOP 负责",
            block.value.contains("launchMode=\"singleTask\"") ||
                block.value.contains("launchMode=\"singleInstance\""),
        )
    }

    /** 与 DesignTokenTest 相同的定位方式：兼容 `user.dir` 是模块目录或 `android/` 两种情况。 */
    private fun findManifest(): File {
        val candidates = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .flatMap { dir ->
                sequenceOf(
                    File(dir, "src/main/AndroidManifest.xml"),
                    File(dir, "app/src/main/AndroidManifest.xml"),
                )
            }
        return candidates.firstOrNull { it.isFile }
            ?: error("未找到 AndroidManifest.xml（user.dir=${System.getProperty("user.dir")}）")
    }
}

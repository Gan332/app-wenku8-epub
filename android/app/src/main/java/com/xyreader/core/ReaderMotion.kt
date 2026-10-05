package com.xyreader.core

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * 阅读器动效基建。
 *
 * 与宿主 `com.example.hyperreader.ui.Motion` 同源同值，但**独立声明**——理由同
 * [ReaderDimens]：阅读器保留上游包名 `com.xyreader`，而 `com.xyreader` 不得反向依赖
 * `com.example.hyperreader`（AGENTS §4.8 第 1 条）。宿主可以依赖阅读器，反向不行，
 * 所以动效基建也必须两边各有一份。
 *
 * 存在的理由：阅读器原先把工具栏进出（250ms）与缩放动画（200ms）写成硬编码常量，
 * 是全应用唯一不响应系统「移除动画」的界面——读者的无障碍开关对阅读器完全无效。
 * 统一走这里的 [duration] 后，系统动画缩放对阅读器与 App 其它页面行为一致。
 */
object ReaderMotion {

    /**
     * 系统动画缩放：0 = 关闭动画，1 = 正常，>1 = 加速（开发者选项）。
     *
     * 与宿主 `Motion.scale()` 同源：直接读 `Settings.Global.ANIMATOR_DURATION_SCALE`，
     * 不依赖 Compose 内部 API。
     */
    @Composable
    fun scale(): Float {
        val view = LocalView.current
        return remember(view) {
            runCatching {
                Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            }.getOrDefault(1f)
        }
    }

    /** 按系统缩放换算时长；关闭动画时返回 0。 */
    @Composable
    fun duration(base: Int = BAR_BASE): Int =
        if (scale() == 0f) 0 else (base * scale()).toInt()

    /**
     * 工具栏 / 弹层进出的基准时长（ms）。
     *
     * 取宿主 `UiDimens.MOTION_MEDIUM` 的 220ms 档；阅读器顶栏是整条横条，
     * 介于快（150）与慢（320）之间。
     */
    const val BAR_BASE = 220

    /** 缩放动画（双击放大 / 整列捏合）的基准时长（ms）。 */
    const val ZOOM_BASE = 200
}
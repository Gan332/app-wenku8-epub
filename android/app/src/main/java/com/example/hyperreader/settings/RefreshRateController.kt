package com.example.hyperreader.settings

import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import kotlin.math.abs

/**
 * 把用户选择的刷新率策略应用到当前窗口。
 *
 * 只改 `WindowManager.LayoutParams`（本 App 窗口级），不触碰系统设置：
 * - [RefreshRate.ADAPTIVE]：清零 preferred 字段，交回系统按内容/触控调度（Android 默认行为）
 * - 固定帧率：优先选**与当前分辨率相同**且最接近目标 Hz 的显示模式，
 *   避免为了高刷切到低分辨率；设备不支持时由系统钳到最接近的模式。
 *
 * 调用点：主界面 [com.example.hyperreader.ui.StudioApp] 与两个阅读器 Activity 的 onCreate。
 */
object RefreshRateController {

    fun apply(context: Context, rate: RefreshRate) {
        val activity = context.findActivity() ?: return
        apply(activity.window, rate)
    }

    fun apply(window: Window, rate: RefreshRate) {
        runCatching {
            val attrs = window.attributes
            val target = rate.hz
            if (target == null) {
                attrs.preferredRefreshRate = 0f
                attrs.preferredDisplayModeId = 0
            } else {
                val display = window.decorView.display
                val current = display?.mode
                val modes = display?.supportedModes.orEmpty()
                val sameSize = modes.filter { mode ->
                    current == null ||
                        (mode.physicalWidth == current.physicalWidth &&
                            mode.physicalHeight == current.physicalHeight)
                }
                val best = sameSize
                    .ifEmpty { modes.toList() }
                    .filter { it.refreshRate > 0f }
                    .minByOrNull { abs(it.refreshRate - target) }
                if (best != null) {
                    attrs.preferredDisplayModeId = best.modeId
                    attrs.preferredRefreshRate = best.refreshRate
                } else {
                    attrs.preferredRefreshRate = target
                    attrs.preferredDisplayModeId = 0
                }
            }
            window.attributes = attrs
        }
    }
}

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

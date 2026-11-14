package com.xyreader.core

import android.view.KeyEvent

/**
 * 音量键翻页桥。
 *
 * 阅读器界面在组合期注册处理函数，Activity 只负责把 `onKeyDown` 转发进来：
 * `enabled` 为 false 时返回 false，音量键交回系统调音量；开启后音量上=上一页、
 * 音量下=下一页。阅读器包不能反向依赖宿主，所以桥放在 `com.xyreader.core`。
 */
object ReaderVolumeKeys {

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var handler: ((Int) -> Unit)? = null

    fun setHandler(active: Boolean, onTurn: ((Int) -> Unit)?) {
        handler = onTurn
        enabled = active && onTurn != null
    }

    /** 返回 true 表示已在阅读器内消费，Activity 不再交给系统。 */
    fun dispatch(keyCode: Int): Boolean {
        if (!enabled) return false
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> -1
            KeyEvent.KEYCODE_VOLUME_DOWN -> 1
            else -> return false
        }
        val current = handler ?: return false
        current(delta)
        return true
    }

    fun clear() {
        handler = null
        enabled = false
    }
}

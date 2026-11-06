package com.example.hyperreader.reader

import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * 阅读器统一窗口配置：全面屏 + 允许绘制到刘海 / 水滴挖孔区。
 *
 * 只开 edge-to-edge 不够：Android 默认会把窗口限制在挖孔区之外（竖屏刘海尤其明显），
 * 屏幕边缘会留下黑边。声明 `SHORT_EDGES` 后窗口铺满整块屏幕，真正的避让交给
 * Compose 的 `WindowInsets.displayCutout` / `safeDrawing`（见 `ReaderScreen`），
 * 因此正文不会压在挖孔下面，背景色又能填满整屏。
 *
 * 本地 EPUB 与在线阅读共用同一套阅读界面，所以两个宿主 Activity 都调用这里。
 */
internal fun ComponentActivity.applyReaderWindowInsets() {
    // 阅读器底色固定偏暗：状态/导航栏图标一律用浅色，避免被系统亮色主题判成深色图标
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
}

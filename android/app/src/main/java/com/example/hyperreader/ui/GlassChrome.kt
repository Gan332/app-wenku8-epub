package com.example.hyperreader.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.material.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 悬浮胶囊底栏占用的内容高度；一级页列表据此留白，避免最后一项被压在胶囊下。 */
val LocalFloatingBarInset = compositionLocalOf { 0.dp }

/**
 * 悬浮底栏实际占用的高度：胶囊 [UiDimens.navHeight] + 它自带的底部安全区留白。
 * 与 [top.yukonga.miuix.kmp.glass.GlassNavigationBar] 的 `defaultWindowInsetsPadding`
 * 口径一致（见其 `bottomPaddingProvider`），避免列表最后一项被胶囊遮住。
 */
@Composable
fun rememberFloatingBarInset(): Dp {
    val density = LocalDensity.current
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val captionBottom = with(density) { WindowInsets.captionBar.getBottom(this).toDp() }
    val systemBottom = maxOf(navBottom, captionBottom)
    val barBottom = when {
        systemBottom <= 16.dp -> 20.dp
        systemBottom >= 28.dp -> systemBottom
        else -> UiDimens.floatingNavBottomGap + systemBottom
    }
    return UiDimens.navHeight + barBottom
}

/**
 * 悬浮底栏采样的 backdrop。关闭毛玻璃或系统不支持 RenderEffect 时返回 null，
 * 调用方据此回退到不透明表面（与 KernelSU 参考实现一致）。
 */
@Composable
fun rememberChromeBackdrop(enabled: Boolean): LayerBackdrop? {
    if (!enabled || !isRenderEffectSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

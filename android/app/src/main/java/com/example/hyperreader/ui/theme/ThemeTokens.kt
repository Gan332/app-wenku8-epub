package com.example.hyperreader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.hyperreader.settings.AppThemeSettings
import com.example.hyperreader.settings.toThemeSpec
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用层语义色令牌。
 *
 * MiuiX 的 `colorScheme` 是「组件色板」，但界面里还反复出现几类派生色：
 * 次要/弱化文字、面板描边、背景渐变、主视觉渐变、导航底板。此前它们散落在
 * 各页面用不同 alpha 现算，主题一换就要逐页调。这里一次性算好，页面只认语义。
 */
@Immutable
data class AppThemeTokens(
    val isDark: Boolean,
    val dynamic: Boolean,
    val accent: Color,
    val secondaryText: Color,
    val tertiaryText: Color,
    val hairline: Color,
    val panelBorder: Color,
    val backdropTop: Color,
    val backdropMiddle: Color,
    val backdropBottom: Color,
    val backdropGlow: Color,
    val backdropGlowSecondary: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val navSurface: Color,
    val chipSurface: Color,
)

internal val LocalAppThemeTokens = staticCompositionLocalOf<AppThemeTokens?> { null }

/**
 * 读取当前语义色。正常情况下由 [StudioTheme] 注入；预览、测试或忘记包主题时
 * 回退到当前 MiuiX 色板，避免直接崩溃。
 */
@Composable
fun appThemeTokens(): AppThemeTokens {
    val provided = LocalAppThemeTokens.current
    val fallback = derivedTokens(dynamic = false)
    return provided ?: fallback
}

@Composable
internal fun rememberAppThemeTokens(settings: AppThemeSettings): AppThemeTokens {
    val spec = settings.toThemeSpec()
    return derivedTokens(dynamic = spec.dynamic)
}

/**
 * 从当前 MiuiX 色板派生语义色。
 *
 * 这里不写 MiuiX 色板的具体类型名：色板类型是上游实现细节，直接读
 * `MiuixTheme.colorScheme` 既能少一个跨版本会改的 import，也不影响 remember 的键。
 */
@Composable
private fun derivedTokens(dynamic: Boolean): AppThemeTokens {
    val colors = MiuixTheme.colorScheme
    val isDark = colors.surface.luminance() < 0.5f
    return remember(colors, isDark, dynamic) {
        AppThemeTokens(
            isDark = isDark,
            dynamic = dynamic,
            accent = colors.primary,
            secondaryText = colors.onSurface.copy(alpha = 0.72f),
            tertiaryText = colors.onSurface.copy(alpha = 0.6f),
            hairline = colors.outline.copy(alpha = 0.16f),
            panelBorder = colors.outline.copy(alpha = 0.12f),
            backdropTop = colors.surface,
            backdropMiddle = colors.background,
            backdropBottom = colors.surfaceVariant.copy(alpha = 0.72f),
            backdropGlow = colors.primary.copy(alpha = if (isDark) 0.16f else 0.11f),
            backdropGlowSecondary = colors.secondaryContainer.copy(alpha = if (isDark) 0.16f else 0.12f),
            heroStart = colors.primaryContainer.copy(alpha = 0.92f),
            heroEnd = colors.surfaceContainerHigh.copy(alpha = 0.96f),
            navSurface = colors.surfaceContainerHigh,
            chipSurface = colors.surfaceContainer,
        )
    }
}

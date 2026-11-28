package com.example.hyperreader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hyperreader.R
import com.example.hyperreader.Wenku8Application
import com.example.hyperreader.settings.AppThemeSettings
import com.example.hyperreader.settings.ThemeScheme
import com.example.hyperreader.settings.ThemeSpec
import com.example.hyperreader.settings.toThemeSpec
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController

private val MiSansFont = FontFamily(Font(R.font.misansvf))

/**
 * 应用主题入口：设置 → 解析 → MiuiX 色板 → 语义令牌。
 *
 * 职责分三层：
 * 1. [AppThemeSettings] 只表达用户选择（明暗 / 动态色 / 强调色）；
 * 2. [toThemeSpec] 把旧数据归一成 [ThemeSpec]；
 * 3. 这里把 [ThemeSpec] 映射到 MiuiX 的 [ColorSchemeMode]，并注入 [AppThemeTokens]。
 *
 * 页面不直接判断 `mode` 与 `useDynamicColor`，只读 `appThemeTokens()`，
 * 避免主题逻辑在设置页、首页、阅读器之间各写一遍。
 */
@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Wenku8Application
    val settings by app.settingsRepository.appTheme.collectAsStateWithLifecycle(
        initialValue = AppThemeSettings(),
        lifecycle = LocalLifecycleOwner.current.lifecycle,
    )
    val spec = settings.toThemeSpec()
    val baseTextStyles = MiuixTheme.textStyles
    val textStyles = remember(baseTextStyles) {
        baseTextStyles.copy(
            main = baseTextStyles.main.copy(fontFamily = MiSansFont),
            paragraph = baseTextStyles.paragraph.copy(fontFamily = MiSansFont),
            body1 = baseTextStyles.body1.copy(fontFamily = MiSansFont),
            body2 = baseTextStyles.body2.copy(fontFamily = MiSansFont),
            button = baseTextStyles.button.copy(fontFamily = MiSansFont),
            footnote1 = baseTextStyles.footnote1.copy(fontFamily = MiSansFont),
            footnote2 = baseTextStyles.footnote2.copy(fontFamily = MiSansFont),
            headline2 = baseTextStyles.headline2.copy(fontFamily = MiSansFont),
            subtitle = baseTextStyles.subtitle.copy(fontFamily = MiSansFont),
            title1 = baseTextStyles.title1.copy(fontFamily = MiSansFont, fontWeight = FontWeight.SemiBold),
            title2 = baseTextStyles.title2.copy(fontFamily = MiSansFont, fontWeight = FontWeight.SemiBold),
            title3 = baseTextStyles.title3.copy(fontFamily = MiSansFont, fontWeight = FontWeight.SemiBold),
            title4 = baseTextStyles.title4.copy(fontFamily = MiSansFont),
            headline1 = baseTextStyles.headline1.copy(fontFamily = MiSansFont, fontWeight = FontWeight.SemiBold),
        )
    }
    MiuixTheme(
        controller = remember(spec) {
            ThemeController(
                colorSchemeMode = spec.toColorSchemeMode(),
                keyColor = Color(spec.accentColor),
                colorSpec = ThemeColorSpec.Spec2025,
            )
        },
        textStyles = textStyles,
    ) {
        CompositionLocalProvider(
            LocalAppThemeTokens provides rememberAppThemeTokens(settings),
            content = content,
        )
    }
}

private fun ThemeSpec.toColorSchemeMode(): ColorSchemeMode = if (dynamic) {
    when (scheme) {
        ThemeScheme.SYSTEM -> ColorSchemeMode.MonetSystem
        ThemeScheme.LIGHT -> ColorSchemeMode.MonetLight
        ThemeScheme.DARK -> ColorSchemeMode.MonetDark
    }
} else {
    when (scheme) {
        ThemeScheme.SYSTEM -> ColorSchemeMode.System
        ThemeScheme.LIGHT -> ColorSchemeMode.Light
        ThemeScheme.DARK -> ColorSchemeMode.Dark
    }
}

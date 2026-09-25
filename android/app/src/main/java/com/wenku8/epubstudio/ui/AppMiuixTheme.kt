package com.wenku8.epubstudio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wenku8.epubstudio.R
import com.wenku8.epubstudio.Wenku8Application
import com.wenku8.epubstudio.settings.AppThemeMode
import com.wenku8.epubstudio.settings.AppThemeSettings
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

private val MiSansFont = FontFamily(Font(R.font.misansvf))

@Composable
fun AppMiuixTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Wenku8Application
    val settings by app.settingsRepository.appTheme.collectAsStateWithLifecycle(
        initialValue = AppThemeSettings(),
        lifecycle = LocalLifecycleOwner.current.lifecycle,
    )
    val baseTextStyles = MiuixTheme.textStyles
    val textStyles = baseTextStyles.copy(
        main = baseTextStyles.main.copy(fontFamily = MiSansFont),
        paragraph = baseTextStyles.paragraph.copy(fontFamily = MiSansFont),
        body1 = baseTextStyles.body1.copy(fontFamily = MiSansFont),
        body2 = baseTextStyles.body2.copy(fontFamily = MiSansFont),
        button = baseTextStyles.button.copy(fontFamily = MiSansFont),
        footnote1 = baseTextStyles.footnote1.copy(fontFamily = MiSansFont),
        footnote2 = baseTextStyles.footnote2.copy(fontFamily = MiSansFont),
        headline1 = baseTextStyles.headline1.copy(fontFamily = MiSansFont),
        headline2 = baseTextStyles.headline2.copy(fontFamily = MiSansFont),
        subtitle = baseTextStyles.subtitle.copy(fontFamily = MiSansFont),
        title1 = baseTextStyles.title1.copy(fontFamily = MiSansFont),
        title2 = baseTextStyles.title2.copy(fontFamily = MiSansFont),
        title3 = baseTextStyles.title3.copy(fontFamily = MiSansFont),
        title4 = baseTextStyles.title4.copy(fontFamily = MiSansFont),
    )
    MiuixTheme(
        controller = remember(settings.mode, settings.useDynamicColor, settings.accentColor) {
            ThemeController(
                colorSchemeMode = if (settings.useDynamicColor) {
                    when (settings.mode) {
                        AppThemeMode.SYSTEM -> ColorSchemeMode.MonetSystem
                        AppThemeMode.LIGHT -> ColorSchemeMode.MonetLight
                        AppThemeMode.DARK -> ColorSchemeMode.MonetDark
                        AppThemeMode.MONET -> ColorSchemeMode.MonetSystem
                    }
                } else {
                    when (settings.mode) {
                        AppThemeMode.SYSTEM -> ColorSchemeMode.System
                        AppThemeMode.LIGHT -> ColorSchemeMode.Light
                        AppThemeMode.DARK -> ColorSchemeMode.Dark
                        AppThemeMode.MONET -> ColorSchemeMode.System
                    }
                },
                keyColor = Color(settings.accentColor),
            )
        },
        textStyles = textStyles,
        content = content,
    )
}

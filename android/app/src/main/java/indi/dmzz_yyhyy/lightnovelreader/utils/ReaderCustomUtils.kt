package indi.dmzz_yyhyy.lightnovelreader.utils

import android.net.Uri
import android.util.Log
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
// LNR 引入适配：Coil 画笔 → 本工程图片管线（http→CoverRepository 限流链 / 本地→直解降采样，AGENTS 4.7）
import androidx.compose.ui.graphics.painter.BitmapPainter
import com.example.hyperreader.R
import indi.dmzz_yyhyy.lightnovelreader.ui.LocalAppTheme
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.SettingState
import indi.dmzz_yyhyy.lightnovelreader.ui.components.LnrReaderImage
import indi.dmzz_yyhyy.lightnovelreader.ui.components.rememberLnrReaderImage
import io.nightfish.lightnovelreader.api.userdata.UriUserData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

const val KRAFT_PAPER_URL = "https://portal.curiousers.org/static/lnr/paper.webp"
const val KRAFT_PAPER_CACHE_KEY = "default_kraft_paper"

fun loadReaderFontFamilySafe(uri: Uri): FontFamily? {
    return try {
        if (uri == Uri.EMPTY) return null
        val fontFile = File(uri.path ?: return null)
        if (!fontFile.exists()) throw FileNotFoundException()
        FontFamily(Font(fontFile))
    } catch (e: Exception) {
        Log.e("FontLoad", "Failed to load custom font", e)
        null
    }
}

@Composable
fun rememberReaderFontFamily(
    fontFamilyUriUserData: UriUserData,
): FontFamily {
    val snackbarScope = rememberCoroutineScope()
    val uri by fontFamilyUriUserData.getFlowWithDefault(Uri.EMPTY)
        .collectAsStateWithLifecycle(Uri.EMPTY)
    val fontFamily = remember(uri) { loadReaderFontFamilySafe(uri) }

    val snackbarHostState = LocalSnackbarHost.current
    val message = stringResource(R.string.reader_custom_font_load_failed)
    if (fontFamily == null && uri != Uri.EMPTY) {
        LaunchedEffect(uri) {
            withContext(Dispatchers.IO) { fontFamilyUriUserData.set(Uri.EMPTY) }
            snackbarScope.launch {
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    return fontFamily ?: FontFamily.Default
}

@Composable
private fun rememberPaperPainter(): Painter {
    val theme = LocalAppTheme.current
    val fallback = remember(theme.isDark, theme.MiuixTheme.colorScheme.background) {
        ColorPainter(theme.MiuixTheme.colorScheme.background)
    }

    // 内置牛皮纸是联网资源：走 rememberLnrReaderImage（http → CoverRepository，
    // 限流 + 缓存）。加载失败/进行中静默回退背景色，不打扰用户（原 Coil 语义一致）。
    val image = rememberLnrReaderImage(Uri.parse(KRAFT_PAPER_URL))
    return if (image is LnrReaderImage.Ready) {
        remember(image.bitmap) { BitmapPainter(image.bitmap) }
    } else {
        fallback
    }
}

@Composable
private fun rememberCustomBackgroundPainter(
    uri: Uri,
    snackbarScope: CoroutineScope,
): Painter {
    val theme = LocalAppTheme.current
    val fallback = remember(theme.isDark, theme.MiuixTheme.colorScheme.background) {
        ColorPainter(theme.MiuixTheme.colorScheme.background)
    }
    val snackbarHostState = LocalSnackbarHost.current
    val message = stringResource(R.string.reader_custom_background_load_failed)

    val image = rememberLnrReaderImage(uri)

    var errorNotified by remember(uri) { mutableStateOf(false) }
    if (image is LnrReaderImage.Failed && !errorNotified) {
        errorNotified = true
        snackbarScope.launch {
            snackbarHostState.showSnackbar(message)
        }
    }

    return if (image is LnrReaderImage.Ready) {
        remember(image.bitmap) { BitmapPainter(image.bitmap) }
    } else {
        fallback
    }
}

@Composable
fun rememberReaderBackgroundPainter(
    settingState: SettingState,
): Painter {
    val isDark = LocalAppTheme.current.isDark
    val snackbarScope = rememberCoroutineScope()

    val backgroundUri = remember(
        isDark,
        settingState.backgroundImageUri,
        settingState.backgroundDarkImageUri
    ) {
        if (isDark) settingState.backgroundDarkImageUri else settingState.backgroundImageUri
    }

    if (backgroundUri == Uri.EMPTY || backgroundUri.toString().isBlank()) {
        return rememberPaperPainter()
    }

    return rememberCustomBackgroundPainter(backgroundUri, snackbarScope)
}

@Composable
fun readerBackgroundColor(settingState: SettingState): Color {
    val localTheme = LocalAppTheme.current
    val isDark = localTheme.isDark
    val background = localTheme.MiuixTheme.colorScheme.background

    val color = remember(
        isDark,
        settingState.backgroundColor,
        settingState.backgroundDarkColor,
        background
    ) {
        when {
            isDark && settingState.backgroundDarkColor.isUnspecified -> background
            !isDark && settingState.backgroundColor.isUnspecified -> background
            isDark -> settingState.backgroundDarkColor
            else -> settingState.backgroundColor
        }
    }

    return color
}

@Composable
fun readerTextColor(settingState: SettingState): Color {
    val localTheme = LocalAppTheme.current
    val isDark = localTheme.isDark
    val onSurface = localTheme.MiuixTheme.colorScheme.onSurface

    val color = remember(isDark, settingState.textColor, settingState.textDarkColor, onSurface) {
        when {
            isDark && settingState.textDarkColor.isUnspecified -> onSurface
            !isDark && settingState.textColor.isUnspecified -> onSurface
            isDark -> settingState.textDarkColor
            else -> settingState.textColor
        }
    }

    return color
}

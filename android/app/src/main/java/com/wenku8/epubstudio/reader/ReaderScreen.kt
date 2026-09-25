package com.wenku8.epubstudio.reader

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wenku8.epubstudio.R
import com.wenku8.epubstudio.file.FontStore
import com.wenku8.epubstudio.settings.ReaderBackground
import com.wenku8.epubstudio.settings.ReaderPageTurnMode
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.util.zip.ZipFile

@Composable
fun ReaderScreen(viewModel: ReaderViewModel, onImportFont: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val book = state.book
    val settings = state.settings
    val palette = readerPalette(settings)
    BackHandler(onBack = { viewModel.showSettings(false) })

    Scaffold(
        topBar = {
            TopAppBar(
                title = book?.chapters?.getOrNull(state.chapterIndex)?.title ?: "EPUB 阅读器",
                navigationIcon = { TextButton(text = "返回", onClick = { viewModel.showSettings(false) }) },
                actions = {
                    TextButton(text = "目录", onClick = { viewModel.showToc(true) })
                    TextButton(text = "设置", onClick = { viewModel.showSettings(true) })
                },
            )
        },
        containerColor = palette.background,
    ) { padding ->
        when {
            state.loading -> MiuixText("正在打开 EPUB…", Modifier.padding(padding).padding(24.dp))
            state.error != null -> MiuixText(state.error, Modifier.padding(padding).padding(24.dp), color = Color(0xFFB3261E))
            book != null -> ReaderContent(book, state, viewModel, palette, Modifier.padding(padding))
        }
    }

    if (state.showToc && book != null) {
        SuperDialog(show = true, title = "目录", summary = "共 ${book.chapters.size} 章", onDismissRequest = { viewModel.showToc(false) }) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(book.chapters) { index, chapter ->
                    TextButton(
                        text = "${index + 1}. ${chapter.title}",
                        onClick = { viewModel.selectChapter(index) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
    if (state.showSettings) {
        ReaderSettingsDialog(
            settings = settings,
            onDismiss = { viewModel.showSettings(false) },
            onFontSize = viewModel::updateFontSize,
            onFontWeight = viewModel::updateFontWeight,
            onLineHeight = viewModel::updateLineHeight,
            onSpacing = viewModel::updateSpacing,
            onPadding = viewModel::updatePadding,
            onBackground = viewModel::updateBackground,
            onBackgroundColor = viewModel::updateBackgroundColor,
            onTextColor = viewModel::updateTextColor,
            onPageMode = viewModel::updatePageMode,
            onKeepScreenOn = viewModel::updateKeepScreenOn,
            onImportFont = onImportFont,
            onResetFont = { viewModel.updateFontUri(null) },
        )
    }
}

@Composable
private fun ReaderContent(book: ReaderBook, state: ReaderUiState, viewModel: ReaderViewModel, palette: ReaderPalette, modifier: Modifier) {
    val fontFamily = rememberFont(state.settings.fontUri)
    if (state.settings.pageTurnMode == ReaderPageTurnMode.HORIZONTAL) {
        val pagerState = rememberPagerState(initialPage = state.chapterIndex, pageCount = { book.chapters.size })
        LaunchedEffect(state.chapterIndex) { if (pagerState.currentPage != state.chapterIndex) pagerState.animateScrollToPage(state.chapterIndex) }
        LaunchedEffect(pagerState.currentPage) { if (pagerState.currentPage != state.chapterIndex) viewModel.selectChapter(pagerState.currentPage) }
        HorizontalPager(state = pagerState, modifier = modifier.fillMaxSize().background(palette.background)) { page ->
            ChapterContent(book.chapters[page], state.settings, fontFamily, palette, Modifier.fillMaxSize(), book.archivePath) { viewModel.setParagraph(it) }
        }
    } else {
        val chapter = book.chapters.getOrNull(state.chapterIndex) ?: return
        ChapterContent(chapter, state.settings, fontFamily, palette, modifier.fillMaxSize(), book.archivePath) { viewModel.setParagraph(it) }
    }
}

@Composable
private fun ChapterContent(chapter: ReaderChapter, settings: com.wenku8.epubstudio.settings.ReaderSettings, fontFamily: FontFamily, palette: ReaderPalette, modifier: Modifier, archivePath: String, onParagraph: (Int) -> Unit) {
    var paragraphNumber = 0
    LazyColumn(
        modifier = modifier.clickable { },
        contentPadding = PaddingValues(horizontal = settings.horizontalPaddingDp.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(settings.paragraphSpacingDp.dp),
    ) {
        itemsIndexed(chapter.blocks) { index, block ->
            when (block) {
                is ReaderBlock.Heading -> MiuixText(block.text, fontSize = (settings.fontSizeSp + 6).sp, fontWeight = FontWeight.Bold, fontFamily = fontFamily, color = palette.text, modifier = Modifier.padding(top = 10.dp))
                is ReaderBlock.Paragraph -> {
                    val paragraph = paragraphNumber++
                    androidx.compose.runtime.LaunchedEffect(paragraph) { onParagraph(paragraph) }
                    MiuixText(block.text, fontSize = settings.fontSizeSp.sp, lineHeight = (settings.fontSizeSp * settings.lineHeight).sp, fontWeight = FontWeight(settings.fontWeight), fontFamily = fontFamily, color = palette.text, softWrap = true)
                }
                is ReaderBlock.Image -> {
                    val bitmap = rememberEpubImage(archivePath, block.path)
                    if (bitmap != null) Image(bitmap, contentDescription = block.alt, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun ReaderSettingsDialog(
    settings: com.wenku8.epubstudio.settings.ReaderSettings,
    onDismiss: () -> Unit,
    onFontSize: (Float) -> Unit,
    onFontWeight: (Int) -> Unit,
    onLineHeight: (Float) -> Unit,
    onSpacing: (Int) -> Unit,
    onPadding: (Int) -> Unit,
    onBackground: (ReaderBackground) -> Unit,
    onBackgroundColor: (Int) -> Unit,
    onTextColor: (Int) -> Unit,
    onPageMode: (ReaderPageTurnMode) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onImportFont: () -> Unit,
    onResetFont: () -> Unit,
) {
    SuperDialog(show = true, title = "阅读设置", onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { MiuixText("背景") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReaderBackground.entries.forEach { background ->
                        TextButton(text = when (background) { ReaderBackground.PAPER -> "米黄"; ReaderBackground.LIGHT -> "白"; ReaderBackground.GREEN -> "护眼"; ReaderBackground.DARK -> "夜间"; ReaderBackground.OLED -> "OLED"; ReaderBackground.CUSTOM -> "自定义" }, onClick = { onBackground(background) })
                    }
                }
            }
            item { MiuixText("字号：${settings.fontSizeSp.toInt()} sp") }
            item { Slider(settings.fontSizeSp, { onFontSize(it) }, valueRange = 12f..32f, steps = 19) }
            item { MiuixText("字重：${settings.fontWeight}") }
            item { Slider(settings.fontWeight.toFloat(), { onFontWeight(it.toInt()) }, valueRange = 100f..900f, steps = 7) }
            item { MiuixText("行高：${"%.1f".format(settings.lineHeight)}") }
            item { Slider(settings.lineHeight, { onLineHeight(it) }, valueRange = 1.2f..2.6f, steps = 13) }
            item { MiuixText("段距：${settings.paragraphSpacingDp} dp") }
            item { Slider(settings.paragraphSpacingDp.toFloat(), { onSpacing(it.toInt()) }, valueRange = 0f..48f, steps = 47) }
            item { MiuixText("页边距：${settings.horizontalPaddingDp} dp") }
            item { Slider(settings.horizontalPaddingDp.toFloat(), { onPadding(it.toInt()) }, valueRange = 0f..48f, steps = 47) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(text = if (settings.pageTurnMode == ReaderPageTurnMode.HORIZONTAL) "左右翻页" else "上下滚动", onClick = { onPageMode(if (settings.pageTurnMode == ReaderPageTurnMode.HORIZONTAL) ReaderPageTurnMode.VERTICAL else ReaderPageTurnMode.HORIZONTAL) })
                    TextButton(text = "导入字体", onClick = onImportFont)
                    TextButton(text = "恢复默认字体", onClick = onResetFont)
                }
            }
            item { MiuixText("自定义背景色") }
            item { ColorSettingRow(onColorChanged = onBackgroundColor) }
            item { MiuixText("文字颜色") }
            item { ColorSettingRow(onColorChanged = onTextColor) }
            item { TextButton(text = if (settings.keepScreenOn) "保持屏幕常亮：开" else "保持屏幕常亮：关", onClick = { onKeepScreenOn(!settings.keepScreenOn) }) }
            item { Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { MiuixText("完成") } }
        }
    }
}

@Composable
private fun ColorSettingRow(onColorChanged: (Int) -> Unit) {
    val colors = listOf(0xFFF4EFE6.toInt(), 0xFFFFFFFF.toInt(), 0xFFE7F0DF.toInt(), 0xFF17191C.toInt(), 0xFFB3261E.toInt(), 0xFF2D6A4F.toInt())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        colors.forEach { color ->
            Box(Modifier.size(32.dp).background(Color(color)).clickable { onColorChanged(color) })
        }
    }
}

private data class ReaderPalette(val background: Color, val text: Color)

private fun readerPalette(settings: com.wenku8.epubstudio.settings.ReaderSettings): ReaderPalette = when (settings.background) {
    ReaderBackground.PAPER -> ReaderPalette(Color(0xFFF4EFE6), Color(settings.textColor))
    ReaderBackground.LIGHT -> ReaderPalette(Color(0xFFFFFFFF), Color(settings.textColor))
    ReaderBackground.GREEN -> ReaderPalette(Color(0xFFE7F0DF), Color(settings.textColor))
    ReaderBackground.DARK -> ReaderPalette(Color(0xFF17191C), Color.White)
    ReaderBackground.OLED -> ReaderPalette(Color.Black, Color.White)
    ReaderBackground.CUSTOM -> ReaderPalette(Color(settings.customBackgroundColor), Color(settings.textColor))
}

@Composable
private fun rememberFont(uri: String?): FontFamily {
    val default = remember { FontFamily(Font(R.font.misansvf)) }
    if (uri.isNullOrBlank()) return default
    val file = File(uri)
    return if (file.isFile) runCatching { FontFamily(Font(file)) }.getOrDefault(default) else default
}

@Composable
private fun rememberEpubImage(archivePath: String, path: String): ImageBitmap? = remember(archivePath, path) {
    runCatching {
        val archive = File(archivePath)
        ZipFile(archive).use { zip -> zip.getInputStream(zip.getEntry(path)).use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }
    }.getOrNull()
}

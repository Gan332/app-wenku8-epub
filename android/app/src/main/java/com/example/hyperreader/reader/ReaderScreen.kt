@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.hyperreader.reader

import android.app.Activity
import android.graphics.BitmapFactory
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hyperreader.R
import com.example.hyperreader.settings.ReaderBackground
import com.example.hyperreader.settings.ReaderPageTurnMode
import com.example.hyperreader.settings.ReaderSettings
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TOUCH_TARGET = 48
private const val CONTROL_BAR_HEIGHT = 56

/**
 * EPUB 阅读界面。**签名保持不变**，实现委托给 [ReaderScreenCore]。
 */
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onImportFont: () -> Unit,
    onImportEpub: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val archivePath = state.book?.archivePath.orEmpty()
    ReaderScreenCore(
        state = state,
        actions = viewModel,
        imageResolver = { block: ReaderBlock.Image -> rememberEpubImage(archivePath, block.path) },
        onImportFont = onImportFont,
        onImportEpub = onImportEpub,
        onBack = onBack,
    )
}

/**
 * 阅读界面的真正实现：EPUB 与 wenku8 在线阅读**共用这一份**，
 * 因此字体、背景、沉浸模式、目录面板、阅读进度回调在两种模式下行为完全一致。
 *
 * @param state 由各自的 ViewModel 持有。
 * @param actions 界面回调。EPUB 侧传 `ReaderViewModel`，在线侧传 `OnlineReaderViewModel`（都实现 [ReaderActions]）。
 * @param imageResolver 正文插图解析：EPUB 走 zip 条目，在线走 [rememberRemoteImage] 的网络图片。
 */
@Composable
fun ReaderScreenCore(
    state: ReaderUiState,
    actions: ReaderActions,
    imageResolver: @Composable (block: ReaderBlock.Image) -> ImageBitmap?,
    onImportFont: () -> Unit,
    onImportEpub: () -> Unit,
    onBack: () -> Unit,
) {
    val book = state.book
    val settings = state.settings
    val palette = readerPalette(settings)
    val error = state.error
    val immersive = state.isImmersive && !state.showSettings && !state.showToc

    ReaderSystemBarsEffect(immersive, settings.keepScreenOn)
    BackHandler {
        when {
            state.showSettings -> actions.showSettings(false)
            state.showToc -> actions.showToc(false)
            state.isImmersive -> actions.setImmersive(false)
            else -> onBack()
        }
    }

    Scaffold(
        topBar = {
            AnimatedVisibility(
                visible = !immersive,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            ) {
                TopAppBar(
                    title = book?.chapters?.getOrNull(state.chapterIndex)?.title ?: "阅读器",
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.size(TOUCH_TARGET.dp)) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { actions.showToc(true) }, modifier = Modifier.size(TOUCH_TARGET.dp)) {
                            Icon(Icons.Default.MenuBook, contentDescription = "目录")
                        }
                        IconButton(onClick = { actions.showSettings(true) }, modifier = Modifier.size(TOUCH_TARGET.dp)) {
                            Icon(Icons.Default.Tune, contentDescription = "设置")
                        }
                    },
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = !immersive,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
                ReaderBottomBar(state, actions)
            }
        },
        containerColor = palette.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(palette.background).pointerInput(Unit) {
            detectTapGestures(onTap = { actions.toggleControls() })
        }) {
            // Crossfade 而非硬切换：打开、加载失败、就绪之间过渡更连贯
            Crossfade(targetState = when {
                state.loading -> 0
                error != null -> 1
                else -> 2
            }, label = "readerBody") { target ->
                when (target) {
                    0 -> Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(size = 24.dp)
                        MiuixText("正在打开…", color = palette.text, fontSize = 14.sp)
                    }
                    1 -> MiuixText(error.orEmpty(), Modifier.padding(24.dp), color = Color(0xFFB3261E))
                    else -> book?.let { ReaderContent(it, state, actions, palette, imageResolver) }
                }
            }
            // 在线模式的一次性提示（如「该内容需要登录」），不遮挡已渲染的正文。
            val notice = state.notice
            if (notice != null && error == null) {
                MiuixText(
                    notice,
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp)
                        .background(palette.background.copy(alpha = .92f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    fontSize = 13.sp,
                    color = Color(0xFFB3261E),
                )
            }
        }
    }

    if (state.showToc && book != null) {
        ReaderTocSheet(book, state.chapterIndex, onSelect = actions::selectChapter, onDismiss = { actions.showToc(false) })
    }
    if (state.showSettings) {
        ReaderSettingsSheet(
            settings = settings,
            onDismiss = { actions.showSettings(false) },
            onFontSize = actions::updateFontSize,
            onFontWeight = actions::updateFontWeight,
            onLineHeight = actions::updateLineHeight,
            onSpacing = actions::updateSpacing,
            onPadding = actions::updatePadding,
            onBackground = actions::updateBackground,
            onBackgroundColor = actions::updateBackgroundColor,
            onTextColor = actions::updateTextColor,
            onPageMode = actions::updatePageMode,
            onKeepScreenOn = actions::updateKeepScreenOn,
            onImmersive = actions::updateImmersive,
            onImportFont = onImportFont,
            onImportEpub = onImportEpub,
            onResetFont = { actions.updateFontUri(null) },
        )
    }
}

@Composable
private fun ReaderBottomBar(state: ReaderUiState, actions: ReaderActions) {
    val chapterCount = state.book?.chapters?.size ?: 0
    val hasPrevious = state.chapterIndex > 0
    val hasNext = state.chapterIndex < chapterCount - 1
    Row(
        modifier = Modifier.fillMaxWidth().height(CONTROL_BAR_HEIGHT.dp).background(MiuixTheme.colorScheme.background).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = actions::previousChapter, enabled = hasPrevious, modifier = Modifier.size(TOUCH_TARGET.dp)) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "上一章")
        }
        MiuixText("${state.chapterIndex + 1} / $chapterCount", fontSize = 14.sp, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(onClick = { actions.showToc(true) }, modifier = Modifier.size(TOUCH_TARGET.dp)) {
                Icon(Icons.Default.MenuBook, contentDescription = "目录")
            }
            IconButton(onClick = { actions.showSettings(true) }, modifier = Modifier.size(TOUCH_TARGET.dp)) {
                Icon(Icons.Default.Tune, contentDescription = "设置")
            }
            IconButton(onClick = actions::nextChapter, enabled = hasNext, modifier = Modifier.size(TOUCH_TARGET.dp)) {
                Icon(Icons.Default.ChevronRight, contentDescription = "下一章")
            }
        }
    }
}

@Composable
private fun ReaderContent(
    book: ReaderBook,
    state: ReaderUiState,
    actions: ReaderActions,
    palette: ReaderPalette,
    imageResolver: @Composable (block: ReaderBlock.Image) -> ImageBitmap?,
) {
    val fontFamily = rememberFont(state.settings.fontUri)
    if (state.settings.pageTurnMode == ReaderPageTurnMode.HORIZONTAL) {
        val pagerState = rememberPagerState(initialPage = state.chapterIndex, pageCount = { book.chapters.size })
        LaunchedEffect(state.chapterIndex) { if (pagerState.currentPage != state.chapterIndex) pagerState.animateScrollToPage(state.chapterIndex) }
        LaunchedEffect(pagerState.currentPage) { if (pagerState.currentPage != state.chapterIndex) actions.selectChapter(pagerState.currentPage) }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val chapter = book.chapters.getOrNull(page) ?: return@HorizontalPager
            ChapterContent(chapter, state.settings, fontFamily, palette, imageResolver) { actions.setParagraph(it) }
        }
    } else {
        SeamlessContent(book, state, actions, palette, imageResolver, fontFamily)
    }
}

/** 上下滚动模式：全书一个 LazyColumn，跨章节连续滚动。 */
@Composable
private fun SeamlessContent(
    book: ReaderBook,
    state: ReaderUiState,
    actions: ReaderActions,
    palette: ReaderPalette,
    imageResolver: @Composable (block: ReaderBlock.Image) -> ImageBitmap?,
    fontFamily: FontFamily,
) {
    val flat = remember(book.id, book.chapters.size) { flattenBook(book) }
    val listState = rememberLazyListState()

    LaunchedEffect(listState, flat.size) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                val item = flat.getOrNull(index) ?: return@collect
                if (item.chapterIndex != state.chapterIndex) actions.selectChapter(item.chapterIndex)
                if (item.paragraphIndex >= 0) actions.setParagraph(item.paragraphIndex)
            }
    }

    // 外部跳转（目录/进度恢复）时对齐到目标章节
    LaunchedEffect(state.chapterIndex, flat.size) {
        val target = flat.indexOfFirst { it.chapterIndex == state.chapterIndex }
        if (target >= 0 && target != listState.firstVisibleItemIndex && !listState.isScrollInProgress) {
            listState.scrollToItem(target)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(palette.background),
        contentPadding = PaddingValues(horizontal = state.settings.horizontalPaddingDp.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(state.settings.paragraphSpacingDp.dp),
    ) {
        itemsIndexed(flat, key = { _, item -> item.key }) { _, item ->
            // 章节交界处插入章名，读者才知道自己进入了新的一章
            if (item.isChapterStart) {
                MiuixText(
                    item.chapterTitle,
                    fontSize = (state.settings.fontSizeSp + 4).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = fontFamily,
                    color = palette.text,
                    modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
                )
            }
            when (val block = item.block) {
                is ReaderBlock.Heading -> MiuixText(block.text, fontSize = (state.settings.fontSizeSp + 6).sp, fontWeight = FontWeight.Bold, fontFamily = fontFamily, color = palette.text, modifier = Modifier.padding(top = 10.dp))
                is ReaderBlock.Paragraph -> MiuixText(block.text, fontSize = state.settings.fontSizeSp.sp, lineHeight = (state.settings.fontSizeSp * state.settings.lineHeight).sp, fontWeight = FontWeight(state.settings.fontWeight), fontFamily = fontFamily, color = palette.text, softWrap = true)
                is ReaderBlock.Image -> imageResolver(block)?.let { bitmap ->
                    Image(bitmap, contentDescription = block.alt, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun ChapterContent(
    chapter: ReaderChapter,
    settings: ReaderSettings,
    fontFamily: FontFamily,
    palette: ReaderPalette,
    imageResolver: @Composable (block: ReaderBlock.Image) -> ImageBitmap?,
    onParagraph: (Int) -> Unit,
) {
    // 段落下标由 blocks 顺序**推导**得出。
    // 之前用 `var paragraphNumber = 0` 在组合期自增，既不是稳定状态，
    // 又会把「最后一个被组合的项」当成阅读位置写回，导致下标漂移。
    fun paragraphIndexAt(blockIndex: Int): Int =
        chapter.blocks.take(blockIndex.coerceIn(0, chapter.blocks.size)).count { it is ReaderBlock.Paragraph }

    val listState = rememberLazyListState()
    LaunchedEffect(listState, chapter.id) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { onParagraph(paragraphIndexAt(it)) }
    }

    LazyColumn(
        state = listState,
        // 这里不能挂 detectTapGestures：它会吞掉全部点击，
        // 父层的 toggleControls() 永远收不到事件，沉浸模式就成了单向陷阱。
        modifier = Modifier.fillMaxSize().background(palette.background),
        contentPadding = PaddingValues(horizontal = settings.horizontalPaddingDp.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(settings.paragraphSpacingDp.dp),
    ) {
        itemsIndexed(chapter.blocks, key = { index, block -> "${chapter.id}-$index-${block::class.simpleName}" }) { index, block ->
            when (block) {
                is ReaderBlock.Heading -> MiuixText(block.text, fontSize = (settings.fontSizeSp + 6).sp, fontWeight = FontWeight.Bold, fontFamily = fontFamily, color = palette.text, modifier = Modifier.padding(top = 10.dp))
                is ReaderBlock.Paragraph -> {
                    MiuixText(block.text, fontSize = settings.fontSizeSp.sp, lineHeight = (settings.fontSizeSp * settings.lineHeight).sp, fontWeight = FontWeight(settings.fontWeight), fontFamily = fontFamily, color = palette.text, softWrap = true)
                }
                is ReaderBlock.Image -> imageResolver(block)?.let { bitmap ->
                    Image(bitmap, contentDescription = block.alt, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun ReaderTocSheet(book: ReaderBook, current: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    // 用 MiuiX 的 OverlayBottomSheet 而非 material3 的 ModalBottomSheet：
    // MiuiX 0.9.4 传递依赖的 material3 与本工程编译期不一致，运行期抛
    // NoSuchMethodError: ModalBottomSheet-dYc4hso（真机点设置/目录必崩）。
    OverlayBottomSheet(show = true, title = "目录", onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(book.chapters) { index, chapter ->
                TextButton(
                    text = "${if (index == current) "● " else ""}${index + 1}. ${chapter.title}",
                    onClick = { onSelect(index) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET.dp),
                )
            }
        }
    }
}

@Composable
private fun ReaderSettingsSheet(
    settings: ReaderSettings,
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
    onImmersive: (Boolean) -> Unit,
    onImportFont: () -> Unit,
    onImportEpub: () -> Unit,
    onResetFont: () -> Unit,
) {
    OverlayBottomSheet(show = true, title = "阅读设置", onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 650.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { MiuixText("阅读设置", fontSize = 21.sp, fontWeight = FontWeight.Bold) }
            item { MiuixText("背景", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .7f)) }
            // 色块预览：直接看到选中的底色与它上面文字的对比度，
            // 避免选完才发现「黑底黑字」。
            item {
                val palette = readerPalette(settings)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ReaderBackground.entries.toList()) { background ->
                        val swatch = when (background) {
                            ReaderBackground.PAPER -> Color(0xFFF4EFE6)
                            ReaderBackground.LIGHT -> Color(0xFFFFFFFF)
                            ReaderBackground.GREEN -> Color(0xFFE7F0DF)
                            ReaderBackground.DARK -> Color(0xFF17191C)
                            ReaderBackground.OLED -> Color.Black
                            ReaderBackground.CUSTOM -> Color(settings.customBackgroundColor)
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(swatch)
                                .clickable { onBackground(background) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            MiuixText(
                                text = when (background) {
                                    ReaderBackground.PAPER -> "米黄"; ReaderBackground.LIGHT -> "白纸"; ReaderBackground.GREEN -> "护眼"
                                    ReaderBackground.DARK -> "夜间"; ReaderBackground.OLED -> "OLED"; ReaderBackground.CUSTOM -> "自定义"
                                },
                                color = readableTextOn(swatch, Color(settings.textColor)),
                                fontSize = 13.sp,
                                fontWeight = if (settings.background == background) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
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
            item { MiuixText("左右边距：${settings.horizontalPaddingDp} dp") }
            item { Slider(settings.horizontalPaddingDp.toFloat(), { onPadding(it.toInt()) }, valueRange = 0f..48f, steps = 47) }
            item { MiuixText("自定义背景颜色", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .7f)) }
            item { ColorSettingRow(onColorChanged = onBackgroundColor) }
            item { MiuixText("文字颜色", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .7f)) }
            item { ColorSettingRow(onColorChanged = onTextColor) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(text = if (settings.pageTurnMode == ReaderPageTurnMode.HORIZONTAL) "左右章节" else "上下滚动", onClick = { onPageMode(if (settings.pageTurnMode == ReaderPageTurnMode.HORIZONTAL) ReaderPageTurnMode.VERTICAL else ReaderPageTurnMode.HORIZONTAL) }, modifier = Modifier.weight(1f))
                    TextButton(text = "导入字体", onClick = onImportFont, modifier = Modifier.weight(1f))
                }
            }
            item { TextButton(text = "恢复默认字体", onClick = onResetFont, modifier = Modifier.fillMaxWidth()) }
            item { TextButton(text = "导入 EPUB", onClick = onImportEpub, modifier = Modifier.fillMaxWidth()) }
            item { TextButton(text = if (settings.keepScreenOn) "保持屏幕常亮：开" else "保持屏幕常亮：关", onClick = { onKeepScreenOn(!settings.keepScreenOn) }, modifier = Modifier.fillMaxWidth()) }
            item { TextButton(text = if (settings.immersiveMode) "沉浸模式：开" else "沉浸模式：关", onClick = { onImmersive(!settings.immersiveMode) }, modifier = Modifier.fillMaxWidth()) }
            item { TextButton(text = "完成", onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET.dp)) }
        }
    }
}

@Composable
private fun ColorSettingRow(onColorChanged: (Int) -> Unit) {
    val colors = listOf(0xFFF4EFE6.toInt(), 0xFFFFFFFF.toInt(), 0xFFE7F0DF.toInt(), 0xFF17191C.toInt(), 0xFFB3261E.toInt(), 0xFF2D6A4F.toInt())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        colors.forEach { color -> Box(Modifier.size(40.dp).background(Color(color)).pointerInput(Unit) { detectTapGestures { onColorChanged(color) } }) }
    }
}

private data class ReaderPalette(val background: Color, val text: Color)

/** WCAG 相对亮度：>0.5 视为浅色底，应配深色字。 */
internal fun relativeLuminance(color: Color): Double {
    fun channel(v: Float): Double {
        val c = v.toDouble()
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

/** 对比度不足时强制纠正文字色，避免黑底黑字。 */
internal fun readableTextOn(background: Color, preferred: Color): Color {
    val ratio = { fg: Color ->
        val l1 = relativeLuminance(fg)
        val l2 = relativeLuminance(background)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        (lighter + 0.05) / (darker + 0.05)
    }
    return if (ratio(preferred) >= MIN_TEXT_CONTRAST) preferred else Color.White
}

internal const val MIN_TEXT_CONTRAST = 4.5

private fun readerPalette(settings: ReaderSettings): ReaderPalette {
    val background = when (settings.background) {
        ReaderBackground.PAPER -> Color(0xFFF4EFE6)
        ReaderBackground.LIGHT -> Color(0xFFFFFFFF)
        ReaderBackground.GREEN -> Color(0xFFE7F0DF)
        ReaderBackground.DARK -> Color(0xFF17191C)
        ReaderBackground.OLED -> Color.Black
        // 之前 CUSTOM 直接用 textColor，而其默认值是深色 —— 选深色自定义背景就黑底黑字
        ReaderBackground.CUSTOM -> Color(settings.customBackgroundColor)
    }
    val preferred = when (settings.background) {
        ReaderBackground.DARK, ReaderBackground.OLED -> Color.White
        else -> Color(settings.textColor)
    }
    return ReaderPalette(background, readableTextOn(background, preferred))
}

@Composable
private fun rememberFont(uri: String?): FontFamily {
    val default = remember { FontFamily(Font(R.font.misansvf)) }
    if (uri.isNullOrBlank()) return default
    val file = File(uri)
    return if (file.isFile) runCatching { FontFamily(Font(file)) }.getOrDefault(default) else default
}

/**
 * 插图解码不能在组合期的 `remember {}` 里做 —— 那是主线程磁盘 I/O，
 * 大文件多插图时会直接卡住甚至无响应。改为 IO 协程 + 内存缓存。
 */
@Composable
private fun rememberEpubImage(archivePath: String, path: String): ImageBitmap? {
    val result = produceState<ImageBitmap?>(initialValue = null, archivePath, path) {
        value = withContext(Dispatchers.IO) { decodeEpubImage(archivePath, path) }
    }
    return result.value
}

private val epubImageCache = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

private fun decodeEpubImage(archivePath: String, path: String): ImageBitmap? {
    val key = "$archivePath::$path"
    epubImageCache.get(key)?.let { return it }
    return runCatching {
        ZipFile(File(archivePath)).use { zip ->
            val entry = zip.getEntry(path) ?: return null
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        }
    }.getOrNull()?.also { epubImageCache.put(key, it) }
}

@Composable
private fun ReaderSystemBarsEffect(immersive: Boolean, keepScreenOn: Boolean) {
    val view = LocalView.current
    DisposableEffect(immersive, keepScreenOn) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) controller?.hide(WindowInsetsCompat.Type.systemBars()) else controller?.show(WindowInsetsCompat.Type.systemBars())
        if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

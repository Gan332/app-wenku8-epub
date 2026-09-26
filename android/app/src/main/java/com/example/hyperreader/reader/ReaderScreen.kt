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
import androidx.compose.foundation.border
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TOUCH_TARGET = 48
private const val CONTROL_BAR_HEIGHT = 56

/**
 * 点按呼出/收起菜单栏。
 *
 * 必须挂在**内容节点**上（LazyColumn、正文 item）：v0.8.x 与 0.9.1 的真机实测
 * 都证明父层 Box 收不到内容区的点击——手势在内容链路里就被消化了，
 * 而当年吞掉点击的空 `detectTapGestures` 恰好挂在 LazyColumn 节点，
 * 那才是这个栈里被验证生效的层级。
 *
 * 多层挂载是安全的：手势从子节点向父节点分发，先触发的一层会消费掉该次点击，
 * 其余层自动取消 —— 一次点击只 toggle 一次。父层检测保留用于边距/加载/错误区域。
 */
private fun Modifier.readerTapToToggle(actions: ReaderActions): Modifier =
    pointerInput(actions) { detectTapGestures { actions.toggleControls() } }

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
    imageResolver: @Composable (block: ReaderBlock.Image) -> ReaderImage,
    onImportFont: () -> Unit,
    onImportEpub: () -> Unit,
    onBack: () -> Unit,
) {
    val book = state.book
    val settings = state.settings
    val palette = readerPalette(settings)
    val error = state.error
    // 系统栏：沉浸设置决定（面板打开时临时显示）。**不随点按翻转**，避免系统栏抖动。
    val systemImmersive = state.isImmersive && !state.showSettings && !state.showToc
    // 菜单栏：只看 controlsShown() —— 只看 isImmersive 会让点正文呼出失效。
    val showControls = state.controlsShown()

    ReaderSystemBarsEffect(systemImmersive, settings.keepScreenOn)
    BackHandler {
        when (state.resolveBack()) {
            BackAction.CLOSE_SETTINGS -> actions.showSettings(false)
            BackAction.CLOSE_TOC -> actions.showToc(false)
            BackAction.SHOW_CONTROLS -> actions.toggleControls()
            BackAction.EXIT -> onBack()
        }
    }

    Scaffold(
        topBar = {
            AnimatedVisibility(
                visible = showControls,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            ) {
                // edge-to-edge 下系统栏可见时给状态栏让位，否则按钮压在状态栏触摸区点不到；
                // 系统栏隐藏时 inset 为 0，沉浸布局不变。
                Box(Modifier.windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))) {
                    TopAppBar(
                        title = book?.chapters?.getOrNull(state.chapterIndex)?.title ?: "阅读器",
                        navigationIcon = {
                            IconButton(onClick = onBack, modifier = Modifier.size(TOUCH_TARGET.dp)) {
                                Icon(MiuixIcons.Back, contentDescription = "返回")
                            }
                        },
                        actions = {
                            IconButton(onClick = { actions.showToc(true) }, modifier = Modifier.size(TOUCH_TARGET.dp)) {
                                Icon(MiuixIcons.ListView, contentDescription = "目录")
                            }
                            IconButton(onClick = { actions.showSettings(true) }, modifier = Modifier.size(TOUCH_TARGET.dp)) {
                                Icon(MiuixIcons.Tune, contentDescription = "设置")
                            }
                        },
                    )
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = showControls,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
                Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))) {
                    ReaderBottomBar(state, actions)
                }
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
            Icon(MiuixIcons.ChevronBackward, contentDescription = "上一章")
        }
        MiuixText("${state.chapterIndex + 1} / $chapterCount", fontSize = 14.sp, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(onClick = { actions.showToc(true) }, modifier = Modifier.size(TOUCH_TARGET.dp)) {
                Icon(MiuixIcons.ListView, contentDescription = "目录")
            }
            IconButton(onClick = { actions.showSettings(true) }, modifier = Modifier.size(TOUCH_TARGET.dp)) {
                Icon(MiuixIcons.Tune, contentDescription = "设置")
            }
            IconButton(onClick = actions::nextChapter, enabled = hasNext, modifier = Modifier.size(TOUCH_TARGET.dp)) {
                Icon(MiuixIcons.ChevronForward, contentDescription = "下一章")
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
    imageResolver: @Composable (block: ReaderBlock.Image) -> ReaderImage,
) {
    val fontFamily = rememberFont(state.settings.fontUri)
    if (state.settings.pageTurnMode == ReaderPageTurnMode.HORIZONTAL) {
        val pagerState = rememberPagerState(initialPage = state.chapterIndex, pageCount = { book.chapters.size })
        LaunchedEffect(state.chapterIndex) { if (pagerState.currentPage != state.chapterIndex) pagerState.animateScrollToPage(state.chapterIndex) }
        LaunchedEffect(pagerState.currentPage) { if (pagerState.currentPage != state.chapterIndex) actions.selectChapter(pagerState.currentPage) }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val chapter = book.chapters.getOrNull(page) ?: return@HorizontalPager
            // 只有「当前章」的页携带断点段落；其余页恒 0，直接章首，避免恢复值串页
            val resumeParagraph = if (page == state.chapterIndex) state.paragraphIndex else 0
            ChapterContent(chapter, resumeParagraph, state.settings, fontFamily, palette, imageResolver, actions) { actions.setParagraph(it) }
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
    imageResolver: @Composable (block: ReaderBlock.Image) -> ReaderImage,
    fontFamily: FontFamily,
) {
    val flat = remember(book.id, book.chapters.size) { flattenBook(book) }
    val listState = rememberLazyListState()
    // 闭包里读到的必须是「当前」章节号：LaunchedEffect 的 block 只在 key 变化时重建，
    // 直接捕获 state 会一直用第一次组合的旧值，导致 selectChapter 反复重置段落。
    val currentChapter by rememberUpdatedState(state.chapterIndex)

    // 断点恢复 + 外部跳转（目录/下一章）统一走一个定位：
    // 必须排在追踪之前，且定位完成前 suppress 写入 —— 否则初始 snapshotFlow
    // （firstVisibleItemIndex=0）会把恢复的章节跳回第 0 章、段落覆盖成 0，
    // 这正是 0.9.0 摊平改造引入的竞态，导致「续读位置」从未真正生效。
    var positioned by remember(book.id) { mutableStateOf(false) }
    LaunchedEffect(state.chapterIndex, state.paragraphIndex, flat.size) {
        val target = resumeTargetIndex(flat, state.chapterIndex, state.paragraphIndex)
        if (target != null && target != listState.firstVisibleItemIndex && !listState.isScrollInProgress) {
            listState.scrollToItem(target)
        }
        if (!positioned) positioned = true
    }

    LaunchedEffect(listState, flat.size) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                if (!positioned) return@collect
                val item = flat.getOrNull(index) ?: return@collect
                if (item.chapterIndex != currentChapter) actions.selectChapter(item.chapterIndex)
                if (item.paragraphIndex >= 0) actions.setParagraph(item.paragraphIndex)
            }
    }

    LazyColumn(
        state = listState,
        // 节点级点按：v0.8.x 实证唯一能收到内容区点击的层级（见 readerTapToToggle）
        modifier = Modifier.fillMaxSize().background(palette.background).readerTapToToggle(actions),
        contentPadding = PaddingValues(horizontal = state.settings.horizontalPaddingDp.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(state.settings.paragraphSpacingDp.dp),
    ) {
        itemsIndexed(flat, key = { _, item -> item.key }) { _, item ->
            // Column 单根 + item 级点按兜底（与 LazyColumn 节点、父层 Box 三层，
            // 消费语义保证一次点击只触发一次）
            Column(Modifier.fillMaxWidth().readerTapToToggle(actions)) {
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
                    is ReaderBlock.Image -> ReaderImageBlock(imageResolver(block), block.alt, palette)
                }
            }
        }
    }
}

@Composable
private fun ChapterContent(
    chapter: ReaderChapter,
    /** 断点恢复目标段落；只有「当前章」的页传真实值，其余页恒为 0（直接章首）。 */
    resumeParagraph: Int,
    settings: ReaderSettings,
    fontFamily: FontFamily,
    palette: ReaderPalette,
    imageResolver: @Composable (block: ReaderBlock.Image) -> ReaderImage,
    actions: ReaderActions,
    onParagraph: (Int) -> Unit,
) {
    // 段落下标由 blocks 顺序**推导**得出。
    // 之前用 `var paragraphNumber = 0` 在组合期自增，既不是稳定状态，
    // 又会把「最后一个被组合的项」当成阅读位置写回，导致下标漂移。
    fun paragraphIndexAt(blockIndex: Int): Int =
        chapter.blocks.take(blockIndex.coerceIn(0, chapter.blocks.size)).count { it is ReaderBlock.Paragraph }

    val listState = rememberLazyListState()

    // 断点定位必须排在追踪之前：先滚到恢复的段落，定位完成前不写进度，
    // 否则初始 snapshotFlow 会把续读段落覆盖成 0。
    // 每章只定位**一次**：定位后 resumeParagraph 会随追踪持续变化，
    // 若还挂在 keys 里，阅读中会反复 scrollToItem 和用户滚动打架。
    var positioned by remember(chapter.id) { mutableStateOf(false) }
    LaunchedEffect(listState, chapter.id) {
        if (!positioned) {
            val target = paragraphItemIndex(chapter, resumeParagraph)
            if (target > 0 && !listState.isScrollInProgress) listState.scrollToItem(target)
            positioned = true
        }
    }

    LaunchedEffect(listState, chapter.id) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { if (positioned) onParagraph(paragraphIndexAt(it)) }
    }

    LazyColumn(
        state = listState,
        // 节点级点按（v0.8.x 实证生效层级），见 readerTapToToggle
        modifier = Modifier.fillMaxSize().background(palette.background).readerTapToToggle(actions),
        contentPadding = PaddingValues(horizontal = settings.horizontalPaddingDp.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(settings.paragraphSpacingDp.dp),
    ) {
        itemsIndexed(chapter.blocks, key = { index, block -> "${chapter.id}-$index-${block::class.simpleName}" }) { index, block ->
            // Column 单根 + item 级点按兜底（见 readerTapToToggle 的消费语义说明）
            Column(Modifier.fillMaxWidth().readerTapToToggle(actions)) {
                when (block) {
                    is ReaderBlock.Heading -> MiuixText(block.text, fontSize = (settings.fontSizeSp + 6).sp, fontWeight = FontWeight.Bold, fontFamily = fontFamily, color = palette.text, modifier = Modifier.padding(top = 10.dp))
                    is ReaderBlock.Paragraph -> {
                        MiuixText(block.text, fontSize = settings.fontSizeSp.sp, lineHeight = (settings.fontSizeSp * settings.lineHeight).sp, fontWeight = FontWeight(settings.fontWeight), fontFamily = fontFamily, color = palette.text, softWrap = true)
                    }
                    is ReaderBlock.Image -> ReaderImageBlock(imageResolver(block), block.alt, palette)
                }
            }
        }
    }
}

/** 正文插图的三态渲染：加载中转圈、失败占位（不再隐形空白）、成功显示。 */
@Composable
private fun ReaderImageBlock(image: ReaderImage, alt: String, palette: ReaderPalette) {
    when (image) {
        ReaderImage.Loading -> Box(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(size = 22.dp) }

        ReaderImage.Failed -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
                .border(1.dp, palette.text.copy(alpha = .35f), RoundedCornerShape(8.dp))
                .padding(vertical = 18.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            MiuixText(
                "图片无法显示：${alt.ifBlank { "(无标题)" }}",
                color = palette.text.copy(alpha = .65f),
                fontSize = 13.sp,
                maxLines = 3,
            )
        }

        is ReaderImage.Ready -> Image(
            image.bitmap,
            contentDescription = alt,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        )
    }
}

@Composable
private fun ReaderTocSheet(book: ReaderBook, current: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    // 用 MiuiX 的 OverlayBottomSheet 而非 material3 的 ModalBottomSheet：
    // MiuiX 0.9.4 传递依赖的 material3 与本工程编译期不一致，运行期抛
    // NoSuchMethodError: ModalBottomSheet-dYc4hso（真机点设置/目录必崩）。
    OverlayBottomSheet(show = true, title = "目录", onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(book.chapters, key = { index, _ -> "toc-$index" }) { index, chapter ->
                BasicComponent(
                    title = "${index + 1}. ${chapter.title}",
                    endActions = {
                        if (index == current) MiuixText("阅读中", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
                    },
                    onClick = { onSelect(index) },
                    modifier = Modifier.fillMaxWidth(),
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
        // 全部行容器改用 MiuiX 组件：SmallTitle 分组、BasicComponent 承载行，
        // 滑块/开关/分段选择分别是 Slider、Switch、TabRow —— 与全局设置页同一套视觉语言。
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 650.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { SmallTitle("背景") }
            // 色块预览：直接看到选中的底色与它上面文字的对比度，
            // 避免选完才发现「黑底黑字」。
            item {
                val palette = readerPalette(settings)
                LazyRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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

            item { SmallTitle("排版") }
            item {
                BasicComponent(title = "字号：${settings.fontSizeSp.toInt()} sp", bottomAction = {
                    Slider(settings.fontSizeSp, { onFontSize(it) }, valueRange = 12f..32f, steps = 19)
                })
            }
            item {
                BasicComponent(title = "字重：${settings.fontWeight}", bottomAction = {
                    Slider(settings.fontWeight.toFloat(), { onFontWeight(it.toInt()) }, valueRange = 100f..900f, steps = 7)
                })
            }
            item {
                BasicComponent(title = "行高：${"%.1f".format(settings.lineHeight)}", bottomAction = {
                    Slider(settings.lineHeight, { onLineHeight(it) }, valueRange = 1.2f..2.6f, steps = 13)
                })
            }
            item {
                BasicComponent(title = "段距：${settings.paragraphSpacingDp} dp", bottomAction = {
                    Slider(settings.paragraphSpacingDp.toFloat(), { onSpacing(it.toInt()) }, valueRange = 0f..48f, steps = 47)
                })
            }
            item {
                BasicComponent(title = "左右边距：${settings.horizontalPaddingDp} dp", bottomAction = {
                    Slider(settings.horizontalPaddingDp.toFloat(), { onPadding(it.toInt()) }, valueRange = 0f..48f, steps = 47)
                })
            }

            item { SmallTitle("颜色") }
            item {
                BasicComponent(title = "自定义背景颜色", bottomAction = { ColorSettingRow(onColorChanged = onBackgroundColor) })
            }
            item {
                BasicComponent(title = "文字颜色", bottomAction = { ColorSettingRow(onColorChanged = onTextColor) })
            }

            item { SmallTitle("翻页") }
            item {
                BasicComponent(title = "翻页模式", bottomAction = {
                    TabRow(
                        tabs = listOf("左右章节", "上下滚动"),
                        selectedTabIndex = if (settings.pageTurnMode == ReaderPageTurnMode.HORIZONTAL) 0 else 1,
                        onTabSelected = { index ->
                            onPageMode(if (index == 0) ReaderPageTurnMode.HORIZONTAL else ReaderPageTurnMode.VERTICAL)
                        },
                    )
                })
            }

            item { SmallTitle("开关") }
            item {
                BasicComponent(
                    title = "保持屏幕常亮",
                    endActions = { Switch(checked = settings.keepScreenOn, onCheckedChange = { onKeepScreenOn(it) }) },
                )
            }
            item {
                BasicComponent(
                    title = "沉浸模式",
                    summary = "隐藏系统栏，点正文呼出菜单栏",
                    endActions = { Switch(checked = settings.immersiveMode, onCheckedChange = { onImmersive(it) }) },
                )
            }

            item { SmallTitle("字体与导入") }
            item { BasicComponent(title = "导入字体", summary = "TTF / OTF", onClick = onImportFont) }
            item { BasicComponent(title = "恢复默认字体", onClick = onResetFont) }
            item {
                BasicComponent(
                    title = "导入 EPUB",
                    summary = "从文件打开另一本书",
                    endActions = { Icon(MiuixIcons.Import, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    onClick = onImportEpub,
                )
            }
            item {
                TextButton(
                    text = "完成",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET.dp).padding(horizontal = 10.dp),
                )
            }
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
private fun rememberEpubImage(archivePath: String, path: String): ReaderImage {
    val result = produceState<ReaderImage>(initialValue = ReaderImage.Loading, archivePath, path) {
        value = withContext(Dispatchers.IO) {
            decodeEpubImage(archivePath, path)?.let { ReaderImage.Ready(it) } ?: ReaderImage.Failed
        }
    }
    return result.value
}

private val epubImageCache = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

/** 解码目标最长边：全尺寸解码大插图会 OOM 返回 null，界面上表现为「图片不显示」。 */
private const val MAX_IMAGE_DIMENSION = 2048

private fun decodeEpubImage(archivePath: String, path: String): ImageBitmap? {
    val key = "$archivePath::$path"
    epubImageCache.get(key)?.let { return it }
    val bitmap = runCatching {
        ZipFile(File(archivePath)).use { zip ->
            // EPUB 里的 href 常带百分号编码（%E6%8F%92%E5%9B%BE.jpg），zip 条目名是原样字节：
            // 先按原样查，miss 再解码查一次，否则查不到条目 → 图片静默空白。
            val entry = zip.getEntry(path)
                ?: zip.getEntry(decodePercentEncoding(path))
                ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > MAX_IMAGE_DIMENSION) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) }?.asImageBitmap()
        }
    }.getOrNull()
    return bitmap?.also { epubImageCache.put(key, it) }
}

/** 含 `%` 才解码；`+` 在路径里是合法字符，只做百分号解码语义（URLDecoder 会把 + 变空格，先还原）。 */
private fun decodePercentEncoding(path: String): String {
    if ('%' !in path) return path
    return runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: path
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

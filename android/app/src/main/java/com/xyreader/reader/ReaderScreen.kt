package com.xyreader.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hyperreader.R
import com.xyreader.archive.NovelStyle
import com.xyreader.core.BookEntity
import com.xyreader.core.ReaderDimens
import com.xyreader.core.BookmarkEntity
import com.xyreader.core.PageSource
import com.xyreader.core.Chapter
import com.xyreader.core.ImageScale
import com.xyreader.core.MangaDirection
import com.xyreader.core.NovelFontFamily
import com.xyreader.core.NovelFontSize
import com.xyreader.core.NovelFontWeight
import com.xyreader.core.PageMode
import com.xyreader.core.ReadBackground
import com.xyreader.core.ReaderPrefs
import com.xyreader.core.ScreenOrientation
import com.xyreader.ui.formatDate
import com.xyreader.ui.CapsuleTab
import com.xyreader.ui.NovelSpacingControls
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.FavoritesFill
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.Unlock
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

/** 工具栏浮层统一样式：半透明深底 + 20dp 圆角 */
private val BarBackground = Color(0xCC101318)
private val BarCorner = RoundedCornerShape(ReaderDimens.panelCorner)
private const val BAR_ANIM_MS = 250

/** 页内双击放大的目标倍数 */
private const val PAGE_ZOOM = 2.5f

/** 沿 ContextWrapper 链找宿主 Activity；拿不到时返回 null（调用方判空降级） */
private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** ReadBackground.argb(Long) 转 Compose Color：0xFFxxxxxx 为负数 Int，Color(Int) 按 ARGB 解析 */
private fun ReadBackground.toComposeColor(): Color = Color(argb.toInt())

/**
 * 阅读器界面：黑底全屏 Pager + 三分点击（左翻上页 / 中工具栏 / 右翻下页，可配置关闭点击翻页）
 * + 双击页内放大（可配置）+ 顶部（返回 / 目录 / 书名 / 书签 / 收藏）与底部
 * （滑条跳页 + 亮度行 + 五按钮：上一章 / 设置 / 添加书签 / 目录 / 下一章）圆角浮层工具栏
 * + 右侧中央手势锁（防误触：锁定只拦点击，滑动照常；点屏幕中央呼出解锁钮）
 * + 全屏目录弹层（目录 / 书签双 tab，当前章高亮）+ 阅读设置快捷面板（翻页 / 背景 / 缩放 / 小说字号 / 常亮）
 * + 阅读背景色与亮度即时生效 + 屏幕常亮。
 * 进入隐藏系统状态栏、应用亮度配置，离开全部恢复。
 */
@Composable
fun ReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
    initialPage: Int = 0,
    startFromBeginning: Boolean = false,
    /**
     * 页面源打开策略，透传给 [ReaderViewModel]；null 时由 ViewModel 走 `ArchiveFactory`。
     * 宿主注入自己的实现即可复用既有 EPUB 解析管线（见 `XyReaderBridge`）。
     */
    sourceOpener: ((Context, BookEntity, NovelStyle?) -> PageSource)? = null,
    /**
     * 打开完整阅读配置管理页（`com.xyreader.ui.ReaderConfigScreen`）。
     * 屏幕方向 / 点击翻页 / 双击缩放 / 图片质量 / 漫画方向 / 字体导入只在该页可改；
     * 为 null 时设置面板只显示一行「去设置页」的提示。
     */
    onOpenReaderConfig: (() -> Unit)? = null,
) {
    val viewModel: ReaderViewModel = viewModel(key = "reader_$bookId") {
        // CreationExtras 里取 Application；非标准宿主（如预览）拿不到，会直接抛错——阅读器不支持预览
        ReaderViewModel(
            bookId = bookId,
            initialPage = initialPage,
            startFromBeginning = startFromBeginning,
            app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!,
            sourceOpener = sourceOpener,
        )
    }
    val state by viewModel.state.collectAsState()
    val book by viewModel.book.collectAsState()
    // 逐页状态是 SnapshotStateMap：直接读取按键订阅，不能用 collect 整表收集
    val pages = viewModel.pages
    val pageAspectRatios = viewModel.pageAspectRatios
    // 书签全量流（UI 按 bookId 过滤本书）；阅读配置提升到顶层供亮度作用与各分支共用
    val bookmarks by viewModel.bookmarks.collectAsState(initial = emptyList())
    val prefs by viewModel.readerPrefs.collectAsState()

    // —— 状态栏：进入隐藏，离开恢复；部分环境拿不到 Activity，整体 try-catch 静默降级 ——
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(context, view) {
        val controller: WindowInsetsControllerCompat? = try {
            context.findActivity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, view)
            }
        } catch (_: Exception) {
            null
        }
        try {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.statusBars())
        } catch (_: Exception) {
        }
        onDispose {
            try {
                controller?.show(WindowInsetsCompat.Type.statusBars())
            } catch (_: Exception) {
            }
        }
    }

    // —— 亮度：配置值作用于本 App 窗口（0.01-1.0）；null（跟随系统）设 -1 即系统默认。
    //    离开阅读器或值变化时先恢复系统亮度再按新值应用，避免亮度"泄漏"到其他页面 ——
    DisposableEffect(prefs.brightness) {
        try {
            context.findActivity()?.window?.let { window ->
                window.attributes = window.attributes.apply {
                    screenBrightness = prefs.brightness?.coerceIn(0.01f, 1f)
                        ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        } catch (_: Exception) {
        }
        onDispose {
            try {
                context.findActivity()?.window?.let { window ->
                    window.attributes = window.attributes.apply {
                        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    // —— 屏幕常亮：按配置给宿主窗口加/清 FLAG_KEEP_SCREEN_ON；离开阅读器或关闭开关时清掉，
    //    拿不到 Activity 的环境 try-catch 静默降级 ——
    DisposableEffect(prefs.keepScreenOn) {
        try {
            if (prefs.keepScreenOn) {
                context.findActivity()?.window?.addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                )
            }
        } catch (_: Exception) {
        }
        onDispose {
            try {
                context.findActivity()?.window?.clearFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                )
            } catch (_: Exception) {
            }
        }
    }

    // —— 一次性提示（书签等）：收集 ViewModel 事件弹 Snackbar ——
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    // —— MiuiX 弹层宿主 ——
    // 阅读设置面板用的是 MiuiX `OverlayBottomSheet`，它把弹层注册进
    // `LocalRootDialogStates ?: LocalDialogStates`，而**只有 MiuiX `Scaffold`
    // 会通过 `MiuixPopupHost` 渲染这个列表**。阅读器根节点原先是一个裸 `Box`，
    // 弹层因此被注册进一个没人渲染的列表——表现为「点设置按钮没反应」。
    // 这里补一个透明 Scaffold 作宿主；insets 交给阅读器自己管（contentWindowInsets = 0）。
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { _ ->
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (state.phase) {
            ReaderPhase.Loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            ReaderPhase.Error -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(ReaderDimens.spaceXL),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = state.errorMessage ?: "打开书籍失败",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body1,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(ReaderDimens.spaceM))
                TextButton(
                    text = "返回",
                    onClick = onBack,
                    colors = ButtonDefaults.textButtonColors(
                        textColor = MiuixTheme.colorScheme.secondary,
                    ),
                )
            }

            ReaderPhase.Ready -> {
                // 屏幕方向锁定：进阅读器应用配置，离开恢复由系统决定
                DisposableEffect(prefs.screenOrientation) {
                    val activity = context.findActivity()
                    try {
                        activity?.requestedOrientation = when (prefs.screenOrientation) {
                            ScreenOrientation.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                            ScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                    } catch (_: Exception) {
                    }
                    onDispose {
                        try {
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        } catch (_: Exception) {
                        }
                    }
                }

                val upDown = prefs.pageMode == PageMode.UP_DOWN
                val pagerState = rememberPagerState(
                    initialPage = state.initialPage,
                    pageCount = { state.pageCount },
                )
                val verticalListState = rememberLazyListState(
                    initialFirstVisibleItemIndex = state.initialPage.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0)),
                )
                // 旋转/重建后仍可恢复：三个状态跨 Activity 重建存活，
                // 使“源未变”判断成立，避免滚动位置回退并污染落库进度
                var currentReadingPage by rememberSaveable { mutableIntStateOf(state.initialPage) }
                var lastAppliedInitialPage by rememberSaveable { mutableIntStateOf(-1) }
                var lastAppliedPageCount by rememberSaveable { mutableIntStateOf(-1) }
                LaunchedEffect(state.initialPage, state.pageCount, upDown) {
                    // 分页重建后用 ViewModel 的字符锚点结果重定位；仅切模式时沿用当前页。
                    val sourcePositionChanged =
                        state.initialPage != lastAppliedInitialPage || state.pageCount != lastAppliedPageCount
                    val target = (if (sourcePositionChanged) state.initialPage else currentReadingPage)
                        .coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
                    currentReadingPage = target
                    if (upDown) verticalListState.scrollToItem(target) else pagerState.scrollToPage(target)
                    lastAppliedInitialPage = state.initialPage
                    lastAppliedPageCount = state.pageCount
                }
                LaunchedEffect(pagerState, verticalListState, upDown) {
                    snapshotFlow {
                        if (upDown) verticalListState.firstVisibleItemIndex else pagerState.currentPage
                    }.distinctUntilChanged().collect { page ->
                        val safePage = page.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
                        currentReadingPage = safePage
                        viewModel.onPageChanged(safePage)
                    }
                }
                LaunchedEffect(verticalListState, pagerState, upDown, state.pageCount) {
                    if (upDown) {
                        snapshotFlow { verticalListState.layoutInfo.visibleItemsInfo.map { it.index } }
                            .distinctUntilChanged()
                            .collect(viewModel::onVisiblePagesChanged)
                    } else {
                        snapshotFlow { listOf(pagerState.currentPage) }
                            .distinctUntilChanged()
                            .collect(viewModel::onVisiblePagesChanged)
                    }
                }
                val scope = rememberCoroutineScope()
                var showDirectory by remember { mutableStateOf(false) }
                // 弹层 tab 记忆：关闭再打开仍停留在上次的 tab
                var directoryTab by remember { mutableIntStateOf(0) }
                // 目录/书签跳页：立即收起弹层，同时滚动到目标页（越界收敛防脏数据）
                val jumpTo: (Int) -> Unit = { target ->
                    showDirectory = false
                    scope.launch {
                        val last = (state.pageCount - 1).coerceAtLeast(0)
                        val safeTarget = target.coerceIn(0, last)
                        if (upDown) verticalListState.animateScrollToItem(safeTarget)
                        else pagerState.animateScrollToPage(safeTarget)
                    }
                }
                ReaderPagerArea(
                    pagerState = pagerState,
                    verticalListState = verticalListState,
                    pageCount = state.pageCount,
                    title = book?.title.orEmpty(),
                    favorite = book?.isFavorite == true,
                    pages = pages,
                    pageAspectRatios = pageAspectRatios,
                    prefs = prefs,
                    chapters = state.chapters,
                    viewModel = viewModel,
                    onOpenDirectory = { showDirectory = true },
                    onBack = onBack,
                    onOpenReaderConfig = onOpenReaderConfig,
                )
                // 全屏目录弹层（替换早期版本的抽屉）
                if (showDirectory) {
                    DirectoryDialog(
                        chapters = state.chapters,
                        bookmarks = bookmarks.filter { it.bookId == bookId },
                        currentPage = currentReadingPage,
                        tab = directoryTab,
                        onTabChange = { directoryTab = it },
                        onJumpTo = jumpTo,
                        onRemoveBookmark = viewModel::removeBookmark,
                        onDismiss = { showDirectory = false },
                    )
                }
            }
        }

        // Snackbar 置于底部，避开导航栏与底部工具栏
        SnackbarHost(
            state = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(WindowInsets.navigationBars.asPaddingValues())
                .padding(bottom = ReaderDimens.snackbarBottomInset),
        )
    }
    }
}

/** 就绪后的阅读主体：Pager（左右 / 上下 / 日漫右开本）+ 上下工具栏浮层 + 手势锁 + 阅读背景色 */
@Composable
private fun ReaderPagerArea(
    pagerState: PagerState,
    verticalListState: LazyListState,
    pageCount: Int,
    title: String,
    favorite: Boolean,
    pages: Map<Int, PageUi>,
    pageAspectRatios: Map<Int, Float>,
    prefs: ReaderPrefs,
    chapters: List<Chapter>,
    viewModel: ReaderViewModel,
    onOpenDirectory: () -> Unit,
    onBack: () -> Unit,
    onOpenReaderConfig: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val upDown = prefs.pageMode == PageMode.UP_DOWN
    val currentPage = if (upDown) verticalListState.firstVisibleItemIndex else pagerState.currentPage
    // 页面与页边距（ContentScale.Fit 留白）统一使用配置的阅读背景色
    val bgColor = prefs.readBackground.toComposeColor()

    var toolbarVisible by remember { mutableStateOf(false) }
    var sliderActive by remember { mutableStateOf(false) }
    var sliderPage by remember { mutableIntStateOf(0) }
    // 手势锁（会话内记忆）：防误触——锁定期间只拦点击（不弹菜单、不点按翻页、不双击缩放），
    // 滑动翻页照常；无遮罩不变暗。解锁钮常驻隐藏，点击屏幕中央呼出。
    var locked by remember { mutableStateOf(false) }
    var lockBadgeVisible by remember { mutableStateOf(false) }
    // 阅读设置快捷面板开关
    var showSettings by remember { mutableStateOf(false) }
    // 底部工具栏第一行显示内容：page = 页码滑条 / brightness = 亮度滑条（点亮度按钮切换）
    var barMode by remember { mutableStateOf("page") }
    // 页内缩放状态：双指捏合与双击放大共用，翻页后复位为 1x
    val pageZoom = remember { PageZoomState() }
    // 连续列表（上下模式）整列同步缩放状态：与页内缩放（pageZoom）独立，
    // 缩放时整列等比放大、页与页仍首尾相接；纵向滚动继续由列表承担
    val columnZoomState = remember { PageZoomState() }
    // 「复制文字」弹层开关（仅文字小说）
    var showCopyDialog by remember { mutableStateOf(false) }
    val isTextNovel by viewModel.isTextNovel.collectAsState()

    fun turnTo(target: Int) {
        if (target in 0 until pageCount) {
            scope.launch {
                if (upDown) verticalListState.animateScrollToItem(target)
                else pagerState.animateScrollToPage(target)
            }
        }
    }

    /** 双击页内放大：1x ↔ 2.5x 平滑动画；缩回 1x 时清零平移 */
    fun togglePageZoom() {
        scope.launch {
            val from = pageZoom.scale
            val to = if (from > 1f) 1f else PAGE_ZOOM
            animate(from, to, animationSpec = tween(200)) { value, _ -> pageZoom.set(value) }
            if (to == 1f) pageZoom.reset()
        }
    }

    fun lockGesture() {
        toolbarVisible = false
        lockBadgeVisible = false
        locked = true
    }

    // 锁定期间返回键不退出（防误触退出）；解锁后返回键恢复常规行为
    BackHandler(enabled = locked) {
    }

    // 当前页变化时重置页内缩放；进度和预载由上层按当前阅读模式同步。
    LaunchedEffect(pagerState, verticalListState, upDown) {
        snapshotFlow { if (upDown) verticalListState.firstVisibleItemIndex else pagerState.currentPage }
            .distinctUntilChanged().collect {
            pageZoom.reset()
        }
    }

    val rtl = !upDown && prefs.mangaDirection == MangaDirection.RTL

    Box(Modifier.fillMaxSize()) {
        if (upDown) {
            BoxWithConstraints(Modifier.fillMaxSize().background(bgColor)) {
                val fallbackPageHeight = maxHeight
                val viewportWidthPx = constraints.maxWidth
                val fallbackPageHeightPx = with(LocalDensity.current) { fallbackPageHeight.toPx() }
                // 以组合值读取缩放：高度随其重算（页页无缝衔接的根）
                val listScale = columnZoomState.scale

                // 未缩放时每一项的高度（px）：页面按宽高比撑满宽度，缺比例用整屏高兜底
                fun pageBaseHeight(page: Int): Float {
                    val aspect = pageAspectRatios[page]
                        ?: (pages[page] as? PageUi.Ready)?.bitmap
                            ?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }
                    return if (aspect != null && aspect.isFinite() && aspect > 0f) {
                        viewportWidthPx.toFloat() / aspect
                    } else {
                        fallbackPageHeightPx
                    }
                }

                fun baseHeights(): List<Float> = List(pageCount) { pageBaseHeight(it) }

                /** 整列缩放到 [to]，并保持视口 [focalY] 处内容不动（锚定滚动、页页相接） */
                fun zoomAnchored(to: Float, focalY: Float) {
                    val from = columnZoomState.scale
                    if (to == from) return
                    val heights = baseHeights()
                    val pixel = ContinuousZoomMath.topPixel(
                        heights, verticalListState.firstVisibleItemIndex, from,
                    ) + verticalListState.firstVisibleItemScrollOffset
                    val target = anchoredScrollOffset(pixel.toInt(), focalY, to / from)
                    val (index, offset) = ContinuousZoomMath.locate(heights, target, to)
                    if (to <= 1f) {
                        columnZoomState.reset()
                    } else {
                        columnZoomState.setScalePreservePan(to)
                        columnZoomState.clampPanToViewport(viewportWidthPx)
                    }
                    verticalListState.requestScrollToItem(index, offset.toInt())
                }

                LazyColumn(
                    state = verticalListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .pointerInput(pageCount, prefs.doubleTapZoom, locked) {
                            detectTapGestures(
                                onDoubleTap = { offset ->
                                    if (!locked && prefs.doubleTapZoom) {
                                        val from = columnZoomState.scale
                                        val to = if (from > 1f) 1f else PAGE_ZOOM
                                        scope.launch {
                                            val heights = baseHeights()
                                            val pixel = ContinuousZoomMath.topPixel(
                                                heights, verticalListState.firstVisibleItemIndex, from,
                                            ) + verticalListState.firstVisibleItemScrollOffset
                                            val target = anchoredScrollOffset(pixel.toInt(), offset.y, to / from)
                                            val (index, offsetInItem) =
                                                ContinuousZoomMath.locate(heights, target, to)
                                            animate(from, to, animationSpec = tween(200)) { value, _ ->
                                                columnZoomState.setScalePreservePan(value)
                                            }
                                            if (to <= 1f) {
                                                columnZoomState.reset()
                                            } else {
                                                columnZoomState.clampPanToViewport(viewportWidthPx)
                                            }
                                            verticalListState.requestScrollToItem(index, offsetInItem.toInt())
                                        }
                                    }
                                },
                                onTap = {
                                    // 锁定中：竖排无分区，点击任意处呼出/收起解锁钮；未锁定时呼出工具栏
                                    if (locked) lockBadgeVisible = !lockBadgeVisible
                                    else toolbarVisible = !toolbarVisible
                                },
                            )
                        }
                        // 连续列表同步缩放：双指捏合整列等比放大（页与页始终衔接）；
                        // 放大后水平拖动平移内容、纵向拖动仍交给列表滚动
                        .columnZoom(
                            onZoom = { zoomChange, centroid ->
                                zoomAnchored(
                                    (columnZoomState.scale * zoomChange).coerceIn(1f, PAGE_MAX_ZOOM),
                                    centroid.y,
                                )
                            },
                            onPanX = { dx -> columnZoomState.panByX(dx, viewportWidthPx) },
                            isZoomed = { columnZoomState.scale > 1f },
                        ),
                ) {
                    items(count = pageCount, key = { it }) { page ->
                        val bitmap = (pages[page] as? PageUi.Ready)?.bitmap
                        val aspect = pageAspectRatios[page]
                            ?: bitmap?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }
                        val pageModifier = Modifier.fillMaxWidth().then(
                            if (aspect != null && aspect.isFinite() && aspect > 0f) {
                                // 高度 = 未缩放高度 × 缩放倍数：整列等比放大且页页相接、不重叠
                                Modifier.aspectRatio(aspect / listScale)
                            } else {
                                Modifier.height(fallbackPageHeight * listScale)
                            },
                        )
                        ReaderPage(
                            page = page,
                            ui = pages[page],
                            bgColor = bgColor,
                            zoomState = null,
                            imageScale = prefs.imageScale,
                            onNeedRender = viewModel::requestPage,
                            modifier = pageModifier,
                            panX = { columnZoomState.offset.x },
                        )
                    }
                }
            }
        } else {
            // 左右翻页：RTL（日漫右开本）时镜像布局方向，pager 自动从右往左翻
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bgColor)
                        // 三分点击：左 1/3 上一页，右 1/3 下一页，中间切换工具栏；
                        // RTL 时镜像（左 1/3 下一页、右 1/3 上一页）。
                        // tapTurnPage 关闭时左右分区与中间一致，只呼出/隐藏工具栏。
                        // detectTapGestures 不消费移动事件，与 Pager 的拖拽翻页互不干扰。
                        .pointerInput(pageCount, rtl, prefs.tapTurnPage, prefs.doubleTapZoom, locked) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (!locked && prefs.doubleTapZoom) togglePageZoom()
                                },
                                onTap = { offset ->
                                    val w = size.width
                                    when {
                                        // 锁定中：只认屏幕中央的点击（呼出/收起解锁钮），
                                        // 左右分区静默，防误触翻页
                                        locked ->
                                            if (offset.x >= w / 3f && offset.x <= w * 2f / 3f) {
                                                lockBadgeVisible = !lockBadgeVisible
                                            }
                                        offset.x < w / 3f ->
                                            if (prefs.tapTurnPage) {
                                                turnTo(pagerState.targetPage + if (rtl) 1 else -1)
                                            } else {
                                                toolbarVisible = !toolbarVisible
                                            }
                                        offset.x > w * 2f / 3f ->
                                            if (prefs.tapTurnPage) {
                                                turnTo(pagerState.targetPage + if (rtl) -1 else 1)
                                            } else {
                                                toolbarVisible = !toolbarVisible
                                            }
                                        else -> toolbarVisible = !toolbarVisible
                                    }
                                },
                            )
                        },
                    pageSpacing = 0.dp, // 页与页零间距
                    // 锁定不影响翻页（防误触只拦点击）；缩放中（scale > 1）把拖动让给图片平移
                    userScrollEnabled = pageZoom.scale <= 1f,
                ) { page ->
                    ReaderPage(
                        page = page,
                        ui = pages[page],
                        bgColor = bgColor,
                        zoomState = pageZoom,
                        imageScale = prefs.imageScale,
                        onNeedRender = viewModel::requestPage,
                    )
                }
            }
        }

        // —— 顶部工具栏：返回 / 目录 / 书名 / 书签 / 收藏 ——
        AnimatedVisibility(
            visible = toolbarVisible && !locked,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(BAR_ANIM_MS)) + slideInVertically(tween(BAR_ANIM_MS)) { -it },
            exit = fadeOut(tween(BAR_ANIM_MS)) + slideOutVertically(tween(BAR_ANIM_MS)) { -it },
        ) {
            Row(
                modifier = Modifier
                    .padding(WindowInsets.statusBars.asPaddingValues())
                    .padding(start = ReaderDimens.spaceS, end = ReaderDimens.spaceS, top = ReaderDimens.spaceXS)
                    .fillMaxWidth()
                    .background(BarBackground, BarCorner)
                    .padding(horizontal = ReaderDimens.spaceXXS, vertical = ReaderDimens.spaceXXS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = "返回",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onOpenDirectory) {
                    Icon(
                        imageVector = MiuixIcons.ListView,
                        contentDescription = "目录",
                        tint = Color.White,
                    )
                }
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    style = MiuixTheme.textStyles.title4,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { viewModel.addBookmark(currentPage) }) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.outline_bookmark_24px),
                        contentDescription = "添加书签",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = viewModel::toggleFavorite) {
                    if (favorite) {
                        Icon(
                            imageVector = MiuixIcons.FavoritesFill,
                            contentDescription = "取消收藏",
                            tint = MiuixTheme.colorScheme.tertiaryContainer,
                        )
                    } else {
                        Icon(
                            imageVector = MiuixIcons.Favorites,
                            contentDescription = "加入收藏",
                            tint = Color.White,
                        )
                    }
                }
            }
        }

        // —— 底部工具栏：滑条跳页 + 亮度行 + 页码 + 五个等宽功能按钮 ——
        AnimatedVisibility(
            visible = toolbarVisible && !locked,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(BAR_ANIM_MS)) + slideInVertically(tween(BAR_ANIM_MS)) { it },
            exit = fadeOut(tween(BAR_ANIM_MS)) + slideOutVertically(tween(BAR_ANIM_MS)) { it },
        ) {
            val last = (pageCount - 1).coerceAtLeast(0)
            val shownPage = (if (sliderActive) sliderPage else currentPage)
                .coerceIn(0, last)

            // —— 章节跳转目标推导：基于 chapters 与当前页；chapters 为空时两钮禁用 ——
            val current = currentPage
            val currentIdx = chapters.indexOfFirst { current in it.startPage..it.endPageInclusive }
            val atChapterStart = currentIdx >= 0 && current == chapters[currentIdx].startPage
            // 上一章：当前页恰为某章 startPage → 取前一章 startPage，否则取当前章 startPage
            val prevTarget: Int? = when {
                atChapterStart && currentIdx > 0 -> chapters[currentIdx - 1].startPage
                !atChapterStart && currentIdx >= 0 -> chapters[currentIdx].startPage
                else -> null
            }
            // 存在且 >0 才可点（=0 时跳转无意义）
            val prevEnabled = prevTarget != null && prevTarget > 0
            // 下一章：第一个 startPage 在当前页之后的章节
            val nextTarget: Int? = chapters.firstOrNull { it.startPage > current }?.startPage

            Column(
                modifier = Modifier
                    .padding(WindowInsets.navigationBars.asPaddingValues())
                    .padding(start = ReaderDimens.spaceS, end = ReaderDimens.spaceS, bottom = ReaderDimens.spaceXS)
                    .fillMaxWidth()
                    .background(BarBackground, BarCorner)
                    .padding(horizontal = ReaderDimens.spaceXS, vertical = ReaderDimens.spaceXXS),
            ) {
                // —— 第一行：页码滑条 / 亮度滑条 二选一（点亮度按钮切换），控制卡片高度 ——
                if (barMode == "brightness") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.lightbulb_24px),
                            contentDescription = "亮度",
                            tint = Color.White,
                            modifier = Modifier.size(ReaderDimens.iconMd),
                        )
                        var brightnessDragging by remember { mutableStateOf(false) }
                        // 跟随系统（null）时滑条显示近似值 50%
                        var sliderBrightness by remember { mutableFloatStateOf(prefs.brightness ?: 0.5f) }
                        // 外部变化（如"跟随系统"重置）同步滑条显示；拖动中不回写，避免与手势竞态回跳
                        LaunchedEffect(prefs.brightness) {
                            if (!brightnessDragging) sliderBrightness = prefs.brightness ?: 0.5f
                        }
                        Slider(
                            value = sliderBrightness,
                            onValueChange = { value ->
                                brightnessDragging = true
                                sliderBrightness = value
                                // 拖动即写配置：DataStore 回流后顶层 DisposableEffect 实时作用于窗口
                                viewModel.updatePrefs { it.copy(brightness = value) }
                            },
                            onValueChangeFinished = { brightnessDragging = false },
                            valueRange = 0.01f..1f,
                            modifier = Modifier
                                .padding(horizontal = ReaderDimens.spaceXS)
                                .weight(1f),
                            colors = SliderDefaults.sliderColors(
                                thumbColor = MiuixTheme.colorScheme.primary,
                                foregroundColor = MiuixTheme.colorScheme.primary,
                                backgroundColor = Color.White.copy(alpha = 0.25f),
                            ),
                        )
                        Text(
                            text = "${(sliderBrightness * 100).roundToInt()}%",
                            color = MiuixTheme.colorScheme.secondary,
                            style = MiuixTheme.textStyles.footnote2,
                        )
                        TextButton(
                            text = "跟随系统",
                            onClick = { viewModel.updatePrefs { it.copy(brightness = null) } },
                        )
                    }
                } else {
                    // 页码与滑条同一行，砍掉独立页码行
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${shownPage + 1}/${pageCount.coerceAtLeast(1)}",
                            color = MiuixTheme.colorScheme.secondary,
                            style = MiuixTheme.textStyles.footnote2,
                        )
                        Slider(
                            value = shownPage.toFloat(),
                            onValueChange = { value ->
                                sliderActive = true
                                sliderPage = value.roundToInt().coerceIn(0, last)
                            },
                            onValueChangeFinished = {
                                sliderActive = false
                                scope.launch {
                                    val target = sliderPage.coerceIn(0, last)
                                    if (upDown) verticalListState.animateScrollToItem(target)
                                    else pagerState.scrollToPage(target)
                                }
                            },
                            valueRange = 0f..last.toFloat().coerceAtLeast(0f),
                            modifier = Modifier
                                .padding(start = ReaderDimens.spaceS)
                                .weight(1f),
                            colors = SliderDefaults.sliderColors(
                                thumbColor = MiuixTheme.colorScheme.primary,
                                foregroundColor = MiuixTheme.colorScheme.primary,
                                backgroundColor = Color.White.copy(alpha = 0.25f),
                            ),
                        )
                    }
                }
                // —— 六个等宽功能按钮：上一章 / 亮度 / 设置 / 添加书签 / 目录 / 下一章 ——
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReaderBarIconButton(
                        icon = MiuixIcons.ChevronBackward,
                        desc = "上一章",
                        enabled = prevEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = { prevTarget?.let { turnTo(it) } },
                    )
                    ReaderBarIconButton(
                        icon = ImageVector.vectorResource(R.drawable.lightbulb_24px),
                        desc = "亮度",
                        selected = barMode == "brightness",
                        modifier = Modifier.weight(1f),
                        onClick = { barMode = if (barMode == "brightness") "page" else "brightness" },
                    )
                    ReaderBarIconButton(
                        icon = MiuixIcons.Tune,
                        desc = "阅读设置",
                        modifier = Modifier.weight(1f),
                        onClick = { showSettings = true },
                    )
                    ReaderBarIconButton(
                        icon = ImageVector.vectorResource(R.drawable.outline_bookmark_24px),
                        desc = "添加书签",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.addBookmark(currentPage) },
                    )
                    if (isTextNovel) {
                        ReaderBarIconButton(
                            icon = MiuixIcons.Copy,
                            desc = "复制文字",
                            modifier = Modifier.weight(1f),
                            onClick = { showCopyDialog = true },
                        )
                    }
                    ReaderBarIconButton(
                        icon = MiuixIcons.ListView,
                        desc = "目录",
                        modifier = Modifier.weight(1f),
                        onClick = onOpenDirectory,
                    )
                    ReaderBarIconButton(
                        icon = MiuixIcons.ChevronForward,
                        desc = "下一章",
                        enabled = nextTarget != null,
                        modifier = Modifier.weight(1f),
                        onClick = { nextTarget?.let { turnTo(it) } },
                    )
                }
            }
        }

        // —— 右侧中央常驻手势锁钮（未锁定态，低调半透明）：点击即锁定 ——
        if (!locked) {
            Surface(
                onClick = { lockGesture() },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = ReaderDimens.spaceXS)
                    .size(ReaderDimens.touchMin),
                shape = CircleShape,
                color = MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = MiuixIcons.Unlock,
                        contentDescription = "锁定手势（防误触）",
                        tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        modifier = Modifier.size(ReaderDimens.iconSm),
                    )
                }
            }
        }

        // —— 解锁钮：锁定后无遮罩不变暗、滑动翻页照常，本钮常驻隐藏；
        //    点击屏幕中央呼出，点击解锁并呼出菜单；返回键由 BackHandler 拦截 ——
        AnimatedVisibility(
            visible = locked && lockBadgeVisible,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = fadeIn(tween(BAR_ANIM_MS)) +
                slideInHorizontally(tween(BAR_ANIM_MS)) { it },
            exit = fadeOut(tween(BAR_ANIM_MS)),
        ) {
            Surface(
                onClick = {
                    locked = false
                    lockBadgeVisible = false
                    toolbarVisible = true
                },
                modifier = Modifier
                    .padding(end = ReaderDimens.spaceXS)
                    .size(ReaderDimens.touchMin),
                shape = CircleShape,
                color = MiuixTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = MiuixIcons.Lock,
                        contentDescription = "解锁手势",
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        // —— 阅读设置快捷面板（底部弹层）——
        if (showSettings) {
            ReaderSettingsSheet(
                prefs = prefs,
                onUpdate = viewModel::updatePrefs,
                onDismiss = { showSettings = false },
                onOpenReaderConfig = onOpenReaderConfig?.let { open ->
                    {
                        showSettings = false
                        open()
                    }
                },
            )
        }

        // —— 复制文字弹层（仅文字小说，从底部工具栏「复制文字」进入）——
        if (showCopyDialog) {
            PageTextCopyDialog(
                viewModel = viewModel,
                page = currentPage,
                onDismiss = { showCopyDialog = false },
            )
        }
    }
}

/** 底部工具栏功能按钮：等宽布局 + 白色图标（禁用时降透明度；选中时用主色高亮） */
@Composable
private fun ReaderBarIconButton(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = when {
                !enabled -> Color.White.copy(alpha = 0.38f)
                selected -> MiuixTheme.colorScheme.primary
                else -> Color.White
            },
        )
    }
}

/**
 * 复制文字弹层（仅文字小说）：展示当前页文字（可根据行布局还原），
 * 长按即可选择片段；「复制本页」一键把整页文本写入剪贴板。
 * 非文字源 / 排版未就绪时给出空态提示。
 */
@Composable
private fun PageTextCopyDialog(viewModel: ReaderViewModel, page: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(page) {
        text = viewModel.pageText(page)
        loading = false
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(ReaderDimens.panelCorner), color = MiuixTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(ReaderDimens.pagePadding),
            ) {
                Text(
                    text = "复制文字 · 第 ${page + 1} 页",
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(ReaderDimens.spaceS))
                val content = text
                when {
                    loading -> Box(
                        modifier = Modifier.fillMaxWidth().height(ReaderDimens.copyBodyMinHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                    content.isNullOrBlank() -> Text(
                        text = "本页暂无可复制的文字",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    else -> SelectionContainer {
                        Text(
                            text = content,
                            modifier = Modifier
                                .heightIn(max = ReaderDimens.copyBodyMaxHeight)
                                .verticalScroll(rememberScrollState()),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
                Spacer(Modifier.height(ReaderDimens.spaceXS))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(text = "关闭", onClick = onDismiss)
                    if (!loading && !content.isNullOrBlank()) {
                        TextButton(
                            text = "复制本页",
                            onClick = {
                                val manager =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                manager?.setPrimaryClip(ClipData.newPlainText("XY reader", content))
                                Toast.makeText(context, "已复制本页文字", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 阅读设置快捷面板（MiuiX OverlayBottomSheet）：顶部胶囊分组 + 横向分页
 * （取代旧的整段上下滚动列表）。
 * 分组：翻页模式 / 页面（阅读背景、图片缩放、屏幕常亮）/ 字体（字体、字重、字号、首行缩进）。
 * 改动经 [onUpdate] 落库即时生效。
 *
 * 上游用 material3 `ModalBottomSheet` + `rememberModalBottomSheetState`；MiuiX 的
 * `OverlayBottomSheet` 由调用方条件渲染控制显隐（`show = true`），没有独立的 sheet state，
 * 因此这里不再需要 `rememberModalBottomSheetState`。
 */
@Composable
private fun ReaderSettingsSheet(
    prefs: ReaderPrefs,
    onUpdate: ((ReaderPrefs) -> ReaderPrefs) -> Unit,
    onDismiss: () -> Unit,
    onOpenReaderConfig: (() -> Unit)? = null,
) {
    OverlayBottomSheet(show = true, onDismissRequest = onDismiss) {
        ReaderSettingsSheetContent(
            prefs = prefs,
            onUpdate = onUpdate,
            onOpenReaderConfig = onOpenReaderConfig,
        )
    }
}

/** 弹层分组（与阅读配置管理页同款三组，胶囊即分组标题） */
private enum class ReaderSheetTab(val label: String) {
    MODE("翻页模式"),
    PAGE("页面"),
    FONT("字体"),
}

/** 弹层分页区高度：容纳最高的字体组；组内偶尔超高时由组内容自滚 */
private val SheetPagerHeight = ReaderDimens.sheetPagerHeight

/**
 * 设置面板内容（拆出独立于弹层的容器以便 UI 测试直挂）：
 * 顶部胶囊 ↔ 横滑分页双向同步；每组内保留原有控件与即时生效行为。
 *
 * @param onOpenReaderConfig 打开完整阅读配置管理页（屏幕方向 / 点击翻页 / 双击缩放 /
 *   图片质量 / 漫画方向 / 字体导入只在该页可改）。为 null 时降级为纯文字提示。
 */
@Composable
internal fun ReaderSettingsSheetContent(
    prefs: ReaderPrefs,
    onUpdate: ((ReaderPrefs) -> ReaderPrefs) -> Unit,
    onOpenReaderConfig: (() -> Unit)? = null,
) {
    val pagerState = rememberPagerState { ReaderSheetTab.entries.size }
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxWidth()) {
        // —— 顶部胶囊分组：点胶囊换组，与横滑分页双向同步 ——
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ReaderDimens.pagePadding),
            horizontalArrangement = Arrangement.spacedBy(ReaderDimens.spaceS),
        ) {
            ReaderSheetTab.entries.forEachIndexed { index, tab ->
                CapsuleTab(
                    label = tab.label,
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                )
            }
        }
        Spacer(Modifier.height(ReaderDimens.spaceM))
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(SheetPagerHeight),
        ) { page ->
            SheetGroupPage {
                when (ReaderSheetTab.entries[page]) {
                    ReaderSheetTab.MODE -> SheetModeGroup(prefs = prefs, onUpdate = onUpdate)
                    ReaderSheetTab.PAGE -> SheetDisplayGroup(prefs = prefs, onUpdate = onUpdate)
                    ReaderSheetTab.FONT -> SheetFontGroup(prefs = prefs, onUpdate = onUpdate)
                }
            }
        }
        Spacer(Modifier.height(ReaderDimens.spaceXS))
        if (onOpenReaderConfig != null) {
            // MiuiX 化的入口：上游在此只放一句「去设置页」的提示，这里直接做成按钮
            TextButton(
                text = "更多设置（屏幕方向 / 点击翻页 / 字体导入）",
                onClick = onOpenReaderConfig,
                modifier = Modifier.padding(horizontal = ReaderDimens.spaceS),
            )
        } else {
            Text(
                text = "更多设置：设置-阅读配置管理",
                modifier = Modifier.padding(horizontal = ReaderDimens.pagePadding),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(ReaderDimens.spaceS))
    }
}

/** 分组页容器：统一内边距（左右 20dp）；内容超高时页内滚动，常规屏幕无需滚动 */
@Composable
private fun SheetGroupPage(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ReaderDimens.pagePadding),
        verticalArrangement = Arrangement.spacedBy(ReaderDimens.spaceM),
    ) {
        content()
        Spacer(Modifier.height(ReaderDimens.spaceS))
    }
}

// ---------- 组 1：翻页模式 ----------

/** 左右翻页 / 上下滚动 快捷切换 */

@Composable
private fun SheetModeGroup(prefs: ReaderPrefs, onUpdate: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ReaderDimens.spaceXS)) {
        PageMode.entries.forEach { mode ->
            FilterChip(
                selected = prefs.pageMode == mode,
                onClick = { onUpdate { it.copy(pageMode = mode) } },
                label = { Text(mode.label) },
            )
        }
    }
}

// ---------- 组 2：页面 ----------

/** 阅读背景 4 档圆色块（选中描边高亮）+ 图片缩放 2 档 + 屏幕常亮 */

@Composable
private fun SheetDisplayGroup(prefs: ReaderPrefs, onUpdate: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    Text(
        text = "阅读背景",
        style = MiuixTheme.textStyles.button,
        fontWeight = FontWeight.SemiBold,
        color = MiuixTheme.colorScheme.primary,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(ReaderDimens.spaceS)) {
        ReadBackground.entries.forEach { bg ->
            val selected = prefs.readBackground == bg
            Surface(
                onClick = { onUpdate { it.copy(readBackground = bg) } },
                modifier = Modifier.size(ReaderDimens.swatch),
                shape = CircleShape,
                color = bg.toComposeColor(),
                border = BorderStroke(
                    width = if (selected) ReaderDimens.borderSelected else ReaderDimens.hairline,
                    color = if (selected) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.dividerLine
                    },
                ),
            ) {}
        }
    }
    Text(
        text = "图片缩放",
        style = MiuixTheme.textStyles.button,
        fontWeight = FontWeight.SemiBold,
        color = MiuixTheme.colorScheme.primary,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(ReaderDimens.spaceXS)) {
        ImageScale.entries.forEach { scale ->
            FilterChip(
                selected = prefs.imageScale == scale,
                onClick = { onUpdate { it.copy(imageScale = scale) } },
                label = { Text(scale.label) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "屏幕常亮",
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Switch(
            checked = prefs.keepScreenOn,
            onCheckedChange = { value -> onUpdate { it.copy(keepScreenOn = value) } },
        )
    }
}

// ---------- 组 3：字体 ----------

/** 小说字体（系统族 / 内置 / 导入 chip）+ 字重 + 字号 + 首行缩进（仅对文字小说生效） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetFontGroup(prefs: ReaderPrefs, onUpdate: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ReaderDimens.spaceXS),
        verticalArrangement = Arrangement.spacedBy(ReaderDimens.spaceXXS),
    ) {
        prefs.novelCustomFont?.let { name ->
            FilterChip(
                selected = true,
                onClick = { },
                label = { Text(name.substringBeforeLast('.')) },
            )
        }
        NovelFontFamily.entries.forEach { family ->
            FilterChip(
                selected = prefs.novelCustomFont == null && prefs.novelFontFamily == family,
                onClick = {
                    onUpdate { it.copy(novelFontFamily = family, novelCustomFont = null) }
                },
                label = { Text(family.label.removePrefix("系统")) },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(ReaderDimens.spaceXS)) {
        NovelFontWeight.entries.forEach { weight ->
            FilterChip(
                selected = prefs.novelFontWeight == weight,
                onClick = { onUpdate { it.copy(novelFontWeight = weight) } },
                label = { Text(weight.label) },
            )
        }
    }
    val fontSizeSlider = remember(prefs.novelFontSizeSp) {
        mutableFloatStateOf(prefs.novelFontSizeSp.coerceIn(12f, 36f))
    }
    Column {
        Text(
            text = "字号 ${fontSizeSlider.floatValue.toInt()} sp",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Slider(
            value = fontSizeSlider.floatValue,
            onValueChange = { fontSizeSlider.floatValue = it },
            onValueChangeFinished = {
                val value = fontSizeSlider.floatValue
                val nearestLegacy = NovelFontSize.entries.minBy { kotlin.math.abs(it.sp - value) }
                onUpdate { it.copy(novelFontSize = nearestLegacy, novelFontSizeSp = value) }
            },
            valueRange = 12f..36f,
            steps = 23,
        )
    }
    NovelSpacingControls(prefs = prefs, onUpdate = onUpdate, compact = true)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "首行缩进",
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Switch(
            checked = prefs.novelFirstLineIndent,
            onCheckedChange = { value ->
                onUpdate { it.copy(novelFirstLineIndent = value) }
            },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "章首另起一页",
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Switch(
            checked = prefs.novelChapterNewPage,
            onCheckedChange = { value ->
                onUpdate { it.copy(novelChapterNewPage = value) }
            },
        )
    }
    Text(
        text = "仅文字小说使用这些排版设置",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/**
 * 全屏目录弹层（Dialog usePlatformDefaultWidth=false）：顶部关闭 + "共 N 章"（无章节时"目录"）
 * + 目录/书签双 tab 胶囊。目录 tab 高亮当前章（[currentPage] 所在章），点击章节跳章节首页并关闭；
 * 书签 tab 沿用书签列表（点击跳页 / 行尾删除）。
 */
@Composable
private fun DirectoryDialog(
    chapters: List<Chapter>,
    bookmarks: List<BookmarkEntity>,
    currentPage: Int,
    tab: Int,
    onTabChange: (Int) -> Unit,
    onJumpTo: (Int) -> Unit,
    onRemoveBookmark: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MiuixTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(WindowInsets.statusBars.asPaddingValues()),
            ) {
                // —— 顶部：✕ 关闭 + 标题 ——
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = ReaderDimens.spaceXXS, end = ReaderDimens.pagePadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = MiuixIcons.Close,
                            contentDescription = "关闭",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = if (chapters.isEmpty()) "目录" else "共 ${chapters.size} 章",
                        modifier = Modifier.weight(1f),
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
                // —— tab 切换：选中深底白字胶囊，未选描边 ——
                Row(
                    modifier = Modifier.padding(horizontal = ReaderDimens.pagePadding, vertical = ReaderDimens.spaceXS),
                    horizontalArrangement = Arrangement.spacedBy(ReaderDimens.spaceXS),
                ) {
                    TabChip(text = "目录", selected = tab == 0, onClick = { onTabChange(0) })
                    TabChip(text = "书签", selected = tab == 1, onClick = { onTabChange(1) })
                }
                when (tab) {
                    1 -> BookmarkList(
                        bookmarks = bookmarks,
                        onJumpTo = onJumpTo,
                        onRemoveBookmark = onRemoveBookmark,
                    )
                    else -> ChapterList(
                        chapters = chapters,
                        currentPage = currentPage,
                        onJumpTo = onJumpTo,
                    )
                }
            }
        }
    }
}

/** 目录 tab 内容：章节列表（标题 + X-Y 页码小字），当前章行背景与文字用 primary 高亮 */
@Composable
private fun ChapterList(
    chapters: List<Chapter>,
    currentPage: Int,
    onJumpTo: (Int) -> Unit,
) {
    if (chapters.isEmpty()) {
        Text(
            text = "本书没有章节结构",
            modifier = Modifier.padding(start = ReaderDimens.pagePadding, end = ReaderDimens.pagePadding, top = ReaderDimens.spaceM),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(chapters) { chapter ->
            // 当前章 = 当前页落在该章闭区间内：背景 primary 微透明 + 文字 primary
            val isCurrent = currentPage in chapter.startPage..chapter.endPageInclusive
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isCurrent) {
                            MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                        } else {
                            Color.Transparent
                        },
                    )
                    .clickable { onJumpTo(chapter.startPage) }
                    .padding(start = ReaderDimens.pagePadding, end = ReaderDimens.pagePadding, top = ReaderDimens.spaceS, bottom = ReaderDimens.spaceS),
            ) {
                Text(
                    text = chapter.title,
                    style = MiuixTheme.textStyles.body1,
                    color = if (isCurrent) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // 页码按 1 起展示："起始页-结束页"
                    text = "${chapter.startPage + 1}-${chapter.endPageInclusive + 1}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (isCurrent) {
                        MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            }
        }
        item { Spacer(Modifier.height(ReaderDimens.spaceM)) }
    }
}

/** 书签 tab 内容：本书书签列表（点击跳页 / 行尾删除），空列表给添加指引 */
@Composable
private fun BookmarkList(
    bookmarks: List<BookmarkEntity>,
    onJumpTo: (Int) -> Unit,
    onRemoveBookmark: (Long) -> Unit,
) {
    if (bookmarks.isEmpty()) {
        Text(
            text = "暂无书签，阅读时点工具栏书签图标添加",
            modifier = Modifier.padding(start = ReaderDimens.pagePadding, end = ReaderDimens.pagePadding, top = ReaderDimens.spaceM),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // 仓库书签流已按创建时间倒序，行尾删除按钮单独处理不触发跳页
        items(bookmarks, key = { it.id }) { bookmark ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onJumpTo(bookmark.pageIndex) }
                    .padding(start = ReaderDimens.pagePadding, end = ReaderDimens.spaceXXS, top = ReaderDimens.spaceXXS, bottom = ReaderDimens.spaceXXS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "第 ${bookmark.pageIndex + 1} 页 · ${formatDate(bookmark.createdAt)}",
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { onRemoveBookmark(bookmark.id) }) {
                    Icon(
                        imageVector = MiuixIcons.Delete,
                        contentDescription = "删除书签",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(ReaderDimens.spaceM)) }
    }
}

/** 弹层 tab 胶囊：选中 primary 深底白字，未选描边透明底 */
@Composable
private fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MiuixTheme.colorScheme.primary else Color.Transparent,
        border = if (selected) {
            null
        } else {
            BorderStroke(ReaderDimens.hairline, MiuixTheme.colorScheme.outline)
        },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = ReaderDimens.spaceM, vertical = ReaderDimens.spaceXS),
            style = MiuixTheme.textStyles.button,
            color = if (selected) {
                MiuixTheme.colorScheme.onPrimary
            } else {
                MiuixTheme.colorScheme.onSurface
            },
        )
    }
}

/**
 * 单页：就绪画图，未就绪转圈，失败提示；进入组合时请求 ViewModel 渲染。
 * [zoomState] 非空 = 页内缩放（双指捏合 + 双击放大 + 平移，左右翻页用）；
 * 为空 = 连续列表模式（缩放由列表层整列处理，仅按 [panX] 做水平平移）。
 * [imageScale] 决定图片适配方式（适合宽度 / 适合屏幕）。
 */
@Composable
private fun ReaderPage(
    page: Int,
    ui: PageUi?,
    bgColor: Color,
    zoomState: PageZoomState?,
    imageScale: ImageScale,
    onNeedRender: (Int) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    panX: () -> Float = { 0f },
) {
    Box(
        modifier.background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        when (ui) {
            is PageUi.Ready -> Image(
                bitmap = ui.bitmap,
                contentDescription = "第 ${page + 1} 页",
                // 适合宽度 = FillWidth；其余（含缺省）= Fit，留白由背景色填充
                contentScale = if (imageScale == ImageScale.FILL_WIDTH) {
                    ContentScale.FillWidth
                } else {
                    ContentScale.Fit
                },
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (zoomState != null) {
                            Modifier
                                // 双指捏合 / 双击缩放 / 缩放态平移；未放大时事件透传给 Pager 翻页
                                .pageZoom(zoomState)
                                .graphicsLayer {
                                    scaleX = zoomState.scale
                                    scaleY = zoomState.scale
                                    translationX = zoomState.offset.x
                                    translationY = zoomState.offset.y
                                }
                        } else {
                            // 连续列表：布局已按整列缩放撑开，这里只做水平平移
                            Modifier.graphicsLayer { translationX = panX() }
                        },
                    ),
            )

            is PageUi.Failed -> Text(
                text = "第 ${page + 1} 页加载失败",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
            )

            else -> CircularProgressIndicator()
        }
    }
    LaunchedEffect(page) {
        if (ui !is PageUi.Ready) onNeedRender(page)
    }
}

/**
 * 胶囊选择钮（原 material3 `FilterChip`）。
 *
 * 本工程界面统一 MiuiX（AGENTS §4.4，有守卫单测强制），而 MiuiX 没有 FilterChip；
 * 这里保留**同名同签名**的本地实现，调用点因此无需逐个改写。选中填充主色容器、
 * 未选中用中性容器，文字颜色经 MiuiX `Surface` 的 `contentColor` 下发给 `label`。
 */
@Composable
private fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (selected) {
            MiuixTheme.colorScheme.primaryContainer
        } else {
            MiuixTheme.colorScheme.surfaceContainer
        },
        contentColor = if (selected) {
            MiuixTheme.colorScheme.onPrimaryContainer
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
    ) {
        Box(Modifier.padding(horizontal = ReaderDimens.spaceM, vertical = ReaderDimens.spaceXS)) { label() }
    }
}

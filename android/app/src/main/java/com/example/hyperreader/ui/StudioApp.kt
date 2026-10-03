package com.example.hyperreader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.anim.DecelerateEasing
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.BreadcrumbBar
import top.yukonga.miuix.kmp.basic.BreadcrumbItem
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import com.example.hyperreader.R
import com.example.hyperreader.Wenku8Application
import com.example.hyperreader.auth.LoginActivity
import com.example.hyperreader.reader.XyReaderActivity
import com.example.hyperreader.reader.onlineReaderIntent
import com.example.hyperreader.ui.AppMiuixTheme
import com.example.hyperreader.ui.BookshelfScreen
import com.example.hyperreader.ui.ExploreDetailScreen
import com.example.hyperreader.ui.ExploreScreen
import com.example.hyperreader.ui.ReadingStatsScreen
import com.example.hyperreader.ui.SearchScreen
import com.example.hyperreader.ui.SettingsSection
import com.example.hyperreader.ui.SettingsScreen
import com.example.hyperreader.ui.formatEta
import com.example.hyperreader.ui.phaseLabel
import com.example.hyperreader.settings.AppThemeMode
import com.example.hyperreader.model.Chapter
import com.example.hyperreader.model.ExportJob
import com.example.hyperreader.model.JobStatus
import com.example.hyperreader.model.SearchField
import com.example.hyperreader.ui.ExportStep
import com.example.hyperreader.ui.ExploreExpandedScreen
import com.example.hyperreader.ui.Motion
import com.example.hyperreader.ui.UiDimens
import com.example.hyperreader.ui.StudioTab
import com.example.hyperreader.ui.StudioUiState
import com.example.hyperreader.ui.StudioViewModel

private val MiSansFont = FontFamily(Font(R.font.misansvf))

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val epubPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            studioViewModel.addLocalEpub(uri.toString(), uri.lastPathSegment?.substringAfterLast('/') ?: "本地 EPUB")
            startActivity(XyReaderActivity.intent(this, uri.toString(), "local:${uri.toString().hashCode()}", uri.lastPathSegment?.substringAfterLast('/') ?: "本地 EPUB"))
        }
    }
    private val fontPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val path = com.example.hyperreader.file.FontStore(this).import(uri)
        if (path != null) studioViewModel.applyImportedFont(path)
    }
    private val loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { studioViewModel.refreshSession() }
    private val exportConfigLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) studioViewModel.exportConfigTo(uri)
    }
    private val importConfigLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) studioViewModel.importConfigFrom(uri)
    }
    private val studioViewModel: StudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleRoute(intent)
        setContent {
            AppMiuixTheme {
                StudioApp(
                    viewModel = studioViewModel,
                    onLogin = { loginLauncher.launch(android.content.Intent(this, LoginActivity::class.java)) },
                    onImportEpub = { epubPicker.launch(arrayOf("application/epub+zip", "application/octet-stream", "application/zip", "*/*")) },
                    onImportFont = { fontPicker.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream")) },
                    onExportConfig = { exportConfigLauncher.launch(com.example.hyperreader.ui.ConfigTransferFile.suggestedName()) },
                    onImportConfig = { importConfigLauncher.launch(com.example.hyperreader.ui.ConfigTransferFile.mimeTypes) },
                )
            }
        }
    }

    /**
     * launchMode=singleTask 时，应用已在前台时通知点击走 onNewIntent 而不会再走 onCreate。
     * 缺少这行会导致热启动时点了通知没反应。
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRoute(intent)
    }

    /**
     * 消费 Intent 里的路由后立即清除 extras，
     * 避免旋转屏幕触发 onCreate 时重复跳转。
     */
    private fun handleRoute(source: android.content.Intent?) {
        val route = source?.getStringExtra(StudioViewModel.EXTRA_ROUTE) ?: return
        val jobId = source.getStringExtra(StudioViewModel.EXTRA_JOB_ID)
        source.removeExtra(StudioViewModel.EXTRA_ROUTE)
        source.removeExtra(StudioViewModel.EXTRA_JOB_ID)
        studioViewModel.route(route, jobId)
    }
}

@Composable
private fun StudioApp(
    viewModel: StudioViewModel,
    onLogin: () -> Unit,
    onImportEpub: () -> Unit,
    onImportFont: () -> Unit,
    onExportConfig: () -> Unit,
    onImportConfig: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settingsTitle = when (state.settingsSection) {
        SettingsSection.OVERVIEW -> "设置"
        SettingsSection.APPEARANCE -> "主题与外观"
        SettingsSection.READER -> "阅读器设置"
        SettingsSection.STATISTICS -> "阅读统计"
        SettingsSection.CATALOG -> "书目缓存"
        SettingsSection.CONFIG -> "配置导入导出"
        SettingsSection.ABOUT -> "关于"
    }
    // 根消息走 Snackbar（可滑走、自动消失），替代源站页的内联错误卡
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        val msg = state.message
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = when {
                    // 全屏覆盖页优先于 tab：探索详情 > 导出记录 > 导出向导 > 主导航
                    state.exploreDetailId != null -> "书籍详情"
                    state.showJobHistory -> "导出记录"
                    state.exploreExpanded -> state.activeExplorePage?.title ?: "榜单"
                    state.searchPageOpen -> "搜索"
                    state.exportStep == ExportStep.RESOLVING -> "解析目录"
                    state.exportStep == ExportStep.CHAPTERS -> "选择章节"
                    state.exportStep == ExportStep.PACKAGING -> "导出设置"
                    state.exportStep == ExportStep.PROGRESS -> "导出进度"
                    state.tab == StudioTab.BOOKSHELF -> "我的书架"
                    state.tab == StudioTab.EXPLORE -> "探索"
                    else -> settingsTitle
                },
            )
        },
        bottomBar = {
            // 全屏覆盖页（探索详情、导出记录、榜单展开、搜索）不显示底部导航
            if (state.exploreDetailId == null && !state.showJobHistory && !state.exploreExpanded && !state.searchPageOpen) {
                NavigationBar {
                    NavigationBarItem(selected = state.tab == StudioTab.BOOKSHELF, onClick = { viewModel.setTab(StudioTab.BOOKSHELF) }, icon = MiuixIcons.Recent, label = "书架")
                    NavigationBarItem(selected = state.tab == StudioTab.EXPLORE, onClick = { viewModel.setTab(StudioTab.EXPLORE) }, icon = MiuixIcons.Search, label = "探索")
                    NavigationBarItem(selected = state.tab == StudioTab.SETTINGS, onClick = { viewModel.setTab(StudioTab.SETTINGS) }, icon = MiuixIcons.Settings, label = "设置")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
            // 全屏覆盖页统一转场（0.18.0）：探索详情 / 导出记录 / 榜单展开 / 搜索 / 导出向导 / tab 内容
            // 五类全屏页互斥，用同一个 AnimatedContent 承载；时长由 Motion 按系统「移除动画」缩放。
            val motionFast = Motion.duration(UiDimens.MOTION_FAST)
            val motionBase = Motion.duration(UiDimens.MOTION_MEDIUM)
            val motionSlow = Motion.duration(UiDimens.MOTION_SLOW)
            val overlayKey = when {
                state.exploreDetailId != null -> "detail"
                state.showJobHistory -> "history"
                state.exploreExpanded -> "expanded"
                state.searchPageOpen -> "search"
                state.exportStep != null -> "export"
                else -> "tabs"
            }
            AnimatedContent(
                targetState = overlayKey,
                transitionSpec = {
                    (fadeIn(tween(motionBase, easing = DecelerateEasing())) +
                        slideInHorizontally(tween(motionSlow, easing = DecelerateEasing())) { it / 8 })
                        .togetherWith(fadeOut(tween(motionFast, easing = DecelerateEasing())))
                },
                label = "overlayTransition",
            ) { overlay ->
                when (overlay) {
                    "detail" -> ExploreDetailScreen(
                    seed = state.exploreDetailSeed,
                    detail = state.exploreDetail,
                    loading = state.exploreDetailLoading,
                    error = state.exploreDetailError,
                    onBack = viewModel::closeExploreDetail,
                    onRetry = viewModel::retryExploreDetail,
                    onAddToShelf = viewModel::addExploreDetailToShelf,
                    onExport = viewModel::exportExploreDetail,
                    onReadOnline = {
                        // 用 ?.let 而非 `?: return@ExploreDetailScreen`：后者依赖非内联 lambda
                        // 的函数名标签推断，在部分编译器/增量场景下会解析失败。
                        state.exploreDetail?.book?.let { book ->
                            context.startActivity(
                                onlineReaderIntent(
                                    context = context,
                                    bookId = book.id.orEmpty(),
                                    title = book.title,
                                    author = book.author,
                                )
                            )
                        }
                    },
                    // 目录里点某一章：直接以该章为起始章在线读（0.18.0）
                    onReadChapter = { chapter ->
                        val book = state.exploreDetail?.book
                        context.startActivity(
                            onlineReaderIntent(
                                context = context,
                                bookId = book?.id?.orEmpty().orEmpty().ifBlank { chapter.id.filter(Char::isDigit) },
                                title = book?.title.orEmpty(),
                                author = book?.author.orEmpty(),
                                startChapterId = chapter.id,
                            )
                        )
                    },
                    onSameAuthor = { state.exploreDetail?.book?.id?.let(viewModel::expandAuthor) },
                    onTagClick = { tag ->
                        viewModel.setSearchField(SearchField.TITLE)
                        viewModel.setSearchQuery(tag)
                        viewModel.setTab(StudioTab.EXPLORE)
                        viewModel.searchLocal()
                    },
                )
                    "history" -> JobHistoryScreen(state, viewModel)
                    "expanded" -> ExploreExpandedScreen(state, viewModel)
                    "search" -> SearchScreen(state = state, viewModel = viewModel, onLogin = onLogin, onClose = viewModel::closeSearchPage)
                    "export" -> ExportWizardScreen(state, viewModel)
                    else -> {
                        val pageKey = state.tab.name.lowercase()
                        AnimatedContent(
                            targetState = pageKey,
                            transitionSpec = {
                                val direction = if (targetState > initialState) 1 else -1
                                (fadeIn(tween(motionBase, easing = DecelerateEasing())) +
                                    slideInHorizontally(tween(motionSlow, easing = DecelerateEasing())) { direction * it / 6 })
                                    .togetherWith(fadeOut(tween(motionFast, easing = DecelerateEasing())))
                            },
                            label = "pageTransition",
                        ) { _ ->
                            when {
                state.tab == StudioTab.BOOKSHELF -> BookshelfScreen(
                    state = state,
                    viewModel = viewModel,
                    onImportEpub = onImportEpub,
                    onOpenLocal = { entry ->
                        entry.localUri?.let { uri ->
                            context.startActivity(XyReaderActivity.intent(context, uri, entry.bookId, entry.title))
                        }
                    },
                    // 0.17.0：远程书点一下直接进阅读器，详情/导出改走卡片菜单
                    onOpenRemote = { entry ->
                        viewModel.markShelfRead(entry.id)
                        context.startActivity(
                            onlineReaderIntent(
                                context = context,
                                bookId = entry.bookId,
                                title = entry.title,
                                author = entry.author,
                                bookshelfId = entry.id,
                            ),
                        )
                    },
                    onOpenDetail = { entry -> viewModel.openShelfBookDetail(entry) },
                )
                state.tab == StudioTab.EXPLORE -> ExploreScreen(
                    state = state,
                    viewModel = viewModel,
                    onLogin = onLogin,
                    onGoToCatalog = {
                        viewModel.setTab(StudioTab.SETTINGS)
                        viewModel.openSettingsSection(SettingsSection.CATALOG)
                    },
                    onOpenSearch = viewModel::openSearchPage,
                )
                state.tab == StudioTab.SETTINGS -> SettingsScreen(
                    viewModel = viewModel,
                    onImportEpub = onImportEpub,
                    onImportFont = onImportFont,
                    onExportConfig = onExportConfig,
                    onImportConfig = onImportConfig,
                )
                            }
                        }
                    }
                }
            }

            // 导出向导的面包屑：只在向导内部出现，不占主导航的位置
            if (state.exportStep != null) {
                val steps = listOf(
                    ExportStep.RESOLVING to "目录",
                    ExportStep.CHAPTERS to "章节",
                    ExportStep.PACKAGING to "打包",
                    ExportStep.PROGRESS to "进度",
                )
                BreadcrumbBar(
                    items = steps.map { BreadcrumbItem(path = it.first.name, text = it.second) },
                    onItemClick = { },
                    highlightIndex = steps.indexOfFirst { it.first == state.exportStep }.coerceAtLeast(0),
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * 导出向导（0.17.0 起为二级页覆盖层，从书架卡片菜单或探索详情发起）。
 *
 * 四步：解析目录（[ExportStep.RESOLVING]）→ 选章节 → 打包设置 → 进度。
 * 章节/打包/进度三屏沿用既有实现，这里只新增解析态并在关闭时回退到发起页面。
 */
@Composable
private fun ExportWizardScreen(state: StudioUiState, viewModel: StudioViewModel) {
    when (state.exportStep) {
        ExportStep.RESOLVING -> ResolvingScreen(state, viewModel)
        ExportStep.CHAPTERS -> ChaptersScreen(state, viewModel)
        ExportStep.PACKAGING -> ExportScreen(state, viewModel)
        ExportStep.PROGRESS -> ProgressScreen(state, viewModel)
        null -> Unit
    }
}

/** 解析目录：带加载指示、失败原因与重试；任何时刻都能关闭向导回到书架。 */
@Composable
private fun ResolvingScreen(state: StudioUiState, viewModel: StudioViewModel) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 18.dp),
        verticalArrangement = Arrangement.spacedBy(UiDimens.spaceM),
    ) {
        TextButton(text = "‹ 关闭", onClick = viewModel::closeExport)
        Text("正在读取目录…", fontSize = UiDimens.title, fontWeight = FontWeight.Bold)
        state.detailError?.let { error ->
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceM)) {
                    Text("目录读取失败", fontWeight = FontWeight.Bold)
                    Text(error, fontSize = UiDimens.body, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f))
                    Button(
                        onClick = viewModel::retryResolve,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.busy) "正在重试…" else "重试") }
                }
            }
        }
        if (state.detailError == null) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceM),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(size = 22.dp)
                Text("正在获取章节列表，请稍候。", fontSize = UiDimens.body, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.75f))
            }
        }
    }
}

/**
 * 导出记录（0.17.0 起独立成页，入口在设置 → 概览与通知栏路由）。
 *
 * 内容直接复用既有的 [HistoryScreen]（进度 / 取消 / 警告 / 保存 / 分享），这里只补返回按钮。
 */
@Composable
private fun JobHistoryScreen(state: StudioUiState, viewModel: StudioViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(UiDimens.spaceM)) {
        TextButton(text = "‹ 返回", onClick = { viewModel.setShowJobHistory(false) })
        HistoryScreen(state.jobs, viewModel)
    }
}

@Composable
private fun ChaptersScreen(state: StudioUiState, viewModel: StudioViewModel) {
    val book = state.book ?: return
    val index = state.index ?: return
    val visible = remember(state.search, index) {
        index.chapters.filter { state.search.isBlank() || it.title.contains(state.search, true) || it.volume.contains(state.search, true) }
    }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(UiDimens.spaceM)) {
        TextButton(text = "‹ 关闭向导", onClick = viewModel::closeExport)
        BookHeader(book.title, book.author, book.category, index.chapters.size, state.selectedIds.size)
        TextField(value = state.search, onValueChange = viewModel::setSearch, label = "搜索章节标题", useLabelAsPlaceholder = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(text = "全选", onClick = viewModel::selectAll)
            Spacer(Modifier.width(8.dp))
            TextButton(text = "清空", onClick = viewModel::clearSelection)
        }
        Text("已选择 ${state.selectedIds.size} / ${index.chapters.size} 章", color = MiuixTheme.colorScheme.primary, fontSize = UiDimens.caption)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS)) {
            items(visible, key = { it.id }) { chapter -> ChapterRow(chapter, chapter.id in state.selectedIds) { viewModel.toggleChapter(chapter.id) } }
        }
        Button(onClick = viewModel::toExport, enabled = state.selectedIds.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("继续导出设置") }
    }
}

@Composable
private fun ExportScreen(state: StudioUiState, viewModel: StudioViewModel) {
    val book = state.book ?: return
    val selected = state.index?.chapters?.count { it.id in state.selectedIds } ?: 0
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 18.dp),
        verticalArrangement = Arrangement.spacedBy(UiDimens.spaceM),
    ) {
        TextButton(text = "‹ 返回章节", onClick = viewModel::backToChapters)
        BookHeader(book.title, book.author, book.category, selected, selected)
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceM)) {
                Text("导出摘要", fontWeight = FontWeight.Bold)
                Text("章节：$selected", fontSize = UiDimens.bodyStrong)
                Text("格式：EPUB 3 + NCX", fontSize = UiDimens.bodyStrong)
                Text("保存位置：Download/EPUB", fontSize = UiDimens.bodyStrong)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("包含书籍封面", fontWeight = FontWeight.Bold)
                        Text("封面下载失败不会阻止正文导出", fontSize = UiDimens.captionSmall, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f))
                    }
                    Switch(checked = state.includeCover, onCheckedChange = viewModel::setCover)
                }
            }
        }
        Button(onClick = viewModel::startExport, enabled = !state.busy && selected > 0, modifier = Modifier.fillMaxWidth()) { Text(if (state.busy) "正在准备…" else "开始生成 EPUB") }
    }
}

@Composable
private fun ProgressScreen(state: StudioUiState, viewModel: StudioViewModel) {
    val job = state.jobs.firstOrNull { it.id == state.activeJobId } ?: state.jobs.firstOrNull { it.status == JobStatus.running }
    if (job == null) {
        Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(UiDimens.spaceM)) {
            TextButton(text = "‹ 关闭向导", onClick = viewModel::closeExport)
            Text("任务状态已更新。", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
            TextButton(text = "查看导出记录", onClick = { viewModel.closeExport(); viewModel.setShowJobHistory(true) })
        }
        return
    }
    ProgressContent(job, viewModel)
}

@Composable
private fun ProgressContent(job: ExportJob, viewModel: StudioViewModel) {
    var showWarnings by remember { mutableStateOf(false) }
    val progress = job.progress.percent / 100f
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 18.dp),
        verticalArrangement = Arrangement.spacedBy(UiDimens.spaceM),
    ) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceM)) {
                Text(job.book.title, fontSize = UiDimens.title, fontWeight = FontWeight.Bold)
                Text(job.progress.message, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f))
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                    Text(phaseLabel(job.progress.phase), fontSize = UiDimens.caption, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary)
                    if (job.progress.etaSeconds >= 0) {
                        Text("剩余约 ${formatEta(job.progress.etaSeconds)}", fontSize = UiDimens.caption, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${job.progress.percent}%", fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary)
                    Text("章节 ${job.progress.completed}/${job.progress.total}", fontSize = UiDimens.caption)
                }
                Text(
                    if (job.progress.imageTotal > 0) "插图 ${job.progress.imageCompleted}/${job.progress.imageTotal} 张" else "插图 ${job.progress.imageCompleted} 张",
                    fontSize = UiDimens.caption,
                )
                if (job.progress.cacheHits > 0) {
                    Text("缓存命中 ${job.progress.cacheHits} 项（0 请求）", fontSize = UiDimens.caption, color = MiuixTheme.colorScheme.primary)
                }
                if (job.progress.currentTitle.isNotBlank()) Text("当前：${job.progress.currentTitle}", fontSize = UiDimens.caption, maxLines = 2)
            }
        }
        if (job.warnings.isNotEmpty()) TextButton(text = "查看 ${job.warnings.size} 条警告", onClick = { showWarnings = true }, modifier = Modifier.fillMaxWidth())
        when (job.status) {
            JobStatus.queued, JobStatus.running -> Button(onClick = { viewModel.cancel(job.id) }, modifier = Modifier.fillMaxWidth()) { Text("取消任务") }
            JobStatus.completed -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceM)) {
                    Button(onClick = { viewModel.save(job.id) }, modifier = Modifier.weight(1f)) { Icon(MiuixIcons.Download, "保存") }
                    Button(onClick = { viewModel.share(job.id) }, modifier = Modifier.weight(1f)) { Icon(MiuixIcons.Share, "分享") }
                }
                Text("已保存到 Download/EPUB，可随时再次保存。", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f), fontSize = UiDimens.caption)
            }
            JobStatus.failed -> MessageCard(job.error?.message ?: "任务失败")
            JobStatus.canceled -> MessageCard("任务已取消。")
        }
    }
    if (showWarnings) {
        OverlayDialog(show = true, title = "导出警告", summary = "以下项目被跳过，但 EPUB 仍会继续生成。", onDismissRequest = { showWarnings = false }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                LazyColumn(Modifier.height(260.dp), verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                    items(job.warnings) { warning -> Text("• $warning", fontSize = UiDimens.body) }
                }
                Button(onClick = { showWarnings = false }, modifier = Modifier.fillMaxWidth()) { Text("知道了") }
            }
        }
    }
}

@Composable
private fun HistoryScreen(jobs: List<ExportJob>, viewModel: StudioViewModel) {
    val context = LocalContext.current
    if (jobs.isEmpty()) { Text("还没有导出任务。", Modifier.padding(top = 20.dp), color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f)); return }
    LazyColumn(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(UiDimens.spaceM)) {
        items(jobs, key = { it.id }) { job ->
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(job.book.title, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text(statusText(job), color = MiuixTheme.colorScheme.primary, fontSize = UiDimens.caption)
                    Text("${job.chapterCount} 章 · ${job.progress.percent}% · ${job.createdAt.take(10)}", fontSize = UiDimens.captionSmall, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f))
                    if (job.status == JobStatus.completed) {
                        Row(horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                            TextButton(text = "保存", onClick = { viewModel.save(job.id) })
                            TextButton(text = "分享", onClick = { viewModel.share(job.id) })
                            job.output?.let { output ->
                                TextButton(text = "阅读", onClick = { context.startActivity(XyReaderActivity.intent(context, output.uri, job.id, job.book.title)) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookHeader(title: String, author: String, category: String, total: Int, selected: Int) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(category, color = MiuixTheme.colorScheme.primary, fontSize = UiDimens.captionSmall, fontWeight = FontWeight.Bold)
            Text(title, fontSize = UiDimens.title, fontWeight = FontWeight.Bold, maxLines = 2)
            Text("$author · $total 章 · 已选 $selected", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f), fontSize = UiDimens.caption)
        }
    }
}

@Composable
private fun ChapterRow(chapter: Chapter, selected: Boolean, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp), onClick = onToggle) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // MiuiX 0.9.4 起 Checkbox 改用 Material 风格的 ToggleableState + onClick
            Checkbox(
                state = if (selected) ToggleableState.On else ToggleableState.Off,
                onClick = onToggle,
            )
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(chapter.title, maxLines = 2, fontSize = UiDimens.bodyStrong)
                Text(chapter.volume + if (chapter.isIllustration) " · 插图章节" else "", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f), fontSize = UiDimens.captionSmall)
            }
        }
    }
}

@Composable
internal fun MessageCard(message: String) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) { Text(message, color = MiuixTheme.colorScheme.error, fontSize = UiDimens.body) }
}

private fun statusText(job: ExportJob): String = when (job.status) {
    JobStatus.queued -> "排队中"
    JobStatus.running -> job.progress.message
    JobStatus.completed -> "已完成"
    JobStatus.failed -> job.error?.message ?: "失败"
    JobStatus.canceled -> "已取消"
}

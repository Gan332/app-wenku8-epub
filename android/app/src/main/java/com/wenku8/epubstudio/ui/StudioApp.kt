package com.wenku8.epubstudio

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
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
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.BreadcrumbBar
import top.yukonga.miuix.kmp.basic.BreadcrumbItem
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import com.wenku8.epubstudio.R
import com.wenku8.epubstudio.Wenku8Application
import com.wenku8.epubstudio.auth.LoginActivity
import com.wenku8.epubstudio.reader.ReaderActivity
import com.wenku8.epubstudio.ui.AppMiuixTheme
import com.wenku8.epubstudio.ui.BookDetailScreen
import com.wenku8.epubstudio.ui.BookshelfScreen
import com.wenku8.epubstudio.ui.ExploreScreen
import com.wenku8.epubstudio.ui.ReadingStatsScreen
import com.wenku8.epubstudio.ui.SearchScreen
import com.wenku8.epubstudio.ui.SettingsSection
import com.wenku8.epubstudio.ui.SettingsScreen
import com.wenku8.epubstudio.settings.AppThemeMode
import com.wenku8.epubstudio.model.Chapter
import com.wenku8.epubstudio.model.ExportJob
import com.wenku8.epubstudio.model.JobStatus
import com.wenku8.epubstudio.model.SearchField
import com.wenku8.epubstudio.ui.CreateStep
import com.wenku8.epubstudio.ui.StudioTab
import com.wenku8.epubstudio.ui.StudioUiState
import com.wenku8.epubstudio.ui.StudioViewModel

private val MiSansFont = FontFamily(Font(R.font.misansvf))

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val epubPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            studioViewModel.addLocalEpub(uri.toString(), uri.lastPathSegment?.substringAfterLast('/') ?: "本地 EPUB")
            startActivity(android.content.Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_URI, uri.toString()).putExtra(ReaderActivity.EXTRA_BOOK_ID, "local:${uri.toString().hashCode()}"))
        }
    }
    private val fontPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val path = com.wenku8.epubstudio.file.FontStore(this).import(uri)
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
                    onExportConfig = { exportConfigLauncher.launch(com.wenku8.epubstudio.ui.ConfigTransferFile.suggestedName()) },
                    onImportConfig = { importConfigLauncher.launch(com.wenku8.epubstudio.ui.ConfigTransferFile.mimeTypes) },
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = when {
                    state.tab == StudioTab.BOOKSHELF -> "我的书架"
                    state.tab == StudioTab.EXPLORE -> "探索"
                    state.tab == StudioTab.SETTINGS -> settingsTitle
                    state.step == CreateStep.SOURCE -> "文库 EPUB 工坊"
                    state.step == CreateStep.DETAIL -> "书籍详情"
                    state.step == CreateStep.CHAPTERS -> "选择章节"
                    state.step == CreateStep.EXPORT -> "导出设置"
                    else -> "导出进度"
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = state.tab == StudioTab.BOOKSHELF, onClick = { viewModel.setTab(StudioTab.BOOKSHELF) }, icon = Icons.Default.History, label = "书架")
                NavigationBarItem(selected = state.tab == StudioTab.EXPLORE, onClick = { viewModel.setTab(StudioTab.EXPLORE) }, icon = Icons.Default.Search, label = "探索")
                NavigationBarItem(selected = state.tab == StudioTab.CREATE, onClick = { viewModel.setTab(StudioTab.CREATE) }, icon = Icons.Default.Add, label = "创建")
                NavigationBarItem(selected = state.tab == StudioTab.SETTINGS, onClick = { viewModel.setTab(StudioTab.SETTINGS) }, icon = Icons.Default.Settings, label = "设置")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
            when {
                state.tab == StudioTab.BOOKSHELF -> BookshelfScreen(state, viewModel, onImportEpub, onOpenLocal = { entry ->
                    entry.localUri?.let { uri ->
                        context.startActivity(android.content.Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_URI, uri).putExtra(ReaderActivity.EXTRA_BOOK_ID, entry.bookId))
                    }
                }, onOpenRemote = { entry -> viewModel.openShelfRemote(entry) })
                state.tab == StudioTab.EXPLORE -> ExploreScreen(state, viewModel, onLogin) {
                    viewModel.setTab(StudioTab.SETTINGS)
                    viewModel.openSettingsSection(SettingsSection.CATALOG)
                }
                state.tab == StudioTab.SETTINGS -> SettingsScreen(
                    viewModel = viewModel,
                    onImportEpub = onImportEpub,
                    onImportFont = onImportFont,
                    onExportConfig = onExportConfig,
                    onImportConfig = onImportConfig,
                )
                state.step == CreateStep.SOURCE -> SourceScreen(state, viewModel)
                state.step == CreateStep.DETAIL -> state.book?.let {
                    BookDetailScreen(
                        book = it,
                        chapterCount = state.index?.chapters?.size ?: 0,
                        viewModel = viewModel,
                        loading = state.busy,
                        loadError = state.detailError,
                    ) { tag ->
                        viewModel.setSearchField(SearchField.TITLE)
                        viewModel.setSearchQuery(tag)
                        viewModel.setTab(StudioTab.EXPLORE)
                        viewModel.searchLocal()
                    }
                } ?: SourceScreen(state, viewModel)
                state.step == CreateStep.CHAPTERS -> ChaptersScreen(state, viewModel)
                state.step == CreateStep.EXPORT -> ExportScreen(state, viewModel)
                state.step == CreateStep.PROGRESS -> ProgressScreen(state, viewModel)
            }

            if (state.tab == StudioTab.CREATE) {
                val steps = listOf(
                    CreateStep.SOURCE to "源站",
                    CreateStep.DETAIL to "详情",
                    CreateStep.CHAPTERS to "章节",
                    CreateStep.EXPORT to "导出",
                    CreateStep.PROGRESS to "进度",
                )
                BreadcrumbBar(
                    items = steps.map { BreadcrumbItem(path = it.first.name, text = it.second) },
                    onItemClick = { },
                    highlightIndex = steps.indexOfFirst { it.first == state.step }.coerceAtLeast(0),
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SourceScreen(state: StudioUiState, viewModel: StudioViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("把公开轻小说整理成可离线阅读的 EPUB。", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("支持 wenku8 书籍页、目录页或纯书籍 ID。请求会遵守源站限流。", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f), fontSize = 14.sp)
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = state.sourceUrl,
                    onValueChange = viewModel::setSource,
                    label = "wenku8 书籍或目录网址",
                    useLabelAsPlaceholder = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = viewModel::parseSource,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.busy) "正在解析…" else "解析书籍") }
                TextButton(text = "填入示例", onClick = { viewModel.setSource("https://www.wenku8.net/novel/2/2835/index.htm") }, modifier = Modifier.align(Alignment.End))
            }
        }
        state.message?.let { MessageCard(it) }
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("导出内容", fontWeight = FontWeight.Bold)
                Text("• 清理章节广告和无关脚本", fontSize = 14.sp)
                Text("• 下载并本地化正文插图", fontSize = 14.sp)
                Text("• 生成 EPUB 3 / NCX，支持离线阅读", fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ChaptersScreen(state: StudioUiState, viewModel: StudioViewModel) {
    val book = state.book ?: return
    val index = state.index ?: return
    val visible = remember(state.search, index) {
        index.chapters.filter { state.search.isBlank() || it.title.contains(state.search, true) || it.volume.contains(state.search, true) }
    }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BookHeader(book.title, book.author, book.category, index.chapters.size, state.selectedIds.size)
        TextField(value = state.search, onValueChange = viewModel::setSearch, label = "搜索章节标题", useLabelAsPlaceholder = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(text = "全选", onClick = viewModel::selectAll)
            Spacer(Modifier.width(8.dp))
            TextButton(text = "清空", onClick = viewModel::clearSelection)
        }
        Text("已选择 ${state.selectedIds.size} / ${index.chapters.size} 章", color = MiuixTheme.colorScheme.primary, fontSize = 13.sp)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(visible, key = { it.id }) { chapter -> ChapterRow(chapter, chapter.id in state.selectedIds) { viewModel.toggleChapter(chapter.id) } }
        }
        Button(onClick = viewModel::toExport, enabled = state.selectedIds.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("继续导出设置") }
    }
}

@Composable
private fun ExportScreen(state: StudioUiState, viewModel: StudioViewModel) {
    val book = state.book ?: return
    val selected = state.index?.chapters?.count { it.id in state.selectedIds } ?: 0
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(text = "‹ 返回章节", onClick = viewModel::backToChapters)
        BookHeader(book.title, book.author, book.category, selected, selected)
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("导出摘要", fontWeight = FontWeight.Bold)
                Text("章节：$selected", fontSize = 15.sp)
                Text("格式：EPUB 3 + NCX", fontSize = 15.sp)
                Text("保存位置：Download/EPUB", fontSize = 15.sp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("包含书籍封面", fontWeight = FontWeight.Bold)
                        Text("封面下载失败不会阻止正文导出", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f))
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
    if (job == null) { Text("任务状态已更新。", Modifier.padding(top = 20.dp)); return }
    ProgressContent(job, viewModel)
}

@Composable
private fun ProgressContent(job: ExportJob, viewModel: StudioViewModel) {
    var showWarnings by remember { mutableStateOf(false) }
    val progress = job.progress.percent / 100f
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(job.book.title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(job.progress.message, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f))
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${job.progress.percent}%", fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary)
                    Text("章节 ${job.progress.completed}/${job.progress.total}", fontSize = 13.sp)
                }
                Text("插图 ${job.progress.imageCompleted} 张", fontSize = 13.sp)
                if (job.progress.currentTitle.isNotBlank()) Text("当前：${job.progress.currentTitle}", fontSize = 13.sp, maxLines = 2)
            }
        }
        if (job.warnings.isNotEmpty()) TextButton(text = "查看 ${job.warnings.size} 条警告", onClick = { showWarnings = true }, modifier = Modifier.fillMaxWidth())
        when (job.status) {
            JobStatus.queued, JobStatus.running -> Button(onClick = { viewModel.cancel(job.id) }, modifier = Modifier.fillMaxWidth()) { Text("取消任务") }
            JobStatus.completed -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { viewModel.save(job.id) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Download, "保存") }
                    Button(onClick = { viewModel.share(job.id) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Share, "分享") }
                }
                Text("已保存到 Download/EPUB，可随时再次保存。", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f), fontSize = 13.sp)
            }
            JobStatus.failed -> MessageCard(job.error?.message ?: "任务失败")
            JobStatus.canceled -> MessageCard("任务已取消。")
        }
    }
    if (showWarnings) {
        OverlayDialog(show = true, title = "导出警告", summary = "以下项目被跳过，但 EPUB 仍会继续生成。", onDismissRequest = { showWarnings = false }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(Modifier.height(260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(job.warnings) { warning -> Text("• $warning", fontSize = 14.sp) }
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
    LazyColumn(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(jobs, key = { it.id }) { job ->
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(job.book.title, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text(statusText(job), color = MiuixTheme.colorScheme.primary, fontSize = 13.sp)
                    Text("${job.chapterCount} 章 · ${job.progress.percent}% · ${job.createdAt.take(10)}", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f))
                    if (job.status == JobStatus.completed) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(text = "保存", onClick = { viewModel.save(job.id) })
                            TextButton(text = "分享", onClick = { viewModel.share(job.id) })
                            job.output?.let { output ->
                                TextButton(text = "阅读", onClick = { context.startActivity(android.content.Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_URI, output.uri).putExtra(ReaderActivity.EXTRA_BOOK_ID, job.id)) })
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
            Text(category, color = MiuixTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text("$author · $total 章 · 已选 $selected", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f), fontSize = 13.sp)
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
                Text(chapter.title, maxLines = 2, fontSize = 15.sp)
                Text(chapter.volume + if (chapter.isIllustration) " · 插图章节" else "", color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f), fontSize = 11.sp)
            }
        }
    }
}

@Composable
internal fun MessageCard(message: String) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) { Text(message, color = MiuixTheme.colorScheme.error, fontSize = 14.sp) }
}

private fun statusText(job: ExportJob): String = when (job.status) {
    JobStatus.queued -> "排队中"
    JobStatus.running -> job.progress.message
    JobStatus.completed -> "已完成"
    JobStatus.failed -> job.error?.message ?: "失败"
    JobStatus.canceled -> "已取消"
}

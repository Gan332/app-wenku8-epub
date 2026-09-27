package com.example.hyperreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.example.hyperreader.model.BookshelfEntry
import com.example.hyperreader.model.BookshelfSource
import com.example.hyperreader.reader.onlineReaderIntent
import com.example.hyperreader.settings.ReadingProgress
import com.example.hyperreader.ui.cover.CoverImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BookshelfScreen(
    state: StudioUiState,
    viewModel: StudioViewModel,
    onImportEpub: () -> Unit,
    onOpenLocal: (BookshelfEntry) -> Unit,
    onOpenRemote: (BookshelfEntry) -> Unit,
) {
    val showHistory = state.showJobHistory
    // 「更多」弹出菜单的锚定条目 id（null = 关闭）。
    // 交互：点卡片主体直接打开书，行尾 More 弹操作菜单（替换旧的全屏操作弹窗）。
    var menuEntryId by remember { mutableStateOf<String?>(null) }
    // 阅读断点（第 x 章 · 第 y 段）：reader_progress_ 前缀键的全量视图
    val readingProgress by viewModel.readingProgress.collectAsStateWithLifecycle(initialValue = emptyMap())
    // 数据变化时刷新「N 分钟前」的基准时刻，避免长开应用后相对时间停在启动瞬间
    val now = remember(state.bookshelf, readingProgress) { System.currentTimeMillis() }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(text = if (showHistory) "返回书架" else "导出记录", onClick = { viewModel.setShowJobHistory(!showHistory) })
        }
        if (showHistory) {
            Text("导出记录", fontSize = 25.sp, fontWeight = FontWeight.Bold)
            if (state.jobs.isEmpty()) Text("还没有导出任务。", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 14.sp)
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.jobs, key = { it.id }) { job ->
                    Card(Modifier.fillMaxWidth().animateItem(), insideMargin = PaddingValues(14.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(job.book.title, fontWeight = FontWeight.Bold, maxLines = 2)
                            Text("${job.chapterCount} 章 · ${job.progress.percent}% · ${job.createdAt.take(10)}", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(text = "保存", onClick = { viewModel.save(job.id) })
                                TextButton(text = "分享", onClick = { viewModel.share(job.id) })
                            }
                        }
                    }
                }
            }
            return
        }
        Text("书架", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("在线书籍和本地 EPUB 都可以在这里继续阅读。", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 13.sp)
        Button(onClick = onImportEpub, modifier = Modifier.fillMaxWidth()) { Text("导入 EPUB") }
        ActiveExportSection(
            jobs = state.jobs,
            onOpen = { jobId -> viewModel.route(StudioViewModel.ROUTE_EXPORT_PROGRESS, jobId) },
            onCancel = { jobId -> viewModel.cancel(jobId) },
        )
        HorizontalDivider(Modifier.fillMaxWidth())
        if (state.bookshelf.isEmpty()) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                Text("书架还是空的。\n可以从“探索”加入 Wenku8 书籍，或导入本地 EPUB。", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .8f))
            }
        } else {
            Text("共 ${state.bookshelf.size} 本", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 13.sp)
            val listState = rememberLazyListState()
            Box(Modifier.fillMaxWidth()) {
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.bookshelf, key = { it.id }) { entry ->
                        Box {
                            BookshelfCard(
                                entry,
                                readingProgress[entry.bookId],
                                now,
                                modifier = Modifier.animateItem(),
                                onMore = { menuEntryId = entry.id },
                            ) {
                                // 卡片主体直接打开：本地书进阅读器并记阅读，远程书进详情页
                                if (entry.localUri != null) {
                                    viewModel.markShelfRead(entry.id)
                                    onOpenLocal(entry)
                                } else {
                                    onOpenRemote(entry)
                                }
                            }
                            if (menuEntryId == entry.id) {
                                // Popup(alignment, offset) 的 offset 是 IntOffset（像素），需按 density 换算
                                val menuOffset = with(LocalDensity.current) { IntOffset(0, 120.dp.roundToPx()) }
                                Popup(
                                    onDismissRequest = { menuEntryId = null },
                                    alignment = Alignment.TopEnd,
                                    offset = menuOffset,
                                ) {
                                    BookEntryMenu(
                                        entry = entry,
                                        viewModel = viewModel,
                                        onOpenLocal = { onOpenLocal(it); menuEntryId = null },
                                        onOpenRemote = { onOpenRemote(it); menuEntryId = null },
                                        onDismiss = { menuEntryId = null },
                                    )
                                }
                            }
                        }
                    }
                }
                VerticalScrollBar(
                    rememberScrollBarAdapter(listState),
                    Modifier.align(Alignment.TopEnd).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun BookshelfCard(
    entry: BookshelfEntry,
    resume: ReadingProgress?,
    now: Long,
    modifier: Modifier = Modifier,
    onMore: () -> Unit,
    onOpen: (BookshelfEntry) -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable { onOpen(entry) },
        insideMargin = PaddingValues(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            CoverImage(
                url = entry.coverUrl,
                contentDescription = entry.title,
                modifier = Modifier.size(64.dp, 88.dp).clip(RoundedCornerShape(6.dp)),
                targetWidthDp = 192,
            )
            Column(
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(entry.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(entry.author, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), maxLines = 1)
                val meta = buildList {
                    add(if (entry.source == BookshelfSource.LOCAL_EPUB) "本地 EPUB" else "Wenku8")
                    entry.wordCount?.let { add("${formatWordCount(it)} 字") }
                    if (entry.chapterCount > 0) add("${entry.chapterCount} 章")
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(meta.joinToString(" · "), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f), maxLines = 1)
                    if (entry.isPinned) {
                        Badge(containerColor = MiuixTheme.colorScheme.primary) { Text("置顶", fontSize = 10.sp) }
                    }
                }
                // 上次阅读时间 + 断点：未读过的书（lastReadAt=0）整行不显示
                if (entry.lastReadAt > 0L) {
                    val resumeText = resume?.let { " · 读到 第${it.chapterIndex + 1}章 · 第${it.paragraphIndex + 1}段" }.orEmpty()
                    Text(
                        "上次阅读：${formatRelativeReadTime(now, entry.lastReadAt)}$resumeText",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.primary.copy(alpha = .9f),
                        maxLines = 1,
                    )
                }
            }
            IconButton(onClick = onMore, modifier = Modifier.size(TOUCH_MIN.dp)) {
                Icon(MiuixIcons.More, contentDescription = "更多")
            }
        }
    }
}

/** 触摸目标下限（与阅读器约定一致）。 */
private const val TOUCH_MIN = 48

/**
 * 书架条目「更多」操作菜单（Popup 锚定卡片右下，全部 MiuiX 组件）。
 * 按书籍来源分组：本地 EPUB 只有打开类操作，Wenku8 书多出详情/在线/同作者。
 */
@Composable
private fun BookEntryMenu(
    entry: BookshelfEntry,
    viewModel: StudioViewModel,
    onOpenLocal: (BookshelfEntry) -> Unit,
    onOpenRemote: (BookshelfEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    fun act(block: () -> Unit) {
        block()
        onDismiss()
    }
    Card(insideMargin = PaddingValues(vertical = 4.dp)) {
        Column(Modifier.width(IntrinsicSize.Min)) {
            if (entry.localUri != null) {
                BasicComponent(title = "打开阅读", onClick = { act { viewModel.markShelfRead(entry.id); onOpenLocal(entry) } })
            } else {
                BasicComponent(title = "查看详情", onClick = { act { onOpenRemote(entry) } })
                BasicComponent(
                    title = "在线阅读",
                    onClick = {
                        act {
                            context.startActivity(
                                onlineReaderIntent(
                                    context = context,
                                    bookId = entry.bookId,
                                    title = entry.title,
                                    author = entry.author,
                                    bookshelfId = entry.id,
                                ),
                            )
                            viewModel.markShelfRead(entry.id)
                        }
                    },
                )
                BasicComponent(title = "同作者作品", onClick = { act { viewModel.expandAuthor(entry.bookId) } })
            }
            BasicComponent(
                title = if (entry.isPinned) "取消置顶" else "置顶",
                onClick = { act { viewModel.setPinned(entry.id, !entry.isPinned) } },
            )
            BasicComponent(title = "移出书架", onClick = { act { viewModel.removeFromShelf(entry.id) } })
        }
    }
}

package com.example.hyperreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hyperreader.model.BookshelfEntry
import com.example.hyperreader.model.BookshelfSource
import com.example.hyperreader.ui.cover.CoverImage
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
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
    var activeEntry by remember { mutableStateOf<BookshelfEntry?>(null) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(text = if (showHistory) "返回书架" else "导出记录", onClick = { viewModel.setShowJobHistory(!showHistory) })
        }
        if (showHistory) {
            Text("导出记录", fontSize = 25.sp, fontWeight = FontWeight.Bold)
            if (state.jobs.isEmpty()) Text("还没有导出任务。", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 14.sp)
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.jobs, key = { it.id }) { job ->
                    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
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
                        BookshelfCard(entry) { activeEntry = it }
                    }
                }
                VerticalScrollBar(
                    rememberScrollBarAdapter(listState),
                    Modifier.align(Alignment.TopEnd).fillMaxHeight(),
                )
            }
        }
    }

    activeEntry?.let { entry ->
        val context = LocalContext.current
        BookActionsDialog(
            entry = entry,
            onDismiss = { activeEntry = null },
            onRead = {
                viewModel.markShelfRead(entry.id)
                onOpenLocal(entry)
                activeEntry = null
            },
            onReadOnline = {
                context.startActivity(
                    com.example.hyperreader.reader.onlineReaderIntent(
                        context = context,
                        bookId = entry.bookId,
                        title = entry.title,
                        author = entry.author,
                        bookshelfId = entry.id,
                    ),
                )
                viewModel.markShelfRead(entry.id)
                activeEntry = null
            },
            onOpenRemote = {
                onOpenRemote(entry)
                activeEntry = null
            },
            onTogglePin = {
                viewModel.setPinned(entry.id, !entry.isPinned)
                activeEntry = null
            },
            onExpandAuthor = {
                viewModel.expandAuthor(entry.bookId)
                activeEntry = null
            },
            onRemove = {
                viewModel.removeFromShelf(entry.id)
                activeEntry = null
            },
        )
    }
}

@Composable
private fun BookshelfCard(
    entry: BookshelfEntry,
    onOpen: (BookshelfEntry) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(entry) },
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
            }
        }
    }
}

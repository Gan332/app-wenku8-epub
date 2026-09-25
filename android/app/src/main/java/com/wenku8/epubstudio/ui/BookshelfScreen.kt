package com.wenku8.epubstudio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wenku8.epubstudio.model.BookshelfEntry
import com.wenku8.epubstudio.model.BookshelfSource
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BookshelfScreen(
    state: StudioUiState,
    viewModel: StudioViewModel,
    onImportEpub: () -> Unit,
    onOpenLocal: (BookshelfEntry) -> Unit,
    onOpenRemote: (BookshelfEntry) -> Unit,
) {
    var showHistory by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(text = if (showHistory) "返回书架" else "导出记录", onClick = { showHistory = !showHistory })
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
        if (state.bookshelf.isEmpty()) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                Text("书架还是空的。\n可以从“探索”加入 Wenku8 书籍，或导入本地 EPUB。", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .8f))
            }
        } else {
            Text("共 ${state.bookshelf.size} 本", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 13.sp)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.bookshelf, key = { it.id }) { entry ->
                    BookshelfCard(entry, viewModel::markShelfRead, viewModel::setPinned, viewModel::removeFromShelf, onOpenLocal, onOpenRemote)
                }
            }
        }
    }
}

@Composable
private fun BookshelfCard(
    entry: BookshelfEntry,
    onRead: (String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onOpenLocal: (BookshelfEntry) -> Unit,
    onOpenRemote: (BookshelfEntry) -> Unit,
) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text("${entry.author} · ${if (entry.source == BookshelfSource.LOCAL_EPUB) "本地 EPUB" else "Wenku8"}", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
            if (entry.wordCount != null) Text("${entry.wordCount} 字", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (entry.source == BookshelfSource.LOCAL_EPUB) {
                    TextButton(text = "继续阅读", onClick = { onRead(entry.id); entry.localUri?.let { onOpenLocal(entry) } })
                } else {
                    TextButton(text = "打开详情", onClick = { onOpenRemote(entry) })
                }
                TextButton(text = if (entry.isPinned) "取消置顶" else "置顶", onClick = { onPin(entry.id, !entry.isPinned) })
                TextButton(text = "移除", onClick = { onRemove(entry.id) })
            }
        }
    }
}

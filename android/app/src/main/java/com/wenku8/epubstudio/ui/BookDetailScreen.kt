package com.wenku8.epubstudio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wenku8.epubstudio.model.Book
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BookDetailScreen(book: Book, chapterCount: Int, viewModel: StudioViewModel) {
    LazyColumn(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TextButton(text = "‹ 更换书籍", onClick = viewModel::backToSource)
        }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(book.title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("作者：${book.author}", fontSize = 15.sp)
                    val details = buildList {
                        add("分类：${book.category}")
                        add("状态：${book.status.ifBlank { if (book.isComplete) "完结" else "未知" }}")
                        if (book.updatedAt.isNotBlank()) add("更新：${book.updatedAt}")
                        if (book.wordCount != null) add("字数：${book.wordCount}")
                        add("章节：$chapterCount")
                    }
                    Text(details.joinToString(" · "), color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 13.sp)
                    if (book.tags.isNotEmpty()) Text("标签：${book.tags.joinToString("、")}", fontSize = 13.sp)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("内容简介", fontWeight = FontWeight.Bold)
                    Text(book.summary.ifBlank { "源站没有提供简介。" }, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .8f), fontSize = 14.sp)
                }
            }
        }
        item {
            Button(onClick = viewModel::toChapters, enabled = chapterCount > 0, modifier = Modifier.fillMaxWidth()) { Text("选择章节并导出") }
        }
        item {
            TextButton(text = "加入书架", onClick = { viewModel.addToShelf(book, chapterCount) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

package com.example.hyperreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hyperreader.model.Book
import com.example.hyperreader.ui.cover.CoverImage
import com.example.hyperreader.ui.cover.CoverViewerDialog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 详情页元数据行最小高度，符合 48dp 触摸目标约定。 */
private val STAT_ROW_HEIGHT = 48.dp

@Composable
fun BookDetailScreen(
    book: Book,
    chapterCount: Int,
    viewModel: StudioViewModel,
    loading: Boolean = false,
    loadError: String? = null,
    onTagClick: (String) -> Unit = {},
) {
    var showCover by remember { mutableStateOf(false) }
    val status = book.status.ifBlank { if (book.isComplete) "完结" else "未知" }

    // 全部内容交给外层 LazyColumn，避免长简介被内部容器裁切
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "back") {
            TextButton(text = "‹ 更换书籍", onClick = viewModel::backToSource)
        }

        item(key = "cover") {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(160.dp)
                        .aspectRatio(0.75f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = book.coverUrl != null) { showCover = true },
                ) {
                    CoverImage(
                        url = book.coverUrl,
                        contentDescription = book.title,
                        modifier = Modifier.fillMaxWidth().aspectRatio(0.75f),
                        targetWidthDp = 480,
                    )
                }
            }
        }

        item(key = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(book.title, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("作者：${book.author}", fontSize = 15.sp)
                if (book.category.isNotBlank()) {
                    Text(book.category, color = MiuixTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        item(key = "stats") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(vertical = 2.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DetailStatRow("状态", status)
                    book.wordCount?.let { DetailStatRow("全文字数", "${formatWordCount(it)} 字") }
                    if (book.updatedAt.isNotBlank()) DetailStatRow("最后更新", book.updatedAt)
                    DetailStatRow("章节数", "$chapterCount")
                    DetailStatRow("来源", "Wenku8")
                }
            }
        }

        if (book.tags.isNotEmpty()) {
            item(key = "tags") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("标签", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(book.tags) { tag -> TextButton(text = tag, onClick = { onTagClick(tag) }) }
                    }
                }
            }
        }

        item(key = "summary") {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(vertical = 2.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("内容简介", fontWeight = FontWeight.Bold)
                    Text(
                        text = book.summary.ifBlank { "源站没有提供简介。" },
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                    )
                }
            }
        }

        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    loadError != null -> {
                        Text("目录加载失败：$loadError", fontSize = 13.sp, color = MiuixTheme.colorScheme.error)
                        TextButton(text = "重试", onClick = viewModel::retryLoadIndex, modifier = Modifier.fillMaxWidth().heightIn(min = STAT_ROW_HEIGHT))
                    }
                    loading -> Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = STAT_ROW_HEIGHT),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(size = 22.dp)
                        Text("正在获取章节目录…", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .75f))
                    }
                    else -> {
                        Button(
                            onClick = viewModel::toChapters,
                            enabled = chapterCount > 0,
                            modifier = Modifier.fillMaxWidth().heightIn(min = STAT_ROW_HEIGHT),
                        ) { Text("选择章节并导出") }
                        val onShelf = viewModel.state.value.bookshelf.any { it.bookId == (book.id ?: "") }
                        TextButton(
                            text = if (onShelf) "已在书架" else "加入书架",
                            onClick = { if (!onShelf) viewModel.addToShelf(book, chapterCount) },
                            enabled = !onShelf,
                            modifier = Modifier.fillMaxWidth().heightIn(min = STAT_ROW_HEIGHT),
                        )
                    }
                }
            }
        }
    }

    if (showCover && book.coverUrl != null) {
        CoverViewerDialog(url = book.coverUrl, title = book.title, onDismiss = { showCover = false })
    }
}

/** 独立的元数据行：左侧标签，右侧值。 */
@Composable
private fun DetailStatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = STAT_ROW_HEIGHT)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f), fontSize = 14.sp)
        Text(value, modifier = Modifier.weight(1f).padding(start = 12.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
    }
}

/** 千分位格式化：207559 -> 207,559 */
internal fun formatWordCount(value: Long): String {
    val negative = value < 0
    val digits = kotlin.math.abs(value).toString()
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (negative) "-$grouped" else grouped
}

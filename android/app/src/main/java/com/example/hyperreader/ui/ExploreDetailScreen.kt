package com.example.hyperreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import com.example.hyperreader.core.ExploreBookDetail
import com.example.hyperreader.core.ExploreBookSeed
import com.example.hyperreader.ui.cover.CoverImage
import com.example.hyperreader.ui.cover.CoverViewerDialog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 元数据行最小高度，符合 48dp 触摸目标约定。 */
private val DETAIL_ROW_HEIGHT = 48.dp

/**
 * 探索页书籍详情：**全屏独立页面**，与创建导出流程彻底分开。
 *
 * 数据来自 [ExploreBookDetail]（只调公开 API，见 `ExploreDetailRepository`）。
 * 页面自己不发起任何请求：加载/重试都走 [StudioViewModel]。
 *
 * @param detail 接口返回的完整数据；为 null 时用 [seed] 先渲染标题/作者等已知字段。
 * @param seed 进入页面时已有的信息（点击来源的榜单/索引条目）。
 */
@Composable
fun ExploreDetailScreen(
    seed: ExploreBookSeed?,
    detail: ExploreBookDetail?,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAddToShelf: () -> Unit,
    onReadOnline: () -> Unit,
    onSameAuthor: () -> Unit,
    onTagClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val book = detail?.book
    val title = book?.title ?: seed?.title.orEmpty()
    val author = book?.author ?: seed?.author.orEmpty()
    val category = book?.category ?: seed?.category.orEmpty()
    val coverUrl = book?.coverUrl ?: seed?.coverUrl
    val summary = book?.summary.orEmpty()
    val tags = book?.tags.orEmpty()
    val status = (book?.status ?: seed?.status.orEmpty()).ifBlank { if (book?.isComplete == true) "完结" else "" }
    val wordCount = book?.wordCount ?: seed?.wordCount
    val updatedAt = book?.updatedAt ?: seed?.updatedAt.orEmpty()
    val bookId = book?.id ?: seed?.id.orEmpty()

    var showCover by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "back") {
            TextButton(text = "‹ 返回探索", onClick = onBack)
        }

        // 加载中且还没有任何数据：只渲染 loading 卡片，避免闪一屏空卡片。
        // 用条件分支包住后续 item，而不是 `return@LazyColumn` —— 后者的标签
        // 指向 LazyListScope 的非内联 lambda，写起来脆且不利于阅读。
        val emptyLoading = loading && detail == null && seed == null
        if (emptyLoading) {
            item(key = "loading-full") {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = DETAIL_ROW_HEIGHT).padding(top = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(size = 22.dp)
                    Text("正在获取书籍信息…", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .75f))
                }
            }
        }

        if (!emptyLoading) {
            item(key = "cover") {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .width(160.dp)
                            .aspectRatio(0.75f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = coverUrl != null) { showCover = true },
                    ) {
                        CoverImage(
                            url = coverUrl,
                            contentDescription = title,
                            modifier = Modifier.fillMaxWidth().aspectRatio(0.75f),
                            targetWidthDp = 480,
                        )
                    }
                }
            }

            item(key = "title") {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(title.ifBlank { "未命名" }, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    if (author.isNotBlank()) Text("作者：$author", fontSize = 15.sp)
                    if (category.isNotBlank()) {
                        Text(category, color = MiuixTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    // 接口回来后给一个「已是最新」的轻提示；加载中显示进度，不阻塞已渲染的内容
                    if (loading) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(size = 16.dp)
                            Text("正在通过接口刷新详情…", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
                        }
                    }
                }
            }

            // 书籍信息全部来自 articleinfo.php 一个接口
            item(key = "stats") {
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(vertical = 2.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (status.isNotBlank()) DetailRow("状态", status)
                        wordCount?.let { DetailRow("全文字数", "${formatWordCount(it)} 字") }
                        if (updatedAt.isNotBlank()) DetailRow("最后更新", updatedAt)
                        // 章节数来自目录页
                        when {
                            detail != null && detail.chapterCount > 0 -> DetailRow("章节数", "${detail.chapterCount}")
                            loading -> DetailRow("章节数", "获取中…")
                            else -> DetailRow("章节数", "未知")
                        }
                        DetailRow("来源", "Wenku8")
                    }
                }
            }

            // 目录页单独失败：书籍信息仍可用，就地提示 + 重试，不整页报错
            if (error != null) {
                item(key = "error") {
                    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("部分信息加载失败", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(error, fontSize = 13.sp, color = MiuixTheme.colorScheme.error)
                            TextButton(
                                text = "重试",
                                onClick = onRetry,
                                modifier = Modifier.fillMaxWidth().heightIn(min = DETAIL_ROW_HEIGHT),
                            )
                        }
                    }
                }
            }

            if (tags.isNotEmpty()) {
                item(key = "tags") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("标签", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(tags) { tag -> TextButton(text = tag, onClick = { onTagClick(tag) }) }
                        }
                    }
                }
            }

            item(key = "summary") {
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("内容简介", fontWeight = FontWeight.Bold)
                        Text(
                            text = summary.ifBlank { if (loading) "正在获取简介…" else "源站没有提供简介。" },
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            fontSize = 14.sp,
                        )
                    }
                }
            }

            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (detail != null || !loading) {
                        Button(
                            onClick = onReadOnline,
                            enabled = bookId.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = DETAIL_ROW_HEIGHT),
                        ) { Text("在线阅读") }
                        TextButton(
                            text = "加入书架",
                            onClick = onAddToShelf,
                            enabled = detail != null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = DETAIL_ROW_HEIGHT),
                        )
                        TextButton(
                            text = "查看同作者作品",
                            onClick = onSameAuthor,
                            enabled = detail != null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = DETAIL_ROW_HEIGHT),
                        )
                    }
                    Text(
                        "本页面只读取源站公开信息，不经过导出解析流程。",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f),
                    )
                }
            }
        }
    }

    if (showCover && coverUrl != null) {
        CoverViewerDialog(url = coverUrl, title = title, onDismiss = { showCover = false })
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DETAIL_ROW_HEIGHT)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f), fontSize = 14.sp)
        Text(value, modifier = Modifier.weight(1f).padding(start = 12.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
    }
}

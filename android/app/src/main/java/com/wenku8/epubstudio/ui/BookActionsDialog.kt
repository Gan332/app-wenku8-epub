package com.wenku8.epubstudio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.wenku8.epubstudio.model.BookshelfEntry
import com.wenku8.epubstudio.model.BookshelfSource
import com.wenku8.epubstudio.ui.cover.CoverImage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val ACTION_ROW_HEIGHT = 48.dp

/**
 * 书籍操作二级界面。书架卡片点击后进入，不在卡片上平铺按钮。
 * 破坏性操作（移除）置于底部并需二次确认。
 */
@Composable
fun BookActionsDialog(
    entry: BookshelfEntry,
    onDismiss: () -> Unit,
    onRead: () -> Unit,
    /** wenku8 书籍的「在线阅读」。默认空实现，书架侧接上 [OnlineReaderActivity] 即可生效。 */
    onReadOnline: () -> Unit = {},
    onOpenRemote: () -> Unit,
    onTogglePin: () -> Unit,
    onExpandAuthor: () -> Unit,
    onRemove: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var confirmRemove by remember { mutableStateOf(false) }

    OverlayDialog(
        show = true,
        title = "书籍操作",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    CoverImage(
                        url = entry.coverUrl,
                        contentDescription = entry.title,
                        modifier = Modifier.size(56.dp, 76.dp).clip(RoundedCornerShape(6.dp)),
                        targetWidthDp = 168,
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(entry.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                        Text(entry.author, fontSize = 13.sp, maxLines = 1)
                        Text(
                            if (entry.source == BookshelfSource.LOCAL_EPUB) "本地 EPUB" else "Wenku8",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f),
                        )
                    }
                }
            }

            if (entry.source == BookshelfSource.LOCAL_EPUB) {
                ActionRow("继续阅读", onRead)
            } else {
                ActionRow("在线阅读", onReadOnline)
                ActionRow("查看目录并导出 EPUB", onOpenRemote)
                Text(
                    "在线阅读的正文需要联网；已读过的章节会缓存，断网也能翻看。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f),
                )
            }
            ActionRow(if (entry.isPinned) "取消置顶" else "置顶到书架顶部", onTogglePin)
            if (entry.source == BookshelfSource.WENKU8) {
                ActionRow("展开同作者作品", onExpandAuthor)
            }

            if (confirmRemove) {
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("确定从书架移除？", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("不会删除本地 EPUB 文件。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .7f))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(text = "取消", onClick = { confirmRemove = false }, modifier = Modifier.weight(1f))
                            TextButton(
                                text = "确认移除",
                                onClick = { confirmRemove = false; onRemove() },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            } else {
                ActionRow("从书架移除", onClick = { confirmRemove = true }, destructive = true)
            }

            TextButton(text = "关闭", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit, destructive: Boolean = false) {
    Text(
        text = label,
        fontSize = 15.sp,
        color = if (destructive) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ACTION_ROW_HEIGHT)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

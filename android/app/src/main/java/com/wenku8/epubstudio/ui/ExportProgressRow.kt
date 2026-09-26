package com.wenku8.epubstudio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wenku8.epubstudio.model.ExportJob
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 书架首页顶部的「进行中导出」区。
 *
 * 没有进行中的任务时不渲染任何节点，因此不会占据任何空间。
 * 任务结束后 [jobs] 里的对应条目状态变化，卡片会自动消失。
 */
@Composable
fun ActiveExportSection(
    jobs: List<ExportJob>,
    onOpen: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    val active = activeExportJobs(jobs)
    if (active.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "正在导出",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.primary,
        )
        active.forEach { job ->
            ExportProgressRow(job = job, onOpen = onOpen, onCancel = onCancel)
        }
    }
}

/** 单个进行中任务的进度卡片。 */
@Composable
fun ExportProgressRow(
    job: ExportJob,
    onOpen: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    val progress = job.progress
    val percent = progress.percent.coerceIn(0, 100)
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(job.id) },
        insideMargin = PaddingValues(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    job.book.title,
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                )
                Text(
                    "$percent%",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
            LinearProgressIndicator(
                progress = percent / 100f,
                modifier = Modifier.fillMaxWidth(),
            )
            if (progress.message.isNotBlank()) {
                Text(
                    progress.message,
                    fontSize = 12.sp,
                    maxLines = 2,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildString {
                        append("章节 ")
                        append(progress.completed)
                        append("/")
                        append(if (progress.total > 0) progress.total else job.chapterCount)
                        append(" · 插图 ")
                        append(progress.imageCompleted)
                        append(" 张")
                    },
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                TextButton(text = "取消", onClick = { onCancel(job.id) })
            }
            if (progress.currentTitle.isNotBlank()) {
                Text(
                    "当前：${progress.currentTitle}",
                    fontSize = 12.sp,
                    maxLines = 1,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

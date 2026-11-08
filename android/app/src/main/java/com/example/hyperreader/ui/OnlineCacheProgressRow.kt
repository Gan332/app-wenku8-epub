package com.example.hyperreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.example.hyperreader.reader.OnlineCacheJob
import com.example.hyperreader.reader.OnlineCacheStatus
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 书架首页的「离线缓存」区：显示进行中 / 刚结束的「缓存全书」任务。
 *
 * 与导出任务分开呈现（AGENTS §4.6：导出记录跟着导出走，离线缓存是阅读能力）。
 * 没有任务时不渲染任何节点，不占空间。
 */
@Composable
fun ActiveOnlineCacheSection(
    jobs: List<OnlineCacheJob>,
    onCancel: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    if (jobs.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS),
    ) {
        Text(
            "离线缓存",
            fontSize = UiDimens.caption,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.primary,
        )
        jobs.forEach { job ->
            OnlineCacheRow(
                job = job,
                onCancel = { onCancel(job.bookId) },
                onDismiss = { onDismiss(job.bookId) },
            )
        }
    }
}

@Composable
private fun OnlineCacheRow(
    job: OnlineCacheJob,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = UiDimens.panelCorner,
        insideMargin = PaddingValues(UiDimens.spaceM),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    job.title,
                    modifier = Modifier.weight(1f),
                    fontSize = UiDimens.bodyStrong,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                )
                Text(
                    if (job.status.active) "${job.percent}%" else statusLabel(job.status),
                    fontSize = UiDimens.caption,
                    fontWeight = FontWeight.Bold,
                    color = if (job.status == OnlineCacheStatus.FAILED) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.primary
                    },
                )
            }
            LinearProgressIndicator(
                progress = if (job.status.active) job.percent / 100f else if (job.status == OnlineCacheStatus.COMPLETED) 1f else job.percent / 100f,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                job.message.ifBlank { "${job.cached} / ${job.total} 章" },
                fontSize = UiDimens.captionSmall,
                maxLines = 2,
                color = secondaryTextColor(),
            )
            TextButton(
                text = if (job.status.active) "取消" else "移除",
                onClick = if (job.status.active) onCancel else onDismiss,
                modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
            )
        }
    }
}

private fun statusLabel(status: OnlineCacheStatus): String = when (status) {
    OnlineCacheStatus.RUNNING -> "缓存中"
    OnlineCacheStatus.COMPLETED -> "已完成"
    OnlineCacheStatus.PARTIAL -> "部分完成"
    OnlineCacheStatus.FAILED -> "失败"
    OnlineCacheStatus.CANCELED -> "已取消"
}

package com.example.hyperreader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.example.hyperreader.model.ReadingStats
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ReadingStatsScreen(
    stats: ReadingStats,
    goalMinutes: Int,
    onGoalChange: (Int) -> Unit,
    onClear: () -> Unit,
) {
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    // 整页可滚：原先 Column 不可滚、内层 LazyColumn 又吃光剩余高度，
    // 「清空阅读统计」按钮被推出屏幕底部不可点（热力图 + 四张统计卡之上）。
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = UiDimens.spaceL, bottom = UiDimens.spaceXL),
        verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
    ) {
        StudioHero(
            kicker = "READING RHYTHM",
            title = "把阅读变成一条可见的轨迹",
            description = "这里记录累计时长、连续天数与每本书的投入。数据只保存在本机。",
        )
        ReadingGoalCard(
            todaySeconds = stats.todaySeconds,
            goalMinutes = goalMinutes,
            onGoalChange = onGoalChange,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceXS)) {
            StatCard("总时长", formatDuration(stats.totalSeconds), Modifier.weight(1f))
            StatCard("今日", formatDuration(stats.todaySeconds), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceXS)) {
            StatCard("连续天数", "${stats.currentStreak} 天", Modifier.weight(1f))
            StatCard("最长连续", "${stats.longestStreak} 天", Modifier.weight(1f))
        }
        StudioPanel("阅读热力图") {
            ReadingHeatmap(
                dailySeconds = stats.dailySeconds,
                onSelectDate = { selectedDate = it },
            )
        }
        // 常驻 AnimatedVisibility 而非 if：点/取消某天时下方内容原地延展，不整屏跳位
        AnimatedVisibility(
            visible = selectedDate != null,
            enter = fadeIn(tween(Motion.duration())) + expandVertically(tween(Motion.duration())),
            exit = fadeOut(tween(Motion.duration())) + shrinkVertically(tween(Motion.duration())),
        ) {
            val day = selectedDate
            if (day != null) {
                val seconds = stats.dailySeconds[day.toString()] ?: 0L
                Card(
                    Modifier.fillMaxWidth(),
                    cornerRadius = UiDimens.panelCorner,
                    insideMargin = PaddingValues(UiDimens.spaceM),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            // ofPattern 没有 (pattern, TextStyle, Locale) 重载，用 withLocale 单独指定。
                            Text(day.format(DateTimeFormatter.ofPattern("M月d日 EEEE").withLocale(java.util.Locale.getDefault())), fontWeight = FontWeight.Bold, fontSize = UiDimens.bodyStrong)
                            Text(formatDuration(seconds), fontSize = UiDimens.caption, color = secondaryTextColor())
                        }
                        if (seconds <= 0L) {
                            Text("这天没有阅读记录", fontSize = UiDimens.captionSmall, color = secondaryTextColor())
                        }
                    }
                }
            }
        }
        Text("按书籍统计", fontSize = UiDimens.section, fontWeight = FontWeight.Bold)
        if (stats.bookSeconds.isEmpty()) {
            EmptyState(title = "还没有阅读记录", description = "打开阅读器后会自动累计时长与连续天数。")
        } else {
            val max = stats.bookSeconds.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
            // 整页已可滚，这里用普通 Column：嵌套 LazyColumn 无法测量高度，
            // 会把外层的「清空阅读统计」按钮顶出屏幕。
            Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS)) {
                stats.bookSeconds.entries.sortedByDescending { it.value }.forEach { entry ->
                    val title = stats.bookTitles[entry.key] ?: entry.key
                    Card(
                        Modifier.fillMaxWidth(),
                        cornerRadius = UiDimens.panelCorner,
                        insideMargin = PaddingValues(UiDimens.spaceM),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXXS)) {
                            Text(title, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(formatDuration(entry.value), color = secondaryTextColor(), fontSize = UiDimens.captionSmall)
                            LinearProgressIndicator(progress = entry.value.toFloat() / max, modifier = Modifier.fillMaxWidth().height(UiDimens.progressThickness))
                        }
                    }
                }
            }
        }
        TextButton(
            text = "清空阅读统计",
            onClick = { confirmClear = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
            colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error),
        )
    }
    if (confirmClear) {
        OverlayDialog(
            show = true,
            title = "清空阅读统计",
            summary = "会删除本机记录的总时长、连续天数与热力图数据，操作不可撤销。",
            onDismissRequest = { confirmClear = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                Button(
                    onClick = { confirmClear = false; onClear() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("确认清空") }
                TextButton(text = "取消", onClick = { confirmClear = false }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ReadingGoalCard(
    todaySeconds: Long,
    goalMinutes: Int,
    onGoalChange: (Int) -> Unit,
) {
    val goalSeconds = goalMinutes * 60L
    val progress = readingGoalProgress(todaySeconds, goalMinutes)
    Card(
        Modifier.fillMaxWidth(),
        cornerRadius = UiDimens.panelCorner,
        insideMargin = PaddingValues(UiDimens.spaceM),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("今日目标", fontWeight = FontWeight.Bold, fontSize = UiDimens.bodyStrong)
                Text(
                    if (goalMinutes <= 0) "未设置" else "$goalMinutes 分钟",
                    color = secondaryTextColor(),
                    fontSize = UiDimens.captionSmall,
                )
            }
            if (goalMinutes <= 0) {
                Text("设置一个每日目标，热力图之外也能看到今天的进度。", fontSize = UiDimens.captionSmall, color = secondaryTextColor())
            } else {
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().height(UiDimens.progressThickness),
                )
                Text(
                    if (todaySeconds >= goalSeconds) "已达成今日目标"
                    else "还差 ${formatDuration(goalSeconds - todaySeconds)}",
                    fontSize = UiDimens.captionSmall,
                    color = if (todaySeconds >= goalSeconds) MiuixTheme.colorScheme.primary else secondaryTextColor(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceXS),
            ) {
                listOf(0, 15, 30, 45, 60, 90, 120).forEach { option ->
                    TextButton(
                        text = if (option == 0) {
                            if (goalMinutes == 0) "✓ 关闭" else "关闭"
                        } else {
                            if (goalMinutes == option) "✓ $option 分" else "$option 分"
                        },
                        onClick = { onGoalChange(option) },
                        modifier = Modifier.heightIn(min = UiDimens.touchMin),
                    )
                }
            }
        }
    }
}

/**
 * 今日目标完成度（0..1）。目标 <= 0 视为未设置，恒为 0；超额完成封顶 1。
 * 纯函数便于单测。
 */
internal fun readingGoalProgress(todaySeconds: Long, goalMinutes: Int): Float {
    if (goalMinutes <= 0) return 0f
    val goalSeconds = goalMinutes * 60L
    if (goalSeconds <= 0L) return 0f
    return (todaySeconds.coerceAtLeast(0L).toFloat() / goalSeconds.toFloat()).coerceIn(0f, 1f)
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Card(
        modifier,
        cornerRadius = UiDimens.panelCorner,
        insideMargin = PaddingValues(UiDimens.spaceM),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXXS)) {
            Text(label, color = secondaryTextColor(), fontSize = UiDimens.captionSmall)
            Text(value, fontSize = UiDimens.section, fontWeight = FontWeight.Bold)
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}小时${minutes}分"
        minutes > 0 -> "${minutes}分钟"
        else -> "${seconds}秒"
    }
}

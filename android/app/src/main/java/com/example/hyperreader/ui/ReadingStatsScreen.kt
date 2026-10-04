package com.example.hyperreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.example.hyperreader.model.ReadingStats
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ReadingStatsScreen(stats: ReadingStats, onClear: () -> Unit) {
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    Column(Modifier.fillMaxWidth().padding(top = UiDimens.spaceL), verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
        Text("阅读统计", fontSize = UiDimens.display, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("总时长", formatDuration(stats.totalSeconds), Modifier.weight(1f))
            StatCard("今日", formatDuration(stats.todaySeconds), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("连续天数", "${stats.currentStreak} 天", Modifier.weight(1f))
            StatCard("最长连续", "${stats.longestStreak} 天", Modifier.weight(1f))
        }
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("阅读热力图", fontWeight = FontWeight.Bold)
                ReadingHeatmap(
                    dailySeconds = stats.dailySeconds,
                    onSelectDate = { selectedDate = it },
                )
            }
        }
        // 点选某天后展示当天明细；未点选时给出引导文案而不是留白。
        val day = selectedDate
        if (day != null) {
            val seconds = stats.dailySeconds[day.toString()] ?: 0L
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
                Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        // ofPattern 没有 (pattern, TextStyle, Locale) 重载，用 withLocale 单独指定。
                        Text(day.format(DateTimeFormatter.ofPattern("M月d日 EEEE").withLocale(java.util.Locale.getDefault())), fontWeight = FontWeight.Bold, fontSize = UiDimens.bodyStrong)
                        Text(formatDuration(seconds), fontSize = UiDimens.caption, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
                    }
                    if (seconds <= 0L) {
                        Text("这天没有阅读记录", fontSize = UiDimens.captionSmall, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
                    }
                }
            }
        }
        Text("按书籍统计", fontSize = UiDimens.section, fontWeight = FontWeight.Bold)
        if (stats.bookSeconds.isEmpty()) {
            Text("还没有阅读记录。打开阅读器后会自动统计。", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = UiDimens.caption)
        } else {
            val max = stats.bookSeconds.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(stats.bookSeconds.entries.sortedByDescending { it.value }) { entry ->
                    val title = stats.bookTitles[entry.key] ?: entry.key
                    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(formatDuration(entry.value), color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = UiDimens.captionSmall)
                            LinearProgressIndicator(progress = entry.value.toFloat() / max, modifier = Modifier.fillMaxWidth().height(8.dp))
                        }
                    }
                }
            }
        }
        TextButton(text = "清空阅读统计", onClick = onClear, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Card(modifier, insideMargin = PaddingValues(UiDimens.cardInset)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = UiDimens.captionSmall)
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

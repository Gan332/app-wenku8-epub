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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hyperreader.model.ReadingStats
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun ReadingStatsScreen(stats: ReadingStats, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("阅读统计", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("总时长", formatDuration(stats.totalSeconds), Modifier.weight(1f))
            StatCard("今日", formatDuration(stats.todaySeconds), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("连续天数", "${stats.currentStreak} 天", Modifier.weight(1f))
            StatCard("最长连续", "${stats.longestStreak} 天", Modifier.weight(1f))
        }
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("最近 7 天", fontWeight = FontWeight.Bold)
                val today = LocalDate.now()
                val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
                val max = days.maxOfOrNull { stats.dailySeconds[it.toString()] ?: 0L }?.coerceAtLeast(1L) ?: 1L
                days.forEach { day ->
                    val value = stats.dailySeconds[day.toString()] ?: 0L
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(day.format(DateTimeFormatter.ofPattern("MM-dd")), fontSize = 12.sp)
                            Text(formatDuration(value), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
                        }
                        LinearProgressIndicator(progress = (value.toFloat() / max).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().height(8.dp))
                    }
                }
            }
        }
        Text("按书籍统计", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (stats.bookSeconds.isEmpty()) {
            Text("还没有阅读记录。打开阅读器后会自动统计。", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 13.sp)
        } else {
            val max = stats.bookSeconds.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(stats.bookSeconds.entries.sortedByDescending { it.value }) { entry ->
                    val title = stats.bookTitles[entry.key] ?: entry.key
                    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(formatDuration(entry.value), color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 12.sp)
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
    Card(modifier, insideMargin = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 12.sp)
            Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold)
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

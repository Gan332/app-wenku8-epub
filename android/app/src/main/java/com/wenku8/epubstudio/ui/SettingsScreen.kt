package com.wenku8.epubstudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wenku8.epubstudio.settings.AppThemeMode
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(viewModel: StudioViewModel, onImportEpub: () -> Unit) {
    val theme by viewModel.appTheme.collectAsStateWithLifecycle(initialValue = com.wenku8.epubstudio.settings.AppThemeSettings())
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("设置", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("主题", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppThemeMode.entries.forEach { mode ->
                TextButton(text = when (mode) { AppThemeMode.SYSTEM -> "系统"; AppThemeMode.LIGHT -> "浅色"; AppThemeMode.DARK -> "深色"; AppThemeMode.MONET -> "动态色" }, onClick = { viewModel.setThemeMode(mode) })
            }
        }
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("使用系统动态色", fontWeight = FontWeight.Bold)
                    Text("关闭后使用固定 MiuiX 配色", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
                }
                Switch(checked = theme.useDynamicColor, onCheckedChange = viewModel::setDynamicColor)
            }
        }
        Text("强调色", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(0xFFA34B2F.toInt(), 0xFF2D6A4F.toInt(), 0xFF2563EB.toInt(), 0xFF7C3AED.toInt(), 0xFF111827.toInt()).forEach { color ->
                Box(Modifier.size(34.dp).background(Color(color)).clickable { viewModel.setAccentColor(color) })
            }
        }
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(10.dp)) {
            ColorPicker(color = Color(theme.accentColor), onColorChanged = { viewModel.setAccentColor(it.toArgb()) }, modifier = Modifier.fillMaxWidth().height(180.dp))
        }
        Text("阅读器设置在 EPUB 阅读界面中提供：背景、字体、字号、字重、行距和翻页模式。", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 13.sp)
        TextButton(text = "导入 EPUB 文件", onClick = onImportEpub, modifier = Modifier.fillMaxWidth())
        Text("书目缓存", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        val updated = state.catalogUpdatedAt.takeIf { it > 0 }
            ?.let { "上次更新 ${java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}" }
            ?: "尚未更新"
        Text("已缓存 ${state.catalogSize} 本 · $updated", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 13.sp)
        Text("仅使用 wenku8 对匿名访客公开的页面（书籍详情、同作者作品、年度与月度榜单），不携带登录 Cookie。", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 12.sp)
        val progress = state.catalogProgress
        if (state.catalogLoading) {
            Text("正在抓取 ${progress?.first ?: 0} / ${progress?.second ?: 0}", color = MiuixTheme.colorScheme.primary, fontSize = 13.sp)
        } else {
            TextButton(text = "更新书目缓存", onClick = viewModel::updateCatalog, modifier = Modifier.fillMaxWidth())
        }
    }
}

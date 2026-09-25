package com.wenku8.epubstudio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wenku8.epubstudio.settings.AppThemeMode
import com.wenku8.epubstudio.settings.AppThemeSettings
import com.wenku8.epubstudio.settings.ReaderBackground
import com.wenku8.epubstudio.settings.ReaderPageTurnMode
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val ROW_HEIGHT = 48.dp

private data class SettingsEntry(
    val section: SettingsSection,
    val title: String,
    val summary: String,
)

private val SETTINGS_ENTRIES = listOf(
    SettingsEntry(SettingsSection.APPEARANCE, "主题与外观", "配色模式、动态色与强调色"),
    SettingsEntry(SettingsSection.READER, "阅读器设置", "背景、字体、字号、行距与翻页"),
    SettingsEntry(SettingsSection.STATISTICS, "阅读统计", "阅读时长、连续天数与每本书排行"),
    SettingsEntry(SettingsSection.CATALOG, "书目缓存", "搜索与探索使用的本地书目索引"),
    SettingsEntry(SettingsSection.ABOUT, "关于", "版本、数据来源与使用边界"),
)

/**
 * 设置根页面只列分类入口，具体控件放在二级页面。
 * 阅读统计从底部导航迁入此处。
 */
@Composable
fun SettingsScreen(
    viewModel: StudioViewModel,
    onImportEpub: () -> Unit,
    onImportFont: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.settingsSection != SettingsSection.OVERVIEW) {
        BackHandler { viewModel.backSettings() }
    }

    when (state.settingsSection) {
        SettingsSection.OVERVIEW -> SettingsOverview(state, viewModel)
        SettingsSection.APPEARANCE -> AppearanceSection(viewModel)
        SettingsSection.READER -> ReaderSection(state, viewModel, onImportFont)
        SettingsSection.STATISTICS -> ReadingStatsScreen(state.readingStats, viewModel::clearReadingStats)
        SettingsSection.CATALOG -> CatalogSection(state, viewModel, onImportEpub)
        SettingsSection.ABOUT -> AboutSection(viewModel)
    }
}

@Composable
private fun SettingsOverview(state: StudioUiState, viewModel: StudioViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("设置", fontSize = 25.sp, fontWeight = FontWeight.Bold) }
        items(SETTINGS_ENTRIES) { entry ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.openSettingsSection(entry.section) },
                insideMargin = PaddingValues(14.dp),
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(entry.summary, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .7f))
                    }
                    Text("›", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .5f), fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun AppearanceSection(viewModel: StudioViewModel) {
    val theme by viewModel.appTheme.collectAsStateWithLifecycle(initialValue = AppThemeSettings())
    SettingsScaffold("主题与外观", viewModel::backSettings, listOf(
        { SectionTitle("配色模式") },
        {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppThemeMode.entries.forEach { mode ->
                    TextButton(
                        text = when (mode) { AppThemeMode.SYSTEM -> "系统"; AppThemeMode.LIGHT -> "浅色"; AppThemeMode.DARK -> "深色"; AppThemeMode.MONET -> "动态色" },
                        onClick = { viewModel.setThemeMode(mode) },
                    )
                }
            }
        },
        {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("使用系统动态色", fontWeight = FontWeight.Bold)
                        Text("关闭后使用固定 MiuiX 配色", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
                    }
                    Switch(checked = theme.useDynamicColor, onCheckedChange = viewModel::setDynamicColor)
                }
            }
        },
        { SectionTitle("强调色") },
        {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(0xFFA34B2F.toInt(), 0xFF2D6A4F.toInt(), 0xFF2563EB.toInt(), 0xFF7C3AED.toInt(), 0xFF111827.toInt()).forEach { color ->
                    Box(Modifier.size(34.dp).background(Color(color)).clickable { viewModel.setAccentColor(color) })
                }
            }
        },
        {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(10.dp)) {
                ColorPicker(
                    color = Color(theme.accentColor),
                    onColorChanged = { viewModel.setAccentColor(it.toArgb()) },
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                )
            }
        },
    ))
}

@Composable
private fun ReaderSection(state: StudioUiState, viewModel: StudioViewModel, onImportFont: () -> Unit) {
    val settings = state.readerSettings
    SettingsScaffold("阅读器设置", viewModel::backSettings, listOf(
        { SectionTitle("背景") },
        {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ReaderBackground.entries.toList()) { background ->
                    TextButton(
                        text = when (background) {
                            ReaderBackground.PAPER -> "米黄"; ReaderBackground.LIGHT -> "白纸"; ReaderBackground.GREEN -> "护眼"
                            ReaderBackground.DARK -> "夜间"; ReaderBackground.OLED -> "OLED"; ReaderBackground.CUSTOM -> "自定义"
                        },
                        onClick = { viewModel.setReaderBackground(background) },
                    )
                }
            }
        },
        { SectionTitle("自定义背景色") },
        { ColorSwatchRow { viewModel.setReaderBackgroundColor(it) } },
        { SectionTitle("文字颜色") },
        { ColorSwatchRow { viewModel.setReaderTextColor(it) } },
        { SectionTitle("字号：${settings.fontSizeSp.toInt()} sp") },
        { Slider(settings.fontSizeSp, { viewModel.setReaderFontSize(it) }, valueRange = 12f..32f, steps = 19) },
        { SectionTitle("字重：${settings.fontWeight}") },
        { Slider(settings.fontWeight.toFloat(), { viewModel.setReaderFontWeight(it.toInt()) }, valueRange = 100f..900f, steps = 7) },
        { SectionTitle("行高：${"%.1f".format(settings.lineHeight)}") },
        { Slider(settings.lineHeight, { viewModel.setReaderLineHeight(it) }, valueRange = 1.2f..2.6f, steps = 13) },
        { SectionTitle("段距：${settings.paragraphSpacingDp} dp") },
        { Slider(settings.paragraphSpacingDp.toFloat(), { viewModel.setReaderSpacing(it.toInt()) }, valueRange = 0f..48f, steps = 47) },
        { SectionTitle("左右边距：${settings.horizontalPaddingDp} dp") },
        { Slider(settings.horizontalPaddingDp.toFloat(), { viewModel.setReaderPadding(it.toInt()) }, valueRange = 0f..48f, steps = 47) },
        { SectionTitle("翻页与显示") },
        {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    text = if (settings.pageTurnMode == ReaderPageTurnMode.HORIZONTAL) "左右章节" else "上下滚动",
                    onClick = {
                        viewModel.setReaderPageMode(
                            if (settings.pageTurnMode == ReaderPageTurnMode.HORIZONTAL) ReaderPageTurnMode.VERTICAL else ReaderPageTurnMode.HORIZONTAL
                        )
                    },
                )
            }
        },
        {
            ToggleRow("保持屏幕常亮", settings.keepScreenOn, viewModel::setReaderKeepScreenOn)
        },
        {
            ToggleRow("沉浸模式", settings.immersiveMode, viewModel::setReaderImmersive)
        },
        { SectionTitle("字体") },
        {
            Text(
                text = settings.fontUri?.let { "当前：${it.substringAfterLast('/')}" } ?: "当前：MiSans（默认）",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f),
            )
        },
        {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(text = "导入字体", onClick = onImportFont, modifier = Modifier.weight(1f))
                TextButton(text = "恢复默认", onClick = viewModel::resetReaderFont, modifier = Modifier.weight(1f))
            }
        },
    ))
}

@Composable
private fun CatalogSection(state: StudioUiState, viewModel: StudioViewModel, onImportEpub: () -> Unit) {
    val updated = state.catalogUpdatedAt.takeIf { it > 0 }
        ?.let { "上次更新 ${java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}" }
        ?: "尚未更新"
    SettingsScaffold("书目缓存", viewModel::backSettings, listOf(
        {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("已缓存 ${state.catalogSize} 本", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(updated, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
                    Text("搜索与探索均基于本地索引，断网可用。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f))
                }
            }
        },
        { SectionTitle("维护") },
        {
            val progress = state.catalogProgress
            if (state.catalogLoading) {
                Text("正在抓取 ${progress?.first ?: 0} / ${progress?.second ?: 0}", color = MiuixTheme.colorScheme.primary, fontSize = 14.sp)
            } else {
                TextButton(text = "更新书目缓存", onClick = viewModel::updateCatalog, modifier = Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT))
            }
        },
        {
            TextButton(text = "清空本地书目", onClick = viewModel::clearCatalog, modifier = Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT))
        },
        { SectionTitle("数据来源") },
        {
            Text(
                "仅访问 wenku8 对匿名访客公开的页面：书籍详情、同作者作品、年度精选与月度新书榜。抓取不携带登录 Cookie，并遵守 1 秒/请求限流与 429 退避。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .7f),
            )
        },
        { SectionTitle("文件") },
        { TextButton(text = "导入 EPUB 文件", onClick = onImportEpub, modifier = Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT)) },
    ))
}

@Composable
private fun AboutSection(viewModel: StudioViewModel) {
    SettingsScaffold("关于", viewModel::backSettings, listOf(
        {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("文库 EPUB 工坊", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("版本 0.7.0", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
                    Text("Kotlin + Jetpack Compose + MiuiX", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
                }
            }
        },
        { SectionTitle("使用边界") },
        {
            Text(
                "search.php、articlelist.php、toplist.php、tags.php 由 wenku8 控制登录，应用不做任何规避。登录为可选补充手段，不是使用前提。\n\n仅支持标准无 DRM EPUB，不支持加密 EPUB。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .7f),
            )
        },
        { SectionTitle("参考项目") },
        {
            Text(
                "数据源与书架思路参考 dmzz-yyhyy/LightNovelReader；Wenku8 页面组织参考 MewX/light-novel-library_Wenku8_Android。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .7f),
            )
        },
    ))
}

@Composable
private fun SettingsScaffold(title: String, onBack: () -> Unit, blocks: List<@Composable () -> Unit>) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(text = "‹ 返回设置", onClick = onBack)
        Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(blocks.size) { index -> item(key = "block-$index") { blocks[index]() } }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), fontSize = 15.sp)
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun ColorSwatchRow(onPicked: (Int) -> Unit) {
    val colors = listOf(0xFFF4EFE6.toInt(), 0xFFFFFFFF.toInt(), 0xFFE7F0DF.toInt(), 0xFF17191C.toInt(), 0xFFB3261E.toInt(), 0xFF2D6A4F.toInt())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        colors.forEach { color ->
            Box(
                Modifier.size(40.dp).background(Color(color)).clickable { onPicked(color) }
            )
        }
    }
}

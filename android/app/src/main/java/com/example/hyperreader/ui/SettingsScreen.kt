package com.example.hyperreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hyperreader.core.Wenku8Endpoint
import com.example.hyperreader.settings.AppThemeMode
import com.example.hyperreader.settings.AppThemeSettings
import com.example.hyperreader.settings.ReaderBackground
import com.example.hyperreader.settings.ReaderPageTurnMode
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme


/**
 * 设置分类（0.18.0 对齐 Kazumi 的 `_SettingsCategory` / `_SettingsGroup` 信息架构）。
 *
 * 每个分类对应一个二级页；分组只是索引页上的标题分组。
 */
private enum class SettingsCategory(val title: String, val summary: String, val section: SettingsSection) {
    ACCOUNT("账号", "登录状态、会话与退出", SettingsSection.ACCOUNT),
    READING("阅读", "背景、字体、字号、行距与翻页", SettingsSection.READER),
    APPEARANCE("外观", "配色模式、动态色与强调色", SettingsSection.APPEARANCE),
    NETWORK("网络", "第三方中继与公开端点连通性", SettingsSection.NETWORK),
    DATA("数据", "书目缓存、阅读统计与配置备份", SettingsSection.CATALOG),
    ABOUT("关于", "版本、数据来源与使用边界", SettingsSection.ABOUT),
}

/** 索引页的分组（Kazumi `_SettingsGroup`）。 */
private val SETTINGS_GROUPS: List<Pair<String, List<SettingsCategory>>> = listOf(
    "账号与网络" to listOf(SettingsCategory.ACCOUNT, SettingsCategory.NETWORK),
    "内容" to listOf(SettingsCategory.READING, SettingsCategory.APPEARANCE),
    "数据" to listOf(SettingsCategory.DATA),
    "应用" to listOf(SettingsCategory.ABOUT),
)

/**
 * 设置根页面只列分类入口，具体控件放在二级页面。
 * 阅读统计从底部导航迁入此处。
 */
@Composable
fun SettingsScreen(
    viewModel: StudioViewModel,
    onLogin: () -> Unit,
    onImportEpub: () -> Unit,
    onImportFont: () -> Unit,
    onExportConfig: () -> Unit,
    onImportConfig: () -> Unit,
    /** 手动打开 Cloudflare 验证窗口（由Activity 侧 launcher 实现）。 */
    onOpenChallenge: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val configState by viewModel.configUi.collectAsStateWithLifecycle()

    if (state.settingsSection != SettingsSection.OVERVIEW) {
        BackHandler { viewModel.backSettings() }
    }

    when (state.settingsSection) {
        SettingsSection.OVERVIEW -> SettingsOverview(state, viewModel)
        SettingsSection.ACCOUNT -> AccountSection(state = state, viewModel = viewModel, onLogin = onLogin)
        SettingsSection.APPEARANCE -> AppearanceSection(viewModel)
        SettingsSection.READER -> ReaderSection(state, viewModel, onImportFont)
        SettingsSection.NETWORK -> NetworkSection(state = state, viewModel = viewModel, onOpenChallenge = onOpenChallenge)
        SettingsSection.STATISTICS -> ReadingStatsScreen(state.readingStats, viewModel::clearReadingStats)
        SettingsSection.CATALOG -> CatalogSection(state, viewModel, onImportEpub)
        SettingsSection.CONFIG -> ConfigSection(
            state = configState,
            onBack = viewModel::backSettings,
            onExportRequest = onExportConfig,
            onImportRequest = onImportConfig,
        )
        SettingsSection.ABOUT -> AboutSection(viewModel)
    }
}

/**
 * 设置索引页（0.18.0 对齐 Kazumi `_SettingsIndexPage`）：分组标题 + 分类卡片。
 *
 * 分类侧栏：宽屏直接渲染左侧 rail（[SettingsRail]），窄屏用「分类」按钮拉出
 * `OverlayBottomSheet`（Kazumi 窄屏抽屉的等价物，MiuiX 组件）。
 */
@Composable
private fun SettingsOverview(state: StudioUiState, viewModel: StudioViewModel) {
    var railOpen by remember { mutableStateOf(false) }
    var engineSheet by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                SettingsRail(
                    current = null,
                    onSelect = { category ->
                        viewModel.openSettingsSection(category.section)
                        railOpen = false
                    },
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = UiDimens.pagePadding, vertical = UiDimens.spaceL),
                verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
            ) {
                item(key = "title") {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("设置", modifier = Modifier.weight(1f), fontSize = UiDimens.display, fontWeight = FontWeight.Bold)
                        if (!wide) {
                            TextButton(text = "分类", onClick = { railOpen = true }, modifier = Modifier.heightIn(min = UiDimens.touchMin))
                        }
                    }
                }
                SETTINGS_GROUPS.forEach { (groupTitle, categories) ->
                    item(key = "group-$groupTitle") {
                        Text(
                            groupTitle,
                            modifier = Modifier.padding(top = UiDimens.spaceS),
                            fontSize = UiDimens.caption,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f),
                        )
                    }
                    items(categories, key = { it.name }) { category ->
                        CategoryCard(
                            title = category.title,
                            summary = category.summary,
                            onClick = { viewModel.openSettingsSection(category.section) },
                        )
                    }
                }
                // —— EPUB 导出引擎：轻量编辑走Sheet（Kazumi 的 proxy / danmaku 编辑器等价物）——
                item(key = "engine") {
                    val engine by viewModel.exportEngine.collectAsStateWithLifecycle(
                        initialValue = com.example.hyperreader.settings.EpubEngine.CLASSIC,
                    )
                    CategoryCard(
                        title = "EPUB 导出引擎",
                        summary = "${engine.label} · ${engine.summary}",
                        onClick = { engineSheet = true },
                    )
                }
                // —— 导出记录（0.17.0）——
                item(key = "job-history") {
                    CategoryCard(
                        title = "导出记录",
                        summary = "历史导出任务，可保存或分享已生成的 EPUB。",
                        onClick = { viewModel.setShowJobHistory(true) },
                    )
                }
                // —— 配置导入导出（Kazumi 的 sync 分类入口）——
                item(key = "config") {
                    CategoryCard(
                        title = "配置备份",
                        summary = "主题与阅读器设置的备份与迁移。",
                        onClick = { viewModel.openSettingsSection(SettingsSection.CONFIG) },
                    )
                }
                item(key = "statistics") {
                    CategoryCard(
                        title = "阅读统计",
                        summary = "阅读时长、连续天数与每本书排行。",
                        onClick = { viewModel.openSettingsSection(SettingsSection.STATISTICS) },
                    )
                }
            }
        }
        if (!wide && railOpen) {
            OverlayBottomSheet(show = true, onDismissRequest = { railOpen = false }) {
                SettingsRail(
                    current = null,
                    onSelect = { category ->
                        viewModel.openSettingsSection(category.section)
                        railOpen = false
                    },
                    modifier = Modifier.padding(UiDimens.spaceL),
                )
            }
        }
        if (engineSheet) {
            val engine by viewModel.exportEngine.collectAsStateWithLifecycle(
                initialValue = com.example.hyperreader.settings.EpubEngine.CLASSIC,
            )
            OverlayBottomSheet(show = true, onDismissRequest = { engineSheet = false }) {
                Column(
                    modifier = Modifier.padding(UiDimens.spaceL),
                    verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
                ) {
                    Text("EPUB 导出引擎", fontSize = UiDimens.section, fontWeight = FontWeight.Bold)
                    Text(
                        "只影响之后创建的导出任务；已导出的文件不受影响。",
                        fontSize = UiDimens.captionSmall,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = .7f),
                    )
                    com.example.hyperreader.settings.EpubEngine.entries.forEach { option ->
                        TextButton(
                            text = (if (option == engine) "✓ ${option.label}" else option.label),
                            onClick = { viewModel.setExportEngine(option) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                        )
                    }
                    Text(engine.summary, fontSize = UiDimens.captionSmall, color = MiuixTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** 分类卡片（Kazumi `SettingsCategoryTile`）：标题 + 摘要 + `›`。 */
@Composable
private fun CategoryCard(title: String, summary: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pressableScale(interaction),
        insideMargin = PaddingValues(UiDimens.cardInset),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = UiDimens.section)
                Text(summary, fontSize = UiDimens.captionSmall, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .7f))
            }
            Text("›", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .5f), fontSize = UiDimens.title)
        }
    }
}

/** 分类侧栏（Kazumi `_RailDestination`）：宽屏常驻左侧，窄屏在 Sheet 里。 */
@Composable
private fun SettingsRail(current: SettingsCategory?, onSelect: (SettingsCategory) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.width(RAIL_WIDTH).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS),
    ) {
        SETTINGS_GROUPS.forEach { (_, categories) ->
            categories.forEach { category ->
                val selected = category == current
                TextButton(
                    text = if (selected) "✓ ${category.title}" else category.title,
                    onClick = { onSelect(category) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private val RAIL_WIDTH = 112.dp

/**
 * 账号分区（0.18.0）：登录状态、登录与退出。
 *
 * 不保存账号密码——登录走 WebView 的 wenku8 官方登录页，会话 Cookie 由 Keystore 加密存放（AGENTS §4.2）。
 */
@Composable
private fun AccountSection(state: StudioUiState, viewModel: StudioViewModel, onLogin: () -> Unit) {
    var confirmLogout by remember { mutableStateOf(false) }
    SettingsScaffold("账号", viewModel::backSettings, listOf(
        {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
                Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS), modifier = Modifier.padding(UiDimens.cardInset)) {
                    Text("wenku8 账号", fontWeight = FontWeight.Bold, fontSize = UiDimens.section)
                    FieldCard(
                        label = "状态",
                        value = if (state.loggedIn) "已登录（使用你自己的会话）" else "未登录",
                    )
                    if (!state.loggedIn) {
                        TextButton(
                            text = "登录 wenku8",
                            onClick = onLogin,
                            modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                        )
                    } else {
                        TextButton(
                            text = "退出登录",
                            onClick = { confirmLogout = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                        )
                    }
                    Text(
                        "登录只在官方登录页进行，应用不保存你的账号密码；会话失效时搜索与标签会自动退回本地内容。",
                        fontSize = UiDimens.captionSmall,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = .7f),
                    )
                }
            }
        },
    ))
    if (confirmLogout) {
        OverlayDialog(
            show = true,
            title = "退出登录",
            summary = "将清除本机保存的会话 Cookie（不影响服务器账号）。",
            onDismissRequest = { confirmLogout = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                Button(
                    onClick = { confirmLogout = false; viewModel.logout() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("确认退出") }
                TextButton(text = "取消", onClick = { confirmLogout = false }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** 网络分区（0.18.0）：第三方中继设置 + 公开端点连通性测试。 */
@Composable
private fun NetworkSection(state: StudioUiState, viewModel: StudioViewModel, onOpenChallenge: (String) -> Unit) {
    SettingsScaffold("网络", viewModel::backSettings, listOf(
        { RelaySettings(viewModel) },
        {
            // Cloudflare 验证状态：有没有浏览器验证凭证（cf_clearance）
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
                Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS), modifier = Modifier.padding(UiDimens.cardInset)) {
                    Text("浏览器验证", fontWeight = FontWeight.Bold, fontSize = UiDimens.section)
                    FieldCard(
                        label = "验证状态",
                        value = if (state.clearancePresent) "已有验证凭证" else "尚无（公开页可能被拦）",
                    )
                    Text(
                        "Cloudflare 要求浏览器执行验证：WebView 能通过，App 的网络请求不行。" +
                            "用下面按钮在浏览器里验证一次，凭证会被复用；若已有凭证却仍然被拦，请配置中继。",
                        fontSize = UiDimens.captionSmall,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = .7f),
                    )
                    TextButton(
                        text = "打开验证页（浏览器）",
                        onClick = { onOpenChallenge(com.example.hyperreader.core.Wenku8Urls.sugoi(java.time.Year.now().value)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                    )
                    TextButton(
                        text = "刷新验证状态",
                        onClick = viewModel::refreshClearanceState,
                        modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                    )
                }
            }
        },
        {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
                Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS), modifier = Modifier.padding(UiDimens.cardInset)) {
                    Text("连通性测试", fontWeight = FontWeight.Bold, fontSize = UiDimens.section)
                    Text(
                        "用一个匿名公开页（articleinfo.php）测试当前公开端点；测试请求不携带会话 Cookie。",
                        fontSize = UiDimens.captionSmall,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = .7f),
                    )
                    Text(
                        "若公开页被 Cloudflare 拦截（提示“要求浏览器验证”），可在这里打开验证窗口，由你亲手完成官方验证；通过后窗口自动关闭，公开页即可恢复。",
                        fontSize = UiDimens.captionSmall,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = .7f),
                    )
                    TextButton(
                        text = "打开验证页（浏览器）",
                        onClick = { onOpenChallenge(com.example.hyperreader.core.Wenku8Urls.sugoi(java.time.Year.now().value)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                    )
                    TextButton(
                        text = "测试公开端点",
                        onClick = viewModel::testRelay,
                        modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                    )
                    TextButton(
                        text = "深度诊断公开端点（5 端点 × 2 客户端）",
                        onClick = viewModel::diagnosePublicEndpoints,
                        modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                    )
                    if (state.endpointProbes.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS)) {
                            state.endpointProbes.forEach { probe ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.rowMin),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "${probe.label}·${probe.clientKind}",
                                        modifier = Modifier.weight(1f),
                                        fontSize = UiDimens.caption,
                                    )
                                    Text(
                                        probe.status,
                                        fontSize = UiDimens.captionSmall,
                                        color = if (probe.status.startsWith("OK")) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    ))
}

/**
 * 第三方中继设置（AGENTS §4.11）。
 *
 * 端点必须由用户自己填写：上游从未公开其中继地址，本项目不内置任何猜测值。
 * 开关默认关闭；端点为空或非法时，点开关会被 [StudioViewModel.setRelayEnabled] 拒绝并提示。
 */
@Composable
private fun RelaySettings(viewModel: StudioViewModel) {
    val enabled by viewModel.relayEnabled.collectAsStateWithLifecycle(initialValue = false)
    val storedBase by viewModel.relayBase.collectAsStateWithLifecycle(initialValue = "")
    var editing by remember { mutableStateOf(storedBase) }
    LaunchedEffect(storedBase) { editing = storedBase }
    val normalized = Wenku8Endpoint.normalizeRelayBase(editing)
    val status = when {
        normalized == null -> "端点未配置或无效：公开页当前直连 ${Wenku8Endpoint.DIRECT_BASE}"
        enabled -> "公开页将走 $normalized；会话链路（搜索/榜单/标签/正文）仍直连"
        else -> "端点已保存，开关关闭中；开启后公开页走中继"
    }
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
        Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("第三方中继（仅公开页）", fontWeight = FontWeight.Bold, fontSize = UiDimens.section)
                    Text(
                        "书目索引、公开榜单与封面改走中继；登录与搜索始终直连，不发送任何 Cookie。",
                        fontSize = UiDimens.captionSmall,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = .7f),
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = viewModel::setRelayEnabled,
                )
            }
            TextField(
                value = editing,
                onValueChange = { editing = it },
                label = "中继端点",
                useLabelAsPlaceholder = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                TextButton(text = "保存端点", onClick = { viewModel.setRelayBase(editing) })
                if (editing.isNotBlank()) TextButton(text = "清除", onClick = { editing = ""; viewModel.setRelayBase("") })
            }
            Text(status, fontSize = UiDimens.captionSmall, color = MiuixTheme.colorScheme.primary)
            Text(
                "上游声明：该中继服务由mewx.org 提供，与 wenku8 无关；仅供海外用户使用，" +
                    "可能滞后网站 24 小时以上；请勿在中国大陆使用。本项目与 MewX 无隶属关系，非官方支持。",
                fontSize = UiDimens.captionSmall,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .7f),
            )
        }
    }
}

@Composable
private fun AppearanceSection(viewModel: StudioViewModel) {
    val theme by viewModel.appTheme.collectAsStateWithLifecycle(initialValue = AppThemeSettings())
    SettingsScaffold("主题与外观", viewModel::backSettings, listOf(
        { SectionTitle("配色模式") },
        {
            Row(horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                AppThemeMode.entries.forEach { mode ->
                    TextButton(
                        text = when (mode) { AppThemeMode.SYSTEM -> "系统"; AppThemeMode.LIGHT -> "浅色"; AppThemeMode.DARK -> "深色"; AppThemeMode.MONET -> "动态色" },
                        onClick = { viewModel.setThemeMode(mode) },
                    )
                }
            }
        },
        {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
                Row(Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("使用系统动态色", fontWeight = FontWeight.Bold)
                        Text("关闭后使用固定 MiuiX 配色", fontSize = UiDimens.captionSmall, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
                    }
                    Switch(checked = theme.useDynamicColor, onCheckedChange = viewModel::setDynamicColor)
                }
            }
        },
        { SectionTitle("强调色") },
        {
            Row(horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceM)) {
                listOf(0xFFA34B2F.toInt(), 0xFF2D6A4F.toInt(), 0xFF2563EB.toInt(), 0xFF7C3AED.toInt(), 0xFF111827.toInt()).forEach { color ->
                    Box(Modifier.size(UiDimens.swatch).background(Color(color)).clickable { viewModel.setAccentColor(color) })
                }
            }
        },
        {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.spaceS)) {
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
            LazyRow(horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
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
                fontSize = UiDimens.caption,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f),
            )
        },
        {
            Row(horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
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
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
                Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS)) {
                    Text("已缓存 ${state.catalogSize} 本", fontWeight = FontWeight.Bold, fontSize = UiDimens.section)
                    Text(updated, fontSize = UiDimens.caption, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
                    Text("搜索与探索均基于本地索引，断网可用。", fontSize = UiDimens.captionSmall, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f))
                }
            }
        },
        { SectionTitle("维护") },
        {
            val progress = state.catalogProgress
            if (state.catalogLoading) {
                Text("正在抓取 ${progress?.first ?: 0} / ${progress?.second ?: 0}", color = MiuixTheme.colorScheme.primary, fontSize = UiDimens.body)
            } else {
                TextButton(text = "更新书目缓存", onClick = viewModel::updateCatalog, modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin))
            }
        },
        {
            TextButton(text = "清空本地书目", onClick = viewModel::clearCatalog, modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin))
        },
        { SectionTitle("数据来源") },
        {
            Text(
                "仅访问 wenku8 对匿名访客公开的页面：书籍详情、同作者作品、年度精选与月度新书榜。抓取不携带登录 Cookie，并遵守 1 秒/请求限流与 429 退避。",
                fontSize = UiDimens.captionSmall,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .7f),
            )
        },
        { SectionTitle("文件") },
        { TextButton(text = "导入 EPUB 文件", onClick = onImportEpub, modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin)) },
    ))
}

@Composable
private fun AboutSection(viewModel: StudioViewModel) {
    SettingsScaffold("关于", viewModel::backSettings, listOf(
        {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
                Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS)) {
                    Text("HyperReader", fontWeight = FontWeight.Bold, fontSize = UiDimens.section)
                    Text("版本 0.7.0", fontSize = UiDimens.caption, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
                    Text("Kotlin + Jetpack Compose + MiuiX", fontSize = UiDimens.caption, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
                }
            }
        },
        { SectionTitle("使用边界") },
        {
            Text(
                "search.php、articlelist.php、toplist.php、tags.php 由 wenku8 控制登录，应用不做任何规避。登录为可选补充手段，不是使用前提。\n\n仅支持标准无 DRM EPUB，不支持加密 EPUB。",
                fontSize = UiDimens.captionSmall,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .7f),
            )
        },
        { SectionTitle("参考项目") },
        {
            Text(
                "数据源与书架思路参考 dmzz-yyhyy/LightNovelReader；Wenku8 页面组织参考 MewX/light-novel-library_Wenku8_Android。",
                fontSize = UiDimens.captionSmall,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .7f),
            )
        },
    ))
}

@Composable
private fun SettingsScaffold(title: String, onBack: () -> Unit, blocks: List<@Composable () -> Unit>) {
    // 必须 fillMaxSize + LazyColumn 用 weight(1f)：
    // 只写 fillMaxWidth 时 Column 会把剩余高度给最后一个子项但不做滚动预算，
    // 内容一旦超出就被裁掉（真机上「字重以下全部不可达」）。
    Column(Modifier.fillMaxSize().padding(top = UiDimens.spaceL), verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
        TextButton(text = "‹ 返回设置", onClick = onBack)
        Text(title, fontSize = UiDimens.title, fontWeight = FontWeight.Bold)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = UiDimens.pagePadding, vertical = UiDimens.spaceXXS),
            verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
        ) {
            items(blocks.size) { index -> blocks[index]() }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = UiDimens.section)
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(vertical = UiDimens.spaceXXS)) {
        Row(Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin).padding(horizontal = UiDimens.pagePadding), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), fontSize = UiDimens.bodyStrong)
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun ColorSwatchRow(onPicked: (Int) -> Unit) {
    val colors = listOf(0xFFF4EFE6.toInt(), 0xFFFFFFFF.toInt(), 0xFFE7F0DF.toInt(), 0xFF17191C.toInt(), 0xFFB3261E.toInt(), 0xFF2D6A4F.toInt())
    Row(horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS), modifier = Modifier.fillMaxWidth()) {
        colors.forEach { color ->
            Box(
                Modifier.size(UiDimens.swatch).background(Color(color)).clickable { onPicked(color) }
            )
        }
    }
}

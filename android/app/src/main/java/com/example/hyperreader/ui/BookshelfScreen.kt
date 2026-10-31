package com.example.hyperreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import com.example.hyperreader.model.BookshelfEntry
import com.example.hyperreader.model.BookshelfSource
import com.example.hyperreader.reader.onlineReaderIntent
import com.example.hyperreader.settings.BookshelfSort
import com.example.hyperreader.settings.ReadingProgress
import com.example.hyperreader.ui.cover.CoverImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun BookshelfScreen(
    state: StudioUiState,
    viewModel: StudioViewModel,
    onImportEpub: () -> Unit,
    onOpenLocal: (BookshelfEntry) -> Unit,
    onOpenRemote: (BookshelfEntry) -> Unit,
    /** 远程书的详情：复用探索详情那条公开数据通路（0.17.0 起菜单项「查看详情」）。 */
    onOpenDetail: (BookshelfEntry) -> Unit,
    /**
     * 书架滚动位置由宿主持有（`StudioApp` 按 tab 缓存）。
     * 在本屏内 `remember` 的话，切走 tab 时列表会离开组合、位置被丢弃，
     * 回到书架就总是跳回顶部。
     */
    listState: LazyListState,
) {
    // 「更多」操作菜单对应的条目 id（null = 关闭）。
    // 交互：点卡片主体直接打开书，行尾 More 打开操作 Sheet。
    var menuEntryId by remember { mutableStateOf<String?>(null) }
    // 排序选择 Sheet（0.19.0-alpha03）：编辑型设置用轻量 Sheet，不占二级页（AGENTS §4.12）
    var sortSheet by remember { mutableStateOf(false) }
    // 阅读断点（第 x 章 · 第 y 段）：reader_progress_ 前缀键的全量视图
    val readingProgress by viewModel.readingProgress.collectAsStateWithLifecycle(initialValue = emptyMap())
    // 数据变化时刷新「N 分钟前」的基准时刻，避免长开应用后相对时间停在启动瞬间
    val now = remember(state.bookshelf, readingProgress) { System.currentTimeMillis() }
    // 菜单目标从列表状态反查：sheet 挂在页面层，条目被滚出视口或书架变化时不会悬空。
    val menuEntry = state.bookshelf.firstOrNull { it.id == menuEntryId }
    // 分组按书架内容记忆，放在 LazyColumn **之外**：`LazyListScope` 的 DSL 块不是
    // @Composable 上下文，在里面调 `remember` 会直接编译失败。
    val sections = remember(state.bookshelf) { groupBookshelf(state.bookshelf) }
    // 折叠状态是纯 UI 关注点，不进 ViewModel（AGENTS §4.6.1）。
    var collapsed by remember { mutableStateOf(emptySet<BookshelfGroup>()) }
    // 单滚动容器：页头（标题 / 说明 / 导入 / 导出 / 分割线）与书籍列表放在同一个
    // LazyColumn 里。此前页头在 Column 中常驻、列表是内层 LazyColumn，导致
    // stickyHeader 只能钉在内层列表顶部——分组头会停在页头下方约 200dp 处不滚，
    // 视觉上与页头割裂。合并后分组头真正钉到视口顶，页头也随滚动离开
    // （insets 仍由 Scaffold 在 StudioApp 消费一次，这里只管页内间距）。
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = UiDimens.spaceL, bottom = UiDimens.spaceL),
        verticalArrangement = Arrangement.spacedBy(UiDimens.spaceM),
    ) {
        item(key = "page-actions") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onImportEpub,
                    modifier = Modifier.weight(1f),
                    cornerRadius = UiDimens.panelCorner,
                ) { Text("导入本地 EPUB") }
                // 排序入口只在有书时出现：空书架没有可排的东西
                if (state.bookshelf.isNotEmpty()) {
                    TextButton(
                        text = "排序：${state.bookshelfSort.label}",
                        onClick = { sortSheet = true },
                        modifier = Modifier.heightIn(min = UiDimens.touchMin),
                    )
                }
            }
        }
        if (state.bookshelf.isNotEmpty()) {
            item(key = "bookshelf-summary") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${state.bookshelf.size} 本书",
                        fontSize = UiDimens.title,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        " · 点书即读，详情与导出在更多菜单",
                        modifier = Modifier.weight(1f),
                        fontSize = UiDimens.captionSmall,
                        color = tertiaryTextColor(),
                    )
                }
            }
        }
        item(key = "export-section") {
            ActiveExportSection(
                jobs = state.jobs,
                onOpen = { jobId -> viewModel.route(StudioViewModel.ROUTE_EXPORT_PROGRESS, jobId) },
                onCancel = { jobId -> viewModel.cancel(jobId) },
            )
        }
        // 三态：读 DataStore 中 → 读到了且为空 → 有书。
        // 少了中间那一态，冷启动会先闪一屏「书架还是空的」再被真实列表替换。
        if (!state.bookshelfLoaded) {
            item(key = "bookshelf-skeleton") { BookshelfSkeleton() }
        } else if (state.bookshelf.isEmpty()) {
            item(key = "bookshelf-empty") {
                EmptyState(
                    title = "书架还是空的",
                    description = "可以从「探索」加入 Wenku8 书籍，或导入本地 EPUB。",
                )
            }
        } else {
            sections.forEach { section ->
                stickyHeader(key = "header-${section.group}") {
                    BookshelfGroupHeader(
                        section = section,
                        expanded = section.group !in collapsed,
                        onToggle = { collapsed = if (section.group in collapsed) collapsed - section.group else collapsed + section.group },
                    )
                }
                if (section.group !in collapsed) {
                    // 一个分组是一个 item：分组头以外的卡片共用同一个 LazyColumn 项，
                    // 于是 `Column` 里的 `Modifier.animateItem()` 依然生效（0.19.0 起），
                    // 又不必为每张卡单独建项——后者会让 stickyHeader 与卡片之间
                    // 多出一个可滚动的项边界，分组头会把上一组的最后一张卡盖住。
                    item(key = "section-${section.group}") {
                        Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                            section.entries.forEachIndexed { index, entry ->
                                BookshelfCard(
                                    entry = entry,
                                    resume = readingProgress[entry.bookId],
                                    now = now,
                                    // 交错入场：按分组内索引延迟，超过首屏上限后不再累加（AGENTS §4.4）
                                    modifier = Modifier
                                        // 显式给 spec：不传时 Compose 用默认 spring，
                                        // 与同一次交互里 alpha/scale 的 tween 手感不一致，
                                        // 且不响应系统动画缩放。
                                        .animateItem(
                                            fadeInSpec = tween(Motion.duration()),
                                            placementSpec = tween(Motion.duration()),
                                            fadeOutSpec = tween(Motion.duration(UiDimens.MOTION_FAST)),
                                        )
                                        .staggeredEnter(index),
                                    onMore = { menuEntryId = entry.id },
                                    onOpen = {
                                        // 点卡片主体直接读：本地书进阅读器并记阅读，远程书进在线阅读（AGENTS §4.6.1）
                                        if (entry.localUri != null) {
                                            viewModel.markShelfRead(entry.id)
                                            onOpenLocal(entry)
                                        } else {
                                            onOpenRemote(entry)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    VerticalScrollBar(
        rememberScrollBarAdapter(listState),
        Modifier.fillMaxHeight(),
    )
    // 排序选择：与设置页「EPUB 导出引擎」同一套轻量 Sheet 范式（AGENTS §4.12）
    if (sortSheet) {
        OverlayBottomSheet(show = true, onDismissRequest = { sortSheet = false }) {
            Column(
                modifier = Modifier.padding(UiDimens.spaceL),
                verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
            ) {
                Text("书架排序", fontSize = UiDimens.section, fontWeight = FontWeight.Bold)
                Text(
                    "置顶的书始终排在最前，排序只影响各分组内部。",
                    fontSize = UiDimens.captionSmall,
                    color = secondaryTextColor(),
                )
                BookshelfSort.entries.forEach { option ->
                    TextButton(
                        text = if (option == state.bookshelfSort) "✓ ${option.label}" else option.label,
                        onClick = { viewModel.setBookshelfSort(option) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                    )
                }
                Text(
                    state.bookshelfSort.summary,
                    fontSize = UiDimens.captionSmall,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
    // 书籍操作：与排序 Sheet 同一套轻量 Sheet 范式（AGENTS §4.12）。
    // 此前是锚在卡片行尾的 `Popup`，偏移写死 120dp——换字号/换机型后菜单会错位，
    // 而且它随列表项一起进出组合，滚动时会被卡片裁掉。
    menuEntry?.let { entry ->
        BookEntryMenu(
            entry = entry,
            viewModel = viewModel,
            onOpenLocal = { onOpenLocal(it); menuEntryId = null },
            onOpenRemote = { onOpenRemote(it); menuEntryId = null },
            onOpenDetail = { onOpenDetail(it); menuEntryId = null },
            onDismiss = { menuEntryId = null },
        )
    }
}

@Composable
private fun BookshelfGroupHeader(section: BookshelfSection, expanded: Boolean, onToggle: () -> Unit) {
    // stickyHeader：跟随 LNR BookshelfHomeContent 的分组头，滚动时钉在顶部。
    // 直接给 Column 上背景色，不套 `Surface`：Surface 需要额外的 contentColor 参数，
    // 缺省时会做一次颜色复制并多出一层合成，这里只需要一块不透明底色挡住滚过去的卡片。
    Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.background.copy(alpha = 0.97f))) {
        Row(
            // 不加 horizontal 内边距：宿主（StudioApp 的 Column）已给过 pagePadding，
            // 再加一遍会让分组头比页内标题与卡片多缩进 16dp。
            modifier = Modifier.fillMaxWidth().height(UiDimens.rowMin).clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                section.group.title(section.entries.size),
                fontSize = UiDimens.bodyStrong,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            // 展开 / 收起用两个图标而不是旋转箭头：MiuiX 图标集里没有可旋转的展开箭头，
            // 故自带 expand_more_24px（Material chevron），只靠 rotate() 表达状态。
            val rotation by animateFloatAsState(
                targetValue = if (expanded) 0f else 180f,
                animationSpec = tween(Motion.duration(UiDimens.MOTION_FAST), easing = FastOutSlowInEasing),
                label = "bookshelfGroupArrow",
            )
            Icon(
                imageVector = ImageVector.vectorResource(com.example.hyperreader.R.drawable.expand_more_24px),
                contentDescription = if (expanded) "收起" else "展开",
                tint = tertiaryTextColor(),
                modifier = Modifier.size(UiDimens.spaceL).rotate(rotation),
            )
        }
    }
}

/**
 * 书架卡片：MiuiX 浮层行，封面更紧凑、元信息纯文本化，操作入口固定在行尾。
 *
 * 「点卡片即读、菜单挂行尾」的交互模型不变（AGENTS §4.6.1 第 2 条）。
 */
@Composable
private fun BookshelfCard(
    entry: BookshelfEntry,
    resume: ReadingProgress?,
    now: Long,
    modifier: Modifier = Modifier,
    onMore: () -> Unit,
    onOpen: (BookshelfEntry) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = UiDimens.shelfCardMinHeight)
            .clickable(interactionSource = interaction, indication = null) { onOpen(entry) }
            .pressableScale(interaction),
        cornerRadius = UiDimens.panelCorner,
        insideMargin = PaddingValues(UiDimens.spaceS),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(
                url = entry.coverUrl,
                contentDescription = entry.title,
                modifier = Modifier
                    .size(UiDimens.shelfCoverWidth, UiDimens.shelfCoverHeight)
                    .clip(RoundedCornerShape(UiDimens.cardCorner)),
                targetWidthDp = 222,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = UiDimens.spaceM, end = UiDimens.spaceXS),
                verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS),
            ) {
                Text(
                    entry.title,
                    fontSize = UiDimens.bodyStrong,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // 作者行：W600 + primary（与 LNR 一致），右侧可接连载状态角标。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.author,
                        fontSize = UiDimens.caption,
                        color = MiuixTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.isPinned) {
                        Badge(containerColor = MiuixTheme.colorScheme.primary) {
                            Text("置顶", fontSize = UiDimens.badge)
                        }
                    }
                }
                // 元信息行：LNR 是「图标 + 文字」两组（更新日期 / 字数），本工程对应
                // 来源与规模（章节数 / 字数）。图标底板尺寸与圆角照 LNR `TagChip`。
                Row(
                    horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceXS),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.source.displayLabel(),
                        fontSize = UiDimens.captionSmall,
                        color = tertiaryTextColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("·", color = tertiaryTextColor(), fontSize = UiDimens.captionSmall)
                    Text(
                        buildString {
                            if (entry.chapterCount > 0) append("${entry.chapterCount} 章")
                            entry.wordCount?.let { append(" · ${formatWordCount(it)} 字") }
                        }.ifBlank { "章节未知" },
                        fontSize = UiDimens.captionSmall,
                        color = tertiaryTextColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 末行：LNR 放简介或最新章节；本工程放「上次读到哪儿」，这是差异里
                // 唯一必须保留的本工程语义（AGENTS §4.8 页进度是续读唯一依据）。
                if (entry.lastReadAt > 0L) {
                    Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXXS)) {
                        Text(
                            "上次阅读 ${formatRelativeReadTime(now, entry.lastReadAt)}",
                            fontSize = UiDimens.captionSmall,
                            color = tertiaryTextColor(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        resume?.let { progress ->
                            Text(
                                "第 ${progress.chapterIndex + 1} 章 · 第 ${progress.paragraphIndex + 1} 段",
                                fontSize = UiDimens.captionSmall,
                                color = MiuixTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    Text(
                        "尚未开始阅读",
                        fontSize = UiDimens.captionSmall,
                        color = tertiaryTextColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(UiDimens.touchMin)
                    .clip(RoundedCornerShape(UiDimens.cardCorner))
                    .clickable(onClick = onMore),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    MiuixIcons.More,
                    contentDescription = "更多",
                    tint = tertiaryTextColor(),
                    modifier = Modifier.size(UiDimens.spaceL),
                )
            }
        }
    }
}

/** 书架来源的显示名（「本地 EPUB」/「Wenku8」），用于卡片元信息行。 */
private fun BookshelfSource.displayLabel(): String = when (this) {
    BookshelfSource.LOCAL_EPUB -> "本地 EPUB"
    BookshelfSource.WENKU8 -> "Wenku8"
}

/**
 * 书架条目「更多」操作菜单。
 *
 * 用轻量 `OverlayBottomSheet` 而不是锚在卡片上的 `Popup`：Sheet 由根 Scaffold 的
 * 弹层承载，不受列表项组合与裁剪影响，条目滚出视口也不会留下悬空的菜单
 * （AGENTS §4.12 同款范式：书架排序、EPUB 导出引擎）。
 *
 * 按书籍来源分组：本地 EPUB 只有打开类操作，Wenku8 书多出详情 / 在线 / 导出 / 同作者。
 */
@Composable
private fun BookEntryMenu(
    entry: BookshelfEntry,
    viewModel: StudioViewModel,
    onOpenLocal: (BookshelfEntry) -> Unit,
    onOpenRemote: (BookshelfEntry) -> Unit,
    onOpenDetail: (BookshelfEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    fun act(block: () -> Unit) {
        block()
        onDismiss()
    }
    // 标题交给 Sheet 自己的 title 槽位（有对齐与内边距），正文不再重复一遍书名。
    OverlayBottomSheet(show = true, title = entry.title, onDismissRequest = onDismiss) {
        Column(
            // 上下再留一点：Sheet 的 `insideMargin` 只给左右（24dp）与底部，
            // 顶部贴着手柄会太挤。水平方向不再补，否则左右各 40dp。
            modifier = Modifier.padding(vertical = UiDimens.spaceL),
            verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
        ) {
            Text(
                "${entry.source.displayLabel()} · ${entry.author}",
                fontSize = UiDimens.captionSmall,
                color = secondaryTextColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.localUri != null) {
                TextButton(
                    text = "打开阅读",
                    onClick = { act { viewModel.markShelfRead(entry.id); onOpenLocal(entry) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                )
            } else {
                TextButton(
                    text = "查看详情",
                    onClick = { act { onOpenDetail(entry) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                )
                TextButton(
                    text = "开始阅读",
                    onClick = {
                        act {
                            context.startActivity(
                                onlineReaderIntent(
                                    context = context,
                                    bookId = entry.bookId,
                                    title = entry.title,
                                    author = entry.author,
                                    bookshelfId = entry.id,
                                ),
                            )
                            viewModel.markShelfRead(entry.id)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                )
                // 0.17.0：导出从「创建 tab」下沉为书籍菜单里的次级动作
                TextButton(
                    text = "导出 EPUB",
                    onClick = { act { viewModel.startExportFromShelf(entry) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                )
                TextButton(
                    text = "同作者作品",
                    onClick = { act { viewModel.expandAuthor(entry.bookId) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                )
            }
            TextButton(
                text = if (entry.isPinned) "取消置顶" else "置顶",
                onClick = { act { viewModel.setPinned(entry.id, !entry.isPinned) } },
                modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
            )
            TextButton(
                text = "移出书架",
                onClick = { act { viewModel.removeFromShelf(entry.id) } },
                modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error),
            )
        }
    }
}

/**
 * 书架首屏骨架，1:1 照搬 LNR `BookCardContentSkeleton` 的尺寸构成。
 *
 * LNR 的骨架与真实卡片严格同构：`146dp` 卡高、`4dp` 内边距、`94×144` 封面，
 * 文字栏四条占位依次是 `40dp`（标题 90% 宽）、`20dp`（作者 43% 宽）、
 * `32dp`（简介）——本工程第四行是「上次阅读」，故沿用同高度。
 *
 * 骨架与内容同尺寸是硬要求（AGENTS §4.4）：否则数据到达时整屏跳位。
 */
@Composable
private fun BookshelfSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
        repeat(2) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(UiDimens.shelfCardMinHeight)
                    .padding(UiDimens.spaceS),
            ) {
                ShimmerBlock(
                    Modifier
                        .size(UiDimens.shelfCoverWidth, UiDimens.shelfCoverHeight)
                        .clip(RoundedCornerShape(UiDimens.cardCorner)),
                )
                Column(
                    Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(start = UiDimens.spaceM),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    ShimmerLine(Modifier.fillMaxWidth(0.9f), heightDp = 40)
                    ShimmerLine(Modifier.fillMaxWidth(0.43f), heightDp = 20)
                    ShimmerLine(Modifier.fillMaxWidth(), heightDp = 32)
                    ShimmerLine(Modifier.fillMaxWidth(0.7f), heightDp = 32)
                }
            }
        }
    }
}


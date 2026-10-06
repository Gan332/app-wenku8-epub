package com.example.hyperreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.example.hyperreader.model.BookshelfEntry
import com.example.hyperreader.model.BookshelfSource
import com.example.hyperreader.reader.onlineReaderIntent
import com.example.hyperreader.settings.BookshelfSort
import com.example.hyperreader.settings.ReadingProgress
import com.example.hyperreader.ui.cover.CoverImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.ListView
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
) {
    // 「更多」弹出菜单的锚定条目 id（null = 关闭）。
    // 交互：点卡片主体直接打开书，行尾 More 弹操作菜单（替换旧的全屏操作弹窗）。
    var menuEntryId by remember { mutableStateOf<String?>(null) }
    // 排序选择 Sheet（0.19.0-alpha03）：编辑型设置用轻量 Sheet，不占二级页（AGENTS §4.12）
    var sortSheet by remember { mutableStateOf(false) }
    // 阅读断点（第 x 章 · 第 y 段）：reader_progress_ 前缀键的全量视图
    val readingProgress by viewModel.readingProgress.collectAsStateWithLifecycle(initialValue = emptyMap())
    // 数据变化时刷新「N 分钟前」的基准时刻，避免长开应用后相对时间停在启动瞬间
    val now = remember(state.bookshelf, readingProgress) { System.currentTimeMillis() }
    Column(Modifier.fillMaxWidth().padding(top = UiDimens.spaceL), verticalArrangement = Arrangement.spacedBy(UiDimens.spaceM)) {
        // 0.17.0：导出记录已独立成页（入口在设置 → 概览与通知栏路由），书架只留进行中的任务概览
        Text("书架", fontSize = UiDimens.display, fontWeight = FontWeight.Bold)
        Text("在线书籍和本地 EPUB 都可以在这里继续阅读。", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = UiDimens.caption)
        Row(horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
            Button(onClick = onImportEpub, modifier = Modifier.weight(1f)) { Text("导入 EPUB") }
            // 排序入口只在有书时出现：空书架没有可排的东西
            if (state.bookshelf.isNotEmpty()) {
                TextButton(
                    text = "排序：${state.bookshelfSort.label}",
                    onClick = { sortSheet = true },
                    modifier = Modifier.heightIn(min = UiDimens.touchMin),
                )
            }
        }
        ActiveExportSection(
            jobs = state.jobs,
            onOpen = { jobId -> viewModel.route(StudioViewModel.ROUTE_EXPORT_PROGRESS, jobId) },
            onCancel = { jobId -> viewModel.cancel(jobId) },
        )
        HorizontalDivider(Modifier.fillMaxWidth())
        // 三态：读 DataStore 中 → 读到了且为空 → 有书。
        // 少了中间那一态，冷启动会先闪一屏「书架还是空的」再被真实列表替换。
        if (!state.bookshelfLoaded) {
            BookshelfSkeleton()
        } else if (state.bookshelf.isEmpty()) {
            EmptyState(
                title = "书架还是空的",
                description = "可以从「探索」加入 Wenku8 书籍，或导入本地 EPUB。",
            )
        } else {
            val sections = remember(state.bookshelf) { groupBookshelf(state.bookshelf) }
            // 折叠状态存在这里而非 ViewModel：纯 UI 关注点，不该进业务状态（AGENTS §4.6.1）。
            var collapsed by remember { mutableStateOf(emptySet<BookshelfGroup>()) }
            val listState = rememberLazyListState()
            Box(Modifier.fillMaxWidth()) {
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                    sections.forEach { section ->
                        stickyHeader(key = "header-${section.group}") {
                            BookshelfGroupHeader(
                                section = section,
                                expanded = section.group !in collapsed,
                                onToggle = { collapsed = if (section.group in collapsed) collapsed - section.group else collapsed + section.group },
                            )
                        }
                        if (section.group !in collapsed) {
                            itemsIndexed(section.entries, key = { _, entry -> entry.id }) { index, entry ->
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
                                if (menuEntryId == entry.id) {
                                    // Popup(alignment, offset) 的 offset 是 IntOffset（像素），需按 density 换算
                                    val menuOffset = with(LocalDensity.current) { IntOffset(0, 120.dp.roundToPx()) }
                                    Popup(
                                        onDismissRequest = { menuEntryId = null },
                                        alignment = Alignment.TopEnd,
                                        offset = menuOffset,
                                    ) {
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
                            }
                        }
                    }
                }
                VerticalScrollBar(
                    rememberScrollBarAdapter(listState),
                    Modifier.align(Alignment.TopEnd).fillMaxHeight(),
                )
            }
        }
    }
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
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = .7f),
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
}

@Composable
private fun BookshelfGroupHeader(section: BookshelfSection, expanded: Boolean, onToggle: () -> Unit) {
    // stickyHeader：跟随 LNR BookshelfHomeContent 的分组头，滚动时钉在顶部。
    Surface(color = MiuixTheme.colorScheme.background) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().height(UiDimens.rowMin).clickable(onClick = onToggle).padding(horizontal = UiDimens.pagePadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = when (section.group) {
                        BookshelfGroup.Pinned -> MiuixIcons.Favorites
                        // MiuiX 图标集没有 Bookmark；用 ListView 表示「全部」。
                        BookshelfGroup.All -> MiuixIcons.ListView
                    },
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(UiDimens.spaceL),
                )
                Spacer(Modifier.size(UiDimens.spaceS))
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
                    tint = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f),
                    modifier = Modifier.size(UiDimens.spaceL).rotate(rotation),
                )
            }
            HorizontalDivider(Modifier.fillMaxWidth())
        }
    }
}

/**
 * 书架卡片，尺寸与排版对齐 LNR `BookCardContent`：94×144 封面、146dp 卡高、
 * 作者用强调色、简介两行。断点行保留本工程特有的「读到第 x 章」（AGENTS §4.8）。
 */
/**
 * 书架卡片，1:1 对齐 LNR `BookCardContent` 的排版规格（0.19.0）。
 *
 * 逐项对应上游：
 * - 卡片 `146dp` 高、圆角 `12dp`、内边距 `4dp` → [LnrDimens]
 * - 封面 `94×144dp`、圆角 `8dp`；封面与文字栏之间留 `12dp`
 * - 文字栏用 `Arrangement.SpaceBetween` 把四行**顶到底**（LNR 原样），
 *   本工程此前用 `spacedBy(spaceXS)`，行距被压缩后底部留白，与上游不一致
 * - 作者用 `W600` + `primary` 色；元信息行是「图标 + 文字」两组（`TagChip`），
 *   本工程此前是纯文本 `·` 拼接
 * - 断点行（「读到第 x 章」）是本工程特有，保留在文字栏最后一行（AGENTS §4.8）
 *
 * 「更多」菜单按钮仍在本工程这一侧：LNR 用长按进选择模式，本工程 0.17.0 起
 * 点卡片即读、菜单挂在行尾（AGENTS §4.6.1 第 2 条），两者交互模型不同，此处不迁就上游。
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
            .height(LnrDimens.cardHeight)
            .clickable(interactionSource = interaction, indication = null) { onOpen(entry) }
            .pressableScale(interaction),
        insideMargin = PaddingValues(LnrDimens.cardPadding),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            CoverImage(
                url = entry.coverUrl,
                contentDescription = entry.title,
                modifier = Modifier
                    .size(LnrDimens.coverWidth, LnrDimens.coverHeight)
                    .clip(RoundedCornerShape(LnrDimens.coverCorner)),
                targetWidthDp = 282,
            )
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .padding(start = LnrDimens.textGutter),
                // LNR 用 SpaceBetween 顶到底；换成 Top 会让末行与底边留空。
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    entry.title,
                    fontSize = UiDimens.bodyStrong,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // 作者行：W600 + primary（与 LNR 一致），右侧可接连载状态角标。
                Row(
                    horizontalArrangement = Arrangement.spacedBy(LnrDimens.authorGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.author,
                        fontSize = UiDimens.caption,
                        color = MiuixTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
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
                    horizontalArrangement = Arrangement.spacedBy(LnrDimens.metaGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TagChip(painter = ImageVector.vectorResource(com.example.hyperreader.R.drawable.lnr_toolbar_24px))
                    Text(
                        entry.source.displayLabel(),
                        fontSize = UiDimens.captionSmall,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TagChip(painter = ImageVector.vectorResource(com.example.hyperreader.R.drawable.lnr_read_more_24px))
                    Text(
                        buildString {
                            if (entry.chapterCount > 0) append("${entry.chapterCount} 章")
                            entry.wordCount?.let { append(" · ${formatWordCount(it)} 字") }
                        }.ifBlank { "章节未知" },
                        fontSize = UiDimens.captionSmall,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 末行：LNR 放简介或最新章节；本工程放「上次读到哪儿」，这是差异里
                // 唯一必须保留的本工程语义（AGENTS §4.8 页进度是续读唯一依据）。
                if (entry.lastReadAt > 0L) {
                    Column {
                        Text(
                            "上次阅读：${formatRelativeReadTime(now, entry.lastReadAt)}",
                            fontSize = UiDimens.captionSmall,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        resume?.let {
                            Text(
                                "读到第${it.chapterIndex + 1}章 · 第${it.paragraphIndex + 1}段",
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
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onMore, modifier = Modifier.size(UiDimens.touchMin)) {
                Icon(MiuixIcons.More, contentDescription = "更多")
            }
        }
    }
}

/**
 * 元信息图标底板（1:1 照搬 LNR `TagChip`：`4dp` 圆角 + `4dp/2dp` 内边距 + `15dp` 图标）。
 */
@Composable
private fun TagChip(painter: ImageVector) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(LnrDimens.metaChipCorner))
            .background(MiuixTheme.colorScheme.background.copy(alpha = .55f))
            .padding(horizontal = LnrDimens.metaChipPadding, vertical = UiDimens.spaceXXS),
    ) {
        Icon(
            imageVector = painter,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f),
            modifier = Modifier.size(LnrDimens.metaChipIcon),
        )
    }
}

/** 书架来源的显示名（「本地 EPUB」/「Wenku8」），用于卡片元信息行。 */
private fun BookshelfSource.displayLabel(): String = when (this) {
    BookshelfSource.LOCAL_EPUB -> "本地 EPUB"
    BookshelfSource.WENKU8 -> "Wenku8"
}

/** 触摸目标下限（与阅读器约定一致）。 */

/**
 * 书架条目「更多」操作菜单（Popup 锚定卡片右下，全部 MiuiX 组件）。
 * 按书籍来源分组：本地 EPUB 只有打开类操作，Wenku8 书多出详情/在线/同作者。
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
    Card(insideMargin = PaddingValues(vertical = UiDimens.spaceXXS)) {
        Column(Modifier.width(IntrinsicSize.Min)) {
            if (entry.localUri != null) {
                BasicComponent(title = "打开阅读", onClick = { act { viewModel.markShelfRead(entry.id); onOpenLocal(entry) } })
            } else {
                BasicComponent(title = "查看详情", onClick = { act { onOpenDetail(entry) } })
                BasicComponent(
                    title = "开始阅读",
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
                )
                // 0.17.0：导出从「创建 tab」下沉为书籍菜单里的次级动作
                BasicComponent(title = "导出 EPUB", onClick = { act { viewModel.startExportFromShelf(entry) } })
                BasicComponent(title = "同作者作品", onClick = { act { viewModel.expandAuthor(entry.bookId) } })
            }
            BasicComponent(
                title = if (entry.isPinned) "取消置顶" else "置顶",
                onClick = { act { viewModel.setPinned(entry.id, !entry.isPinned) } },
            )
            BasicComponent(title = "移出书架", onClick = { act { viewModel.removeFromShelf(entry.id) } })
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
                    .height(LnrDimens.cardHeight)
                    .padding(LnrDimens.cardPadding),
            ) {
                ShimmerBlock(
                    Modifier
                        .size(LnrDimens.coverWidth, LnrDimens.coverHeight)
                        .clip(RoundedCornerShape(LnrDimens.coverCorner)),
                )
                Column(
                    Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(start = LnrDimens.textGutter),
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


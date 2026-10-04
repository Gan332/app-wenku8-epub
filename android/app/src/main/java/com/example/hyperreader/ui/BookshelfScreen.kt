package com.example.hyperreader.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.example.hyperreader.model.BookshelfEntry
import com.example.hyperreader.model.BookshelfSource
import com.example.hyperreader.reader.onlineReaderIntent
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
    // 阅读断点（第 x 章 · 第 y 段）：reader_progress_ 前缀键的全量视图
    val readingProgress by viewModel.readingProgress.collectAsStateWithLifecycle(initialValue = emptyMap())
    // 数据变化时刷新「N 分钟前」的基准时刻，避免长开应用后相对时间停在启动瞬间
    val now = remember(state.bookshelf, readingProgress) { System.currentTimeMillis() }
    Column(Modifier.fillMaxWidth().padding(top = UiDimens.spaceL), verticalArrangement = Arrangement.spacedBy(UiDimens.spaceM)) {
        // 0.17.0：导出记录已独立成页（入口在设置 → 概览与通知栏路由），书架只留进行中的任务概览
        Text("书架", fontSize = UiDimens.display, fontWeight = FontWeight.Bold)
        Text("在线书籍和本地 EPUB 都可以在这里继续阅读。", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = UiDimens.caption)
        Button(onClick = onImportEpub, modifier = Modifier.fillMaxWidth()) { Text("导入 EPUB") }
        ActiveExportSection(
            jobs = state.jobs,
            onOpen = { jobId -> viewModel.route(StudioViewModel.ROUTE_EXPORT_PROGRESS, jobId) },
            onCancel = { jobId -> viewModel.cancel(jobId) },
        )
        HorizontalDivider(Modifier.fillMaxWidth())
        if (state.bookshelf.isEmpty()) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
                Text("书架还是空的。\n可以从“探索”加入 Wenku8 书籍，或导入本地 EPUB。", fontSize = UiDimens.body, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .8f))
            }
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
                                    modifier = Modifier.animateItem().staggeredEnter(index),
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
                    imageVector = ImageVector.vectorResource(R.drawable.expand_more_24px),
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
            .clickable(interactionSource = interaction, indication = null) { onOpen(entry) }
            .pressableScale(interaction),
        insideMargin = PaddingValues(UiDimens.cardInset),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            CoverImage(
                url = entry.coverUrl,
                contentDescription = entry.title,
                modifier = Modifier.size(CardCoverWidth, CardCoverHeight).clip(RoundedCornerShape(UiDimens.cardCorner)),
                targetWidthDp = 282,
            )
            Column(
                modifier = Modifier.weight(1f).padding(start = UiDimens.spaceS),
                verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS),
            ) {
                Text(entry.title, fontSize = UiDimens.section, fontWeight = FontWeight.Bold, maxLines = 2)
                // 作者用强调色：与 LNR BookCardContent 一致，也让「谁写的」在长列表里一眼可辨。
                Text(entry.author, fontSize = UiDimens.caption, color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Bold, maxLines = 1)
                val meta = buildList {
                    add(if (entry.source == BookshelfSource.LOCAL_EPUB) "本地 EPUB" else "Wenku8")
                    entry.wordCount?.let { add("${formatWordCount(it)} 字") }
                    if (entry.chapterCount > 0) add("${entry.chapterCount} 章")
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                    Text(meta.joinToString(" · "), fontSize = UiDimens.captionSmall, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f), maxLines = 1)
                    if (entry.isPinned) {
                        Badge(containerColor = MiuixTheme.colorScheme.primary) { Text("置顶", fontSize = UiDimens.badge) }
                    }
                }
                // 上次阅读时间 + 断点：未读过的书（lastReadAt=0）整行不显示
                if (entry.lastReadAt > 0L) {
                    val resumeText = resume?.let { " · 读到 第${it.chapterIndex + 1}章 · 第${it.paragraphIndex + 1}段" }.orEmpty()
                    Text(
                        "上次阅读：${formatRelativeReadTime(now, entry.lastReadAt)}$resumeText",
                        fontSize = UiDimens.captionSmall,
                        color = MiuixTheme.colorScheme.primary.copy(alpha = .9f),
                        maxLines = 1,
                    )
                }
            }
            IconButton(onClick = onMore, modifier = Modifier.size(UiDimens.touchMin)) {
                Icon(MiuixIcons.More, contentDescription = "更多")
            }
        }
    }
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

// —— 卡片尺寸 ——
// 封面比例取 LNR BookCardContent 的 94:144（2:3），高度放大后一行能放下两行简介。
// 属装饰性布局尺寸，与控件最小触控区无关，故不走 UiDimens 令牌（AGENTS §4.4）。
private val CardCoverWidth = 94.dp
private val CardCoverHeight = 144.dp

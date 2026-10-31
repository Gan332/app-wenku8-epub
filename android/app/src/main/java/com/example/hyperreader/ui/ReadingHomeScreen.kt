package com.example.hyperreader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.example.hyperreader.model.BookshelfEntry
import com.example.hyperreader.settings.ReadingProgress
import com.example.hyperreader.ui.cover.CoverImage
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「读了多少」的百分比：按**章节**估算（页进度依赖字号，不能跨设备换算，AGENTS §4.8）。
 *
 * 有进度时至少显示 1%，避免刚读到第 1 章时出现「0%」被误读成没开始读；
 * 章节总数未知时同样给 1%——按「第 1 章 / 1 章」算成 100% 会被误读成已读完。
 */
internal fun readingPercent(progress: ReadingProgress?, chapterCount: Int): Int {
    if (progress == null) return 0
    if (chapterCount <= 0) return 1
    val total = chapterCount.coerceAtLeast(progress.chapterIndex + 1)
    return (((progress.chapterIndex + 1).toFloat() / total) * 100).roundToInt().coerceIn(1, 100)
}

/**
 * 阅读首页。
 *
 * 这一屏不是「书架摘要」，而是读者回到应用后的第一落点：先给一个可继续的阅读入口，
 * 再提供最近书目。因此主卡使用完整的封面 + 大字号书名 + 阅读断点，
 * 其余内容收敛成横向封面轨道；书签中心收在顶栏右槽。
 */
@Composable
fun ReadingHomeScreen(
    bookshelf: List<BookshelfEntry>,
    progress: Map<String, ReadingProgress>,
    bookshelfLoaded: Boolean,
    /** 滚动位置由宿主按 tab 缓存（同书架 / 探索），切走再回来不会跳回顶部。 */
    listState: LazyListState,
    onOpenLocal: (BookshelfEntry) -> Unit,
    onOpenRemote: (BookshelfEntry) -> Unit,
    onOpenBookshelf: () -> Unit,
    onOpenExplore: () -> Unit,
) {
    val now = remember(bookshelf, progress) { System.currentTimeMillis() }
    val withProgress = remember(bookshelf, progress) {
        bookshelf.filter { progress.containsKey(it.bookId) }.sortedByDescending { it.lastReadAt }
    }
    val continueEntry = remember(withProgress) { withProgress.firstOrNull() }
    val recentWithProgress = remember(withProgress) { withProgress.drop(1) }
    val recentlyRead = remember(bookshelf, progress) {
        bookshelf
            .filter { it.lastReadAt > 0L && !progress.containsKey(it.bookId) }
            .sortedByDescending { it.lastReadAt }
    }

    fun open(entry: BookshelfEntry) {
        if (entry.localUri != null) onOpenLocal(entry) else onOpenRemote(entry)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = UiDimens.spaceS, bottom = UiDimens.spaceXL),
            verticalArrangement = Arrangement.spacedBy(UiDimens.spaceL),
        ) {
            if (!bookshelfLoaded) {
                item(key = "loading") {
                    Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceL)) {
                        ReadingHomeSkeleton()
                        ReadingHomeRailSkeleton()
                    }
                }
            } else if (continueEntry == null) {
                item(key = "empty") {
                    StudioHero(
                        kicker = "START HERE",
                        title = "从一本书开始",
                        description = "书架里的书会自动保存阅读位置。读过的内容会出现在这里，下次打开就能接着读。",
                    ) {
                        TextButton(text = "打开书架", onClick = onOpenBookshelf)
                        TextButton(text = "去探索", onClick = onOpenExplore)
                    }
                }
            } else {
                item(key = "continue") {
                    ContinueReadingCard(
                        entry = continueEntry,
                        resume = progress[continueEntry.bookId],
                        now = now,
                        onClick = { open(continueEntry) },
                    )
                }
            }

            if (bookshelfLoaded && recentWithProgress.isNotEmpty()) {
                item(key = "recent-progress") {
                    Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                        SectionHeader("继续上次")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceM)) {
                            items(recentWithProgress, key = { it.id }) { entry ->
                                RecentBookCard(entry = entry, resume = progress[entry.bookId], onClick = { open(entry) })
                            }
                        }
                    }
                }
            }

            if (bookshelfLoaded && recentlyRead.isNotEmpty()) {
                item(key = "recent-read") {
                    Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                        SectionHeader("最近翻过")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceM)) {
                            items(recentlyRead, key = { it.id }) { entry ->
                                RecentBookCard(entry = entry, resume = progress[entry.bookId], onClick = { open(entry) })
                            }
                        }
                    }
                }
            }
        }
        AppScrollBar(listState)
    }
}

/** 冷启动占位：与「继续阅读」主卡保持相近高度，避免空态闪现后整屏跳位。 */
@Composable
private fun ReadingHomeSkeleton() {
    StudioPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceL),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShimmerBlock(
                Modifier
                    .width(UiDimens.heroCoverWidth)
                    .aspectRatio(LnrDimens.POSTER_ASPECT)
                    .clip(RoundedCornerShape(UiDimens.cardCorner)),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
            ) {
                ShimmerLine(Modifier.fillMaxWidth(0.35f), heightDp = 12)
                ShimmerLine(Modifier.fillMaxWidth(0.88f), heightDp = 24)
                ShimmerLine(Modifier.fillMaxWidth(), heightDp = 14)
                ShimmerLine(Modifier.fillMaxWidth(0.7f), heightDp = 8)
            }
        }
    }
}

/** 最近阅读横向轨道的占位：数据返回时不会从一张主卡突然长出一行封面。 */
@Composable
private fun ReadingHomeRailSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
        SectionHeader("最近阅读")
        Row(horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceM)) {
            repeat(3) {
                Column(
                    modifier = Modifier.width(UiDimens.recentCardWidth),
                    verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS),
                ) {
                    ShimmerBlock(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(LnrDimens.POSTER_ASPECT)
                            .clip(RoundedCornerShape(UiDimens.panelCorner)),
                    )
                    ShimmerLine(Modifier.fillMaxWidth(0.88f), heightDp = 14)
                    ShimmerLine(Modifier.fillMaxWidth(0.45f), heightDp = 10)
                }
            }
        }
    }
}

/** 主阅读入口：封面是视觉锚点，右侧只保留读者续读需要的三条信息。 */
@Composable
private fun ContinueReadingCard(
    entry: BookshelfEntry,
    resume: ReadingProgress?,
    now: Long,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(UiDimens.heroCorner)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        colors.primaryContainer.copy(alpha = 0.95f),
                        colors.surfaceContainerHigh,
                    ),
                ),
            )
            .border(BorderStroke(UiDimens.hairline, colors.outline.copy(alpha = 0.16f)), shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pressableScale(interaction)
            .padding(UiDimens.spaceL),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceL),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(UiDimens.heroCoverWidth)
                    .aspectRatio(LnrDimens.POSTER_ASPECT)
                    .clip(RoundedCornerShape(UiDimens.cardCorner))
                    .background(colors.surface.copy(alpha = 0.72f)),
            ) {
                CoverImage(
                    url = entry.coverUrl,
                    contentDescription = entry.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(LnrDimens.POSTER_ASPECT),
                    targetWidthDp = 220,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
            ) {
                Text(
                    "继续阅读",
                    color = colors.primary,
                    fontSize = UiDimens.captionSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    entry.title,
                    fontSize = UiDimens.title,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildList {
                        resume?.let { add("第 ${it.chapterIndex + 1} 章 · 第 ${it.paragraphIndex + 1} 段") }
                        add(formatRelativeReadTime(now, entry.lastReadAt))
                    }.joinToString(" · "),
                    fontSize = UiDimens.caption,
                    color = secondaryTextColor(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                resume?.let { progress ->
                    val percent = readingPercent(progress, entry.chapterCount)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LinearProgressIndicator(
                            progress = percent / 100f,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "$percent%",
                            fontSize = UiDimens.captionSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("翻开", fontSize = UiDimens.caption, fontWeight = FontWeight.Bold)
                    Icon(
                        imageVector = MiuixIcons.ChevronForward,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(UiDimens.spaceL),
                    )
                }
            }
        }
    }
}

/** 横向封面轨道中的一本书。 */
@Composable
private fun RecentBookCard(
    entry: BookshelfEntry,
    resume: ReadingProgress?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(UiDimens.recentCardWidth)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pressableScale(interaction),
        verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(LnrDimens.POSTER_ASPECT)
                .clip(RoundedCornerShape(UiDimens.panelCorner))
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .padding(UiDimens.spaceXXS),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(UiDimens.panelCorner))
                    .background(MiuixTheme.colorScheme.surface),
            ) {
                CoverImage(
                    url = entry.coverUrl,
                    contentDescription = entry.title,
                    modifier = Modifier.fillMaxSize(),
                    targetWidthDp = 160,
                )
            }
        }
        Text(
            entry.title,
            fontSize = UiDimens.caption,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            resume?.let { "第 ${it.chapterIndex + 1} 章 · ${readingPercent(it, entry.chapterCount)}%" } ?: "尚未开始",
            fontSize = UiDimens.captionSmall,
            color = if (resume != null) MiuixTheme.colorScheme.primary else tertiaryTextColor(),
            maxLines = 1,
        )
    }
}

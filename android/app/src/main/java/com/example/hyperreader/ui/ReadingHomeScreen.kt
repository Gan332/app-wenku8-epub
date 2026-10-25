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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 阅读首页。
 *
 * 这一屏不是「书架摘要」，而是读者回到应用后的第一落点：先给一个可继续的阅读入口，
 * 再提供最近书目与书签。因此主卡使用完整的封面 + 大字号书名 + 阅读断点，
 * 其余内容收敛成横向封面轨道。
 */
@Composable
fun ReadingHomeScreen(
    bookshelf: List<BookshelfEntry>,
    progress: Map<String, ReadingProgress>,
    onOpenLocal: (BookshelfEntry) -> Unit,
    onOpenRemote: (BookshelfEntry) -> Unit,
    onOpenBookshelf: () -> Unit,
    onOpenExplore: () -> Unit,
    onOpenBookmarks: () -> Unit,
) {
    val now = remember(bookshelf, progress) { System.currentTimeMillis() }
    val withProgress = remember(bookshelf, progress) {
        bookshelf.filter { progress.containsKey(it.bookId) }.sortedByDescending { it.lastReadAt }
    }
    val continueEntry = remember(withProgress) { withProgress.firstOrNull() }
    val recentWithProgress = remember(withProgress) { withProgress.drop(1) }
    val recentlyRead = remember(bookshelf) {
        bookshelf.filter { it.lastReadAt > 0L }.sortedByDescending { it.lastReadAt }
    }

    fun open(entry: BookshelfEntry) {
        if (entry.localUri != null) onOpenLocal(entry) else onOpenRemote(entry)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = UiDimens.spaceS, bottom = UiDimens.spaceXL),
        verticalArrangement = Arrangement.spacedBy(UiDimens.spaceL),
    ) {
        item(key = "reading-toolbar") {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = "书签中心",
                    onClick = onOpenBookmarks,
                    modifier = Modifier.heightIn(min = UiDimens.touchMin),
                )
            }
        }

        if (continueEntry == null) {
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

        if (recentWithProgress.isNotEmpty()) {
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

        if (recentlyRead.isNotEmpty()) {
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
                resume?.let {
                    LinearProgressIndicator(
                        progress = ((it.chapterIndex + 1).toFloat() /
                            (entry.chapterCount.coerceAtLeast(it.chapterIndex + 1))).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                    )
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
            resume?.let { "第 ${it.chapterIndex + 1} 章" } ?: "尚未开始",
            fontSize = UiDimens.captionSmall,
            color = if (resume != null) MiuixTheme.colorScheme.primary else tertiaryTextColor(),
            maxLines = 1,
        )
    }
}

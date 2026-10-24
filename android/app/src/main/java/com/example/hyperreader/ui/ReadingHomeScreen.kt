package com.example.hyperreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import com.example.hyperreader.model.BookshelfEntry
import com.example.hyperreader.settings.ReadingProgress
import com.example.hyperreader.ui.cover.CoverImage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 阅读首页（0.19.0 起对齐 LNR `MainDestination.Reading` / `ReadingHomeScreen`）。
 *
 * LNR 把「阅读」放在四个一级页面的**第一位并且是默认首屏**（`MainDestination.Reading(0, …)`），
 * 结构是：顶栏 → 「继续阅读」大卡 → 「最近阅读」横向卡片流。本屏照搬这套排布：
 *
 * - **继续阅读**：取最近读过且有断点的书，显示封面、书名、读到第几章与时间；
 *   点击直接回到阅读器（本地 EPUB → 离线阅读器，在线书 → 在线阅读器并跳到断点章）。
 * - **最近阅读**：其余有断点的书，横向滑动；「最近更新」块展示整架最近读过的书。
 * - 空态给出去探索/书架的出口，而不是一片空白。
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
    val recentlyRead = remember(bookshelf, progress) {
        bookshelf.filter { it.lastReadAt > 0L }.sortedByDescending { it.lastReadAt }
    }

    fun open(entry: BookshelfEntry) {
        if (entry.localUri != null) onOpenLocal(entry) else onOpenRemote(entry)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 左右内边距由宿主给一次（StudioApp 的 Column 已 padding(pagePadding)），
        // 这里再写一遍会让正文比页内标题多缩进 16dp。
        contentPadding = PaddingValues(vertical = UiDimens.spaceM),
        verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
    ) {
        item(key = "bookmarks-entry") {
            // 页名由顶栏给（PageChrome，0.19.0-alpha05），页内不再重复画「阅读」大标题。
            // 书签中心是全屏页而非新的一级导航（AGENTS §4.6.1 第 1 条），保留常驻入口。
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = "书签",
                    onClick = onOpenBookmarks,
                    modifier = Modifier.heightIn(min = UiDimens.touchMin),
                )
            }
        }

        if (continueEntry == null) {
            item(key = "empty") {
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(UiDimens.spaceL),
                        verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
                    ) {
                        Text("还没有开始读", fontWeight = FontWeight.Bold, fontSize = UiDimens.section)
                        Text(
                            "从书架里点一本书就能开读；读过的书会自动出现在这里，随时接着往下读。",
                            fontSize = UiDimens.caption,
                            color = secondaryTextColor(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                            TextButton(text = "去书架", onClick = onOpenBookshelf, modifier = Modifier.heightIn(min = UiDimens.touchMin))
                            TextButton(text = "去探索", onClick = onOpenExplore, modifier = Modifier.heightIn(min = UiDimens.touchMin))
                        }
                    }
                }
            }
        } else {
            item(key = "continue") {
                SectionHeader("继续阅读")
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
                SectionHeader("最近在读")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                    items(recentWithProgress, key = { it.id }) { entry ->
                        RecentBookCard(entry = entry, resume = progress[entry.bookId], onClick = { open(entry) })
                    }
                }
            }
        }

        if (recentlyRead.isNotEmpty()) {
            item(key = "recent-read") {
                // 这一栏取的是 `lastReadAt` 降序，是「最近读过的书」，不是源站最近更新的书——
                // 标题过去写成「最近更新」，与内容不符。栏目内不再另取数据，只把名字改对。
                SectionHeader("最近阅读")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                    items(recentlyRead, key = { it.id }) { entry ->
                        RecentBookCard(entry = entry, resume = progress[entry.bookId], onClick = { open(entry) })
                    }
                }
            }
        }
    }
}

/** 「继续阅读」大卡：左侧封面，右侧书名 / 章节断点 / 时间 / 进度条。 */
@Composable
private fun ContinueReadingCard(
    entry: BookshelfEntry,
    resume: ReadingProgress?,
    now: Long,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pressableScale(interaction),
        insideMargin = PaddingValues(UiDimens.cardInset),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(LnrDimens.continueCoverWidth)
                    .aspectRatio(LnrDimens.POSTER_ASPECT)
                    .clip(RoundedCornerShape(LnrDimens.posterCardCorner)),
            ) {
                CoverImage(
                    url = entry.coverUrl,
                    contentDescription = entry.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(LnrDimens.POSTER_ASPECT),
                    targetWidthDp = 180,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXXS),
            ) {
                Text(entry.title, fontWeight = FontWeight.Bold, fontSize = UiDimens.bodyStrong, maxLines = 2)
                val detail = buildList {
                    resume?.let { add("第${it.chapterIndex + 1}章 · 第${it.paragraphIndex + 1}段") }
                    add(formatRelativeReadTime(now, entry.lastReadAt))
                }
                Text(
                    detail.joinToString(" · "),
                    fontSize = UiDimens.captionSmall,
                    color = secondaryTextColor(),
                    maxLines = 1,
                )
                resume?.let {
                    LinearProgressIndicator(
                        progress = ((it.chapterIndex + 1).toFloat() / (entry.chapterCount.coerceAtLeast(it.chapterIndex + 1))).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** 最近书目的横向小卡：封面 + 书名。 */
@Composable
private fun RecentBookCard(
    entry: BookshelfEntry,
    resume: ReadingProgress?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(POSTER_CARD_WIDTH)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pressableScale(interaction)
            .padding(vertical = UiDimens.spaceXXS),
        verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXXS),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(LnrDimens.POSTER_ASPECT)
                .clip(RoundedCornerShape(LnrDimens.posterCardCorner)),
        ) {
            CoverImage(
                url = entry.coverUrl,
                contentDescription = entry.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(LnrDimens.POSTER_ASPECT),
                targetWidthDp = 160,
            )
        }
        Text(entry.title, fontSize = UiDimens.caption, maxLines = 2)
        resume?.let {
            Text("第${it.chapterIndex + 1}章", fontSize = UiDimens.captionSmall, color = MiuixTheme.colorScheme.primary)
        }
    }
}

private val POSTER_CARD_WIDTH = LnrDimens.recentCardWidth




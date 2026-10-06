package com.example.hyperreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.example.hyperreader.model.BookshelfEntry
import com.example.hyperreader.reader.BookmarkGroup
import com.xyreader.core.BookmarkEntity
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 书签中心（0.19.0）：跨书聚合全部书签。
 *
 * 入口在阅读首页与设置页，**不占一级导航**（AGENTS §4.6.1 第 1 条：底部导航固定四项）。
 *
 * 数据来自两个阅读器 Bridge 共用的 `xy_reader_bookmarks` 键，按书分组。
 * 点击一条书签：
 * - 本地 EPUB → 用书架条目的 `localUri` 开本地阅读器（页跳转见调用方）；
 * - 在线书 → 以书籍编号开在线阅读器。在线页进度只与「按某起始章打开的页轴」配对
 *   （AGENTS §4.8），因此这里不承诺恢复到具体页，由在线阅读器按已存进度续读。
 *
 * @param bookshelf 用于本地书签回查 URI；书已移出书架时本地书签无法跳转，明示禁用。
 */
@Composable
fun BookmarkCenterScreen(
    groups: List<BookmarkGroup>,
    bookshelf: List<BookshelfEntry>,
    onOpenBookmark: (BookmarkGroup, BookmarkEntity) -> Unit,
    onRemoveBookmark: (Long) -> Unit,
    onClearBook: (String) -> Unit,
    onBack: () -> Unit,
) {
    val now = remember(groups) { System.currentTimeMillis() }
    val localUriByBookId = remember(bookshelf) { bookshelf.associate { it.bookId to it.localUri } }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = UiDimens.spaceL),
        verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
    ) {
        TextButton(text = "‹ 返回", onClick = onBack)

        if (groups.isEmpty()) {
            EmptyState(
                title = "还没有书签",
                description = "在阅读器里点书签按钮就会出现在这里，按书分组，方便回到上次读到的地方。",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(UiDimens.listGap),
                contentPadding = PaddingValues(bottom = UiDimens.spaceXL),
            ) {
                items(groups, key = { "${it.bookSource}:${it.hostBookId}" }) { group ->
                    BookmarkGroupCard(
                        group = group,
                        now = now,
                        // 书已移出书架时本地书签定位不到文件，明确说明而不是点了没反应。
                        canOpen = !group.isLocal || localUriByBookId[group.hostBookId] != null,
                        onOpenBookmark = { bookmark -> onOpenBookmark(group, bookmark) },
                        onRemoveBookmark = onRemoveBookmark,
                        onClearBook = onClearBook,
                    )
                }
            }
        }
    }
}

/** 一本书的书签组：组头（书名 + 数量 + 清空），组内每条书签。 */
@Composable
private fun BookmarkGroupCard(
    group: BookmarkGroup,
    now: Long,
    canOpen: Boolean,
    onOpenBookmark: (BookmarkEntity) -> Unit,
    onRemoveBookmark: (Long) -> Unit,
    onClearBook: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(UiDimens.spaceS),
            verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXXS),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.rowMin),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        group.displayTitle,
                        fontWeight = FontWeight.Bold,
                        fontSize = UiDimens.bodyStrong,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${group.bookmarks.size} 条 · ${formatRelativeReadTime(now, group.latestAt)}",
                        fontSize = UiDimens.captionSmall,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f),
                    )
                }
                TextButton(
                    text = "清空",
                    onClick = { onClearBook(group.hostBookId) },
                    modifier = Modifier.heightIn(min = UiDimens.touchMin),
                )
            }

            if (!canOpen) {
                Text(
                    "这本书已不在书架中，无法跳转。",
                    fontSize = UiDimens.captionSmall,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f),
                )
            }

            group.bookmarks.forEach { bookmark ->
                BookmarkRow(
                    bookmark = bookmark,
                    now = now,
                    onClick = { onOpenBookmark(bookmark) },
                    onRemove = { onRemoveBookmark(bookmark.id) },
                )
            }
        }
    }
}

/** 单条书签：第 N 页 + 摘录（或「本页无正文」）+ 删除。 */
@Composable
private fun BookmarkRow(
    bookmark: BookmarkEntity,
    now: Long,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = UiDimens.spaceXS),
        horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXXS)) {
            Text(
                "第 ${bookmark.pageIndex + 1} 页 · ${formatRelativeReadTime(now, bookmark.createdAt)}",
                fontSize = UiDimens.caption,
                color = MiuixTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = bookmark.snippet.ifBlank { "（本页无正文）" },
                fontSize = UiDimens.captionSmall,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .75f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            text = "删除",
            onClick = onRemove,
            modifier = Modifier.heightIn(min = UiDimens.touchMin),
        )
    }
}
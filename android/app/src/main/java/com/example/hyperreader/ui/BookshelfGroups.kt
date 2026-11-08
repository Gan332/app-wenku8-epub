package com.example.hyperreader.ui

import com.example.hyperreader.model.BookshelfEntry

/**
 * 书架分组（参考 LNR `BookshelfHomeContent.bookshelfContent` 的 stickyHeader 分组）。
 *
 * 与上游的差异：上游有「更新中 / 置顶 / 全部」三段，其中「更新中」依赖
 * `latestChapterTitle`。本工程自 0.19.0-alpha06 起用 `lastCheckedChapterCount`
 * 与 `chapterCount` 的差值得到未读新章（见 [BookshelfEntry.unreadChapters]），
 * 因此也能给出「更新中」段：有更新的书只出现在该段，避免同一本书两张卡片。
 */
enum class BookshelfGroup { Updated, Pinned, All }

/** 一组书及其展开状态。 */
data class BookshelfSection(
    val group: BookshelfGroup,
    val entries: List<BookshelfEntry>,
)

/** 分组标题（显示用；「全部」段在末尾补总数）。 */
fun BookshelfGroup.title(count: Int): String = when (this) {
    BookshelfGroup.Updated -> "更新中 $count 本"
    BookshelfGroup.Pinned -> "置顶 $count 本"
    BookshelfGroup.All -> "全部 $count 本"
}

/**
 * 把书架切成「置顶 / 全部」两段，空组不产出。
 *
 * 段内顺序沿用传入顺序（即 `BookshelfRepository` 的排序：置顶优先、然后最近阅读优先），
 * 不在这里重排——重排会与仓库层产生两个真相来源。
 */
fun groupBookshelf(entries: List<BookshelfEntry>): List<BookshelfSection> = buildList {
    val updated = entries.filter { it.hasUnreadChapters }
    if (updated.isNotEmpty()) add(BookshelfSection(BookshelfGroup.Updated, updated))
    val pinned = entries.filter { it.isPinned && !it.hasUnreadChapters }
    if (pinned.isNotEmpty()) add(BookshelfSection(BookshelfGroup.Pinned, pinned))
    val rest = entries.filter { !it.isPinned && !it.hasUnreadChapters }
    if (rest.isNotEmpty()) add(BookshelfSection(BookshelfGroup.All, rest))
}


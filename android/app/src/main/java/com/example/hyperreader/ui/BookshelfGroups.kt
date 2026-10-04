package com.example.hyperreader.ui

import com.example.hyperreader.model.BookshelfEntry

/**
 * 书架分组（参考 LNR `BookshelfHomeContent.bookshelfContent` 的 stickyHeader 分组）。
 *
 * 与上游的差异：上游有「更新中 / 置顶 / 全部」三段，其中「更新中」依赖
 * `latestChapterTitle`（有新章节未读）。本工程的 `BookshelfEntry` 没有这个字段，
 * 加它要动 `BookshelfEntry` 与阅读/抓取链路；因此只分**置顶 / 全部**两段，
 * 置顶位用既有的 `isPinned`，不需要新数据（AGENTS §4.6.1：不为视觉差异引入无用数据）。
 */
enum class BookshelfGroup { Pinned, All }

/** 一组书及其展开状态。 */
data class BookshelfSection(
    val group: BookshelfGroup,
    val entries: List<BookshelfEntry>,
)

/** 分组标题（显示用；「全部」段在末尾补总数）。 */
fun BookshelfGroup.title(count: Int): String = when (this) {
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
    val pinned = entries.filter { it.isPinned }
    if (pinned.isNotEmpty()) add(BookshelfSection(BookshelfGroup.Pinned, pinned))
    val rest = entries.filter { !it.isPinned }
    if (rest.isNotEmpty()) add(BookshelfSection(BookshelfGroup.All, rest))
}


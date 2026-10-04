package com.example.hyperreader.ui

import com.example.hyperreader.model.BookshelfEntry
import com.example.hyperreader.settings.BookshelfSort
/**
 * 按 [sort] 排列书架条目（纯函数，可单测）。
 *
 * 排序方式 [BookshelfSort] 本身是**持久化设置**，因此定义在 `settings` 包
 * （与 `EpubEngine` / `ReaderBackground` 一致），这里只放展示层的排列逻辑，
 * 避免 settings 反向依赖 ui。
 *
 * 不变量：
 * - **置顶恒在最前**：任何排序方式下 `isPinned` 都优先。它是用户显式标记的意图，
 *   不应被排序方式覆盖（也与 0.19.0 之前的行为一致）。
 * - **全序且确定**：同键值一律用 `title`、再 `id` 兜底。少了这层兜底，
 *   等值条目的相对顺序取决于输入顺序，DataStore 每次发射都可能抖动，列表会莫名跳动。
 * - **不改动入参**，返回新列表。
 */
fun sortBookshelf(entries: List<BookshelfEntry>, sort: BookshelfSort): List<BookshelfEntry> {
    val byPinned = compareByDescending<BookshelfEntry> { it.isPinned }
    val within = when (sort) {
        // 从未读过的书 lastReadAt 为 0，回落到加入时间，否则它们会全部挤在最后
        BookshelfSort.RecentRead -> compareByDescending<BookshelfEntry> {
            it.lastReadAt.takeIf { value -> value > 0 } ?: it.addedAt
        }

        BookshelfSort.RecentlyAdded -> compareByDescending<BookshelfEntry> { it.addedAt }

        BookshelfSort.Title -> compareBy { it.title }

        // 未知字数（本地 EPUB 未解析出字数）回落 0 并降序 → 排在最后
        BookshelfSort.WordCount -> compareByDescending<BookshelfEntry> { it.wordCount ?: 0L }
    }
    return entries.sortedWith(byPinned.then(within).thenBy { it.title }.thenBy { it.id })
}

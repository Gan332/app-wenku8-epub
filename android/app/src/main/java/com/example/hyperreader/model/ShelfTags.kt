package com.example.hyperreader.model

/** 单本书最多保留的标签数；超出时按输入顺序截断。 */
const val MAX_SHELF_TAGS_PER_BOOK = 8

/** 单个标签的最大长度（字符）。 */
const val MAX_SHELF_TAG_LENGTH = 16

/**
 * 规范化用户输入的标签：去掉首尾空白与开头的 `#`，把内部连续空白收成一个空格。
 * 空标签或只含 `#` 的输入返回 null。
 */
fun normalizeShelfTag(raw: String): String? {
    val trimmed = raw.trim().trimStart('#', '＃').trim()
    if (trimmed.isEmpty()) return null
    return trimmed.replace(Regex("\\s+"), " ").take(MAX_SHELF_TAG_LENGTH).trim().ifBlank { null }
}

/** 规范化一组标签：去空、大小写不敏感去重（保留首次出现的写法）、限长限量。 */
fun normalizeShelfTags(tags: Iterable<String>): List<String> =
    tags.asSequence()
        .mapNotNull(::normalizeShelfTag)
        .distinctBy { it.lowercase() }
        .take(MAX_SHELF_TAGS_PER_BOOK)
        .toList()

/** 书架标签及其使用次数。 */
data class ShelfTag(val name: String, val count: Int)

/**
 * 汇总书架上的标签，按使用次数降序（同次数按名称升序），计数时同一本书只算一次。
 * 标签是本地展示层数据，不从 Wenku8 的书籍标签复用。
 */
fun buildShelfTagIndex(entries: List<BookshelfEntry>): List<ShelfTag> {
    val names = LinkedHashMap<String, String>()
    val counts = LinkedHashMap<String, Int>()
    entries.forEach { entry ->
        normalizeShelfTags(entry.tags).forEach { tag ->
            val key = tag.lowercase()
            if (key !in names) names[key] = tag
            counts[key] = (counts[key] ?: 0) + 1
        }
    }
    return counts.map { (key, count) -> ShelfTag(names.getValue(key), count) }
        .sortedWith(compareByDescending<ShelfTag> { it.count }.thenBy { it.name })
}

/** 标签筛选；[tag] 为 null 表示不筛选。 */
fun BookshelfEntry.matchesShelfTag(tag: String?): Boolean =
    tag == null || tags.any { it.equals(tag, ignoreCase = true) }

/** 书名、作者或标签任一命中关键词。 */
fun BookshelfEntry.matchesShelfQuery(query: String): Boolean {
    val keyword = query.trim()
    if (keyword.isEmpty()) return true
    return title.contains(keyword, ignoreCase = true) ||
        author.contains(keyword, ignoreCase = true) ||
        tags.any { it.contains(keyword, ignoreCase = true) }
}

/** 加上或移除一个标签，结果总是规范化后的列表。 */
fun BookshelfEntry.withShelfTag(tag: String, enabled: Boolean): BookshelfEntry {
    val normalized = normalizeShelfTag(tag) ?: return this
    val current = normalizeShelfTags(tags)
    val exists = current.any { it.equals(normalized, ignoreCase = true) }
    val next = when {
        !enabled -> current.filterNot { it.equals(normalized, ignoreCase = true) }
        exists -> current
        // 到达上限时挤掉最早的一个，避免「添加」按钮点了却没有效果
        else -> (current + normalized).takeLast(MAX_SHELF_TAGS_PER_BOOK)
    }
    return copy(tags = next)
}

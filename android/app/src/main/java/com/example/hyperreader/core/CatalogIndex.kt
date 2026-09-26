package com.example.hyperreader.core

import java.text.Normalizer
import java.util.Locale

/**
 * 免登录的本地书目索引。构建一次，之后所有搜索都在内存完成。
 * 目标：1 万条规模下单次检索 < 50ms。
 */
class CatalogIndex(entries: Collection<CatalogEntry>) {
    val entries: Map<String, CatalogEntry> = entries.associateBy { it.id }
    private val byTitle = HashMap<String, List<String>>()
    private val byAuthor = HashMap<String, List<String>>()
    private val tagIndex = HashMap<String, List<String>>()

    init {
        val title = HashMap<String, MutableList<String>>()
        val author = HashMap<String, MutableList<String>>()
        val tag = HashMap<String, MutableList<String>>()
        for (entry in entries) {
            indexInto(title, entry.title, entry.id)
            indexInto(author, entry.author, entry.id)
            entry.tags.forEach { indexInto(tag, it, entry.id) }
            entry.category.takeIf { it.isNotBlank() }?.let { indexInto(tag, it, entry.id) }
        }
        byTitle.putAll(title)
        byAuthor.putAll(author)
        tagIndex.putAll(tag)
    }

    val size: Int get() = entries.size

    val allTags: List<String> get() = tagIndex.keys.sorted()

    fun get(id: String): CatalogEntry? = entries[id]

    /**
     * 本地搜索。field 决定主检索字段，标签始终参与匹配。
     * 排序：完全匹配 > 前缀匹配 > 包含匹配，同权重按字数与更新时间。
     */
    fun search(query: String, field: CatalogSearchField = CatalogSearchField.TITLE, limit: Int = 60): List<CatalogEntry> {
        val needle = normalize(query)
        if (needle.isBlank()) return emptyList()
        val primary = when (field) {
            CatalogSearchField.TITLE -> byTitle
            CatalogSearchField.AUTHOR -> byAuthor
        }
        val exact = HashSet<String>()
        val prefix = HashSet<String>()
        val contains = HashSet<String>()
        for ((key, ids) in primary) {
            if (key == needle) ids.forEach(exact::add)
            else if (key.startsWith(needle)) ids.forEach(prefix::add)
            else if (key.contains(needle)) ids.forEach(contains::add)
        }
        for ((key, ids) in tagIndex) {
            if (key == needle) ids.forEach(exact::add)
            else if (key.startsWith(needle)) ids.forEach(prefix::add)
            else if (key.contains(needle)) ids.forEach(contains::add)
        }
        val rank = HashMap<String, Int>()
        exact.forEach { rank[it] = 3 }
        prefix.forEach { rank.putIfAbsent(it, 2) }
        contains.forEach { rank.putIfAbsent(it, 1) }
        return rank.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenByDescending { entries[it.key]?.wordCount ?: 0L }
                    .thenByDescending { entries[it.key]?.updatedAt.orEmpty() }
                    .thenBy { entries[it.key]?.title.orEmpty() }
            )
            .take(limit)
            .mapNotNull { entries[it.key] }
    }

    /** 按标签浏览。 */
    fun searchTag(tag: String, limit: Int = 200): List<CatalogEntry> =
        tagIndex[normalize(tag)].orEmpty().mapNotNull { entries[it] }.take(limit)

    private fun indexInto(target: HashMap<String, MutableList<String>>, raw: String, id: String) {
        if (raw.isBlank()) return
        val key = normalize(raw)
        if (key.isBlank()) return
        target.getOrPut(key) { mutableListOf() }.add(id)
    }

    companion object {
        /** NFKC + 去空白 + 全角转半角 + 小写。 */
        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), "")
            .replace(Regex("[！-～]")) { m -> ((m.value[0].code - 0xFEE0).toChar()).toString() }
    }
}

enum class CatalogSearchField { TITLE, AUTHOR }

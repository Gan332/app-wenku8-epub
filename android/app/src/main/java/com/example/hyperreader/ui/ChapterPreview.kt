package com.example.hyperreader.ui

import com.example.hyperreader.model.Chapter

/**
 * 详情页目录预览（0.18.0）。
 *
 * 章节目录动辄上千章，详情页不能一次性渲染，因此纯逻辑抽到这里以便单测：
 * - 折叠时只给前 [PREVIEW_LIMIT] 章；
 * - 搜索按章节标题与卷名匹配（忽略大小写），在**折叠与展开之前**先过滤；
 * - 展开后不再截断，但外层仍用限高 `LazyColumn` 渲染，避免整页一次性构建。
 */
internal object ChapterPreview {
    /** 折叠状态下默认展示的章节数。 */
    const val PREVIEW_LIMIT = 20

    /** 展开态列表的最大高度（外层限高 + 内部滚动）。 */
    const val EXPANDED_MAX_HEIGHT_DP = 420

    fun filter(chapters: List<Chapter>, query: String): List<Chapter> {
        if (query.isBlank()) return chapters
        return chapters.filter { chapter ->
            chapter.title.contains(query, ignoreCase = true) || chapter.volume.contains(query, ignoreCase = true)
        }
    }

    /** 当前状态下要渲染的章节：折叠取前 [PREVIEW_LIMIT]，展开取全量。 */
    fun preview(chapters: List<Chapter>, query: String, expanded: Boolean): List<Chapter> {
        val filtered = filter(chapters, query)
        return if (expanded) filtered else filtered.take(PREVIEW_LIMIT)
    }

    /** 是否需要显示「展开全部 / 收起」按钮。 */
    fun canExpand(chapters: List<Chapter>, query: String, expanded: Boolean): Boolean {
        if (expanded) return filter(chapters, query).size > PREVIEW_LIMIT
        return filter(chapters, query).size > PREVIEW_LIMIT
    }
}
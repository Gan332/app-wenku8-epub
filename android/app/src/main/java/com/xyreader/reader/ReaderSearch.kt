package com.xyreader.reader

/** 一处命中：页码（0 起）+ 上下文摘要。 */
data class SearchHit(val page: Int, val snippet: String)

/**
 * 页内搜索的对外状态。
 *
 * [scannedPages]/[totalPages] 让界面能显示「已扫 123 / 400 页」——全书扫描不是瞬时的，
 * 没有进度用户会以为卡住了。[hits] 随扫描推进逐步增长，可边搜边看。
 */
data class SearchState(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val scannedPages: Int = 0,
    val totalPages: Int = 0,
    val running: Boolean = false,
    val finished: Boolean = false,
)

/**
 * 命中片段的分隔符。
 *
 * 摘要里用它把命中段与上下文分开：界面上据此给命中段上强调色。选 `‖`（双竖线）
 * 是因为它几乎不会出现在正文里，不引入额外状态也不必做转义。
 */
const val SNIPPET_MARK = "‖"

/** 摘要默认截取半径（命中位置前后各取多少字）。 */
const val SNIPPET_RADIUS = 24

/**
 * 从 [text] 里找出 [query] 的第一处命中，截取上下文摘要。
 *
 * 命中段用 [SNIPPET_MARK] 包裹；命中在页首或页尾时不产生悬空分隔符。
 * 未命中返回 null。匹配忽略大小写（英文关键词受益，中文无影响）。
 */
fun contextSnippet(
    text: String,
    query: String,
    radius: Int = SNIPPET_RADIUS,
): String? {
    val needle = query.trim()
    if (needle.isEmpty() || text.isEmpty()) return null
    val at = text.indexOf(needle, ignoreCase = true)
    if (at < 0) return null
    // 半径为负时退化成只显示命中段，避免 coerceIn 因下界反向而抛异常。
    val safeRadius = radius.coerceAtLeast(0)
    val from = (at - safeRadius).coerceAtLeast(0)
    val to = (at + needle.length + safeRadius).coerceAtMost(text.length)
    val before = text.substring(from, at)
    val match = text.substring(at, at + needle.length)
    val after = text.substring(at + needle.length, to)
    return buildString {
        if (from > 0) append('…')
        append(before)
        append(SNIPPET_MARK)
        append(match)
        append(SNIPPET_MARK)
        append(after)
        if (to < text.length) append('…')
    }
}

/**
 * 在逐页文本里搜索 [query]，返回全部命中。
 *
 * [pages] 是「页码（0 起） -> 页文本」的映射；null 表示该页无文字（图片页、
 * 提取失败），跳过而不是当作空串——否则会把整本图片书报成「零命中」以外的假空结果。
 *
 * 纯函数：调用方负责按什么节奏喂页（见 `ReaderViewModel.searchBook`——逐页调用
 * `PageSource.pageText`，每次都释放 `renderMutex`，避免长时间占锁导致翻页卡顿）。
 */
fun searchResultPages(pages: Map<Int, String?>, query: String, radius: Int = SNIPPET_RADIUS): List<SearchHit> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    // 按页码升序，结果顺序与阅读顺序一致，可直接用「上一个/下一个」翻找。
    return pages.entries.sortedBy { it.key }.mapNotNull { (page, text) ->
        val body = text ?: return@mapNotNull null
        val snippet = contextSnippet(body, needle, radius) ?: return@mapNotNull null
        SearchHit(page, snippet)
    }
}

/** 书签摘录的默认长度（字）。够看清「大概在哪一段」，又不至于把整页塞进列表行。 */
const val BOOKMARK_SNIPPET_LENGTH = 60

/**
 * 从一页正文里取书签摘录：压掉换行与多余空白后截取开头 [limit] 个字。
 *
 * 摘录**不是**搜索命中片段（那需要 query），而是「加书签时这页在讲什么」，
 * 因此固定取页首而非命中位置附近——命中位置此时未知。
 *
 * 纯函数（便于单测）：空白折叠后为空则返回 null，调用方据此存空摘录而不是空白符。
 */
fun bookmarkSnippet(text: String?, limit: Int = BOOKMARK_SNIPPET_LENGTH): String? {
    if (text.isNullOrBlank()) return null
    val flat = text.replace(WHITESPACE_RUN, " ").trim()
    if (flat.isEmpty()) return null
    val safeLimit = limit.coerceAtLeast(0)
    return if (flat.length <= safeLimit) flat else flat.take(safeLimit).trimEnd() + "…"
}

/** 连续空白（含换行、段落缩进）折叠为一个空格。 */
private val WHITESPACE_RUN = Regex("\\s+")

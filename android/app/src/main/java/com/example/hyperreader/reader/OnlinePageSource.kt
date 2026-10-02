package com.example.hyperreader.reader

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import com.example.hyperreader.model.Chapter as HostChapter
import com.xyreader.archive.AbstractPageSource
import com.xyreader.archive.ChapterMark
import com.xyreader.archive.NovelPageSource
import com.xyreader.archive.NovelStyle
import com.xyreader.archive.Paragraph
import com.xyreader.core.Chapter as ReaderChapter
import com.xyreader.core.PageSource

/**
 * 在线阅读的页面源：**按章抓取 → 用与本地 EPUB 完全同一套 `StaticLayout` 分页 → 位图页**。
 *
 * 这样在线阅读与本地阅读共用 xy-reader 的阅读界面、手势、目录、书签与排版设置，
 * 不再维护第二套渲染（旧的 `ReaderScreenCore`）。
 *
 * == 为什么可以「增长」 ==
 * xy-reader 的 [PageSource] 原本约定 `pageCount` 打开后恒定；在线阅读逐章抓取，
 * 打开时并不知道总页数，因此本实现置 [growable] = true，由 [loadMore] 追加章节。
 * `ReaderViewModel.maybeGrow` 会在读到接近末尾时调用它并刷新页数与目录。
 *
 * == 章节如何拼成一条页轴 ==
 * 每章各自分页，然后按加载顺序**首尾相接**：第 N 章第 0 页在整条页轴上的下标是
 * 前面各章页数之和。[chapters] 用同样的累加规则给出每章的 `startPage` /
 * `endPageInclusive`，阅读界面的「上一章 / 下一章」因此可以直接按页跳转。
 *
 * 抓取本身由宿主注入 [fetchParagraphs]（走 `OnlineReaderSource` + 全局限流），
 * 本类不关心网络细节，也不反向依赖 `com.example.hyperreader` 之外的任何东西。
 */
class OnlinePageSource private constructor(
    private val context: Context,
    /** 全书目录（源站目录页的顺序），用于决定「下一章抓哪个」。 */
    private val catalog: List<HostChapter>,
    private val style: NovelStyle?,
    /** 抓取并解析某一章的正文段落；返回空列表表示该章没有正文。 */
    private val fetchParagraphs: suspend (HostChapter) -> List<String>,
    private val startIndex: Int,
    initial: LoadedChapter,
) : AbstractPageSource(), PageSource {

    private data class LoadedChapter(
        val id: String,
        val title: String,
        val source: NovelPageSource,
    )

    /**
     * 已加载章节的快照。`loadMore` 整体替换引用（而不是原地修改），
     * 这样渲染线程读到的永远是一个自洽的列表，不需要加锁。
     */
    @Volatile
    private var snapshot: List<LoadedChapter> = listOf(initial)

    /** 下一个待抓取的目录下标。 */
    @Volatile
    private var nextIndex: Int = startIndex + 1

    /** 同一时刻只允许一个抓取在跑（`ReaderViewModel` 也会去重，这里是双保险）。 */
    private val loading = java.util.concurrent.atomic.AtomicBoolean(false)

    override val growable: Boolean = true

    protected override val cachedPageCount: Int
        get() = snapshot.sumOf { it.source.pageCount }

    override val chapters: List<ReaderChapter>
        get() {
            var offset = 0
            return snapshot.map { chapter ->
                val pages = chapter.source.pageCount
                val start = offset
                offset += pages
                ReaderChapter(
                    title = chapter.title,
                    startPage = start,
                    endPageInclusive = (offset - 1).coerceAtLeast(start),
                )
            }
        }

    override suspend fun renderPage(index: Int): ImageBitmap {
        checkPage(index)
        val pages = snapshot
        var remaining = index
        for (chapter in pages) {
            val count = chapter.source.pageCount
            if (remaining < count) return chapter.source.renderPage(remaining)
            remaining -= count
        }
        throw IndexOutOfBoundsException("页码越界: $index / $cachedPageCount")
    }

    override suspend fun pageAspectRatio(index: Int): Float? {
        val pages = snapshot
        var remaining = index
        for (chapter in pages) {
            val count = chapter.source.pageCount
            if (remaining < count) return chapter.source.pageAspectRatio(remaining)
            remaining -= count
        }
        return null
    }

    /**
     * 抓取下一章并接到页轴尾部。
     *
     * 空章节（源站没正文）会被跳过并继续抓下一章，避免在空章上卡住翻页。
     *
     * @return 确实新增了页则 `true`。
     */
    override suspend fun loadMore(): Boolean {
        if (!loading.compareAndSet(false, true)) return false
        try {
            var cursor = nextIndex
            while (cursor < catalog.size) {
                val chapter = catalog[cursor]
                cursor += 1
                nextIndex = cursor
                val paragraphs = runCatching { fetchParagraphs(chapter) }.getOrDefault(emptyList())
                if (paragraphs.isEmpty()) continue
                val source = runCatching {
                    NovelPageSource.open(
                        context = context,
                        paragraphs = paragraphs.map { Paragraph(it, chapterIndex = 0) },
                        marks = listOf(ChapterMark(chapter.title, 0)),
                        style = style,
                    )
                }.getOrNull() ?: continue
                if (source.pageCount <= 0) continue
                snapshot = snapshot + LoadedChapter(chapter.id, chapter.title, source)
                return true
            }
            return false
        } finally {
            loading.set(false)
        }
    }

    override fun close() {
        onFirstClose {
            snapshot.forEach { runCatching { it.source.close() } }
        }
    }

    companion object {
        /**
         * 打开：先抓**起始章**再构造（[PageSource.pageCount] 必须非 0 才能被阅读器接受）。
         *
         * @param startChapterId 从目录里的哪一章开始读；找不到时退回第 0 章。
         */
        suspend fun open(
            context: Context,
            catalog: List<HostChapter>,
            startChapterId: String?,
            style: NovelStyle?,
            fetchParagraphs: suspend (HostChapter) -> List<String>,
        ): OnlinePageSource {
            if (catalog.isEmpty()) throw java.io.IOException("目录为空，无法在线阅读")
            val startIndex = catalog.indexOfFirst { it.id == startChapterId }.takeIf { it >= 0 } ?: 0

            // 起始章可能没有正文，向后找到第一个有正文的章；都没有才失败
            var cursor = startIndex
            var first: LoadedChapter? = null
            while (cursor < catalog.size && first == null) {
                val chapter = catalog[cursor]
                val paragraphs = runCatching { fetchParagraphs(chapter) }.getOrDefault(emptyList())
                if (paragraphs.isNotEmpty()) {
                    val source = NovelPageSource.open(
                        context = context,
                        paragraphs = paragraphs.map { Paragraph(it, chapterIndex = 0) },
                        marks = listOf(ChapterMark(chapter.title, 0)),
                        style = style,
                    )
                    if (source.pageCount > 0) first = LoadedChapter(chapter.id, chapter.title, source)
                }
                cursor += 1
            }
            val initial = first ?: throw java.io.IOException("起始章节没有正文，无法在线阅读")

            return OnlinePageSource(
                context = context,
                catalog = catalog,
                style = style,
                fetchParagraphs = fetchParagraphs,
                startIndex = cursor - 1,
                initial = initial,
            )
        }
    }
}

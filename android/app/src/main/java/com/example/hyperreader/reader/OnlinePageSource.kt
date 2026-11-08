package com.example.hyperreader.reader

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import com.example.hyperreader.model.Chapter as HostChapter
import com.xyreader.archive.AbstractPageSource
import com.xyreader.archive.ChapterMark
import com.xyreader.archive.EpubImageGroup
import com.xyreader.archive.NovelPageSource
import com.xyreader.archive.NovelStyle
import com.xyreader.archive.Paragraph
import com.xyreader.core.Chapter as ReaderChapter
import com.xyreader.core.ChapterOutline
import com.xyreader.core.PageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 在线阅读的页面源：**按章抓取 → 用与本地 EPUB 完全同一套 `StaticLayout` 分页 → 位图页**。
 *
 * 这样在线阅读与本地阅读共用 xy-reader 的阅读界面、手势、目录、书签与排版设置，
 * 不再维护第二套渲染（旧的 `ReaderScreenCore` 渲染层已于 0.19.0-alpha02 移除）。
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
 * 抓取本身由宿主注入 [fetchContent]（走 OnlineReaderSource 与全局限流），
 * 本类不关心网络细节，也不反向依赖 `com.example.hyperreader` 之外的任何东西。
 */
class OnlinePageSource private constructor(
    private val context: Context,
    /** 全书目录（源站目录页的顺序），用于决定「下一章抓哪个」。 */
    private val catalog: List<HostChapter>,
    private val style: NovelStyle?,
    /** 抓取并解析某一章的正文段落与插图；没有段落表示该章没有正文。 */
    private val fetchContent: suspend (HostChapter) -> OnlineChapterContent,
    private val startIndex: Int,
    initial: LoadedChapter,
) : AbstractPageSource(), PageSource, com.xyreader.core.ChapterSearchSource {

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

    /**
     * 同一时刻只允许一个抓取在跑（`ReaderViewModel` 也会去重，这里是双保险）。
     *
     * 用 [Mutex] 而不是 AtomicBoolean：目录跳章与「读到末尾自动续章」可能同时请求，
     * 后到者应当排队而不是直接失败——否则点了后面的章节却什么都没发生。
     */
    private val loading = Mutex()

    override val growable: Boolean = true
    override val styleDependent: Boolean get() = true

    /** 供在线仓库保存页进度时同时定位起始章和当前章。 */
    val loadedChapterProgress: List<OnlineLoadedChapter>
        get() {
            val pages = snapshot
            var offset = 0
            return pages.mapIndexed { loadedIndex, chapter ->
                val pageCount = chapter.source.pageCount
                val catalogIndex = catalog.indexOfFirst { it.id == chapter.id }
                    .takeIf { it >= 0 } ?: loadedIndex
                val start = offset
                offset += pageCount
                OnlineLoadedChapter(
                    id = chapter.id,
                    index = catalogIndex,
                    startPage = start,
                    endPageInclusive = (offset - 1).coerceAtLeast(start),
                )
            }
        }

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

    /**
     * 全书目录：**目录序号与源站目录一致**，已加载的章节带页区间，未加载的只有标题。
     *
     * 阅读器目录因此能一次展示整本书；未加载章节点击后由 [loadChapterPage] 按需续抓。
     */
    override val chapterOutline: List<ChapterOutline>
        get() = onlineChapterOutline(catalog, loadedPageRanges())

    private fun loadedPageRanges(): Map<String, Pair<Int, Int>> {
        val ranges = HashMap<String, Pair<Int, Int>>(snapshot.size)
        var offset = 0
        for (chapter in snapshot) {
            val pages = chapter.source.pageCount
            val start = offset
            offset += pages
            ranges[chapter.id] = start to (offset - 1).coerceAtLeast(start)
        }
        return ranges
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

    override suspend fun pageText(index: Int): String? {
        val pages = snapshot
        var remaining = index
        for (chapter in pages) {
            val count = chapter.source.pageCount
            if (remaining < count) return chapter.source.pageText(remaining)
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
    override suspend fun loadMore(): Boolean = loading.withLock {
        var cursor = nextIndex
        while (cursor < catalog.size) {
            val chapter = catalog[cursor]
            // 抓取成功后才推进游标：网络失败时保留本章，下次续章会重试。
            // 若先推进，一次瞬时限流/断网就会让这一章从页轴上永久消失。
            val content = try {
                fetchContent(chapter)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock false
            }
            cursor += 1
            nextIndex = cursor
            val paragraphs = content.paragraphs
            if (paragraphs.isEmpty()) continue
            val source = runCatching {
                NovelPageSource.open(
                    context = context,
                    paragraphs = paragraphs.map { Paragraph(it, chapterIndex = 0) },
                    marks = listOf(ChapterMark(chapter.title, 0)),
                    style = style,
                    imageGroups = content.images
                        .filter { (index, bytes) -> index in 0..paragraphs.size && bytes.isNotEmpty() }
                        .map { (index, bytes) -> EpubImageGroup(index, listOf(bytes)) },
                )
            }.getOrNull() ?: continue
            if (source.pageCount <= 0) continue
            snapshot = snapshot + LoadedChapter(chapter.id, chapter.title, source)
            return@withLock true
        }
        false
    }

    /**
     * 目录跳章：目标章在当前页轴之后时向前抓取到它；在起点之前则返回 null。
     *
     * 页轴只从本次阅读的起始章向后生长（AGENTS §4.8 的续读语义），
     * 因此**不**尝试把章节插到已渲染页之前——那会让当前页码整体位移、进度错位。
     * 调用方收到 null 后应当从目标章重开阅读（[OnlineReaderActivity] 用
     * `EXTRA_START_CHAPTER` 重建页轴）。
     */
    override suspend fun loadChapterPage(index: Int): Int? {
        val target = catalog.getOrNull(index) ?: return null
        pageForChapter(index)?.let { return it }
        val firstLoaded = catalog.indexOfFirst { it.id == snapshot.firstOrNull()?.id }
        if (firstLoaded < 0 || index < firstLoaded) return null
        // 抓取目标章；loadMore 会跳过空章并继续往后，所以循环条件以「页轴上已出现
        // 目标或目标之后的章」为准，而不是严格的 nextIndex == index。
        while (pageForChapter(index) == null && nextIndex <= index) {
            if (!loadMore()) break
        }
        // 目标章正文为空时跳它后面第一个有正文的章，避免点击后停在原地。
        if (pageForChapter(index) == null) {
            // 抓取失败（游标仍停在目标或之前）时不要往后跳：返回 null 让宿主
            // 从目标章重开，用户重试仍能读到这一章，而不是被带到下一章。
            if (nextIndex <= index) return null
            for (candidate in index + 1 until catalog.size) {
                pageForChapter(candidate)?.let { return it }
            }
        }
        return pageForChapter(index)
    }

    override fun close() {
        onFirstClose {
            snapshot.forEach { runCatching { it.source.close() } }
        }
    }

    /**
     * 全书章节级搜索（0.19.0）：逐章抓取正文并匹配，**不把章节接到页轴上**。
     *
     * 与页级搜索的根本差别：页级搜索只能扫已渲染的页，未读章节此刻还不存在；
     * 这里直接把正文抓下来搜，因此**受全局 1 秒限流约束**——一本 300 章的书
     * 最坏要几分钟，界面必须显示进度并允许取消。
     *
     * 三条约束（[com.xyreader.core.ChapterSearchSource] 的契约）：
     * - **可取消**：每章之间检查协程取消，关掉搜索面板即刻停手；
     * - **不改页轴**：只读 [fetchContent]，不碰 [snapshot] / [nextIndex]，
     *   用户正在读的位置不受搜索影响；
     * - **单章只抓一次**：[fetchContent] 内部已有 `loaded` 缓存（见
     *   `OnlinePageSourceOpener.fetch`），重复搜索同一章不会重复打网络。
     *
     * 单章失败（源站删除、限流退避后仍失败）**跳过该章继续**而不是中断整轮：
     * 一章读不到不代表后面几百章都读不到。
     */
    override suspend fun searchChapters(
        query: String,
        onProgress: (Int, Int) -> Unit,
    ): List<com.xyreader.core.ChapterSearchHit> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()

        val total = catalog.size
        val hits = mutableListOf<com.xyreader.core.ChapterSearchHit>()
        onProgress(0, total)

        for ((index, chapter) in catalog.withIndex()) {
            // 用户取消 / 换书 / 关面板后立即停手，不继续打扰源站
            if (!currentCoroutineContext().isActive) break

            val content = try {
                fetchContent(chapter)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            // 匹配规则在纯函数里（matchChapterBody），这里只负责取正文与推进循环
            val matched = com.xyreader.reader.matchChapterBody(
                content?.paragraphs.orEmpty(),
                needle,
            )
            if (matched != null) {
                val (at, snippet) = matched
                hits += com.xyreader.core.ChapterSearchHit(
                    chapterIndex = index,
                    chapterTitle = chapter.title,
                    snippet = snippet,
                    charOffset = at,
                )
            }
            onProgress(index + 1, total)
        }
        return hits
    }

    /**
     * 目录序号 → 页轴起始页。只有已加载的章才有页码；从中间某章打开时
     * 目录序号与加载序号存在偏移，因此按章节 id 查找而不是直接当下标用。
     */
    override fun pageForChapter(index: Int): Int? {
        val target = catalog.getOrNull(index) ?: return null
        val pages = snapshot
        var offset = 0
        for (chapter in pages) {
            if (chapter.id == target.id) return offset
            offset += chapter.source.pageCount
        }
        return null
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
            fetchContent: suspend (HostChapter) -> OnlineChapterContent,
        ): OnlinePageSource {
            if (catalog.isEmpty()) throw java.io.IOException("目录为空，无法在线阅读")
            val startIndex = catalog.indexOfFirst { it.id == startChapterId }.takeIf { it >= 0 } ?: 0

            // 起始章可能没有正文，向后找到第一个有正文的章；都没有才失败
            var cursor = startIndex
            var first: LoadedChapter? = null
            while (cursor < catalog.size && first == null) {
                val chapter = catalog[cursor]
                // 抓取失败不能当成空章跳过，否则会静默从下一章开始读；
                // 让调用方拿到错误并重试，用户至少知道这一章没打开。
                val content = try {
                    fetchContent(chapter)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    throw java.io.IOException("起始章节抓取失败：${chapter.title}", error)
                }
                val paragraphs = content.paragraphs
                if (paragraphs.isNotEmpty()) {
                    val source = NovelPageSource.open(
                        context = context,
                        paragraphs = paragraphs.map { Paragraph(it, chapterIndex = 0) },
                        marks = listOf(ChapterMark(chapter.title, 0)),
                        style = style,
                        imageGroups = content.images
                            .filter { (index, bytes) -> index in 0..paragraphs.size && bytes.isNotEmpty() }
                            .map { (index, bytes) -> EpubImageGroup(index, listOf(bytes)) },
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
                fetchContent = fetchContent,
                startIndex = cursor - 1,
                initial = initial,
            )
        }
    }
}

/**
 * 目录装配纯函数：按源站目录顺序输出全书章节，已分页的带页区间，其余留空。
 *
 * 阅读器的目录必须一眼看到整本书，不能只看到 snapshot 里已抓取的几章；
 * 这也是在线阅读「目录只有一章」缺陷的回归锚点。
 */
internal fun onlineChapterOutline(
    catalog: List<HostChapter>,
    pageRanges: Map<String, Pair<Int, Int>>,
): List<ChapterOutline> = catalog.map { chapter ->
    pageRanges[chapter.id]?.let { (start, end) ->
        ChapterOutline(chapter.title, start, end)
    } ?: ChapterOutline(chapter.title)
}

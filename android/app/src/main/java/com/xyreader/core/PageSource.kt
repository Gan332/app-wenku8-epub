package com.xyreader.core

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.Closeable

/** 章节：标题 + 页区间（闭区间）。由包内子文件夹等结构推导，无章节结构的格式返回空列表 */
data class Chapter(
    val title: String,
    val startPage: Int,
    val endPageInclusive: Int,
)

/**
 * 一本书的页面数据源契约。所有格式（CBZ/CBR/CB7/CBT/PDF/EPUB/图片目录/MOBI）都实现此接口。
 *
 * 实现要求：
 * - [pageCount] 在打开后不可变；
 * - [renderPage] 支持随机访问任意页（0 起），实现内部自行保证线程安全
 *   （如 PdfRenderer 这类单线程库要在实现里加锁，并在 IO 线程执行阻塞工作）；
 * - [close] 之后不可再调用 renderPage；
 * - 页面图片按文件名"自然顺序"排列（第2页排在第10页前面），
 *   过滤掉 __MACOSX、. 开头的隐藏文件与非图片文件。
 */
interface PageSource : Closeable {
    val pageCount: Int

    /**
     * 章节列表（阅读器目录抽屉用）：压缩包按条目路径第一层子文件夹分组；
     * 根级直接放图片时归入"正文"。无章节结构的格式（PDF/MOBI/扁平包）返回空列表。
     * 必须按 startPage 升序排列。
     */
    val chapters: List<Chapter>
        get() = emptyList()

    /** 渲染第 index 页（0 起） */
    suspend fun renderPage(index: Int): ImageBitmap

    /** 页面宽高比（宽 / 高）；无预先元数据的格式可返回 null，由渲染后的位图推导。 */
    suspend fun pageAspectRatio(index: Int): Float? = null

    /** 封面默认取第一页；格式自带封面元数据时可覆盖 */
    suspend fun renderCover(): ImageBitmap = renderPage(0)

    /**
     * 页数是否会在阅读过程中**增长**。
     *
     * 本地文件（EPUB / 压缩包 / PDF）打开后页数恒定，保持默认 `false`；
     * **在线阅读**是逐章抓取后分页，打开时只知道自己抓到的那些章，
     * 因此为 `true`，并由 [loadMore] 追加。
     */
    val growable: Boolean get() = false

    /**
     * 是否依赖 NovelStyle 重新分页。文字小说（含在线阅读的组合源）为 true；
     * PDF / 漫画位图源为 false，避免字体与段落设置变化触发无意义重开。
     */
    val styleDependent: Boolean get() = false

    /**
     * 按需加载更多内容（仅 [growable] 为 `true` 时有意义）。
     *
     * @return `true` 表示确实新增了页；调用方据此刷新 [pageCount] 与 [chapters]。
     *   默认不做事。
     */
    suspend fun loadMore(): Boolean = false

    /** 取文字页文本；非文字源默认不支持，阅读器据此隐藏复制文字。 */
    suspend fun pageText(page: Int): String? = null

    override fun close()
}

/** 便捷：字节数组解码为 ImageBitmap，失败返回 null */
fun ByteArray.decodeToImageBitmap(): ImageBitmap? =
    BitmapFactory.decodeByteArray(this, 0, size)?.asImageBitmap()

/**
 * 章节级全量搜索：把搜索范围从「已渲染的页」扩到「全书正文」。
 *
 * 存在的理由：默认的页级搜索（`pageText` 逐页）只能扫到**已加载**的页。在线阅读
 * 是逐章抓取的，未读章节的正文此刻根本不存在，页级扫描必然搜不到（AGENTS §4.8）。
 *
 * 契约要点：
 * - **可取消**：实现方必须在长循环里响应协程取消，否则用户关掉搜索面板后仍会继续抓；
 * - **不改变页轴**：搜索只读正文，不得把章节接到页面源上，否则用户正在读的位置会跳；
 * - **报告进度**：通过 [onProgress] 汇报「已扫 n / m」，全书抓取不是瞬时的。
 *
 * 命中以章节为单位（[ChapterSearchHit]）而非页码——全量搜索发生在页面源
 * 打开之前，那时还没有页码可言。调用方负责把章节换算成可跳转的位置。
 */
interface ChapterSearchSource {
    /**
     * 搜索全书正文，返回命中的章节。
     *
     * @param query 关键词（调用方已 trim）
     * @param onProgress 已扫描章节数与总章节数
     */
    suspend fun searchChapters(
        query: String,
        onProgress: (scanned: Int, total: Int) -> Unit,
    ): List<ChapterSearchHit>
}

/** 一处章节级命中。 [snippet] 用 [SNIPPET_MARK] 标记命中段，渲染时给命中段上强调色。 */
data class ChapterSearchHit(
    val chapterIndex: Int,
    val chapterTitle: String,
    val snippet: String,
    /** 该章命中处落在整章正文里的字符偏移；定位用，无则 -1。 */
    val charOffset: Int = -1,
)

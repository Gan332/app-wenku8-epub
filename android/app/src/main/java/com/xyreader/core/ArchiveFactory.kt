package com.xyreader.core

import android.content.Context
import com.xyreader.archive.DirectoryPageSource
import com.xyreader.archive.EpubImageBasedException
import com.xyreader.archive.MobiNotTextException
import com.xyreader.archive.MobiPageSource
import com.xyreader.archive.NovelPageSource
import com.xyreader.archive.NovelStyle
import com.xyreader.archive.NovelTextExtractor
import com.xyreader.archive.PageSources
import com.xyreader.archive.PdfPageSource
import com.xyreader.archive.RarPageSource
import com.xyreader.archive.SevenZipPageSource
import com.xyreader.archive.TarPageSource
import com.xyreader.archive.ZipPageSource
import java.io.FileInputStream
import java.nio.channels.SeekableByteChannel
import org.apache.commons.compress.archivers.zip.ZipFile

/**
 * 按格式路由到具体 PageSource 实现（见 com.xyreader.archive 包）。
 *
 * 路由表：
 * - TXT              -> NovelPageSource（文字小说：编码探测 → 章节切分 → 预分页 → 透明底文字页）
 * - CBZ / ZIP        -> ZipPageSource（commons-compress ZipFile）
 * - EPUB             -> 文本型（NovelTextExtractor.parseEpub 成功）-> NovelPageSource 文字管线；
 *                       图片型（首个 xhtml 含 <img> 或 spine 无文本条目）-> 回退 ZipPageSource
 * - MOBI / AZW3      -> 文本版（无压缩/PalmDOC 压缩 + 无加密 + 无图片资源）-> NovelPageSource；
 *                       其余（HUFF/CDIC、DRM、图片版）-> 回退 MobiPageSource（PDB 图片管线）
 * - CBR / RAR        -> RarPageSource（junrar，content URI 先复制到缓存目录）
 * - CB7 / 7Z         -> SevenZipPageSource（commons-compress SevenZipFile）
 * - CBT / TAR        -> TarPageSource（commons-compress TarFile）
 * - PDF              -> PdfPageSource（android.graphics.pdf.PdfRenderer）
 * - DIRECTORY        -> DirectoryPageSource（DocumentFile 目录内图片）
 * - UNKNOWN          -> IllegalArgumentException
 *
 * 样式：[open] 的 [NovelStyle] 重载仅在格式命中文本管线时生效；null 时由
 * NovelPageSource 用 displayMetrics 现算页面尺寸并套默认样式（黑底浅字 19f）。
 *
 * == 与上游的差异（本工程裁剪）==
 * 上游还有 `webdav://` 与 `gdrive://` 两条远程仓库路由（HttpRangeChannel 流式随机读）。
 * 本工程的数据源只有 wenku8（AGENTS §4.2），没有远程书库设施，故整条远程链路
 * ——连同 `RemoteArchiveSources` / `RemoteGdriveSources` / `HttpRangeChannel`——
 * 未引入。`book.uri` 因此只接受 `file://` / `/` 开头路径 / `content://` SAF 文档。
 */
object ArchiveFactory {

    /**
     * 打开一本书的页面源（默认样式）。
     * 打开过程涉及压缩包头解析、条目枚举等阻塞 IO，调用方应在 IO 线程调用；
     * 返回的 [PageSource] 的 renderPage 内部已自行切换到 IO 线程。
     */
    fun open(context: Context, book: BookEntity): PageSource = open(context, book, null)

    /**
     * 打开一本书的页面源，可为文字小说指定排版样式（字色/字号/页面尺寸）。
     * style 仅对文本管线（TXT / 文本型 EPUB / 文本版 MOBI）生效；其余格式与 null 等价。
     * 调用方应在 IO 线程调用（同 [open]）。
     */
    fun open(context: Context, book: BookEntity, style: NovelStyle?): PageSource {
        val format = runCatching { BookFormat.valueOf(book.format) }.getOrNull()
        return when (format) {
            BookFormat.TXT -> NovelPageSource.fromFile(context, book, style)
            BookFormat.CBZ -> ZipPageSource.open(context, book)
            BookFormat.EPUB ->
                openEpubTextOrFallback(
                    context, style,
                    openOwnedChannel = { openLocalChannel(context, book) },
                    fallback = { ZipPageSource.open(context, book) },
                )
            BookFormat.CBR -> RarPageSource.open(context, book)
            BookFormat.CB7 -> SevenZipPageSource.open(context, book)
            BookFormat.CBT -> TarPageSource.open(context, book)
            BookFormat.PDF -> PdfPageSource.open(context, book)
            BookFormat.MOBI, BookFormat.AZW3 ->
                openMobiTextOrFallback(
                    context, style,
                    openOwnedChannel = { openLocalChannel(context, book) },
                    fallback = { MobiPageSource.open(context, book) },
                )
            BookFormat.DIRECTORY -> DirectoryPageSource.open(context, book)
            BookFormat.UNKNOWN, null ->
                throw IllegalArgumentException("不支持的格式: ${book.format} ${book.uri}")
        }
    }

    // ------------------------------------------------------------------
    // 文本管线（TXT / EPUB 文本版 / MOBI 文本版）的打开与回退
    // ------------------------------------------------------------------

    /**
     * EPUB 打开：先走文本管线探测（[NovelTextExtractor.parseEpub]，container → OPF →
     * spine → 首个 xhtml 查 <img>）——命中文本版则构造 [NovelPageSource]（文本全量进内存后
     * 立即释放 zip 与通道）；抛 [EpubImageBasedException]（首个内容文档含 <img> / spine 无
     * 文本条目）则回退 [fallback]（现有 ZipPageSource 图片管线，自行重新打开文件）。
     * 其余异常（结构损坏等）回收资源后原样抛出。
     *
     * openOwnedChannel 返回（随机读通道, 通道外资源的释放动作，如 SAF pfd）。
     */
    private fun openEpubTextOrFallback(
        context: Context,
        style: NovelStyle?,
        openOwnedChannel: () -> Pair<SeekableByteChannel, () -> Unit>,
        fallback: () -> PageSource,
    ): PageSource {
        val (channel, release) = openOwnedChannel()
        var zip: ZipFile? = null
        try {
            zip = ZipFile(channel)
            val data = NovelTextExtractor.parseEpub(zip)
            val source = NovelPageSource.open(
                context, data.paragraphs, data.marks, style, data.coverBytes, data.imageGroups,
            )
            runCatching { zip.close() }
            runCatching { channel.close() }
            runCatching { release() }
            return source
        } catch (e: EpubImageBasedException) {
            runCatching { zip?.close() }
            runCatching { channel.close() }
            runCatching { release() }
            return fallback()
        } catch (t: Throwable) {
            runCatching { zip?.close() }
            runCatching { channel.close() }
            runCatching { release() }
            throw t
        }
    }

    /**
     * MOBI/AZW3 打开：探测（[NovelTextExtractor.probeMobi] 只读 PDB 头 + record0）→
     * 压缩可支持（无压缩/PalmDOC）+ 无加密 + 无图片资源 → 文本管线；抛
     * [MobiNotTextException]（HUFF/CDIC 压缩、DRM、图片版、无文本记录）则回退
     * [fallback]（现有 MobiPageSource 图片管线，保留其 DRM/图片行为与错误提示）。
     */
    private fun openMobiTextOrFallback(
        context: Context,
        style: NovelStyle?,
        openOwnedChannel: () -> Pair<SeekableByteChannel, () -> Unit>,
        fallback: () -> PageSource,
    ): PageSource {
        val (channel, release) = openOwnedChannel()
        try {
            val info = NovelTextExtractor.probeMobi(channel)
            info.requireTextCandidate()
            val html = NovelTextExtractor.extractMobiHtml(channel, info)
            val (paragraphs, marks) = NovelTextExtractor.mobiHtmlToBook(html)
            val source = NovelPageSource.open(context, paragraphs, marks, style)
            runCatching { channel.close() }
            runCatching { release() }
            return source
        } catch (e: MobiNotTextException) {
            runCatching { channel.close() }
            runCatching { release() }
            return fallback()
        } catch (t: Throwable) {
            runCatching { channel.close() }
            runCatching { release() }
            throw t
        }
    }

    /** 本地随机读通道：file:// 或 / 开头路径直接开 FileChannel；content:// 走 SAF pfd */
    private fun openLocalChannel(
        context: Context,
        book: BookEntity,
    ): Pair<SeekableByteChannel, () -> Unit> {
        val local = PageSources.resolveLocalFile(book)
        if (local != null) {
            val channel = FileInputStream(local).channel
            return channel to { runCatching { channel.close() } }
        }
        val pfd = PageSources.openPfd(context, book)
        val channel = FileInputStream(pfd.fileDescriptor).channel
        return channel to { runCatching { pfd.close() } }
    }
}

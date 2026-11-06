package com.example.hyperreader.epub

import com.example.hyperreader.model.ContentBlock
import com.example.hyperreader.model.DownloadedImage
import com.example.hyperreader.model.ParsedChapter
import java.util.Locale

/**
 * 导出前的归一化章节：章节正文已按导出需要切成「文本 / 富文本 / 图片」三种块，
 * 文件名也在这里一次性定好，后续 XML 生成与归档只读这份模型。
 */
internal data class ResolvedChapter(
    val id: String,
    val title: String,
    val volume: String,
    val fileName: String,
    val blocks: List<ResolvedBlock>,
)

internal sealed class ResolvedBlock {
    data class Text(val value: String) : ResolvedBlock()

    /** 已 sanitize 的内联强调 HTML（b/i/u/…，标签由解析期代码生成）。 */
    data class Rich(val html: String) : ResolvedBlock()

    data class Image(val image: DownloadedImage) : ResolvedBlock()
}

/**
 * 把解析结果对齐到导出模型。
 *
 * 插图按「所属章节 + 章内序号」匹配；下载失败的图在解析阶段已被丢弃，
 * 缺图告警由 `ExportJobManager.missingImageWarnings` 统一产出。
 */
internal object EpubChapterResolver {
    fun resolve(
        chapters: List<ParsedChapter>,
        images: List<DownloadedImage>,
    ): List<ResolvedChapter> = chapters.mapIndexed { index, chapter ->
        val byChapter = images.filter { it.sourceId == chapter.id }
        val blocks = chapter.blocks.mapNotNull { block ->
            when (block) {
                is ContentBlock.Text -> ResolvedBlock.Text(block.value)
                is ContentBlock.Rich -> ResolvedBlock.Rich(block.html)
                is ContentBlock.Image ->
                    byChapter.firstOrNull { it.chapterIndex == block.index }
                        ?.let { ResolvedBlock.Image(it) }
            }
        }
        ResolvedChapter(
            id = chapter.id,
            title = chapter.title,
            volume = chapter.volume,
            fileName = fileName(index),
            blocks = blocks,
        )
    }

    /** 固定宽度序号：目录、manifest 与 ZIP 条目共用，避免三处各拼一次。 */
    fun fileName(index: Int): String =
        "chapter-${String.format(Locale.ROOT, "%04d", index + 1)}.xhtml"
}

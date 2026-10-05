package com.example.hyperreader.epub

import com.example.hyperreader.model.Book
import com.example.hyperreader.model.ContentBlock
import com.example.hyperreader.model.DownloadedImage
import com.example.hyperreader.model.OutputFile
import com.example.hyperreader.model.ParsedChapter
import com.example.hyperreader.service.imageMimeFor
import io.nightfish.potatoepub.builder.EpubBuilder
import java.io.File
import java.util.Locale

/**
 * 第二种 EPUB 导出引擎：LightNovelReader 的 `:epub` 模块（`io.nightfish.potatoepub`，Apache-2.0）。
 *
 * 与自研 [EpubBuilder] 的关系：两者产出同样的 EPUB 3.3 结构，**调用契约完全一致**
 * （`build(book, chapters, images, cover, output)`），由设置里的「导出引擎」选择，
 * 见 [com.example.hyperreader.settings.EpubEngine]。
 *
 * == 与自研引擎的差异 ==
 * - 本引擎正文按**段落纯文本**写入，不保留行内强调（`ContentBlock.Rich` 的 HTML 会被
 *   当作文本处理）；自研引擎会原样写入已 sanitize 的内联标签。需要保留强调时用自研引擎。
 * - 目录用 `toc.ncx` + `nav.xhtml` 双份（与自研引擎一致），但层级结构由 potatoepub 生成。
 * - 封面必须是 jpg；封面是其它格式时跳过封面图（不阻断导出）。
 */
class PotatoEpubBuilder {

    fun build(
        book: Book,
        chapters: List<ParsedChapter>,
        images: List<DownloadedImage>,
        cover: DownloadedImage?,
        output: File,
    ): OutputFile {
        val builder = EpubBuilder().apply {
            id = book.id?.takeIf(String::isNotBlank) ?: "wenku8-${book.sourceUrl.hashCode()}"
            title = book.title
            language = Locale.SIMPLIFIED_CHINESE
            titleLang = Locale.SIMPLIFIED_CHINESE
            creator = book.author
            description = book.summary.takeIf(String::isNotBlank)
            publisher = book.category.takeIf(String::isNotBlank)
            manifestId = "epub"
            spineId = "spine"
            // **必填**：EpubBuilder.build() 里是 `modifier ?: throw Error("Missing 'modifier'")`，
            // 不设它每次导出都会直接失败（表现为「LNR 引擎无法导出」）。
            // 语义是 OPF 的 dcterms:modified，取导出时刻即可。
            modifier = java.time.LocalDateTime.now()
        }

        // 封面：potatoepub 的 cover() 只接受 jpg，非 jpg 直接跳过（不阻断整次导出）。
        // 跳过时必须把默认清单里的 cover.jpg 撤掉——resFiles 里没有这个文件，
        // OPF 却声明了它，epubcheck 报「manifest 引用不存在的资源」，
        // Calibre / Apple Books 会提示「书籍损坏」。本应用自读看不出来，
        // 只有用户把文件发出去时才爆。
        val usableCover = cover?.takeIf {
            it.mime == "image/jpeg" || it.ext.equals("jpg", true) || it.ext.equals("jpeg", true)
        }
        if (usableCover != null) {
            runCatching { builder.cover(File(usableCover.localPath)) }
        } else {
            builder.manifestItems.removeIf { it.href == "cover.jpg" }
        }

        var imageSeq = 0
        chapters.forEach { chapter ->
            // 与自研引擎同一套匹配规则：插图按「所属章节 + 章内序号」对齐
            val byChapter = images.filter { it.sourceId == chapter.id }
            builder.chapter {
                title(chapter.title)
                content {
                    title(chapter.title)
                    chapter.blocks.forEach { block ->
                        when (block) {
                            is ContentBlock.Text -> text(block.value)
                            is ContentBlock.Rich -> text(block.html)
                            is ContentBlock.Image ->
                                byChapter.firstOrNull { it.chapterIndex == block.index }?.let { image ->
                                    image(
                                        image = File(image.localPath),
                                        id = "img_${imageSeq++}",
                                        src = "images/${image.fileName}",
                                        mime = imageMimeFor(image.ext),
                                    )
                                }
                        }
                    }
                }
            }
        }

        val epub = builder.build()
        epub.save(output)
        return OutputFile(
            name = output.name,
            size = output.length(),
            uri = "",
            sourcePath = output.absolutePath,
        )
    }
}

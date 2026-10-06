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
 * - 目录用 `toc.ncx` + `nav.xhtml` 双份（与自研引擎一致），但层级结构由 potatoepub 生成。
 * - 封面必须是 jpg；封面是其它格式时跳过封面图（不阻断导出）。
 * - 章节 id 由本工程按序号指定（`chapter_1`、`chapter_2`…），不沿用 potatoepub 的
 *   hash 派生公式 —— 后者会让标题与内容都相同的章节撞 id，成品里少一章（见 [Chapter.idOverride]）。
 * - 正文结构与自研引擎一致：段落按 `<p>` 切分，`ContentBlock.Rich` 的行内强调
 *   （`b` / `i` / `u` / `sup` / `sub`）写成真标签而不是被转义成字面量。
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
        chapters.forEachIndexed { index, chapter ->
            // 与自研引擎同一套匹配规则：插图按「所属章节 + 章内序号」对齐
            val byChapter = images.filter { it.sourceId == chapter.id }
            builder.chapter {
                title(chapter.title)
                // 显式 id：potatoepub 默认按「内容 + 标题」的 hash 派生，
                // 标题与内容都相同的章节会撞同一个 id —— manifest 与 spine 出现重复条目，
                // `documents` map 后写覆盖先写，成品里直接少一章。按序号指定即彻底避免。
                id("chapter_${index + 1}")
                content {
                    title(chapter.title)
                    chapter.blocks.forEach { block ->
                        when (block) {
                            // 段落必须落在 <p> 里：potatoepub 的 text() 只是往
                            // <div id="content"> 追加裸文本，整章会塌成一个大段落。
                            is ContentBlock.Text -> paragraph(block.value)
                            // 已 sanitize 的行内强调写成真标签，转换逻辑见 RichParagraphs
                            is ContentBlock.Rich -> RichParagraphs.append(paragraphElement(), block.html)
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

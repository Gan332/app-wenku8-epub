package com.example.hyperreader.epub

import com.example.hyperreader.core.Wenku8Exception
import com.example.hyperreader.model.Book
import com.example.hyperreader.model.DownloadedImage
import com.example.hyperreader.model.OutputFile
import com.example.hyperreader.model.ParsedChapter
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipOutputStream

/**
 * 自研 EPUB 3 导出引擎（设置里的「自研引擎」）。
 *
 * 本类只保留**编排**：归一化章节 → 生成 manifest/spine → 依次写容器、目录与正文 →
 * 原子替换目标文件。具体职责拆到：
 * - [EpubChapterResolver]：解析结果 → 导出模型
 * - [EpubPackageBuilder]：manifest / spine
 * - [EpubDocumentWriter]：OPF、nav、NCX、XHTML 与 CSS（纯字符串）
 * - [EpubArchiveWriter]：ZIP 条目（mimetype 必须首个且 STORED）
 *
 * 拆分后单元测试可以直接断言结构与转义，不需要先落盘再解压。
 */
class EpubBuilder : EpubPackager {

    override fun build(
        book: Book,
        chapters: List<ParsedChapter>,
        images: List<DownloadedImage>,
        cover: DownloadedImage?,
        output: File,
    ): OutputFile {
        val resolved = EpubChapterResolver.resolve(chapters, images)
        val manifest = EpubPackageBuilder.manifest(resolved, cover, images)
        val spine = EpubPackageBuilder.spine(resolved)
        val temporary = File(output.parentFile, "${output.name}.tmp")

        try {
            FileOutputStream(temporary).use { fileOut ->
                ZipOutputStream(fileOut, StandardCharsets.UTF_8).use { zip ->
                    val archive = EpubArchiveWriter(zip)
                    archive.writeMimetype()
                    archive.writeText("META-INF/container.xml", EpubDocumentWriter.containerXml())
                    archive.writeText(
                        "EPUB/package.opf",
                        EpubDocumentWriter.packageXml(book, manifest, spine),
                    )
                    archive.writeText("EPUB/nav.xhtml", EpubDocumentWriter.navXml(resolved))
                    archive.writeText("EPUB/toc.ncx", EpubDocumentWriter.ncxXml(book, resolved))
                    archive.writeText("EPUB/text/style.css", EpubDocumentWriter.CSS)
                    archive.writeText("EPUB/text/title.xhtml", EpubDocumentWriter.titleXml(book, cover))
                    resolved.forEach { chapter ->
                        archive.writeText(
                            "EPUB/text/${chapter.fileName}",
                            EpubDocumentWriter.chapterXml(chapter),
                        )
                    }
                    (listOfNotNull(cover) + images).forEach { image ->
                        archive.writeFile("EPUB/images/${image.fileName}", File(image.localPath))
                    }
                }
            }
            replace(temporary, output)
        } catch (failure: Throwable) {
            // 失败时别把半截临时包留在输出目录（旧实现会残留 .tmp）
            temporary.delete()
            throw failure
        }

        return OutputFile(output.name, output.length(), output.toURI().toString(), output.absolutePath)
    }

    /** 原子替换：先删旧文件，rename 失败再退化为复制，避免留下只写了一半的成品。 */
    private fun replace(temporary: File, output: File) {
        if (output.exists() && !output.delete()) {
            throw Wenku8Exception("无法覆盖已有 EPUB。", "EPUB_REPLACE_FAILED")
        }
        if (!temporary.renameTo(output)) {
            temporary.copyTo(output, overwrite = true)
            temporary.delete()
        }
    }

    companion object {
        /** 兼容既有单测（`EpubBuilder.CSS`）与外部读取，真实定义在文档生成器里。 */
        internal val CSS = EpubDocumentWriter.CSS
    }
}

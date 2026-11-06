package com.example.hyperreader.epub

import com.example.hyperreader.model.Book
import com.example.hyperreader.model.DownloadedImage
import com.example.hyperreader.model.OutputFile
import com.example.hyperreader.model.ParsedChapter
import java.io.File

/**
 * EPUB 打包引擎的统一契约。
 *
 * 自研 [EpubBuilder] 与第三方的 [PotatoEpubBuilder] 都实现它，`ExportJobManager`
 * 只依赖本接口，不再用 `if (engine == POTATO)` 把两套调用点散在任务流程里。
 * 新增引擎时只需实现接口并注册，导出/进度/落盘链路无需改动。
 */
interface EpubPackager {
    /**
     * 把已解析的章节与已下载的图片写成 EPUB 文件。
     *
     * 实现方负责保证 `output` 要么是完整成品，要么保持原样（失败时清理临时文件）。
     */
    fun build(
        book: Book,
        chapters: List<ParsedChapter>,
        images: List<DownloadedImage>,
        cover: DownloadedImage?,
        output: File,
    ): OutputFile
}

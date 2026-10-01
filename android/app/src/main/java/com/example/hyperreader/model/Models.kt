package com.example.hyperreader.model

import kotlinx.serialization.Serializable

@Serializable
data class Book(
    val id: String? = null,
    val title: String,
    val author: String = "未知作者",
    val category: String = "轻小说",
    val status: String = "",
    val updatedAt: String = "",
    val wordCount: Long? = null,
    val latestChapter: String = "",
    val isComplete: Boolean = false,
    val tags: List<String> = emptyList(),
    val summary: String = "",
    val coverUrl: String? = null,
    val sourceUrl: String,
    val bookUrl: String,
    val directoryUrl: String? = null,
)

@Serializable
data class Chapter(
    val id: String,
    val title: String,
    val url: String,
    val volume: String = "正文",
    val order: Int,
    val isIllustration: Boolean = false,
)

@Serializable
data class BookIndex(
    val title: String,
    val url: String,
    val bookId: String? = null,
    val chapters: List<Chapter>,
)

@Serializable
sealed class ContentBlock {
    @Serializable
    data class Text(val value: String) : ContentBlock()

    @Serializable
    data class Image(val index: Int) : ContentBlock()

    /**
     * 携带**已 sanitize 的内联 HTML** 的富文本段落（仅导出链路产生，
     * 见 `Wenku8Parser.parseChapter(preserveInlineFormat = true)`）。
     *
     * 白名单只允许 b/strong/i/em/u/s/sup/sub 等强调标签；
     * 在线阅读端出现时降级为纯文本（Jsoup.text()），EPUB 导出端原样写入。
     */
    @Serializable
    data class Rich(val html: String) : ContentBlock()
}

@Serializable
data class ParsedChapter(
    val id: String,
    val title: String,
    val volume: String,
    val order: Int,
    val sourceUrl: String,
    val imageUrls: List<String> = emptyList(),
    val blocks: List<ContentBlock> = emptyList(),
    val textLength: Int = 0,
)

@Serializable
enum class JobStatus { queued, running, completed, failed, canceled }

@Serializable
enum class JobPhase { queued, fetching, images, cover, packaging, completed, failed, canceled }

@Serializable
data class JobProgress(
    val phase: JobPhase = JobPhase.queued,
    val percent: Int = 0,
    val completed: Int = 0,
    val total: Int = 0,
    val imageCompleted: Int = 0,
    val message: String = "等待开始…",
    val currentTitle: String = "",
    /** 插图总数（随章节抓取逐步累计为真实值）；0 = 尚未知。 */
    val imageTotal: Int = 0,
    /** 预计剩余秒数；-1 = 尚不可估。 */
    val etaSeconds: Int = -1,
    /** 命中导出缓存的项数（正文页 + 插图），命中即 0 请求。 */
    val cacheHits: Int = 0,
)

@Serializable
data class JobError(val code: String, val message: String)

@Serializable
data class ExportOptions(val includeCover: Boolean = true)

@Serializable
data class OutputFile(
    val name: String,
    val size: Long,
    val uri: String,
    val sourcePath: String = "",
)

@Serializable
data class ExportJob(
    val id: String,
    val status: JobStatus = JobStatus.queued,
    val book: Book,
    val chapterCount: Int,
    val requestedChapters: List<Chapter> = emptyList(),
    val options: ExportOptions = ExportOptions(),
    val progress: JobProgress = JobProgress(),
    val createdAt: String,
    val updatedAt: String,
    val finishedAt: String? = null,
    val error: JobError? = null,
    val warnings: List<String> = emptyList(),
    val output: OutputFile? = null,
    val imageCount: Int = 0,
    /**
     * 打包用的 EPUB 导出引擎。默认自研；书籍详情页的两个导出按钮可**按任务**指定，
     * 不受全局设置影响（见 `EpubEngine`）。旧任务记录缺该字段时按默认值反序列化。
     */
    val engine: com.example.hyperreader.settings.EpubEngine = com.example.hyperreader.settings.EpubEngine.CLASSIC,
)

@Serializable
data class DownloadedImage(
    val chapterId: String,
    val sourceId: String,
    val chapterIndex: Int,
    val globalIndex: Int,
    val fileName: String,
    val manifestId: String,
    val mime: String,
    val localPath: String,
    val ext: String,
    val bytes: Long,
    val isCover: Boolean = false,
)

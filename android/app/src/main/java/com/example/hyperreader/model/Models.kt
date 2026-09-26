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

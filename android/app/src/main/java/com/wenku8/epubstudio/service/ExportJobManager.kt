package com.wenku8.epubstudio.service

import android.content.Context
import android.net.Uri
import com.wenku8.epubstudio.core.SourceKind
import com.wenku8.epubstudio.core.Wenku8Exception
import com.wenku8.epubstudio.core.Wenku8HttpClient
import com.wenku8.epubstudio.core.Wenku8Parser
import com.wenku8.epubstudio.core.Wenku8Url
import com.wenku8.epubstudio.data.JobRepository
import com.wenku8.epubstudio.epub.EpubBuilder
import com.wenku8.epubstudio.file.EpubFileStore
import com.wenku8.epubstudio.model.Book
import com.wenku8.epubstudio.model.BookIndex
import com.wenku8.epubstudio.model.Chapter
import com.wenku8.epubstudio.model.ContentBlock
import com.wenku8.epubstudio.model.DownloadedImage
import com.wenku8.epubstudio.model.ExportJob
import com.wenku8.epubstudio.model.ExportOptions
import com.wenku8.epubstudio.model.JobError
import com.wenku8.epubstudio.model.JobPhase
import com.wenku8.epubstudio.model.JobProgress
import com.wenku8.epubstudio.model.JobStatus
import com.wenku8.epubstudio.model.OutputFile
import com.wenku8.epubstudio.model.ParsedChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.text.normalize
import java.io.File
import java.text.Normalizer
import java.time.Instant
import java.util.UUID

class ExportJobManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = JobRepository(context.filesDir)
    private val http = Wenku8HttpClient(File(context.cacheDir, "wenku8"))
    private val fileStore = EpubFileStore(context)
    private val epubBuilder = EpubBuilder()
    private val outputDirectory = File(context.filesDir, "output").apply { mkdirs() }
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val running = mutableMapOf<String, Job>()
    private val state = MutableStateFlow<Map<String, ExportJob>>(emptyMap())

    val jobs: StateFlow<Map<String, ExportJob>> = state.asStateFlow()

    init {
        scope.launch {
            val loaded = repository.loadAll()
            state.value = loaded.associateBy { it.id }
            for (job in loaded) if (job.status == JobStatus.queued || job.status == JobStatus.running) queue.send(job.id)
        }
        scope.launch {
            for (id in queue) runCatching { execute(id) }
        }
    }

    suspend fun parseBook(input: String): Book {
        val requested = Wenku8Url.normalizeSource(input)
        val ids = Wenku8Url.sourceIds(requested)
        val url = if (ids.kind == SourceKind.index) "https://www.wenku8.net/book/${ids.bookId}.htm" else requested.toString()
        val page = http.fetchText(url, "parse-${UUID.randomUUID()}")
        return Wenku8Url.validateBook(Wenku8Parser.parseBook(page.html, page.finalUrl, if (ids.kind == SourceKind.index) requested.toString() else null))
    }

    suspend fun parseSource(input: String): Pair<Book, BookIndex> {
        val book = parseBook(input)
        val directory = book.directoryUrl ?: input
        val ids = Wenku8Url.sourceIds(Wenku8Url.assertAllowed(directory))
        val page = http.fetchText(directory, "parse-${UUID.randomUUID()}")
        return book to Wenku8Parser.parseIndex(page.html, page.finalUrl, ids.bookId)
    }

    suspend fun parseIndex(input: String): BookIndex {
        var url = Wenku8Url.normalizeSource(input)
        var ids = Wenku8Url.sourceIds(url)
        if (ids.kind != SourceKind.index) {
            val book = parseBook(input)
            url = Wenku8Url.assertAllowed(book.directoryUrl ?: throw Wenku8Exception("书籍页没有目录链接。", "INDEX_URL_REQUIRED"))
            ids = Wenku8Url.sourceIds(url)
        }
        val page = http.fetchText(url.toString(), "parse-${UUID.randomUUID()}")
        return Wenku8Parser.parseIndex(page.html, page.finalUrl, ids.bookId)
    }

    suspend fun create(book: Book, chapters: List<Chapter>, includeCover: Boolean): ExportJob {
        val validatedChapters = Wenku8Url.validateChapters(chapters)
        val now = Instant.now().toString()
        val job = ExportJob(
            id = "${now.hashCode().toUInt().toString(36)}-${UUID.randomUUID().toString().take(8)}",
            status = JobStatus.queued,
            book = Wenku8Url.validateBook(book),
            chapterCount = validatedChapters.size,
            requestedChapters = validatedChapters,
            options = ExportOptions(includeCover),
            progress = JobProgress(total = validatedChapters.size),
            createdAt = now,
            updatedAt = now,
        )
        update(job)
        queue.send(job.id)
        return job
    }

    fun cancel(id: String) {
        val job = state.value[id] ?: return
        if (job.status != JobStatus.queued && job.status != JobStatus.running) return
        running[id]?.cancel(CancellationException("用户取消"))
        http.cancelJob(id)
        scope.launch {
            val canceled = job.copy(status = JobStatus.canceled, finishedAt = Instant.now().toString(), error = JobError("CANCELED", "任务已取消。"), progress = job.progress.copy(phase = JobPhase.canceled, message = "任务已取消"))
            update(canceled)
            ExportNotificationService.stop(context)
        }
    }

    fun save(id: String): Uri {
        val job = state.value[id] ?: throw Wenku8Exception("任务不存在。", "JOB_NOT_FOUND")
        val output = job.output ?: throw Wenku8Exception("EPUB 尚未生成完成。", "EPUB_NOT_READY")
        return fileStore.save(File(output.sourcePath), output.name)
    }

    fun share(id: String) {
        val job = state.value[id] ?: return
        val output = job.output ?: return
        fileStore.share(Uri.parse(output.uri), output.name)
    }

    private suspend fun execute(id: String) {
        val initial = state.value[id] ?: return
        if (initial.status != JobStatus.queued && initial.status != JobStatus.running) return
        val task = scope.launch { runJob(initial) }
        running[id] = task
        task.join()
        running.remove(id)
    }

    private suspend fun runJob(initial: ExportJob) {
        var job = initial.copy(status = JobStatus.running, progress = initial.progress.copy(phase = JobPhase.fetching, percent = 1, message = "开始获取章节…"))
        val warnings = mutableListOf<String>()
        val parsed = mutableListOf<ParsedChapter>()
        val images = mutableListOf<DownloadedImage>()
        var completed = 0
        var globalImage = 0
        var cover: DownloadedImage? = null
        ExportNotificationService.start(context, job.id, job.book.title, job.progress.message, job.progress.percent)
        try {
            for (chapter in initial.requestedChapters) {
                val baseline = completed.toDouble() / initial.requestedChapters.size.coerceAtLeast(1)
                job = job.copy(progress = job.progress.copy(phase = JobPhase.fetching, percent = (baseline * 94).toInt(), completed = completed, currentTitle = chapter.title, message = "正在下载：${chapter.title}"))
                update(job)
                try {
                    val page = http.fetchText(chapter.url, job.id)
                    val parsedChapter = Wenku8Parser.parseChapter(page.html, chapter, page.finalUrl)
                    parsed += parsedChapter
                    parsedChapter.imageUrls.forEachIndexed { index, imageUrl ->
                        val progress = index.toDouble() / parsedChapter.imageUrls.size.coerceAtLeast(1)
                        job = job.copy(progress = job.progress.copy(phase = JobPhase.images, percent = (baseline * 92 + progress * 92 / initial.requestedChapters.size.coerceAtLeast(1)).toInt().coerceAtMost(92), imageCompleted = images.size, message = "正在下载插图 ${index + 1}/${parsedChapter.imageUrls.size}"))
                        update(job)
                        runCatching {
                            val downloaded = http.downloadImage(imageUrl, parsedChapter.sourceUrl, job.id, "chapter-${globalImage + index + 1}")
                            images += DownloadedImage(parsedChapter.id, parsedChapter.id, index, globalImage + index, "image-${String.format("%04d", globalImage + index + 1)}.${downloaded.ext}", "image-${globalImage + index + 1}", downloaded.mime, downloaded.path, downloaded.ext, downloaded.bytes)
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            warnings += "插图 ${index + 1}/${parsedChapter.imageUrls.size}：${error.message ?: "下载失败"}"
                        }
                    }
                    globalImage += parsedChapter.imageUrls.size
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    warnings += "${chapter.title}：${error.message ?: "章节处理失败"}"
                }
                completed += 1
                job = job.copy(warnings = warnings.toList(), progress = job.progress.copy(phase = JobPhase.fetching, percent = (completed.toDouble() / initial.requestedChapters.size.coerceAtLeast(1) * 94).toInt().coerceAtMost(94), completed = completed, message = "已处理 $completed/${initial.requestedChapters.size} 个章节"))
                update(job)
            }
            if (parsed.isEmpty()) throw Wenku8Exception("所有章节都未能成功读取。", "NO_CHAPTERS_PARSED")
            val coverUrl = job.book.coverUrl
            if (job.options.includeCover && coverUrl != null) {
                job = job.copy(progress = job.progress.copy(phase = JobPhase.cover, percent = 94, message = "正在下载书籍封面…"))
                update(job)
                runCatching {
                    val downloaded = http.downloadImage(coverUrl, job.book.bookUrl, job.id, "cover")
                    cover = DownloadedImage("", "", 0, 0, "cover.${downloaded.ext}", "cover-image", downloaded.mime, downloaded.path, downloaded.ext, downloaded.bytes, true)
                }.onFailure { warnings += "封面：${it.message ?: "下载失败"}" }
            }
            job = job.copy(progress = job.progress.copy(phase = JobPhase.packaging, percent = 97, message = "正在写入 EPUB 容器…"))
            update(job)
            val output = File(outputDirectory, "${bookSafeName(job.book.title)}-${job.id}.epub")
            val built = epubBuilder.build(job.book, parsed, images, cover, output)
            val savedUri = fileStore.save(File(built.sourcePath), built.name)
            job = job.copy(
                status = JobStatus.completed,
                finishedAt = Instant.now().toString(),
                warnings = warnings.toList(),
                output = built.copy(uri = savedUri.toString()),
                imageCount = images.size + if (cover == null) 0 else 1,
                progress = job.progress.copy(phase = JobPhase.completed, percent = 100, completed = completed, imageCompleted = images.size, currentTitle = "", message = "EPUB 已生成"),
            )
            update(job)
        } catch (error: Throwable) {
            if (error is CancellationException) {
                update(job.copy(status = JobStatus.canceled, finishedAt = Instant.now().toString(), error = JobError("CANCELED", "任务已取消。"), warnings = warnings.toList(), progress = job.progress.copy(phase = JobPhase.canceled, message = "任务已取消")))
            } else {
                val normalized = error as? Wenku8Exception ?: Wenku8Exception(error.message ?: "生成失败。", "EXPORT_FAILED", error)
                update(job.copy(status = JobStatus.failed, finishedAt = Instant.now().toString(), error = JobError(normalized.code, normalized.message.orEmpty()), warnings = warnings.toList(), progress = job.progress.copy(phase = JobPhase.failed, message = normalized.message.orEmpty())))
            }
        } finally {
            File(context.cacheDir, "wenku8/${job.id.replace(Regex("[^A-Za-z0-9_-]"), "_")}").deleteRecursively()
            ExportNotificationService.stop(context)
        }
    }

    private fun bookSafeName(value: String): String = value.normalize(Normalizer.Form.NFKC).replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001f]"), "_").trim().take(90).ifBlank { "轻小说" }

    private suspend fun update(job: ExportJob) {
        val next = job.copy(updatedAt = Instant.now().toString())
        repository.save(next)
        state.value = state.value + (next.id to next)
        if (next.status == JobStatus.running || next.status == JobStatus.queued) ExportNotificationService.update(context, next.id, next.book.title, next.progress.message, next.progress.percent)
    }

    fun get(id: String): ExportJob? = state.value[id]
}

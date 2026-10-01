package com.example.hyperreader.service

import android.content.Context
import android.net.Uri
import com.example.hyperreader.core.SourceKind
import com.example.hyperreader.core.Wenku8SessionStore
import com.example.hyperreader.core.Wenku8Exception
import com.example.hyperreader.core.Wenku8HttpClient
import com.example.hyperreader.core.Wenku8Parser
import com.example.hyperreader.core.Wenku8Url
import com.example.hyperreader.data.JobRepository
import com.example.hyperreader.epub.EpubBuilder
import com.example.hyperreader.file.EpubFileStore
import com.example.hyperreader.model.Book
import com.example.hyperreader.settings.EpubEngine
import com.example.hyperreader.model.BookIndex
import com.example.hyperreader.model.Chapter
import com.example.hyperreader.model.ContentBlock
import com.example.hyperreader.model.DownloadedImage
import com.example.hyperreader.model.ExportJob
import com.example.hyperreader.model.ExportOptions
import com.example.hyperreader.model.JobError
import com.example.hyperreader.model.JobPhase
import com.example.hyperreader.model.JobProgress
import com.example.hyperreader.model.JobStatus
import com.example.hyperreader.model.OutputFile
import com.example.hyperreader.model.ParsedChapter
import com.example.hyperreader.ui.isOngoing
import com.example.hyperreader.ui.formatEta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.Normalizer
import java.time.Instant
import java.util.UUID

class ExportJobManager(private val context: Context, sessionStore: Wenku8SessionStore? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = JobRepository(context.filesDir)
    private val http = Wenku8HttpClient(File(context.cacheDir, "wenku8"), sessionStore?.cookieJar())
    private val fileStore = EpubFileStore(context)
    private val epubBuilder = EpubBuilder()

    /** 第二种导出引擎（设置里可切换），见 [EpubEngine]。 */
    private val potatoEpubBuilder = com.example.hyperreader.epub.PotatoEpubBuilder()
    private val settings = com.example.hyperreader.settings.SettingsRepository(context)
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
            ExportNotificationService.finish(context, canceled.id, canceled.book.title, canceled.progress.message, canceled.status)
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
        // 导出缓存（URL 内容寻址）：正文页与插图命中即 0 请求 —— 重复导出接近秒级完成。
        val cache = ExportCache(File(context.cacheDir, "wenku8-export"))
        // 同一 URL 的插图在本次任务内只落盘/下载一次（跨章复用）。
        val assets = HashMap<String, ImageAsset>()
        var cacheHits = 0
        var imageTotal = 0
        ExportNotificationService.start(context, job.id, job.book.title, job.progress.message, job.progress.percent)
        try {
            for (chapter in initial.requestedChapters) {
                val baseline = completed.toDouble() / initial.requestedChapters.size.coerceAtLeast(1)
                job = job.copy(progress = job.progress.copy(phase = JobPhase.fetching, percent = (baseline * 94).toInt(), completed = completed, currentTitle = chapter.title, message = "正在下载：${chapter.title}", cacheHits = cacheHits))
                update(job)
                try {
                    // 缓存命中直接解析缓存原文；未命中走网络，成功后存**原始 HTML**
                    //（存 raw 而非解析结果：解析逻辑升级后缓存依然有效）。
                    val parsedChapter = cache.page(chapter.url)?.let { cachedHtml ->
                        cacheHits++
                        Wenku8Parser.parseChapter(cachedHtml, chapter, chapter.url, preserveInlineFormat = true)
                    } ?: run {
                        val page = http.fetchText(chapter.url, job.id)
                        val fresh = Wenku8Parser.parseChapter(page.html, chapter, page.finalUrl, preserveInlineFormat = true)
                        cache.putPage(chapter.url, page.html)
                        fresh
                    }
                    parsed += parsedChapter
                    imageTotal += parsedChapter.imageUrls.size
                    parsedChapter.imageUrls.forEachIndexed { index, imageUrl ->
                        val progress = index.toDouble() / parsedChapter.imageUrls.size.coerceAtLeast(1)
                        job = job.copy(progress = job.progress.copy(
                            phase = JobPhase.images,
                            percent = (baseline * 92 + progress * 92 / initial.requestedChapters.size.coerceAtLeast(1)).toInt().coerceAtMost(92),
                            imageCompleted = images.size,
                            imageTotal = imageTotal,
                            cacheHits = cacheHits,
                            message = "正在下载插图 ${index + 1}/${parsedChapter.imageUrls.size}",
                        ))
                        update(job)
                        runCatching {
                            val asset = assets.getOrPut(imageUrl) {
                                cache.image(imageUrl)?.let { cachedFile ->
                                    cacheHits++
                                    ImageAsset(cachedFile.absolutePath, cachedFile.extension, imageMimeFor(cachedFile.extension), cachedFile.length())
                                } ?: run {
                                    val downloaded = http.downloadImage(imageUrl, parsedChapter.sourceUrl, job.id, "chapter-${globalImage + index + 1}")
                                    val stored = cache.putImage(imageUrl, File(downloaded.path), downloaded.ext)
                                    ImageAsset(stored?.absolutePath ?: downloaded.path, downloaded.ext, downloaded.mime, downloaded.bytes)
                                }
                            }
                            images += DownloadedImage(parsedChapter.id, parsedChapter.id, index, globalImage + index, "image-${String.format("%04d", globalImage + index + 1)}.${asset.ext}", "image-${globalImage + index + 1}", asset.mime, asset.localPath, asset.ext, asset.bytes)
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
                job = job.copy(warnings = warnings.toList(), progress = job.progress.copy(phase = JobPhase.fetching, percent = (completed.toDouble() / initial.requestedChapters.size.coerceAtLeast(1) * 94).toInt().coerceAtMost(94), completed = completed, imageTotal = imageTotal, cacheHits = cacheHits, message = "已处理 $completed/${initial.requestedChapters.size} 个章节"))
                update(job)
            }
            // 下载失败的插图在 EpubBuilder.resolveChapters 里会被**静默丢弃**，
            // 任务却显示成功 —— 把缺图变成明确警告（复用「查看 N 条警告」入口）。
            warnings += missingImageWarnings(parsed, images)
            if (parsed.isEmpty()) throw Wenku8Exception("所有章节都未能成功读取。", "NO_CHAPTERS_PARSED")
            val coverUrl = job.book.coverUrl
            if (job.options.includeCover && coverUrl != null) {
                job = job.copy(progress = job.progress.copy(phase = JobPhase.cover, percent = 94, cacheHits = cacheHits, imageTotal = imageTotal, message = "正在下载书籍封面…"))
                update(job)
                runCatching {
                    val asset = cache.image(coverUrl)?.let { cachedFile ->
                        cacheHits++
                        ImageAsset(cachedFile.absolutePath, cachedFile.extension, imageMimeFor(cachedFile.extension), cachedFile.length())
                    } ?: run {
                        val downloaded = http.downloadImage(coverUrl, job.book.bookUrl, job.id, "cover")
                        val stored = cache.putImage(coverUrl, File(downloaded.path), downloaded.ext)
                        ImageAsset(stored?.absolutePath ?: downloaded.path, downloaded.ext, downloaded.mime, downloaded.bytes)
                    }
                    cover = DownloadedImage("", "", 0, 0, "cover.${asset.ext}", "cover-image", asset.mime, asset.localPath, asset.ext, asset.bytes, true)
                }.onFailure { warnings += "封面：${it.message ?: "下载失败"}" }
            }
            job = job.copy(progress = job.progress.copy(phase = JobPhase.packaging, percent = 97, cacheHits = cacheHits, imageTotal = imageTotal, message = "正在写入 EPUB 容器…"))
            update(job)
            val output = File(outputDirectory, "${bookSafeName(job.book.title)}-${job.id}.epub")
            // 导出引擎在设置里切换（0.14.0）：两个引擎调用契约一致，只换实现
            val engine = settings.exportEngine.first()
            val built = if (engine == EpubEngine.POTATO) {
                potatoEpubBuilder.build(job.book, parsed, images, cover, output)
            } else {
                epubBuilder.build(job.book, parsed, images, cover, output)
            }
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
            // 成功后裁剪缓存容量；失败不裁剪，让重试继续命中。
            runCatching { cache.trim(CACHE_TRIM_BYTES) }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                update(job.copy(status = JobStatus.canceled, finishedAt = Instant.now().toString(), error = JobError("CANCELED", "任务已取消。"), warnings = warnings.toList(), progress = job.progress.copy(phase = JobPhase.canceled, message = "任务已取消")))
            } else {
                val normalized = error as? Wenku8Exception ?: Wenku8Exception(error.message ?: "生成失败。", "EXPORT_FAILED", error)
                update(job.copy(status = JobStatus.failed, finishedAt = Instant.now().toString(), error = JobError(normalized.code, normalized.message.orEmpty()), warnings = warnings.toList(), progress = job.progress.copy(phase = JobPhase.failed, message = normalized.message.orEmpty())))
            }
        } finally {
            File(context.cacheDir, "wenku8/${job.id.replace(Regex("[^A-Za-z0-9_-]"), "_")}").deleteRecursively()
            // 队列是单消费者串行执行的，任务收尾时不会与其它任务的前台通知冲突。
            // 用 finish 而非 stop，让用户能看到完成态并点击进入导出记录。
            val finished = state.value[job.id]
            if (finished != null && !finished.status.isOngoing()) {
                ExportNotificationService.finish(context, finished.id, finished.book.title, finished.progress.message, finished.status)
            } else {
                ExportNotificationService.stop(context)
            }
        }
    }

    private fun bookSafeName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC).replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001f]"), "_").trim().take(90).ifBlank { "轻小说" }

    /**
     * 进度落盘/通知节流。
     *
     * 内存 `state` **每次即时更新**（UI 不受影响）；DataStore 全量 JSON 写与通知 IPC
     * 最多每 [PERSIST_INTERVAL_MS] 一次 —— 旧实现每张插图各落盘一次，
     * 大图册光是序列化与通知开销就相当可观。阶段变化与终态强制立即落盘。
     */
    private var lastPersistedAt = 0L
    private var lastPersistedPhase = JobPhase.queued

    private suspend fun update(job: ExportJob) {
        val eta = if (job.status.isOngoing() && job.progress.phase != JobPhase.packaging) {
            estimateEtaSeconds(job.progress.completed, job.chapterCount, job.progress.imageCompleted, job.progress.imageTotal)
        } else {
            -1
        }
        val next = job.copy(updatedAt = Instant.now().toString(), progress = job.progress.copy(etaSeconds = eta))
        state.value = state.value + (next.id to next)
        val terminal = !next.status.isOngoing()
        val phaseChanged = next.progress.phase != lastPersistedPhase
        val due = terminal || phaseChanged || System.currentTimeMillis() - lastPersistedAt >= PERSIST_INTERVAL_MS
        if (!due) return
        lastPersistedAt = System.currentTimeMillis()
        lastPersistedPhase = next.progress.phase
        repository.save(next)
        if (next.status == JobStatus.running || next.status == JobStatus.queued) {
            val notificationMessage = if (eta >= 0) "${next.progress.message}（剩余约 ${formatEta(eta)}）" else next.progress.message
            ExportNotificationService.update(context, next.id, next.book.title, notificationMessage, next.progress.percent)
        }
    }

    /**
     * 预计剩余秒数（纯函数，可单测）：剩余请求数 × 限流节奏（~1s/请求）+ 打包余量。
     * 图片总数是**累计已知值**（边抓边加），因此前期估算偏乐观、后期收敛。
     * 无剩余请求时返回 -1（不可估）。
     */
    internal companion object {
        const val PERSIST_INTERVAL_MS = 250L

        /** 导出缓存上限：512MB（成功导出后按新旧淘汰）。 */
        const val CACHE_TRIM_BYTES = 512L * 1024 * 1024

        fun estimateEtaSeconds(completedChapters: Int, totalChapters: Int, imageCompleted: Int, imageTotal: Int): Int {
            val remaining = (totalChapters - completedChapters).coerceAtLeast(0) +
                (imageTotal - imageCompleted).coerceAtLeast(0)
            return if (remaining <= 0) -1 else remaining + 3
        }
    }

    fun get(id: String): ExportJob? = state.value[id]

    fun httpClient(): Wenku8HttpClient = http
}

/**
 * 导出缺图警告（纯函数，可单测）。
 *
 * 按章对比「正文中出现的插图数」与「实际下载成功的插图数」：
 * 少于正文数量即说明有图没进包（下载失败被 [EpubBuilder] 静默跳过）。
 */
internal fun missingImageWarnings(parsed: List<ParsedChapter>, images: List<DownloadedImage>): List<String> =
    parsed.mapNotNull { chapter ->
        val expected = chapter.imageUrls.size
        if (expected <= 0) return@mapNotNull null
        val missing = expected - images.count { it.sourceId == chapter.id }
        if (missing > 0) "《${chapter.title}》有 $missing 张插图未能下载，已不包含在 EPUB 中" else null
    }

/** 已就绪的插图资产（磁盘路径 + 元数据）；同一 URL 在任务内复用，跨章去重。 */
private data class ImageAsset(val localPath: String, val ext: String, val mime: String, val bytes: Long)

package com.example.hyperreader.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class OnlineCacheStatus {
    RUNNING,
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELED;

    val active: Boolean get() = this == RUNNING
}

/** 一本书的全书缓存任务（界面进度条直接读这几个字段）。 */
data class OnlineCacheJob(
    val bookId: String,
    val title: String = "",
    val total: Int = 0,
    val cached: Int = 0,
    val failed: Int = 0,
    val status: OnlineCacheStatus = OnlineCacheStatus.RUNNING,
    val message: String = "",
) {
    val percent: Int
        get() = if (total <= 0) 0 else (cached * 100 / total).coerceIn(0, 100)
}

/**
 * 「缓存全书」任务管理器：把一本书的全部章节写入 [OnlineChapterCache]，
 * 让用户之后能离线打开（目录来自 [OnlineIndexStore]，正文来自章节缓存）。
 *
 * 硬约束：
 * - 走 [OnlineReadingSource]，因此仍由全局限流（1 秒/请求 + 429 退避）决定节奏，
 *   不新建 HTTP 客户端、不并发抓取。
 * - **可取消**：每章之间检查协程取消；关掉应用即停（不做前台服务）。
 * - **可恢复**：已存在的章节直接跳过，取消后再点「缓存全书」从断点继续。
 * - **不碰登录**：目录与正文都是匿名公开页；遇到登录墙直接失败并提示。
 */
class OnlineBookCacheManager(
    private val sourceProvider: suspend () -> OnlineReadingSource,
    private val cache: OnlineChapterCache,
    private val indexStore: OnlineIndexStore,
    /** 网络策略：返回 false 时直接拒绝开始（例如「仅 WiFi」下处于移动网络）。 */
    private val canDownload: suspend () -> Boolean = { true },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val mutable = MutableStateFlow<Map<String, OnlineCacheJob>>(emptyMap())
    val jobs: StateFlow<Map<String, OnlineCacheJob>> = mutable.asStateFlow()

    private val running = mutableMapOf<String, Job>()
    private val lock = Any()

    fun start(bookId: String, title: String, author: String = "") {
        val id = normalize(bookId)
        if (id.isBlank()) return
        synchronized(lock) {
            if (running[id]?.isActive == true) return
            running[id] = scope.launch { execute(id, title, author) }
        }
    }

    /** 取消后已下载的章节保留，再点一次从断点继续。 */
    fun cancel(bookId: String) {
        val id = normalize(bookId)
        val job = synchronized(lock) { running.remove(id) }
        job?.cancel()
        mutate(id) {
            it.copy(status = OnlineCacheStatus.CANCELED, message = "已取消，已缓存章节会保留")
        }
    }

    /** 从书架进度区移除已结束任务（不影响磁盘缓存）。 */
    fun dismiss(bookId: String) {
        val id = normalize(bookId)
        mutable.update { it - id }
    }

    /** 删除一本书的正文缓存与目录快照。 */
    fun deleteCache(bookId: String) {
        val id = normalize(bookId)
        synchronized(lock) { running.remove(id)?.cancel() }
        cache.clear(bookId = id)
        indexStore.delete(id)
        mutable.update { it - id }
    }

    fun clearAll() {
        synchronized(lock) {
            running.values.forEach { it.cancel() }
            running.clear()
        }
        cache.clear()
        indexStore.clear()
        mutable.value = emptyMap()
    }

    fun diskUsageBytes(): Long = cache.totalBytes() + indexStore.totalBytes()

    fun cachedChapterCount(bookId: String): Int = cache.chapterCount(normalize(bookId))

    /** 每本书已缓存的章节数（书架卡片角标用）。 */
    fun cachedCounts(): Map<String, Int> =
        cache.cachedBookIds().associateWith { bookId -> cache.chapterCount(bookId) }

    fun hasAnyCache(bookId: String): Boolean {
        val id = normalize(bookId)
        return indexStore.has(id) || cache.chapterCount(id) > 0
    }

    /** 测试直接调用；[start] 只是把它放进自己的 scope。 */
    suspend fun cacheBook(bookId: String, title: String, author: String = "") {
        execute(normalize(bookId), title, author)
    }

    private suspend fun execute(id: String, title: String, author: String) {
        if (id.isBlank()) return
        val displayTitle = title.ifBlank { "书籍 $id" }
        mutate(id) { it.copy(title = displayTitle, message = "正在获取目录…") }
        try {
            if (!canDownload()) {
                fail(id, "已开启「仅 WiFi 缓存」，当前网络为移动数据")
                return
            }
            val source = sourceProvider()
            val loaded = source.loadIndex(id, displayTitle, author)
            val index = when (loaded) {
                is OnlineReaderResult.Ready -> loaded.value
                is OnlineReaderResult.NeedsLogin -> {
                    fail(id, loaded.message)
                    return
                }
                is OnlineReaderResult.Failed -> {
                    fail(id, loaded.message)
                    return
                }
            }
            val chapters = index.catalog.chapters
            if (chapters.isEmpty()) {
                fail(id, "目录中没有可缓存的章节。")
                return
            }
            // 目录先落盘：即使正文缓存被取消，下次打开也还能展示全书目录。
            indexStore.write(index.catalog)
            cache.pin(id)
            var cached = chapters.count { cache.has(id, it.id) }
            var failed = 0
            var consecutiveFailures = 0
            mutate(id) {
                it.copy(
                    total = chapters.size,
                    cached = cached,
                    failed = 0,
                    message = if (cached > 0) "已有 $cached 章，继续补齐" else "开始缓存",
                )
            }
            for ((position, chapter) in chapters.withIndex()) {
                if (!currentCoroutineContext().isActive) throw CancellationException("cache canceled")
                if (cache.has(id, chapter.id)) continue
                when (val result = source.loadChapter(id, chapter, chapters)) {
                    is OnlineReaderResult.Ready -> {
                        // 真实数据源在 loadChapter 里已写缓存；这里兜底是为了让
                        // 「缓存全书」不依赖某个源的实现细节（测试与未来的源都受益）。
                        if (!cache.has(id, chapter.id)) {
                            cache.write(
                                OnlineChapterCacheEntry(
                                    bookId = id,
                                    chapterId = chapter.id,
                                    title = chapter.title,
                                    volume = chapter.volume,
                                    sourceUrl = chapter.url,
                                    blocks = result.value.blocks,
                                ),
                            )
                        }
                        cached += 1
                        consecutiveFailures = 0
                    }
                    is OnlineReaderResult.NeedsLogin -> {
                        fail(id, result.message)
                        return
                    }
                    is OnlineReaderResult.Failed -> {
                        failed += 1
                        consecutiveFailures += 1
                        if (result.code == OnlineReaderResult.CODE_CHAPTER_GONE) {
                            cache.markGone(id, chapter.id)
                        }
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            fail(
                                id,
                                "连续 $consecutiveFailures 章失败，已停止：${result.message}",
                                cached = cached,
                                failed = failed,
                            )
                            return
                        }
                    }
                }
                mutate(id) {
                    it.copy(
                        cached = cached,
                        failed = failed,
                        message = "正在缓存 ${position + 1} / ${chapters.size}",
                    )
                }
            }
            mutable.update { map ->
                val current = map[id] ?: OnlineCacheJob(id, displayTitle)
                map + (
                    id to current.copy(
                        total = chapters.size,
                        cached = cached,
                        failed = failed,
                        status = if (failed > 0) OnlineCacheStatus.PARTIAL else OnlineCacheStatus.COMPLETED,
                        message = if (failed > 0) {
                            "已缓存 $cached 章，$failed 章失败（可再次点击重试）"
                        } else {
                            "已缓存全部 $cached 章，可离线阅读"
                        },
                    )
                )
            }
        } catch (canceled: CancellationException) {
            mutate(id) {
                it.copy(status = OnlineCacheStatus.CANCELED, message = "已取消，已缓存章节会保留")
            }
            throw canceled
        } catch (error: Throwable) {
            fail(id, error.message ?: "缓存失败，请稍后重试。")
        } finally {
            synchronized(lock) { running.remove(id) }
        }
    }

    private fun fail(id: String, message: String, cached: Int? = null, failed: Int? = null) {
        mutate(id) {
            it.copy(
                status = OnlineCacheStatus.FAILED,
                message = message,
                cached = cached ?: it.cached,
                failed = failed ?: it.failed,
            )
        }
    }

    /** job 不存在时用当前已知标题补一条，避免进度区出现空行。 */
    private fun mutate(id: String, transform: (OnlineCacheJob) -> OnlineCacheJob) {
        mutable.update { map ->
            val base = map[id] ?: OnlineCacheJob(id)
            map + (id to transform(base))
        }
    }

    private fun normalize(bookId: String): String = bookId.filter(Char::isDigit)

    companion object {
        /** 连续失败这么多章就判定为网络/拦截问题，停止以免继续打扰源站。 */
        const val MAX_CONSECUTIVE_FAILURES = 5
    }
}

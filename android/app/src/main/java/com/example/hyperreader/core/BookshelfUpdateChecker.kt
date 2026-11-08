package com.example.hyperreader.core

import com.example.hyperreader.data.BookshelfRepository
import com.example.hyperreader.model.BookshelfEntry
import com.example.hyperreader.reader.OnlineReaderResult
import com.example.hyperreader.reader.OnlineReadingSource

/** 单本书更新检查的结果。 */
sealed interface UpdateCheckOutcome {
    /** 检查成功。[newChapters] 是相对用户上次打开时的新增章数（0 = 无更新）。 */
    data class Checked(val entry: BookshelfEntry, val newChapters: Int) : UpdateCheckOutcome

    /** 跳过（本地书、目录为空、网络失败、需要登录）。 */
    data class Skipped(val entry: BookshelfEntry, val reason: String) : UpdateCheckOutcome
}

/**
 * 书架更新检查：抓源站目录，与 `BookshelfEntry.chapterCount` 比对得出未读新章。
 *
 * 取数一律走 [OnlineReadingSource]，因此仍然受全局限流（1 秒/请求 + 429 退避）约束，
 * 不新建 HTTP 客户端、不并发抓取，也不会携带登录 Cookie（公开目录页）。
 *
 * 「已读基线」口径见 [BookshelfEntry.unreadChapters]：打开书时由
 * [BookshelfRepository.recordRead] 抬高 `chapterCount`，所以检查本身不需要额外状态。
 */
class BookshelfUpdateChecker(
    private val repository: BookshelfRepository,
    private val sourceProvider: suspend () -> OnlineReadingSource,
) {
    suspend fun check(
        entry: BookshelfEntry,
        now: Long = System.currentTimeMillis(),
    ): UpdateCheckOutcome {
        if (!entry.isRemoteSource) {
            return UpdateCheckOutcome.Skipped(entry, "本地书不检查更新")
        }
        val loaded = sourceProvider().loadIndex(entry.bookId, entry.title, entry.author)
        return when (loaded) {
            is OnlineReaderResult.Ready -> {
                val chapters = loaded.value.catalog.chapters
                if (chapters.isEmpty()) {
                    UpdateCheckOutcome.Skipped(entry, "目录为空")
                } else {
                    val serverCount = chapters.size
                    val latest = chapters.last().title
                    val adoptBaseline = entry.chapterCount <= 0
                    repository.recordUpdateCheck(
                        id = entry.id,
                        serverChapterCount = serverCount,
                        latestChapter = latest,
                        checkedAt = now,
                        adoptAsBaseline = adoptBaseline,
                    )
                    val updated = applyUpdate(entry, serverCount, latest, now, adoptBaseline)
                    UpdateCheckOutcome.Checked(updated, updated.unreadChapters)
                }
            }
            is OnlineReaderResult.NeedsLogin -> UpdateCheckOutcome.Skipped(entry, loaded.message)
            is OnlineReaderResult.Failed -> UpdateCheckOutcome.Skipped(entry, loaded.message)
        }
    }

    /**
     * 顺序检查（**不并发**：源站限流是全局 1 秒/请求，并发只会排队）。
     * 每次最多 [maxBooks] 本，避免一次性打满请求配额。
     */
    suspend fun checkAll(
        entries: List<BookshelfEntry>,
        now: Long = System.currentTimeMillis(),
        maxBooks: Int = MAX_BOOKS_PER_RUN,
    ): List<UpdateCheckOutcome> =
        entries.filter { it.isRemoteSource }
            .take(maxBooks)
            .map { check(it, now) }

    companion object {
        /** 单次「检查全部」最多处理的书本数。 */
        const val MAX_BOOKS_PER_RUN = 60

        /** 自动检查的最小间隔：12 小时。 */
        const val AUTO_CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
    }
}

/**
 * 纯函数：把一次检查结果写回条目。抽出来便于单测（不依赖 DataStore）。
 *
 * [adoptAsBaseline] 用于 `chapterCount` 未知（0）的旧条目：首次检查直接把它设为基线，
 * 否则会把整本书误报成「全部未读」。
 */
internal fun applyUpdate(
    entry: BookshelfEntry,
    serverChapterCount: Int,
    latestChapter: String,
    checkedAt: Long,
    adoptAsBaseline: Boolean,
): BookshelfEntry = entry.copy(
    lastCheckedChapterCount = serverChapterCount,
    latestChapter = latestChapter,
    lastCheckedAt = checkedAt,
    chapterCount = if (adoptAsBaseline) serverChapterCount else entry.chapterCount,
)

/** 纯函数：按上次检查时间筛出本次该检查的书。 */
internal fun entriesDueForCheck(
    entries: List<BookshelfEntry>,
    now: Long,
    minIntervalMs: Long = BookshelfUpdateChecker.AUTO_CHECK_INTERVAL_MS,
): List<BookshelfEntry> = entries.filter { entry ->
    entry.isRemoteSource && (entry.lastCheckedAt <= 0L || now - entry.lastCheckedAt >= minIntervalMs)
}

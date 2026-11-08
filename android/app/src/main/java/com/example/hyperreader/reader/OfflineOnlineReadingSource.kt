package com.example.hyperreader.reader

import com.example.hyperreader.model.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 离线模式包装：**只读本地缓存，不发起任何网络请求**。
 *
 * 存在的意义不是「断网兜底」（那由各源内部的缓存回落负责），而是让用户能主动
 * 保证零流量：地铁、飞机、漫游时打开阅读器不会因为目录过期而先打一次源站，
 * 也不会在章节缺失时卡在 1 秒限流上。
 *
 * 目录来自 [OnlineIndexStore]（`缓存全书` 或上一次成功打开时写入），
 * 正文来自 [OnlineChapterCache]（两个阅读源共用同一份缓存）。
 * 缺目录或章节时直接返回 [OnlineReaderResult.CODE_OFFLINE]，并提示先联网缓存。
 */
class OfflineOnlineReadingSource(
    private val cache: OnlineChapterCache,
    private val indexStore: OnlineIndexStore,
) : OnlineReadingSource {

    override suspend fun loadIndex(
        bookId: String,
        title: String,
        author: String,
    ): OnlineReaderResult<OnlineIndex> = withContext(Dispatchers.IO) {
        val cached = indexStore.read(bookId) ?: return@withContext OnlineReaderResult.Failed(
            "离线模式下没有这本书的目录缓存。请关闭离线模式后联网打开一次，或使用「缓存全书」。",
            OnlineReaderResult.CODE_OFFLINE,
        )
        OnlineReaderResult.Ready(
            OnlineIndex(cached.toReaderBook(title, author), cached),
            fromCache = true,
        )
    }

    override suspend fun loadChapter(
        bookId: String,
        chapter: Chapter,
        catalog: List<Chapter>,
    ): OnlineReaderResult<ReaderChapter> = withContext(Dispatchers.IO) {
        if (cache.isGone(bookId, chapter.id)) {
            return@withContext OnlineReaderResult.Failed(
                "该章已被源站移除。",
                OnlineReaderResult.CODE_CHAPTER_GONE,
            )
        }
        val cached = cache.read(bookId, chapter.id) ?: return@withContext OnlineReaderResult.Failed(
            "离线模式下该章尚未缓存。关闭离线模式或先「缓存全书」。",
            OnlineReaderResult.CODE_OFFLINE,
        )
        OnlineReaderResult.Ready(
            ReaderChapter(chapter.id, chapter.title, chapter.url, cached.blocks),
            fromCache = true,
        )
    }
}

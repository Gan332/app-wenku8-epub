package com.example.hyperreader

import com.example.hyperreader.model.BookIndex
import com.example.hyperreader.model.Chapter
import com.example.hyperreader.reader.OnlineBookCacheManager
import com.example.hyperreader.reader.OnlineCacheStatus
import com.example.hyperreader.reader.OnlineChapterCache
import com.example.hyperreader.reader.OnlineChapterCacheEntry
import com.example.hyperreader.reader.OnlineIndexStore
import com.example.hyperreader.reader.OnlineIndex
import com.example.hyperreader.reader.OnlineReaderResult
import com.example.hyperreader.reader.OnlineReadingSource
import com.example.hyperreader.reader.ReaderBlock
import com.example.hyperreader.reader.ReaderChapter
import com.example.hyperreader.reader.toReaderBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 「缓存全书（离线）」链路的纯 JVM 测试：
 * 目录快照、固定书不参与 LRU 淘汰、逐章缓存与断点续传。
 */
class OnlineCacheTest {

    private fun tempDir(): File =
        Files.createTempDirectory("online-cache-test").toFile().also { it.deleteOnExit() }

    private fun chapter(id: String) = Chapter(
        id = id,
        title = "第${id}章",
        url = "https://www.wenku8.net/novel/2/2835/$id.htm",
        volume = "第一卷",
        order = id.toInt(),
    )

    private fun entry(bookId: String, chapterId: String) = OnlineChapterCacheEntry(
        bookId = bookId,
        chapterId = chapterId,
        title = "第${chapterId}章",
        volume = "第一卷",
        sourceUrl = "https://www.wenku8.net/novel/2/$bookId/$chapterId.htm",
        blocks = listOf(ReaderBlock.Paragraph("正文")),
    )

    @Test
    fun indexSnapshotRoundTripsAndCorruptFilesAreDropped() {
        val root = tempDir()
        val store = OnlineIndexStore(root)
        val index = BookIndex(
            title = "测试书",
            url = "https://www.wenku8.net/novel/2/2835/index.htm",
            bookId = "2835",
            chapters = listOf(chapter("100"), chapter("101")),
        )
        assertTrue(store.write(index))
        val restored = store.read("2835")
        assertNotNull(restored)
        assertEquals("测试书", restored!!.title)
        assertEquals(2, restored.chapters.size)
        assertEquals("第一卷", restored.chapters[0].volume)
        assertTrue(store.totalBytes() > 0)

        // 半截 JSON 不应拖垮打开流程：读取返回 null，文件被删除。
        store.fileFor("2835").writeText("{ not json", Charsets.UTF_8)
        assertNull(store.read("2835"))
        assertTrue(!store.fileFor("2835").exists())

        store.delete("2835")
        assertNull(store.read("2835"))
    }

    @Test
    fun pinnedBookIsNotEvictedByLru() {
        val root = tempDir()
        val cache = OnlineChapterCache(root, maxChaptersPerBook = 2)
        repeat(3) { i -> assertTrue(cache.write(entry("2835", "10$i"))) }
        // 普通书按 LRU 上限淘汰到 2 章
        assertEquals(2, cache.chapterCount("2835"))

        cache.clear(bookId = "2835")
        cache.pin("2835")
        repeat(3) { i -> assertTrue(cache.write(entry("2835", "10$i"))) }
        assertEquals(3, cache.chapterCount("2835"))
        assertTrue(cache.isPinned("2835"))
        assertTrue(cache.cachedBookIds().contains("2835"))
        assertTrue(cache.totalBytes() > 0)
        assertTrue(cache.bookBytes("2835") > 0)
    }

    @Test
    fun cacheManagerDownloadsEveryChapterThenSkipsCachedOnes() = runBlocking {
        val root = tempDir()
        val cache = OnlineChapterCache(root)
        val indexStore = OnlineIndexStore(File(root, "index"))
        val chapters = listOf(chapter("100"), chapter("101"), chapter("102"))
        val fetched = mutableListOf<String>()
        val manager = OnlineBookCacheManager(
            sourceProvider = { FakeSource(chapters, fetched) },
            cache = cache,
            indexStore = indexStore,
        )

        manager.cacheBook("2835", "测试书", "作者甲")

        val job = manager.jobs.value["2835"]
        assertNotNull(job)
        assertEquals(OnlineCacheStatus.COMPLETED, job!!.status)
        assertEquals(3, job.cached)
        assertEquals(3, cache.chapterCount("2835"))
        assertNotNull(indexStore.read("2835"))
        assertEquals(listOf("100", "101", "102"), fetched)

        // 断点续传：第二次全部命中缓存，一次网络请求都不发。
        manager.cacheBook("2835", "测试书", "作者甲")
        assertEquals(3, fetched.size)
        assertEquals(OnlineCacheStatus.COMPLETED, manager.jobs.value["2835"]!!.status)
    }

    @Test
    fun cacheManagerStopsAfterConsecutiveFailures() = runBlocking {
        val root = tempDir()
        val cache = OnlineChapterCache(root)
        val indexStore = OnlineIndexStore(File(root, "index"))
        val chapters = (0 until 8).map { chapter("10$it") }
        val manager = OnlineBookCacheManager(
            sourceProvider = { FailingSource(chapters) },
            cache = cache,
            indexStore = indexStore,
        )

        manager.cacheBook("2835", "测试书", "作者甲")

        val job = manager.jobs.value["2835"]!!
        assertEquals(OnlineCacheStatus.FAILED, job.status)
        assertEquals(0, job.cached)
        assertEquals(OnlineBookCacheManager.MAX_CONSECUTIVE_FAILURES, job.failed)
        // 目录仍然落盘：下次打开至少能看到全书目录
        assertNotNull(indexStore.read("2835"))
    }

    private class FakeSource(
        private val chapters: List<Chapter>,
        private val fetched: MutableList<String>,
    ) : OnlineReadingSource {
        override suspend fun loadIndex(
            bookId: String,
            title: String,
            author: String,
        ): OnlineReaderResult<OnlineIndex> {
            val index = BookIndex(
                title = title,
                url = "https://www.wenku8.net/novel/2/$bookId/index.htm",
                bookId = bookId,
                chapters = chapters,
            )
            return OnlineReaderResult.Ready(OnlineIndex(index.toReaderBook(title, author), index))
        }

        override suspend fun loadChapter(
            bookId: String,
            chapter: Chapter,
            catalog: List<Chapter>,
        ): OnlineReaderResult<ReaderChapter> {
            fetched += chapter.id
            return OnlineReaderResult.Ready(
                ReaderChapter(
                    id = chapter.id,
                    title = chapter.title,
                    href = chapter.url,
                    blocks = listOf(ReaderBlock.Paragraph("正文")),
                ),
            )
        }
    }

    private class FailingSource(private val chapters: List<Chapter>) : OnlineReadingSource {
        override suspend fun loadIndex(
            bookId: String,
            title: String,
            author: String,
        ): OnlineReaderResult<OnlineIndex> {
            val index = BookIndex(
                title = title,
                url = "https://www.wenku8.net/novel/2/$bookId/index.htm",
                bookId = bookId,
                chapters = chapters,
            )
            return OnlineReaderResult.Ready(OnlineIndex(index.toReaderBook(title, author), index))
        }

        override suspend fun loadChapter(
            bookId: String,
            chapter: Chapter,
            catalog: List<Chapter>,
        ): OnlineReaderResult<ReaderChapter> =
            OnlineReaderResult.Failed("网络错误", "UPSTREAM_HTTP_ERROR")
    }
}

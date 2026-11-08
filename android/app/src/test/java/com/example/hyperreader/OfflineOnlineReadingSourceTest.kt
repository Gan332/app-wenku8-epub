package com.example.hyperreader

import com.example.hyperreader.model.BookIndex
import com.example.hyperreader.model.Chapter
import com.example.hyperreader.reader.OfflineOnlineReadingSource
import com.example.hyperreader.reader.OnlineChapterCache
import com.example.hyperreader.reader.OnlineChapterCacheEntry
import com.example.hyperreader.reader.OnlineIndexStore
import com.example.hyperreader.reader.OnlineReaderResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 离线模式：只读缓存，缺失时返回 CODE_OFFLINE 而不是尝试联网。 */
class OfflineOnlineReadingSourceTest {

    private fun tempDir(): File =
        Files.createTempDirectory("offline-source-test").toFile().also { it.deleteOnExit() }

    private fun chapter(id: String) = Chapter(
        id = id,
        title = "第${id}章",
        url = "https://www.wenku8.net/novel/2/2835/$id.htm",
        volume = "第一卷",
        order = id.toInt(),
    )

    private fun index() = BookIndex(
        title = "离线书",
        url = "https://www.wenku8.net/novel/2/2835/index.htm",
        bookId = "2835",
        chapters = listOf(chapter("100"), chapter("101")),
    )

    @Test
    fun servesIndexAndCachedChapterWithoutNetwork() = runBlocking {
        val root = tempDir()
        val cache = OnlineChapterCache(File(root, "cache"))
        val store = OnlineIndexStore(File(root, "index"))
        assertTrue(store.write(index()))
        assertTrue(
            cache.write(
                OnlineChapterCacheEntry(
                    bookId = "2835",
                    chapterId = "100",
                    title = "第100章",
                    blocks = emptyList(),
                ),
            ),
        )

        val source = OfflineOnlineReadingSource(cache, store)
        val loadedIndex = source.loadIndex("2835", "离线书", "作者")
        assertTrue(loadedIndex is OnlineReaderResult.Ready)
        assertEquals(2, (loadedIndex as OnlineReaderResult.Ready).value.size)

        val loadedChapter = source.loadChapter("2835", chapter("100"))
        assertTrue(loadedChapter is OnlineReaderResult.Ready)
        assertTrue((loadedChapter as OnlineReaderResult.Ready).fromCache)
    }

    @Test
    fun missingIndexAndChapterReportOfflineInsteadOfFetching() = runBlocking {
        val root = tempDir()
        val source = OfflineOnlineReadingSource(
            cache = OnlineChapterCache(File(root, "cache")),
            indexStore = OnlineIndexStore(File(root, "index")),
        )

        val indexFailure = source.loadIndex("2835", "离线书", "作者")
        assertEquals(
            OnlineReaderResult.CODE_OFFLINE,
            (indexFailure as OnlineReaderResult.Failed).code,
        )

        val chapterFailure = source.loadChapter("2835", chapter("100"))
        assertEquals(
            OnlineReaderResult.CODE_OFFLINE,
            (chapterFailure as OnlineReaderResult.Failed).code,
        )
    }

    @Test
    fun goneChapterStaysGoneInOfflineMode() = runBlocking {
        val root = tempDir()
        val cache = OnlineChapterCache(File(root, "cache"))
        val store = OnlineIndexStore(File(root, "index"))
        store.write(index())
        cache.markGone("2835", "101")

        val source = OfflineOnlineReadingSource(cache, store)
        val result = source.loadChapter("2835", chapter("101"))
        assertEquals(
            OnlineReaderResult.CODE_CHAPTER_GONE,
            (result as OnlineReaderResult.Failed).code,
        )
    }
}

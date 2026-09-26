package com.wenku8.epubstudio

import com.wenku8.epubstudio.core.Wenku8Exception
import com.wenku8.epubstudio.model.Chapter
import com.wenku8.epubstudio.model.ContentBlock
import com.wenku8.epubstudio.model.ParsedChapter
import com.wenku8.epubstudio.reader.FetchedText
import com.wenku8.epubstudio.reader.OnlineChapterCache
import com.wenku8.epubstudio.reader.OnlineChapterCacheEntry
import com.wenku8.epubstudio.reader.OnlineReaderResult
import com.wenku8.epubstudio.reader.OnlineReaderSource
import com.wenku8.epubstudio.reader.OnlineTextFetcher
import com.wenku8.epubstudio.reader.ReaderBlock
import com.wenku8.epubstudio.reader.isAuthWall
import com.wenku8.epubstudio.reader.toReaderBlocks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 在线阅读的纯 JVM 单元测试：不依赖任何 Android 运行时类型。
 *
 * 覆盖 AGENTS.md 4.2 的硬性边界：登录页不可被当作正文、登录门禁端点保持原样；
 * 以及 4.5 的被动触发约束：命中缓存时一次网络请求都不发。
 */
class OnlineReaderTest {
    private val bookId = "2835"
    private val chapterUrl = "https://www.wenku8.net/novel/2/2835/100.htm"

    private fun chapter(id: String = "100", volume: String = "正文") =
        Chapter(id = id, title = "第一章", url = chapterUrl, volume = volume, order = 1)

    private fun tempDirectory(): File =
        File(System.getProperty("java.io.tmpdir"), "wenku8-online-test-${System.nanoTime()}").apply { mkdirs() }

    private fun indexHtml() = """
        <html><head><title>测试轻小说 - 文库</title></head><body><table>
        <tr><td class="vcss">第一卷</td></tr>
        <tr><td class="ccss"><a href="/novel/2/2835/100.htm">第一章</a></td></tr>
        <tr><td class="ccss"><a href="/novel/2/2835/101.htm">第二章</a></td></tr>
        </table></body></html>
    """.trimIndent()

    private fun chapterHtml() = """
        <html><head><title>第一章 - 文库</title></head><body><div id="content">
        <p>这是第一段正文内容，长度足以通过正文校验。</p>
        <br>
        <p>这是第二段正文内容，同样足够长。</p>
        <img src="/image/2/2835/2835_001.jpg" width="600" height="800">
        </div></body></html>
    """.trimIndent()

    private fun loginHtml() = """
        <html><head><title>用户登录 - 文库</title></head><body>
        <form action="/login.php"><input name="username"><input name="password"></form>
        <div>请先登录后继续</div></body></html>
    """.trimIndent()

    // ---- ParsedChapter -> ReaderBlock 装配 ----

    @Test
    fun parsedChapterBecomesHeadingsParagraphsAndRemoteImages() {
        val parsed = ParsedChapter(
            id = "100",
            title = "第一章 起点",
            volume = "第一卷",
            order = 1,
            sourceUrl = chapterUrl,
            imageUrls = listOf("https://img.wenku8.com/image/2/2835/2835_001.jpg"),
            blocks = listOf(
                ContentBlock.Text("第一段正文。"),
                ContentBlock.Image(0),
                ContentBlock.Text("第二段正文。"),
            ),
        )
        val blocks = parsed.toReaderBlocks()
        assertEquals(ReaderBlock.Heading(1, "第一卷"), blocks[0])
        assertEquals(ReaderBlock.Heading(2, "第一章 起点"), blocks[1])
        assertEquals(ReaderBlock.Paragraph("第一段正文。"), blocks[2])
        assertEquals(ReaderBlock.Image("https://img.wenku8.com/image/2/2835/2835_001.jpg", "插图 1"), blocks[3])
        assertEquals(ReaderBlock.Paragraph("第二段正文。"), blocks[4])
    }

    @Test
    fun defaultVolumeDoesNotEmitExtraHeadingAndBlankTextIsDropped() {
        val parsed = ParsedChapter(
            id = "100",
            title = "第一章",
            volume = "正文",
            order = 1,
            sourceUrl = chapterUrl,
            blocks = listOf(ContentBlock.Text("   "), ContentBlock.Text("有效段落。")),
        )
        val blocks = parsed.toReaderBlocks()
        assertEquals(1, blocks.count { it is ReaderBlock.Heading })
        assertEquals(listOf(ReaderBlock.Paragraph("有效段落。")), blocks.filterIsInstance<ReaderBlock.Paragraph>())
    }

    @Test
    fun outOfRangeImageMarkerIsDroppedInsteadOfCrashing() {
        val parsed = ParsedChapter(
            id = "100",
            title = "第一章",
            volume = "正文",
            order = 1,
            sourceUrl = chapterUrl,
            imageUrls = emptyList(),
            blocks = listOf(ContentBlock.Image(3), ContentBlock.Text("正文。")),
        )
        val blocks = parsed.toReaderBlocks()
        assertTrue(blocks.none { it is ReaderBlock.Image })
        assertEquals(1, blocks.count { it is ReaderBlock.Paragraph })
    }

    // ---- 缓存键与 LRU ----

    @Test
    fun cacheKeyIsStableAndPathTraversalIsNeutralized() {
        val directory = tempDirectory()
        try {
            val cache = OnlineChapterCache(directory)
            assertEquals("2835/100", cache.key("2835", "100"))
            assertEquals("2835/100", cache.key("2835", "100"))
            val escaped = cache.key("2835", "../../evil")
            assertFalse(escaped.contains(".."))
            assertTrue(cache.fileFor("2835", "../../evil").canonicalPath.startsWith(directory.canonicalPath))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun lruEvictsOldestChapterBeyondFifty() {
        val directory = tempDirectory()
        try {
            val roomy = OnlineChapterCache(directory, maxChaptersPerBook = 200)
            repeat(51) { index ->
                roomy.write(entry(chapterId = index.toString().padStart(3, '0')))
            }
            assertEquals(51, roomy.chapterCount(bookId))
            // 明确排定访问顺序：000 最老，050 最新
            repeat(51) { index ->
                val id = index.toString().padStart(3, '0')
                roomy.fileFor(bookId, id).setLastModified(1_000_000L + index * 10_000L)
            }

            val limited = OnlineChapterCache(directory, maxChaptersPerBook = 50)
            val dropped = limited.evict(bookId)
            assertEquals(listOf("000.json"), dropped)
            assertFalse(limited.has(bookId, "000"))
            assertTrue(limited.has(bookId, "050"))
            assertEquals(50, limited.chapterCount(bookId))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun writeIsAtomicAndLeavesNoTemporaryFile() {
        val directory = tempDirectory()
        try {
            val cache = OnlineChapterCache(directory)
            assertTrue(cache.write(entry(chapterId = "100")))
            assertFalse(directory.walkTopDown().any { it.name.endsWith(".tmp") })
            val read = cache.read(bookId, "100")
            assertEquals("第一章", read?.title)
            assertEquals(1, read?.blocks?.count { it is ReaderBlock.Paragraph })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun corruptedCacheFileIsDiscardedSoNetworkFallbackWorks() {
        val directory = tempDirectory()
        try {
            val cache = OnlineChapterCache(directory)
            val file = cache.fileFor(bookId, "100")
            file.parentFile?.mkdirs()
            file.writeText("{ 这不是合法 JSON")
            assertTrue(cache.read(bookId, "100") == null)
            assertFalse(file.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    // ---- 缓存命中不发网络 ----

    @Test
    fun cacheHitPerformsZeroNetworkRequests() {
        val directory = tempDirectory()
        try {
            val calls = mutableListOf<String>()
            val fetcher = recordingFetcher(calls) { FetchedText(chapterHtml(), chapterUrl) }
            val source = OnlineReaderSource(OnlineChapterCache(directory), fetcher)

            val first = runBlocking { source.loadChapter(bookId, chapter()) }
            assertTrue(first is OnlineReaderResult.Ready)
            assertEquals(1, calls.size)

            val second = runBlocking { source.loadChapter(bookId, chapter()) }
            val ready = second as OnlineReaderResult.Ready
            assertTrue(ready.fromCache)
            assertEquals(1, calls.size) // 命中缓存 => 零新增请求
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun indexIsAssembledIntoReaderBookWithoutBodyText() {
        val directory = tempDirectory()
        try {
            val calls = mutableListOf<String>()
            val fetcher = recordingFetcher(calls) { FetchedText(indexHtml(), "https://www.wenku8.net/novel/2/2835/index.htm") }
            val source = OnlineReaderSource(OnlineChapterCache(directory), fetcher)

            val result = runBlocking { source.loadIndex(bookId, title = "测试轻小说", author = "作者甲") }
            val index = (result as OnlineReaderResult.Ready).value
            assertEquals(bookId, index.book.id)
            assertEquals("测试轻小说", index.book.title)
            assertEquals("作者甲", index.book.author)
            assertEquals(2, index.book.chapters.size)
            assertEquals("第一章", index.book.chapters[0].title)
            assertEquals("https://www.wenku8.net/novel/2/2835/101.htm", index.book.chapters[1].href)
            assertTrue(index.book.chapters.all { it.blocks.isEmpty() })
            assertTrue(index.book.archivePath.isEmpty())
            assertEquals(1, calls.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun onlineIndexUrlStaysOnTheAnonymousPublicEndpoint() {
        val directory = tempDirectory()
        try {
            val source = OnlineReaderSource(OnlineChapterCache(directory), recordingFetcher(mutableListOf()) { FetchedText("", "") })
            val url = source.indexUrl(bookId)
            assertEquals("https://www.wenku8.net/novel/2/2835/index.htm", url)
            // 登录门禁端点保持原样，在线阅读不得触碰
            listOf("search.php", "articlelist.php", "toplist.php", "tags.php").forEach { endpoint ->
                assertFalse(url.contains(endpoint))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    // ---- 登录墙：识别并如实提示，绝不绕过 ----

    @Test
    fun loginPageIsDetectedAndNeverParsedAsBody() {
        assertTrue(isAuthWall(loginHtml()))
        assertFalse(isAuthWall(chapterHtml()))
    }

    @Test
    fun loginPageChapterYieldsNeedsLoginAndNoRetry() {
        val directory = tempDirectory()
        try {
            val calls = mutableListOf<String>()
            val source = OnlineReaderSource(OnlineChapterCache(directory), recordingFetcher(calls) { FetchedText(loginHtml(), chapterUrl) })

            val result = runBlocking { source.loadChapter(bookId, chapter()) }
            val needsLogin = result as OnlineReaderResult.NeedsLogin
            assertEquals(OnlineReaderResult.LOGIN_MESSAGE, needsLogin.message)
            assertTrue(needsLogin.message.contains("需要登录"))
            // 只请求一次后如实停手：不换 IP、不补 Cookie、不重试绕过
            assertEquals(1, calls.size)
            assertEquals(0, OnlineChapterCache(directory).chapterCount(bookId))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun loginPageIndexYieldsNeedsLogin() {
        val directory = tempDirectory()
        try {
            val source = OnlineReaderSource(OnlineChapterCache(directory), recordingFetcher(mutableListOf()) { FetchedText(loginHtml(), "https://www.wenku8.net/novel/2/2835/index.htm") })
            val result = runBlocking { source.loadIndex(bookId) }
            assertTrue(result is OnlineReaderResult.NeedsLogin)
        } finally {
            directory.deleteRecursively()
        }
    }

    // ---- 章节被源站删除 ----

    @Test
    fun deletedChapterIsMarkedGoneAndThenShortCircuited() {
        val directory = tempDirectory()
        try {
            val calls = mutableListOf<String>()
            val source = OnlineReaderSource(
                OnlineChapterCache(directory),
                recordingFetcher(calls) { throw Wenku8Exception("源站返回 HTTP 404。", "UPSTREAM_HTTP_ERROR") },
            )

            val first = runBlocking { source.loadChapter(bookId, chapter()) }
            val failed = first as OnlineReaderResult.Failed
            assertEquals(OnlineReaderResult.CODE_CHAPTER_GONE, failed.code)
            assertTrue(failed.message.contains("源站"))
            assertTrue(source.chapterCache.isGone(bookId, "100"))

            val second = runBlocking { source.loadChapter(bookId, chapter()) }
            assertEquals(OnlineReaderResult.CODE_CHAPTER_GONE, (second as OnlineReaderResult.Failed).code)
            assertEquals(1, calls.size) // 已标记失效，不再重复打扰源站
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rateLimitIsNotMistakenForADeletedChapter() {
        val directory = tempDirectory()
        try {
            val cache = OnlineChapterCache(directory)
            val source = OnlineReaderSource(
                cache,
                recordingFetcher(mutableListOf()) { throw Wenku8Exception("源站暂时限制了请求（HTTP 429），请稍后重试。", "UPSTREAM_RATE_LIMIT") },
            )
            val result = runBlocking { source.loadChapter(bookId, chapter()) }
            val failed = result as OnlineReaderResult.Failed
            assertEquals("UPSTREAM_RATE_LIMIT", failed.code)
            assertFalse(cache.isGone(bookId, "100"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun goneChapterRedirectsToTheNeighbouringChapter() {
        val directory = tempDirectory()
        try {
            val source = OnlineReaderSource(OnlineChapterCache(directory), recordingFetcher(mutableListOf()) { FetchedText("", "") })
            assertEquals(6, source.nearestChapterIndex(5, total = 10, step = 1))
            assertEquals(4, source.nearestChapterIndex(5, total = 10, step = -1))
            assertEquals(0, source.nearestChapterIndex(0, total = 10, step = -1))
            assertEquals(9, source.nearestChapterIndex(9, total = 10, step = 1))
            assertEquals(0, source.nearestChapterIndex(3, total = 0))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingChapterIndexFailsWithoutAnyRequest() {
        val directory = tempDirectory()
        try {
            val calls = mutableListOf<String>()
            val source = OnlineReaderSource(OnlineChapterCache(directory), recordingFetcher(calls) { FetchedText("", "") })
            val book = com.wenku8.epubstudio.reader.ReaderBook(id = bookId, title = "测试")
            val result = runBlocking { source.loadChapter(bookId, 5, book) }
            assertEquals("CHAPTER_NOT_FOUND", (result as OnlineReaderResult.Failed).code)
            assertTrue(calls.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun invalidBookIdFailsWithoutAnyRequest() {
        val directory = tempDirectory()
        try {
            val calls = mutableListOf<String>()
            val source = OnlineReaderSource(OnlineChapterCache(directory), recordingFetcher(calls) { FetchedText("", "") })
            val result = runBlocking { source.loadIndex("not-a-number") }
            assertEquals("INVALID_BOOK_ID", (result as OnlineReaderResult.Failed).code)
            assertTrue(calls.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    // ---- helpers ----

    private fun entry(chapterId: String) = OnlineChapterCacheEntry(
        bookId = bookId,
        chapterId = chapterId,
        title = "第一章",
        volume = "正文",
        sourceUrl = chapterUrl,
        blocks = listOf(ReaderBlock.Heading(2, "第一章"), ReaderBlock.Paragraph("正文内容。")),
    )

    private fun recordingFetcher(
        calls: MutableList<String>,
        response: () -> FetchedText,
    ): OnlineTextFetcher = OnlineTextFetcher { url, _ ->
        calls += url
        response()
    }
}

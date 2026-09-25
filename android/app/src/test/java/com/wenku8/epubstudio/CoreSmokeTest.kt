package com.wenku8.epubstudio

import com.wenku8.epubstudio.core.Wenku8Parser
import com.wenku8.epubstudio.core.Wenku8Url
import com.wenku8.epubstudio.core.Wenku8Urls
import com.wenku8.epubstudio.model.ReadingStats
import kotlinx.serialization.json.Json
import com.wenku8.epubstudio.epub.EpubBuilder
import com.wenku8.epubstudio.reader.EpubReaderRepository
import com.wenku8.epubstudio.model.Book
import com.wenku8.epubstudio.model.Chapter
import com.wenku8.epubstudio.model.ContentBlock
import com.wenku8.epubstudio.model.ParsedChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class CoreSmokeTest {
    @Test
    fun normalizesNumericBookIdAndRejectsOtherHosts() {
        assertEquals("https://www.wenku8.net/book/2835.htm", Wenku8Url.normalizeSource("2835").toString())
        assertTrue(runCatching { Wenku8Url.assertAllowed("https://example.com/book/1.htm") }.isFailure)
    }

    @Test
    fun parsesIndexRowsAndIllustrationMarkers() {
        val html = """
            <html><head><title>测试 - 目录</title></head><body><table>
            <tr><td class="vcss">第一卷</td></tr>
            <tr><td class="ccss"><a href="/novel/2/2835/100.htm">第一章</a></td></tr>
            <tr><td class="ccss"><a href="/novel/2/2835/101.htm">插图章</a></td></tr>
            </table></body></html>
        """.trimIndent()
        val index = Wenku8Parser.parseIndex(html, "https://www.wenku8.net/novel/2/2835/index.htm", "2835")
        assertEquals(2, index.chapters.size)
        assertEquals("第一卷", index.chapters[0].volume)
        assertTrue(index.chapters[1].isIllustration)
    }

    @Test
    fun parsesBookMetadataAndWordCount() {
        val html = """
            <html><head><title>测试书 - 作者 - 文库</title></head><body><div id="content"><table>
            <tr><td>小说作者：测试作者</td><td>文章状态：连载中</td></tr>
            <tr><td>最后更新：2026-09-25</td><td>全文长度：207,559字</td></tr>
            <tr><td><a href="/novel/2/2835/index.htm">目录</a></td></tr>
            </table></div></body></html>
        """.trimIndent()
        val book = Wenku8Parser.parseBook(html, "https://www.wenku8.net/book/2835.htm")
        assertEquals("测试作者", book.author)
        assertEquals(207559L, book.wordCount)
        assertEquals("2026-09-25", book.updatedAt)
        assertEquals(false, book.isComplete)
    }

    @Test
    fun searchParserExtractsResultIdsAndLoginIsExplicit() {
        assertTrue(Wenku8Parser.looksLikeLoginPage("<html><title>用户登录</title><form action='/login.php'></form></html>"))
        val html = "<div><a href='/book/12.htm'>结果书</a><span>作者：作者甲</span><span>字数：1234字</span></div>"
        val result = Wenku8Parser.parseSearchResults(html, "https://www.wenku8.net/modules/article/search.php").single()
        assertEquals("12", result.id)
        assertEquals("结果书", result.title)
        assertEquals(1234L, result.wordCount)
    }

    @Test
    fun exploreEndpointsAndStatsAreStable() {
        assertEquals("https://www.wenku8.net/modules/article/toplist.php?sort=lastupdate", Wenku8Urls.toplist("lastupdate"))
        assertTrue(Wenku8Urls.tag("恋爱").startsWith("https://www.wenku8.net/modules/article/tags.php?t="))
        val stats = ReadingStats(totalSeconds = 7200, todaySeconds = 600, currentStreak = 3, longestStreak = 8, totalSessions = 4, lastReadAt = 10L, dailySeconds = mapOf("2026-09-25" to 600L), bookSeconds = mapOf("b1" to 700L), bookTitles = mapOf("b1" to "测试书"))
        val encoded = Json.encodeToString(ReadingStats.serializer(), stats)
        assertEquals(stats, Json.decodeFromString(ReadingStats.serializer(), encoded))
    }

    @Test
    fun epubStartsWithUncompressedMimetype() {
        val directory = File(System.getProperty("java.io.tmpdir"), "wenku8-epub-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val output = File(directory, "book.epub")
            val builder = EpubBuilder()
            val book = Book(title = "测试书", author = "作者", sourceUrl = "https://www.wenku8.net/book/1.htm", bookUrl = "https://www.wenku8.net/book/1.htm")
            val chapter = ParsedChapter("1", "第一章", "正文", 1, "https://www.wenku8.net/novel/2/1/1.htm", blocks = listOf(ContentBlock.Text("正文内容")), textLength = 4)
            builder.build(book, listOf(chapter), emptyList(), null, output)
            ZipFile(output).use { zip ->
                val first = zip.entries().nextElement()
                assertEquals("mimetype", first.name)
                assertEquals(0, first.method)
                assertEquals("application/epub+zip", zip.getInputStream(first).readBytes().decodeToString())
                assertTrue(zip.getEntry("EPUB/package.opf") != null)
            }
            val readerBook = EpubReaderRepository().parseArchive("1", output)
            assertEquals("测试书", readerBook.title)
            assertTrue(readerBook.chapters.isNotEmpty())
            assertTrue(readerBook.chapters.first().blocks.any { it is com.wenku8.epubstudio.reader.ReaderBlock.Paragraph })
        } finally {
            directory.deleteRecursively()
        }
    }
}

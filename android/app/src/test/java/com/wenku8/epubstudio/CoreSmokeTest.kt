package com.wenku8.epubstudio

import com.wenku8.epubstudio.core.CatalogEntry
import com.wenku8.epubstudio.core.CatalogIndex
import com.wenku8.epubstudio.core.CatalogSearchField
import com.wenku8.epubstudio.core.CatalogStats
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
    fun publicCatalogParsersSkipPlaceholdersAndNavNoise() {
        val html = """
            <html><body>
            <a href="/modules/article/toplist.php?sort=allvisit">热门轻小说</a>
            <a href="/book/3988.htm" title="あちき volt">あちき volt</a>
            <a href="//www.wenku8.net/book/2580.htm">标题文字</a>
            <a href="#"><img src="x.jpg"></a>
            <a href="/book/3988.htm">重复</a>
            <a href="/novel/2/1/index.htm">目录</a>
            <a href="/modules/article/authorarticle.php?author=%BE%FD%B4%A8">同作者作品</a>
            </body></html>
        """.trimIndent()
        val links = Wenku8Parser.parseBookLinks(html, "https://www.wenku8.net/zt/sugoi/2026.php")
        assertEquals(listOf("3988", "2580"), links.map { it.id })
        assertEquals("あちき volt", links.first().title)

        val author = Wenku8Parser.parseAuthorLink(html, "https://www.wenku8.net/book/2835.htm")
        assertEquals("https://www.wenku8.net/modules/article/authorarticle.php?author=%BE%FD%B4%A8", author)

        val seed = Wenku8Parser.parseSeedList(html, "https://www.wenku8.net/zt/sugoi/2026.php", "2026 年度精选")
        assertEquals(2, seed.size)
        assertEquals("2026 年度精选", seed.first().listName)
    }

    @Test
    fun publicCatalogUrlsAreAnonymousSafe() {
        assertEquals("https://www.wenku8.net/zt/sugoi/2026.php", Wenku8Urls.sugoi(2026))
        assertEquals("https://www.wenku8.net/zt/booklist/202609.php", Wenku8Urls.booklist("202609"))
        val author = Wenku8Urls.authorArticle("君川优树")
        assertTrue(author.startsWith("https://www.wenku8.net/modules/article/authorarticle.php?author="))
        // 登录门禁接口保持原样，代码不得试图规避
        assertTrue(Wenku8Urls.search("x", com.wenku8.epubstudio.model.SearchField.TITLE).contains("search.php"))
        assertTrue(Wenku8Urls.toplist("allvisit").contains("toplist.php"))
    }

    @Test
    fun localCatalogSearchRanksAndNormalizes() {
        val entries = listOf(
            CatalogEntry(id = "1", title = "关于我转生变成史莱姆这档事", author = "伏濑", wordCount = 900_000, updatedAt = "2026-01-01", sourceUrl = "u1", tags = listOf("异世界")),
            CatalogEntry(id = "2", title = "史莱姆", author = "伏濑", wordCount = 100, updatedAt = "2026-02-01", sourceUrl = "u2", tags = listOf("短篇")),
            CatalogEntry(id = "3", title = "ＳＬＩＭ　ＴＥＳＴ", author = "别人", wordCount = 50, updatedAt = "2026-03-01", sourceUrl = "u3", tags = listOf("异世界")),
        )
        val index = CatalogIndex(entries)
        assertEquals(3, index.size)

        // 全角 -> 半角 + 大小写归一
        val byTitle = index.search("slim test", CatalogSearchField.TITLE)
        assertEquals(listOf("3"), byTitle.map { it.id })

        // 完全匹配优先于包含匹配
        val ranked = index.search("史莱姆", CatalogSearchField.TITLE).map { it.id }
        assertEquals("2", ranked.first())
        assertTrue(ranked.contains("1"))

        // 作者搜索
        assertTrue(index.search("伏濑", CatalogSearchField.AUTHOR).map { it.id }.containsAll(listOf("1", "2")))

        // 标签检索
        assertTrue(index.searchTag("异世界").map { it.id }.containsAll(listOf("1", "3")))

        // 空白查询返回空
        assertTrue(index.search("   ", CatalogSearchField.TITLE).isEmpty())
    }

    @Test
    fun catalogStatsRoundTrip() {
        val stats = CatalogStats(count = 12, lastUpdatedAt = 99L, totalFetched = 30, skipped = 2)
        val encoded = Json.encodeToString(CatalogStats.serializer(), stats)
        assertEquals(stats, Json.decodeFromString(CatalogStats.serializer(), encoded))
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

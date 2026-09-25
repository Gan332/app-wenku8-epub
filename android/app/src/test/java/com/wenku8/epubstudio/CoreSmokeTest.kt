package com.wenku8.epubstudio

import com.wenku8.epubstudio.core.Wenku8Parser
import com.wenku8.epubstudio.core.Wenku8Url
import com.wenku8.epubstudio.epub.EpubBuilder
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
    fun epubStartsWithUncompressedMimetype() {
        val directory = createTempDir("wenku8-epub-test")
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
        } finally {
            directory.deleteRecursively()
        }
    }
}

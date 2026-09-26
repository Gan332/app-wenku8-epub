package com.example.hyperreader

import com.example.hyperreader.core.Wenku8Parser
import com.example.hyperreader.epub.EpubBuilder
import com.example.hyperreader.model.Book
import com.example.hyperreader.model.Chapter
import com.example.hyperreader.model.ContentBlock
import com.example.hyperreader.model.JobPhase
import com.example.hyperreader.model.ParsedChapter
import com.example.hyperreader.reader.ReaderBlock
import com.example.hyperreader.reader.toReaderBlocks
import com.example.hyperreader.service.ExportCache
import com.example.hyperreader.service.ExportJobManager
import com.example.hyperreader.ui.formatEta
import com.example.hyperreader.ui.phaseLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * 导出 EPUB 模块（0.9.5 重写）的测试：
 * 富文本还原与 XSS 剥离、导出缓存、卷级目录、ETA、在线端降级。
 */
class ExportPipelineTest {
    private val chapter = Chapter("1", "第一章", "https://www.wenku8.net/novel/2/1/1.html", "正文", 1)

    private val richHtml = """
        <html><body><div id="content">
        <p>甲 <b>粗</b> <span style="font-weight:bold">乙</span> <i>斜</i></p>
        <script>alert('xss')</script>
        <p>注入<a href="javascript:alert(1)" onclick="x()">点我</a></p>
        <p>普通段落。</p>
        </div></body></html>
    """.trimIndent()

    @Test
    fun richTextKeepsEmphasisAndStripsDanger() {
        val parsed = Wenku8Parser.parseChapter(richHtml, chapter, chapter.url, preserveInlineFormat = true)

        val rich = parsed.blocks.filterIsInstance<ContentBlock.Rich>()
        assertTrue("应产出富文本段落", rich.isNotEmpty())
        assertTrue(rich.any { it.html.contains("<b>粗</b>") })
        // style 规范化：font-weight:bold 的 span → <b>，属性绝不带出
        assertTrue(rich.any { it.html.contains("<b>乙</b>") })
        assertTrue(rich.any { it.html.contains("<i>斜</i>") })

        val all = rich.joinToString("\n") { it.html }
        assertFalse("script 必须被删", all.contains("script"))
        assertFalse("javascript: 必须消失", all.contains("javascript:"))
        assertFalse("事件属性必须消失", all.contains("onclick"))
        assertFalse("style 属性不得带出", all.contains("style="))

        // 无强调的普通段落保持纯文本，不无谓引入 HTML
        val texts = parsed.blocks.filterIsInstance<ContentBlock.Text>()
        assertTrue(texts.any { it.value.contains("普通段落") })
        // 注入段：a 标签展开为纯文本，内容保留、属性剥净
        assertTrue(texts.any { it.value.contains("注入") })
        assertTrue(texts.none { it.value.contains("<") })
        // 字数统计走 strip，不受标签影响
        assertTrue("textLength 应按纯文本计", parsed.textLength > 10)
    }

    @Test
    fun richTextDefaultOffKeepsLegacyPlainText() {
        // 在线链路走默认参数：行为必须与旧版完全一致（纯文本、零 Rich）
        val parsed = Wenku8Parser.parseChapter(richHtml, chapter, chapter.url)
        assertTrue(parsed.blocks.none { it is ContentBlock.Rich })
        val texts = parsed.blocks.filterIsInstance<ContentBlock.Text>()
        assertTrue(texts.any { it.value.contains("粗") })
        assertTrue(texts.none { it.value.contains("<") })
    }

    @Test
    fun stripRichHtmlRemovesTags() {
        assertEquals("强调文本", Wenku8Parser.stripRichHtml("<b>强调</b>文本"))
        assertEquals("a b", Wenku8Parser.stripRichHtml("a <i> </i> b").replace(Regex("\\s+"), " "))
    }

    @Test
    fun onlineAssemblyDegradesRichToPlainText() {
        // 在线端防御分支：Rich 只能降级为纯文本，标签不得显示给用户
        val parsed = ParsedChapter(
            id = "c1",
            title = "章",
            volume = "正文",
            order = 1,
            sourceUrl = "https://www.wenku8.net/novel/2/1/1.html",
            blocks = listOf(ContentBlock.Rich("<b>强调</b>文本"), ContentBlock.Text("普通")),
        )
        val paragraphs = parsed.toReaderBlocks().filterIsInstance<ReaderBlock.Paragraph>().map { it.text }
        assertEquals(listOf("强调文本", "普通"), paragraphs)
        assertTrue(paragraphs.none { it.contains("<") })
    }

    @Test
    fun exportCacheRoundTripAndTrim() {
        val dir = Files.createTempDirectory("export-cache-test").toFile()
        val cache = ExportCache(dir)
        val url = "https://www.wenku8.net/novel/2/1/1.html"

        // 页面：miss → null；写入后命中
        assertNull(cache.page(url))
        cache.putPage(url, "<html>正文</html>")
        assertEquals("<html>正文</html>", cache.page(url))

        // 图片：写入后命中同一文件；重复写返回同一缓存
        val source = File(dir, "src.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) }
        val stored = cache.putImage(url, source, "jpg")
        assertNotNull(stored)
        assertEquals(stored!!.absolutePath, cache.image(url)?.absolutePath)
        assertEquals(stored.absolutePath, cache.putImage(url, source, "jpg")?.absolutePath)

        // trim(0)：全部淘汰，读取回到 miss（安全地重下）
        cache.trim(0)
        assertNull(cache.page(url))
        assertNull(cache.image(url))
    }

    @Test
    fun navGroupsByVolumeAndKeepsLinkCount() {
        val dir = Files.createTempDirectory("epub-nav-test").toFile()
        val output = File(dir, "book.epub")
        val book = Book(
            title = "测试书",
            sourceUrl = "https://www.wenku8.net/book/1.htm",
            bookUrl = "https://www.wenku8.net/book/1.htm",
        )
        val chapters = listOf(
            ParsedChapter("c1", "第一章", "卷一", 1, "https://www.wenku8.net/novel/2/1/1.html", blocks = listOf(ContentBlock.Text("甲"), ContentBlock.Rich("<b>乙</b>"))),
            ParsedChapter("c2", "第二章", "卷二", 2, "https://www.wenku8.net/novel/2/1/2.html", blocks = listOf(ContentBlock.Text("丙"))),
        )
        EpubBuilder().build(book, chapters, emptyList(), null, output)

        val nav = ZipFile(output).use { zip ->
            String(zip.getInputStream(zip.getEntry("EPUB/nav.xhtml")).readBytes(), Charsets.UTF_8)
        }
        // 卷级分组：卷名是 span（不产生链接）
        assertTrue("nav 应含卷一分组", nav.contains("<span>卷一</span>"))
        assertTrue("nav 应含卷二分组", nav.contains("<span>卷二</span>"))
        // 链接数 = 书籍信息 + 2 章：卷名不产生额外链接，回读端（取 a[href]）不受层级影响
        assertEquals(3, Regex("<a href=").findAll(nav).count())
        // Rich 段落原样进 EPUB
        val chapter1 = ZipFile(output).use { zip ->
            String(zip.getInputStream(zip.getEntry("EPUB/text/chapter-0001.xhtml")).readBytes(), Charsets.UTF_8)
        }
        assertTrue("富文本强调应保留", chapter1.contains("<b>乙</b>"))
    }

    @Test
    fun estimateEtaSecondsTracksRemainingRequests() {
        // 5/10 章完成、插图 5 总 3 完 → 剩余 (10-5)+(5-3)=7 → 7+3 打包余量
        assertEquals(10, ExportJobManager.estimateEtaSeconds(5, 10, 3, 5))
        // 全部完成 → 不可估
        assertEquals(-1, ExportJobManager.estimateEtaSeconds(10, 10, 5, 5))
        // 尚未开始且图片未知 → 不可估
        assertEquals(-1, ExportJobManager.estimateEtaSeconds(0, 0, 0, 0))
    }

    @Test
    fun formatEtaAndPhaseLabelFormat() {
        assertEquals("", formatEta(-1))
        assertEquals("45 秒", formatEta(45))
        assertEquals("2 分钟", formatEta(125))
        assertEquals("1 小时", formatEta(3700))
        assertEquals("抓取章节", phaseLabel(JobPhase.fetching))
        assertEquals("下载插图", phaseLabel(JobPhase.images))
        assertEquals("打包 EPUB", phaseLabel(JobPhase.packaging))
    }
}

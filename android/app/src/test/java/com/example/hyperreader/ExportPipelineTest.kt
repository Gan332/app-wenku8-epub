package com.example.hyperreader

import com.example.hyperreader.core.Wenku8Parser
import com.example.hyperreader.epub.EpubBuilder
import com.example.hyperreader.model.Book
import com.example.hyperreader.model.Chapter
import com.example.hyperreader.model.ContentBlock
import com.example.hyperreader.model.DownloadedImage
import com.example.hyperreader.model.JobPhase
import com.example.hyperreader.model.ParsedChapter
import com.example.hyperreader.reader.EpubNative
import com.example.hyperreader.reader.EpubReaderRepository
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
        assertEquals("段落必须按 p 边界切分（否则全书合并成一段）", 2, texts.size)
        assertTrue("普通段落应保持 Text，实际：$texts", texts.any { it.value.contains("普通段落") })
        // 注入段：a 标签展开为纯文本，内容保留、属性剥净
        assertTrue("注入段应保持 Text，实际：$texts", texts.any { it.value.contains("注入") })
        assertTrue("Text 不得含标签：$texts", texts.none { it.value.contains("<") })
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

    @Test
    fun nativeJsonMapsToReaderBook() {
        // Rust 侧（libepub_core.so）返回的 JSON → ReaderBook 映射：
        // HTML→blocks 复用 Jsoup parseBlocks，图片相对路径与 legacy 同一算法
        val json = """
            {"packageDir":"EPUB","title":"书名","author":"作者","language":"zh-CN","chapters":[
              {"id":"c1","title":"第一章","href":"EPUB/text/ch1.xhtml",
               "html":"<html><body><h1>第一章</h1><p>正文一段。</p><img src=\"../images/a.jpg\" alt=\"图1\"/></body></html>"},
              {"id":"c2","title":"第二章","href":"EPUB/text/ch2.xhtml","html":"<html><body><p>正文二。</p></body></html>"}
            ]}
        """.trimIndent()
        val book = EpubReaderRepository().fromNativeJson("local:test", json, File("/tmp/any.epub"))
        assertEquals("书名", book.title)
        assertEquals("作者", book.author)
        assertEquals(2, book.chapters.size)
        assertEquals("第一章", book.chapters[0].title)
        val image = book.chapters[0].blocks.filterIsInstance<ReaderBlock.Image>().single()
        // 图片路径：EPUB/text + ../images → 归一为 EPUB/images/a.jpg（与 legacy resolvePath 一致）
        assertEquals("EPUB/images/a.jpg", image.path)
        assertTrue(book.chapters[1].blocks.any { it is ReaderBlock.Paragraph })
    }

    @Test
    fun parseArchiveFallsBackToLegacyWithoutNative() {
        // JVM 单测环境没有 libepub_core.so → available=false → 必须走 legacy zip+Jsoup 且成功
        assertFalse("JVM 单测不应加载 native so", EpubNative.available)
        val dir = Files.createTempDirectory("epub-fallback").toFile()
        val output = File(dir, "book.epub")
        val book = Book(
            title = "回退测试书",
            sourceUrl = "https://www.wenku8.net/book/1.htm",
            bookUrl = "https://www.wenku8.net/book/1.htm",
        )
        val chapters = listOf(
            ParsedChapter("c1", "第一章", "卷一", 1, "https://www.wenku8.net/novel/2/1/1.html", blocks = listOf(ContentBlock.Text("正文甲。"))),
            ParsedChapter("c2", "第二章", "卷一", 2, "https://www.wenku8.net/novel/2/1/2.html", blocks = listOf(ContentBlock.Text("正文乙。"))),
        )
        EpubBuilder().build(book, chapters, emptyList(), null, output)
        val parsed = EpubReaderRepository().parseArchive("local:fb", output)
        assertEquals("回退测试书", parsed.title)
        // spine 全量进章节（含扉页 title.xhtml）——与阅读器现状一致
        assertEquals(3, parsed.chapters.size)
        assertEquals("书籍信息", parsed.chapters[0].title)
        assertEquals("第一章", parsed.chapters[1].title)
        assertTrue(parsed.chapters[1].blocks.any { it is ReaderBlock.Paragraph })
    }

    /**
     * 自产 EPUB 的回读回归（本组测试的核心）。
     *
     * EpubBuilder 产出的章节是 `<body><section epub:type="chapter">`，图片包一层 `<figure>`。
     * 原先 parseBlocks 只遍历 `body.children()` 一层且只认裸 `img`，于是每章塌成一个段落、
     * **插图全部消失**——而旧断言只有 `any { it is ReaderBlock.Paragraph }`，恰好测不出来。
     */
    @Test
    fun selfProducedEpubKeepsParagraphsAndImagesOnReadback() {
        val dir = Files.createTempDirectory("epub-illustration").toFile()
        val output = File(dir, "book.epub")
        val image = File(dir, "image-0001.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) }
        val book = Book(
            title = "插图测试书",
            sourceUrl = "https://www.wenku8.net/book/2.htm",
            bookUrl = "https://www.wenku8.net/book/2.htm",
        )
        val chapters = listOf(
            ParsedChapter(
                id = "c1",
                title = "彩页",
                volume = "彩页",
                order = 1,
                url = "https://www.wenku8.net/novel/2/1/1.html",
                blocks = listOf(
                    ContentBlock.Text("图前正文。"),
                    ContentBlock.Image(0),
                    ContentBlock.Text("图后正文。"),
                ),
            ),
        )
        val downloaded = DownloadedImage(
            chapterId = "c1",
            sourceId = "c1",
            chapterIndex = 0,
            globalIndex = 0,
            fileName = "image-0001.png",
            manifestId = "img-0001",
            mime = "image/png",
            localPath = image.absolutePath,
            ext = "png",
            bytes = image.length(),
        )
        EpubBuilder().build(book, chapters, listOf(downloaded), null, output)

        val parsed = EpubReaderRepository().parseArchive("local:illu", output)
        val chapter = parsed.chapters.first { it.title == "彩页" }
        val blocks = chapter.blocks

        // 插图必须还在：路径归一到 manifest 里登记的 EPUB/images/image-0001.png
        val readImage = blocks.filterIsInstance<ReaderBlock.Image>().singleOrNull()
        assertNotNull("回读后插图丢失：$blocks", readImage)
        assertEquals("EPUB/images/image-0001.png", readImage!!.path)

        // 段落不能塌成一段：图前图后各成一块
        val paragraphs = blocks.filterIsInstance<ReaderBlock.Paragraph>().map { it.text }
        assertTrue("正文段落应保留分段：$paragraphs", paragraphs.size >= 2)
        assertTrue(paragraphs.any { it.contains("图前正文") })
        assertTrue(paragraphs.any { it.contains("图后正文") })

        // 块顺序必须与原文一致，不能因为递归而把图甩到段末
        val imageIndex = blocks.indexOfFirst { it is ReaderBlock.Image }
        val before = blocks.take(imageIndex).filterIsInstance<ReaderBlock.Paragraph>().any { it.text.contains("图前正文") }
        val after = blocks.drop(imageIndex).filterIsInstance<ReaderBlock.Paragraph>().any { it.text.contains("图后正文") }
        assertTrue("插图与段落顺序错乱：$blocks", before && after)
    }

    /** POTATO 产物的容器是 `<div id="content">` + `<div class="div_image">`，同样必须解析出图。 */
    @Test
    fun potatoStyleNestingAlsoKeepsImages() {
        val json = """
            {"packageDir":"EPUB","title":"书名","author":"作者","language":"zh-CN","chapters":[
              {"id":"c1","title":"彩页","href":"EPUB/c1.xhtml",
               "html":"<html><body><div id=\"content\"><div class=\"div_image\"><img src=\"images/b.png\" alt=\"彩页\"/></div><p>尾部正文。</p></div></body></html>"}
            ]}
        """.trimIndent()
        val parsed = EpubReaderRepository().fromNativeJson("local:potato", json, File("/tmp/any.epub"))
        val blocks = parsed.chapters.single().blocks
        assertEquals("EPUB/images/b.png", blocks.filterIsInstance<ReaderBlock.Image>().single().path)
        assertTrue(blocks.filterIsInstance<ReaderBlock.Paragraph>().any { it.text.contains("尾部正文") })
    }

    /** 懒加载图必须按 `data-base` 基准解析——与 Web 版 `test/parsers.test.js` 对等。 */
    @Test
    fun resolvesLazyImageAgainstDataBase() {
        val chapterUrl = "https://www.wenku8.net/novel/2/2835/113354.htm"
        val html = "<div id=\"content\"><p>这是足够长的章节正文内容。</p>" +
            "<img data-base=\"/image-root/\" data-src=\"cover/a.jpg\"><p>后续正文仍然存在。</p></div>"
        val parsed = Wenku8Parser.parseChapter(
            html,
            chapter.copy(url = chapterUrl),
            chapterUrl,
        )
        assertEquals(listOf("https://www.wenku8.net/image-root/cover/a.jpg"), parsed.imageUrls)
        assertEquals(listOf(ContentBlock.Image(0)), parsed.blocks.filterIsInstance<ContentBlock.Image>())
    }

    /** 缓存命中不得把 `putImage` 中途失败留下的 `.tmp` 当成正式图片。 */
    @Test
    fun exportCacheIgnoresLeftoverTmpFiles() {
        val dir = Files.createTempDirectory("export-cache-tmp").toFile()
        val cache = ExportCache(dir)
        val url = "https://img.wenku8.com/image/a.png"
        val source = File(dir, "src.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50)) }
        val stored = requireNotNull(cache.putImage(url, source, "png"))
        assertNotNull("正式文件必须命中", cache.image(url))

        // 模拟进程在 copy 与 rename 之间被杀：正式文件没了，只剩 `${key}.png.tmp`
        assertTrue(stored.delete())
        File(stored.parentFile, "${stored.name}.tmp").writeBytes(byteArrayOf(0x89.toByte(), 0x50))
        assertNull("`.tmp` 残留不得被当成缓存命中，否则 EPUB 里会少一张图且无告警", cache.image(url))
    }
}

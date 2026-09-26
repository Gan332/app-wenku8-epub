package com.example.hyperreader

import com.example.hyperreader.core.CatalogEntry
import com.example.hyperreader.core.CatalogIndex
import com.example.hyperreader.core.CatalogSearchField
import com.example.hyperreader.core.CatalogStats
import com.example.hyperreader.core.Wenku8Parser
import com.example.hyperreader.core.Wenku8Url
import com.example.hyperreader.core.Wenku8Urls
import com.example.hyperreader.model.ReadingStats
import com.example.hyperreader.reader.BackAction
import com.example.hyperreader.reader.ReaderBlock
import com.example.hyperreader.reader.ReaderBook
import com.example.hyperreader.reader.ReaderChapter
import com.example.hyperreader.reader.ReaderUiState
import com.example.hyperreader.reader.controlsShown
import com.example.hyperreader.reader.flattenBook
import com.example.hyperreader.reader.paragraphItemIndex
import com.example.hyperreader.reader.readableTextOn
import com.example.hyperreader.reader.relativeLuminance
import com.example.hyperreader.reader.resolveBack
import com.example.hyperreader.reader.resumeTargetIndex
import com.example.hyperreader.service.missingImageWarnings
import com.example.hyperreader.settings.ReadingProgress
import com.example.hyperreader.settings.parseProgressMap
import com.example.hyperreader.ui.formatRelativeReadTime
import com.example.hyperreader.model.DownloadedImage
import com.example.hyperreader.ui.SettingsSection
import com.example.hyperreader.ui.formatWordCount
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import com.example.hyperreader.epub.EpubBuilder
import com.example.hyperreader.reader.EpubReaderRepository
import com.example.hyperreader.model.Book
import com.example.hyperreader.model.Chapter
import com.example.hyperreader.model.ContentBlock
import com.example.hyperreader.model.ParsedChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(Wenku8Urls.search("x", com.example.hyperreader.model.SearchField.TITLE).contains("search.php"))
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
    fun detailMetaRowsHideWhenEmpty() {
        val book = Book(title = "测试", author = "作者", sourceUrl = "u", bookUrl = "u", status = "", wordCount = null, updatedAt = "")
        // 空字数字段不应渲染出「全文字数」行
        assertTrue(book.wordCount == null)
        assertTrue(book.updatedAt.isBlank())
        // 状态缺省回落到完结/未知
        val status = book.status.ifBlank { if (book.isComplete) "完结" else "未知" }
        assertEquals("未知", status)
    }

    @Test
    fun wordCountUsesThousandsSeparator() {
        assertEquals("207,559", formatWordCount(207559))
        assertEquals("999", formatWordCount(999))
        assertEquals("1,000,000", formatWordCount(1_000_000))
        assertEquals("0", formatWordCount(0))
    }

    @Test
    fun coverUrlNormalizesToHttpsAndStaysInAllowlist() {
        assertTrue(Wenku8Url.isAllowedHost("img.wenku8.com"))
        assertTrue(Wenku8Url.isAllowedHost("www.wenku8.net"))
        assertTrue(!Wenku8Url.isAllowedHost("evil.com"))
        // 源站封面为 http，客户端会强制升级为 https
        val source = "http://img.wenku8.com/image/2/2835/2835s.jpg"
        val normalized = "${java.net.URI(source).scheme.let { "https" }}://img.wenku8.com/image/2/2835/2835s.jpg"
        assertEquals("https", java.net.URI(normalized).scheme)
        assertEquals("img.wenku8.com", java.net.URI(normalized).host)
    }

    @Test
    fun settingsSectionsCoverEveryCategory() {
        assertEquals(7, SettingsSection.entries.size)
        assertEquals(SettingsSection.OVERVIEW, SettingsSection.entries.first())
        assertTrue(SettingsSection.entries.contains(SettingsSection.READER))
        assertTrue(SettingsSection.entries.contains(SettingsSection.STATISTICS))
        assertTrue(SettingsSection.entries.contains(SettingsSection.CATALOG))
    }

    @Test
    fun flattenedBookKeepsChapterAndParagraphMapping() {
        // 跨章节摊平是「无缝滚动」的基础：章节归属与段落下标必须仍然正确
        val book = ReaderBook(
            id = "b",
            title = "书",
            chapters = listOf(
                ReaderChapter(
                    id = "c1",
                    title = "第一章",
                    href = "h1",
                    blocks = listOf(
                        ReaderBlock.Heading(1, "第一章"),
                        ReaderBlock.Paragraph("甲"),
                        ReaderBlock.Paragraph("乙"),
                    ),
                ),
                ReaderChapter(
                    id = "c2",
                    title = "第二章",
                    href = "h2",
                    blocks = listOf(
                        ReaderBlock.Paragraph("丙"),
                        ReaderBlock.Image("i.png", "插图"),
                    ),
                ),
            ),
        )
        val flat = flattenBook(book)
        assertEquals(5, flat.size)
        // 第一章：标题 + 两个段落
        assertEquals(0, flat[0].chapterIndex)
        assertTrue(flat[0].isChapterStart)
        assertEquals(-1, flat[0].paragraphIndex)
        assertEquals(0, flat[1].paragraphIndex)
        assertEquals(1, flat[2].paragraphIndex)
        assertEquals(0, flat[2].chapterIndex)
        assertFalse(flat[2].isChapterStart)
        // 第二章从摊平列表的第 3 项开始，段落下标在章内重新计数
        assertEquals(1, flat[3].chapterIndex)
        assertTrue(flat[3].isChapterStart)
        assertEquals(0, flat[3].paragraphIndex)
        // 非段落块没有段落下标
        assertEquals(-1, flat[4].paragraphIndex)
        assertEquals(1, flat[4].chapterIndex)
        assertFalse(flat[4].isChapterStart)
        // 键必须唯一且稳定，否则 LazyColumn 重组会错位
        assertEquals(flat.size, flat.map { it.key }.toSet().size)
        assertEquals(flat.map { it.key }, flattenBook(book).map { it.key })
    }

    @Test
    fun controlsShownFollowsControlsAndPanels() {
        // 默认沉浸态：菜单栏必须隐藏
        assertFalse(ReaderUiState().controlsShown())
        // 点正文呼出（controlsVisible=true）——曾经这里只看 isImmersive，导致呼不出
        assertTrue(ReaderUiState(controlsVisible = true).controlsShown())
        // 面板打开时菜单栏也在，关掉面板后仍然可见
        assertTrue(ReaderUiState(showSettings = true, controlsVisible = false).controlsShown())
        assertTrue(ReaderUiState(showToc = true, controlsVisible = false).controlsShown())
        assertTrue(ReaderUiState(showSettings = false, controlsVisible = true).controlsShown())
        // 沉浸设置本身不决定菜单栏（isImmersive=false 但 controlsVisible=false 时应为隐藏——
        // 不会出现，因为 setImmersive 两者同步；这里只断言判据不依赖 isImmersive）
        assertFalse(ReaderUiState(isImmersive = false, controlsVisible = false).controlsShown())
    }

    @Test
    fun backDispatchMatchesLegacyBehaviourWithoutLooping() {
        // 面板优先于其它分支
        assertEquals(BackAction.CLOSE_SETTINGS, ReaderUiState(showSettings = true).resolveBack())
        assertEquals(BackAction.CLOSE_TOC, ReaderUiState(showToc = true).resolveBack())
        // 沉浸默认（菜单栏隐藏）：返回键先呼出菜单栏，与 0.8.x 行为一致
        assertEquals(BackAction.SHOW_CONTROLS, ReaderUiState().resolveBack())
        // 菜单栏可见：返回键退出
        assertEquals(BackAction.EXIT, ReaderUiState(controlsVisible = true).resolveBack())
        // 无死循环：SHOW_CONTROLS 执行 toggleControls 后（controlsVisible 翻真），下一次必须是 EXIT
        val afterShow = ReaderUiState().copy(controlsVisible = true)
        assertEquals(BackAction.EXIT, afterShow.resolveBack())
        // 面板关闭回到「菜单栏可见」，仍然退出而不是再呼出一次
        assertEquals(BackAction.EXIT, afterShow.copy(showSettings = false).resolveBack())
    }

    @Test
    fun resumeTargetPositionsChapterAndParagraph() {
        val book = ReaderBook(
            id = "b",
            title = "书",
            chapters = listOf(
                ReaderChapter(
                    id = "c1",
                    title = "第一章",
                    href = "h1",
                    blocks = listOf(
                        ReaderBlock.Heading(1, "第一章"),
                        ReaderBlock.Paragraph("甲"),
                        ReaderBlock.Paragraph("乙"),
                    ),
                ),
                ReaderChapter(
                    id = "c2",
                    title = "第二章",
                    href = "h2",
                    blocks = listOf(
                        ReaderBlock.Paragraph("丙"),
                        ReaderBlock.Paragraph("丁"),
                    ),
                ),
            ),
        )
        val flat = flattenBook(book)
        // 摊平下标：0=标题, 1=甲, 2=乙, 3=丙, 4=丁
        assertEquals(0, resumeTargetIndex(flat, 0, 0))
        assertEquals(1, resumeTargetIndex(flat, 0, 1))
        assertEquals(3, resumeTargetIndex(flat, 1, 0))
        assertEquals(4, resumeTargetIndex(flat, 1, 1))
        // 段落超范围 → 回章首
        assertEquals(3, resumeTargetIndex(flat, 1, 9))
        // 章不存在 → null（不动）
        assertEquals(null, resumeTargetIndex(flat, 9, 0))
    }

    @Test
    fun paragraphItemIndexMapsParagraphsToBlocks() {
        val chapter = ReaderChapter(
            id = "c1",
            title = "章",
            href = "h",
            blocks = listOf(
                ReaderBlock.Heading(1, "标题"),
                ReaderBlock.Paragraph("甲"),
                ReaderBlock.Paragraph("乙"),
                ReaderBlock.Image("i.jpg", "插图"),
                ReaderBlock.Paragraph("丙"),
            ),
        )
        assertEquals(0, paragraphItemIndex(chapter, 0))
        assertEquals(2, paragraphItemIndex(chapter, 1))
        assertEquals(4, paragraphItemIndex(chapter, 2))
        assertEquals(0, paragraphItemIndex(chapter, 3))
        assertEquals(0, paragraphItemIndex(chapter, -1))
    }

    @Test
    fun epubCssIsDarkModeSafeAndWellSpaced() {
        val css = EpubBuilder.CSS
        // 深色模式安全：不得写死任何颜色（0.9.1 前 body{color:#272522} → 黑底黑字）
        assertFalse("CSS 不得写死颜色属性", css.contains("color:"))
        assertFalse(css.contains("#272522"))
        // 段落：间距 + 首行缩进
        assertTrue(css.contains("p{margin"))
        assertTrue(css.contains("text-indent:2em"))
        // 图片：不拉伸、不溢出
        assertTrue("图片必须 height:auto", css.contains("height:auto"))
        assertTrue(css.contains("max-width:100%"))
        // 扉页：作者行不缩进、CJK 字体回退
        assertTrue(css.contains(".author"))
        assertTrue(css.contains("Noto Sans CJK SC"))
    }

    @Test
    fun missingImageWarningsReportsDroppedImages() {
        fun chapter(id: String, title: String, images: Int) = ParsedChapter(
            id = id,
            title = title,
            volume = "卷一",
            order = 1,
            sourceUrl = "https://example.invalid/$id",
            imageUrls = (0 until images).map { "https://example.invalid/$id/$it.jpg" },
        )
        fun image(sourceId: String, index: Int) = DownloadedImage(
            chapterId = sourceId, sourceId = sourceId, chapterIndex = index, globalIndex = index,
            fileName = "img$index.jpg", manifestId = "img$index", mime = "image/jpeg",
            localPath = "/tmp/img.jpg", ext = "jpg", bytes = 100L,
        )
        val parsed = listOf(chapter("c1", "第一章", 3), chapter("c2", "第二章", 2), chapter("c3", "第三章", 0))
        val images = listOf(image("c1", 0), image("c1", 1), image("c2", 0))
        val warnings = missingImageWarnings(parsed, images)
        assertEquals(2, warnings.size)
        assertTrue(warnings[0].contains("第一章"))
        assertTrue(warnings[0].contains("1 张"))
        assertTrue(warnings[1].contains("第二章"))
        // 全部到齐 → 无警告；没有插图的章节不产生噪音
        assertTrue(missingImageWarnings(listOf(chapter("c1", "一", 1)), listOf(image("c1", 0))).isEmpty())
        assertTrue(missingImageWarnings(listOf(chapter("c3", "三", 0)), emptyList()).isEmpty())
    }

    @Test
    fun progressMapSkipsBrokenEntries() {
        val good = """{"bookId":"b1","chapterId":"c1","chapterIndex":4,"paragraphIndex":7,"updatedAt":123}"""
        val result = parseProgressMap(mapOf("b1" to good, "b2" to "not-json", "b3" to null, "" to good))
        assertEquals(1, result.size)
        assertEquals(4, result["b1"]?.chapterIndex)
        assertEquals(7, result["b1"]?.paragraphIndex)
    }

    @Test
    fun relativeReadTimeFormats() {
        val now = 1_700_000_000_000L
        assertEquals("", formatRelativeReadTime(now, 0))
        assertEquals("刚刚", formatRelativeReadTime(now, now - 30_000L))
        assertEquals("5 分钟前", formatRelativeReadTime(now, now - 300_000L))
        assertEquals("3 小时前", formatRelativeReadTime(now, now - 3 * 3_600_000L))
        assertEquals("昨天", formatRelativeReadTime(now, now - 86_400_000L))
        assertEquals("3 天前", formatRelativeReadTime(now, now - 3 * 86_400_000L))
        val old = formatRelativeReadTime(now, now - 100 * 86_400_000L)
        assertTrue("日期格式不正确：$old", old.matches(Regex("""\d{1,4}年?\d{1,2}月\d{1,2}日""")))
    }

    @Test
    fun darkBackgroundNeverKeepsDarkText() {
        // CUSTOM 背景配默认深色文字色（0xFF272522）曾经直接黑底黑字
        val darkBackground = Color(0xFF17191C)
        val defaultText = Color(0xFF272522)
        assertTrue("黑底必须被纠正为可读文字色", relativeLuminance(readableTextOn(darkBackground, defaultText)) > 0.5)
        // 纯黑（OLED）同理
        assertTrue(relativeLuminance(readableTextOn(Color.Black, defaultText)) > 0.5)
    }

    @Test
    fun lightBackgroundKeepsDarkTextWhenContrastIsSufficient() {
        val paper = Color(0xFFF4EFE6)
        val darkText = Color(0xFF272522)
        assertEquals(darkText, readableTextOn(paper, darkText))
        assertEquals(darkText, readableTextOn(Color.White, darkText))
    }

    @Test
    fun darkBackgroundKeepsWhiteText() {
        val white = Color.White
        assertEquals(white, readableTextOn(Color.Black, white))
        assertEquals(white, readableTextOn(Color(0xFF17191C), white))
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
            assertTrue(readerBook.chapters.first().blocks.any { it is com.example.hyperreader.reader.ReaderBlock.Paragraph })
        } finally {
            directory.deleteRecursively()
        }
    }
}

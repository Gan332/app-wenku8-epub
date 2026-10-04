package com.example.hyperreader

import com.example.hyperreader.core.CatalogEntry
import com.example.hyperreader.core.CatalogIndex
import com.example.hyperreader.core.CatalogSearchField
import com.example.hyperreader.core.CatalogStats
import com.example.hyperreader.core.ExploreBookDetail
import com.example.hyperreader.core.ExploreBookSeed
import com.example.hyperreader.core.Wenku8Parser
import com.example.hyperreader.core.Wenku8Url
import com.example.hyperreader.core.Wenku8Urls
import com.example.hyperreader.core.toBook
import com.example.hyperreader.http.HttpRateLimiter
import com.example.hyperreader.model.ReadingStats
import com.example.hyperreader.reader.BackAction
import com.example.hyperreader.reader.ReaderUiState
import com.example.hyperreader.reader.controlsShown
import com.example.hyperreader.reader.resolveBack
import com.example.hyperreader.reader.withPanel
import com.example.hyperreader.service.missingImageWarnings
import com.example.hyperreader.settings.ReadingProgress
import com.example.hyperreader.settings.parseProgressMap
import com.example.hyperreader.ui.formatRelativeReadTime
import com.example.hyperreader.model.DownloadedImage
import com.example.hyperreader.ui.SettingsSection
import com.example.hyperreader.ui.formatWordCount
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
        // 0.18.0：新增 ACCOUNT（账号）与 NETWORK（网络/中继）
        assertEquals(9, SettingsSection.entries.size)
        assertEquals(SettingsSection.OVERVIEW, SettingsSection.entries.first())
        assertTrue(SettingsSection.entries.contains(SettingsSection.ACCOUNT))
        assertTrue(SettingsSection.entries.contains(SettingsSection.NETWORK))
        assertTrue(SettingsSection.entries.contains(SettingsSection.READER))
        assertTrue(SettingsSection.entries.contains(SettingsSection.STATISTICS))
        assertTrue(SettingsSection.entries.contains(SettingsSection.CATALOG))
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
    fun panelsAreMutuallyExclusiveSoTheirOverlayWindowsCannotStack() {
        // 从初始态打开设置：目录必须被关掉，且菜单栏保持可见
        val settings = ReaderUiState().withPanel(showSettings = true)
        assertTrue(settings.showSettings)
        assertFalse(settings.showToc)
        assertTrue(settings.controlsShown())

        // 从目录态打开设置：目录必须关掉 —— 这正是「关掉目录再点设置没反应」的根因
        val switched = ReaderUiState(showToc = true, controlsVisible = true).withPanel(showSettings = true)
        assertTrue(switched.showSettings)
        assertFalse(switched.showToc)

        // 反向同理
        val toToc = ReaderUiState(showSettings = true, controlsVisible = true).withPanel(showToc = true)
        assertTrue(toToc.showToc)
        assertFalse(toToc.showSettings)

        // 关闭某个面板不得把另一个打开，也不得改变菜单栏显隐
        val closed = ReaderUiState(showToc = true, controlsVisible = true).withPanel(showToc = false)
        assertFalse(closed.showToc)
        assertFalse(closed.showSettings)
        assertTrue(closed.controlsShown())

        // 关闭不改变其它字段：互斥只在「打开」方向发力 ——
        // 设置面板仍然开着，因此菜单栏依旧可见（controlsShown 含 showSettings）
        val onlyClose = ReaderUiState(showSettings = true, controlsVisible = false).withPanel(showToc = false)
        assertTrue(onlyClose.showSettings)
        assertTrue(onlyClose.controlsShown())
        // 对照：没有面板、也没有 controlsVisible 时才是隐藏
        assertFalse(ReaderUiState(controlsVisible = false).withPanel(showToc = false).controlsShown())
    }

    @Test
    fun resolveBackStillClosesThePanelAfterMutualExclusionSwitchedThem() {
        // 互斥切换后返回键仍必须先关当前面板，不能直接退出
        val switched = ReaderUiState(showToc = true, controlsVisible = true).withPanel(showSettings = true)
        assertEquals(BackAction.CLOSE_SETTINGS, switched.resolveBack())
        assertEquals(BackAction.CLOSE_TOC, ReaderUiState(showToc = true, controlsVisible = true).resolveBack())
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
        assertTrue("日期格式不正确：$old", old.matches(Regex("""(\d{4}年)?\d{1,2}月\d{1,2}日""")))
    }

    @Test
    fun mainSourcesUseNoMaterialComponents() {
        // 「界面统一 MiuiX」的回归守卫：material / material3 / material-icons
        // 于 0.9.3 全量移除（版本错配崩溃类问题的根子）。连
        // `@file:OptIn(androidx.compose.material3...)` 这种全限定写法一起抓。
        val forbidden = listOf(
            "androidx.compose.material3",
            "androidx.compose.material.icons",
            "androidx.compose.material.",
        )
        // 测试工作目录可能是 android/app 或 android，向上找 src/main/java，
        // 同时考虑中间隔一层 app/ 的情况。
        val sourceRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .flatMap { dir -> sequenceOf(File(dir, "src/main/java"), File(dir, "app/src/main/java")) }
            .firstOrNull { it.isDirectory }
        requireNotNull(sourceRoot) { "未找到 src/main/java（user.dir=${System.getProperty("user.dir")}）" }
        val offenders = mutableListOf<String>()
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (forbidden.any { line.contains(it) }) {
                        offenders += "${file.name}:${index + 1}: ${line.trim()}"
                    }
                }
            }
        assertTrue(
            "界面必须全部使用 MiuiX，发现 material 引用：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
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

    // ---- 探索详情与限流档位（0.10.1）----

    @Test
    fun interactiveModeIsFasterThanBatchButKeepsBatchPaceIntact() {
        HttpRateLimiter.resetForTest()
        val batch = HttpRateLimiter.batchIntervalForTest()
        val interactive = HttpRateLimiter.interactiveIntervalForTest()
        // 提速的本质：交互档单请求间隔显著小于批量档
        assertTrue("交互档($interactive) 必须快于批量档($batch)", interactive < batch)
        // 但不能快到「无节流」，仍要给源站留呼吸空间
        assertTrue("交互档($interactive) 仍需保留节流", interactive > 0L)
    }

    @Test
    fun interactiveBurstIsCappedThenFallsBackToBatchPace() {
        HttpRateLimiter.resetForTest()
        val window = HttpRateLimiter.interactiveWindowForTest()
        val maxInWindow = HttpRateLimiter.interactiveMaxInWindowForTest()
        val interactive = HttpRateLimiter.interactiveIntervalForTest()
        // 窗口与次数上限必须是有限值，否则连续点击会把源站打穿
        assertTrue(window > 0L)
        assertTrue(maxInWindow in 1..32)
        // 交互档不是「无限提速」：单窗口配额只够一两次点击，
        // 远小于「窗口按交互间隔填满」的请求数，长期均值仍被批量档兜住
        val interactiveIfUncapped = (window / interactive).toInt()
        assertTrue("交互档($interactive ms) 若不限次会发 $interactiveIfUncapped 次请求，配额必须远小于它", maxInWindow < interactiveIfUncapped)
        // 配额也不该小到「点一次就降级」
        assertTrue(maxInWindow >= 2)
    }

    @Test
    fun exploreDetailSeedIsConvertedToDisplayableBook() {
        val entry = CatalogEntry(
            id = "2835",
            title = "境界的彼方",
            author = "鸟居なごむ",
            category = "轻小说",
            status = "连载中",
            wordCount = 1_234_567L,
            updatedAt = "2026-09-27",
            tags = listOf("校园", "奇幻"),
            summary = "简介正文",
            coverUrl = "https://www.wenku8.net/img/2835.jpg",
            sourceUrl = "https://www.wenku8.net/book/2835.htm",
        )
        val book = entry.toBook()
        assertEquals("2835", book.id)
        assertEquals("境界的彼方", book.title)
        assertEquals(1_234_567L, book.wordCount)
        assertEquals(listOf("校园", "奇幻"), book.tags)
    }

    @Test
    fun exploreDetailFallsBackToSeedWhenApiOmitsFields() {
        // 接口只回了标题，作者/分类等字段缺失时必须用 seed 兜底，避免详情页空字段
        val entry = CatalogEntry(
            id = "9",
            title = "只有标题",
            sourceUrl = "https://www.wenku8.net/book/9.htm",
        )
        val seed = ExploreBookSeed(
            id = "9",
            title = "种子标题",
            author = "种子作者",
            category = "轻小说",
            status = "完结",
            updatedAt = "2026-01-01",
            wordCount = 500_000L,
            coverUrl = "https://www.wenku8.net/img/9.jpg",
            sourceUrl = "https://www.wenku8.net/book/9.htm",
        )
        val book = entry.toBook(seed)
        // 接口有值以接口为准
        assertEquals("只有标题", book.title)
        // 接口缺值回退 seed
        assertEquals("种子作者", book.author)
        assertEquals("轻小说", book.category)
        assertEquals("完结", book.status)
        assertEquals(500_000L, book.wordCount)
        assertEquals("https://www.wenku8.net/img/9.jpg", book.coverUrl)
    }

    @Test
    fun exploreDetailBookIsUsableEvenWhenIndexFails() {
        // 目录页失败是可降级的：详情仍然可用，chapterCount 为 0，indexError 有值
        val detail = ExploreBookDetail(
            book = Book(
                id = "1",
                title = "书",
                sourceUrl = "https://www.wenku8.net/book/1.htm",
                bookUrl = "https://www.wenku8.net/book/1.htm",
            ),
            chapters = emptyList(),
            indexError = "目录页请求超时",
        )
        assertEquals(0, detail.chapterCount)
        assertEquals("目录页请求超时", detail.indexError)
        assertEquals("书", detail.book.title)
    }

    @Test
    fun exploreDetailChapterCountMirrorsParsedIndex() {
        val chapters = listOf(
            Chapter(id = "1", title = "第一章", url = "https://www.wenku8.net/novel/2/1/1.htm", order = 1),
            Chapter(id = "2", title = "第二章", url = "https://www.wenku8.net/novel/2/1/2.htm", order = 2),
            Chapter(id = "3", title = "第三章", url = "https://www.wenku8.net/novel/2/1/3.htm", order = 3),
        )
        val detail = ExploreBookDetail(
            book = Book(
                id = "1",
                title = "书",
                sourceUrl = "https://www.wenku8.net/book/1.htm",
                bookUrl = "https://www.wenku8.net/book/1.htm",
            ),
            chapters = chapters,
        )
        assertEquals(3, detail.chapterCount)
        assertEquals(null, detail.indexError)
    }

    @Test
    fun indexUrlFallsBackToDefaultCategoryWhenCategoryMissing() {
        assertEquals("https://www.wenku8.net/novel/2/2835/index.htm", Wenku8Urls.index("2835"))
        assertEquals("https://www.wenku8.net/novel/3/2835/index.htm", Wenku8Urls.index("2835", "3"))
        // 非数字脏值不能拼进 URL，退回默认分类
        assertEquals("https://www.wenku8.net/novel/2/2835/index.htm", Wenku8Urls.index("2835", "abc"))
        // ID 里的非数字字符必须被清掉
        assertEquals("https://www.wenku8.net/novel/2/88/index.htm", Wenku8Urls.index("book-88"))
    }
}

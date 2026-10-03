package com.example.hyperreader

import com.example.hyperreader.core.Wenku8Exception
import com.example.hyperreader.core.Wenku8Parser
import com.example.hyperreader.core.Wenku8Urls
import com.example.hyperreader.core.Wenku8HttpClient
import com.example.hyperreader.core.SessionGate
import com.example.hyperreader.core.Wenku8SearchProvider
import com.example.hyperreader.model.SearchField
import com.example.hyperreader.ui.SearchPager
import com.example.hyperreader.model.SearchBook
import com.example.hyperreader.ui.StudioUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 登录墙内端点（search.php / toplist.php / tags.php）的解析夹具。
 *
 * search.php / tags.php / toplist.php 匿名访问会被 302 到登录页，拿不到真实 HTML，
 * 夹具按上游 `defaultplugin/wenku8` 的选择器结构合成：
 * - search.php 结果：`#content > div > a[href*=/book/]`，分页状态在 `em#pagestats`
 * - toplist.php 卡片：`#content > table > tbody > tr > td > div`
 * - tags.php 标签行：`div:nth-child(1)` 封面 + `div:nth-child(2) > b > a` 标题 + `p` 作者
 * - tags.php 标签锚点：`a[href~=tags\.php?t=...]`，t 参数为 GBK 字节的百分号编码
 */
class SessionExploreTest {

    @Test
    fun searchUrlIncludesGbKeywordAndOneBasedPage() {
        val title = Wenku8Urls.search("关于我转生", SearchField.TITLE, 3)
        assertTrue(title.startsWith("https://www.wenku8.net/modules/article/search.php?searchtype=articlename&searchkey="))
        assertTrue(title.endsWith("&page=3"))
        assertTrue(title.contains("%B9%D8"))

        val author = Wenku8Urls.search("伏濑", SearchField.AUTHOR, 2)
        assertTrue(author.contains("searchtype=author"))
        assertTrue(author.endsWith("&page=2"))
        assertTrue(runCatching { Wenku8Urls.search("x", SearchField.TITLE, 0) }.isFailure)
    }

    @Test
    fun searchPageParserReadsPageStatsAndDetectsLastPage() {
        val first = searchPageHtml(page = 1, pageCount = 3)
        val firstPage = Wenku8Parser.parseSearchPage(first, Wenku8Urls.SEARCH, 1)
        assertEquals(1, firstPage.page)
        assertEquals(3, firstPage.pageCount)
        assertTrue(firstPage.hasNextPage)
        assertEquals(listOf("1", "2"), firstPage.books.map { it.id })

        val last = searchPageHtml(page = 3, pageCount = 3, includeNext = false)
        val lastPage = Wenku8Parser.parseSearchPage(last, Wenku8Urls.SEARCH, 3)
        assertEquals(3, lastPage.page)
        assertEquals(3, lastPage.pageCount)
        assertFalse(lastPage.hasNextPage)
    }

    @Test
    fun searchPageParserFallsBackToNextAnchorAndStopsWithoutStats() {
        val withNext = searchPageHtml(page = 2, pageCount = null, includeNext = true)
        val next = Wenku8Parser.parseSearchPage(withNext, Wenku8Urls.SEARCH, 2)
        assertEquals(2, next.page)
        assertEquals(null, next.pageCount)
        assertTrue(next.hasNextPage)

        val withoutNext = searchPageHtml(page = 2, pageCount = null, includeNext = false)
        val stopped = Wenku8Parser.parseSearchPage(withoutNext, Wenku8Urls.SEARCH, 2)
        assertFalse(stopped.hasNextPage)
    }

    @Test
    fun searchPageParserTreatsRequestedPageAsTruthAndInfersTotalFromStats() {
        // 文案只有一个数字（年份/计数）时不能把它当成当前页，更不能当成总页数
        val yearOnly = Wenku8Parser.parseSearchPage(searchPageHtml(page = 1, pageCount = null, includeNext = true), Wenku8Urls.SEARCH, 1)
        assertEquals(1, yearOnly.page)
        assertEquals(null, yearOnly.pageCount)
        assertTrue(yearOnly.hasNextPage)

        // 「共N页」形式：总页数认出来了，但没有下一页锚点时仍以页数为准
        val totalOnly = Wenku8Parser.parseSearchPage(
            searchPageHtml(page = 1, pageCount = null, includeNext = false, stats = "共5页"),
            Wenku8Urls.SEARCH,
            1,
        )
        assertEquals(1, totalOnly.page)
        assertEquals(5, totalOnly.pageCount)
        assertTrue(totalOnly.hasNextPage)

        val singlePage = Wenku8Parser.parseSearchPage(
            searchPageHtml(page = 1, pageCount = null, includeNext = true, stats = "共1页"),
            Wenku8Urls.SEARCH,
            1,
        )
        assertEquals(1, singlePage.pageCount)
        assertFalse(singlePage.hasNextPage)

        // 斜杠形式里「当前页 > 总页数」说明这段文案不是分页状态，退回锚点判断
        val brokenStats = Wenku8Parser.parseSearchPage(
            searchPageHtml(page = 1, pageCount = null, includeNext = false, stats = "9/2"),
            Wenku8Urls.SEARCH,
            1,
        )
        assertEquals(null, brokenStats.pageCount)
        assertFalse(brokenStats.hasNextPage)
    }

    @Test
    fun searchPageParserRejectsLoginPage() {
        val html = "<html><head><title>用户登录</title></head><body><form action='/login.php'></form></body></html>"
        val error = runCatching { Wenku8Parser.parseSearchPage(html, Wenku8Urls.LOGIN, 1) }.exceptionOrNull()
        assertTrue(error is Wenku8Exception)
        assertEquals("AUTH_REQUIRED", (error as Wenku8Exception).code)
    }

    @Test
    fun searchProviderRejectsRequestBeforeLoginWithoutHttpClient() {
        val provider = Wenku8SearchProvider(Wenku8HttpClient(File(System.getProperty("java.io.tmpdir"))), FakeSessionGate(loggedIn = false))
        val error = runCatching {
            kotlinx.coroutines.runBlocking { provider.search("测试", SearchField.TITLE, 2) }
        }.exceptionOrNull()
        assertTrue(error is Wenku8Exception)
        assertEquals("AUTH_REQUIRED", (error as Wenku8Exception).code)
    }

    @Test
    fun searchProviderShortCircuitsBlankKeywordAndRejectsZeroPage() {
        var cleared = false
        val provider = Wenku8SearchProvider(
            Wenku8HttpClient(File(System.getProperty("java.io.tmpdir"))),
            FakeSessionGate(loggedIn = true) { cleared = true },
        )
        kotlinx.coroutines.runBlocking {
            val blank = provider.search("   ", SearchField.TITLE, 4)
            assertTrue(blank.books.isEmpty())
            assertFalse(blank.hasNextPage)
            assertEquals(4, blank.page)
        }
        assertFalse(cleared)
        assertTrue(runCatching { kotlinx.coroutines.runBlocking { provider.search("x", SearchField.TITLE, 0) } }.isFailure)
    }

    /** 单测替身：不碰 Context / Keystore，只表达「有没有会话」与「是否被清理」。 */
    private class FakeSessionGate(
        private val loggedIn: Boolean,
        private val onClear: () -> Unit = {},
    ) : SessionGate {
        override fun hasSession(): Boolean = loggedIn
        override fun clear() = onClear()
    }

    @Test
    fun crossPageMergeDedupesByIdAndStaleRequestCannotWin() {
        val first = listOf(searchBook("1", "第一页"), searchBook("2", "重复"))
        val second = listOf(searchBook("2", "重复-新页"), searchBook("3", "第三本"))
        val merged = SearchPager.append(first, second)
        assertEquals(listOf("1", "2", "3"), merged.map { it.id })
        assertEquals("重复", merged[1].title)

        val staleId = 1L
        val freshId = 2L
        assertFalse(SearchPager.isCurrent(staleId, freshId))
        assertTrue(SearchPager.isCurrent(freshId, freshId))
    }

    @Test
    fun pageStateOverwritesFirstSearchAndAppendsOrStopsMorePages() {
        val firstPage = Wenku8Parser.SearchPageData(
            books = listOf(searchBook("1", "旧结果"), searchBook("2", "重复")),
            page = 1,
            pageCount = 2,
            hasNextPage = true,
        )
        val first = SearchPager.firstPage(
            StudioUiState(searchResults = listOf(searchBook("9", "更早结果")), searchBusy = true),
            firstPage,
            loggedIn = true,
        )
        assertEquals(listOf("1", "2"), first.searchResults.map { it.id })
        assertEquals(1, first.searchPage)
        assertTrue(first.searchHasNextPage)
        assertFalse(first.searchBusy)

        val morePage = Wenku8Parser.SearchPageData(
            books = listOf(searchBook("2", "重复不覆盖"), searchBook("3", "新增")),
            page = 2,
            pageCount = 2,
            hasNextPage = false,
        )
        val more = SearchPager.morePage(first.copy(searchLoadingMore = true), morePage, loggedIn = true)
        assertEquals(listOf("1", "2", "3"), more.searchResults.map { it.id })
        assertEquals("重复", more.searchResults[1].title)
        assertEquals(2, more.searchPage)
        assertFalse(more.searchHasNextPage)
        assertFalse(more.searchLoadingMore)

        val emptyPage = morePage.copy(books = emptyList())
        val stopped = SearchPager.morePage(more.copy(searchLoadingMore = true), emptyPage, loggedIn = true)
        assertEquals(listOf("1", "2", "3"), stopped.searchResults.map { it.id })
        assertFalse(stopped.searchHasNextPage)
        assertFalse(stopped.searchLoadingMore)
    }

    private fun searchBook(id: String, title: String) = SearchBook(
        id = id,
        title = title,
        sourceUrl = Wenku8Urls.book(id),
    )

    private fun searchPageHtml(page: Int, pageCount: Int?, includeNext: Boolean, stats: String? = null): String {
        val statsText = stats ?: pageCount?.let { "当前页$page/$it 页" } ?: "当前页$page"
        val next = if (includeNext) "<a href='?searchkey=x&page=${page + 1}'>下一页</a>" else ""
        return """
            <html><body><div id='content'>
            <div><a href='/book/${page}01.htm'>第一页书</a><span>作者：作者甲</span></div>
            <div><a href='/book/${page}02.htm'>第二页书</a><span>作者：作者乙</span></div>
            <em id='pagestats'>$statsText</em>$next
            </div></body></html>
        """.trimIndent()
    }

    @Test
    fun tagListParserDecodesGbkParamsAndDedupes() {
        val html = """
            <html><body><div id="content">
            <a href="tags.php?t=%c1%b5%b0%ae">恋爱</a>
            <a href="/modules/article/tags.php?t=%d0%a3%d4%b0">校园</a>
            <a href="tags.php">全部标签</a>
            <a href="tags.php?t=">空</a>
            <a href="tags.php?t=%c1%b5%b0%ae">重复锚点</a>
            <a href="tags.php?t=%b0%d9%ba%cf&amp;v=1">百合</a>
            </div></body></html>
        """.trimIndent()
        val tags = Wenku8Parser.parseTagList(html, "https://www.wenku8.net/modules/article/tags.php")
        assertEquals(listOf("恋爱", "校园", "百合"), tags)
    }

    @Test
    fun tagListParserRejectsLoginPage() {
        val html = "<html><head><title>用户登录</title></head><body><form action=\"/login.php\"></form></body></html>"
        val error = runCatching { Wenku8Parser.parseTagList(html, "https://www.wenku8.net/login.php") }.exceptionOrNull()
        assertTrue(error is Wenku8Exception)
        assertEquals("AUTH_REQUIRED", (error as Wenku8Exception).code)
    }

    @Test
    fun toplistCardSurvivesTextlessCoverAnchorFirst() {
        // 封面链接（无文字）排在标题链接之前：id 必须等到标题解析成功才占位，
        // 否则整本书会被当成重复丢掉
        val html = """
            <html><body><div id="content"><table><tbody><tr><td><div>
            <div><a href="/book/2835.htm"><img src="/images/2023/2835.jpg" alt=""></a></div>
            <div><a href="/book/2835.htm" title="测试书">测试书</a><p>作者：测试作者 / 字数：207,000字 / 更新：2026-09-20</p></div>
            </div></td></tr></tbody></table></div></body></html>
        """.trimIndent()
        val results = Wenku8Parser.parseSearchResults(html, "https://www.wenku8.net/modules/article/toplist.php?sort=lastupdate")
        val book = results.single()
        assertEquals("2835", book.id)
        assertEquals("测试书", book.title)
        assertEquals("测试作者", book.author)
        assertEquals("2026-09-20", book.updatedAt)
        assertEquals(207000L, book.wordCount)
        // 封面在兄弟 div 里，文字容器找不到时向上爬一级
        assertEquals("https://www.wenku8.net/images/2023/2835.jpg", book.coverUrl)
    }

    @Test
    fun tagsRowExtractsAuthorAndCoverFromSiblingBlocks() {
        val html = """
            <html><body><div id="content"><table><tbody>
            <tr><td>分页</td></tr>
            <tr><td><div>
            <div><a href="/book/3988.htm"><img src="/images/c/3988.jpg"></a></div>
            <div><b><a href="/book/3988.htm">あちき volt</a></b><p>连载中</p><p>作者：测试作者 / 文库：富士见</p></div>
            </div></td></tr>
            </tbody></table></div></body></html>
        """.trimIndent()
        val book = Wenku8Parser.parseSearchResults(html, "https://www.wenku8.net/modules/article/tags.php?t=%c1%b5%b0%ae").single()
        assertEquals("3988", book.id)
        assertEquals("あちき volt", book.title)
        assertEquals("测试作者", book.author)
        assertEquals("https://www.wenku8.net/images/c/3988.jpg", book.coverUrl)
    }
}

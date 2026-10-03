package com.example.hyperreader

import com.example.hyperreader.core.Wenku8Endpoint
import com.example.hyperreader.core.Wenku8Parser
import com.example.hyperreader.core.Wenku8Url
import com.example.hyperreader.core.Wenku8Urls
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 第三方中继（AGENTS §4.11）的夹具。
 *
 * 断言三条底线：
 * 1. 默认关闭，未配置端点时行为与加中继前**逐字节一致**；
 * 2. 中继只作用于公开页端点，`search.php` / `toplist.php` / `tags.php` / `book()` 始终直连；
 * 3. 解析出口把中继 URL 还原成 wenku8 原域，书架与 EPUB 内链不会指向第三方。
 */
class RelayEndpointTest {

    @Before
    fun setUp() = reset()

    @After
    fun tearDown() = reset()

    private fun reset() {
        Wenku8Endpoint.applyRelay(null)
        Wenku8Endpoint.setRelayEnabled(false)
    }

    private fun enableRelay(base: String = "https://relay.example.com") {
        assertTrue(Wenku8Endpoint.applyRelay(base))
        Wenku8Endpoint.setRelayEnabled(true)
    }

    @Test
    fun defaultStateIsDirectAndUnconfigured() {
        assertFalse(Wenku8Endpoint.isRelayEnabled())
        assertFalse(Wenku8Endpoint.isRelayActive())
        assertNull(Wenku8Endpoint.relayBase())
        assertNull(Wenku8Endpoint.relayHost())
        assertEquals(Wenku8Endpoint.DIRECT_BASE, Wenku8Endpoint.publicBase())
        assertEquals("https://www.wenku8.net/modules/article/articleinfo.php?id=2365", Wenku8Urls.articleInfo("2365"))
    }

    @Test
    fun relayBaseAcceptsHttpsHostAndNormalizesIt() {
        assertEquals("https://relay.example.com", Wenku8Endpoint.normalizeRelayBase(" https://relay.example.com/ "))
        assertEquals("https://relay.example.com", Wenku8Endpoint.normalizeRelayBase("https://Relay.Example.com"))
        assertEquals("https://relay.example.com:8443", Wenku8Endpoint.normalizeRelayBase("https://relay.example.com:8443/wenku8/"))
    }

    @Test
    fun relayBaseRejectsUnsafeOrMeaninglessEndpoints() {
        // 明文 http
        assertNull(Wenku8Endpoint.normalizeRelayBase("http://relay.example.com"))
        // IP 字面量（含内网）
        assertNull(Wenku8Endpoint.normalizeRelayBase("https://127.0.0.1"))
        assertNull(Wenku8Endpoint.normalizeRelayBase("https://192.168.1.10:8080"))
        // wenku8 自身域名（走中继没有意义）
        assertNull(Wenku8Endpoint.normalizeRelayBase("https://www.wenku8.net"))
        assertNull(Wenku8Endpoint.normalizeRelayBase("https://wenku8.cc"))
        // 明文凭据与无 host
        assertNull(Wenku8Endpoint.normalizeRelayBase("https://user:pass@relay.example.com"))
        assertNull(Wenku8Endpoint.normalizeRelayBase("not a url"))
        assertNull(Wenku8Endpoint.normalizeRelayBase(""))
        assertNull(Wenku8Endpoint.normalizeRelayBase(null))
    }

    @Test
    fun publicEndpointsFollowTheSwitchAndSessionEndpointsNeverDo() {
        enableRelay()
        assertTrue(Wenku8Endpoint.isRelayActive())
        assertEquals("https://relay.example.com", Wenku8Endpoint.publicBase())

        // 公开端点跟开关走
        assertEquals("https://relay.example.com/modules/article/articleinfo.php?id=2365", Wenku8Urls.articleInfo("2365"))
        assertEquals("https://relay.example.com/novel/2/2365/index.htm", Wenku8Urls.index("2365"))
        assertEquals("https://relay.example.com/zt/sugoi/2025.php", Wenku8Urls.sugoi(2025))
        assertEquals("https://relay.example.com/zt/booklist/202609.php", Wenku8Urls.booklist("202609"))
        assertTrue(Wenku8Urls.authorArticle("伏濑", 2).startsWith("https://relay.example.com/modules/article/authorarticle.php?author="))

        // 会话端点与「来源地址」永远直连
        assertEquals("https://www.wenku8.net/modules/article/search.php?searchtype=articlename&searchkey=x&page=1", Wenku8Urls.search("x", com.example.hyperreader.model.SearchField.TITLE))
        assertEquals("https://www.wenku8.net/modules/article/toplist.php?sort=lastupdate", Wenku8Urls.toplist())
        assertTrue(Wenku8Urls.tag("恋爱").startsWith("https://www.wenku8.net/modules/article/tags.php?t="))
        assertEquals("https://www.wenku8.net/login.php", Wenku8Urls.LOGIN)
        assertEquals("https://www.wenku8.net/book/2365.htm", Wenku8Urls.book("2365"))

        // 关掉就回直连
        Wenku8Endpoint.setRelayEnabled(false)
        assertEquals(Wenku8Endpoint.DIRECT_BASE, Wenku8Endpoint.publicBase())
    }

    @Test
    fun relayUrlRewritesDirectLinksAndRestoresThemBack() {
        enableRelay()
        val direct = "https://www.wenku8.net/modules/article/articleinfo.php?id=2365"
        val relayed = Wenku8Endpoint.toRelayUrl(direct)
        assertEquals("https://relay.example.com/modules/article/articleinfo.php?id=2365", relayed)
        assertEquals(direct, Wenku8Endpoint.restoreToDirect(relayed))

        // 已是中继 / 非 wenku8 域：原样返回，不叠加改写
        assertEquals(relayed, Wenku8Endpoint.toRelayUrl(relayed))
        val other = "https://img.wenku8.com/images/c/3988.jpg"
        assertEquals(other, Wenku8Endpoint.restoreToDirect(other))
        // 相似前缀的其它域名不得被误还原
        assertEquals("https://relay.example.com.evil.test/x", Wenku8Endpoint.restoreToDirect("https://relay.example.com.evil.test/x"))
    }

    @Test
    fun whitelistAllowsRelayHostOnlyWhileItIsActive() {
        assertFalse(Wenku8Url.isAllowedHost("relay.example.com"))
        enableRelay()
        assertTrue(Wenku8Url.isAllowedHost("relay.example.com"))
        assertTrue(Wenku8Url.isAllowedHost("www.wenku8.net"))
        // 中继关闭后白名单立刻回到原样
        Wenku8Endpoint.setRelayEnabled(false)
        assertFalse(Wenku8Url.isAllowedHost("relay.example.com"))
    }

    @Test
    fun sessionCookiesNeverTravelToTheRelayHost() {
        enableRelay()
        // 只有wenku8 自身域名允许携带 jieqiUserInfo / PHPSESSID
        assertTrue(Wenku8Url.carriesSession("www.wenku8.net"))
        assertTrue(Wenku8Url.carriesSession("img.wenku8.com"))
        assertFalse(Wenku8Url.carriesSession("relay.example.com"))
        assertFalse(Wenku8Url.carriesSession("mewx.org"))
        assertFalse(Wenku8Url.carriesSession(null))
    }

    @Test
    fun userSuppliedRelayUrlsAreRestoredBeforeValidation() {
        enableRelay()
        val pasted = Wenku8Url.normalizeSource("https://relay.example.com/novel/2/2365/index.htm")
        assertEquals("https://www.wenku8.net/novel/2/2365/index.htm", pasted.toString())
        val normalized = Wenku8Url.normalizeSource(" 2365 ")
        assertEquals("https://www.wenku8.net/book/2365.htm", normalized.toString())
    }

    @Test
    fun parserRestoresRelayUrlsBeforeAnythingIsPersisted() {
        enableRelay()
        val relayBase = Wenku8Endpoint.publicBase()
        val detailHtml = """
            <html><head><title>测试书 - 作者 - 文库</title></head><body><div id="content"><table>
            <tr><td colspan="5"><b>测试书</b></td></tr>
            <tr><td>小说作者：测试作者</td><td>文章状态：连载中</td></tr>
            <tr><td>最后更新：2026-09-20</td><td>全文长度：207,559字</td></tr>
            <tr><td><a href="$relayBase/novel/2/2365/index.htm">目录</a></td></tr>
            <tr><td><img src="$relayBase/images/c/2365.jpg"></td></tr>
            </table></div></body></html>
        """.trimIndent()
        val book = Wenku8Parser.parseBook(detailHtml, "${relayBase}/modules/article/articleinfo.php?id=2365")
        assertEquals("2365", book.id)
        assertEquals("测试作者", book.author)
        assertEquals("https://www.wenku8.net/novel/2/2365/index.htm", book.directoryUrl)
        assertEquals("https://www.wenku8.net/modules/article/articleinfo.php?id=2365", book.sourceUrl)
        assertEquals("https://www.wenku8.net/modules/article/articleinfo.php?id=2365", book.bookUrl)
        assertEquals("https://www.wenku8.net/images/c/2365.jpg", book.coverUrl)

        val indexHtml = """
            <html><body><div>
            <table><tr><td class="vcss">正文</td></tr>
            <tr><td class="ccss"><a href="$relayBase/novel/2/2365/10001.htm">第一章</a></td></tr>
            </table></div></body></html>
        """.trimIndent()
        val index = Wenku8Parser.parseIndex(indexHtml, "${relayBase}/novel/2/2365/index.htm", "2365")
        assertEquals("https://www.wenku8.net/novel/2/2365/10001.htm", index.chapters.single().url)

        val listHtml = """
            <html><body><div id="content">
            <div><a href="$relayBase/book/2365.htm">测试书</a><span>作者：甲 / 字数：207,000字</span>
            <img src="$relayBase/images/c/2365.jpg"></div>
            </div></body></html>
        """.trimIndent()
        val listed = Wenku8Parser.parseSearchResults(listHtml, "${relayBase}/zt/sugoi/2025.php").single()
        assertEquals("https://www.wenku8.net/book/2365.htm", listed.sourceUrl)
        assertEquals("https://www.wenku8.net/images/c/2365.jpg", listed.coverUrl)
    }
}
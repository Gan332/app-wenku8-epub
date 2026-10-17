package com.example.hyperreader

import com.example.hyperreader.core.Wenku8Endpoint
import com.example.hyperreader.core.Wenku8Parser
import com.example.hyperreader.core.Wenku8Url
import com.example.hyperreader.model.Chapter
import com.example.hyperreader.settings.DirectEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片链路（AGENTS §4.2）：白名单、地址归一与中继边界。
 *
 * 三条实测结论钉在这里，任何一条被改回去都会让封面/插图静默消失：
 * 1. 源站插图现在挂在 `pic.777743.xyz`（自家夹具 `test/fixtures/illustration.html` 里就是它），
 *    该域只放行**图片请求**；
 * 2. 页面常给相对路径（`/image/…`、`/pictures/…`），按页面地址解析会得到
 *    `www.wenku8.net/image/…`——主站没有这个路径（404），必须钉回图片 CDN；
 * 3. 入口轮换与中继改写都不得碰图片域：`rewire()` 曾把 `img.wenku8.com` 改写成
 *    `www.wenku8.net`（404），中继 Worker 的 ALLOWED 里也没有图片路径。
 */
class ImageUrlTest {
    private val bookUrl = "https://www.wenku8.net/book/2835.htm"

    @After
    fun restoreEndpointState() {
        Wenku8Endpoint.applyPreferredHost(null)
        Wenku8Endpoint.applyRelay(null)
        Wenku8Endpoint.setRelayEnabled(false)
    }

    @Test
    fun imagesUseTheirOwnWhitelistAndPagesStayStrict() {
        assertTrue(Wenku8Url.isAllowedImageHost("img.wenku8.com"))
        assertTrue(Wenku8Url.isAllowedImageHost("pic.wenku8.com"))
        // 站点 2026 年启用的新图床：只对图片放行
        assertTrue(Wenku8Url.isAllowedImageHost("pic.777743.xyz"))
        assertFalse(Wenku8Url.isAllowedImageHost("evil.com"))

        // 页面/接口链路不受影响：新图床不是 wenku8 域名，仍被拒
        assertFalse(Wenku8Url.isAllowedHost("pic.777743.xyz"))
        assertTrue(
            runCatching { Wenku8Url.assertAllowed("https://pic.777743.xyz/2/2835/120828/149153.jpg") }.isFailure,
        )
        assertTrue(
            runCatching { Wenku8Url.assertAllowedImage("https://pic.777743.xyz/2/2835/120828/149153.jpg") }.isSuccess,
        )
        assertTrue(runCatching { Wenku8Url.assertAllowedImage("https://evil.com/a.jpg") }.isFailure)
        // 凭据与协议校验不因图片白名单放宽
        assertTrue(runCatching { Wenku8Url.assertAllowedImage("https://user:pw@pic.777743.xyz/a.jpg") }.isFailure)
    }

    @Test
    fun canonicalizePinsRelativePathsToImageCdns() {
        assertEquals(
            "https://img.wenku8.com/image/2/2835/2835s.jpg",
            Wenku8Url.canonicalizeImage("https://www.wenku8.net/image/2/2835/2835s.jpg"),
        )
        assertEquals(
            "https://pic.wenku8.com/pictures/0/471/17513/3213.jpg",
            Wenku8Url.canonicalizeImage("https://www.wenku8.net/pictures/0/471/17513/3213.jpg"),
        )
        // 未识别的路径前缀原样保留（`/image-root/` 是 App API 形态，不在本工程链路里）
        assertEquals(
            "https://www.wenku8.cc/image-root/cover/a.jpg",
            Wenku8Url.canonicalizeImage("https://www.wenku8.cc/image-root/cover/a.jpg"),
        )
        // 查询串保留（缓存键由 CoverRepository 另行裁掉）
        assertEquals(
            "https://img.wenku8.com/image/1/2/2s.jpg?v=2",
            Wenku8Url.canonicalizeImage("http://www.wenku8.net/image/1/2/2s.jpg?v=2"),
        )
    }

    @Test
    fun canonicalizeUpgradesCleartextForImageHostsOnly() {
        assertEquals(
            "https://img.wenku8.com/image/2/2835/2835s.jpg",
            Wenku8Url.canonicalizeImage("http://img.wenku8.com/image/2/2835/2835s.jpg"),
        )
        assertEquals(
            "https://pic.777743.xyz/2/2835/120828/149153.jpg",
            Wenku8Url.canonicalizeImage("http://pic.777743.xyz/2/2835/120828/149153.jpg"),
        )
        assertEquals(
            "https://pic.777743.xyz/2/2835/120828/149153.jpg",
            Wenku8Url.canonicalizeImage("https://pic.777743.xyz/2/2835/120828/149153.jpg"),
        )
        // 未知第三方原样返回：不在这里擅自改写，仍由白名单把关
        assertEquals("http://example.com/a.jpg", Wenku8Url.canonicalizeImage("http://example.com/a.jpg"))
    }

    @Test
    fun normalizeResolvesRelativeAndRelayImageUrls() {
        assertEquals(
            "https://img.wenku8.com/image/2/2835/2835s.jpg",
            Wenku8Url.normalizeImage("/image/2/2835/2835s.jpg", bookUrl),
        )
        assertEquals(
            "https://pic.wenku8.com/pictures/0/471/17513/3213.jpg",
            Wenku8Url.normalizeImage("/pictures/0/471/17513/3213.jpg", bookUrl),
        )
        assertEquals(
            "https://img.wenku8.com/image/2/2835/2835s.jpg",
            Wenku8Url.normalizeImage("//img.wenku8.com/image/2/2835/2835s.jpg", bookUrl),
        )
        // 解析出口先还原中继域（书架与 EPUB 内链不得指向第三方），再钉回图片 CDN
        Wenku8Endpoint.applyRelay("https://relay.example.com")
        assertEquals(
            "https://img.wenku8.com/image/2365.jpg",
            Wenku8Url.normalizeImage("https://relay.example.com/image/2365.jpg", bookUrl),
        )
        assertNull(Wenku8Url.normalizeImage("data:image/gif;base64,AAAA", bookUrl))
        assertNull(Wenku8Url.normalizeImage("", bookUrl))
    }

    @Test
    fun rewireSkipsImageHosts() {
        Wenku8Endpoint.applyPreferredHost(null)
        val cover = "https://img.wenku8.com/image/2/2835/2835s.jpg"
        val illustration = "https://pic.777743.xyz/2/2835/120828/149153.jpg"
        assertEquals(cover, Wenku8Endpoint.rewire(cover))
        assertEquals(illustration, Wenku8Endpoint.rewire(illustration))

        assertTrue(Wenku8Endpoint.rotateHost())
        assertEquals(
            "https://www.wenku8.cc/modules/article/articleinfo.php?id=2365",
            Wenku8Endpoint.rewire("https://www.wenku8.net/modules/article/articleinfo.php?id=2365"),
        )
        // 入口换了，图片域依旧不动
        assertEquals(cover, Wenku8Endpoint.rewire(cover))
        assertEquals(illustration, Wenku8Endpoint.rewire(illustration))
    }

    @Test
    fun relayRewritesPublicPagesButNeverImages() {
        Wenku8Endpoint.applyRelay("https://relay.example.com")
        Wenku8Endpoint.setRelayEnabled(true)

        val cover = "https://img.wenku8.com/image/2/2835/2835s.jpg"
        assertEquals(cover, Wenku8Endpoint.toRelayUrl(cover))
        assertEquals(
            "https://pic.777743.xyz/2/2835/120828/149153.jpg",
            Wenku8Endpoint.toRelayUrl("https://pic.777743.xyz/2/2835/120828/149153.jpg"),
        )
        // 公开页（Worker ALLOWED 内）照旧改写
        assertEquals(
            "https://relay.example.com/modules/article/articleinfo.php?id=2365",
            Wenku8Endpoint.toRelayUrl("https://www.wenku8.net/modules/article/articleinfo.php?id=2365"),
        )
        // 会话端点（Worker DENIED 内）永不改写
        assertEquals(
            "https://www.wenku8.net/modules/article/search.php?searchkey=x",
            Wenku8Endpoint.toRelayUrl("https://www.wenku8.net/modules/article/search.php?searchkey=x"),
        )
    }

    @Test
    fun parserPinsChapterAndDetailImagesToImageCdns() {
        val chapterHtml = """
            <html><head><title>第一章 - 文库</title></head><body><div id="content">
            <p>这是第一段正文内容，长度足以通过正文校验。</p>
            <img src="/image/2/2835/2835_001.jpg" width="600" height="800">
            <img src="http://pic.777743.xyz/2/2835/120828/149153.jpg">
            </div></body></html>
        """.trimIndent()
        val chapter = Chapter(
            id = "113354",
            title = "第一章",
            url = "https://www.wenku8.net/novel/2/2835/113354.htm",
            order = 1,
        )
        val parsed = Wenku8Parser.parseChapter(chapterHtml, chapter, chapter.url)
        assertEquals(
            listOf(
                "https://img.wenku8.com/image/2/2835/2835_001.jpg",
                "https://pic.777743.xyz/2/2835/120828/149153.jpg",
            ),
            parsed.imageUrls,
        )

        val detailHtml = """
            <html><head><title>测试书 - 作者 - 文库</title></head><body><div id="content"><table>
            <tr><td colspan="5"><b>测试书</b></td></tr>
            <tr><td>小说作者：测试作者</td><td>文章状态：连载中</td></tr>
            <tr><td><a href="/novel/2/2365/index.htm">目录</a></td></tr>
            <tr><td><img src="/image/2/2365/2365s.jpg"></td></tr>
            </table></div></body></html>
        """.trimIndent()
        val book = Wenku8Parser.parseBook(detailHtml, "https://www.wenku8.net/book/2365.htm")
        assertEquals("https://img.wenku8.com/image/2/2365/2365s.jpg", book.coverUrl)
    }

    @Test
    fun preferredEntryReordersRotationWithoutTouchingCanonicalBase() {
        Wenku8Endpoint.setRelayEnabled(false)
        Wenku8Endpoint.applyPreferredHost("https://www.wenku8.cc")

        assertEquals("https://www.wenku8.cc", Wenku8Endpoint.activeDirectBase())
        assertEquals("https://www.wenku8.cc", Wenku8Endpoint.publicBase())
        assertTrue(Wenku8Endpoint.rotateHost())
        assertEquals("https://www.wenku8.net", Wenku8Endpoint.activeDirectBase())
        assertTrue(Wenku8Endpoint.rotateHost())
        assertEquals("https://www.wenku8.com", Wenku8Endpoint.activeDirectBase())
        assertFalse(Wenku8Endpoint.rotateHost())

        // 规范 URL 始终是 .net：书架、EPUB 内链与缓存键都以它为准
        assertEquals("https://www.wenku8.net", Wenku8Endpoint.DIRECT_BASE)
        Wenku8Endpoint.applyPreferredHost(null)
        assertEquals(Wenku8Endpoint.DIRECT_BASE, Wenku8Endpoint.activeDirectBase())
        // 非法值回落 .net，不改变候选集合
        Wenku8Endpoint.applyPreferredHost("https://evil.com")
        assertEquals(Wenku8Endpoint.DIRECT_BASE, Wenku8Endpoint.activeDirectBase())
        assertEquals(3, Wenku8Endpoint.DIRECT_HOSTS.size)
    }

    /** 设置项与端点常量必须一一对应，改了一处而漏了另一处会静默换错镜像。 */
    @Test
    fun directEntryNamesRoundTrip() {
        assertEquals(DirectEntry.NET, DirectEntry.fromName(null))
        assertEquals(DirectEntry.NET, DirectEntry.fromName("nope"))
        assertEquals(DirectEntry.CC, DirectEntry.fromName("CC"))
        assertEquals("https://www.wenku8.cc", DirectEntry.CC.base)
        assertEquals(DirectEntry.entries.map { it.base }, Wenku8Endpoint.DIRECT_HOSTS)
    }
}

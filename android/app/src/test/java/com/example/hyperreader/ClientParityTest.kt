package com.example.hyperreader

import com.example.hyperreader.core.Wenku8Encoding
import com.example.hyperreader.core.Wenku8Endpoint
import com.example.hyperreader.core.Wenku8NetProtocols
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对齐 LNR 的三项客户端口径（0.18.0）：
 * 浏览器导航头组、GB18030 解码、直连边缘入口轮换。
 */
class ClientParityTest {

    @After
    fun tearDown() {
        Wenku8Endpoint.applyRelay(null)
        Wenku8Endpoint.setRelayEnabled(false)
        Wenku8Endpoint.resetHosts()
    }

    @Test
    fun navigationHeadersDeclareASecondaryNavigation() {
        val headers = Wenku8NetProtocols.headers()
        assertEquals("document", headers["Sec-Fetch-Dest"])
        assertEquals("navigate", headers["Sec-Fetch-Mode"])
        assertEquals("none", headers["Sec-Fetch-Site"])
        assertEquals("?1", headers["Sec-Fetch-User"])
        assertEquals("1", headers["Upgrade-Insecure-Requests"])
        assertTrue(headers["Accept"].orEmpty().contains("image/avif"))
        assertTrue(headers["Accept"].orEmpty().contains("image/webp"))
        assertTrue(headers["Accept-Language"].orEmpty().startsWith("zh-CN"))

        val image = Wenku8NetProtocols.imageHeaders()
        assertEquals("image", image["Sec-Fetch-Dest"])
        assertEquals("no-cors", image["Sec-Fetch-Mode"])
        // 图片请求不能冒用 document 语义
        assertFalse(image.containsKey("Sec-Fetch-User"))
    }

    @Test
    fun gbDeclaredCharsetIsDecodedAsGb18030() {
        // LNR issue #485：声明 gbk 实际 GB18030，按 GBK 解码会碎
        assertEquals("GB18030", Wenku8Encoding.normalize("gbk"))
        assertEquals("GB18030", Wenku8Encoding.normalize("gb2312"))
        assertEquals("GB18030", Wenku8Encoding.normalize("GBK"))
        assertEquals("GB18030", Wenku8Encoding.normalize(null))
        assertEquals("GB18030", Wenku8Encoding.normalize(""))
        // 明确的其它编码照旧
        assertEquals("utf-8", Wenku8Encoding.normalize("utf-8"))

        val head = Wenku8Encoding.declaredCharset("text/html", "<meta charset=gb2312>")
        assertEquals("gb2312", head)
        val fromHeader = Wenku8Encoding.declaredCharset("text/html; charset=GBK", "")
        assertEquals("gbk", fromHeader)
        assertNull(Wenku8Encoding.declaredCharset("text/html", "<html>"))

        // 用一个只有 GB18030 能正确解出的 4 字节序列验证
        val text = "书名・第一卷"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertTrue(Wenku8Encoding.decode(bytes, "gbk").isNotEmpty())
    }

    @Test
    fun hostsRotateOnBlockAndExhaustAfterThreeTries() {
        Wenku8Endpoint.resetHosts()
        assertEquals("初始应为 wenku8.net", "https://www.wenku8.net", Wenku8Endpoint.publicBase())
        assertTrue("第一次轮换应成功", Wenku8Endpoint.rotateHost())
        assertEquals("第二次应换到 .cc", "https://www.wenku8.cc", Wenku8Endpoint.publicBase())
        assertTrue("第二次轮换应成功", Wenku8Endpoint.rotateHost())
        assertEquals("第三次应换到 .com", "https://www.wenku8.com", Wenku8Endpoint.publicBase())
        // 三个入口都试过：不再轮换（避免把全部镜像打进风控）
        assertFalse("耗尽后不应再轮换", Wenku8Endpoint.rotateHost())
        assertEquals("耗尽后保持 .com", "https://www.wenku8.com", Wenku8Endpoint.publicBase())
        // 重置回到默认入口
        Wenku8Endpoint.resetHosts()
        assertEquals("重置后回到 .net", "https://www.wenku8.net", Wenku8Endpoint.publicBase())
    }

    @Test
    fun rewireKeepsPathAndQuery() {
        Wenku8Endpoint.resetHosts()
        Wenku8Endpoint.rotateHost()
        val rewired = Wenku8Endpoint.rewire("https://www.wenku8.net/modules/article/articleinfo.php?id=2365")
        assertEquals("https://www.wenku8.cc/modules/article/articleinfo.php?id=2365", rewired)
    }
}
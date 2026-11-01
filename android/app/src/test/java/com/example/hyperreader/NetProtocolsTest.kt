package com.example.hyperreader

import com.example.hyperreader.core.Wenku8NetProtocols
import com.example.hyperreader.core.decodeHttpContent
import okhttp3.Protocol
import okhttp3.TlsVersion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

/**
 * 客户端协议口径（HTTP/2 + TLS 1.3）的夹具。
 *
 * 这两项直接影响 Cloudflare 侧的风控判断，因此显式声明并用测试锁住，
 * 防止日后构建或依赖变更悄悄回落到 HTTP/1.1 + 老TLS。
 */
class NetProtocolsTest {

    @Test
    fun http2IsPreferredAndHttp11RemainsAsFallback() {
        assertEquals(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), Wenku8NetProtocols.protocols())
    }

    @Test
    fun tls13IsEnabledWithTls12Fallback() {
        val versions = Wenku8NetProtocols.tlsSpecs().single().tlsVersions
        assertTrue("必须支持 TLS 1.3", versions?.contains(TlsVersion.TLS_1_3) == true)
        assertTrue("必须保留 TLS 1.2 回落", versions?.contains(TlsVersion.TLS_1_2) == true)
        // 不应再支持 TLS 1.0/1.1 这类老协议
        assertTrue(versions?.contains(TlsVersion.TLS_1_1) != true)
        assertTrue(versions?.contains(TlsVersion.TLS_1_0) != true)
    }

    @Test
    fun cipherSuitesAreLeftToTheModernTlsDefaults() {
        // allEnabledCipherSuites() 的语义是“清空自定义列表、沿用 MODERN_TLS 默认全集”，
        // 因此 cipherSuites 为 null 是预期结果——我们自己不裁剪 cipher 集。
        assertNull(Wenku8NetProtocols.tlsSpecs().single().cipherSuites)
    }

    @Test
    fun userAgentUsesStandardBrowserForm() {
        // 标准浏览器 UA（Android Chrome 形态）：在 Cloudflare 侧呈常规客户端外观
        val ua = Wenku8NetProtocols.USER_AGENT
        assertTrue(ua.startsWith("Mozilla/5.0"))
        assertTrue(ua.contains("AppleWebKit/537.36"))
        assertTrue(ua.contains("Chrome/131."))
        assertTrue(ua.contains("Mobile Safari/537.36"))
        assertTrue(ua.none { it == '\n' || it == '\r' })
    }

    @Test
    fun acceptLanguageIsDeclaredAndPairedWithTheUserAgent() {
        assertTrue(Wenku8NetProtocols.ACCEPT_LANGUAGE.startsWith("zh-CN"))
    }

    @Test
    fun acceptEncodingAvoidsTheSingleGzipFingerprint() {
        // 实测：同一网络同一组头，OkHttp 默认的 `Accept-Encoding: gzip` 会被 Cloudflare
        // 下发 managed challenge（403 cf-mitigated=challenge），而 `gzip, deflate` 直接 200。
        val encoding = Wenku8NetProtocols.headers()["Accept-Encoding"]
        assertEquals("gzip, deflate", encoding)
        // 绝不能声明 OkHttp 解不了的编码，否则服务端真会返回 br/zstd
        assertFalse(encoding!!.contains("br"))
        assertFalse(encoding.contains("zstd"))
    }

    @Test
    fun compressedBodiesAreDecodedBecauseTransparentGzipIsOff() {
        val text = "源站声明 gbk，实际输出 GB18030 字节".toByteArray()
        assertArrayEquals(text, decodeHttpContent(gzip(text), "gzip"))
        assertArrayEquals(text, decodeHttpContent(deflate(text), "deflate"))
        assertArrayEquals(text, decodeHttpContent(text, ""))
    }

    @Test
    fun truncatedCompressedBodyStillYieldsTheDecodedPrefix() {
        // 403 拦截页只 peek 前 2KB，压缩流必然是截断的；标题必须仍能读出来
        val raw = gzip(ByteArray(16 * 1024) { 'a'.code.toByte() }).copyOfRange(0, 64)
        val decoded = String(decodeHttpContent(raw, "gzip"))
        assertTrue(decoded.startsWith("aaaa"))
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(bytes) }
    }.toByteArray()

    private fun deflate(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        DeflaterOutputStream(output).use { it.write(bytes) }
    }.toByteArray()

    @Test
    fun clientHintsMatchTheDeclaredBrowser() {
        // UA 声称 Chrome 131 + Android，客户端提示必须同口径；缺这一组会被 WAF
        // 当成「UA 与握手自相矛盾」的脚本客户端。
        val headers = Wenku8NetProtocols.headers()
        assertEquals("?1", headers["sec-ch-ua-mobile"])
        assertEquals("\"Android\"", headers["sec-ch-ua-platform"])
        assertTrue(headers.getValue("sec-ch-ua").contains("v=\"131\""))
        assertTrue(Wenku8NetProtocols.USER_AGENT.contains("Chrome/131."))
        // 顶层导航不带 Cache-Control（那是刷新请求的语义）
        assertNull(headers["Cache-Control"])
    }

    @Test
    fun http1PreferenceIsLearnedPerHost() {
        val host = "http1-probe-" + System.nanoTime() + ".example"
        assertFalse(Wenku8NetProtocols.prefersHttp1(host))
        Wenku8NetProtocols.rememberHttp1(host)
        assertTrue(Wenku8NetProtocols.prefersHttp1(host))
    }
}
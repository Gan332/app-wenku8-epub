package com.example.hyperreader

import com.example.hyperreader.core.Wenku8NetProtocols
import okhttp3.Protocol
import okhttp3.TlsVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
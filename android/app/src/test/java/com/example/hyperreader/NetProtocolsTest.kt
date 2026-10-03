package com.example.hyperreader

import com.example.hyperreader.core.Wenku8NetProtocols
import okhttp3.ConnectionSpec
import okhttp3.Protocol
import okhttp3.TlsVersion
import org.junit.Assert.assertEquals
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
        val spec: ConnectionSpec = Wenku8NetProtocols.tlsSpecs().single()
        assertTrue("必须支持 TLS 1.3", spec.isCompatibleWith(TlsVersion.TLS_1_3))
        assertTrue("必须保留 TLS 1.2 回落", spec.isCompatibleWith(TlsVersion.TLS_1_2))
        // 不应再支持 TLS 1.0/1.1 这类老协议
        assertTrue(!spec.isCompatibleWith(TlsVersion.TLS_1_1))
        assertTrue(!spec.isCompatibleWith(TlsVersion.TLS_1_0))
    }

    @Test
    fun cipherSuitesComeFromModernTls() {
        val spec = Wenku8NetProtocols.tlsSpecs().single()
        // MODERN_TLS 的 cipher 集非空，且不应是空实现
        assertTrue(spec.cipherSuites.isNotEmpty())
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
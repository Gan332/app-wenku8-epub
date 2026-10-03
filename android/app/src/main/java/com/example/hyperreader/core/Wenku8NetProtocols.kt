package com.example.hyperreader.core

import okhttp3.ConnectionSpec
import okhttp3.Protocol
import okhttp3.TlsVersion

/**
 * 客户端的网络协议口径（0.18.0-alpha02 起显式声明）。
 *
 * 背景：源站前置 Cloudflare，其风控会看**协议与握手特征**。OkHttp 在 Android 上
 * 默认就支持 HTTP/2 + TLS 1.3（底层同样是 BoringSSL/Conscrypt，与浏览器接近），
 * 但这里显式声明，避免日后被依赖默认值或构建配置悄悄改动。
 *
 * 规则（AGENTS §4.2「客户端协议」）：
 * 1. **HTTP/2 优先**，靠 ALPN 协商；服务端不支持时回落 HTTP/1.1；
 * 2. **TLS 1.3 优先**、回落 1.2；minSdk 26 的旧设备没有完整 1.3，由 TLS 栈自动回落，
 *    不需要我们做版本判断；
 * 3. **不要手加 `Accept-Encoding: br`**：OkHttp 只自动处理 gzip，声明 br 会拿到
 *    压不动的响应体（这是协议协商，不是压缩偏好）；
 * 4. **User-Agent 诚实标识**，不伪装成浏览器——既是对源站的尊重，也是长期最稳的选择
 *    （伪装随时被识别，且会让我们自己难以判断问题出在哪）。
 */
object Wenku8NetProtocols {
    /** 协议顺序：HTTP/2 优先，HTTP/1.1 兜底。 */
    fun protocols(): List<Protocol> = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)

    /** TLS 1.3 + 1.2，套用 MODERN_TLS 的现代 cipher 集。 */
    fun tlsSpecs(): List<ConnectionSpec> = listOf(
        ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .allEnabledCipherSuites()
            .build(),
    )

    /** 诚实标识的 UA（不做浏览器伪装，见规则 4）。 */
    const val USER_AGENT = "Wenku8EPUBStudio-Android/0.18"
}
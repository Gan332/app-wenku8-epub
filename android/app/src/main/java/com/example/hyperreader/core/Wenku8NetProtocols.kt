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
 * 4. **User-Agent 使用标准浏览器 UA**（平台一致的 Android Chrome 形态），让请求在
 *    Cloudflare 侧看起来是常规客户端而不是脚本工具。
 *
 * 关于第 4 条的边界：UA 只影响“像什么客户端”，**不能**绕过任何访问控制——
 * 实测补齐浏览器 UA / Referer / Accept-Language 对匿名公开页的 403 并无帮助（仍是 403），
 * 登录墙、验证码、Cloudflare 验证页一律照旧按 AGENTS §4.2 处理，不解析、不重试绕过。
 * 若站方按 UA 规则收紧，应回头改这一行并重测，而不是叠加更多伪装头。
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

    /** 标准浏览器 UA（Android Chrome 形态，与 App 运行平台一致）。 */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** 默认语言头，与 UA 配套（显式带，避免服务端按地区语种做差异处理时我们没声明）。 */
    const val ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.6"
}
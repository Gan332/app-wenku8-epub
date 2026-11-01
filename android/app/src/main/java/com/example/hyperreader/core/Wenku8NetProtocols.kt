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
 * 3. **`Accept-Encoding` 必须显式声明为 `gzip, deflate`**（见 [headers]）。OkHttp 默认只发
 *    `Accept-Encoding: gzip`，而 Cloudflare 对「声称是 Chrome、却只声明 gzip」的请求一律下发
 *    managed challenge（实测：同网络同头组，仅把该头从 `gzip` 改成 `gzip, deflate` 就从
 *    `403 cf-mitigated=challenge` 变成 `200`）。不能声明 `br`/`zstd`：OkHttp 解不了，
 *    服务端会真的返回 `br`/`zstd`。显式声明会关闭 OkHttp 的透明解压，
 *    因此 [Wenku8HttpClient] 按 `Content-Encoding` 自行解压。
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
    const val ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8"

    /**
     * 完整「顶层导航」头组（对照 LNR `Wenku8Api` 的 Ktor `DefaultRequest`）。
     *
     * 关键在 `Sec-Fetch-*`：它们声明「这是一次用户主动导航」，而我们此前只有 UA/Accept，
     * 缺这一组会被许多 WAF/Bot 判据当成脚本客户端。这也是 LNR 能在同样网络下直连的原因之一。
     */
    fun headers(): Map<String, String> = linkedMapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to ACCEPT_LANGUAGE,
        // 关键：只写 gzip 会被 Cloudflare 判为「非浏览器」并下发 JS 挑战。
        // 浏览器发的是 gzip, deflate, br, zstd；我们只能解开前两种，所以声明前两种。
        "Accept-Encoding" to "gzip, deflate",
        // 与 UA 配套的客户端提示：只带 Chrome 默认发送的低熵项（brands/mobile/platform），
        // 缺这一组时「UA 声称是 Chrome、握手却不像浏览器」自相矛盾，部分 WAF 据此直接 403。
        "sec-ch-ua" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\"",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
    )

    /** 图片等子资源用的头组（语义上应是 `image`，不能冒用 `document`）。 */
    fun imageHeaders(): Map<String, String> = linkedMapOf(
        "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
        "Accept-Language" to ACCEPT_LANGUAGE,
        "Sec-Fetch-Dest" to "image",
        "Sec-Fetch-Mode" to "no-cors",
        "Sec-Fetch-Site" to "same-origin",
    )

    /**
     * 运行期学到的「这个 host 只有 HTTP/1.1 才过」偏好（进程内，重启后重新试探）。
     *
     * 起因：实测同一网络下桌面 curl（HTTP/1.1）带同一组头能 200，而 App（HTTP/2）被 403。
     * 云端风控可能按 HTTP/2 帧特征区分客户端；遇到 403 时降到 1.1 重试一次，成功后记住。
     */
    private val http1Hosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun prefersHttp1(host: String): Boolean = http1Hosts.contains(host)

    fun rememberHttp1(host: String) {
        http1Hosts.add(host)
    }
}
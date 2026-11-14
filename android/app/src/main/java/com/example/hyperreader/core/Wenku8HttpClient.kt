package com.example.hyperreader.core

import com.example.hyperreader.http.HttpRateLimiter
import com.example.hyperreader.model.DownloadedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max

class Wenku8HttpClient(private val cacheDirectory: File, private val sessionCookieJar: CookieJar? = null) {
    private val activeCalls = ConcurrentHashMap<String, MutableSet<Call>>()
    // 连接池/调度器在进程内共享：各仓库（探索、详情、导出、封面、诊断）各建一个
    // OkHttpClient 时，同一个 host 会重复握手、各自占一组线程与空闲连接。
    // 共享 base 后只换 CookieJar，连接仍然可以复用（Cookie 是请求期附加的）。
    private val client = sharedClient.newBuilder()
        .apply { sessionCookieJar?.let { cookieJar(it) } }
        .build()

    /** 降级用的 HTTP/1.1 客户端（参数相同，只改协议；遇到 403 时按需启用）。 */
    private val http1Client by lazy {
        client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
    }

    suspend fun fetchText(url: String, jobId: String, referer: String? = null): TextResource = withContext(Dispatchers.IO) {
        val result = execute(url, jobId, 16 * 1024 * 1024, referer)
        TextResource(decodeHtml(result.bytes, result.contentType), result.finalUrl, result.contentType)
    }

    /**
     * 用户按需请求（探索页、书籍详情）：走交互档节流，允许短时突刺。
     *
     * 只影响**节奏档位**，URL 白名单、内网拦截、429 退避、重试等边界完全一致。
     * 批量抓取（导出、书目缓存）继续用 [fetchText]，保持 1 秒/请求。
     */
    suspend fun fetchTextInteractive(url: String, jobId: String, referer: String? = null): TextResource = withContext(Dispatchers.IO) {
        val result = execute(url, jobId, 16 * 1024 * 1024, referer, HttpRateLimiter.Mode.INTERACTIVE)
        TextResource(decodeHtml(result.bytes, result.contentType), result.finalUrl, result.contentType)
    }

    /**
     * 用**指定**直连入口抓一次文本，供设置页测速选择最快镜像。
     *
     * 与 [fetchText] 不同，这里不经过 [Wenku8Endpoint.rewire] 的入口轮换，
     * 因此可以独立比较 `.net` / `.cc`；仍走同一套 URL 白名单、内网拦截、限流与响应体限制。
     */
    suspend fun fetchTextFromHost(base: String, path: String, jobId: String): TextResource = withContext(Dispatchers.IO) {
        val parsed = validateUrl(base.trimEnd('/') + path)
        val result = executeOnce(
            rawUrl = parsed.toString(),
            jobId = jobId,
            maxBytes = 16 * 1024 * 1024,
            referer = null,
            mode = HttpRateLimiter.Mode.INTERACTIVE,
            image = false,
            http1 = Wenku8NetProtocols.prefersHttp1(parsed.host),
        )
        TextResource(decodeHtml(result.bytes, result.contentType), result.finalUrl, result.contentType)
    }

    suspend fun downloadImage(url: String, referer: String, jobId: String, name: String): DownloadedResource = withContext(Dispatchers.IO) {
        val result = execute(url, jobId, 30 * 1024 * 1024, referer, image = true)
        val type = detectImage(result.bytes, result.contentType) ?: throw Wenku8Exception("不支持的图片格式。", "UNSUPPORTED_IMAGE")
        val safeJob = jobId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val directory = File(cacheDirectory, safeJob).apply { mkdirs() }
        val target = File(directory, "${name.replace(Regex("[^A-Za-z0-9_-]"), "_")}-${System.currentTimeMillis()}.${type.extension}")
        FileOutputStream(target).use { it.write(result.bytes) }
        DownloadedResource(target.absolutePath, type.mime, type.extension, target.length())
    }

    fun cancelJob(jobId: String) {
        activeCalls[jobId]?.toList()?.forEach(Call::cancel)
    }

    /**
     * 执行请求；遇到 Cloudflare 拦截或 TLS 证书异常时**换一个直连边缘入口重试一次**（.net → .cc）。
     *
     * 换的是边缘入口而不是接口语义：端点集合、白名单、限流与退避都不变。
     * 两个入口都试过则原样抛出，避免把全部镜像都打进风控。
     */
    private suspend fun execute(
        rawUrl: String,
        jobId: String,
        maxBytes: Int,
        referer: String?,
        mode: HttpRateLimiter.Mode = HttpRateLimiter.Mode.BATCH,
        image: Boolean = false,
    ): HttpResult {
        // 图片：先钉回 CDN 主机与 https，再交给入口改写（图片不是入口，不会被改写）
        val target = if (image) Wenku8Url.canonicalizeImage(rawUrl) else rawUrl
        var rotated = false
        val clearanceChecked = mutableSetOf<String>()
        val http1Tried = mutableSetOf<String>()
        while (true) {
            val host = Wenku8Endpoint.activeDirectHost()
            val useHttp1 = Wenku8NetProtocols.prefersHttp1(host)
            try {
                return executeOnce(Wenku8Endpoint.rewire(target), jobId, maxBytes, referer, mode, image, useHttp1)
            } catch (error: Wenku8Exception) {
                // 凭证失效时 Cloudflare 会硬拦「带旧 token」的请求，而不带 token 未必被拦。
                // 先挂起当前入口的 token 重试一次：成功说明 token 有害，直接删掉；失败则恢复。
                if (error.code == "UPSTREAM_CHALLENGE" && clearanceChecked.add(host)) {
                    val jar = sessionCookieJar as? ClearanceCookieJar
                    if (jar != null && jar.suspendFor(host)) {
                        val withoutToken = try {
                            executeOnce(Wenku8Endpoint.rewire(target), jobId, maxBytes, referer, mode, image, useHttp1)
                        } catch (_: Wenku8Exception) {
                            null
                        }
                        if (withoutToken != null) {
                            jar.forget(host)
                            return withoutToken
                        }
                        jar.resumeFor(host)
                    }
                }
                // 实测同一网络下桌面 curl（HTTP/1.1）带同一组头 200，而 App（HTTP/2）403；
                // 风控可能按 H2 帧特征区分客户端。降级 1.1 再试一次，成功即记住该 host。
                if (error.code == "UPSTREAM_CHALLENGE" && !useHttp1 && http1Tried.add(host)) {
                    val downgraded = try {
                        executeOnce(Wenku8Endpoint.rewire(target), jobId, maxBytes, referer, mode, image, true)
                    } catch (_: Wenku8Exception) {
                        null
                    }
                    if (downgraded != null) {
                        Wenku8NetProtocols.rememberHttp1(host)
                        return downgraded
                    }
                }
                // 证书/握手失败同样可能只发生在某一个边缘入口（各镜像证书配置独立），
                // 因此与 Cloudflare 挑战一样换一个入口重试一次；图片不走入口轮换。
                val retryable = error.code == "UPSTREAM_CHALLENGE" ||
                    (error.code == "UPSTREAM_TLS" && !image)
                if (!retryable || rotated) throw error
                if (!Wenku8Endpoint.rotateHost()) throw error
                rotated = true
            }
        }
    }

    private suspend fun executeOnce(
        rawUrl: String,
        jobId: String,
        maxBytes: Int,
        referer: String?,
        mode: HttpRateLimiter.Mode,
        image: Boolean,
        http1: Boolean,
    ): HttpResult {
        var current = validateUrl(rawUrl, image)
        var redirects = 0
        var attempt = 0
        while (redirects <= MAX_REDIRECTS) {
            HttpRateLimiter.acquire(mode)
            val request = Request.Builder()
                .url(current)
                .header("User-Agent", Wenku8NetProtocols.USER_AGENT)
                .apply {
                    val headers = if (image) Wenku8NetProtocols.imageHeaders() else Wenku8NetProtocols.headers()
                    headers.forEach { (name, value) -> header(name, value) }
                }
                .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
                .build()
            val call = if (http1) http1Client.newCall(request) else client.newCall(request)
            activeCalls.computeIfAbsent(jobId) { ConcurrentHashMap.newKeySet() }.add(call)
            try {
            val response = try {
                call.execute()
            } catch (error: SSLPeerUnverifiedException) {
                throw tlsError(error, current, mismatch = true)
            } catch (error: SSLHandshakeException) {
                throw tlsError(error, current, mismatch = false)
            }
            val status = response.code
            if (status in REDIRECT_CODES) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) throw Wenku8Exception("资源返回重定向但没有 Location。", "BAD_REDIRECT")
                current = validateUrl(current.resolve(location)?.toString() ?: location, image)
                redirects += 1
                continue
            }
            if (status == 429 || status == 408 || status >= 500) {
                val retryAfter = HttpRateLimiter.retryAfterMs(response.header("Retry-After"))
                val waitMs = if (status == 429) max(retryAfter, HttpRateLimiter.fallbackDelayMs()) else max(retryAfter, 750L shl attempt)
                response.close()
                if (attempt < MAX_RETRIES) {
                    HttpRateLimiter.defer(waitMs)
                    delay(waitMs)
                    attempt += 1
                    continue
                }
                throw Wenku8Exception(
                    if (status == 429) "源站暂时限制了请求（HTTP 429），请稍后重试。" else "源站暂时不可用（HTTP $status）。",
                    if (status == 429) "UPSTREAM_RATE_LIMIT" else "UPSTREAM_UNAVAILABLE",
                )
            }
            if (!response.isSuccessful) {
                // Cloudflare 拦截页的判定：403 + 挑战页特征 → 归类为 UPSTREAM_CHALLENGE，
                // 这样**所有**链路（探索、缓存、导出、在线阅读）都能触发验证窗口，
                // 而不是只在解析器里针对 parseBook 生效。
                // 诊断信息（cf-mitigated / cf-ray / 页面标题）只用于在设置页区分
                // 「JS 挑战」与「WAF 硬拦截」：前者能用 clearance，后者换网络或中继才有用。
                // 显式声明 Accept-Encoding 后 OkHttp 不再透明解压（见 Wenku8NetProtocols.headers），
                // 这里按 Content-Encoding 自己解，否则拿到的是压缩字节。
                val encoding = response.header("Content-Encoding").orEmpty()
                val previewBytes = runCatching {
                    response.body?.let { body -> decodeLimited(body, encoding, CF_PEEK_LIMIT) } ?: ByteArray(0)
                }.getOrDefault(ByteArray(0))
                val bodyPreview = String(previewBytes, Charsets.ISO_8859_1)
                val mitigated = response.header("cf-mitigated") ?: "无"
                val ray = response.header("cf-ray") ?: "无"
                response.close()
                if (status == 403 && Wenku8Parser.looksLikeChallenge(bodyPreview)) {
                    throw Wenku8Exception(
                        "被 Cloudflare 拦截：$current（cf-mitigated=$mitigated；cf-ray=$ray；title=${challengeTitle(bodyPreview)}）",
                        "UPSTREAM_CHALLENGE",
                    )
                }
                throw Wenku8Exception("源站返回 HTTP $status。", "UPSTREAM_HTTP_ERROR")
            }
            val body = response.body ?: throw Wenku8Exception("源站响应为空。", "EMPTY_RESPONSE")
            val encoding = response.header("Content-Encoding").orEmpty()
            val declared = if (encoding.isBlank()) body.contentLength() else -1L
            val bytes = decodedStream(body, encoding).use { readLimited(it, declared, maxBytes) }
            val result = HttpResult(bytes, response.header("Content-Type").orEmpty(), status, current.toString())
            response.close()
            return result
            } finally {
                // 保留到响应体读完：取消任务时要能真正中断阻塞中的 body read。
                activeCalls.computeIfPresent(jobId) { _, calls ->
                    calls.remove(call)
                    if (calls.isEmpty()) null else calls
                }
            }
        }
        throw Wenku8Exception("资源重定向次数过多。", "TOO_MANY_REDIRECTS")
    }

    /**
     * TLS 失败的可读化：原文 `Hostname xxx not verified: certificate: …` 是英文的内部信息，
     * 直接透出无从下手；这里保留主机名与失败种类，并给出可操作的下一步。
     */
    private fun challengeTitle(html: String): String =
        Regex("<title[^>]*>\\s*([^<]{1,80})", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()?.take(60) ?: "无"

    private fun tlsError(error: Exception, url: HttpUrl, mismatch: Boolean): Wenku8Exception {
        val host = url.host
        val detail = if (mismatch) "TLS 证书与域名不匹配" else "TLS 握手未完成"
        val hint = when {
            Wenku8Endpoint.isRelayActive() && host == Wenku8Endpoint.relayHost() ->
                "中继端点的证书异常，请检查设置 → 网络里的中继地址，或暂时关闭中继。"
            Wenku8Url.isEntryHost(host) ->
                "可在设置 → 网络更换直连入口，或使用第三方中继后重试。"
            else -> "请检查网络代理、系统证书或图片域名解析后重试。"
        }
        return Wenku8Exception("与 $host 的安全连接失败：$detail。$hint", "UPSTREAM_TLS", error)
    }

    private fun validateUrl(raw: String, image: Boolean = false): HttpUrl {
        val parsed = raw.toHttpUrlOrNull() ?: throw Wenku8Exception("网址格式无效。", "INVALID_URL")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) throw Wenku8Exception("网址不能包含用户名或密码。", "URL_CREDENTIALS")
        // 图片走图片白名单（多放行站点自有图床 pic.777743.xyz，AGENTS §4.2）；页面/接口仍严格三域
        val allowed = if (image) Wenku8Url.isAllowedImageHost(parsed.host) else Wenku8Url.isAllowedHost(parsed.host)
        if (!allowed) throw Wenku8Exception("只允许访问 wenku8 来源。", "UNSUPPORTED_HOST")
        try {
            InetAddress.getAllByName(parsed.host).forEach { address ->
                if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) {
                    throw Wenku8Exception("已阻止内网地址。", "PRIVATE_ADDRESS")
                }
            }
        } catch (error: Wenku8Exception) {
            throw error
        } catch (error: Exception) {
            throw Wenku8Exception("无法解析来源域名。", "DNS_ERROR", error)
        }
        return if (parsed.scheme == "http") parsed.newBuilder().scheme("https").build() else parsed
    }

    private fun readLimited(stream: java.io.InputStream, declared: Long, maxBytes: Int): ByteArray {
        if (declared > maxBytes) throw Wenku8Exception("资源超过大小限制。", "RESOURCE_TOO_LARGE")
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw Wenku8Exception("资源超过大小限制。", "RESOURCE_TOO_LARGE")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /** 按 `Content-Encoding` 包装响应体。显式声明 Accept-Encoding 后 OkHttp 不再透明解压。 */
    private fun decodedStream(body: okhttp3.ResponseBody, encoding: String): InputStream = when {
        encoding.contains("gzip", ignoreCase = true) -> GZIPInputStream(body.byteStream())
        encoding.contains("deflate", ignoreCase = true) -> InflaterInputStream(body.byteStream())
        else -> body.byteStream()
    }

    /** 解压并只读前 [limit] 字节，供 403 拦截页做特征识别（压缩流截断时保留已解出的前缀）。 */
    private fun decodeLimited(body: okhttp3.ResponseBody, encoding: String, limit: Long): ByteArray {
        val raw = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4 * 1024)
        try {
            body.byteStream().use { input ->
                while (raw.size() < limit) {
                    val room = minOf(buffer.size.toLong(), limit - raw.size()).toInt()
                    if (room <= 0) break
                    val count = input.read(buffer, 0, room)
                    if (count < 0) break
                    raw.write(buffer, 0, count)
                }
            }
        } catch (_: Exception) {
            // 读不全也能用前缀判定挑战页
        }
        return decodeHttpContent(raw.toByteArray(), encoding)
    }

    private fun decodeHtml(bytes: ByteArray, contentType: String): String {
        // wenku8 声明 gbk 但实际输出 GB18030（issue #485），归一化见 Wenku8Encoding
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        return Wenku8Encoding.decode(bytes, Wenku8Encoding.declaredCharset(contentType, head))
    }

    private fun detectImage(bytes: ByteArray, contentType: String): ImageType? {
        if (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) return ImageType("jpg", "image/jpeg")
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) return ImageType("png", "image/png")
        if (bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII).let { it == "GIF87a" || it == "GIF89a" }) return ImageType("gif", "image/gif")
        if (bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP") return ImageType("webp", "image/webp")
        return when (contentType.substringBefore(';').lowercase()) {
            "image/jpeg", "image/jpg" -> ImageType("jpg", "image/jpeg")
            "image/png" -> ImageType("png", "image/png")
            "image/gif" -> ImageType("gif", "image/gif")
            "image/webp" -> ImageType("webp", "image/webp")
            else -> null
        }
    }

    data class TextResource(val html: String, val finalUrl: String, val contentType: String)
    data class DownloadedResource(val path: String, val mime: String, val ext: String, val bytes: Long)
    private data class HttpResult(val bytes: ByteArray, val contentType: String, val status: Int, val finalUrl: String)
    private data class ImageType(val extension: String, val mime: String)

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private const val MAX_REDIRECTS = 5
        private const val MAX_RETRIES = 3
        /** 403 时读取响应体前N 字节用于识别 Cloudflare 挑战页（够识别标题即可）。 */
        private const val CF_PEEK_LIMIT = 2048L

        /** 进程内共享的连接池与调度器；协议/TLS 口径集中在这里。 */
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                // 协议与 TLS 口径集中在 Wenku8NetProtocols（HTTP/2 优先 + TLS 1.3，见该文件 KDoc）
                .protocols(Wenku8NetProtocols.protocols())
                .connectionSpecs(Wenku8NetProtocols.tlsSpecs())
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
    }
}

/**
 * 按 `Content-Encoding` 解压响应体。
 *
 * `Wenku8NetProtocols.headers()` 显式声明 `Accept-Encoding: gzip, deflate` 后，
 * OkHttp 不再为我们透明解压，所以所有响应体必须过一遍这里。
 * 无法识别的编码原样返回；压缩流被截断时返回已解出的前缀（用于挑战页判定）。
 */
internal fun decodeHttpContent(bytes: ByteArray, encoding: String): ByteArray {
    if (bytes.isEmpty() || encoding.isBlank()) return bytes
    val stream: InputStream = when {
        encoding.contains("gzip", ignoreCase = true) -> GZIPInputStream(bytes.inputStream())
        encoding.contains("deflate", ignoreCase = true) -> InflaterInputStream(bytes.inputStream())
        else -> return bytes
    }
    val output = java.io.ByteArrayOutputStream(bytes.size * 4)
    val buffer = ByteArray(16 * 1024)
    try {
        stream.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
    } catch (_: Exception) {
        // 截断的压缩体：保留已解出的部分
    }
    return if (output.size() == 0) bytes else output.toByteArray()
}

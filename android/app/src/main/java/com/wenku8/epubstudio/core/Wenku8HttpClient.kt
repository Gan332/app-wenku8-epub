package com.wenku8.epubstudio.core

import com.wenku8.epubstudio.http.HttpRateLimiter
import com.wenku8.epubstudio.model.DownloadedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max

class Wenku8HttpClient(private val cacheDirectory: File, sessionCookieJar: CookieJar? = null) {
    private val activeCalls = ConcurrentHashMap<String, MutableSet<Call>>()
    private val client = OkHttpClient.Builder()
        .apply { sessionCookieJar?.let { cookieJar(it) } }
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun fetchText(url: String, jobId: String, referer: String? = null): TextResource = withContext(Dispatchers.IO) {
        val result = execute(url, jobId, 16 * 1024 * 1024, referer)
        TextResource(decodeHtml(result.bytes, result.contentType), result.finalUrl, result.contentType)
    }

    suspend fun downloadImage(url: String, referer: String, jobId: String, name: String): DownloadedResource = withContext(Dispatchers.IO) {
        val result = execute(url, jobId, 30 * 1024 * 1024, referer)
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

    private fun execute(rawUrl: String, jobId: String, maxBytes: Int, referer: String?): HttpResult {
        var current = validateUrl(rawUrl)
        var redirects = 0
        var attempt = 0
        while (redirects <= MAX_REDIRECTS) {
            HttpRateLimiter.acquire()
            val request = Request.Builder()
                .url(current)
                .header("User-Agent", "Wenku8EPUBStudio-Android/0.2.0")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.4")
                .header("Accept", "text/html,application/xhtml+xml,image/*;q=0.8,*/*;q=0.5")
                .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
                .build()
            val call = client.newCall(request)
            activeCalls.computeIfAbsent(jobId) { ConcurrentHashMap.newKeySet() }.add(call)
            val response = try { call.execute() } finally { activeCalls[jobId]?.remove(call) }
            val status = response.code
            if (status in REDIRECT_CODES) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) throw Wenku8Exception("资源返回重定向但没有 Location。", "BAD_REDIRECT")
                current = validateUrl(current.resolve(location)?.toString() ?: location)
                redirects += 1
                continue
            }
            if (status == 429 || status == 408 || status >= 500) {
                val retryAfter = HttpRateLimiter.retryAfterMs(response.header("Retry-After"))
                val delay = if (status == 429) max(retryAfter, HttpRateLimiter.fallbackDelayMs()) else max(retryAfter, 750L shl attempt)
                response.close()
                if (attempt < MAX_RETRIES) {
                    HttpRateLimiter.defer(delay)
                    Thread.sleep(delay)
                    attempt += 1
                    continue
                }
                throw Wenku8Exception(
                    if (status == 429) "源站暂时限制了请求（HTTP 429），请稍后重试。" else "源站暂时不可用（HTTP $status）。",
                    if (status == 429) "UPSTREAM_RATE_LIMIT" else "UPSTREAM_UNAVAILABLE",
                )
            }
            if (!response.isSuccessful) {
                response.close()
                throw Wenku8Exception("源站返回 HTTP $status。", "UPSTREAM_HTTP_ERROR")
            }
            val body = response.body ?: throw Wenku8Exception("源站响应为空。", "EMPTY_RESPONSE")
            val bytes = body.byteStream().use { readLimited(it, body.contentLength(), maxBytes) }
            val result = HttpResult(bytes, response.header("Content-Type").orEmpty(), status, current.toString())
            response.close()
            return result
        }
        throw Wenku8Exception("资源重定向次数过多。", "TOO_MANY_REDIRECTS")
    }

    private fun validateUrl(raw: String): HttpUrl {
        val parsed = raw.toHttpUrlOrNull() ?: throw Wenku8Exception("网址格式无效。", "INVALID_URL")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) throw Wenku8Exception("网址不能包含用户名或密码。", "URL_CREDENTIALS")
        if (!Wenku8Url.isAllowedHost(parsed.host)) throw Wenku8Exception("只允许访问 wenku8 来源。", "UNSUPPORTED_HOST")
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

    private fun decodeHtml(bytes: ByteArray, contentType: String): String {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val declared = Regex("charset\\s*=\\s*[\"']?([^;\\s\"'/>]+)", RegexOption.IGNORE_CASE)
            .find("$contentType\n$head")?.groupValues?.get(1)?.lowercase().orEmpty()
        val normalized = if (declared == "gb2312" || declared == "gbk") "GBK" else declared
        return String(bytes, runCatching { Charset.forName(normalized) }.getOrDefault(Charsets.UTF_8))
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
    }
}

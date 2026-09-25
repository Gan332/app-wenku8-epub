package com.wenku8.epubstudio.http

import android.net.Uri
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl
import java.util.concurrent.TimeUnit

@CapacitorPlugin(name = "Wenku8Http")
class Wenku8HttpPlugin : Plugin() {
    private val executor: ExecutorService = Executors.newFixedThreadPool(3)
    private val activeCalls = ConcurrentHashMap<String, MutableSet<Call>>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @PluginMethod
    fun fetchText(call: PluginCall) {
        val url = call.getString("url")
        val jobId = call.getString("jobId") ?: "parse"
        if (url.isNullOrBlank()) {
            call.reject("缺少请求网址。", "INVALID_URL")
            return
        }
        executor.execute {
            try {
                val result = executeRequest(call, url, jobId, 16 * 1024 * 1024)
                val response = JSObject()
                    .put("html", decodeHtml(result.bytes, result.contentType))
                    .put("finalUrl", result.finalUrl)
                    .put("contentType", result.contentType)
                    .put("status", result.status)
                call.resolve(response)
            } catch (error: Throwable) {
                call.reject(error.message ?: "请求失败。", errorCode(error))
            }
        }
    }

    @PluginMethod
    fun downloadFile(call: PluginCall) {
        val url = call.getString("url")
        val referer = call.getString("referer")
        val jobId = call.getString("jobId") ?: "download"
        val requestedName = call.getString("fileName")
        if (url.isNullOrBlank()) {
            call.reject("缺少图片网址。", "INVALID_URL")
            return
        }
        executor.execute {
            try {
                val result = executeRequest(call, url, jobId, 30 * 1024 * 1024, referer)
                val type = detectImage(result.bytes, result.contentType)
                    ?: throw IllegalStateException("不支持的图片格式：${result.contentType.ifBlank { "未知" }}")
                val safeJob = jobId.replace(Regex("[^A-Za-z0-9_-]"), "_")
                val directory = File(context.cacheDir, "wenku8/$safeJob")
                if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("无法创建图片缓存目录。")
                val baseName = (requestedName ?: "image").replace(Regex("[^A-Za-z0-9_-]"), "_")
                val target = File(directory, "$baseName-${System.currentTimeMillis()}.${type.extension}")
                FileOutputStream(target).use { output -> output.write(result.bytes) }
                val response = JSObject()
                    .put("path", target.absolutePath)
                    .put("uri", Uri.fromFile(target).toString())
                    .put("mime", type.mime)
                    .put("ext", type.extension)
                    .put("bytes", target.length())
                call.resolve(response)
            } catch (error: Throwable) {
                call.reject(error.message ?: "图片下载失败。", errorCode(error))
            }
        }
    }

    @PluginMethod
    fun cancelJob(call: PluginCall) {
        val jobId = call.getString("jobId")
        if (!jobId.isNullOrBlank()) activeCalls[jobId]?.toList()?.forEach { it.cancel() }
        call.resolve()
    }

    private fun executeRequest(
        call: PluginCall,
        rawUrl: String,
        jobId: String,
        maxBytes: Int,
        referer: String? = null,
    ): HttpResult {
        var current = validateUrl(rawUrl)
        var redirectCount = 0
        var attempt = 0
        while (redirectCount <= MAX_REDIRECTS) {
            HttpRateLimiter.acquire()
            val requestBuilder = Request.Builder()
                .url(current)
                .header("User-Agent", "Wenku8EPUBStudio-Android/0.1.0")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.4")
                .header("Accept", "text/html,application/xhtml+xml,image/*;q=0.8,*/*;q=0.5")
            if (!referer.isNullOrBlank()) requestBuilder.header("Referer", referer)
            val request = requestBuilder.build()
            val okCall = client.newCall(request)
            activeCalls.computeIfAbsent(jobId) { ConcurrentHashMap.newKeySet() }.add(okCall)
            val response: Response = try {
                okCall.execute()
            } finally {
                activeCalls[jobId]?.remove(okCall)
            }

            if (response.code in REDIRECT_CODES) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) throw IllegalStateException("资源返回重定向但没有 Location。")
                current = validateUrl(response.request.url.resolve(location)?.toString() ?: location)
                redirectCount += 1
                continue
            }

            val retryable = response.code == 429 || response.code == 408 || response.code >= 500
            if (retryable) {
                val retryAfter = HttpRateLimiter.retryAfterMs(response.header("Retry-After"))
                val delay = if (response.code == 429) maxOf(retryAfter, HttpRateLimiter.fallbackDelayMs()) else maxOf(retryAfter, backoffMs(attempt))
                response.close()
                if (attempt < MAX_RETRIES) {
                    HttpRateLimiter.defer(delay)
                    Thread.sleep(delay)
                    attempt += 1
                    continue
                }
                val message = if (response.code == 429) "源站暂时限制了请求（HTTP 429），已按退避策略等待，请稍后重试。" else "源站暂时不可用（HTTP ${response.code}）。"
                throw UpstreamException(message, if (response.code == 429) "UPSTREAM_RATE_LIMIT" else "UPSTREAM_UNAVAILABLE")
            }

            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                throw UpstreamException("源站返回 HTTP $code。", "UPSTREAM_HTTP_ERROR")
            }

            val contentType = response.header("Content-Type").orEmpty()
            val bytes = response.body?.let { body -> readLimited(body.byteStream(), body.contentLength(), maxBytes) }
                ?: ByteArray(0)
            val result = HttpResult(bytes, contentType, response.code, current.toString())
            response.close()
            return result
        }
        throw IllegalStateException("资源重定向次数过多。")
    }

    private fun readLimited(stream: java.io.InputStream, declaredLength: Long, maxBytes: Int): ByteArray {
        if (declaredLength > maxBytes) throw IllegalStateException("资源超过大小限制。")
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        stream.use {
            var count = it.read(buffer)
            while (count >= 0) {
                total += count
                if (total > maxBytes) throw IllegalStateException("资源超过大小限制。")
                output.write(buffer, 0, count)
                count = it.read(buffer)
            }
        }
        return output.toByteArray()
    }

    private fun validateUrl(raw: String): HttpUrl {
        val parsed = HttpUrl.parse(raw) ?: throw IllegalArgumentException("网址格式无效。")
        if (parsed.scheme != "https" && parsed.scheme != "http") throw IllegalArgumentException("只允许 HTTP/HTTPS 网址。")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) throw IllegalArgumentException("网址不能包含用户名或密码。")
        val host = parsed.host.lowercase()
        if (!WENKU_HOSTS.any { host == it || host.endsWith(".$it") }) throw IllegalArgumentException("只允许访问 wenku8 来源。")
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") throw IllegalArgumentException("已阻止本机地址。")
        try {
            InetAddress.getAllByName(host).forEach { address ->
                if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) {
                    throw IllegalArgumentException("已阻止内网地址。")
                }
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: Exception) {
            throw IllegalArgumentException("无法解析来源域名。")
        }
        return if (parsed.scheme == "http") parsed.newBuilder().scheme("https").build() else parsed
    }

    private fun decodeHtml(bytes: ByteArray, contentType: String): String {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val declared = Regex("charset\\s*=\\s*[\\\"']?([^;\\s\\\"'/>]+)", RegexOption.IGNORE_CASE)
            .find(contentType + "\n" + head)
            ?.groupValues
            ?.get(1)
            ?.lowercase()
            .orEmpty()
        val normalized = when (declared) {
            "gb2312", "gbk" -> "GBK"
            else -> declared
        }
        val charset = runCatching { Charset.forName(normalized) }.getOrDefault(Charsets.UTF_8)
        return String(bytes, charset)
    }

    private fun detectImage(bytes: ByteArray, contentType: String): ImageType? {
        if (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) return ImageType("jpg", "image/jpeg")
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) return ImageType("png", "image/png")
        if (bytes.size >= 6 && (String(bytes, 0, 6, Charsets.US_ASCII) == "GIF87a" || String(bytes, 0, 6, Charsets.US_ASCII) == "GIF89a")) return ImageType("gif", "image/gif")
        if (bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP") return ImageType("webp", "image/webp")
        return when (contentType.substringBefore(';').trim().lowercase()) {
            "image/jpeg", "image/jpg" -> ImageType("jpg", "image/jpeg")
            "image/png" -> ImageType("png", "image/png")
            "image/gif" -> ImageType("gif", "image/gif")
            "image/webp" -> ImageType("webp", "image/webp")
            else -> null
        }
    }

    private fun backoffMs(attempt: Int): Long = (750L * (1L shl attempt)).coerceAtMost(30_000L)

    private fun errorCode(error: Throwable): String = when (error) {
        is UpstreamException -> error.code
        else -> "MOBILE_HTTP_ERROR"
    }

    override fun handleOnDestroy() {
        executor.shutdownNow()
        activeCalls.values.flatten().forEach { it.cancel() }
        activeCalls.clear()
        super.handleOnDestroy()
    }

    private data class HttpResult(val bytes: ByteArray, val contentType: String, val status: Int, val finalUrl: String)
    private data class ImageType(val extension: String, val mime: String)
    private class UpstreamException(message: String, val code: String) : Exception(message)

    companion object {
        private val WENKU_HOSTS = listOf("wenku8.net", "wenku8.cc", "wenku8.com")
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private const val MAX_REDIRECTS = 5
        private const val MAX_RETRIES = 3
    }
}

package com.wenku8.epubstudio.ui.cover

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.wenku8.epubstudio.core.Wenku8HttpClient
import com.wenku8.epubstudio.core.Wenku8Url
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 网络封面加载与缓存。
 *
 * 刻意不引入 Coil/Glide：项目自带的 [Wenku8HttpClient] 带全局 1 秒限流与
 * HTTP 429 退避，第三方图片库会自建 OkHttp 客户端从而绕过限流去请求
 * img.wenku8.com。复用现有客户端更安全，也保持与导出流程一致的下载路径。
 *
 * 缓存分两层：内存 LruCache + 磁盘 cacheDir/covers。
 */
class CoverRepository(context: Context) {
    private val directory = File(context.cacheDir, "covers").apply { mkdirs() }
    private val client = Wenku8HttpClient(File(context.cacheDir, "wenku8-covers"))
    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /** URL 规范化：强制 https，并裁掉查询串以提高命中率。 */
    fun normalize(url: String): String = runCatching {
        val uri = Wenku8Url.assertAllowed(url)
        val base = "${uri.scheme}://${uri.host}${uri.path}"
        Wenku8Url.assertAllowed(base).toString()
    }.getOrElse { url.trim() }

    fun cacheFile(url: String): File = File(directory, "${url.hashCode().toUInt().toString(16)}-${url.length}.cover")

    /** 命中内存或磁盘时返回，未命中返回 null（不触发网络）。 */
    fun cached(url: String): ImageBitmap? {
        val key = normalize(url)
        memory.get(key)?.let { return it }
        val file = cacheFile(key)
        if (!file.isFile) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }
            .getOrNull()
            ?.also { memory.put(key, it) }
    }

    /**
     * 加载封面。优先内存/磁盘缓存，其次网络。
     * @param targetWidthPx 目标宽度（像素），用于下采样避免 OOM；<=0 表示原图。
     */
    suspend fun load(url: String, targetWidthPx: Int = 0): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = normalize(url)
        memory.get(key)?.let { return@withContext it }

        val file = cacheFile(key)
        if (file.isFile) {
            runCatching { decode(file, targetWidthPx) }.getOrNull()?.let { decoded ->
                memory.put(key, decoded)
                return@withContext decoded
            }
        }

        val downloaded = runCatching {
            client.downloadImage(key, REFERER, "cover", "cover")
        }.getOrNull() ?: return@withContext null

        val source = File(downloaded.path)
        val bytes = runCatching { source.readBytes() }.getOrNull()
        source.delete()
        bytes ?: return@withContext null

        val bitmap = decodeBytes(bytes, targetWidthPx) ?: return@withContext null
        runCatching { file.writeBytes(bytes) }
        memory.put(key, bitmap)
        bitmap
    }

    private fun decode(file: File, targetWidthPx: Int): ImageBitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return decodeFileWithSampling(file.absolutePath, targetWidthPx, bounds.outWidth, bounds.outHeight)
    }

    private fun decodeBytes(bytes: ByteArray, targetWidthPx: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        if (targetWidthPx > 0 && bounds.outWidth > targetWidthPx) {
            while (bounds.outWidth / (sample * 2) >= targetWidthPx) sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    private fun decodeFileWithSampling(path: String, targetWidthPx: Int, outWidth: Int, outHeight: Int): ImageBitmap? {
        var sample = 1
        if (targetWidthPx > 0 && outWidth > targetWidthPx) {
            while (outWidth / (sample * 2) >= targetWidthPx) sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, options)?.asImageBitmap()
    }

    fun clear() {
        memory.evictAll()
        directory.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val MEMORY_BYTES = 12 * 1024 * 1024
        const val REFERER = "https://www.wenku8.net/"
    }
}

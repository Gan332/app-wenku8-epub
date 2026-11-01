package com.example.hyperreader.ui.cover

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.hyperreader.core.Wenku8Endpoint
import com.example.hyperreader.core.Wenku8HttpClient
import com.example.hyperreader.core.Wenku8Url
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 网络封面加载与缓存。
 *
 * 刻意不引入 Coil/Glide：项目自带的 [Wenku8HttpClient] 带全局 1 秒限流与
 * HTTP 429 退避，第三方图片库会自建 OkHttp 客户端从而绕过限流去请求
 * img.wenku8.com。复用现有客户端更安全，也保持与导出流程一致的下载路径。
 *
 * 缓存分两层：内存 LruCache + 磁盘 cacheDir/covers。
 *
 * 缓存键始终是wenku8 原域 URL（解析出口已用 [Wenku8Endpoint.restoreToDirect] 还原），
 * 且图片一律**直连**：图片 CDN 不在中继白名单内（见 [Wenku8Endpoint.toRelayUrl]），
 * 图域本身也不经 Cloudflare，所以开关切换不会重复占缓存，EPUB 与书架里存的也始终是 wenku8 地址。
 */
class CoverRepository(context: Context) {
    private val directory = File(context.cacheDir, "covers").apply { mkdirs() }
    private val client = Wenku8HttpClient(File(context.cacheDir, "wenku8-covers"))
    /** 同一 URL 的并发加载合并成一次：列表里同一本书的封面常被多屏同时请求。 */
    private val inflight = ConcurrentHashMap<String, CompletableDeferred<ImageBitmap?>>()
    /** 下载与清理互斥：trim 与写入并发时会按过期总量继续删，把刚写好的封面删掉。 */
    private val diskLock = Any()
    /**
     * 下载自己跑：调用方离开组合只代表没人再等结果，不该让一次已经发出的请求作废，
     * 也不该把「被取消」当成「下载失败」传给同一 URL 的其他等待者。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /**
     * URL 规范化：先 [Wenku8Url.canonicalizeImage]（钉回图片 CDN + 强制 https），
     * 再裁掉查询串以提高命中率。
     *
     * 缓存键与真实请求共用这份结果，因此 `http://img.wenku8.com/x.jpg` 与
     * `https://img.wenku8.com/x.jpg` 命中同一份缓存；图片域走图片白名单
     * （多放行站点自有图床 `pic.777743.xyz`，AGENTS §4.2）。
     */
    fun normalize(url: String): String = runCatching {
        val uri = Wenku8Url.assertAllowedImage(Wenku8Url.canonicalizeImage(url))
        val base = "${uri.scheme}://${uri.host}${uri.path}"
        Wenku8Url.assertAllowedImage(base).toString()
    }.getOrElse { url.trim() }

    fun cacheFile(url: String): File = File(directory, "${url.hashCode().toUInt().toString(16)}-${url.length}.cover")

    /**
     * 内存缓存按「URL + 目标宽度」分键：列表小图与全屏大图共用一张位图会让
     * 小图把原图常驻在 LRU 里，占满 12 MB 后其余封面反复重解。
     */
    private fun memoryKey(key: String, targetWidthPx: Int): String =
        if (targetWidthPx > 0) "$key@$targetWidthPx" else key

    /**
     * 加载封面。优先内存/磁盘缓存，其次网络。
     *
     * 并发去重按 URL：一次下载，多种宽度共用；后来者拿到共享结果后按**自己的**宽度
     * 从磁盘重新解码。直接把领先者的位图交给别人会让 480dp 的详情封面顶替全屏原图。
     * @param targetWidthPx 目标宽度（像素），用于下采样避免 OOM；<=0 表示原图。
     */
    suspend fun load(url: String, targetWidthPx: Int = 0): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = normalize(url)
        val memKey = memoryKey(key, targetWidthPx)
        memory.get(memKey)?.let { return@withContext it }

        val file = cacheFile(key)
        if (file.isFile) {
            runCatching { decode(file, targetWidthPx) }.getOrNull()?.let { decoded ->
                memory.put(memKey, decoded)
                return@withContext decoded
            }
        }

        val gate = CompletableDeferred<ImageBitmap?>()
        inflight.putIfAbsent(key, gate)?.let { leader ->
            val shared = awaitShared(leader) ?: return@withContext null
            val own = runCatching { decode(file, targetWidthPx) }.getOrNull()
            return@withContext (own ?: shared).also { memory.put(memKey, it) }
        }
        scope.launch {
            val result = runCatching { fetchRemote(key, file, targetWidthPx) }.getOrNull()
            gate.complete(result)
            inflight.remove(key, gate)
        }
        awaitShared(gate)
    }

    /** 等待共享结果；调用方被取消时向上抛，不能被当成「下载失败」。 */
    private suspend fun awaitShared(gate: CompletableDeferred<ImageBitmap?>): ImageBitmap? =
        try {
            gate.await()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            null
        }

    private suspend fun fetchRemote(key: String, file: File, targetWidthPx: Int): ImageBitmap? {
        val downloaded = runCatching {
            client.downloadImage(Wenku8Endpoint.toRelayUrl(key), REFERER, "cover", "cover")
        }.getOrNull() ?: return null

        val source = File(downloaded.path)
        val bytes = runCatching { source.readBytes() }.getOrNull()
        source.delete()
        bytes ?: return null

        val bitmap = decodeBytes(bytes, targetWidthPx) ?: return null
        runCatching {
            writeCache(file, bytes)
            trimDisk()
        }
        memory.put(memoryKey(key, targetWidthPx), bitmap)
        return bitmap
    }

    /**
     * 读取图片原始字节，供 xy-reader 的整页图片排版使用。
     * 与 [load] 共用内存/磁盘缓存和同一限流客户端；失败返回 null，正文可降级继续阅读。
     */
    suspend fun loadBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val key = normalize(url)
        val file = cacheFile(key)
        if (file.isFile) runCatching { file.readBytes() }.getOrNull()?.let { return@withContext it }
        val downloaded = runCatching { client.downloadImage(Wenku8Endpoint.toRelayUrl(key), REFERER, "reader-image", "image") }.getOrNull()
            ?: return@withContext null
        val bytes = runCatching { File(downloaded.path).readBytes() }.getOrNull()
        File(downloaded.path).delete()
        bytes?.takeIf { it.isNotEmpty() }?.also {
            runCatching {
                writeCache(file, it)
                trimDisk()
            }
        }
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
        synchronized(diskLock) {
            memory.evictAll()
            directory.listFiles()?.forEach { it.delete() }
        }
    }

    /** 先写临时文件再改名：中途被杀不会留下半张图被当成缓存命中。 */
    private fun writeCache(file: File, bytes: ByteArray) {
        val staging = File(file.parentFile, file.name + PART_SUFFIX)
        staging.writeBytes(bytes)
        val renamed = staging.renameTo(file)
        if (!renamed) {
            file.writeBytes(bytes)
            staging.delete()
        }
    }

    private fun trimDisk() = synchronized(diskLock) {
        // 进程被杀会留下 .part：不参与容量统计，但也不能永远占着磁盘。
        val now = System.currentTimeMillis()
        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(PART_SUFFIX) && now - it.lastModified() > STALE_PART_MS }
            ?.forEach { it.delete() }
        trimCoverDirectory(directory, DISK_LIMIT_BYTES, DISK_TARGET_BYTES)
    }

    /** 磁盘缓存的占用（字节）；设置页展示与清理前确认用。 */
    fun diskUsageBytes(): Long = directory.listFiles()
        ?.filter { it.isFile && !it.name.endsWith(PART_SUFFIX) }
        ?.sumOf { it.length() }
        ?: 0L

    private companion object {
        const val MEMORY_BYTES = 12 * 1024 * 1024
        const val DISK_LIMIT_BYTES = 128L * 1024 * 1024
        const val DISK_TARGET_BYTES = 96L * 1024 * 1024
        const val REFERER = "https://www.wenku8.net/"
        const val PART_SUFFIX = ".part"
        const val STALE_PART_MS = 60L * 60 * 1000
    }
}

/**
 * 封面/插图磁盘缓存的容量控制：超过 [limitBytes] 时按最后修改时间从旧到新删到 [targetBytes]。
 *
 * 不设上限时 `cacheDir/covers` 会随浏览历史无限增长；系统虽然能回收缓存目录，
 * 但不会替我们控制水位，低存储设备上容易先被系统当作“占用大户”。
 */
internal fun trimCoverDirectory(directory: File, limitBytes: Long, targetBytes: Long) {
    val files = directory.listFiles()
        ?.filter { it.isFile && !it.name.endsWith(".part") }
        ?: return
    var total = files.sumOf { it.length() }
    if (total <= limitBytes) return
    for (file in files.sortedBy { it.lastModified() }) {
        if (total <= targetBytes) break
        val size = file.length()
        if (file.delete()) total -= size
    }
}

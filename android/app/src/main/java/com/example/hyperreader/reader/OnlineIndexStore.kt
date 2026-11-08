package com.example.hyperreader.reader

import com.example.hyperreader.model.BookIndex
import com.example.hyperreader.model.Chapter
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 落盘用的目录快照；刻意不复用业务模型，避免给它加序列化注解。 */
@Serializable
private data class CachedIndex(
    val bookId: String = "",
    val title: String = "",
    val url: String = "",
    val chapters: List<CachedChapter> = emptyList(),
)

@Serializable
private data class CachedChapter(
    val id: String,
    val title: String = "",
    val url: String = "",
    val volume: String = "",
    val order: Int = 0,
    val isIllustration: Boolean = false,
)

/**
 * 在线阅读目录的本地快照（`filesDir/online-index/{bookId}.json`）。
 *
 * 为什么需要：只缓存正文对断网没有意义——打开阅读器第一步就是拉目录，目录页拉不到
 * 就直接报错，缓存的几百章正文根本用不上。把目录单独存一份后，「缓存全书」
 * 才真正等于离线可读：目录来自快照，正文来自 [OnlineChapterCache]。
 *
 * 目录是公开数据，不含 Cookie 或账号信息；写入失败一律吞掉，不阻塞阅读。
 */
class OnlineIndexStore(private val rootDirectory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun fileFor(bookId: String): File = File(rootDirectory, "${segment(bookId)}.json")

    fun has(bookId: String): Boolean = fileFor(bookId).isFile

    /** 读取快照；文件损坏或不存在返回 null，调用方应回落到网络。 */
    fun read(bookId: String): BookIndex? {
        val file = fileFor(bookId)
        if (!file.isFile) return null
        val cached = runCatching {
            json.decodeFromString(CachedIndex.serializer(), file.readText(Charsets.UTF_8))
        }.getOrNull() ?: run {
            runCatching { file.delete() }
            return null
        }
        if (cached.chapters.isEmpty()) return null
        return BookIndex(
            title = cached.title,
            url = cached.url,
            bookId = cached.bookId.ifBlank { bookId.filter(Char::isDigit) },
            chapters = cached.chapters.map { chapter ->
                Chapter(
                    id = chapter.id,
                    title = chapter.title,
                    url = chapter.url,
                    volume = chapter.volume,
                    order = chapter.order,
                    isIllustration = chapter.isIllustration,
                )
            },
        )
    }

    fun write(index: BookIndex): Boolean {
        val bookId = index.bookId?.filter(Char::isDigit).orEmpty()
        if (bookId.isBlank() || index.chapters.isEmpty()) return false
        if (!rootDirectory.isDirectory && !rootDirectory.mkdirs()) return false
        val snapshot = CachedIndex(
            bookId = bookId,
            title = index.title,
            url = index.url,
            chapters = index.chapters.map { chapter ->
                CachedChapter(
                    id = chapter.id,
                    title = chapter.title,
                    url = chapter.url,
                    volume = chapter.volume,
                    order = chapter.order,
                    isIllustration = chapter.isIllustration,
                )
            },
        )
        val encoded = runCatching {
            json.encodeToString(CachedIndex.serializer(), snapshot)
        }.getOrNull() ?: return false
        val target = fileFor(bookId)
        val temp = File(rootDirectory, "${target.name}.tmp")
        if (runCatching { temp.writeText(encoded, Charsets.UTF_8) }.isFailure) {
            runCatching { temp.delete() }
            return false
        }
        if (!runCatching { temp.renameTo(target) }.getOrDefault(false)) {
            runCatching { target.delete() }
            val retried = runCatching { temp.renameTo(target) }.getOrDefault(false)
            if (!retried) {
                runCatching { temp.delete() }
                return false
            }
        }
        return true
    }

    fun delete(bookId: String) {
        runCatching { fileFor(bookId).delete() }
    }

    fun clear() {
        runCatching { rootDirectory.deleteRecursively() }
    }

    fun totalBytes(): Long =
        rootDirectory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** 与 [OnlineChapterCache] 一致的文件名安全化。 */
    private fun segment(value: String): String {
        val trimmed = value.trim()
        val cleaned = trimmed.replace(UNSAFE, "_").take(64)
        if (cleaned.isBlank() || cleaned == "." || cleaned == "..") return "_"
        return if (cleaned == trimmed) cleaned else "$cleaned-${trimmed.hashCode().toUInt().toString(16)}"
    }

    companion object {
        private val UNSAFE = Regex("[^A-Za-z0-9_-]")
    }
}

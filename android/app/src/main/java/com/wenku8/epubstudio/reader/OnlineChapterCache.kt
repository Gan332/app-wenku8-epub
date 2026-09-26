package com.wenku8.epubstudio.reader

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 缓存的单个在线章节正文。
 *
 * 只保存渲染需要的最小集合：正文块、标题、来源页。**不保存 Cookie、账号或任何登录态**，
 * 因此缓存可离线复用而不会泄露凭据。
 */
@Serializable
data class OnlineChapterCacheEntry(
    val bookId: String,
    val chapterId: String,
    val title: String = "",
    val volume: String = "",
    val sourceUrl: String = "",
    val cachedAt: Long = 0L,
    val blocks: List<ReaderBlock> = emptyList(),
)

/**
 * 在线阅读的正文磁盘缓存，位于 `filesDir/online-cache/{bookId}/{chapterId}.json`。
 *
 * 设计要点：
 * - **命中缓存不发任何网络请求**，所以断网时已读过的章节照样能打开（AGENTS.md 4.2/4.5）。
 * - 每本书最多保留 [maxChaptersPerBook] 章，超出按 LRU（文件 lastModified）淘汰。
 * - 写入用「临时文件 + rename」，避免进程被杀后留下半截 JSON。
 * - **缓存写入失败不阻塞阅读**：所有写操作都吞掉异常并返回 false。
 *
 * 本类刻意不依赖任何 Android 运行时类型，便于在纯 JVM 单元测试里验证。
 */
class OnlineChapterCache(
    private val rootDirectory: File,
    val maxChaptersPerBook: Int = DEFAULT_MAX_CHAPTERS,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val goneSuffix = ".gone"

    fun bookDirectory(bookId: String): File = File(rootDirectory, segment(bookId))

    fun fileFor(bookId: String, chapterId: String): File =
        File(bookDirectory(bookId), "${segment(chapterId)}.json")

    /** 相对缓存根目录的键，形如 `2835/100`。测试直接断言这个字符串。 */
    fun key(bookId: String, chapterId: String): String = "${segment(bookId)}/${segment(chapterId)}"

    fun has(bookId: String, chapterId: String): Boolean = fileFor(bookId, chapterId).isFile

    fun chapterCount(bookId: String): Int = chapterFiles(bookId).size

    /**
     * 读缓存并顺带刷新 LRU 时间戳。
     * 文件损坏（半截 JSON、被外部清理）时删除并返回 null，调用方应当回落到网络。
     */
    fun read(bookId: String, chapterId: String): OnlineChapterCacheEntry? {
        val file = fileFor(bookId, chapterId)
        if (!file.isFile) return null
        val decoded = runCatching {
            json.decodeFromString(OnlineChapterCacheEntry.serializer(), file.readText(Charsets.UTF_8))
        }.getOrNull()
        if (decoded == null) {
            runCatching { file.delete() }
            return null
        }
        touch(file)
        return decoded
    }

    /** 写缓存。任何失败都只返回 false，不抛异常。 */
    fun write(entry: OnlineChapterCacheEntry): Boolean {
        val directory = bookDirectory(entry.bookId)
        if (!directory.isDirectory && !directory.mkdirs()) return false
        val target = fileFor(entry.bookId, entry.chapterId)
        val temp = File(directory, "${target.name}.tmp")
        val encoded = runCatching {
            json.encodeToString(OnlineChapterCacheEntry.serializer(), entry.copy(cachedAt = System.currentTimeMillis()))
        }.getOrNull() ?: return false
        if (runCatching { temp.writeText(encoded, Charsets.UTF_8) }.isFailure) {
            runCatching { temp.delete() }
            return false
        }
        // 临时文件 + rename：同分区 rename 是原子的，读者永远看不到半截 JSON。
        if (!runCatching { temp.renameTo(target) }.getOrDefault(false)) {
            // 某些文件系统上目标已存在时 rename 会失败，先删再试一次。
            runCatching { target.delete() }
            val retried = runCatching { temp.renameTo(target) }.getOrDefault(false)
            if (!retried) {
                runCatching { temp.delete() }
                return false
            }
        }
        touch(target)
        evict(entry.bookId)
        return true
    }

    /**
     * 标记章节已被源站删除。之后的读取直接短路返回失效，不重复打扰源站。
     */
    fun markGone(bookId: String, chapterId: String): Boolean {
        val directory = bookDirectory(bookId)
        if (!directory.isDirectory && !directory.mkdirs()) return false
        return runCatching {
            File(directory, "${segment(chapterId)}$goneSuffix").writeText("", Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    fun isGone(bookId: String, chapterId: String): Boolean =
        File(bookDirectory(bookId), "${segment(chapterId)}$goneSuffix").isFile

    fun clearGone(bookId: String, chapterId: String) {
        runCatching { File(bookDirectory(bookId), "${segment(chapterId)}$goneSuffix").delete() }
    }

    /** 淘汰超出上限的章节，返回被删掉的文件名（测试断言用）。 */
    fun evict(bookId: String): List<String> {
        val files = chapterFiles(bookId)
        if (files.size <= maxChaptersPerBook) return emptyList()
        val dropped = files.drop(maxChaptersPerBook)
        dropped.forEach { runCatching { it.delete() } }
        return dropped.map { it.name }
    }

    fun clear(bookId: String? = null) {
        val target = if (bookId == null) rootDirectory else bookDirectory(bookId)
        runCatching { target.deleteRecursively() }
    }

    /** 按最近使用时间倒序；时间相同时按文件名倒序，保证结果可复现。 */
    private fun chapterFiles(bookId: String): List<File> =
        bookDirectory(bookId).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedWith(
                compareByDescending<File> { it.lastModified() }
                    .thenByDescending { it.name },
            )
            .orEmpty()

    private fun touch(file: File) {
        runCatching { file.setLastModified(System.currentTimeMillis()) }
    }

    /**
     * 文件名安全化。目录穿越字符被替换；只有真正发生替换时才追加哈希后缀，
     * 避免 `../../` 这类输入互相覆盖，同时保持正常 ID 的路径可读。
     */
    /**
     * 文件名安全化。
     *
     * 允许集里**刻意不含 `.`**：一旦允许，`../../evil` 会被洗成 `.._.._evil`，
     * 名字里仍然留着 `..`，断言与「任何单一文件名组件都不得等于或含有 `..`」的安全意图都会被绕过。
     * 去掉 `.` 后 `../../evil` 变成 `______evil-<hash>`，`..` 无处可藏。
     * 只有真正发生替换时才追加哈希后缀，避免 `../../` 这类输入互相覆盖，
     * 同时保持正常 ID（纯数字）的路径可读且稳定。
     */
    private fun segment(value: String): String {
        val trimmed = value.trim()
        val cleaned = trimmed.replace(UNSAFE, "_").take(64)
        if (cleaned.isBlank() || cleaned == "." || cleaned == "..") return "_"
        return if (cleaned == trimmed) cleaned else "$cleaned-${trimmed.hashCode().toUInt().toString(16)}"
    }

    companion object {
        const val DEFAULT_MAX_CHAPTERS = 50

        /** 允许集只有字母、数字、下划线、连字符；`.` 与路径分隔符一律替换。 */
        private val UNSAFE = Regex("[^A-Za-z0-9_-]")
    }
}

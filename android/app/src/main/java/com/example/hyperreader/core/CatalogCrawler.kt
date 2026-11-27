package com.example.hyperreader.core

import android.content.Context
import java.io.File
import java.time.Year
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 公开书目索引抓取器。
 *
 * 严格只请求 wenku8 对匿名访客公开返回 200 的页面：
 *  - articleinfo.php 书籍详情
 *  - authorarticle.php 同作者作品列表
 *  - /zt/sugoi/{year}.php 年度精选榜
 *  - /zt/booklist/{yyyyMM}.php 往期书单存档（仅 2015–2016 的季度页）
 *
 * 明确不涉及 search.php / articlelist.php / toplist.php / tags.php，
 * 这些由站点自行控制登录，应用不去规避。使用独立的无 Cookie 客户端，
 * 即使本地存在登录态也不携带任何 Cookie。
 */
class CatalogCrawler(
    context: Context,
    /** 全链路书源；装配后公开页取数都从书源出，避免再建客户端。 */
    private val sourceProvider: () -> Wenku8BookSource? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mapSerializer = MapSerializer(String.serializer(), CatalogEntry.serializer())
    private val directory = File(context.filesDir, "catalog").apply { mkdirs() }
    private val entriesFile = File(directory, "catalog.json")
    private val cursorFile = File(directory, "index.json")

    /**
     * 公开页客户端：**不携带会话 Cookie**，但携带 Cloudflare 的 `cf_clearance`
     * （人机验证通过凭证，不是账号凭据）——否则用户验证完了公开页仍然 403。
     */
    private val http by lazy {
        Wenku8HttpClient(
            File(context.cacheDir, "wenku8-catalog"),
            (context.applicationContext as com.example.hyperreader.Wenku8Application).sessionStore.clearanceCookieJar(),
        )
    }
    private val jobId = "catalog"

    fun loadEntries(): MutableMap<String, CatalogEntry> {
        if (!entriesFile.isFile) return mutableMapOf()
        return runCatching { json.decodeFromString(mapSerializer, entriesFile.readText()) }
            .getOrElse {
                entriesFile.copyTo(File(directory, "catalog.json.bak"), overwrite = true)
                mutableMapOf()
            }
            .toMutableMap()
    }

    fun loadCursor(): CatalogCursor {
        if (!cursorFile.isFile) return CatalogCursor()
        return runCatching { json.decodeFromString(CatalogCursor.serializer(), cursorFile.readText()) }.getOrDefault(CatalogCursor())
    }

    fun stats(entries: Map<String, CatalogEntry>, cursor: CatalogCursor) = CatalogStats(
        count = entries.size,
        lastUpdatedAt = cursor.lastRunAt,
        totalFetched = cursor.totalFetched,
        skipped = cursor.skipped,
    )

    private fun persist(entries: Map<String, CatalogEntry>, cursor: CatalogCursor) {
        writeAtomic(entriesFile, json.encodeToString(mapSerializer, entries))
        writeAtomic(cursorFile, json.encodeToString(CatalogCursor.serializer(), cursor))
    }

    fun clear() {
        entriesFile.delete()
        cursorFile.delete()
        File(directory, "catalog.json.bak").delete()
    }

    fun writeAtomic(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }

    /** 年度精选榜种子（最近若干年）。 */
    fun sugoiSeeds(years: Int = 5): List<CatalogSeed> {
        val thisYear = Year.now().value
        return (0 until years).map { CatalogSeed(SeedKind.SUGOI, Wenku8Urls.sugoi(thisYear - it), "${thisYear - it} 年度精选") }
    }

    /**
     * 往期书单种子。
     *
     * `/zt/booklist/` 只有 2015–2016 的历史存档（201501/201504/201507/201510/201601），
     * 源站已不再按自然月发布；按「最近若干月」拼 URL 会 404。这里固定使用有效存档，
     * 作为书目缓存的补充种子。
     */
    fun booklistSeeds(limit: Int = BOOKLIST_ARCHIVES.size): List<CatalogSeed> =
        BOOKLIST_ARCHIVES.take(limit).map { (stamp, title) ->
            CatalogSeed(SeedKind.BOOKLIST, Wenku8Urls.booklist(stamp), title)
        }

    /** 内置保底种子 ID。 */
    fun idSeeds(ids: List<String> = DEFAULT_SEED_IDS): List<CatalogSeed> =
        ids.map { CatalogSeed(SeedKind.SEED_ID, Wenku8Urls.book(it), it, listOf(it)) }

    /**
     * 增量抓取一轮：从种子页取书，按作者页做广度优先扩展，直到达到详情预算。
     * 每本详情只请求 articleinfo.php。
     */
    suspend fun crawlOnce(
        entries: MutableMap<String, CatalogEntry>,
        cursor: CatalogCursor,
        detailBudget: Int = 200,
        queueDepthLimit: Int = 3,
        onProgress: (fetched: Int, budget: Int) -> Unit = { _, _ -> },
    ): CatalogCursor {
        val queue = ArrayDeque(cursor.queue)
        val processed = cursor.processedIds.toMutableSet()
        var fetched = 0
        var skipped = 0
        val authorUrls = LinkedHashSet<String>()

        // 1) 首次运行时抓种子页，得到初始书籍 ID
        if (queue.isEmpty()) {
            for (seed in sugoiSeeds() + booklistSeeds() + idSeeds()) {
                val page = fetchOrNull(seed.url, jobId)
                val links = page?.let { Wenku8Parser.parseSeedList(it.html, it.finalUrl, seed.name) }
                val ids = when (seed.kind) {
                    SeedKind.SEED_ID -> seed.bookIds
                    else -> links.orEmpty().map { it.bookId }
                }
                ids.forEach { if (it !in processed) queue.addLast(it) }
            }
        }

        // 2) 广度优先抓取书籍详情，并记录作者页链接
        var depth = 0
        while (queue.isNotEmpty() && fetched < detailBudget && depth < queueDepthLimit) {
            val bookId = queue.removeFirst()
            if (!processed.add(bookId)) continue
            val page = fetchOrNull(Wenku8Urls.articleInfo(bookId), jobId)
            if (page != null) {
                runCatching { Wenku8Parser.parseCatalogEntry(page.html, page.finalUrl) }
                    .onSuccess { entry ->
                        entries[entry.id] = entry
                        fetched++
                        onProgress(fetched, detailBudget)
                        runCatching { Wenku8Parser.parseAuthorLink(page.html, page.finalUrl) }
                            .getOrNull()?.let(authorUrls::add)
                    }
                    .onFailure { skipped++ }
            } else {
                skipped++
            }
            depth++
        }

        // 3) 用作者页扩展队列：抓该作者所有作品（含分页）
        for (authorUrl in authorUrls) {
            var pageNo = 1
            while (pageNo <= MAX_AUTHOR_PAGES && queue.size < detailBudget) {
                val target = if (pageNo == 1) authorUrl else appendPage(authorUrl, pageNo)
                val page = fetchOrNull(target, jobId) ?: break
                val links = runCatching { Wenku8Parser.parseBookLinks(page.html, page.finalUrl) }.getOrDefault(emptyList())
                if (links.isEmpty()) break
                links.forEach { if (it.id !in processed) queue.addLast(it.id) }
                if (links.size < AUTHOR_PAGE_SIZE) break
                pageNo++
            }
        }

        val nextCursor = cursor.copy(
            processedIds = processed.toList().takeLast(MAX_PROCESSED),
            queue = queue.toList().takeLast(MAX_QUEUE),
            lastRunAt = System.currentTimeMillis(),
            totalFetched = cursor.totalFetched + fetched,
            skipped = cursor.skipped + skipped,
        )
        persist(entries, nextCursor)
        return nextCursor
    }

    /** 公开页抓取：失败可跳过，但协程取消必须继续传播。 */
    private suspend fun fetchOrNull(url: String, jobId: String): Wenku8HttpClient.TextResource? = try {
        sourceProvider()?.fetchPublicPage(url, jobId)
            ?: http.fetchText(url, jobId, Wenku8Urls.BASE)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private fun appendPage(url: String, page: Int): String =
        if (url.contains("&page=")) url.replace(Regex("([?&]page=)\\d+"), "$1$page") else "$url&page=$page"

    private companion object {
        val DEFAULT_SEED_IDS = listOf("2835", "2580", "2964", "3057", "3988")
        val BOOKLIST_ARCHIVES = listOf(
            "201601" to "2016年冬季书单",
            "201510" to "2015年秋季书单",
            "201507" to "2015年夏季书单",
            "201504" to "2015年春季书单",
            "201501" to "2015年冬季书单",
        )
        const val AUTHOR_PAGE_SIZE = 20
        const val MAX_AUTHOR_PAGES = 3
        const val MAX_PROCESSED = 4000
        const val MAX_QUEUE = 2000
    }
}

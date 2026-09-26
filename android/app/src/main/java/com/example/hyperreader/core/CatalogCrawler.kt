package com.example.hyperreader.core

import android.content.Context
import java.io.File
import java.time.Year
import java.time.YearMonth
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
 *  - /zt/booklist/{yyyyMM}.php 月度新书榜
 *
 * 明确不涉及 search.php / articlelist.php / toplist.php / tags.php，
 * 这些由站点自行控制登录，应用不去规避。使用独立的无 Cookie 客户端，
 * 即使本地存在登录态也不携带任何 Cookie。
 */
class CatalogCrawler(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mapSerializer = MapSerializer(String.serializer(), CatalogEntry.serializer())
    private val directory = File(context.filesDir, "catalog").apply { mkdirs() }
    private val entriesFile = File(directory, "catalog.json")
    private val cursorFile = File(directory, "index.json")

    /** 独立的无 Cookie 客户端。 */
    private val http = Wenku8HttpClient(File(context.cacheDir, "wenku8-catalog"))
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

    /** 月度新书榜种子（最近若干月）。 */
    fun booklistSeeds(months: Int = 3): List<CatalogSeed> {
        val now = YearMonth.now()
        return (0 until months).map {
            val ym = now.minusMonths(it.toLong())
            CatalogSeed(SeedKind.BOOKLIST, Wenku8Urls.booklist("${ym.year}${ym.monthValue.toString().padStart(2, '0')}"), "${ym.year}年${ym.monthValue}月新书")
        }
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
                val links = runCatching {
                    val page = http.fetchText(seed.url, jobId, Wenku8Urls.BASE)
                    Wenku8Parser.parseSeedList(page.html, page.finalUrl, seed.name)
                }.getOrNull()
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
            var page: Wenku8HttpClient.TextResource? = null
            runCatching { http.fetchText(Wenku8Urls.articleInfo(bookId), jobId, Wenku8Urls.BASE) }
                .onSuccess {
                    page = it
                    runCatching { Wenku8Parser.parseCatalogEntry(it.html, it.finalUrl) }
                        .onSuccess { entry ->
                            entries[entry.id] = entry
                            fetched++
                            onProgress(fetched, detailBudget)
                            runCatching { Wenku8Parser.parseAuthorLink(it.html, it.finalUrl) }.getOrNull()?.let(authorUrls::add)
                        }
                        .onFailure { skipped++ }
                }
                .onFailure { skipped++ }
            page = null
            depth++
        }

        // 3) 用作者页扩展队列：抓该作者所有作品（含分页）
        for (authorUrl in authorUrls) {
            var pageNo = 1
            while (pageNo <= MAX_AUTHOR_PAGES && queue.size < detailBudget) {
                val target = if (pageNo == 1) authorUrl else appendPage(authorUrl, pageNo)
                val page = runCatching { http.fetchText(target, jobId, Wenku8Urls.BASE) }.getOrNull() ?: break
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

    private fun appendPage(url: String, page: Int): String =
        if (url.contains("&page=")) url.replace(Regex("([?&]page=)\\d+"), "$1$page") else "$url&page=$page"

    private companion object {
        val DEFAULT_SEED_IDS = listOf("2835", "2580", "2964", "3057", "3988")
        const val AUTHOR_PAGE_SIZE = 20
        const val MAX_AUTHOR_PAGES = 3
        const val MAX_PROCESSED = 4000
        const val MAX_QUEUE = 2000
    }
}

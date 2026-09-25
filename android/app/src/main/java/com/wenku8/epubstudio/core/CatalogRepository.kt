package com.wenku8.epubstudio.core

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CatalogState(
    val stats: CatalogStats = CatalogStats(),
    val loading: Boolean = false,
    val message: String? = null,
    val progress: Pair<Int, Int>? = null,
)

/**
 * 免登录书目仓库：协调抓取、持久化与内存索引。
 * 使用独立的无 Cookie 客户端，不携带登录态。
 */
class CatalogRepository(private val context: Context) {
    private val crawler = CatalogCrawler(context)
    private val http = Wenku8HttpClient(File(context.cacheDir, "wenku8-catalog"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val entries = HashMap<String, CatalogEntry>()
    private var cursor = CatalogCursor()
    private var index = CatalogIndex(emptyList())

    private val mutable = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = mutable.asStateFlow()

    init {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { crawler.loadEntries() to crawler.loadCursor() }
            lock.withLock {
                entries.clear()
                entries.putAll(loaded.first)
                cursor = loaded.second
                index = CatalogIndex(entries.values)
                mutable.value = CatalogState(stats = crawler.stats(entries, cursor))
            }
        }
    }

    fun currentStats(): CatalogStats = crawler.stats(entries, cursor)
    fun tagList(): List<String> = index.allTags
    fun cachedSize(): Int = index.size
    fun get(id: String): CatalogEntry? = index.get(id)

    /** 本地搜索，免登录。 */
    fun search(query: String, field: CatalogSearchField, limit: Int = 60): List<CatalogEntry> = index.search(query, field, limit)
    fun searchTag(tag: String): List<CatalogEntry> = index.searchTag(tag)

    /** 触发一次增量抓取。 */
    fun update(detailBudget: Int = 200) {
        scope.launch {
            lock.withLock {
                if (mutable.value.loading) return@launch
                mutable.value = mutable.value.copy(loading = true, message = null, progress = 0 to detailBudget)
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val newEntries = HashMap(entries)
                    val newCursor = crawler.crawlOnce(newEntries, cursor, detailBudget) { fetched, budget ->
                        mutable.value = mutable.value.copy(progress = fetched to budget)
                    }
                    newEntries to newCursor
                }
            }
            lock.withLock {
                result.onSuccess { (newEntries, newCursor) ->
                    entries.clear()
                    entries.putAll(newEntries)
                    cursor = newCursor
                    index = CatalogIndex(entries.values)
                    mutable.value = CatalogState(stats = crawler.stats(entries, cursor))
                }.onFailure { error ->
                    mutable.value = mutable.value.copy(loading = false, message = error.message ?: "更新失败。", progress = null)
                }
            }
        }
    }

    /** 按需补齐单本详情（打开书籍时用）。 */
    fun ensureBook(id: String, onDone: (CatalogEntry?) -> Unit) {
        scope.launch {
            val existing = index.get(id)
            if (existing != null) {
                onDone(existing)
                return@launch
            }
            val entry = withContext(Dispatchers.IO) {
                runCatching {
                    val page = http.fetchText(Wenku8Urls.articleInfo(id), "catalog-one", Wenku8Urls.BASE)
                    Wenku8Parser.parseCatalogEntry(page.html, page.finalUrl)
                }.getOrNull()
            }
            if (entry != null) {
                lock.withLock {
                    entries[entry.id] = entry
                    index = CatalogIndex(entries.values)
                    mutable.value = mutable.value.copy(stats = crawler.stats(entries, cursor))
                }
            }
            onDone(entry)
        }
    }

    fun clearMessage() { mutable.value = mutable.value.copy(message = null) }
}

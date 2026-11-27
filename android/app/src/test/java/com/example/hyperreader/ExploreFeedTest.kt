package com.example.hyperreader

import com.example.hyperreader.core.ExploreFeed
import com.example.hyperreader.core.ExploreCardCache
import com.example.hyperreader.core.ExplorePage
import com.example.hyperreader.model.SearchBook
import com.example.hyperreader.ui.StudioViewModel
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探索榜单定义与卡片合并的回归基线。
 *
 * 公开榜只有 ID，卡片来自本地索引或逐本补全；补全顺序若按到达顺序追加，
 * 榜单排名会被打乱。这里锁定「按榜单 ID 顺序合并、本地字段优先、逐本补全去重」。
 */
class ExploreFeedTest {

    private val publicPage = ExplorePage(
        id = "sugoi-2026",
        title = "2026 年度精选",
        url = "https://www.wenku8.net/zt/sugoi/2026.php",
        requiresAuth = false,
    )
    private val authPage = ExplorePage(
        id = "toplist-lastupdate",
        title = "今日更新",
        url = "https://www.wenku8.net/modules/article/toplist.php?sort=lastupdate",
        requiresAuth = true,
    )

    @Test
    fun pagesCoverAuthAndPublicFeedsInDisplayOrder() {
        val pages = ExploreFeed.pages(YearMonth.of(2026, 11))

        assertEquals(12, pages.size)
        assertEquals(
            listOf("toplist-lastupdate", "toplist-allvisit", "toplist-postdate", "toplist-anime"),
            pages.take(4).map { it.id },
        )
        assertTrue("前四个排行榜需要登录", pages.take(4).all { it.requiresAuth })

        assertEquals(
            listOf("sugoi-2026", "sugoi-2025", "sugoi-2024", "sugoi-2023", "sugoi-2022"),
            pages.subList(4, 9).map { it.id },
        )
        assertTrue("年度精选是公开页", pages.subList(4, 9).none { it.requiresAuth })

        assertEquals(
            listOf("booklist-202611", "booklist-202610", "booklist-202609"),
            pages.drop(9).map { it.id },
        )
        assertEquals("11 月新书", pages[9].title)
        assertTrue(pages[9].url.endsWith("202611.php"))
        assertTrue("月度新书是公开页", pages.drop(9).none { it.requiresAuth })
    }

    @Test
    fun anonymousUsersCanLoadOnlyPublicFeeds() {
        assertTrue(ExploreFeed.canLoad(publicPage, loggedIn = false))
        assertFalse(ExploreFeed.canLoad(authPage, loggedIn = false))
        assertTrue(ExploreFeed.canLoad(authPage, loggedIn = true))
    }

    @Test
    fun anonymousUsersOnlySeePublicFeeds() {
        val pages = ExploreFeed.pages(YearMonth.of(2026, 11))
        val anonymous = ExploreFeed.visiblePages(pages, loggedIn = false)
        val signedIn = ExploreFeed.visiblePages(pages, loggedIn = true)

        assertTrue("未登录不应看到登录墙榜单", anonymous.none { it.requiresAuth })
        assertEquals(8, anonymous.size)
        assertEquals(12, signedIn.size)
    }

    @Test
    fun defaultPageIsLoadableForBothSessionStates() {
        val pages = ExploreFeed.pages(YearMonth.of(2026, 11))
        val anonymous = StudioViewModel.defaultExplorePage(pages, loggedIn = false)
        val signedIn = StudioViewModel.defaultExplorePage(pages, loggedIn = true)

        assertFalse("匿名首屏不能落到登录墙榜单", anonymous.requiresAuth)
        assertTrue("登录后首屏优先排行榜", signedIn.requiresAuth)
        assertTrue(ExploreFeed.canLoad(anonymous, loggedIn = false))
        assertTrue(ExploreFeed.canLoad(signedIn, loggedIn = true))
    }

    @Test
    fun mergeCardsKeepsRankOrderDedupesAndPrefersCatalog() {
        val ids = listOf("2", "1", "2", "3")
        val catalog = mapOf(
            "1" to book("1", "本地一"),
            "3" to book("3", "本地三"),
        )
        val remote = mapOf(
            "2" to book("2", "远程二"),
            "3" to book("3", "远程三"),
        )

        val merged = ExploreFeed.mergeCards(ids, catalog::get, remote)

        assertEquals(listOf("2", "1", "3"), merged.map { it.id })
        assertEquals("远程二", merged[0].title)
        assertEquals("本地一", merged[1].title)
        assertEquals("本地字段优先", "本地三", merged[2].title)
    }

    @Test
    fun missingIdsOnlyCoversPublicFeedsAndSkipsKnownCards() {
        val ids = listOf("1", "2", "3", "2", "4", "5")
        val catalog = mapOf("1" to book("1"))
        val remote = mapOf("2" to book("2"))
        val catalogLookup: (String) -> SearchBook? = catalog::get

        assertEquals(
            listOf("3", "4"),
            ExploreFeed.missingIds(publicPage, ids, catalogLookup, remote, limit = 2),
        )
        assertTrue(
            "登录墙榜单首屏已带完整卡片，不再逐本补全",
            ExploreFeed.missingIds(authPage, ids, catalogLookup, remote, limit = 2).isEmpty(),
        )
    }

    @Test
    fun fetchedCardsSurviveAcrossRefreshBatches() {
        val ids = (1..25).map { it.toString() }
        val cache = ExploreCardCache()
        val catalog: (String) -> SearchBook? = { null }

        val firstBatch = ExploreFeed.missingIds(publicPage, ids, catalog, cache.snapshot(), limit = 20)
        assertEquals((1..20).map { it.toString() }, firstBatch)
        firstBatch.forEach { id -> cache.put(book(id, "远程 $id")) }

        val secondBatch = ExploreFeed.missingIds(publicPage, ids, catalog, cache.snapshot(), limit = 20)
        assertEquals("第二轮必须从第 21 本继续，而不是重新补前 20 本", (21..25).map { it.toString() }, secondBatch)
        secondBatch.forEach { id -> cache.put(book(id, "远程 $id")) }

        val merged = ExploreFeed.mergeCards(ids, catalog, cache.snapshot())
        assertEquals(ids, merged.map { it.id })
        assertEquals("远程 1", merged.first().title)
    }

    @Test
    fun cardCacheKeepsMostRecentCardsBeyondCapacity() {
        val cache = ExploreCardCache(capacity = 2)
        cache.put(book("1", "一"))
        cache.put(book("2", "二"))
        cache.put(book("3", "三"))

        assertEquals(2, cache.size())
        assertNull("最早写入的卡片应被淘汰", cache.get("1"))
        assertEquals("二", cache.get("2")?.title)
        assertEquals("三", cache.get("3")?.title)
    }

    @Test
    fun publicIdsFollowPageOrderAndDropDuplicates() {
        val html = """
            <html><body>
            <a href="/book/7.htm">七</a>
            <a href="/book/3.htm">三</a>
            <a href="/book/7.htm">重复</a>
            <a href="/news/">非书籍链接</a>
            </body></html>
        """.trimIndent()

        assertEquals(
            listOf("7", "3"),
            ExploreFeed.parsePublicIds(html, "https://www.wenku8.net/zt/sugoi/2026.php"),
        )
    }

    private fun book(id: String, title: String = "书 $id") = SearchBook(
        id = id,
        title = title,
        sourceUrl = "https://www.wenku8.net/book/$id.htm",
    )
}

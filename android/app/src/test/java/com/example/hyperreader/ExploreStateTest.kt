package com.example.hyperreader

import com.example.hyperreader.core.ExploreBooksRow
import com.example.hyperreader.core.ExploreBookDetail
import com.example.hyperreader.core.ExploreBookSeed
import com.example.hyperreader.core.ExplorePage
import com.example.hyperreader.model.Book
import com.example.hyperreader.model.SearchBook
import com.example.hyperreader.ui.ExploreState
import com.example.hyperreader.ui.StudioUiState
import com.example.hyperreader.ui.StudioViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探索页状态迁移的回归基线。
 *
 * 这些规则此前散落在 ViewModel 的协程块里：快速切榜、补全过程中切标签、
 * 未登录点排行榜都可能让旧响应覆盖新页面。纯函数测试把「什么响应能写状态」
 * 钉死，网络层无关。
 */
class ExploreStateTest {

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
    fun loadingClearsPreviousRowsAndMessageAndStartsNewRequest() {
        val previous = StudioUiState(
            exploreRows = listOf(ExploreBooksRow("旧榜", listOf(book("old")), "old")),
            exploreMessage = "旧提示",
            exploreBusy = false,
        )

        val loading = ExploreState.loading(previous, publicPage, requestId = 7)

        assertEquals(publicPage, loading.activeExplorePage)
        assertEquals(7L, loading.exploreRequestId)
        assertTrue(loading.exploreBusy)
        assertFalse(loading.exploreRefreshing)
        assertNull(loading.exploreMessage)
        assertTrue(loading.exploreRows.isEmpty())
    }

    @Test
    fun pullToRefreshKeepsRowsButOnlySpinsTheRefreshIndicator() {
        val previous = StudioUiState(
            activeExplorePage = publicPage,
            exploreRows = listOf(ExploreBooksRow(publicPage.title, listOf(book("1")), publicPage.id)),
            exploreLoadedOnce = true,
        )

        val refreshing = ExploreState.loading(previous, publicPage, requestId = 11, refreshing = true)

        assertTrue(refreshing.exploreRefreshing)
        assertEquals(previous.exploreRows, refreshing.exploreRows)
        // 首次页面请求返回后停掉下拉指示器；后台逐本补全不算刷新。
        val rows = ExploreState.rows(refreshing, publicPage, previous.exploreRows.single().books, requestId = 11, busy = true)
        assertFalse(rows.exploreRefreshing)
        assertTrue(rows.exploreBusy)
        assertEquals(previous.exploreRows, rows.exploreRows)
    }

    @Test
    fun rowsKeepBusyUntilCompletionAndAttachExpandedPageId() {
        val loading = ExploreState.loading(StudioUiState(), publicPage, requestId = 7)
        val rows = ExploreState.rows(
            loading,
            publicPage,
            listOf(book("1"), book("2")),
            requestId = 7,
            busy = true,
        )

        assertTrue(rows.exploreBusy)
        val row = rows.exploreRows.single()
        assertEquals(publicPage.title, row.title)
        assertEquals(publicPage.id, row.expandedPageId)
        assertEquals(listOf("1", "2"), row.books.map { it.id })

        val completed = ExploreState.completed(rows, requestId = 7)
        assertFalse(completed.exploreBusy)
        assertNull(completed.exploreMessage)
    }

    @Test
    fun emptyPublicPageCanStayBusyWhileAutoFetching() {
        val loading = ExploreState.loading(StudioUiState(), publicPage, requestId = 9)
        val rows = ExploreState.rows(loading, publicPage, emptyList(), requestId = 9, busy = true)

        assertTrue(rows.exploreBusy)
        assertTrue(rows.exploreRows.isEmpty())

        val failed = ExploreState.failed(rows, "该榜单暂时没有内容。", requestId = 9)
        assertFalse(failed.exploreBusy)
        assertEquals("该榜单暂时没有内容。", failed.exploreMessage)
    }

    @Test
    fun completionWarningIsShownOnlyWhenTheRequestIsStillCurrent() {
        val current = ExploreState.loading(StudioUiState(), publicPage, requestId = 12)

        val warned = ExploreState.completed(current, requestId = 12, warning = "补全失败")
        assertEquals("补全失败", warned.exploreMessage)
        assertFalse(warned.exploreBusy)

        assertSame(current, ExploreState.completed(current, requestId = 11, warning = "旧请求"))
    }

    @Test
    fun progressAdvancesOnFailuresAndLabelsThem() {
        val current = ExploreState.loading(StudioUiState(), publicPage, requestId = 12)

        val first = ExploreState.progress(current, processed = 1, total = 3, requestId = 12, failures = 1)
        assertEquals("失败也计入已处理，不能停在 0/3", "正在补全 1/3（失败 1）…", first.exploreMessage)

        val second = ExploreState.progress(first, processed = 2, total = 3, requestId = 12, failures = 1)
        assertEquals("正在补全 2/3（失败 1）…", second.exploreMessage)
    }

    @Test
    fun completionWarningDistinguishesFailureAndRemainingBatches() {
        assertEquals(
            "公开书目补全失败：可尝试浏览器验证或稍后刷新。",
            ExploreState.completionWarning(total = 40, shown = 0, attempted = 20, failures = 20),
        )
        assertTrue(
            ExploreState.completionWarning(total = 40, shown = 18, attempted = 20, failures = 2)
                .orEmpty()
                .contains("已显示 18/40 本"),
        )
        assertEquals(
            "上一轮已有卡片时，本轮全失败不能误报整榜不可用",
            "2 本公开书目补全失败；已显示 18/40 本，刷新可继续。",
            ExploreState.completionWarning(total = 40, shown = 18, attempted = 2, failures = 2),
        )
        assertTrue(
            ExploreState.completionWarning(total = 40, shown = 40, attempted = 20, failures = 2)
                .orEmpty()
                .contains("2 本公开书目补全失败"),
        )
        assertEquals(
            "补全上限之外的条目要提示还能刷新继续",
            "已显示 20/40 本，刷新可继续补全。",
            ExploreState.completionWarning(total = 40, shown = 20, attempted = 20, failures = 0),
        )
        assertNull(ExploreState.completionWarning(total = 20, shown = 20, attempted = 0, failures = 0))
    }

    @Test
    fun emptyPublicFeedDoesNotHideCompletionFailures() {
        assertEquals(
            "有 ID 但全部补全失败时，不能误报成空榜",
            "公开书目补全失败：可尝试浏览器验证或稍后刷新。",
            ExploreState.emptyPublicResult(total = 12, attempted = 3, failures = 3),
        )
        assertEquals(
            "有 ID、但一张都没补成功也没有失败时，应提示可刷新重试",
            "该榜单暂时没有可显示的书目，刷新可重试。",
            ExploreState.emptyPublicResult(total = 12, attempted = 0, failures = 0),
        )
        assertEquals(
            "确实没有 ID 时才是空榜",
            "该榜单暂时没有内容。",
            ExploreState.emptyPublicResult(total = 0, attempted = 0, failures = 0),
        )
    }

    @Test
    fun staleResponsesCannotOverwriteCurrentPage() {
        val current = ExploreState.loading(StudioUiState(), publicPage, requestId = 8)

        assertSame(current, ExploreState.rows(current, authPage, listOf(book("stale")), requestId = 7))
        assertSame(current, ExploreState.failed(current, "旧失败", requestId = 7))
        assertSame(current, ExploreState.progress(current, 1, 3, requestId = 7))
        assertSame(current, ExploreState.completed(current, requestId = 7))
    }

    @Test
    fun rejectedPageAdvancesRequestIdAndInvalidatesInFlightLoad() {
        val inFlight = ExploreState.loading(
            StudioUiState(exploreRows = listOf(ExploreBooksRow("旧榜", listOf(book("old")), "old"))),
            publicPage,
            requestId = 3,
        )

        val rejected = ExploreState.rejected(inFlight, authPage, "登录后可查看该榜单。", requestId = 4)

        assertEquals(authPage, rejected.activeExplorePage)
        assertEquals(4L, rejected.exploreRequestId)
        assertFalse(rejected.exploreBusy)
        assertEquals("登录后可查看该榜单。", rejected.exploreMessage)
        assertTrue(rejected.exploreRows.isEmpty())
        // 旧请求仍在途时返回，不能把「需要登录」的页面覆盖回公开榜。
        assertSame(rejected, ExploreState.rows(rejected, publicPage, listOf(book("late")), requestId = 3))
    }

    @Test
    fun tagSelectionWaitsForServerAndMergesWithoutDuplicates() {
        val selected = ExploreState.selectTag(
            state = StudioUiState(),
            tag = "恋爱",
            local = listOf(book("local-1"), book("shared", "本地")),
            loggedIn = true,
            awaitingServer = true,
        )

        assertEquals("恋爱", selected.activeTag)
        assertTrue(selected.tagBusy)

        val merged = ExploreState.mergeTag(
            selected,
            tag = "恋爱",
            server = listOf(book("server-1"), book("shared", "服务端"), book("server-1", "重复")),
            local = listOf(book("local-1"), book("shared", "本地")),
        )

        assertEquals(listOf("server-1", "shared", "local-1"), merged.tagResults.map { it.id })
        assertEquals("服务端", merged.tagResults[1].title)
        assertFalse(merged.tagBusy)
        assertNull(merged.tagMessage)
    }

    @Test
    fun staleTagResponsesAreIgnoredAfterSwitchingOrClearing() {
        val love = ExploreState.selectTag(
            state = StudioUiState(),
            tag = "恋爱",
            local = listOf(book("love")),
            loggedIn = true,
            awaitingServer = true,
        )
        val campus = ExploreState.selectTag(
            state = love,
            tag = "校园",
            local = emptyList(),
            loggedIn = true,
            awaitingServer = true,
        )

        assertSame(campus, ExploreState.mergeTag(campus, "恋爱", listOf(book("late")), emptyList()))
        assertSame(campus, ExploreState.tagFailed(campus, "恋爱", "旧失败"))

        val failed = ExploreState.tagFailed(campus, "校园", "网络错误")
        assertEquals("网络错误", failed.tagMessage)
        assertFalse(failed.tagBusy)
        assertTrue(failed.tagResults.isEmpty())

        val cleared = ExploreState.clearTag(failed)
        assertNull(cleared.activeTag)
        assertFalse(cleared.tagBusy)
        assertTrue(cleared.tagResults.isEmpty())
        assertNull(cleared.tagMessage)
    }

    @Test
    fun anonymousTagSelectionDoesNotStayBusyForever() {
        val selection = ExploreState.selectTag(
            state = StudioUiState(),
            tag = "恋爱",
            local = emptyList(),
            loggedIn = false,
            awaitingServer = true,
        )

        assertFalse(selection.tagBusy)
        assertEquals("恋爱", selection.activeTag)
        assertTrue(selection.tagResults.isEmpty())
    }

    @Test
    fun signingInOrOutInvalidatesSessionScopedContentAndAllowsReload() {
        val sessionContent = StudioUiState(
            loggedIn = true,
            exploreExpanded = true,
            exploreLoadedOnce = true,
            activeExplorePage = authPage,
            exploreRows = listOf(ExploreBooksRow("今日更新", listOf(book("old")), authPage.id)),
            exploreBusy = true,
            exploreMessage = "正在补全 1/3…",
            activeTag = "恋爱",
            tagResults = listOf(book("tag")),
            tagMessage = "旧标签提示",
            tagBusy = true,
            exploreTags = listOf("恋爱", "校园"),
        )

        val signedIn = ExploreState.signedIn(sessionContent, exploreTags = listOf("恋爱", "校园", "奇幻"), requestId = 41)
        assertTrue(signedIn.loggedIn)
        assertEquals(41L, signedIn.exploreRequestId)
        assertFalse("登录后关闭登录前打开的榜单展开层", signedIn.exploreExpanded)
        assertTrue(signedIn.exploreRows.isEmpty())
        assertNull(signedIn.activeExplorePage)
        assertFalse(signedIn.exploreBusy)
        assertFalse(signedIn.exploreLoadedOnce)
        assertNull(signedIn.activeTag)
        assertTrue(signedIn.tagResults.isEmpty())
        assertFalse(signedIn.tagBusy)
        assertEquals(listOf("恋爱", "校园", "奇幻"), signedIn.exploreTags)

        val signedOut = ExploreState.signedOut(sessionContent, exploreTags = listOf("恋爱"), requestId = 42)
        assertFalse(signedOut.loggedIn)
        assertEquals(42L, signedOut.exploreRequestId)
        assertFalse("登出后不能停留在登录墙榜单的展开层", signedOut.exploreExpanded)
        assertTrue(signedOut.exploreRows.isEmpty())
        assertNull(signedOut.activeExplorePage)
        assertFalse(signedOut.exploreLoadedOnce)
        assertNull(signedOut.activeTag)
        assertTrue(signedOut.tagResults.isEmpty())
        assertEquals(listOf("恋爱"), signedOut.exploreTags)
    }

    @Test
    fun sessionReloadIsMarkedLoadedAfterTheRequestCompletes() {
        val signedOut = ExploreState.signedOut(StudioUiState(loggedIn = true), exploreTags = emptyList(), requestId = 51)
        assertFalse(signedOut.exploreLoadedOnce)

        val loading = ExploreState.loading(signedOut, publicPage, requestId = 52)
        val rows = ExploreState.rows(loading, publicPage, listOf(book("1")), requestId = 52)

        assertTrue(
            "登录态变化后的重载完成后必须记为已加载，否则切回 tab 会再次请求",
            rows.exploreLoadedOnce,
        )
    }

    @Test
    fun beginAllocatesANewRequestIdWithoutStaleSelfInvalidation() {
        val signedIn = ExploreState.signedIn(
            StudioUiState(loggedIn = false, exploreRequestId = 7),
            exploreTags = emptyList(),
            requestId = 8,
        )

        val started = ExploreState.begin(signedIn, authPage)

        assertEquals(
            "登录清状态后立即重载，新请求不能复用作废掉的登录序号",
            9L,
            started.exploreRequestId,
        )
        assertTrue(ExploreState.isCurrent(started.exploreRequestId, started.exploreRequestId))
    }

    @Test
    fun rejectingALoginWallPageStartsANewRequestThatInvalidatesTheOldOne() {
        val inFlight = ExploreState.loading(StudioUiState(), publicPage, requestId = 4)

        val rejected = ExploreState.reject(inFlight, authPage, "登录后可查看该榜单。")

        assertEquals(5L, rejected.exploreRequestId)
        assertFalse(ExploreState.isCurrent(inFlight.exploreRequestId, rejected.exploreRequestId))
    }

    @Test
    fun switchingFeedDuringRefreshClearsThePreviousSessionRows() {
        val previous = StudioUiState(
            loggedIn = true,
            activeExplorePage = authPage,
            exploreRows = listOf(ExploreBooksRow("今日更新", listOf(book("auth")), authPage.id)),
        )

        val switched = ExploreState.begin(previous, publicPage, refreshing = true)

        assertEquals("换榜时即使带 refreshing 也不能保留旧登录态内容", emptyList<ExploreBooksRow>(), switched.exploreRows)
        assertFalse(switched.exploreRefreshing)
        assertTrue(switched.exploreBusy)

        val sameFeed = ExploreState.begin(
            StudioUiState(activeExplorePage = publicPage, exploreRows = previous.exploreRows),
            publicPage,
            refreshing = true,
        )
        assertEquals("同一榜单的下拉刷新才保留旧行", previous.exploreRows, sameFeed.exploreRows)
        assertTrue(sameFeed.exploreRefreshing)
    }

    @Test
    fun staleDetailResponseCannotOverwriteANewerLoadOfTheSameBook() {
        val seed = ExploreBookSeed(id = "42", title = "旧标题")
        val opened = ExploreState.beginDetail(StudioUiState(), "42", seed)
        val first = ExploreState.detailLoading(opened, "42")
        val retried = ExploreState.detailLoading(first, "42")

        assertEquals(3L, retried.exploreDetailRequestId)
        assertSame(
            "第一次请求迟到时不能覆盖重试后的状态",
            retried,
            ExploreState.detailLoaded(retried, "42", first.exploreDetailRequestId, detail("旧结果")),
        )
        assertSame(
            "第一次请求的失败也不能把重试状态改成失败",
            retried,
            ExploreState.detailFailed(retried, "42", first.exploreDetailRequestId, "旧错误"),
        )

        val loaded = ExploreState.detailLoaded(retried, "42", retried.exploreDetailRequestId, detail("新结果"))
        assertFalse(loaded.exploreDetailLoading)
        assertEquals("新结果", loaded.exploreDetail?.book?.title)
    }

    @Test
    fun detailResponseForAnotherBookIsIgnored() {
        val opened = ExploreState.beginDetail(StudioUiState(), "42", ExploreBookSeed(id = "42"))
        val other = ExploreState.beginDetail(opened, "43", ExploreBookSeed(id = "43"))

        assertSame(
            other,
            ExploreState.detailLoaded(other, "42", opened.exploreDetailRequestId, detail("旧书")),
        )
        assertSame(
            other,
            ExploreState.detailFailed(other, "42", opened.exploreDetailRequestId, "旧错误"),
        )
    }

    @Test
    fun publicFetchStopsAfterThreeConsecutiveFailures() {
        assertFalse(StudioViewModel.shouldStopPublicFetch(0))
        assertFalse(StudioViewModel.shouldStopPublicFetch(2))
        assertTrue(StudioViewModel.shouldStopPublicFetch(3))
        assertTrue(StudioViewModel.shouldStopPublicFetch(4))
    }

    private fun detail(title: String) = ExploreBookDetail(
        book = Book(
            id = "42",
            title = title,
            sourceUrl = "https://www.wenku8.net/book/42.htm",
            bookUrl = "https://www.wenku8.net/book/42.htm",
        ),
    )

    private fun book(id: String, title: String = "书 $id") = SearchBook(
        id = id,
        title = title,
        sourceUrl = "https://www.wenku8.net/book/$id.htm",
    )
}

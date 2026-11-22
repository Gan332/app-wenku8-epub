package com.example.hyperreader.ui

import com.example.hyperreader.core.ExploreBooksRow
import com.example.hyperreader.core.ExploreBookDetail
import com.example.hyperreader.core.ExploreBookSeed
import com.example.hyperreader.core.ExplorePage
import com.example.hyperreader.model.SearchBook

/**
 * 探索页的状态迁移（纯函数，便于 JVM 单测）。
 *
 * 页面本身只读 `StudioUiState`；这里把“开始请求 / 丢弃旧响应 / 合并补全 / 标签结果”的规则
 * 从 ViewModel 里拆出来，避免状态规则散落在协程块中。
 */
object ExploreState {
    /**
     * 开始一轮新的探索请求。
     *
     * 请求序号必须**只在状态迁移里**分配：登录/登出会先清掉上一轮状态，随后若在
     * ViewModel 里再自行 `requestId += 1`，刚写入的页面状态会立刻被判定为“旧请求”。
     * 统一从这里分配，任何调用方都不需要维护另一份计数器。
     */
    fun begin(
        state: StudioUiState,
        page: ExplorePage,
        refreshing: Boolean = false,
    ): StudioUiState {
        // 只有刷新**同一个榜单**才保留旧行；切换榜单时即使调用方传了 refreshing，
        // 也必须清空，否则登录/登出后回落默认榜的瞬间会显示上一登录态的内容。
        val keepRows = refreshing && state.activeExplorePage?.id == page.id
        return loading(state, page, requestId = state.exploreRequestId + 1, refreshing = keepRows)
    }

    /** 未登录/无权限进入某个榜单：同样开启新一轮请求，作废仍在途的旧请求。 */
    fun reject(
        state: StudioUiState,
        page: ExplorePage,
        message: String,
    ): StudioUiState = rejected(state, page, message, requestId = state.exploreRequestId + 1)

    /** 请求开始：记录目标榜单与请求序号，清掉上一轮的行与提示。 */
    fun loading(
        state: StudioUiState,
        page: ExplorePage,
        requestId: Long,
        refreshing: Boolean = false,
    ): StudioUiState =
        state.copy(
            activeExplorePage = page,
            exploreBusy = true,
            exploreRefreshing = refreshing,
            // 换榜/重载时上一轮的提示必须清掉：否则旧榜的补全失败提示会挂在新榜下面。
            exploreMessage = null,
            exploreLoadedOnce = true,
            // 下拉刷新保留当前榜单，只显示顶部转圈；切换榜单（refreshing=false）
            // 才清空旧行，避免刷新时整屏闪成骨架。
            exploreRows = if (refreshing) state.exploreRows else emptyList(),
            exploreRequestId = requestId,
        )

    /**
     * 未登录/无权限进入某个榜单：同样推进请求序号，作废仍在途的旧请求。
     *
     * 不能复用旧序号写失败态：否则旧请求返回后会把「需要登录」的页面覆盖回
     * 上一个榜单的内容。
     */
    fun rejected(state: StudioUiState, page: ExplorePage, message: String, requestId: Long): StudioUiState =
        state.copy(
            activeExplorePage = page,
            exploreBusy = false,
            exploreRefreshing = false,
            exploreLoadedOnce = true,
            exploreMessage = message,
            exploreRows = emptyList(),
            exploreRequestId = requestId,
        )

    /**
     * 初始卡片返回：落成榜单行。
     *
     * [busy] 表示后面还要逐本补全；此时即使暂时没有卡片也要保持加载态，
     * 否则会先闪一帧空态。补全全部结束后调用 [completed] 收尾。
     */
    fun rows(
        state: StudioUiState,
        page: ExplorePage,
        books: List<SearchBook>,
        requestId: Long,
        busy: Boolean = false,
    ): StudioUiState {
        if (!isCurrent(requestId, state.exploreRequestId)) return state
        return state.copy(
            // 登录/登出会先把 exploreLoadedOnce 清掉再触发当前页重载；请求一旦返回，
            // 这里必须重新置位，否则切走再回探索页会再抓一次并覆盖本次结果。
            exploreLoadedOnce = true,
            exploreBusy = busy,
            // 首次页面请求结束后就停掉下拉刷新指示器；逐本补全在后台继续，
            // 否则慢速补全会让列表顶端的转圈一直挂着，像是刷新没有结束。
            exploreRefreshing = false,
            exploreRows = if (books.isEmpty()) emptyList() else listOf(ExploreBooksRow(page.title, books, page.id)),
        )
    }

    fun failed(
        state: StudioUiState,
        message: String,
        requestId: Long,
    ): StudioUiState {
        if (!isCurrent(requestId, state.exploreRequestId)) return state
        return state.copy(
            exploreBusy = false,
            exploreRefreshing = false,
            exploreMessage = message,
            exploreLoadedOnce = true,
        )
    }

    fun completed(
        state: StudioUiState,
        requestId: Long,
        warning: String? = null,
    ): StudioUiState {
        if (!isCurrent(requestId, state.exploreRequestId)) return state
        return state.copy(
            exploreBusy = false,
            exploreRefreshing = false,
            exploreMessage = warning,
            exploreLoadedOnce = true,
        )
    }

    /**
     * 逐本补全进度。
     *
     * [processed] 是“已处理”而不是“已成功”：失败的书也占用了一次请求，若只累计成功数，
     * 首次失败会长期显示 `0/N`，看起来像卡住。失败数另行标注，不把失败伪装成完成。
     */
    fun progress(
        state: StudioUiState,
        processed: Int,
        total: Int,
        requestId: Long,
        failures: Int = 0,
    ): StudioUiState {
        if (!isCurrent(requestId, state.exploreRequestId)) return state
        val suffix = if (failures > 0) "（失败 $failures）" else ""
        return state.copy(exploreMessage = "正在补全 $processed/$total$suffix…")
    }

    /**
     * 补全结束时的非阻断提示。
     *
     * 三种情况分别说明：本轮全部失败且一张卡片都没有、部分失败、因单轮上限还有未显示的书。
     * 没有这些差异时返回 null，避免每次进入探索页都挂一条常驻提示。
     */
    fun completionWarning(total: Int, shown: Int, attempted: Int, failures: Int): String? = when {
        attempted > 0 && failures == attempted && shown == 0 -> "公开书目补全失败：可尝试浏览器验证或稍后刷新。"
        failures > 0 && shown < total -> "$failures 本公开书目补全失败；已显示 $shown/$total 本，刷新可继续。"
        failures > 0 -> "$failures 本公开书目补全失败，可稍后刷新重试。"
        shown < total -> "已显示 $shown/$total 本，刷新可继续补全。"
        else -> null
    }

    /**
     * 公开榜没有任何可展示卡片时的收尾提示。
     *
     * 榜单有 ID、但本地索引与补全都没有卡片，不等于「榜单没有内容」：
     * 通常是匿名链路被拦截，或单个详情页解析失败。提示必须可操作，不能把请求失败
     * 伪装成空榜。
     */
    fun emptyPublicResult(total: Int, attempted: Int, failures: Int): String {
        if (total <= 0) return "该榜单暂时没有内容。"
        if (attempted == 0 && failures == 0) return "该榜单暂时没有可显示的书目，刷新可重试。"
        return completionWarning(total, shown = 0, attempted = attempted, failures = failures)
            ?: "该榜单暂时没有可显示的书目，刷新可重试。"
    }

    /** 标签选择：本地结果立即展示；服务端结果只在仍选中同一标签时覆盖。 */
    fun selectTag(
        state: StudioUiState,
        tag: String,
        local: List<SearchBook>,
        loggedIn: Boolean,
        awaitingServer: Boolean = false,
    ): StudioUiState = state.copy(
        activeTag = tag,
        tagResults = local,
        tagMessage = null,
        // 未登录时服务端请求根本不会发出：必须停掉 busy，否则标签区会永久
        // 显示“正在从站点补齐…”，而本地结果为空时连空态文案都看不到。
        tagBusy = loggedIn && awaitingServer,
    )

    fun mergeTag(state: StudioUiState, tag: String, server: List<SearchBook>, local: List<SearchBook>): StudioUiState {
        if (state.activeTag != tag) return state
        // 服务端结果在前、本地索引补充在后；两边各自去重，避免同 ID 出现两行。
        val merged = linkedMapOf<String, SearchBook>()
        server.forEach { merged.putIfAbsent(it.id, it) }
        local.forEach { merged.putIfAbsent(it.id, it) }
        return state.copy(
            tagResults = merged.values.toList(),
            tagMessage = null,
            tagBusy = false,
        )
    }

    fun tagFailed(state: StudioUiState, tag: String, message: String): StudioUiState {
        if (state.activeTag != tag) return state
        return state.copy(tagMessage = message, tagBusy = false)
    }

    fun clearTag(state: StudioUiState): StudioUiState =
        state.copy(activeTag = null, tagResults = emptyList(), tagMessage = null, tagBusy = false)

    /**
     * 登录成功：作废匿名公开榜的旧响应，下一次进入探索页从登录后的默认榜单开始。
     *
     * 四类内容必须一起清：榜单行、展开页目标、标签结果与标签请求态。只改 `loggedIn`
     * 会让用户继续看到登录前拉到的公开榜，而 `exploreLoadedOnce` 又阻止重新加载。
     */
    fun signedIn(state: StudioUiState, exploreTags: List<String>, requestId: Long): StudioUiState =
        state.copy(
            loggedIn = true,
            exploreTags = exploreTags,
            exploreRequestId = requestId,
            exploreExpanded = false,
            exploreRows = emptyList(),
            exploreBusy = false,
            exploreRefreshing = false,
            exploreMessage = null,
            exploreLoadedOnce = false,
            activeExplorePage = null,
            activeTag = null,
            tagResults = emptyList(),
            tagMessage = null,
            tagBusy = false,
        )

    /** 登出/会话失效：登录墙内容、官方标签与标签补齐结果全部不可再用。 */
    fun signedOut(state: StudioUiState, exploreTags: List<String>, requestId: Long): StudioUiState =
        state.copy(
            loggedIn = false,
            exploreTags = exploreTags,
            exploreRequestId = requestId,
            exploreExpanded = false,
            activeExplorePage = null,
            exploreRows = emptyList(),
            exploreBusy = false,
            exploreRefreshing = false,
            exploreMessage = null,
            exploreLoadedOnce = false,
            activeTag = null,
            tagResults = emptyList(),
            tagMessage = null,
            tagBusy = false,
        )

    fun isCurrent(requestId: Long, current: Long): Boolean = requestId == current

    /**
     * 打开书籍详情并开启新一轮请求。
     *
     * 详情与榜单各有一套序号：同一本书的「重试」可能同时存在两个在途请求，
     * 只比较 bookId 无法丢弃先发后至的旧响应。
     */
    fun beginDetail(state: StudioUiState, bookId: String, seed: ExploreBookSeed?): StudioUiState =
        state.copy(
            exploreDetailId = bookId,
            exploreDetail = null,
            exploreDetailLoading = true,
            exploreDetailError = null,
            exploreDetailSeed = seed,
            exploreDetailRequestId = state.exploreDetailRequestId + 1,
        )

    /** 详情请求开始（重试也走这里）：只有仍在同一本书上时才推进序号。 */
    fun detailLoading(state: StudioUiState, bookId: String): StudioUiState =
        if (state.exploreDetailId != bookId) {
            state
        } else {
            state.copy(
                exploreDetailLoading = true,
                exploreDetailError = null,
                exploreDetailRequestId = state.exploreDetailRequestId + 1,
            )
        }

    fun detailLoaded(
        state: StudioUiState,
        bookId: String,
        requestId: Long,
        detail: ExploreBookDetail,
    ): StudioUiState {
        if (!isCurrentDetail(state, bookId, requestId)) return state
        return state.copy(
            exploreDetailLoading = false,
            exploreDetail = detail,
            exploreDetailError = detail.indexError,
        )
    }

    fun detailFailed(
        state: StudioUiState,
        bookId: String,
        requestId: Long,
        message: String,
    ): StudioUiState {
        if (!isCurrentDetail(state, bookId, requestId)) return state
        return state.copy(exploreDetailLoading = false, exploreDetailError = message)
    }

    fun isCurrentDetail(state: StudioUiState, bookId: String, requestId: Long): Boolean =
        state.exploreDetailId == bookId && state.exploreDetailRequestId == requestId
}

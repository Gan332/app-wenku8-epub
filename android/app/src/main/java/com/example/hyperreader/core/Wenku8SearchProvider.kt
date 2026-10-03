package com.example.hyperreader.core

import com.example.hyperreader.model.SearchField

/**
 * 站内搜索（`modules/article/search.php`）——**登录墙内接口**，见 AGENTS §4.2 / §4.5.3。
 *
 * 只携带 [SessionGate] 里**用户本人**的会话 Cookie；未登录直接拒绝，不做任何匿名规避。
 * 会话失效（返回登录页）时清会话并抛出 `AUTH_REQUIRED`，由 UI 提示重新登录。
 *
 * 节流走交互档（[Wenku8HttpClient.fetchTextInteractive]）：搜索是用户按下的动作，
 * 与探索详情同一口径；白名单、内网拦截、429 退避、重定向复校验完全不变。
 */
class Wenku8SearchProvider(
    private val http: Wenku8HttpClient,
    private val sessionStore: SessionGate,
) {
    fun isLoggedIn(): Boolean = sessionStore.hasSession()

    /**
     * 拉取第 [page] 页（从 1 开始）的搜索结果。
     *
     * [Wenku8Urls.search] 把页码放进 `page=` 参数，`em#pagestats` 只用来推断总页数；
     * 关键词为空时短路返回空页，不打网络。
     */
    suspend fun search(keyword: String, field: SearchField, page: Int = 1): Wenku8Parser.SearchPageData {
        require(page >= 1) { "搜索页码必须从 1 开始。" }
        val value = keyword.trim()
        if (value.isEmpty()) return Wenku8Parser.SearchPageData(emptyList(), page, page, false)
        if (!sessionStore.hasSession()) throw Wenku8Exception("请先登录轻小说文库后搜索。", "AUTH_REQUIRED")
        val response = http.fetchTextInteractive(Wenku8Urls.search(value, field, page), "search", Wenku8Urls.BASE)
        if (Wenku8Parser.looksLikeLoginPage(response.html)) {
            sessionStore.clear()
            throw Wenku8Exception("搜索登录已过期，请重新登录。", "AUTH_REQUIRED")
        }
        return Wenku8Parser.parseSearchPage(response.html, response.finalUrl, page)
    }
}
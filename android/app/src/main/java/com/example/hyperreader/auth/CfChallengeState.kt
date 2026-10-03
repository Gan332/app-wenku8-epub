package com.example.hyperreader.auth

/**
 * Cloudflare 验证是否已通过的判定（0.18.0）。
 *
 * 抽成纯函数便于单测，也避免「什么算验证完成」散在 WebView 回调里：
 * 1. **拿到 `cf_clearance` 即通过**——这是 Cloudflare 写给浏览器的那枚通过凭证，
 *    由 WebView 的 CookieManager 落盘，后续 OkHttp 请求自动携带（见
 *    `Wenku8SessionStore.cookieJar`）；
 * 2. 其次看页面标题不再含挑战特征（`Just a moment…` / `Attention Required` / `Checking your browser`），
 *    用于某些不发 clearance、只做一次确认的软挑战；
 * 3. 以上都不满足即视为「仍在验证中」。
 *
 * 合规边界：这里**只判定状态，不代替用户完成交互**——验证码/验证由用户在 WebView 里
 * 亲手完成，我们不注入脚本、不伪造 token（AGENTS §4.2）。
 */
object CfChallengeState {
    const val CLEARANCE_COOKIE = "cf_clearance"

    private val TITLE_MARKERS = listOf(
        "just a moment",
        "attention required",
        "checking your browser",
        "请稍候",
        "验证",
    )

    /** [title] 为页面标题（可空），[hasClearance] 表示 Cookie 里已有 clearance。 */
    fun isSatisfied(title: String?, hasClearance: Boolean): Boolean {
        if (hasClearance) return true
        val value = title?.trim().orEmpty()
        if (value.isEmpty()) return false
        return TITLE_MARKERS.none { marker -> value.contains(marker, ignoreCase = true) }
    }
}
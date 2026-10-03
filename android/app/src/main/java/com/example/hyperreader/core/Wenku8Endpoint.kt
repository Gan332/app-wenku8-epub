package com.example.hyperreader.core

import java.net.URI

/**
 * 公开页的传输端点：直连源站，或用户自填的**第三方中继**（AGENTS §4.11）。
 *
 * 中继只服务「免登录公开页」这条链路（书目索引、公开榜单、封面图）：
 * - [Wenku8Urls] 的公开端点用 [publicBase] 拼地址；会话端点（搜索/榜单/标签/登录）一律用
 *   直连 [Wenku8Urls.BASE]，**不携带任何 Cookie**
 * - 端点集合、白名单、内网拦截、限流与 429 退避都不因中继而放宽
 * - 解析结果在出口用 [restoreToDirect] 还原成 wenku8 原域，书架 / EPUB 内链不会指向第三方；
 *   真正下载前再用 [toRelayUrl] 改写
 *
 * 导出 / 在线阅读链路与公开链路共用同一批 URL 工厂，因此开启后它们的 `articleinfo.php`
 * 与目录页也可能走中继；会话 Cookie 由 [Wenku8Url.carriesSession] 按 host 把关，
 * 中继**永远收不到** `jieqiUserInfo` / `PHPSESSID`。
 *
 * 端点必须由用户显式填写：MewX 官方 App 的中继地址从未公开（1.x 已把相关常量移除，
 * `wenku8.mewx.org` 只是其前端页），内置任何猜测地址都不可取。
 */
object Wenku8Endpoint {
    /** 直连源站，与 [Wenku8Urls.BASE] 同源。 */
    const val DIRECT_BASE = "https://www.wenku8.net"

    /**
     * 直连候选边缘入口（按 403 轮换；对照 LNR `Wenku8Api.hosts`）。
     *
     * 起始用 `wenku8.net`；遇到 Cloudflare 拦截时 [rotateHost] 依次换到 `.cc`、`.com`——
     * 换的是**边缘入口**而非接口语义，白名单与合规边界不变。
     */
    val DIRECT_HOSTS: List<String> = listOf(DIRECT_BASE, "https://www.wenku8.cc", "https://www.wenku8.com")

    @Volatile
    private var hostIndex: Int = 0

    /** 本会话已试过的直连入口；全部试过则不再轮换（避免把三个域都打进风控）。 */
    private val triedHosts = LinkedHashSet<Int>()

    /** 公开页基址：中继优先（若启用），否则当前直连入口。 */
    fun publicBase(): String = relayBase() ?: DIRECT_HOSTS[hostIndex]

    /** 当前使用的直连入口（设置页展示用）。 */
    fun activeDirectBase(): String = DIRECT_HOSTS[hostIndex]

    /**
     * 换一个直连边缘入口重试；返回 false 表示已经没有未试过的入口。
     * 中继生效时不做轮换（中继端点由用户自己决定）。
     */
    fun rotateHost(): Boolean {
        if (isRelayActive()) return false
        for (offset in 1..DIRECT_HOSTS.size) {
            val candidate = (hostIndex + offset) % DIRECT_HOSTS.size
            if (triedHosts.add(candidate)) {
                hostIndex = candidate
                return true
            }
        }
        return false
    }

    /** 把任意 wenku8 URL 的 scheme+host 换成当前入口（路径与查询保留）。 */
    fun rewire(url: String): String = url.replaceFirst(HOST_PREFIX, publicBase())

    /** 恢复默认入口（App 启动时调用，避免上一次会话的轮换结果影响本次）。 */
    fun resetHosts() {
        hostIndex = 0
        triedHosts.clear()
    }

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var relay: String? = null

    /** 中继是否**实际生效**：开关打开且端点合法。设置页据此禁用开关或提示原因。 */
    fun isRelayActive(): Boolean = enabled && relay != null

    fun isRelayEnabled(): Boolean = enabled

    /** 当前中继 base（未配置返回 null）。 */
    fun relayBase(): String? = relay

    /** 中继 host（未配置返回 null），供 URL 白名单放行。 */
    fun relayHost(): String? = relay?.let { value -> runCatching { URI(value).host }.getOrNull() }

    /**
     * 应用中继端点（可重复调用）。合法则生效，非法/空则**清空**中继（回落到直连）。
     *
     * 返回是否生效，便于设置页直接提示原因。
     */
    fun applyRelay(raw: String?): Boolean {
        val normalized = normalizeRelayBase(raw)
        relay = normalized
        return normalized != null
    }

    /** 设置开关；关闭时保留端点以便再次开启。 */
    fun setRelayEnabled(value: Boolean) {
        enabled = value
    }

    /** 与 `relay/worker.js` 的 `ALLOWED` / `DENIED` 保持一致的路径正则（路径契约，由单测锁定）。 */
    private val RELAY_ALLOWED = listOf(
        Regex("^/modules/article/articleinfo\\.php$"),
        Regex("^/modules/article/authorarticle\\.php$"),
        Regex("^/novel/\\d+/\\d+/index\\.html?$"),
        Regex("^/zt/sugoi/\\d{4}\\.php$"),
        Regex("^/zt/booklist/\\d{6}\\.php$"),
    )

    private val RELAY_DENIED = listOf(
        Regex("^/login\\.php$"),
        Regex("^/modules/article/(search|toplist|tags|articlelist)\\.php$"),
        Regex("^/api/"),
    )

    /**
     * 该路径是否允许经中继转发。
     *
     * 与 Worker 端是同一份契约：登录墙内接口在这里先被拒，即便 Worker 配置写错，
     * App 也不会把会话类请求发给第三方。单测 `relayPathContractMatchesWorkerAllowList` 锁定。
     */
    fun isRelayablePath(path: String?): Boolean {
        val value = path.orEmpty()
        if (value.isBlank()) return false
        if (RELAY_DENIED.any { it.matches(value) }) return false
        return RELAY_ALLOWED.any { it.matches(value) }
    }

    /** 规范化中继端点：`https://host[:port]`，去掉路径/查询/尾斜杠；非法返回 null。 */
    fun normalizeRelayBase(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.userInfo != null) return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        if (host.toIpv4OrNull() != null || host == "::1" || host.startsWith("[")) return null
        if (Wenku8Url.isDirectHost(host)) return null
        val port = uri.port.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
        return "https://$host$port"
    }

    /**
     * 直连 URL → 中继 URL（同路径同查询的反代形式）。
     *
     * 只改写 wenku8 自身域名；其它 URL（已是中继、或第三方图片域）原样返回。
     */
    fun toRelayUrl(url: String): String {
        val base = relayBase() ?: return url
        if (!isRelayActive()) return url
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return url
        val host = uri.host?.lowercase() ?: return url
        if (!Wenku8Url.isDirectHost(host)) return url
        val suffix = buildString {
            append(uri.rawPath.ifEmpty { "/" })
            uri.rawQuery?.let { append('?').append(it) }
        }
        return base + suffix
    }

    /**
     * 中继 URL → 直连 URL，解析出口统一调用，保证书架 / EPUB 内链不落到第三方。
     *
     * 与 [toRelayUrl] 互为逆运算；非中继 URL 原样返回。
     */
    fun restoreToDirect(url: String): String {
        val host = relayHost() ?: return url
        val prefix = "https://$host"
        val trimmed = url.trim()
        if (!trimmed.startsWith(prefix)) return trimmed
        val suffix = trimmed.removePrefix(prefix)
        if (suffix.isNotEmpty() && !suffix.startsWith('/')) return trimmed
        return Wenku8Urls.BASE + suffix
    }
}

/** 匹配 URL 的 scheme+host 前缀，用于换边缘入口。 */
private val HOST_PREFIX = Regex("^https://[^/]+")

/** 只含数字与点的字面量就是 IPv4；含其它字符则交给 URI 解析。 */
private fun String.toIpv4OrNull(): String? = split('.')
    .takeIf { parts ->
        parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all(Char::isDigit) && part.toInt() in 0..255 }
    }
    ?.joinToString(".")
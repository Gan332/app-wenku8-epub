package com.example.hyperreader.core

import android.content.Context
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * 会话门：会话链路（`search.php` / `toplist.php` / `tags.php`）需要的最小能力。
 *
 * 抽成接口是为了让 [Wenku8SearchProvider]、[Wenku8DataSource] 在单测里能注入假实现——
 * [Wenku8SessionStore] 依赖 Context + Keystore + `EncryptedSharedPreferences`，
 * JVM 单测（无 Android runtime）里构造它会直接抛 `Stub!`。
 *
 * 只表达「有没有用户自己的会话」与「会话失效时清掉」，**不含任何绕过登录的能力**。
 */
interface SessionGate {
    fun hasSession(): Boolean
    fun clear()
}

/** 用户**本人**的 wenku8 会话 Cookie（Keystore 加密存储），实现 [SessionGate]。 */
class Wenku8SessionStore(context: Context) : SessionGate {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun hasSession(): Boolean = !allCookies()["jieqiUserInfo"].isNullOrBlank()

    fun allCookies(): Map<String, String> {
        val raw = preferences.getString(COOKIE_KEY, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.optString(it) }
        }.getOrDefault(emptyMap())
    }

    fun saveWebViewCookies(cookieHeader: String?, host: String? = null) {
        val values = allCookies().toMutableMap()
        cookieHeader.orEmpty().split(';').mapNotNull { part ->
            val item = part.trim()
            if (!item.contains('=')) return@mapNotNull null
            val name = item.substringBefore('=').trim()
            val value = item.substringAfter('=').trim()
            if (name.isBlank()) null else name to value
        }.toMap().forEach { (name, value) ->
            if (name == CLEARANCE_COOKIE && host != null) {
                // clearance 与 host 绑定：同样一枚 token 换到别的镜像上会被 Cloudflare 判无效，
                // 必须按 host 分开存，不能再共用同一个扁平键。
                values[clearanceKey(host)] = value
                if (host.equals(LEGACY_CLEARANCE_HOST, ignoreCase = true)) values[CLEARANCE_COOKIE] = value
            } else {
                values[name] = value
            }
        }
        preferences.edit().putString(COOKIE_KEY, jsonObject(values).toString()).apply()
    }

    /**
     * 把 WebView 里的 Cookie 收进加密存储。
     *
     * [url] 是实际完成验证的页面地址（可能是轮换后的 .cc），**不能固定只读 .net**：
     * 否则在 .cc 上拿到的 `cf_clearance` 永远存不下来，而 .net 的旧 token 又被回放到 .cc，
     * 表现为「验证页正常、正常抓取仍被拦」。两个地址都读（验证页所在入口在后，覆盖同名的会话 Cookie），
     * clearance 按 host 分开存。
     */
    fun saveWebViewSession(url: String = Wenku8Urls.BASE) {
        val manager = CookieManager.getInstance()
        manager.flush()
        val targets = if (url == Wenku8Urls.BASE) listOf(url) else listOf(Wenku8Urls.BASE, url)
        targets.forEach { target ->
            saveWebViewCookies(manager.getCookie(target), target.toHttpUrlOrNull()?.host)
        }
    }

    override fun clear() {
        preferences.edit().remove(COOKIE_KEY).apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    /** 当前是否已通过 Cloudflare 人机验证（任一枚按 host 保存的凭证或旧版单键都算）。 */
    fun hasClearance(): Boolean = hasClearance(allCookies())

    /** 该 host 是否有自己的 clearance（不兼容旧版 .net 单键，用于判断该钉哪个入口）。 */
    fun hasClearanceFor(host: String): Boolean = allCookies()[clearanceKey(host)]?.isNotBlank() == true

    internal fun saveClearanceFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val clearance = cookies.firstOrNull { it.name == CLEARANCE_COOKIE && Wenku8Url.carriesSession(url.host) }
            ?: return
        val values = allCookies().toMutableMap()
        values[clearanceKey(url.host)] = clearance.value
        if (url.host.equals(LEGACY_CLEARANCE_HOST, ignoreCase = true)) values[CLEARANCE_COOKIE] = clearance.value
        preferences.edit().putString(COOKIE_KEY, jsonObject(values).toString()).apply()
    }

    internal fun loadClearanceCookies(url: HttpUrl): List<Cookie> {
        if (!Wenku8Url.carriesSession(url.host)) return emptyList()
        val value = clearanceValue(allCookies(), url.host) ?: return emptyList()
        return listOfNotNull(Cookie.parse(url, "$CLEARANCE_COOKIE=$value"))
    }

    /** 只删某一个 host 的凭证（带该 token 反而被 403 时用）。 */
    fun removeClearanceFor(host: String) {
        val current = allCookies()
        val key = clearanceKey(host)
        val renameLegacy = host.equals(LEGACY_CLEARANCE_HOST, ignoreCase = true)
        val remaining = current.filterKeys { it != key && !(renameLegacy && it == CLEARANCE_COOKIE) }
        if (remaining.size == current.size) return
        preferences.edit().putString(COOKIE_KEY, jsonObject(remaining).toString()).apply()
    }

    /** 当前存有凭证的 host 列表（诊断用）。 */
    fun clearanceHosts(): List<String> = allCookies().keys
        .filter { it.startsWith(CLEARANCE_PREFIX) }
        .map { it.removePrefix(CLEARANCE_PREFIX) }

    /**
     * 清除所有 host 的 `cf_clearance`（含旧版单键）。
     *
     * 凭证会过期，也会因为 UA / 出口 IP 变化被 Cloudflare 判为无效；此时**带着旧 token**
     * 反而可能被硬拦（403 Attention Required），而不带 token 的请求是 200。
     * 所以「验证过仍被拦」时先清掉再试，而不是反复打开验证页（页面正常时不会重发 token）。
     */
    fun removeClearance() {
        val current = allCookies()
        val remaining = current.filterKeys { it != CLEARANCE_COOKIE && !it.startsWith(CLEARANCE_PREFIX) }
        if (remaining.size == current.size) return
        preferences.edit().putString(COOKIE_KEY, jsonObject(remaining).toString()).apply()
    }

    /**
     * 只携带 `cf_clearance` 的 CookieJar（0.18.0）。
     *
     * 给**免登录公开链路**用：验证窗口完成后，Cloudflare 下的 clearance 必须随请求回传，
     * 否则公开页永远是 403（我们实测过）。它与 [cookieJar] 的区别是**只给这一枚**——
     * `jieqiUserInfo` / `PHPSESSID` 绝不外泄，符合 AGENTS §4.2「免登录链路不得携带会话」。
     */
    fun clearanceCookieJar(): ClearanceCookieJar = ClearanceCookieJar(this)

    fun cookieJar(): CookieJar = object : CookieJar {
        /**
         * 会话 Cookie **只发给 wenku8 自身域名**。
         *
         * [Wenku8Url.carriesSession] 是这一层的唯一判据：启用第三方中继后，
         * 公开页会请求中继 host，而这里的过滤保证 `jieqiUserInfo` / `PHPSESSID`
         * 永远不会被第三方看到（存与取两侧同时把关，避免中继发来的 Set-Cookie 落盘）。
         */
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (!Wenku8Url.carriesSession(url.host)) return
            val values = allCookies().toMutableMap()
            cookies.forEach { cookie ->
                if (cookie.name == CLEARANCE_COOKIE) {
                    values[clearanceKey(url.host)] = cookie.value
                    if (url.host.equals(LEGACY_CLEARANCE_HOST, ignoreCase = true)) values[CLEARANCE_COOKIE] = cookie.value
                } else if (cookie.persistent || cookie.name == "PHPSESSID" || cookie.name == "jieqiUserInfo") {
                    values[cookie.name] = cookie.value
                }
            }
            preferences.edit().putString(COOKIE_KEY, jsonObject(values).toString()).apply()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            if (!Wenku8Url.carriesSession(url.host)) return emptyList()
            val cookies = allCookies()
            val clearance = clearanceValue(cookies, url.host)
            return cookies.mapNotNull { (name, value) ->
                if (name == CLEARANCE_COOKIE || name.startsWith(CLEARANCE_PREFIX)) return@mapNotNull null
                Cookie.parse(url, "$name=$value")
            } + listOfNotNull(clearance?.let { Cookie.parse(url, "$CLEARANCE_COOKIE=$it") })
        }
    }

    private fun jsonObject(values: Map<String, String>): JSONObject = JSONObject().apply { values.forEach { (key, value) -> put(key, value) } }

    /**
     * 内部常量与方法：internal 让同模块单测能直接断言 [clearanceValue]，
     * 而 [FILE_NAME]/[COOKIE_KEY] 仍保持 private（它们只是加密存储的文件名与键名，不是密钥本身）。
     */
    internal companion object {
        private const val FILE_NAME = "wenku8_session"
        private const val COOKIE_KEY = "cookies"

        /** 旧版本把 clearance 存成不带 host 的单键；只有 .net 请求还能安全复用它。 */
        private const val LEGACY_CLEARANCE_HOST = "www.wenku8.net"

        /** 人机验证通过凭证（Cloudflare 写入）；不是账号凭据，但和会话 Cookie 存于同一处。 */
        const val CLEARANCE_COOKIE = "cf_clearance"

        /** 按 host 存放 clearance 的键前缀；带 `@` 的键不会被当作普通 Cookie 发送。 */
        const val CLEARANCE_PREFIX = "$CLEARANCE_COOKIE@"

        fun clearanceKey(host: String): String = "$CLEARANCE_PREFIX${host.lowercase()}"

        /** 从 CookieMap 里取出 [CLEARANCE_COOKIE] 的值（companion 里以便单测直接调用）。 */
        fun clearanceValue(cookies: Map<String, String>): String? =
            cookies[CLEARANCE_COOKIE]?.takeIf { it.isNotBlank() }

        /**
         * 取与请求 host 匹配的 clearance：优先按 host 存的，其次（仅 .net）兼容旧版单键。
         *
         * 跨 host 回放是行不通的：`cf_clearance` 与 host/IP/UA 绑定，.net 的 token 发给 .cc
         * 只会得到 403，所以除 .net 外不做兜底。
         */
        fun clearanceValue(cookies: Map<String, String>, host: String?): String? {
            if (host != null) {
                cookies[clearanceKey(host)]?.takeIf { it.isNotBlank() }?.let { return it }
            }
            return if (host == null || host.equals(LEGACY_CLEARANCE_HOST, ignoreCase = true)) {
                clearanceValue(cookies)
            } else {
                null
            }
        }

        fun hasClearance(cookies: Map<String, String>): Boolean =
            clearanceValue(cookies) != null ||
                cookies.any { (name, value) -> name.startsWith(CLEARANCE_PREFIX) && value.isNotBlank() }
    }
}

/**
 * 只携带 `cf_clearance` 的 CookieJar（0.18.0 起；0.19.0 起按 host 存）。
 *
 * 除存取之外还提供「临时挂起」：Cloudflare 在凭证失效时会硬拦携带旧 token 的请求
 * （403 Attention Required），而不带 token 的请求反而可能 200。客户端在
 * `Wenku8HttpClient` 里 403 后会先挂起该 host 的 token 重试一次，成功即判定 token 有害并删除。
 */
internal class ClearanceCookieJar(private val store: Wenku8SessionStore) : CookieJar {
    private val suspended = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) =
        store.saveClearanceFromResponse(url, cookies)

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (suspended.contains(url.host)) return emptyList()
        return store.loadClearanceCookies(url)
    }

    /** 该 host 有 token 时临时不发送；返回是否真的挂起了。 */
    fun suspendFor(host: String): Boolean {
        if (Wenku8SessionStore.clearanceValue(store.allCookies(), host) == null) return false
        suspended.add(host)
        return true
    }

    fun resumeFor(host: String) {
        suspended.remove(host)
    }

    /** 不带 token 也能过：旧 token 是有害的，删掉。 */
    fun forget(host: String) {
        suspended.remove(host)
        store.removeClearanceFor(host)
    }
}

package com.example.hyperreader.core

import android.content.Context
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
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

    fun saveWebViewCookies(cookieHeader: String?) {
        val values = allCookies().toMutableMap()
        cookieHeader.orEmpty().split(';').mapNotNull { part ->
            val item = part.trim()
            if (!item.contains('=')) return@mapNotNull null
            val name = item.substringBefore('=').trim()
            val value = item.substringAfter('=').trim()
            if (name.isBlank()) null else name to value
        }.toMap().forEach { (name, value) -> values[name] = value }
        preferences.edit().putString(COOKIE_KEY, jsonObject(values).toString()).apply()
    }

    fun saveWebViewSession() {
        saveWebViewCookies(CookieManager.getInstance().getCookie(Wenku8Urls.BASE))
    }

    override fun clear() {
        preferences.edit().remove(COOKIE_KEY).apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    /** 当前是否已通过 Cloudflare 人机验证。 */
    fun hasClearance(): Boolean = clearanceValue(allCookies()) != null

    /**
     * 只携带 `cf_clearance` 的 CookieJar（0.18.0）。
     *
     * 给**免登录公开链路**用：验证窗口完成后，Cloudflare 下的 clearance 必须随请求回传，
     * 否则公开页永远是 403（我们实测过）。它与 [cookieJar] 的区别是**只给这一枚**——
     * `jieqiUserInfo` / `PHPSESSID` 绝不外泄，符合 AGENTS §4.2「免登录链路不得携带会话」。
     */
    fun clearanceCookieJar(): CookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val clearance = cookies.firstOrNull { it.name == CLEARANCE_COOKIE && Wenku8Url.carriesSession(url.host) }
                ?: return
            val values = allCookies().toMutableMap()
            values[CLEARANCE_COOKIE] = clearance.value
            preferences.edit().putString(COOKIE_KEY, jsonObject(values).toString()).apply()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val value = clearanceValue(allCookies()) ?: return emptyList()
            if (!Wenku8Url.carriesSession(url.host)) return emptyList()
            return listOfNotNull(Cookie.parse(url, "$CLEARANCE_COOKIE=$value"))
        }
    }

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
                if (cookie.persistent || cookie.name == "PHPSESSID" || cookie.name == "jieqiUserInfo") values[cookie.name] = cookie.value
            }
            preferences.edit().putString(COOKIE_KEY, jsonObject(values).toString()).apply()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            if (!Wenku8Url.carriesSession(url.host)) return emptyList()
            return allCookies().mapNotNull { (name, value) -> Cookie.parse(url, "$name=$value") }
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

        /** 人机验证通过凭证（Cloudflare 写入）；不是账号凭据，但和会话 Cookie 存于同一处。 */
        const val CLEARANCE_COOKIE = "cf_clearance"

        /** 从 CookieMap 里取出 [CLEARANCE_COOKIE] 的值（companion 里以便单测直接调用）。 */
        fun clearanceValue(cookies: Map<String, String>): String? =
            cookies[CLEARANCE_COOKIE]?.takeIf { it.isNotBlank() }
    }
}

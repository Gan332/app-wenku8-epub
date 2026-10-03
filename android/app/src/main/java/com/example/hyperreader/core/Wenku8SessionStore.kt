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

    private companion object {
        const val FILE_NAME = "wenku8_session"
        const val COOKIE_KEY = "cookies"
    }
}

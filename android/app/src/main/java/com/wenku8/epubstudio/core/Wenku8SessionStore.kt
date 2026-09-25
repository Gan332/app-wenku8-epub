package com.wenku8.epubstudio.core

import android.content.Context
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONObject

class Wenku8SessionStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun hasSession(): Boolean = !allCookies()["jieqiUserInfo"].isNullOrBlank()

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

    fun clear() {
        preferences.edit().remove(COOKIE_KEY).apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    fun cookieJar(): CookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (!url.host.endsWith("wenku8.net")) return
            val values = allCookies().toMutableMap()
            cookies.forEach { cookie ->
                if (cookie.persistent || cookie.name == "PHPSESSID" || cookie.name == "jieqiUserInfo") values[cookie.name] = cookie.value
            }
            preferences.edit().putString(COOKIE_KEY, jsonObject(values).toString()).apply()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            if (!url.host.endsWith("wenku8.net")) return emptyList()
            return allCookies().mapNotNull { (name, value) -> Cookie.parse(url, "$name=$value") }
        }
    }

    private fun jsonObject(values: Map<String, String>): JSONObject = JSONObject().apply { values.forEach { (key, value) -> put(key, value) } }

    private companion object {
        const val FILE_NAME = "wenku8_session"
        const val COOKIE_KEY = "cookies"
    }
}

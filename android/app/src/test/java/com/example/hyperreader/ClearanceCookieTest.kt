package com.example.hyperreader

import com.example.hyperreader.core.Wenku8SessionStore
import com.example.hyperreader.core.Wenku8Url
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cloudflare 验证凭证（`cf_clearance`）取用逻辑的夹具（0.18.0）。
 *
 * 背景：验证窗口拿到 clearance 后，**免登录公开链路**必须能带上它，否则用户验证完了
 * 探索页仍然 403；而它又绝不能顺带捎上 `jieqiUserInfo` / `PHPSESSID`。
 */
class ClearanceCookieTest {

    @Test
    fun clearanceValueIsExtractedAndBlankMeansNone() {
        assertEquals("abc", Wenku8SessionStore.clearanceValue(mapOf("cf_clearance" to "abc")))
        assertNull(Wenku8SessionStore.clearanceValue(mapOf("PHPSESSID" to "x")))
        assertNull(Wenku8SessionStore.clearanceValue(mapOf("cf_clearance" to "  ")))
        assertNull(Wenku8SessionStore.clearanceValue(emptyMap()))
    }

    @Test
    fun clearanceIsScopedToTheHostItWasIssuedFor() {
        // 验证页可能在轮换后的 .cc 上通过；一枚 token 不能跨镜像复用
        val cc = mapOf(Wenku8SessionStore.clearanceKey("www.wenku8.cc") to "cc-token")
        assertEquals("cc-token", Wenku8SessionStore.clearanceValue(cc, "www.wenku8.cc"))
        assertNull(Wenku8SessionStore.clearanceValue(cc, "www.wenku8.net"))
        assertTrue(Wenku8SessionStore.hasClearance(cc))
    }

    @Test
    fun legacyClearanceOnlyAppliesToTheNetEntry() {
        // 旧版本只存过不带 host 的单键；升级后仅 .net 还能复用它
        val legacy = mapOf("cf_clearance" to "legacy-token")
        assertEquals("legacy-token", Wenku8SessionStore.clearanceValue(legacy, "www.wenku8.net"))
        assertNull(Wenku8SessionStore.clearanceValue(legacy, "www.wenku8.cc"))
        assertTrue(Wenku8SessionStore.hasClearance(legacy))
        assertFalse(Wenku8SessionStore.hasClearance(emptyMap()))
    }

    @Test
    fun clearanceKeyIsLowercasedAndPerHost() {
        assertEquals("cf_clearance@www.wenku8.cc", Wenku8SessionStore.clearanceKey("WWW.Wenku8.CC"))
    }

    @Test
    fun scopedTokenDoesNotLeakAcrossEntriesWhenLegacyTokenExists() {
        val mixed = mapOf(
            "cf_clearance" to "legacy-net",
            Wenku8SessionStore.clearanceKey("www.wenku8.cc") to "cc-token",
        )
        assertEquals("legacy-net", Wenku8SessionStore.clearanceValue(mixed, "www.wenku8.net"))
        assertEquals("cc-token", Wenku8SessionStore.clearanceValue(mixed, "www.wenku8.cc"))
        assertNull(Wenku8SessionStore.clearanceValue(mixed, "www.wenku8.com"))
    }

    @Test
    fun clearanceTravelsToWenku8HostsOnly() {
        // 公开链路只向 wenku8 自身域名回传 clearance
        assertTrue(Wenku8Url.carriesSession("www.wenku8.net"))
        assertTrue(Wenku8Url.carriesSession("img.wenku8.com"))
        assertFalse(Wenku8Url.carriesSession("relay.example.com"))
        assertFalse(Wenku8Url.carriesSession(null))
    }
}
package com.example.hyperreader

import com.example.hyperreader.core.AppVersion
import com.example.hyperreader.core.parseAppReleases
import com.example.hyperreader.core.selectLatestAppRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用内更新的纯函数回归基线。
 *
 * 版本比较与 Release 选择不依赖网络/Android，单独钉死可以避免「预发布版被正式版
 * 反向覆盖」「ci-latest 被当成版本号」这类只在发版时暴露的问题。
 */
class AppUpdateTest {

    @Test
    fun prereleaseVersionsCompareNumerically() {
        assertTrue(version("0.19.0-alpha05") < version("0.19.0-alpha06"))
        assertTrue("alpha10 必须大于 alpha9，不能按字符串比较", version("0.19.0-alpha9") < version("0.19.0-alpha10"))
        assertEquals(0, version("0.19.0-alpha5").compareTo(version("0.19.0-alpha05")))
        assertTrue(version("0.19.0-alpha06") < version("0.19.0"))
        assertTrue(version("0.18.9") < version("0.19.0"))
        assertEquals(0, version("0.19").compareTo(version("0.19.0")))
    }

    @Test
    fun versionAcceptsVPrefixAndRejectsNonVersions() {
        assertEquals(version("0.19.0-alpha05"), AppVersion.parse("v0.19.0-alpha05"))
        assertNull(AppVersion.parse("ci-latest"))
        assertNull(AppVersion.parse(""))
    }

    @Test
    fun releaseParsingPrefersReleaseApkAndSkipsDrafts() {
        val releases = parseAppReleases(RELEASES)

        assertEquals(3, releases.size)
        assertEquals("0.19.0-alpha06", releases.first().versionName)
        assertEquals(
            "app-release.apk",
            releases.first().apkUrl.substringAfterLast('/'),
        )
        assertEquals("修复若干问题", releases.first().notes)
        assertTrue("草稿 Release 不应进入更新候选", releases.none { it.versionName == "0.19.0-alpha07" })
    }

    @Test
    fun latestReleaseOnlyOffersStrictlyNewerVersions() {
        val releases = parseAppReleases(RELEASES)

        assertEquals("0.19.0-alpha06", selectLatestAppRelease(releases, "0.19.0-alpha05")?.versionName)
        assertNull("同版本不应提示更新", selectLatestAppRelease(releases, "0.19.0-alpha06"))
        assertNull("正式版不应回退到预发布版", selectLatestAppRelease(releases, "0.19.0"))
        assertEquals(
            "0.19.0-alpha06",
            selectLatestAppRelease(releases, "0.18.0")?.versionName,
        )
    }

    @Test
    fun releaseWithoutApkAssetIsIgnored() {
        val releases = parseAppReleases(
            """
            [
              {
                "tag_name": "v0.20.0",
                "body": "没有 APK",
                "draft": false,
                "assets": []
              }
            ]
            """.trimIndent(),
        )

        assertTrue(releases.isEmpty())
        assertNull(selectLatestAppRelease(releases, "0.19.0"))
    }

    private fun version(raw: String): AppVersion = requireNotNull(AppVersion.parse(raw)) { "无法解析版本 $raw" }

    private companion object {
        val RELEASES = """
            [
              {
                "tag_name": "v0.19.0-alpha07",
                "body": "草稿",
                "draft": true,
                "assets": [
                  {
                    "name": "app-release.apk",
                    "browser_download_url": "https://github.com/Gan332/app-wenku8-epub/releases/download/v0.19.0-alpha07/app-release.apk",
                    "size": 300
                  }
                ]
              },
              {
                "tag_name": "v0.19.0-alpha06",
                "body": "修复若干问题",
                "draft": false,
                "published_at": "2026-11-20T00:00:00Z",
                "assets": [
                  {
                    "name": "app-debug.apk",
                    "browser_download_url": "https://github.com/Gan332/app-wenku8-epub/releases/download/v0.19.0-alpha06/app-debug.apk",
                    "size": 100
                  },
                  {
                    "name": "app-release.apk",
                    "browser_download_url": "https://github.com/Gan332/app-wenku8-epub/releases/download/v0.19.0-alpha06/app-release.apk",
                    "size": 200
                  }
                ]
              },
              {
                "tag_name": "v0.19.0-alpha05",
                "body": "上一版",
                "draft": false,
                "assets": [
                  {
                    "name": "app-release.apk",
                    "browser_download_url": "https://github.com/Gan332/app-wenku8-epub/releases/download/v0.19.0-alpha05/app-release.apk",
                    "size": 200
                  }
                ]
              },
              {
                "tag_name": "ci-latest",
                "body": "滚动构建",
                "draft": false,
                "assets": [
                  {
                    "name": "app-release.apk",
                    "browser_download_url": "https://github.com/Gan332/app-wenku8-epub/releases/download/ci-latest/app-release.apk",
                    "size": 200
                  }
                ]
              }
            ]
        """.trimIndent()
    }
}

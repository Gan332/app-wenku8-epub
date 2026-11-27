package com.example.hyperreader.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.hyperreader.BuildConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 应用自身的更新包。
 *
 * 与应用内「书架更新检查」完全无关：这里只处理 GitHub Release 里的 APK。
 * 更新检查**不经过** [Wenku8HttpClient]，也不属于 wenku8 数据源白名单；
 * 它只访问仓库的 GitHub Release API，且强制 HTTPS。
 */
data class AppRelease(
    val versionName: String,
    val tagName: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val publishedAt: String,
)

/**
 * 可比较的版本号，支持 `0.19.0-alpha05` 这类预发布格式。
 *
 * 规则与 SemVer 保持一致：先比数字核心，正式版大于同核心的预发布版，
 * 预发布标识按「字母前缀 + 尾随数字」比较（alpha05 < alpha06 < alpha10）。
 */
internal data class AppVersion(
    val core: List<Int>,
    val prerelease: String?,
) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        val size = maxOf(core.size, other.core.size)
        for (index in 0 until size) {
            val left = core.getOrElse(index) { 0 }
            val right = other.core.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return when {
            prerelease == null && other.prerelease == null -> 0
            prerelease == null -> 1
            other.prerelease == null -> -1
            else -> comparePrerelease(prerelease, other.prerelease)
        }
    }

    companion object {
        fun parse(raw: String): AppVersion? {
            val value = raw.trim().removePrefix("v").removePrefix("V")
            if (value.isBlank()) return null
            val corePart = value.substringBefore('-').substringBefore('+')
            val prerelease = value.substringAfter('-', "").substringBefore('+').ifBlank { null }
            val core = corePart.split('.').map { it.toIntOrNull() ?: return null }
            if (core.isEmpty()) return null
            return AppVersion(core, prerelease)
        }

        private fun comparePrerelease(left: String, right: String): Int {
            val leftParts = left.split('.', '-')
            val rightParts = right.split('.', '-')
            for (index in 0 until minOf(leftParts.size, rightParts.size)) {
                val compared = compareIdentifier(leftParts[index], rightParts[index])
                if (compared != 0) return compared
            }
            return leftParts.size.compareTo(rightParts.size)
        }

        private fun compareIdentifier(left: String, right: String): Int {
            val leftNumber = left.toLongOrNull()
            val rightNumber = right.toLongOrNull()
            if (leftNumber != null && rightNumber != null) return leftNumber.compareTo(rightNumber)
            // SemVer：数字标识的优先级低于字母标识。
            if (leftNumber != null) return -1
            if (rightNumber != null) return 1
            val (leftPrefix, leftSuffix) = splitTrailingNumber(left)
            val (rightPrefix, rightSuffix) = splitTrailingNumber(right)
            val prefix = leftPrefix.compareTo(rightPrefix)
            return if (prefix != 0) prefix else leftSuffix.compareTo(rightSuffix)
        }

        private fun splitTrailingNumber(value: String): Pair<String, Long> {
            val match = Regex("^(.*?)(\\d*)$").find(value) ?: return value to 0L
            return match.groupValues[1] to (match.groupValues[2].toLongOrNull() ?: 0L)
        }
    }
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    val draft: Boolean = false,
    @SerialName("published_at") val publishedAt: String = "",
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0,
)

private val appUpdateJson = Json {
    ignoreUnknownKeys = true
    // GitHub 的 body/name 等字段可能是显式 null，按默认值处理。
    coerceInputValues = true
}

/** 解析 GitHub Release 列表；没有可用 APK 的条目直接跳过。 */
internal fun parseAppReleases(payload: String): List<AppRelease> {
    val releases = runCatching { appUpdateJson.decodeFromString<List<GitHubRelease>>(payload) }
        .getOrElse { return emptyList() }
    return releases.filterNot { it.draft }.mapNotNull { release ->
        val apk = release.assets
            .filter { it.name.endsWith(".apk", ignoreCase = true) && it.downloadUrl.startsWith("https://") }
            .minByOrNull { assetPriority(it.name) }
            ?: return@mapNotNull null
        val version = release.tagName.trim().removePrefix("v").removePrefix("V").ifBlank { release.name.trim() }
        if (version.isBlank()) return@mapNotNull null
        AppRelease(
            versionName = version,
            tagName = release.tagName,
            notes = release.body.trim(),
            apkUrl = apk.downloadUrl,
            sizeBytes = apk.size,
            publishedAt = release.publishedAt,
        )
    }
}

/** 选出高于当前版本的最新 Release；同版本或更低版本一律忽略。 */
internal fun selectLatestAppRelease(releases: List<AppRelease>, currentVersion: String): AppRelease? {
    val current = AppVersion.parse(currentVersion) ?: return null
    return releases
        .mapNotNull { release -> AppVersion.parse(release.versionName)?.let { it to release } }
        .filter { (version, _) -> version > current }
        .maxByOrNull { (version, _) -> version }
        ?.second
}

private fun assetPriority(name: String): Int = when {
    name.equals("app-release.apk", ignoreCase = true) -> 0
    name.contains("release", ignoreCase = true) -> 1
    name.contains("debug", ignoreCase = true) -> 3
    else -> 2
}

/**
 * GitHub Release 检查与 APK 下载。
 *
 * 使用独立 OkHttp 客户端，不复用 [Wenku8HttpClient]：后者带全局限流与会话 Cookie，
 * 而更新链路只应访问 GitHub，两者不能混用。
 */
class AppUpdateRepository(context: Context) {
    private val appContext = context.applicationContext
    private val userAgent = "HyperReader/${BuildConfig.VERSION_NAME}"
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    /** 返回比 [currentVersion] 更新的最新 Release；没有则返回 null。 */
    suspend fun check(currentVersion: String): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", userAgent)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("检查更新失败：HTTP ${response.code}")
            val payload = response.body?.string().orEmpty()
            if (payload.isBlank()) throw IOException("检查更新失败：响应为空。")
            selectLatestAppRelease(parseAppReleases(payload), currentVersion)
        }
    }

    /**
     * 下载并校验 APK，返回可直接交给系统安装器的文件。
     *
     * 下载先写 `.part` 临时文件，校验包名与版本通过后才改名，避免半截文件被安装。
     */
    suspend fun download(release: AppRelease, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val url = validateDownloadUrl(release.apkUrl)
        val directory = File(appContext.cacheDir, "app-update").apply { mkdirs() }
        val safeVersion = release.versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(directory, "hyperreader-$safeVersion.apk")
        val temp = File(directory, target.name + ".part")
        temp.delete()
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("下载更新失败：HTTP ${response.code}")
                val body = response.body ?: throw IOException("下载更新失败：响应为空。")
                val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        var lastPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            val percent = if (total > 0) ((copied * 100) / total).toInt().coerceIn(0, 100) else 0
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (temp.length() <= 0L) throw IOException("下载更新失败：文件为空。")
            validateApk(temp)
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            onProgress(100)
            target
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    private fun validateDownloadUrl(raw: String): HttpUrl {
        val url = raw.toHttpUrlOrNull() ?: throw IOException("更新地址无效。")
        if (url.scheme != "https") throw IOException("更新地址必须使用 HTTPS。")
        if (url.host != "github.com" && !url.host.endsWith(".githubusercontent.com")) {
            throw IOException("更新地址不受信任。")
        }
        return url
    }

    private fun validateApk(file: File) {
        val info = appContext.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: throw IOException("下载的安装包无效。")
        if (info.packageName != appContext.packageName) throw IOException("安装包包名不匹配。")
        val candidate = AppVersion.parse(info.versionName.orEmpty())
            ?: throw IOException("安装包版本信息无效。")
        val current = AppVersion.parse(BuildConfig.VERSION_NAME)
            ?: throw IOException("当前版本信息无效。")
        if (candidate <= current) throw IOException("安装包版本不高于当前版本。")
    }

    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/Gan332/app-wenku8-epub/releases?per_page=30"
    }
}

/** 把下载好的 APK 交给系统安装器；Android 8+ 需先允许「安装未知应用」。 */
object AppUpdateInstaller {
    enum class Result { STARTED, NEEDS_PERMISSION, FAILED }

    fun install(context: Context, file: File): Result {
        if (!file.isFile) return Result.FAILED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { context.startActivity(intent); Result.NEEDS_PERMISSION }.getOrDefault(Result.FAILED)
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Result.STARTED
        }.getOrDefault(Result.FAILED)
    }
}

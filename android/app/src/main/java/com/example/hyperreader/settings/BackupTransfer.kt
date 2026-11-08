package com.example.hyperreader.settings

import com.example.hyperreader.model.BookshelfEntry
import com.example.hyperreader.model.ReadingStats
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 用户内容备份（书架 + 阅读数据 + 统计）。
 *
 * 与 [ConfigTransfer] 的分工：
 * - [ConfigTransfer]：环境配置（主题 / 阅读器 / 导出偏好），跨设备迁移设置；
 * - 本文件：**用户内容**，换机、重装、清数据前的兜底。
 *
 * ## 安全边界
 *
 * 只导出以下两类 DataStore 键，白名单由 [isBackupPreferenceKey] 唯一决定：
 * - `reader_progress_*`：章 + 段阅读断点
 * - `xy_reader_*`：阅读器配置、页进度、在线页进度、书签、收藏
 *
 * **绝不包含**登录 Cookie（保存在 Keystore 加密的 `Wenku8SessionStore`，不在此
 * DataStore 内）、密码、Token、EPUB 本体、字体文件、封面缓存。
 *
 * ## 版本
 *
 * 高于 [SCHEMA_VERSION] 的文件整份拒绝，避免未来格式被静默降级。
 */
@Serializable
data class BackupDocument(
    val schemaVersion: Int = BackupTransfer.SCHEMA_VERSION,
    val appVersion: String = "",
    val exportedAt: Long = 0L,
    val bookshelf: List<BookshelfEntry> = emptyList(),
    /** 白名单内的原始 DataStore 字符串值（键 → JSON / 文本）。 */
    val preferences: Map<String, String> = emptyMap(),
    val stats: ReadingStats? = null,
)

object BackupTransfer {

    const val SCHEMA_VERSION = 1

    private val encodeJson = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val decodeJson = Json { ignoreUnknownKeys = true }

    /** 允许进入备份的 DataStore 键前缀（唯一判据，读写两侧都用它）。 */
    private val ALLOWED_PREFIXES = listOf("reader_progress_", "xy_reader_")

    fun isBackupPreferenceKey(key: String): Boolean =
        ALLOWED_PREFIXES.any { key.startsWith(it) }

    fun documentOf(
        bookshelf: List<BookshelfEntry>,
        preferences: Map<String, String>,
        stats: ReadingStats?,
        appVersion: String,
        exportedAt: Long,
    ): BackupDocument = BackupDocument(
        schemaVersion = SCHEMA_VERSION,
        appVersion = appVersion,
        exportedAt = exportedAt,
        bookshelf = bookshelf,
        // 写入前再过滤一次：调用方即使传了更大的 map，也不会把凭据类键带出去。
        preferences = preferences.filterKeys(::isBackupPreferenceKey),
        stats = stats,
    )

    fun encode(document: BackupDocument): String =
        encodeJson.encodeToString(BackupDocument.serializer(), document)

    sealed interface DecodeResult {
        data class Success(val document: BackupDocument) : DecodeResult
        data class Rejected(val reason: String) : DecodeResult
    }

    fun decode(text: String): DecodeResult {
        if (text.isBlank()) return DecodeResult.Rejected("备份文件为空。")
        val document = runCatching {
            decodeJson.decodeFromString(BackupDocument.serializer(), text)
        }.getOrElse { error ->
            return DecodeResult.Rejected("备份文件解析失败：${error.message ?: "格式不正确"}")
        }
        if (document.schemaVersion <= 0) {
            return DecodeResult.Rejected("不是本应用的备份文件。")
        }
        if (document.schemaVersion > SCHEMA_VERSION) {
            return DecodeResult.Rejected(
                "备份版本 ${document.schemaVersion} 高于当前支持的 $SCHEMA_VERSION，请升级应用后再导入。",
            )
        }
        return DecodeResult.Success(
            document.copy(preferences = document.preferences.filterKeys(::isBackupPreferenceKey)),
        )
    }
}

/** 备份文件建议名，例如 `wenku8-backup-20261108-101500.json`。 */
fun backupExportFileName(now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
    val stamp = java.time.Instant.ofEpochMilli(now).atZone(zone)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    return "wenku8-backup-$stamp.json"
}

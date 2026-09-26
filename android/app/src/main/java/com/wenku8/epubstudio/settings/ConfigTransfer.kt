package com.wenku8.epubstudio.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 设置配置的导入导出（schemaVersion 1）。
 *
 * ## 安全边界
 *
 * 导出内容**只有**主题与阅读器设置。以下内容永远不会出现在配置文件里，
 * 并且这一点由类型层面保证——本文件的 DTO 中根本不存在能承载它们的字段：
 *
 * - Wenku8 登录 Cookie（`PHPSESSID` / `jieqiUserInfo` 等，保存在 `Wenku8SessionStore`）
 * - 任何密码、Token、账号标识
 * - 书架条目、阅读记录、阅读统计（属用户内容，走独立的数据迁移流程）
 * - EPUB 文件本体与字体文件本体
 *
 * 主题属于「环境配置」，跨设备迁移合理；书架属于「内容与阅读历史」，
 * 不应混进设置同步。
 *
 * ## 复用范围钳制
 *
 * 数值字段的合法区间与 `SettingsRepository` 的写入钳制保持一致，
 * 因此本文件的钳制结果再写入仓储不会产生二次变化。
 */
object ConfigTransfer {

    /** 当前支持的配置格式版本。未知版本一律拒绝，不做静默降级。 */
    const val SCHEMA_VERSION = 1

    private const val MIN_FONT_WEIGHT = 100
    private const val MAX_FONT_WEIGHT = 900
    private const val MIN_SPACING_DP = 0
    private const val MAX_SPACING_DP = 48
    private const val MIN_PADDING_DP = 0
    private const val MAX_PADDING_DP = 48

    private val encodeJson = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val decodeJson = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    // ---------------------------------------------------------------------
    // 文件模型
    // ---------------------------------------------------------------------

    /**
     * 配置文件根对象。所有字段可选（缺字段 = 不导入该字段），
     * 除 [schemaVersion] 外没有必填项。
     */
    @Serializable
    data class ConfigDocument(
        val schemaVersion: Int = 0,
        val appVersion: String = "",
        val exportedAt: Long = 0L,
        val theme: ConfigTheme? = null,
        val reader: ConfigReader? = null,
    )

    /**
     * 主题字段。枚举以字符串承载，未知取值在导入时按「跳过该字段」处理，
     * 而不是让整份文件解析失败。
     */
    @Serializable
    data class ConfigTheme(
        val mode: String? = null,
        val useDynamicColor: Boolean? = null,
        val accentColor: Int? = null,
    )

    /**
     * 阅读器字段。
     *
     * 刻意**不含** `fontUri`：那是设备本地的 `content://` 授权地址，
     * 跨设备无效且会泄露本机路径。字体文件不走配置同步。
     */
    @Serializable
    data class ConfigReader(
        val fontSizeSp: Float? = null,
        val fontWeight: Int? = null,
        val lineHeight: Float? = null,
        val paragraphSpacingDp: Int? = null,
        val horizontalPaddingDp: Int? = null,
        val background: String? = null,
        val customBackgroundColor: Int? = null,
        val textColor: Int? = null,
        val pageTurnMode: String? = null,
        val keepScreenOn: Boolean? = null,
        val immersiveMode: Boolean? = null,
    )

    /** 导入一条配置后的结果文案，例如「导入成功 14 项，跳过 0 项」。 */
    data class ImportPlan(
        val changes: List<ConfigChange>,
        val skipped: List<String>,
    ) {
        val appliedCount: Int get() = changes.size
        val skippedCount: Int get() = skipped.size
        val isEmpty: Boolean get() = changes.isEmpty()

        fun summary(): String = "导入成功 $appliedCount 项，跳过 $skippedCount 项"

        /** 供 UI 展示的细节：被跳过的字段名及原因。 */
        fun details(): String = if (skipped.isEmpty()) "" else skipped.joinToString("\n") { "· $it" }
    }

    /** 解析结果：成功得到文档，或整体被拒绝并带上原因。 */
    sealed interface DecodeResult {
        data class Success(val document: ConfigDocument) : DecodeResult
        data class Rejected(val reason: String) : DecodeResult
    }

    // ---------------------------------------------------------------------
    // 编码
    // ---------------------------------------------------------------------

    /** 用当前设置构造导出文档（不含任何凭据与用户内容）。 */
    fun documentOf(
        theme: AppThemeSettings,
        reader: ReaderSettings,
        appVersion: String,
        exportedAt: Long,
    ): ConfigDocument = ConfigDocument(
        schemaVersion = SCHEMA_VERSION,
        appVersion = appVersion,
        exportedAt = exportedAt,
        theme = ConfigTheme(
            mode = theme.mode.name,
            useDynamicColor = theme.useDynamicColor,
            accentColor = theme.accentColor,
        ),
        reader = ConfigReader(
            fontSizeSp = reader.fontSizeSp,
            fontWeight = reader.fontWeight,
            lineHeight = reader.lineHeight,
            paragraphSpacingDp = reader.paragraphSpacingDp,
            horizontalPaddingDp = reader.horizontalPaddingDp,
            background = reader.background.name,
            customBackgroundColor = reader.customBackgroundColor,
            textColor = reader.textColor,
            pageTurnMode = reader.pageTurnMode.name,
            keepScreenOn = reader.keepScreenOn,
            immersiveMode = reader.immersiveMode,
            // fontUri 是设备本地地址，不导出
        ),
    )

    /**
     * 编码为格式化 JSON。[exportedAt] 可注入，便于测试。
     * 生产代码用 [System.currentTimeMillis]。
     */
    fun encode(theme: AppThemeSettings, reader: ReaderSettings, appVersion: String, exportedAt: Long): String =
        encodeJson.encodeToString(ConfigDocument.serializer(), documentOf(theme, reader, appVersion, exportedAt))

    /** 直接编码一个已构造好的文档（测试与「导出前预览」用）。 */
    fun encodeDocument(document: ConfigDocument): String =
        encodeJson.encodeToString(ConfigDocument.serializer(), document)

    // ---------------------------------------------------------------------
    // 解码
    // ---------------------------------------------------------------------

    /**
     * 解析配置文件文本。
     *
     * - 非法 JSON、缺 `schemaVersion`、未知 `schemaVersion` 一律 [DecodeResult.Rejected]
     * - 单个字段的非法取值**不会**导致整体失败，交由 [plan] 逐字段跳过
     */
    fun decode(text: String): DecodeResult {
        if (text.isBlank()) return DecodeResult.Rejected("配置文件为空")
        val document = runCatching { decodeJson.decodeFromString(ConfigDocument.serializer(), text) }
            .getOrElse { return DecodeResult.Rejected("不是合法的配置文件（JSON 解析失败：${it.messageOrType()}）") }
        val version = document.schemaVersion
        if (version <= 0) {
            return DecodeResult.Rejected("缺少 schemaVersion，无法确认配置格式")
        }
        if (version != SCHEMA_VERSION) {
            return DecodeResult.Rejected("不支持的配置版本 $version，本应用支持 $SCHEMA_VERSION，请升级应用后重试")
        }
        return DecodeResult.Success(document)
    }

    // ---------------------------------------------------------------------
    // 逐字段校验与导入计划
    // ---------------------------------------------------------------------

    /**
     * 把文档转换为逐字段的导入计划。
     *
     * 数值越界按钳制处理（与 `SettingsRepository` 的区间一致），
     * 枚举未知、颜色非法、NaN 等按跳过处理。缺失字段也计入「跳过」。
     */
    fun plan(document: ConfigDocument): ImportPlan {
        val changes = mutableListOf<ConfigChange>()
        val skipped = mutableListOf<String>()

        val theme = document.theme
        if (theme == null) {
            skipped += "主题设置（缺失）"
        } else {
            planEnum("主题模式", theme.mode, AppThemeMode.entries, changes, skipped) { mode ->
                ConfigChange.SetThemeMode(mode)
            }
            val dynamicColor = theme.useDynamicColor
            if (dynamicColor == null) {
                skipped += "使用动态色（缺失）"
            } else {
                changes += ConfigChange.SetDynamicColor(dynamicColor)
            }
            planColor("强调色", theme.accentColor, changes, skipped) { color ->
                ConfigChange.SetAccentColor(color)
            }
        }

        val reader = document.reader
        if (reader == null) {
            skipped += "阅读器设置（缺失）"
        } else {
            planNumber("字号", reader.fontSizeSp, changes, skipped, ReaderSettings.MIN_FONT_SIZE, ReaderSettings.MAX_FONT_SIZE) { value ->
                ConfigChange.SetReaderFontSize(value)
            }
            planNumber("字重", reader.fontWeight, changes, skipped, MIN_FONT_WEIGHT, MAX_FONT_WEIGHT) { value ->
                ConfigChange.SetReaderFontWeight(value)
            }
            planNumber("行高", reader.lineHeight, changes, skipped, ReaderSettings.MIN_LINE_HEIGHT, ReaderSettings.MAX_LINE_HEIGHT) { value ->
                ConfigChange.SetReaderLineHeight(value)
            }
            planNumber("段距", reader.paragraphSpacingDp, changes, skipped, MIN_SPACING_DP, MAX_SPACING_DP) { value ->
                ConfigChange.SetReaderParagraphSpacing(value)
            }
            planNumber("左右边距", reader.horizontalPaddingDp, changes, skipped, MIN_PADDING_DP, MAX_PADDING_DP) { value ->
                ConfigChange.SetReaderHorizontalPadding(value)
            }
            planEnum("阅读背景", reader.background, ReaderBackground.entries, changes, skipped) { background ->
                ConfigChange.SetReaderBackground(background)
            }
            planColor("自定义背景色", reader.customBackgroundColor, changes, skipped) { color ->
                ConfigChange.SetReaderCustomBackground(color)
            }
            planColor("文字颜色", reader.textColor, changes, skipped) { color ->
                ConfigChange.SetReaderTextColor(color)
            }
            planEnum("翻页方式", reader.pageTurnMode, ReaderPageTurnMode.entries, changes, skipped) { mode ->
                ConfigChange.SetReaderPageTurn(mode)
            }
            val keepScreenOn = reader.keepScreenOn
            if (keepScreenOn == null) {
                skipped += "保持屏幕常亮（缺失）"
            } else {
                changes += ConfigChange.SetReaderKeepScreenOn(keepScreenOn)
            }
            val immersive = reader.immersiveMode
            if (immersive == null) {
                skipped += "沉浸模式（缺失）"
            } else {
                changes += ConfigChange.SetReaderImmersive(immersive)
            }
        }

        return ImportPlan(changes.toList(), skipped.toList())
    }

    /** 校验单个字段是否合法（用于「导入前预览」），不产生任何写入。 */
    fun inspect(text: String): DecodeResult = decode(text)

    // ---------------------------------------------------------------------
    // 写入仓储
    // ---------------------------------------------------------------------

    /** 逐项写入的落盘结果，用于向用户如实汇报。 */
    data class ApplyResult(val written: Int, val failed: Int)

    /** 依次写入计划中的每一项；单项失败不中断其余项。 */
    suspend fun apply(repository: SettingsRepository, plan: ImportPlan): ApplyResult {
        var written = 0
        var failed = 0
        plan.changes.forEach { change ->
            runCatching { change.writeTo(repository) }
                .onSuccess { written++ }
                .onFailure { failed++ }
        }
        return ApplyResult(written, failed)
    }

    // ---------------------------------------------------------------------
    // 内部工具
    // ---------------------------------------------------------------------

    private fun <T : Enum<T>> planEnum(
        label: String,
        raw: String?,
        values: List<T>,
        changes: MutableList<ConfigChange>,
        skipped: MutableList<String>,
        build: (T) -> ConfigChange,
    ) {
        if (raw == null) {
            skipped += "$label（缺失）"
            return
        }
        val normalized = raw.trim().uppercase()
        val match = values.firstOrNull { it.name == normalized }
        if (match == null) {
            skipped += "$label 取值无效：$raw"
            return
        }
        changes += build(match)
    }

    private fun planNumber(
        label: String,
        value: Float?,
        changes: MutableList<ConfigChange>,
        skipped: MutableList<String>,
        min: Float,
        max: Float,
        build: (Float) -> ConfigChange,
    ) {
        if (value == null) {
            skipped += "$label（缺失）"
            return
        }
        if (value.isNaN() || value.isInfinite()) {
            skipped += "$label 取值无效：$value"
            return
        }
        changes += build(value.coerceIn(min, max))
    }

    private fun planNumber(
        label: String,
        value: Int?,
        changes: MutableList<ConfigChange>,
        skipped: MutableList<String>,
        min: Int,
        max: Int,
        build: (Int) -> ConfigChange,
    ) {
        if (value == null) {
            skipped += "$label（缺失）"
            return
        }
        changes += build(value.coerceIn(min, max))
    }

    private fun planColor(
        label: String,
        value: Int?,
        changes: MutableList<ConfigChange>,
        skipped: MutableList<String>,
        build: (Int) -> ConfigChange,
    ) {
        if (value == null) {
            skipped += "$label（缺失）"
            return
        }
        // 必须是完整的不透明 ARGB，避免导入出透明或越界的控件颜色
        if (value < 0 || (value ushr 24) != 0xFF) {
            skipped += "$label 取值无效：$value"
            return
        }
        changes += build(value)
    }

    private fun Throwable.messageOrType(): String = message?.lineSequence()?.firstOrNull()?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: this::class.simpleName
        ?: "未知错误"
}

/**
 * 一条待写入的设置变更。[label] 用于 UI 展示，[writeTo] 复用
 * `SettingsRepository` 的既有写入方法（其内部已做范围钳制）。
 */
sealed class ConfigChange(val label: String) {
    class SetThemeMode(val value: AppThemeMode) : ConfigChange("主题模式")
    class SetDynamicColor(val value: Boolean) : ConfigChange("使用动态色")
    class SetAccentColor(val value: Int) : ConfigChange("强调色")

    class SetReaderFontSize(val value: Float) : ConfigChange("字号")
    class SetReaderFontWeight(val value: Int) : ConfigChange("字重")
    class SetReaderLineHeight(val value: Float) : ConfigChange("行高")
    class SetReaderParagraphSpacing(val value: Int) : ConfigChange("段距")
    class SetReaderHorizontalPadding(val value: Int) : ConfigChange("左右边距")
    class SetReaderBackground(val value: ReaderBackground) : ConfigChange("阅读背景")
    class SetReaderCustomBackground(val value: Int) : ConfigChange("自定义背景色")
    class SetReaderTextColor(val value: Int) : ConfigChange("文字颜色")
    class SetReaderPageTurn(val value: ReaderPageTurnMode) : ConfigChange("翻页方式")
    class SetReaderKeepScreenOn(val value: Boolean) : ConfigChange("保持屏幕常亮")
    class SetReaderImmersive(val value: Boolean) : ConfigChange("沉浸模式")

    internal suspend fun writeTo(repository: SettingsRepository) {
        when (this) {
            is SetThemeMode -> repository.setThemeMode(value)
            is SetDynamicColor -> repository.setDynamicColor(value)
            is SetAccentColor -> repository.setAccentColor(value)
            is SetReaderFontSize -> repository.setReaderFontSize(value)
            is SetReaderFontWeight -> repository.setReaderFontWeight(value)
            is SetReaderLineHeight -> repository.setReaderLineHeight(value)
            is SetReaderParagraphSpacing -> repository.setReaderParagraphSpacing(value)
            is SetReaderHorizontalPadding -> repository.setReaderHorizontalPadding(value)
            is SetReaderBackground -> repository.setReaderBackground(value)
            is SetReaderCustomBackground -> repository.setReaderCustomBackground(value)
            is SetReaderTextColor -> repository.setReaderTextColor(value)
            is SetReaderPageTurn -> repository.setReaderPageTurn(value)
            is SetReaderKeepScreenOn -> repository.setReaderKeepScreenOn(value)
            is SetReaderImmersive -> repository.setReaderImmersive(value)
        }
    }
}

/** 导出文件名，例如 `wenku8-settings-20260925-120000.json`。 */
fun configExportFileName(now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
    val stamp = java.time.Instant.ofEpochMilli(now).atZone(zone)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    return "wenku8-settings-$stamp.json"
}

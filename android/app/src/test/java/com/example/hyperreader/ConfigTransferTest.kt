package com.example.hyperreader

import com.example.hyperreader.settings.AppThemeMode
import com.example.hyperreader.settings.AppThemeSettings
import com.example.hyperreader.settings.ConfigChange
import com.example.hyperreader.settings.ConfigTransfer
import com.example.hyperreader.settings.ReaderBackground
import com.example.hyperreader.settings.ReaderPageTurnMode
import com.example.hyperreader.settings.ReaderSettings
import com.example.hyperreader.settings.configExportFileName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.time.ZoneId

/**
 * 设置配置导入导出的安全与健壮性测试。
 *
 * 核心底线：导出文件**不可能**包含凭据、登录态或用户内容，
 * 而且这一点由模型类型保证，不是导出时靠「记得别写」。
 */
class ConfigTransferTest {

    private val theme = AppThemeSettings(
        mode = AppThemeMode.DARK,
        useDynamicColor = false,
        accentColor = 0xFF2D6A4F.toInt(),
    )

    private val reader = ReaderSettings(
        fontSizeSp = 21f,
        fontWeight = 500,
        lineHeight = 1.9f,
        paragraphSpacingDp = 16,
        horizontalPaddingDp = 24,
        background = ReaderBackground.GREEN,
        customBackgroundColor = 0xFFE7F0DF.toInt(),
        textColor = 0xFF272522.toInt(),
        pageTurnMode = ReaderPageTurnMode.VERTICAL,
        keepScreenOn = false,
        immersiveMode = false,
        // 设备本地字体地址：绝不进入配置文件
        fontUri = "content://com.android.providers.downloads.documents/document/msf%3A2719",
    )

    private fun export(appVersion: String = "0.8.0", exportedAt: Long = 1_730_000_000_000L): String =
        ConfigTransfer.encode(theme, reader, appVersion, exportedAt)

    private fun keysOf(json: String): Set<String> =
        Json.parseToJsonElement(json).jsonObject.keys

    // -----------------------------------------------------------------
    // 1. 硬性排除：敏感键名与敏感值
    // -----------------------------------------------------------------

    @Test
    fun exportedJsonContainsNoSensitiveKeyNamesOrValues() {
        val text = export()
        val forbidden = listOf(
            "phpsessid", "jieqiuserinfo", "cookie", "cookies", "set-cookie",
            "password", "passwd", "pwd", "token", "access_token", "refresh_token",
            "authorization", "auth", "bearer", "session", "sessionid", "credential",
            "secret", "apikey", "api_key", "account", "username", "login",
        )
        val haystack = text.lowercase()
        forbidden.forEach { needle ->
            assertFalse("导出文件不得出现敏感键名或值：$needle\n$text", haystack.contains(needle))
        }
    }

    @Test
    fun exportedJsonContainsNoBookshelfOrReadingHistory() {
        val text = export().lowercase()
        // 书架条目与阅读记录属用户内容，不进配置同步
        listOf(
            "bookshelf", "bookshelfentry", "书架", "bookid", "book_id", "isbn",
            "chapter", "章节", "booktitles", "readingstats", "progress", "阅读记录",
            "bookmark", "书签",
        ).forEach { needle ->
            assertFalse("导出文件不得包含书架/阅读记录内容：$needle", text.contains(needle))
        }
    }

    @Test
    fun exportedJsonNeverLeaksDeviceLocalFontUri() {
        val text = export()
        assertFalse(text.contains("content://"))
        assertFalse(text.contains("msf%3A2719"))
        assertFalse(text.contains("fontUri"))
        // 但其余阅读器字段确实导出了，说明不是靠「整个对象都没写」
        assertTrue(text.contains("\"fontSizeSp\""))
    }

    // -----------------------------------------------------------------
    // 2. 顶层结构固定
    // -----------------------------------------------------------------

    @Test
    fun topLevelKeysAreExactlyTheDocumentedSet() {
        assertEquals(
            setOf("schemaVersion", "appVersion", "exportedAt", "theme", "reader"),
            keysOf(export()),
        )
    }

    @Test
    fun nestedKeysAreExactlyTheDocumentedSets() {
        val root = Json.parseToJsonElement(export()).jsonObject
        assertEquals(
            setOf("mode", "useDynamicColor", "accentColor"),
            root.getValue("theme").jsonObject.keys,
        )
        assertEquals(
            setOf(
                "fontSizeSp", "fontWeight", "lineHeight", "paragraphSpacingDp", "horizontalPaddingDp",
                "background", "customBackgroundColor", "textColor", "pageTurnMode",
                "keepScreenOn", "immersiveMode",
            ),
            root.getValue("reader").jsonObject.keys,
        )
    }

    @Test
    fun exportedDocumentCarriesSchemaAndInjectedMetadata() {
        val text = export(appVersion = "0.8.0", exportedAt = 1_730_000_000_000L)
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals(1, root.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals(1, ConfigTransfer.SCHEMA_VERSION)
        assertEquals("0.8.0", root.getValue("appVersion").jsonPrimitive.content)
        assertEquals(1_730_000_000_000L, root.getValue("exportedAt").jsonPrimitive.content.toLong())
    }

    @Test
    fun appVersionAndTimestampAreInjectableNotHardcoded() {
        // 传入任意版本号与时间戳都必须被如实写出，避免随版本推进写死 0.8.0
        val text = ConfigTransfer.encode(theme, reader, appVersion = "9.9.9", exportedAt = 42L)
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals("9.9.9", root.getValue("appVersion").jsonPrimitive.content)
        assertEquals(42L, root.getValue("exportedAt").jsonPrimitive.content.toLong())
    }

    // -----------------------------------------------------------------
    // 3. 类型层面无法承载凭据
    // -----------------------------------------------------------------

    @Test
    fun transferModelTypesCannotHoldCredentialsOrCollections() {
        // 任何能装下凭据的形态（集合、Map、字节数组、密封类）都被排除在外
        val allowedScalarTypes = setOf(
            "int", "java.lang.Integer",
            "float", "java.lang.Float",
            "long", "java.lang.Long",
            "boolean", "java.lang.Boolean",
            "java.lang.String",
            // 根对象只允许挂这两个纯标量 DTO
            ConfigTransfer.ConfigTheme::class.java.name,
            ConfigTransfer.ConfigReader::class.java.name,
        )
        val dtoTypes = listOf(
            ConfigTransfer.ConfigDocument::class.java,
            ConfigTransfer.ConfigTheme::class.java,
            ConfigTransfer.ConfigReader::class.java,
            ConfigChange::class.java,
        )
        dtoTypes.forEach { type ->
            // @Serializable 会由编译器注入一个 static Companion 字段（序列化器的宿主），
            // 它不是数据字段，按类型隔离的语义应当排除；只校验实例字段。
            val instanceFields = type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
            assertTrue("${type.simpleName} 应当仍有实例字段，否则本测试形同虚设", instanceFields.isNotEmpty())
            instanceFields.forEach { field ->
                assertTrue(
                    "${type.simpleName}.${field.name} 的类型 ${field.type.name} 不应是可承载凭据的结构",
                    field.type.name in allowedScalarTypes,
                )
            }
        }
        // 根对象只有 5 个标量字段 + 2 个 DTO，没有任何集合型字段
        val root = Json.parseToJsonElement(export()).jsonObject
        assertEquals(5, root.size)
    }

    @Test
    fun everyWritableChangeTargetsASingleSettingsField() {
        // 导入计划的每一条都只能改一个具体设置项，无法整体替换设置对象
        val plan = ConfigTransfer.plan(ConfigTransfer.documentOf(theme, reader, "0.8.0", 0L))
        assertEquals(14, plan.appliedCount)
        assertTrue(plan.changes.all { it.label.isNotBlank() })
        assertEquals(plan.changes.size, plan.changes.map { it.label }.toSet().size)
    }

    // -----------------------------------------------------------------
    // 4. schemaVersion 校验
    // -----------------------------------------------------------------

    @Test
    fun unknownSchemaVersionIsRejectedWithReason() {
        val future = ConfigTransfer.decode("""{"schemaVersion":99,"appVersion":"9.0.0"}""")
        assertTrue(future is ConfigTransfer.DecodeResult.Rejected)
        assertTrue((future as ConfigTransfer.DecodeResult.Rejected).reason.contains("99"))

        val older = ConfigTransfer.decode("""{"schemaVersion":0}""")
        assertTrue(older is ConfigTransfer.DecodeResult.Rejected)

        val missing = ConfigTransfer.decode("""{"appVersion":"0.8.0","theme":{}}""")
        assertTrue(missing is ConfigTransfer.DecodeResult.Rejected)
        assertTrue((missing as ConfigTransfer.DecodeResult.Rejected).reason.contains("schemaVersion"))
    }

    @Test
    fun malformedOrEmptyPayloadIsRejectedWithoutThrowing() {
        listOf("", "   ", "not json", "{", "[]", "null", "{\"schemaVersion\": \"one\"}").forEach { payload ->
            val result = ConfigTransfer.decode(payload)
            assertTrue("非法输入必须被拒绝：'$payload'", result is ConfigTransfer.DecodeResult.Rejected)
            assertTrue((result as ConfigTransfer.DecodeResult.Rejected).reason.isNotBlank())
        }
    }

    // -----------------------------------------------------------------
    // 5. 逐字段钳制与跳过
    // -----------------------------------------------------------------

    @Test
    fun outOfRangeNumbersAreClampedInsteadOfCrashing() {
        val document = ConfigTransfer.ConfigDocument(
            schemaVersion = 1,
            theme = ConfigTransfer.ConfigTheme(mode = "LIGHT", useDynamicColor = true, accentColor = 0xFF102030.toInt()),
            reader = ConfigTransfer.ConfigReader(
                fontSizeSp = -5f,
                fontWeight = 5_000,
                lineHeight = 100f,
                paragraphSpacingDp = -10,
                horizontalPaddingDp = 999,
                background = "PAPER",
                customBackgroundColor = 0xFFFFFFFF.toInt(),
                textColor = 0xFF000000.toInt(),
                pageTurnMode = "HORIZONTAL",
                keepScreenOn = true,
                immersiveMode = true,
            ),
        )
        val result = ConfigTransfer.decode(ConfigTransfer.encodeDocument(document))
        assertTrue(result is ConfigTransfer.DecodeResult.Success)
        val plan = ConfigTransfer.plan((result as ConfigTransfer.DecodeResult.Success).document)
        assertEquals(0, plan.skippedCount)
        assertEquals(14, plan.appliedCount)

        fun change(label: String) = plan.changes.first { it.label == label }
        assertEquals(12f, (change("字号") as ConfigChange.SetReaderFontSize).value, 0.001f)
        assertEquals(900, (change("字重") as ConfigChange.SetReaderFontWeight).value)
        assertEquals(2.6f, (change("行高") as ConfigChange.SetReaderLineHeight).value, 0.001f)
        assertEquals(0, (change("段距") as ConfigChange.SetReaderParagraphSpacing).value)
        assertEquals(48, (change("左右边距") as ConfigChange.SetReaderHorizontalPadding).value)
    }

    @Test
    fun nonFiniteAndInvalidValuesAreSkippedNotFatal() {
        val document = ConfigTransfer.ConfigDocument(
            schemaVersion = 1,
            theme = ConfigTransfer.ConfigTheme(mode = "SEPIA", useDynamicColor = true, accentColor = 123),
            reader = ConfigTransfer.ConfigReader(
                fontSizeSp = Float.NaN,
                lineHeight = Float.POSITIVE_INFINITY,
                background = "NEON",
                textColor = 0x00FFFFFF,
                pageTurnMode = "DIAGONAL",
            ),
        )
        val plan = ConfigTransfer.plan(document)
        // 合法项照常导入
        assertTrue(plan.changes.any { it is ConfigChange.SetDynamicColor })
        // 非法项只跳过自身：主题 2 项 + 阅读器 11 项全部不可用，其余不受影响
        assertEquals(13, plan.skippedCount)
        assertEquals(1, plan.appliedCount)
        assertTrue(plan.skipped.any { it.contains("SEPIA") })
        assertTrue(plan.skipped.any { it.contains("NEON") })
        assertTrue(plan.skipped.any { it.contains("DIAGONAL") })
        assertTrue(plan.appliedCount > 0)
        assertFalse(plan.changes.any { it is ConfigChange.SetAccentColor })
        assertFalse(plan.changes.any { it is ConfigChange.SetReaderTextColor })
    }

    @Test
    fun missingFieldsStillLetTheRestImport() {
        val partial = """
            {"schemaVersion":1,"appVersion":"0.8.0","exportedAt":1,
             "theme":{"mode":"LIGHT"},
             "reader":{"fontSizeSp":16,"background":"PAPER"}}
        """.trimIndent()
        val decoded = ConfigTransfer.decode(partial)
        assertTrue(decoded is ConfigTransfer.DecodeResult.Success)
        val plan = ConfigTransfer.plan((decoded as ConfigTransfer.DecodeResult.Success).document)
        assertEquals(3, plan.appliedCount)
        assertEquals(11, plan.skippedCount)
        assertTrue(plan.summary().contains("导入成功 3 项"))
        assertTrue(plan.summary().contains("跳过 11 项"))
        assertTrue(plan.details().isNotBlank())
    }

    @Test
    fun entirelyMissingSectionsAreReportedNotIgnored() {
        val plan = ConfigTransfer.plan(ConfigTransfer.ConfigDocument(schemaVersion = 1))
        assertEquals(0, plan.appliedCount)
        // 整段缺失按「一段一条」计数，而不是把 14 个字段逐条摊开
        assertEquals(2, plan.skippedCount)
        assertTrue(plan.isEmpty)
        assertTrue(plan.summary().contains("导入成功 0 项"))
        assertTrue(plan.details().contains("主题设置"))
        assertTrue(plan.details().contains("阅读器设置"))
    }

    // -----------------------------------------------------------------
    // 6. 宽容解析与往返
    // -----------------------------------------------------------------

    @Test
    fun unknownKeysFromFutureVersionsAreTolerated() {
        val payload = """
            {"schemaVersion":1,"appVersion":"0.8.0","exportedAt":1,
             "futureFeature":{"whatever":true},
             "theme":{"mode":"DARK","newThemeKnob":7},
             "reader":{"fontSizeSp":20,"futureReaderField":"x"}}
        """.trimIndent()
        val decoded = ConfigTransfer.decode(payload)
        assertTrue(decoded is ConfigTransfer.DecodeResult.Success)
        val plan = ConfigTransfer.plan((decoded as ConfigTransfer.DecodeResult.Success).document)
        assertEquals(20f, (plan.changes.first { it is ConfigChange.SetReaderFontSize } as ConfigChange.SetReaderFontSize).value, 0.001f)
        assertEquals(AppThemeMode.DARK, (plan.changes.first { it is ConfigChange.SetThemeMode } as ConfigChange.SetThemeMode).value)
    }

    @Test
    fun roundTripReproducesEverySetting() {
        val decoded = ConfigTransfer.decode(export())
        assertTrue(decoded is ConfigTransfer.DecodeResult.Success)
        val plan = ConfigTransfer.plan((decoded as ConfigTransfer.DecodeResult.Success).document)
        assertEquals(0, plan.skippedCount)
        assertEquals(
            setOf(
                "主题模式", "使用动态色", "强调色",
                "字号", "字重", "行高", "段距", "左右边距",
                "阅读背景", "自定义背景色", "文字颜色", "翻页方式",
                "保持屏幕常亮", "沉浸模式",
            ),
            plan.changes.map { it.label }.toSet(),
        )
        assertEquals(AppThemeMode.DARK, (plan.changes.first { it is ConfigChange.SetThemeMode } as ConfigChange.SetThemeMode).value)
        assertEquals(false, (plan.changes.first { it is ConfigChange.SetDynamicColor } as ConfigChange.SetDynamicColor).value)
        assertEquals(0xFF2D6A4F.toInt(), (plan.changes.first { it is ConfigChange.SetAccentColor } as ConfigChange.SetAccentColor).value)
        assertEquals(21f, (plan.changes.first { it is ConfigChange.SetReaderFontSize } as ConfigChange.SetReaderFontSize).value, 0.001f)
        assertEquals(500, (plan.changes.first { it is ConfigChange.SetReaderFontWeight } as ConfigChange.SetReaderFontWeight).value)
        assertEquals(1.9f, (plan.changes.first { it is ConfigChange.SetReaderLineHeight } as ConfigChange.SetReaderLineHeight).value, 0.001f)
        assertEquals(16, (plan.changes.first { it is ConfigChange.SetReaderParagraphSpacing } as ConfigChange.SetReaderParagraphSpacing).value)
        assertEquals(24, (plan.changes.first { it is ConfigChange.SetReaderHorizontalPadding } as ConfigChange.SetReaderHorizontalPadding).value)
        assertEquals(ReaderBackground.GREEN, (plan.changes.first { it is ConfigChange.SetReaderBackground } as ConfigChange.SetReaderBackground).value)
        assertEquals(ReaderPageTurnMode.VERTICAL, (plan.changes.first { it is ConfigChange.SetReaderPageTurn } as ConfigChange.SetReaderPageTurn).value)
    }

    @Test
    fun everyThemeAndReaderValueSurvivesTheCodec() {
        // 逐一枚举枚举取值，确保没有哪个值在编解码往返中丢失或被拒
        AppThemeMode.entries.forEach { mode ->
            val text = ConfigTransfer.encode(
                theme.copy(mode = mode), reader, "0.8.0", 1_730_000_000_000L,
            )
            val decoded = ConfigTransfer.decode(text)
            assertTrue(decoded is ConfigTransfer.DecodeResult.Success)
            val plan = ConfigTransfer.plan((decoded as ConfigTransfer.DecodeResult.Success).document)
            assertEquals(mode, (plan.changes.first { it is ConfigChange.SetThemeMode } as ConfigChange.SetThemeMode).value)
            assertEquals(0, plan.skippedCount)
        }
        ReaderBackground.entries.forEach { background ->
            ReaderPageTurnMode.entries.forEach { turn ->
                val text = ConfigTransfer.encode(
                    theme, reader.copy(background = background, pageTurnMode = turn), "0.8.0", 1_730_000_000_000L,
                )
                val plan = ConfigTransfer.plan((ConfigTransfer.decode(text) as ConfigTransfer.DecodeResult.Success).document)
                assertEquals(background, (plan.changes.first { it is ConfigChange.SetReaderBackground } as ConfigChange.SetReaderBackground).value)
                assertEquals(turn, (plan.changes.first { it is ConfigChange.SetReaderPageTurn } as ConfigChange.SetReaderPageTurn).value)
            }
        }
    }

    @Test
    fun enumMatchingIsLenientAboutCaseAndPadding() {
        val plan = ConfigTransfer.plan(
            ConfigTransfer.ConfigDocument(
                schemaVersion = 1,
                theme = ConfigTransfer.ConfigTheme(mode = "  dark "),
                reader = ConfigTransfer.ConfigReader(pageTurnMode = "vertical"),
            )
        )
        assertEquals(AppThemeMode.DARK, (plan.changes.first { it is ConfigChange.SetThemeMode } as ConfigChange.SetThemeMode).value)
        assertEquals(ReaderPageTurnMode.VERTICAL, (plan.changes.first { it is ConfigChange.SetReaderPageTurn } as ConfigChange.SetReaderPageTurn).value)
    }

    @Test
    fun exportFileNameIsDerivedFromInjectedTime() {
        val name = configExportFileName(1_730_000_000_000L, ZoneId.of("UTC"))
        assertTrue(name, name.matches(Regex("wenku8-settings-\\d{8}-\\d{6}\\.json")))
        // 时间可注入：不同时间得到不同文件名，命名不含任何凭据或路径
        assertTrue(name != configExportFileName(1_730_003_600_000L, ZoneId.of("UTC")))
        // 时区参与格式化，因此同一时刻在不同时区得到不同名字
        assertTrue(name != configExportFileName(1_730_000_000_000L, ZoneId.of("Asia/Shanghai")))
        assertFalse(name.contains("/"))
        assertFalse(name.contains(":"))
    }
}

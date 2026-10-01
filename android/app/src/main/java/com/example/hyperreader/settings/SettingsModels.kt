package com.example.hyperreader.settings

import kotlinx.serialization.Serializable

enum class AppThemeMode { SYSTEM, LIGHT, DARK, MONET }

enum class ReaderBackground { PAPER, LIGHT, GREEN, DARK, OLED, CUSTOM }

enum class ReaderPageTurnMode { HORIZONTAL, VERTICAL }

@Serializable
data class AppThemeSettings(
    val mode: AppThemeMode = AppThemeMode.MONET,
    val useDynamicColor: Boolean = true,
    val accentColor: Int = 0xFFA34B2F.toInt(),
)

@Serializable
data class ReaderSettings(
    val fontSizeSp: Float = 18f,
    val fontWeight: Int = 400,
    val lineHeight: Float = 1.7f,
    val paragraphSpacingDp: Int = 12,
    val horizontalPaddingDp: Int = 20,
    val background: ReaderBackground = ReaderBackground.PAPER,
    val customBackgroundColor: Int = 0xFFF4EFE6.toInt(),
    val textColor: Int = 0xFF272522.toInt(),
    val pageTurnMode: ReaderPageTurnMode = ReaderPageTurnMode.HORIZONTAL,
    val keepScreenOn: Boolean = true,
    val immersiveMode: Boolean = true,
    val fontUri: String? = null,
) {
    companion object {
        const val MIN_FONT_SIZE = 12f
        const val MAX_FONT_SIZE = 32f
        const val MIN_LINE_HEIGHT = 1.2f
        const val MAX_LINE_HEIGHT = 2.6f
    }
}

@Serializable
data class ReadingProgress(
    val bookId: String,
    val chapterId: String,
    val chapterIndex: Int = 0,
    val paragraphIndex: Int = 0,
    val updatedAt: Long = 0L,
)

/**
 * EPUB 导出引擎（0.14.0 起可在设置里切换）。
 *
 * 两个引擎产出同样的 EPUB 3.3 结构、调用契约一致，区别见各自实现的 KDoc：
 * - [CLASSIC]：自研 `epub/EpubBuilder`，保留已 sanitize 的行内强调标签
 * - [POTATO]：LightNovelReader 的 `:epub` 模块（`io.nightfish.potatoepub`，Apache-2.0），
 *   正文按段落纯文本写入
 */
@kotlinx.serialization.Serializable
enum class EpubEngine(val label: String, val summary: String) {
    CLASSIC("自研引擎", "保留行内强调，默认"),
    POTATO("LNR 引擎", "段落纯文本，结构更规范"),
    ;

    companion object {
        fun fromName(raw: String?): EpubEngine =
            entries.firstOrNull { it.name == raw } ?: CLASSIC
    }
}

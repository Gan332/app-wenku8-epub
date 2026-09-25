package com.wenku8.epubstudio.settings

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

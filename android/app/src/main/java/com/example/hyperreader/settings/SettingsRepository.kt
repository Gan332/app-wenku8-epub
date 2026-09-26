package com.example.hyperreader.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.hyperreader.data.appDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class SettingsRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val data = context.applicationContext.appDataStore

    val appTheme: Flow<AppThemeSettings> = data.safeData().map { prefs ->
        AppThemeSettings(
            mode = prefs[THEME_MODE]?.let { name -> runCatching { AppThemeMode.valueOf(name) }.getOrNull() } ?: AppThemeMode.MONET,
            useDynamicColor = prefs[USE_DYNAMIC_COLOR] ?: true,
            accentColor = prefs[ACCENT_COLOR] ?: 0xFFA34B2F.toInt(),
        )
    }

    val readerSettings: Flow<ReaderSettings> = data.safeData().map { prefs ->
        val default = ReaderSettings()
        ReaderSettings(
            fontSizeSp = prefs[READER_FONT_SIZE] ?: default.fontSizeSp,
            fontWeight = prefs[READER_FONT_WEIGHT] ?: default.fontWeight,
            lineHeight = prefs[READER_LINE_HEIGHT] ?: default.lineHeight,
            paragraphSpacingDp = prefs[READER_PARAGRAPH_SPACING] ?: default.paragraphSpacingDp,
            horizontalPaddingDp = prefs[READER_HORIZONTAL_PADDING] ?: default.horizontalPaddingDp,
            background = prefs[READER_BACKGROUND]?.let { name -> runCatching { ReaderBackground.valueOf(name) }.getOrNull() } ?: default.background,
            customBackgroundColor = prefs[READER_CUSTOM_BACKGROUND] ?: default.customBackgroundColor,
            textColor = prefs[READER_TEXT_COLOR] ?: default.textColor,
            pageTurnMode = prefs[READER_PAGE_TURN]?.let { name -> runCatching { ReaderPageTurnMode.valueOf(name) }.getOrNull() } ?: default.pageTurnMode,
            keepScreenOn = prefs[READER_KEEP_SCREEN_ON] ?: default.keepScreenOn,
            immersiveMode = prefs[READER_IMMERSIVE] ?: default.immersiveMode,
            fontUri = prefs[READER_FONT_URI],
        )
    }

    val searchHistory: Flow<List<String>> = data.safeData().map { prefs ->
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), prefs[SEARCH_HISTORY].orEmpty()) }.getOrDefault(emptyList())
    }

    fun progress(bookId: String): Flow<ReadingProgress?> = data.safeData().map { prefs ->
        prefs[progressKey(bookId)]?.let { encoded -> runCatching { json.decodeFromString(ReadingProgress.serializer(), encoded) }.getOrNull() }
    }

    /**
     * 全部阅读断点（书架「上次读到哪」展示用）：扫描 `reader_progress_` 前缀键。
     * 解码走纯函数 [parseProgressMap]，坏数据跳过而不是拖垮整个书架。
     */
    fun allProgress(): Flow<Map<String, ReadingProgress>> = data.safeData().map { prefs ->
        val raw = prefs.asMap()
            .filterKeys { it.name.startsWith(PROGRESS_PREFIX) }
            .mapKeys { it.key.name.removePrefix(PROGRESS_PREFIX) }
            .mapValues { (_, value) -> value as? String }
        parseProgressMap(raw)
    }

    suspend fun setThemeMode(mode: AppThemeMode) = edit { it[THEME_MODE] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[USE_DYNAMIC_COLOR] = enabled }
    suspend fun setAccentColor(color: Int) = edit { it[ACCENT_COLOR] = color }

    suspend fun setReaderFontSize(value: Float) = edit { it[READER_FONT_SIZE] = value.coerceIn(ReaderSettings.MIN_FONT_SIZE, ReaderSettings.MAX_FONT_SIZE) }
    suspend fun setReaderFontWeight(value: Int) = edit { it[READER_FONT_WEIGHT] = value.coerceIn(100, 900) }
    suspend fun setReaderLineHeight(value: Float) = edit { it[READER_LINE_HEIGHT] = value.coerceIn(ReaderSettings.MIN_LINE_HEIGHT, ReaderSettings.MAX_LINE_HEIGHT) }
    suspend fun setReaderParagraphSpacing(value: Int) = edit { it[READER_PARAGRAPH_SPACING] = value.coerceIn(0, 48) }
    suspend fun setReaderHorizontalPadding(value: Int) = edit { it[READER_HORIZONTAL_PADDING] = value.coerceIn(0, 48) }
    suspend fun setReaderBackground(value: ReaderBackground) = edit { it[READER_BACKGROUND] = value.name }
    suspend fun setReaderCustomBackground(value: Int) = edit { it[READER_CUSTOM_BACKGROUND] = value }
    suspend fun setReaderTextColor(value: Int) = edit { it[READER_TEXT_COLOR] = value }
    suspend fun setReaderPageTurn(value: ReaderPageTurnMode) = edit { it[READER_PAGE_TURN] = value.name }
    suspend fun setReaderKeepScreenOn(value: Boolean) = edit { it[READER_KEEP_SCREEN_ON] = value }
    suspend fun setReaderImmersive(value: Boolean) = edit { it[READER_IMMERSIVE] = value }
    suspend fun setReaderFontUri(value: String?) = edit { prefs -> if (value == null) prefs.remove(READER_FONT_URI) else prefs[READER_FONT_URI] = value }

    suspend fun recordSearch(keyword: String) = edit { prefs ->
        val history = runCatching { json.decodeFromString(ListSerializer(String.serializer()), prefs[SEARCH_HISTORY].orEmpty()) }.getOrDefault(emptyList())
        val next = (listOf(keyword.trim()) + history.filterNot { it.equals(keyword.trim(), ignoreCase = true) }).filter(String::isNotBlank).take(20)
        prefs[SEARCH_HISTORY] = json.encodeToString(ListSerializer(String.serializer()), next)
    }

    suspend fun clearSearchHistory() = edit { it.remove(SEARCH_HISTORY) }

    suspend fun saveProgress(progress: ReadingProgress) = edit { it[progressKey(progress.bookId)] = json.encodeToString(ReadingProgress.serializer(), progress) }
    suspend fun clearProgress(bookId: String) = edit { it.remove(progressKey(bookId)) }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        data.edit { block(it) }
    }

    private fun progressKey(bookId: String) = stringPreferencesKey(PROGRESS_PREFIX + bookId)

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
        val ACCENT_COLOR = intPreferencesKey("accent_color")
        val READER_FONT_SIZE = floatPreferencesKey("reader_font_size")
        val READER_FONT_WEIGHT = intPreferencesKey("reader_font_weight")
        val READER_LINE_HEIGHT = floatPreferencesKey("reader_line_height")
        val READER_PARAGRAPH_SPACING = intPreferencesKey("reader_paragraph_spacing")
        val READER_HORIZONTAL_PADDING = intPreferencesKey("reader_horizontal_padding")
        val READER_BACKGROUND = stringPreferencesKey("reader_background")
        val READER_CUSTOM_BACKGROUND = intPreferencesKey("reader_custom_background")
        val READER_TEXT_COLOR = intPreferencesKey("reader_text_color")
        val READER_PAGE_TURN = stringPreferencesKey("reader_page_turn")
        val READER_KEEP_SCREEN_ON = booleanPreferencesKey("reader_keep_screen_on")
        val READER_IMMERSIVE = booleanPreferencesKey("reader_immersive")
        val READER_FONT_URI = stringPreferencesKey("reader_font_uri")
        val SEARCH_HISTORY = stringPreferencesKey("search_history")
    }
}

private fun DataStore<Preferences>.safeData(): Flow<Preferences> = data.catch { emit(emptyPreferences()) }

private const val PROGRESS_PREFIX = "reader_progress_"

private val PROGRESS_JSON = Json { ignoreUnknownKeys = true }

/**
 * 纯函数：`bookId → ReadingProgress JSON` 解码（可单测）。
 * 空键、坏 JSON 一律跳过，不让书架整体加载失败。
 */
internal fun parseProgressMap(raw: Map<String, String?>): Map<String, ReadingProgress> =
    raw.entries.mapNotNull { (bookId, encoded) ->
        if (bookId.isBlank() || encoded.isNullOrBlank()) null
        else runCatching { PROGRESS_JSON.decodeFromString(ReadingProgress.serializer(), encoded) }
            .getOrNull()
            ?.let { bookId to it }
    }.toMap()

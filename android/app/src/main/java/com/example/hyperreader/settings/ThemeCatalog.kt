package com.example.hyperreader.settings

/**
 * 一个可一键套用的主题预设。
 *
 * 预设只描述「模式 + 是否动态取色 + 强调色」三件事，实际色板仍由 MiuiX 生成；
 * 因此新增预设不需要复制任何颜色表，也不会与动态色/暗色逻辑分叉。
 */
data class ThemePreset(
    val id: String,
    val label: String,
    val summary: String,
    val settings: AppThemeSettings,
)

/**
 * 内置主题目录（设置 → 主题与外观）。
 *
 * 顺序即界面顺序：先给「跟随系统」与 HyperOS 经典配色，再给几套固定强调色。
 * [ALL] 是唯一数据源，设置页不再维护第二份硬编码列表。
 */
object ThemePresets {
    /** HyperOS 官方蓝强调色。 */
    const val HYPEROS_BLUE = 0xFF3482FF.toInt()

    /** 默认暖棕强调色（与旧版默认值一致，避免升级后观感跳变）。 */
    const val WARM_ACCENT = 0xFFA34B2F.toInt()

    const val FOREST_ACCENT = 0xFF2D6A4F.toInt()
    const val VIOLET_ACCENT = 0xFF7C3AED.toInt()
    const val GRAPHITE_ACCENT = 0xFF111827.toInt()
    const val CRIMSON_ACCENT = 0xFFB3261E.toInt()
    const val NIGHT_BLUE_ACCENT = 0xFF6EA8FF.toInt()
    const val NIGHT_GRAPHITE_ACCENT = 0xFF9AA4B2.toInt()

    /**
     * HyperOS 经典蓝白：浅色底 + 固定 HyperOS 蓝，不跟随系统动态色。
     * 白底蓝点，贴近 HyperOS 默认观感。
     */
    val HYPEROS_BLUE_WHITE = AppThemeSettings(
        mode = AppThemeMode.LIGHT,
        useDynamicColor = false,
        accentColor = HYPEROS_BLUE,
    )

    /** HyperOS 深色蓝：夜间固定蓝，保持蓝白家族的一致性。 */
    val HYPEROS_NIGHT = AppThemeSettings(
        mode = AppThemeMode.DARK,
        useDynamicColor = false,
        accentColor = NIGHT_BLUE_ACCENT,
    )

    /** 暖纸：旧版默认的暖棕强调色 + 浅色。 */
    val WARM_PAPER = AppThemeSettings(
        mode = AppThemeMode.LIGHT,
        useDynamicColor = false,
        accentColor = WARM_ACCENT,
    )

    /** 森林：浅色 + 墨绿。 */
    val FOREST = AppThemeSettings(
        mode = AppThemeMode.LIGHT,
        useDynamicColor = false,
        accentColor = FOREST_ACCENT,
    )

    /** 紫罗兰：浅色 + 紫。 */
    val VIOLET = AppThemeSettings(
        mode = AppThemeMode.LIGHT,
        useDynamicColor = false,
        accentColor = VIOLET_ACCENT,
    )

    /** 曜石：深色 + 中性灰蓝。 */
    val GRAPHITE = AppThemeSettings(
        mode = AppThemeMode.DARK,
        useDynamicColor = false,
        accentColor = NIGHT_GRAPHITE_ACCENT,
    )

    /** 系统动态色：跟随系统明暗，并从壁纸取色。 */
    val SYSTEM_DYNAMIC = AppThemeSettings(
        mode = AppThemeMode.SYSTEM,
        useDynamicColor = true,
        accentColor = WARM_ACCENT,
    )

    val ALL: List<ThemePreset> = listOf(
        ThemePreset("system-dynamic", "系统动态色", "跟随系统明暗，并从壁纸取色", SYSTEM_DYNAMIC),
        ThemePreset("hyperos-blue-white", "HyperOS 经典蓝白", "浅色底 + HyperOS 蓝，不跟随动态色", HYPEROS_BLUE_WHITE),
        ThemePreset("hyperos-night", "HyperOS 深色蓝", "深色底 + 夜间蓝，低亮度下更克制", HYPEROS_NIGHT),
        ThemePreset("warm-paper", "暖纸", "浅色底 + 暖棕强调，接近纸书观感", WARM_PAPER),
        ThemePreset("forest", "森林", "浅色底 + 墨绿强调，适合长时间阅读", FOREST),
        ThemePreset("violet", "紫罗兰", "浅色底 + 紫色强调，对比更鲜明", VIOLET),
        ThemePreset("graphite", "曜石", "深色底 + 中性灰蓝，夜间不刺眼", GRAPHITE),
    )

    /** 强调色快捷调色板；设置页与预设目录共用。 */
    val ACCENT_COLORS: List<Int> = listOf(
        WARM_ACCENT,
        FOREST_ACCENT,
        HYPEROS_BLUE,
        VIOLET_ACCENT,
        GRAPHITE_ACCENT,
        CRIMSON_ACCENT,
    )

    /** 当前设置精确命中某个预设时返回它；否则返回 null（表示自定义）。 */
    fun match(settings: AppThemeSettings): ThemePreset? {
        val normalized = settings.normalized()
        return ALL.firstOrNull { it.settings == normalized }
    }
}

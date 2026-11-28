package com.example.hyperreader.settings

/**
 * 主题解析：把持久化的 [AppThemeSettings] 收敛成**实际生效**的三要素。
 *
 * 旧版把「动态色」当成一种独立模式（[AppThemeMode.MONET]），导致「系统 + 动态色」
 * 与「动态色」两种写法并存，界面和配置导入都要各判断一次。现在模式只表达明暗，
 * 动态取色由 [AppThemeSettings.useDynamicColor] 表达；[MONET] 在解析时归一为
 * [ThemeScheme.SYSTEM]，保证旧数据不会失效。
 *
 * 这里是纯函数，不依赖 Compose / MiuiX，便于单测锁定迁移口径。
 */
enum class ThemeScheme { SYSTEM, LIGHT, DARK }

data class ThemeSpec(
    val scheme: ThemeScheme,
    val dynamic: Boolean,
    val accentColor: Int,
)

/** 旧数据迁移：仅把 [AppThemeMode.MONET] 归一到 [AppThemeMode.SYSTEM]，其余字段原样保留。 */
fun AppThemeSettings.normalized(): AppThemeSettings =
    if (mode == AppThemeMode.MONET) copy(mode = mode.normalizedMode()) else this

fun AppThemeMode.normalizedMode(): AppThemeMode =
    if (this == AppThemeMode.MONET) AppThemeMode.SYSTEM else this

fun AppThemeSettings.toThemeSpec(): ThemeSpec {
    val normalized = normalized()
    return ThemeSpec(
        scheme = normalized.mode.toThemeScheme(),
        dynamic = normalized.useDynamicColor,
        accentColor = normalized.accentColor,
    )
}

fun AppThemeMode.toThemeScheme(): ThemeScheme = when (this) {
    AppThemeMode.SYSTEM, AppThemeMode.MONET -> ThemeScheme.SYSTEM
    AppThemeMode.LIGHT -> ThemeScheme.LIGHT
    AppThemeMode.DARK -> ThemeScheme.DARK
}

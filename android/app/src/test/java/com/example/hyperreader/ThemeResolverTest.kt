package com.example.hyperreader

import com.example.hyperreader.settings.AppThemeMode
import com.example.hyperreader.settings.AppThemeSettings
import com.example.hyperreader.settings.ThemeScheme
import com.example.hyperreader.settings.normalized
import com.example.hyperreader.settings.normalizedMode
import com.example.hyperreader.settings.toThemeScheme
import com.example.hyperreader.settings.toThemeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题解析口径（0.19.0）。
 *
 * 旧数据把 `MONET` 当成第四种模式；新模型里模式只表达明暗，动态取色独立成
 * `useDynamicColor`。这里锁定迁移只做一件事：`MONET → SYSTEM`，其余字段原样保留。
 */
class ThemeResolverTest {

    @Test
    fun legacyMonetIsReadAsSystem() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromName("MONET"))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.MONET.normalizedMode())
        assertEquals(ThemeScheme.SYSTEM, AppThemeMode.MONET.toThemeScheme())
    }

    @Test
    fun unknownModeFallsBackToSystem() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromName("NOT_A_MODE"))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromName(null))
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.fromName(" light "))
    }

    @Test
    fun normalizedOnlyRewritesMode() {
        val legacy = AppThemeSettings(
            mode = AppThemeMode.MONET,
            useDynamicColor = false,
            accentColor = 0xFF3482FF.toInt(),
        )

        val normalized = legacy.normalized()
        assertEquals(AppThemeMode.SYSTEM, normalized.mode)
        assertEquals(legacy.useDynamicColor, normalized.useDynamicColor)
        assertEquals(legacy.accentColor, normalized.accentColor)
    }

    @Test
    fun themeSpecSplitsSchemeAndDynamicColor() {
        val spec = AppThemeSettings(
            mode = AppThemeMode.DARK,
            useDynamicColor = true,
            accentColor = 0xFF7C3AED.toInt(),
        ).toThemeSpec()

        assertEquals(ThemeScheme.DARK, spec.scheme)
        assertTrue(spec.dynamic)
        assertEquals(0xFF7C3AED.toInt(), spec.accentColor)
    }

    @Test
    fun legacyMonetWithDynamicColorKeepsDynamicFlag() {
        val spec = AppThemeSettings(
            mode = AppThemeMode.MONET,
            useDynamicColor = true,
            accentColor = 0xFFA34B2F.toInt(),
        ).toThemeSpec()

        assertEquals(ThemeScheme.SYSTEM, spec.scheme)
        assertTrue(spec.dynamic)
    }

    @Test
    fun fixedColorModesAreNotDynamic() {
        val spec = AppThemeSettings(
            mode = AppThemeMode.LIGHT,
            useDynamicColor = false,
            accentColor = 0xFF3482FF.toInt(),
        ).toThemeSpec()

        assertEquals(ThemeScheme.LIGHT, spec.scheme)
        assertFalse(spec.dynamic)
    }
}

package com.example.hyperreader

import com.example.hyperreader.settings.AppThemeMode
import com.example.hyperreader.settings.AppThemeSettings
import com.example.hyperreader.settings.ThemePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePresetTest {

    @Test
    fun hyperOsBlueWhiteIsLightFixedBlue() {
        val preset = ThemePresets.HYPEROS_BLUE_WHITE

        assertEquals(AppThemeMode.LIGHT, preset.mode)
        assertFalse(preset.useDynamicColor)
        assertEquals(ThemePresets.HYPEROS_BLUE, preset.accentColor)
    }

    @Test
    fun presetIdsAreUniqueAndNonEmpty() {
        val ids = ThemePresets.ALL.map { it.id }
        assertTrue("预设 id 必须唯一", ids.size == ids.toSet().size)
        assertTrue("主题目录不能为空", ids.isNotEmpty())
        assertTrue("每个预设都要有名称与说明", ThemePresets.ALL.all { it.label.isNotBlank() && it.summary.isNotBlank() })
    }

    @Test
    fun catalogCoversLightDarkAndDynamic() {
        val modes = ThemePresets.ALL.map { it.settings.mode }.toSet()
        assertTrue("缺少浅色预设", AppThemeMode.LIGHT in modes)
        assertTrue("缺少深色预设", AppThemeMode.DARK in modes)
        assertTrue("缺少动态色预设", ThemePresets.ALL.any { it.settings.useDynamicColor })
    }

    @Test
    fun accentColorsAreOpaque() {
        ThemePresets.ACCENT_COLORS.forEach { color ->
            assertTrue("强调色必须不透明：${color.toUInt().toString(16)}", ((color ushr 24) and 0xFF) == 0xFF)
        }
        ThemePresets.ALL.forEach { preset ->
            assertTrue("预设强调色必须不透明：${preset.id}", ((preset.settings.accentColor ushr 24) and 0xFF) == 0xFF)
        }
    }

    @Test
    fun legacyMonetMatchesSystemDynamicPreset() {
        val legacy = AppThemeSettings(
            mode = AppThemeMode.MONET,
            useDynamicColor = true,
            accentColor = ThemePresets.WARM_ACCENT,
        )

        assertEquals("旧版动态色应命中「系统动态色」", "system-dynamic", ThemePresets.match(legacy)?.id)
    }

    @Test
    fun customSettingsDoNotMatchAnyPreset() {
        val custom = AppThemeSettings(
            mode = AppThemeMode.LIGHT,
            useDynamicColor = false,
            accentColor = 0xFF123456.toInt(),
        )

        assertNull(ThemePresets.match(custom))
    }
}

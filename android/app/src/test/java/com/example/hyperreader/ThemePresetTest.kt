package com.example.hyperreader

import com.example.hyperreader.settings.AppThemeMode
import com.example.hyperreader.settings.ThemePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ThemePresetTest {

    @Test
    fun hyperOsBlueWhiteIsLightFixedBlue() {
        val preset = ThemePresets.HYPEROS_BLUE_WHITE

        assertEquals(AppThemeMode.LIGHT, preset.mode)
        assertFalse(preset.useDynamicColor)
        assertEquals(ThemePresets.HYPEROS_BLUE, preset.accentColor)
    }
}

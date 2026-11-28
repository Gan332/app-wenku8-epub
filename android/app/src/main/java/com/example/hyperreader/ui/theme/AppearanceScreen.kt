package com.example.hyperreader.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hyperreader.settings.AppThemeMode
import com.example.hyperreader.settings.AppThemeSettings
import com.example.hyperreader.settings.ThemePreset
import com.example.hyperreader.settings.ThemePresets
import com.example.hyperreader.settings.normalized
import com.example.hyperreader.ui.ColorSwatch
import com.example.hyperreader.ui.PreferencePanel
import com.example.hyperreader.ui.PreferenceRowMargin
import com.example.hyperreader.ui.SectionTitle
import com.example.hyperreader.ui.SettingsScaffold
import com.example.hyperreader.ui.StudioFactRow
import com.example.hyperreader.ui.StudioPanel
import com.example.hyperreader.ui.StudioViewModel
import com.example.hyperreader.ui.UiDimens
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 设置 → 主题与外观（0.19.0 重构）。
 *
 * 旧版把「动态色」当成一种独立模式，又与「系统/浅色/深色」并列，用户无法同时选
 * 「深色 + 动态色」；快捷主题也只有 HyperOS 蓝白一项。现在拆成三层：
 *
 * 1. **预设**：一键套用「模式 + 动态色 + 强调色」三件套；
 * 2. **明暗模式**：只表达系统/浅色/深色，与取色方式正交；
 * 3. **取色**：动态取色开关 + 固定强调色色板/取色器。
 *
 * 所有颜色仍由 MiuiX 从 [ThemePresets] 的强调色生成，页面不维护第二套色表。
 */
@Composable
internal fun AppearanceSection(viewModel: StudioViewModel) {
    val theme by viewModel.appTheme.collectAsStateWithLifecycle(initialValue = AppThemeSettings())
    val settings = theme.normalized()
    val matched = ThemePresets.match(settings)
    val tokens = appThemeTokens()

    SettingsScaffold("主题与外观", viewModel::backSettings, listOf(
        { SectionTitle("主题预设") },
        {
            PresetRail(
                selectedId = matched?.id,
                onSelect = viewModel::applyThemePreset,
            )
        },
        { SectionTitle("明暗模式") },
        {
            PreferencePanel {
                AppThemeMode.selectable.forEach { mode ->
                    RadioButtonPreference(
                        title = mode.label(),
                        summary = mode.summary(),
                        selected = settings.mode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        insideMargin = PreferenceRowMargin,
                    )
                }
            }
        },
        { SectionTitle("取色") },
        {
            PreferencePanel {
                SwitchPreference(
                    title = "动态取色",
                    summary = "从壁纸生成整套配色；关闭后使用下方强调色",
                    checked = settings.useDynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                    insideMargin = PreferenceRowMargin,
                )
            }
        },
        {
            AccentPalette(
                selected = settings.accentColor,
                onPick = viewModel::setAccentColor,
            )
        },
        {
            Card(
                Modifier.fillMaxWidth(),
                cornerRadius = UiDimens.panelCorner,
                insideMargin = PaddingValues(UiDimens.spaceS),
            ) {
                ColorPicker(
                    color = Color(settings.accentColor),
                    onColorChanged = { viewModel.setAccentColor(it.toArgb()) },
                    modifier = Modifier.fillMaxWidth().height(UiDimens.colorPickerHeight),
                )
            }
        },
        { SectionTitle("当前外观") },
        {
            StudioPanel {
                StudioFactRow("预设", matched?.label ?: "自定义")
                StudioFactRow("明暗", settings.mode.label())
                StudioFactRow("取色", if (settings.useDynamicColor) "系统动态色" else "固定强调色")
                StudioFactRow("强调色", "#%06X".format(settings.accentColor and 0xFFFFFF))
                StudioFactRow(
                    "说明",
                    when {
                        settings.useDynamicColor -> "跟随壁纸生成配色，强调色仅作动态色不可用时的兜底"
                        tokens.isDark -> "深色底 + 固定强调色，夜间对比更克制"
                        else -> "浅色底 + 固定强调色，接近 HyperOS 默认观感"
                    },
                )
            }
        },
    ))
}

@Composable
private fun PresetRail(
    selectedId: String?,
    onSelect: (AppThemeSettings) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
        contentPadding = PaddingValues(horizontal = UiDimens.spaceXXS),
    ) {
        items(ThemePresets.ALL, key = { it.id }) { preset ->
            ThemePresetCard(
                preset = preset,
                selected = preset.id == selectedId,
                onClick = { onSelect(preset.settings) },
            )
        }
    }
}

/**
 * 预设卡：小尺寸配色预览 + 名称 + 一句说明。
 *
 * 预览只用当前主题的语义色与预设强调色，不硬编码任何成品配色；这样主题升级后
 * 卡片不会与真实界面脱节。选中态用强调色描边 + 对钩，不依赖额外的组件状态。
 */
@Composable
private fun ThemePresetCard(
    preset: ThemePreset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = appThemeTokens()
    val shape = RoundedCornerShape(UiDimens.panelCorner)
    Column(
        modifier = Modifier
            .width(UiDimens.themePresetWidth)
            .clip(shape)
            .background(tokens.chipSurface)
            .border(
                BorderStroke(
                    width = if (selected) UiDimens.hairline * 2f else UiDimens.hairline,
                    color = if (selected) tokens.accent else tokens.panelBorder,
                ),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(UiDimens.spaceS),
        verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS),
    ) {
        PresetPreview(preset)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                preset.label,
                modifier = Modifier.weight(1f),
                fontSize = UiDimens.bodyStrong,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                Text("✓", color = tokens.accent, fontSize = UiDimens.bodyStrong, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            preset.summary,
            fontSize = UiDimens.captionSmall,
            color = tokens.tertiaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PresetPreview(preset: ThemePreset) {
    val tokens = appThemeTokens()
    val accent = if (preset.settings.useDynamicColor) tokens.accent else Color(preset.settings.accentColor)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(UiDimens.themePreviewHeight)
            .clip(RoundedCornerShape(UiDimens.cardCorner))
            .background(tokens.navSurface),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(UiDimens.spaceS)
                .background(accent.copy(alpha = 0.22f)),
        )
        Column(
            modifier = Modifier.padding(UiDimens.spaceS),
            verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(UiDimens.spaceM)
                        .clip(CircleShape)
                        .background(accent),
                )
                Spacer(Modifier.width(UiDimens.spaceXS))
                Box(
                    Modifier
                        .weight(1f)
                        .height(UiDimens.spaceXS)
                        .clip(CircleShape)
                        .background(tokens.tertiaryText.copy(alpha = 0.5f)),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth(0.72f)
                    .height(UiDimens.spaceXS)
                    .clip(CircleShape)
                    .background(tokens.tertiaryText.copy(alpha = 0.28f)),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.5f)
                    .height(UiDimens.spaceXS)
                    .clip(CircleShape)
                    .background(tokens.tertiaryText.copy(alpha = 0.18f)),
            )
        }
    }
}

@Composable
private fun AccentPalette(
    selected: Int,
    onPick: (Int) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ThemePresets.ACCENT_COLORS.forEach { color ->
            ColorSwatch(
                color = color,
                selected = selected == color,
                onClick = { onPick(color) },
            )
        }
    }
}

private fun AppThemeMode.label(): String = when (this) {
    AppThemeMode.SYSTEM -> "跟随系统"
    AppThemeMode.LIGHT -> "浅色"
    AppThemeMode.DARK -> "深色"
    AppThemeMode.MONET -> "跟随系统"
}

private fun AppThemeMode.summary(): String = when (this) {
    AppThemeMode.SYSTEM -> "随系统切换明暗，最省心"
    AppThemeMode.LIGHT -> "始终使用浅色界面"
    AppThemeMode.DARK -> "始终使用深色界面"
    AppThemeMode.MONET -> "旧版动态色，已并入跟随系统"
}

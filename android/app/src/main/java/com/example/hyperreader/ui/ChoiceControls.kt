package com.example.hyperreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 单选项按钮：选中态用填充按钮，未选中态用文字按钮。
 *
 * 0.19.0 之前配色模式、直连入口、阅读背景、翻页方式等选项组都只是并排的
 * [TextButton]，用户无法看出当前选的是哪一个（只有旁边一行说明文字）。
 */
@Composable
internal fun ChoiceButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = UiDimens.touchMin),
        ) {
            Text(text)
        }
    } else {
        TextButton(
            text = text,
            onClick = onClick,
            modifier = modifier.heightIn(min = UiDimens.touchMin),
        )
    }
}

/**
 * 颜色圆点：满足 48dp 触摸区，选中时外圈高亮并显示对钩。
 *
 * 原先色块只有 36dp 且无选中态，既是触摸区不足（AGENTS §4.4），
 * 也无法判断自定义背景色/文字色当前是哪一种。
 */
@Composable
internal fun ColorSwatch(
    color: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val swatch = Color(color)
    val primary = MiuixTheme.colorScheme.primary
    val outline = MiuixTheme.colorScheme.outline.copy(alpha = 0.35f)
    Box(
        modifier = modifier
            .size(UiDimens.touchMin)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(UiDimens.swatch)
                .clip(CircleShape)
                .background(swatch)
                .border(
                    width = if (selected) 3.dp else UiDimens.hairline,
                    color = if (selected) primary else outline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Text(
                    "✓",
                    color = if (swatch.luminance() > 0.5f) Color.Black else Color.White,
                    fontSize = UiDimens.caption,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

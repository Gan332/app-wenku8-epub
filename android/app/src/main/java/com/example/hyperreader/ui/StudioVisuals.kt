package com.example.hyperreader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全局纸张背景（KernelSU 风格对齐）。
 *
 * 参考实现是**纯色平面**：背景不叠渐变，也不画主题色光晕，卡片与浮层是画面里唯一的高光。
 * 浅色下这能避免整屏泛蓝、正文与卡片边界发灰；暗色下也不会出现大块染色。
 */
@Composable
fun StudioBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface),
    ) {
        content()
    }
}

/**
 * 编辑式主视觉面板：小标签、醒目标题、说明与操作区。
 *
 * 参考实现的卡片一律是中性底：这里用 `surfaceContainer`，不再铺主色渐变或光晕；
 * 强调只留在 kicker 胶囊与标题字重上，浅色下正文对比度更稳定。
 */
@Composable
fun StudioHero(
    kicker: String,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(UiDimens.heroCorner)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainer)
            .border(BorderStroke(UiDimens.hairline, colors.outline.copy(alpha = 0.16f)), shape),
    ) {
        Column(
            modifier = Modifier.padding(UiDimens.spaceL),
            verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.primary.copy(alpha = 0.14f))
                    .padding(horizontal = UiDimens.spaceS, vertical = UiDimens.spaceXXS),
            ) {
                Text(
                    kicker,
                    color = colors.primary,
                    style = MiuixTheme.textStyles.footnote2,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                title,
                style = MiuixTheme.textStyles.title2,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            Text(
                description,
                style = MiuixTheme.textStyles.body2,
                color = secondaryTextColor(),
            )
            actions?.let {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
                    content = it,
                )
            }
        }
    }
}

/**
 * 统一的信息分组：标题 + 连续内容行。
 *
 * 设置二级页和详情页不再为每个字段套一张 Card；同一组信息共用一块浮层，
 * 行与行之间只靠间距和细分割线区分。0.19.0 起圆角与 Miuix Card 默认对齐（[UiDimens.panelCorner]），
 * 底色显式取 `surfaceContainer`，与页面 `surface` 背景形成稳定层级。
 */
@Composable
fun StudioPanel(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = UiDimens.panelCorner,
        insideMargin = PaddingValues(UiDimens.spaceM),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
        ),
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                title,
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Bold,
                color = tertiaryTextColor(),
            )
            Spacer(Modifier.size(UiDimens.spaceXXS))
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
            content = content,
        )
    }
}

/** 信息键值行：左侧标签，右侧值；用于详情字段与设置摘要。 */
@Composable
fun StudioFactRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = UiDimens.spaceXXS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.footnote1,
            color = tertiaryTextColor(),
        )
        Text(
            value,
            modifier = Modifier.weight(1.4f),
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

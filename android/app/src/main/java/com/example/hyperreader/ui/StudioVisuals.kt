package com.example.hyperreader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全局纸张背景。
 *
 * 只使用主题色和低透明度渐变，不引入位图或固定色值：亮色下像带暖调的纸，
 * 暗色下仍有层次；强调色变化时两个光斑会一起换色。
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
            .background(
                Brush.verticalGradient(
                    listOf(
                        colors.surface,
                        colors.background,
                        colors.surfaceVariant.copy(alpha = 0.72f),
                    ),
                ),
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(colors.primary.copy(alpha = 0.11f), Color.Transparent),
                    center = Offset(size.width * 0.92f, size.height * 0.04f),
                    radius = size.width * 0.78f,
                ),
                radius = size.width * 0.78f,
                center = Offset(size.width * 0.92f, size.height * 0.04f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(colors.secondaryContainer.copy(alpha = 0.12f), Color.Transparent),
                    center = Offset(size.width * 0.04f, size.height * 0.72f),
                    radius = size.width * 0.72f,
                ),
                radius = size.width * 0.72f,
                center = Offset(size.width * 0.04f, size.height * 0.72f),
            )
        }
        content()
    }
}

/**
 * 编辑式主视觉面板：小标签、醒目标题、说明与操作区。
 *
 * 与普通 Card 的差别是它承担「这一屏现在该做什么」的叙事，而不是承载一条设置或一本书。
 * 渐变、光斑与边框都来自主题色，暗色和动态色下自动收敛。
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
            .background(
                Brush.linearGradient(
                    listOf(
                        colors.primaryContainer.copy(alpha = 0.92f),
                        colors.surfaceContainerHigh.copy(alpha = 0.96f),
                    ),
                ),
            )
            .border(BorderStroke(UiDimens.hairline, colors.outline.copy(alpha = 0.18f)), shape)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(colors.primary.copy(alpha = 0.18f), Color.Transparent),
                        center = Offset(size.width * 0.88f, size.height * 0.12f),
                        radius = size.width * 0.55f,
                    ),
                    radius = size.width * 0.55f,
                    center = Offset(size.width * 0.88f, size.height * 0.12f),
                )
            },
    ) {
        Column(
            modifier = Modifier.padding(UiDimens.spaceL),
            verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
        ) {
            Text(
                kicker,
                color = colors.primary,
                fontSize = UiDimens.captionSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(title, fontSize = UiDimens.title, fontWeight = FontWeight.Bold)
            Text(description, color = secondaryTextColor(), fontSize = UiDimens.caption)
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
 * 行与行之间只靠间距和细分割线区分。
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
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                title,
                fontSize = UiDimens.captionSmall,
                fontWeight = FontWeight.Bold,
                color = secondaryTextColor(),
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
            fontSize = UiDimens.captionSmall,
            color = tertiaryTextColor(),
        )
        Text(
            value,
            modifier = Modifier.weight(1.4f),
            fontSize = UiDimens.body,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

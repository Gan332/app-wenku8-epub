package com.example.hyperreader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextAlign
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 共用状态呈现：加载中 / 空态 / 出错。
 *
 * 此前同一件事在全项目有 4~6 种写法：进度圈有带尺寸的、裸的、圆环加文字并排的；
 * 空态文案有「书架还是空的。」「这一栏暂时没有内容。」「这一榜暂时没有内容。」
 * 「还没有阅读记录。」等等，位置与字号各不相同。同一屏里甚至能同时出现
 * 骨架与转圈两套加载指示（探索详情页）。
 *
 * 抽到这里之后：**加载态一律转圈 + 可选说明文字，空态一律标题 + 可选说明 + 可选动作**，
 * 文案由调用方传入（语义不同，不在这里强行统一措辞），但版式与节奏统一。
 */
@Composable
fun LoadingBlock(text: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = UiDimens.touchMin)
            .padding(vertical = UiDimens.spaceXS),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(size = UiDimens.indicator)
        if (!text.isNullOrBlank()) {
            Text(
                text,
                modifier = Modifier.padding(start = UiDimens.spaceS),
                fontSize = UiDimens.caption,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * 空态：标题 + 可选说明 + 可选动作。
 *
 * 用 [AnimatedVisibility] 常驻而非 `if`：内容出现 / 消失时原地淡入淡出，
 * 下方列表不会整屏跳位。
 */
@Composable
fun EmptyState(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    // MutableTransitionState 而不是 visible = true：后者首帧已是 true，
    // 入场动画与 staggeredEnter 当初的失效是同一个坑（初值即终值）。
    val enterState = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = enterState,
        enter = fadeIn(tween(Motion.duration())) + scaleIn(tween(Motion.duration()), initialScale = 0.96f),
        exit = fadeOut(tween(Motion.duration(UiDimens.MOTION_FAST))) + scaleOut(tween(Motion.duration(UiDimens.MOTION_FAST)), targetScale = 0.96f),
        modifier = modifier,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = UiDimens.spaceXL),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
        ) {
            Text(
                title,
                fontSize = UiDimens.bodyStrong,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            if (!description.isNullOrBlank()) {
                Text(
                    description,
                    fontSize = UiDimens.caption,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction, modifier = Modifier.heightIn(min = UiDimens.touchMin)) {
                    Text(actionLabel)
                }
            }
        }
    }
}
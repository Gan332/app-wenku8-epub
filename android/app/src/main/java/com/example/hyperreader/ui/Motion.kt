package com.example.hyperreader.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import android.provider.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 动效基建（0.18.0）。
 *
 * 全部基于 Compose 原生动画 API（AGENTS §4.4：UI 唯一组件是 MiuiX，动画用标准 Compose）；
 * 不引入 `compose-animations` 等第三方依赖。设计约束：
 * - **尊重系统「移除动画」**：[scale] 读系统 `ANIMATOR_DURATION_SCALE`（0 = 关闭），时长随之归零；
 * - **长列表只对首屏可见项做延迟**（[staggeredDelay] 对超过 [STAGGER_VISIBLE_LIMIT] 的项不再累加，
 *   否则千条列表里靠后的项要等几秒才出现）；
 * - 交互动效只用 scale/alpha，不做位移，避免与页面转场叠加成眩晕。
 */
object Motion {
    /** 首屏之后的项不再累加延迟（只对约一屏半的内容做交错）。 */
    const val STAGGER_VISIBLE_LIMIT = 12

    /**
     * 系统动画缩放：0 = 关闭动画，1 = 正常，>1 = 加速（开发者选项）。
     *
     * 直接读 `Settings.Global.ANIMATOR_DURATION_SCALE`：不依赖 Compose 内部 API，
     * 系统「移除动画」与开发者选项的加速都能生效。
     */
    @Composable
    fun scale(): Float {
        val view = LocalView.current
        return remember(view) {
            runCatching {
                Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            }.getOrDefault(1f)
        }
    }

    /** 按系统缩放换算时长；关闭动画时返回 0。 */
    @Composable
    fun duration(base: Int = UiDimens.MOTION_MEDIUM): Int =
        if (scale() == 0f) 0 else (base * scale()).toInt()

    /** 交错延迟的纯逻辑（供单测）：系统缩放为0时无延迟；只有首屏附近的项累加步长。 */
    fun staggeredDelayFor(index: Int, scaleFactor: Float, step: Int = UiDimens.STAGGER_STEP): Int = when {
        scaleFactor == 0f -> 0
        index >= STAGGER_VISIBLE_LIMIT -> step
        else -> index * step
    }

    /** 交错延迟：只对前 [STAGGER_VISIBLE_LIMIT] 项累加步长。 */
    @Composable
    fun staggeredDelay(index: Int, step: Int = UiDimens.STAGGER_STEP): Int =
        staggeredDelayFor(index, scale(), step)
}

/**
 * 交错入场：按 [index] 延迟淡入 + 轻微放大（0.98 → 1）。
 *
 * 列表里配合 `itemsIndexed` 使用；[visible] 为 false 时反向收起。
 *
 * 用 [Animatable] 而非 `animateFloatAsState`：后者首次组合时初值就等于 targetValue，
 * 淡入与缩放根本不会播放（首帧直接是终值），交错入场等于没做。
 */
fun Modifier.staggeredEnter(index: Int, visible: Boolean = true): Modifier = composed {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(ENTER_SCALE_FROM) }
    LaunchedEffect(visible, index) {
        val delay = Motion.staggeredDelay(index)
        coroutineScope {
            // alpha 与 scale 同时推进：串行 await 会让缩放白白多等一个 alpha 时长
            launch {
                alpha.animateTo(
                    targetValue = if (visible) 1f else 0f,
                    animationSpec = tween(Motion.duration(), delay, FastOutSlowInEasing),
                )
            }
            launch {
                scale.animateTo(
                    targetValue = if (visible) 1f else ENTER_SCALE_FROM,
                    animationSpec = tween(Motion.duration(UiDimens.MOTION_SLOW), delay, FastOutSlowInEasing),
                )
            }
        }
    }
    graphicsLayer {
        this.alpha = alpha.value
        this.scaleX = scale.value
        this.scaleY = scale.value
    }
}

/** 交错入场的起始缩放（轻微放大入场，避免整屏元素同时「弹」出来）。 */
private const val ENTER_SCALE_FROM = 0.98f

/**
 * 按压反馈：按下 0.97 倍缩放，抬起回弹。
 *
 * **由调用方提供** [interactionSource]：使用 `Modifier.clickable(interactionSource, indication = null)`
 * 触发（这样不拆掉点击事件），再叠加本修饰符只看按压态。只改 scale，不做位移。
 */
fun Modifier.pressableScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        // 经 Motion.duration 换算：系统「移除动画」时按压缩放也要归零，
        // 否则它是全应用唯一还会动的交互反馈。
        animationSpec = tween(Motion.duration(UiDimens.MOTION_FAST), easing = FastOutSlowInEasing),
        label = "pressScale",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Shimmer 骨架行：加载中的占位条，宽度由调用方决定，高度 [heightDp]。
 *
 * 用 `onSurface` 的低透明度而不是固定灰，得以跟随亮/暗主题。
 */
@Composable
fun ShimmerLine(modifier: Modifier = Modifier, heightDp: Int = 16) {
    ShimmerBlock(
        modifier
            .fillMaxWidth()
            .height(heightDp.dp),
    )
}

/**
 * Shimmer 骨架块：尺寸完全由 [modifier] 决定。
 *
 * 骨架必须与真实内容同尺寸，否则数据到达时列表整体跳一屏——封面占位、
 * 方块这类非「整行」的骨架只能用这个。
 */
@Composable
fun ShimmerBlock(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = shimmerAlpha())),
    )
}

/**
 * Shimmer 骨架：加载中占位块的微光扫过（alpha 0.35 ↔ 0.7 往返）。
 *
 * 系统关闭动画时固定 0.5，不循环。
 */
@Composable
fun shimmerAlpha(): Float {
    if (Motion.scale() == 0f) return 0.5f
    val transition = rememberInfiniteTransition(label = "shimmer")
    val value by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(UiDimens.MOTION_SLOW * 2, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmerAlpha",
    )
    return value
}
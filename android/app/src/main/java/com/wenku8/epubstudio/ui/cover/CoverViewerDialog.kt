package com.wenku8.epubstudio.ui.cover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SuperDialog
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import kotlin.math.abs

/**
 * 封面全屏预览。缩放手势自行实现，不引入 panpf 等重依赖：
 * - 双指缩放 + 拖动
 * - 双击在 1x / 2.5x 之间切换
 * - 平移量按缩放比例夹紧，避免图片被拖出屏幕
 */
@Composable
fun CoverViewerDialog(
    url: String?,
    title: String,
    onDismiss: () -> Unit,
) {
    val repository = rememberCoverRepository()
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        bitmap = repository.cached(url) ?: repository.load(url)
        failed = bitmap == null
    }

    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    fun clampOffset(candidate: Offset, currentScale: Float) = Offset(
        x = candidate.x.coerceIn(-MAX_PAN, MAX_PAN) * currentScale.coerceAtLeast(1f),
        y = candidate.y.coerceIn(-MAX_PAN, MAX_PAN) * currentScale.coerceAtLeast(1f),
    )

    BackHandler(onBack = onDismiss)

    SuperDialog(show = true, title = title, onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            when {
                image != null -> Image(
                    bitmap = image,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(url) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val next = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                                scale = next
                                offset = clampOffset(offset + pan, next)
                            }
                        }
                        .pointerInput(url) {
                            detectTapGestures(
                                onDoubleTap = { if (abs(scale - 1f) < 0.01f) scale = 2.5f else reset() },
                            )
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                )
                failed -> MiuixText("封面加载失败", color = Color.White, fontSize = 14.sp)
                else -> MiuixText("正在加载封面…", color = Color.White, fontSize = 14.sp)
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(48.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "关闭")
            }
        }
    }
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val MAX_PAN = 2000f

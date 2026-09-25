package com.wenku8.epubstudio.ui.cover

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wenku8.epubstudio.Wenku8Application
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun rememberCoverRepository(): CoverRepository {
    val context = LocalContext.current
    return remember { (context.applicationContext as Wenku8Application).coverRepository }
}

/**
 * 网络封面。加载中与失败都显示主题色占位，不阻塞页面。
 * @param targetWidthDp 下采样目标宽度，<=0 表示按原图加载。
 */
@Composable
fun CoverImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    targetWidthDp: Int = 0,
) {
    val repository = rememberCoverRepository()
    val density = LocalDensity.current
    val targetPx = if (targetWidthDp > 0) with(density) { targetWidthDp.dp.roundToPx() } else 0

    var bitmap by remember(url, targetPx) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url, targetPx) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        val cached = repository.cached(url)
        if (cached != null) {
            bitmap = cached
            failed = false
            return@LaunchedEffect
        }
        bitmap = null
        val loaded = repository.load(url, targetPx)
        bitmap = loaded
        failed = loaded == null
    }

    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(MiuixTheme.colorScheme.background).padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            MiuixText(
                text = if (failed) "无封面" else "加载中",
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

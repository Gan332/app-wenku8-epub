package com.example.hyperreader.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.hyperreader.Wenku8Application

/**
 * 在线正文插图的加载入口。
 *
 * 刻意**不引入 Coil/Glide**（AGENTS.md 4.6）：第三方图片库会自建 OkHttp 客户端，
 * 从而绕过 [com.example.hyperreader.core.Wenku8HttpClient] 的全局 1 秒限流与 HTTP 429 退避，
 * 直接把请求打到 img.wenku8.com。这里复用已有的 [com.example.hyperreader.ui.cover.CoverRepository]
 * ——它已经具备「内存 LruCache + 磁盘缓存 + 按宽度下采样 + 走限流客户端」的全部能力，
 * 再造一份只会让缓存预算翻倍并有漂移风险。
 *
 * 加载顺序：内存/磁盘缓存 → 网络。命中缓存时不发任何请求，因此离线也能看到已读过的插图。
 */
@Composable
fun rememberRemoteImage(
    url: String?,
    targetWidth: Dp = 0.dp,
): ImageBitmap? {
    val context = LocalContext.current
    val repository = remember(context) {
        (context.applicationContext as? Wenku8Application)?.coverRepository
    } ?: return null
    val density = LocalDensity.current
    val targetWidthPx = remember(targetWidth, density) {
        if (targetWidth.value <= 0f) 0 else with(density) { targetWidth.roundToPx() }
    }
    val image by produceState<ImageBitmap?>(initialValue = null, url, targetWidthPx) {
        if (url.isNullOrBlank()) {
            value = null
            return@produceState
        }
        // 先查缓存（不发网络），未命中再走限流客户端下载。
        value = repository.cached(url) ?: repository.load(url, targetWidthPx)
    }
    return image
}

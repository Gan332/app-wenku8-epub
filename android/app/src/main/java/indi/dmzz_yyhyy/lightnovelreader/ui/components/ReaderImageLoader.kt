/*
 * Copyright (C) 2025 走路 simply
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package indi.dmzz_yyhyy.lightnovelreader.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.example.hyperreader.Wenku8Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 正文插图的三态，与本工程既有 `ReaderImage` 保持一致（0.9.x 起沿用）。 */
sealed interface LnrReaderImage {
    data object Loading : LnrReaderImage
    data object Failed : LnrReaderImage
    data class Ready(val bitmap: ImageBitmap) : LnrReaderImage
}

/**
 * LNR 引入适配：正文插图加载入口，**不引入 Coil / panpf**（AGENTS.md 4.7）。
 *
 * 上游 `ZoomableImage` 用 `coil3.compose.SubcomposeAsyncImage` + Coil transformations
 * + `hiltViewModel<ImageTransPostProcessingViewModel>()`；`ImageViewerScreen` 用
 * `coil3` 的 `CoilZoomAsyncImage`（panpf）。三者都要砍掉：
 * - Coil 会自建 OkHttp 客户端，绕过 Wenku8HttpClient 的全局限流与 429 退避；
 * - panpf 只为缩放手势，而本工程 CoverViewerDialog 已经用
 *   `detectTransformGestures` 自行实现（4.7 明确「不引入 panpf」）。
 *
 * 这里把加载分成两类源，**图片字节永不绕过限流**：
 * - `http(s)://` → 复用 `CoverRepository`（内存 LruCache + 磁盘 + 按宽度下采样 +
 *   走限流客户端下载），与封面、在线插图同一条路径；
 * - 本地路径（`file://` 或裸路径）→ 直接 `BitmapFactory` 解码，EPUB 解包出的图片
 *   与导入字体都走这条，不产生任何网络请求。
 *
 * EPUB zip 内部条目（`ReaderBlock.Image.path` 尚未解包成文件的情形）由 P3 的
 * `ReaderBook → ContentBuilder` 桥接层负责先解出字节再落到缓存目录，之后同样
 * 命中上面的本地分支。
 */
@Composable
fun rememberLnrReaderImage(
    uri: Uri?,
    targetWidth: Dp = 0.dp,
): LnrReaderImage {
    val context = LocalContext.current
    val density = LocalDensity.current
    val targetWidthPx = remember(targetWidth, density) {
        if (targetWidth.value <= 0f) 0 else with(density) { targetWidth.roundToPx() }
    }
    val repository = remember(context) {
        (context.applicationContext as? Wenku8Application)?.coverRepository
    }

    val image by produceState<LnrReaderImage>(
        initialValue = LnrReaderImage.Loading,
        uri,
        targetWidthPx,
    ) {
        if (uri == null) {
            value = LnrReaderImage.Failed
            return@produceState
        }
        val raw = uri.toString().trim()
        if (raw.isBlank()) {
            value = LnrReaderImage.Failed
            return@produceState
        }
        value = if (raw.startsWith("http://") || raw.startsWith("https://")) {
            val repo = repository
            if (repo == null) {
                LnrReaderImage.Failed
            } else {
                runCatching { repo.cached(raw) ?: repo.load(raw, targetWidthPx) }
                    .getOrNull()
                    ?.let { LnrReaderImage.Ready(it) }
                    ?: LnrReaderImage.Failed
            }
        } else {
            withContext(Dispatchers.IO) {
                decodeLocal(raw, targetWidthPx)?.let { LnrReaderImage.Ready(it) }
                    ?: LnrReaderImage.Failed
            }
        }
    }
    return image
}

/**
 * 本地图片解码，行为对齐 CoverRepository 的下采样：先只读 bounds 算
 * `inSampleSize`，再按 2 的幂次缩小，避免长图插图直接 OOM。
 */
private fun decodeLocal(raw: String, targetWidthPx: Int): ImageBitmap? {
    val path = raw.removePrefix("file://")
    val file = java.io.File(path)
    if (!file.isFile) return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    if (targetWidthPx > 0 && bounds.outWidth > targetWidthPx) {
        while (bounds.outWidth / (sample * 2) >= targetWidthPx) sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching {
        BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
    }.getOrNull()
}

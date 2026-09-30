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

import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.hyperreader.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * LNR 引入适配：`ZoomableImage`。
 *
 * 与上游的差异，全部来自 AGENTS.md 的硬约束：
 * 1. 上游用 `SubcomposeAsyncImage`(Coil3) + Coil transformations + Hilt 的
 *    `ImageTransPostProcessingViewModel`。本工程 4.7 禁止 Coil/panpf（图片字节
 *    不得绕过全局限流），改为 [rememberLnrReaderImage] 走 CoverRepository。
 * 2. 上游的 `header: Map<String,String>`（Referer 伪造）是为了骗过防盗链。
 *    本工程的 [com.example.hyperreader.core.Wenku8HttpClient.downloadImage] 内部
 *    已固定带上 wenku8 Referer（CoverRepository 里传 REFERER 常量），因此这个
 *    参数没有存在意义，删掉而不是留一个不生效的形参。
 * 3. material3 Button/Icon/Text → MiuiX 同名组件（4.4 守卫）。
 *
 * 交互语义**逐条保留**上游行为，这是 LNR 正文的既定体验：
 *  - 长按 ≥380ms 且未位移 → 全屏查看，并消费事件避免同时触发沉浸模式切换
 *  - 双指轻点 ≤280ms 且未位移 → 全屏查看
 */
@Composable
fun ZoomableImage(
    imageUri: Uri,
    modifier: Modifier = Modifier,
    onViewImage: () -> Unit,
    placeholderHeight: Dp = 200.dp,
) {
    var retryKey by remember { mutableIntStateOf(0) }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    // retryKey 进 produceState keys：点「重试」才会真正重新加载
    val image = rememberLnrReaderImage(imageUri, screenWidth, retryKey)

    Box(
        modifier = modifier
            .animateContentSize()
            .fillMaxWidth()
            .heightIn(min = placeholderHeight),
        contentAlignment = Alignment.Center,
    ) {
        when (image) {
            is LnrReaderImage.Loading -> Box(
                modifier = Modifier
                    .height(placeholderHeight)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Loading()
            }

            is LnrReaderImage.Failed -> Column(
                modifier = Modifier
                    .height(placeholderHeight)
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    modifier = Modifier.size(36.dp),
                    painter = painterResource(R.drawable.release_alert_24px),
                    tint = MiuixTheme.colorScheme.error,
                    contentDescription = null,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "插图加载失败",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { retryKey++ }) {
                    Text("重试")
                }
            }

            is LnrReaderImage.Ready -> Image(
                bitmap = image.bitmap,
                contentDescription = "",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(onViewImage) {
                        awaitPointerEventScope {
                            val longPressMillis = 380L
                            val twoFingerTapMaxMillis = 280L
                            val slop = viewConfiguration.touchSlop

                            while (true) {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val t0 = down.uptimeMillis
                                var twoFingerClick = false
                                val startPos = linkedMapOf(down.id to down.position)
                                var maxMove = 0f

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressedChanges = event.changes.filter { it.pressed }

                                    pressedChanges.forEach { ch ->
                                        if (!startPos.containsKey(ch.id)) {
                                            startPos[ch.id] = ch.position
                                        }
                                        val sp = startPos[ch.id]!!
                                        val dx = ch.position.x - sp.x
                                        val dy = ch.position.y - sp.y
                                        val dist = kotlin.math.hypot(dx, dy)
                                        if (dist > maxMove) maxMove = dist
                                    }

                                    if (!twoFingerClick && pressedChanges.size >= 2) {
                                        twoFingerClick = true
                                    }

                                    if (!twoFingerClick) {
                                        val now = event.changes.firstOrNull { it.id == down.id }
                                            ?.uptimeMillis ?: down.uptimeMillis
                                        val elapsed = now - t0
                                        if (elapsed >= longPressMillis && maxMove <= slop) {
                                            event.changes.forEach { it.consume() }
                                            onViewImage()
                                            break
                                        }
                                    }

                                    if (pressedChanges.isEmpty()) {
                                        if (twoFingerClick) {
                                            val elapsed = (event.changes.maxOfOrNull {
                                                it.uptimeMillis
                                            } ?: t0) - t0
                                            if (elapsed <= twoFingerTapMaxMillis && maxMove <= slop) {
                                                event.changes.forEach { it.consume() }
                                                onViewImage()
                                            }
                                        }
                                        break
                                    }
                                }
                            }
                        }
                    },
            )
        }
    }
}

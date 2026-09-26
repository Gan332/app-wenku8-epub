package com.example.hyperreader.reader

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 正文插图的三态。
 *
 * 之前 resolver 返回 `ImageBitmap?`，**加载中与失败都是 null**，
 * 失败（路径解码不中、OOM、网络错）会静默变成一块空白，
 * 用户无法区分「还没加载」与「永远不会出来」。
 */
sealed interface ReaderImage {
    /** 正在加载。 */
    data object Loading : ReaderImage

    /** 加载成功。 */
    data class Ready(val bitmap: ImageBitmap) : ReaderImage

    /** 加载失败：界面显示占位框，而不是隐形空白。 */
    data object Failed : ReaderImage
}

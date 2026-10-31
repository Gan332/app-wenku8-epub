package com.example.hyperreader.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi

/**
 * 长列表的统一滚动条。
 *
 * 0.19.0 之前只有书架页挂了 `VerticalScrollBar`，阅读首页 / 探索 / 搜索 / 设置 /
 * 书签 / 书籍详情这些同样会超过一屏的页面都没有滚动提示：用户滑到一半时不知道
 * 还剩多少，平板与大屏上尤其明显。这里把「滚动适配器 + 实验 API opt-in」收成一处，
 * 页面只写一行 [AppScrollBar]。
 *
 * 用法：与 `LazyColumn` 放在同一个 `Box` 中，并让列表 `fillMaxSize()`；
 * 滚动条本身不占布局宽度，不改变列表内容区尺寸。
 */
@OptIn(ExperimentalScrollBarApi::class)
@Composable
internal fun AppScrollBar(
    state: LazyListState,
    modifier: Modifier = Modifier.fillMaxHeight(),
) {
    VerticalScrollBar(
        adapter = rememberScrollBarAdapter(state),
        modifier = modifier,
    )
}

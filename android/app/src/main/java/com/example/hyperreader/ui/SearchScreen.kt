package com.example.hyperreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.example.hyperreader.MessageCard
import com.example.hyperreader.model.SearchBook
import com.example.hyperreader.model.SearchField

@Composable
fun SearchScreen(
    state: StudioUiState,
    viewModel: StudioViewModel,
    onLogin: () -> Unit,
    /** 已由顶栏返回图标与系统返回键接管（0.19.0-alpha05）；保留参数只为兼容旧调用点。 */
    onClose: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(top = UiDimens.spaceL), verticalArrangement = Arrangement.spacedBy(UiDimens.spaceM)) {
        // 搜索是独立全屏页：页名与返回都由顶栏给（PageChrome，0.19.0-alpha05），
        // 页内不再画同名大标题或「‹ 返回探索」文字按钮。
        Text(
            "按书名或作者搜索；本地索引在断网时仍可用。",
            fontSize = UiDimens.caption,
            color = secondaryTextColor(),
        )
        if (!state.loggedIn) {
            Card(
                Modifier.fillMaxWidth(),
                cornerRadius = UiDimens.panelCorner,
                insideMargin = PaddingValues(UiDimens.spaceM),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("搜索需要 wenku8 登录", fontSize = UiDimens.body)
                    TextButton(text = "登录", onClick = onLogin)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
            TextButton(text = "按书名", onClick = { viewModel.setSearchField(SearchField.TITLE) })
            TextButton(text = "按作者", onClick = { viewModel.setSearchField(SearchField.AUTHOR) })
        }
        // MiuiX SearchBar 容器：输入框常驻、历史词作为展开内容。
        // 初始展开 = 与旧布局等价（历史词常驻），即使不触发收起也不产生回归。
        var searchExpanded by remember { mutableStateOf(true) }
        SearchBar(
            inputField = {
                TextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    label = if (state.searchField == SearchField.TITLE) "搜索书名" else "搜索作者",
                    useLabelAsPlaceholder = true,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (!state.searchBusy && state.searchQuery.isNotBlank()) {
                                viewModel.searchBooks()
                            }
                        },
                    ),
                )
            },
            expanded = searchExpanded,
            onExpandedChange = { searchExpanded = it },
            modifier = Modifier.fillMaxWidth(),
            content = {
                if (state.searchHistory.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("最近搜索", fontSize = UiDimens.caption, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
                        TextButton(text = "清空", onClick = viewModel::clearSearchHistory)
                    }
                    // 历史词是可变长度内容：普通 Row 装不下第 3 个词就会把后面的裁掉，
                    // 改成横向滚动，保证每个历史词都能完整看到并点到。
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                        // 不传 key：历史词理论上已去重，但旧版本可能留下重复项，
                        // 重复 key 会让 LazyRow 直接崩溃。
                        items(state.searchHistory.take(5)) { keyword ->
                            TextButton(text = keyword, onClick = { viewModel.setSearchQuery(keyword) })
                        }
                    }
                }
            },
        )
        Button(onClick = viewModel::searchBooks, enabled = !state.searchBusy && state.searchQuery.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (state.searchBusy) "搜索中…" else "搜索")
        }
        state.searchMessage?.let { MessageCard(it) }
        if (state.searchResults.isEmpty() && state.searchBusy) {
            // 原先这里只有一个无提示的孤立转圈，全项目唯一没有说明文字的加载态
            LoadingBlock("正在搜索…")
        } else if (state.searchResults.isEmpty() && !state.searchBusy) {
            if (state.searchPage > 0) {
                EmptyState(title = "没有找到结果", description = "换个关键词，或去掉作者限定再试一次。")
            } else {
                EmptyState(title = "输入关键词开始搜索", description = "支持书名与作者，按书名搜索走本地书目索引，断网也能用。")
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                items(state.searchResults, key = { it.id }) { result ->
                    SearchResultCard(
                        result,
                        viewModel::openSearchBook,
                        Modifier.animateItem(
                            fadeInSpec = tween(Motion.duration()),
                            placementSpec = tween(Motion.duration()),
                            fadeOutSpec = tween(Motion.duration(UiDimens.MOTION_FAST)),
                        ),
                    )
                }
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = UiDimens.spaceXS),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
                    ) {
                        if (state.searchHasNextPage) {
                            Button(
                                onClick = viewModel::loadMoreSearchResults,
                                enabled = !state.searchLoadingMore && !state.searchBusy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                            ) {
                                if (state.searchLoadingMore) {
                                    Text("加载中…")
                                } else {
                                    Text("加载更多")
                                }
                            }
                            if (state.searchLoadingMore) CircularProgressIndicator(size = UiDimens.indicator)
                        } else if (state.searchPage > 0) {
                            Text(
                                "已加载第 ${state.searchPage} 页",
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f),
                                fontSize = UiDimens.caption,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(result: SearchBook, onOpen: (SearchBook) -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    // 整卡可点 + 按压缩放：原先整卡既不可点也无反馈，只有卡内一个按钮，
    // 点大片留白毫无反应，与书架/探索/设置三张卡的交互语义不一致。
    Card(
        modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onOpen(result) },
            )
            .pressableScale(interaction),
        cornerRadius = UiDimens.panelCorner,
        insideMargin = PaddingValues(UiDimens.spaceM),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS)) {
            Text(result.title, fontSize = UiDimens.section, fontWeight = FontWeight.Bold, maxLines = 2)
            if (result.author.isNotBlank()) Text("作者：${result.author}", fontSize = UiDimens.caption)
            val meta = buildList {
                if (result.category.isNotBlank()) add(result.category)
                if (result.status.isNotBlank()) add(result.status)
                if (result.updatedAt.isNotBlank()) add("更新 ${result.updatedAt}")
                if (result.wordCount != null) add("${result.wordCount} 字")
            }
            Text(meta.joinToString(" · "), color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = UiDimens.captionSmall)
            if (result.latestChapter.isNotBlank()) Text("最新：${result.latestChapter}", fontSize = UiDimens.captionSmall, maxLines = 1)
        }
    }
}

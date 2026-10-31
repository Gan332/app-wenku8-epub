package com.example.hyperreader.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import com.example.hyperreader.MessageCard
import com.example.hyperreader.model.SearchBook
import com.example.hyperreader.model.SearchField
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SearchScreen(
    state: StudioUiState,
    viewModel: StudioViewModel,
    onLogin: () -> Unit,
    /** 已由顶栏返回图标与系统返回键接管（0.19.0-alpha05）；保留参数只为兼容旧调用点。 */
    onClose: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = UiDimens.spaceL),
        verticalArrangement = Arrangement.spacedBy(UiDimens.spaceM),
    ) {
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
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("搜索需要 wenku8 登录", fontSize = UiDimens.body)
                    TextButton(text = "登录", onClick = onLogin)
                }
            }
        }
        // 字段切换用 TabRow：两个平铺 TextButton 没有选中态，用户看不出当前按哪个字段搜。
        TabRow(
            tabs = listOf("按书名", "按作者"),
            selectedTabIndex = if (state.searchField == SearchField.TITLE) 0 else 1,
            onTabSelected = { index ->
                viewModel.setSearchField(if (index == 0) SearchField.TITLE else SearchField.AUTHOR)
            },
            modifier = Modifier.fillMaxWidth(),
            height = UiDimens.touchMin,
        )
        // MiuiX SearchBar 容器：输入框常驻、历史词作为展开内容。
        var searchExpanded by remember { mutableStateOf(true) }
        val hasHistory = state.searchHistory.isNotEmpty()
        // 搜出结果后自动收起历史词，把屏幕让给结果；清空关键词再展开。
        LaunchedEffect(state.searchResults.isNotEmpty()) {
            if (state.searchResults.isNotEmpty()) searchExpanded = false
        }
        LaunchedEffect(state.searchQuery.isBlank()) {
            if (state.searchQuery.isBlank()) searchExpanded = true
        }
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
                    // 搜索动作收进输入框尾部：原先输入框下方还有一整行「搜索」按钮，
                    // 与键盘的搜索键重复，也白占一行高度。
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (!state.searchBusy && state.searchQuery.isNotBlank()) viewModel.searchBooks()
                            },
                            modifier = Modifier.size(UiDimens.touchMin),
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Search,
                                contentDescription = "搜索",
                                tint = if (state.searchQuery.isNotBlank()) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    tertiaryTextColor()
                                },
                            )
                        }
                    },
                )
            },
            expanded = searchExpanded && hasHistory,
            onExpandedChange = { searchExpanded = it },
            modifier = Modifier.fillMaxWidth(),
            content = {
                if (hasHistory) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("最近搜索", fontSize = UiDimens.caption, color = secondaryTextColor())
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
            val listState = rememberLazyListState()
            // 结果数量放在列表顶上：翻到第 2 页后仍能一眼看到总量，不必回滚确认。
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceXS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "找到 ${state.searchResults.size} 本",
                    modifier = Modifier.weight(1f),
                    fontSize = UiDimens.caption,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (state.searchHasNextPage) "还有更多" else "已全部加载",
                    fontSize = UiDimens.captionSmall,
                    color = secondaryTextColor(),
                )
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = UiDimens.spaceXL),
                    verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
                ) {
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
                                    color = secondaryTextColor(),
                                    fontSize = UiDimens.caption,
                                )
                            }
                        }
                    }
                }
                AppScrollBar(listState)
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
            Text(
                result.title,
                fontSize = UiDimens.section,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (result.author.isNotBlank()) {
                Text(
                    "作者：${result.author}",
                    fontSize = UiDimens.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val meta = buildList {
                if (result.category.isNotBlank()) add(result.category)
                if (result.status.isNotBlank()) add(result.status)
                if (result.updatedAt.isNotBlank()) add("更新 ${result.updatedAt}")
                if (result.wordCount != null) add("${result.wordCount} 字")
            }
            Text(
                meta.joinToString(" · "),
                color = secondaryTextColor(),
                fontSize = UiDimens.captionSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (result.latestChapter.isNotBlank()) {
                Text(
                    "最新：${result.latestChapter}",
                    fontSize = UiDimens.captionSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

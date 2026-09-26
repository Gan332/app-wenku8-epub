package com.example.hyperreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.example.hyperreader.MessageCard
import com.example.hyperreader.model.SearchBook
import com.example.hyperreader.model.SearchField

@Composable
fun SearchScreen(state: StudioUiState, viewModel: StudioViewModel, onLogin: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("搜索轻小说", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("登录 wenku8 后按书名或作者搜索。", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 13.sp)
        if (!state.loggedIn) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("搜索需要 wenku8 登录", fontSize = 14.sp)
                    TextButton(text = "登录", onClick = onLogin)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(text = "按书名", onClick = { viewModel.setSearchField(SearchField.TITLE) })
            TextButton(text = "按作者", onClick = { viewModel.setSearchField(SearchField.AUTHOR) })
        }
        TextField(
            value = state.searchQuery,
            onValueChange = viewModel::setSearchQuery,
            label = if (state.searchField == SearchField.TITLE) "搜索书名" else "搜索作者",
            useLabelAsPlaceholder = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = viewModel::searchBooks, enabled = !state.searchBusy && state.searchQuery.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (state.searchBusy) "搜索中…" else "搜索")
        }
        if (state.searchHistory.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("最近搜索", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
                TextButton(text = "清空", onClick = viewModel::clearSearchHistory)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.searchHistory.take(5).forEach { keyword -> TextButton(text = keyword, onClick = { viewModel.setSearchQuery(keyword) }) }
            }
        }
        state.searchMessage?.let { MessageCard(it) }
        if (state.searchResults.isEmpty() && !state.searchBusy) {
            Text("输入关键词开始搜索。", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 14.sp)
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.searchResults, key = { it.id }) { result -> SearchResultCard(result, viewModel::openSearchBook) }
            }
        }
    }
}

@Composable
private fun SearchResultCard(result: SearchBook, onOpen: (SearchBook) -> Unit) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(result.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            if (result.author.isNotBlank()) Text("作者：${result.author}", fontSize = 13.sp)
            val meta = buildList {
                if (result.category.isNotBlank()) add(result.category)
                if (result.status.isNotBlank()) add(result.status)
                if (result.updatedAt.isNotBlank()) add("更新 ${result.updatedAt}")
                if (result.wordCount != null) add("${result.wordCount} 字")
            }
            Text(meta.joinToString(" · "), color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 12.sp)
            if (result.latestChapter.isNotBlank()) Text("最新：${result.latestChapter}", fontSize = 12.sp, maxLines = 1)
            TextButton(text = "查看详情与目录", onClick = { onOpen(result) })
        }
    }
}

package com.example.hyperreader.core

import com.example.hyperreader.model.SearchBook

data class ExplorePage(
    val id: String,
    val title: String,
    val url: String,
    val requiresAuth: Boolean = true,
)

data class ExploreBooksRow(
    val title: String,
    val books: List<SearchBook>,
    val expandedPageId: String? = null,
)

interface NovelDataSource {
    val id: String
    val displayName: String
    suspend fun explore(page: ExplorePage): List<SearchBook>

    /**
     * 官方标签列表（登录墙内的可选增强）。匿名场景返回空列表，
     * 由调用方回落到本地书目索引；wenku8 实现走用户自己的会话。
     */
    suspend fun officialTags(): List<String> = emptyList()

    /** 单个官方标签下的书（登录墙内）。同上：匿名源返回空，调用方兜底。 */
    suspend fun tagBooks(tag: String): List<SearchBook> = emptyList()
}

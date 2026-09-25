package com.wenku8.epubstudio.core

import com.wenku8.epubstudio.model.SearchBook

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
}

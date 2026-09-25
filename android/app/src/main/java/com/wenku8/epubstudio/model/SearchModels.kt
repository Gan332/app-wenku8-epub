package com.wenku8.epubstudio.model

import kotlinx.serialization.Serializable

@Serializable
enum class SearchField { TITLE, AUTHOR }

@Serializable
data class SearchBook(
    val id: String,
    val title: String,
    val author: String = "",
    val category: String = "",
    val status: String = "",
    val updatedAt: String = "",
    val wordCount: Long? = null,
    val coverUrl: String? = null,
    val latestChapter: String = "",
    val sourceUrl: String,
)

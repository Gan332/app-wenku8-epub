package com.example.hyperreader.core

import kotlinx.serialization.Serializable

@Serializable
data class CatalogEntry(
    val id: String,
    val title: String,
    val author: String = "",
    val category: String = "",
    val status: String = "",
    val wordCount: Long? = null,
    val updatedAt: String = "",
    val tags: List<String> = emptyList(),
    val summary: String = "",
    val coverUrl: String? = null,
    val sourceUrl: String,
    val firstSeenAt: Long = 0L,
)

@Serializable
data class CatalogSeedRef(
    val bookId: String,
    val title: String = "",
    val listName: String = "",
)

enum class SeedKind { SUGOI, BOOKLIST, SEED_ID }

data class CatalogSeed(
    val kind: SeedKind,
    val url: String,
    val name: String,
    val bookIds: List<String> = emptyList(),
)

@Serializable
data class CatalogCursor(
    val processedIds: List<String> = emptyList(),
    val queue: List<String> = emptyList(),
    val lastRunAt: Long = 0L,
    val totalFetched: Int = 0,
    val skipped: Int = 0,
)

@Serializable
data class CatalogStats(
    val count: Int = 0,
    val lastUpdatedAt: Long = 0L,
    val totalFetched: Int = 0,
    val skipped: Int = 0,
)

@Serializable
data class BookLink(
    val id: String,
    val title: String = "",
)

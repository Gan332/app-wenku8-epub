package com.example.hyperreader.reader

import kotlinx.serialization.Serializable

@Serializable
data class ReaderManifestItem(
    val id: String,
    val href: String,
    val mediaType: String = "",
    val properties: String = "",
)

@Serializable
sealed class ReaderBlock {
    @Serializable
    data class Heading(val level: Int, val text: String) : ReaderBlock()

    @Serializable
    data class Paragraph(val text: String) : ReaderBlock()

    @Serializable
    data class Image(val path: String, val alt: String = "") : ReaderBlock()
}

@Serializable
data class ReaderChapter(
    val id: String,
    val title: String,
    val href: String,
    val blocks: List<ReaderBlock> = emptyList(),
)

@Serializable
data class ReaderBook(
    val id: String,
    val title: String,
    val author: String = "",
    val language: String = "zh-CN",
    val chapters: List<ReaderChapter> = emptyList(),
    val archivePath: String = "",
)

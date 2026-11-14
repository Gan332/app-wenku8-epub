package com.example.hyperreader

import com.example.hyperreader.model.BookshelfEntry
import com.example.hyperreader.model.MAX_SHELF_TAG_LENGTH
import com.example.hyperreader.model.MAX_SHELF_TAGS_PER_BOOK
import com.example.hyperreader.model.buildShelfTagIndex
import com.example.hyperreader.model.matchesShelfQuery
import com.example.hyperreader.model.matchesShelfTag
import com.example.hyperreader.model.normalizeShelfTag
import com.example.hyperreader.model.normalizeShelfTags
import com.example.hyperreader.model.withShelfTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfTagsTest {

    private fun entry(
        id: String,
        title: String = "书 $id",
        author: String = "作者 $id",
        tags: List<String> = emptyList(),
    ) = BookshelfEntry(id = id, bookId = "book-$id", title = title, author = author, tags = tags)

    @Test
    fun normalizeStripsHashAndCollapsesWhitespace() {
        assertEquals("战斗", normalizeShelfTag("  #战斗  "))
        assertEquals("科幻 冒险", normalizeShelfTag("科幻\t 冒险"))
        assertEquals("tag", normalizeShelfTag("＃  tag"))
    }

    @Test
    fun blankTagsBecomeNull() {
        assertNull(normalizeShelfTag(""))
        assertNull(normalizeShelfTag("   "))
        assertNull(normalizeShelfTag("###"))
    }

    @Test
    fun tagsAreTrimmedToMaxLength() {
        val normalized = normalizeShelfTag("x".repeat(MAX_SHELF_TAG_LENGTH + 10))
        assertEquals(MAX_SHELF_TAG_LENGTH, normalized?.length)
    }

    @Test
    fun normalizeDeduplicatesCaseInsensitivelyAndCapsCount() {
        assertEquals(
            listOf("Fantasy", "日常"),
            normalizeShelfTags(listOf("Fantasy", " fantasy ", "#日常", "日常")),
        )
        val many = (1..MAX_SHELF_TAGS_PER_BOOK + 5).map { "tag$it" }
        assertEquals(MAX_SHELF_TAGS_PER_BOOK, normalizeShelfTags(many).size)
    }

    @Test
    fun indexCountsEachBookOnceAndSortsByUsage() {
        val index = buildShelfTagIndex(
            listOf(
                entry("a", tags = listOf("战斗", "日常")),
                entry("b", tags = listOf("战斗")),
                entry("c", tags = listOf("治愈")),
                entry("d", tags = listOf("日常")),
            ),
        )
        assertEquals(listOf("战斗", "日常", "治愈"), index.map { it.name })
        assertEquals(listOf(2, 2, 1), index.map { it.count })
    }

    @Test
    fun indexMergesTagsWithDifferentCase() {
        val index = buildShelfTagIndex(
            listOf(
                entry("a", tags = listOf("Fantasy")),
                entry("b", tags = listOf("fantasy")),
            ),
        )
        assertEquals(1, index.size)
        assertEquals("Fantasy", index.single().name)
        assertEquals(2, index.single().count)
    }

    @Test
    fun tagFilterAndQueryIncludeTags() {
        val book = entry("a", title = "剑与魔法", author = "某人", tags = listOf("战斗", "日常"))
        assertTrue(book.matchesShelfTag(null))
        assertTrue(book.matchesShelfTag("战斗"))
        assertTrue(book.matchesShelfTag("战斗".uppercase()))
        assertFalse(book.matchesShelfTag("科幻"))
        assertTrue(book.matchesShelfQuery("剑与"))
        assertTrue(book.matchesShelfQuery("某人"))
        assertTrue(book.matchesShelfQuery("日常"))
        assertFalse(book.matchesShelfQuery("不存在"))
    }

    @Test
    fun withShelfTagAddsRemovesAndKeepsOrder() {
        val book = entry("a").withShelfTag("战斗", true).withShelfTag("日常", true)
        assertEquals(listOf("战斗", "日常"), book.tags)
        assertEquals(listOf("战斗"), book.withShelfTag("日常", false).tags)
        assertEquals(listOf("战斗", "日常"), book.withShelfTag("战斗", true).tags)
    }

    @Test
    fun addingBeyondLimitDropsOldestTag() {
        var book = entry("a")
        (1..MAX_SHELF_TAGS_PER_BOOK).forEach { book = book.withShelfTag("tag$it", true) }
        val over = book.withShelfTag("new", true)
        assertEquals(MAX_SHELF_TAGS_PER_BOOK, over.tags.size)
        assertFalse(over.tags.contains("tag1"))
        assertTrue(over.tags.contains("new"))
    }
}

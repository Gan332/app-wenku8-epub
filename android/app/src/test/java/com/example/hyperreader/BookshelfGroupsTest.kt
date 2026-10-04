package com.example.hyperreader

import com.example.hyperreader.model.BookshelfEntry
import com.example.hyperreader.ui.BookshelfGroup
import com.example.hyperreader.ui.groupBookshelf
import com.example.hyperreader.ui.title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 书架分组的纯逻辑（置顶 / 全部，空组不产出）。 */
class BookshelfGroupsTest {

    private fun entry(id: String, pinned: Boolean = false) = BookshelfEntry(
        id = id,
        bookId = "book-$id",
        title = "书 $id",
        isPinned = pinned,
    )

    @Test
    fun emptyShelfProducesNoSections() {
        assertTrue("空书架不应产出任何分组", groupBookshelf(emptyList()).isEmpty())
    }

    @Test
    fun onlyPinnedProducesSingleSection() {
        val sections = groupBookshelf(listOf(entry("a", pinned = true)))
        assertEquals(1, sections.size)
        assertEquals(BookshelfGroup.Pinned, sections[0].group)
        assertEquals(1, sections[0].entries.size)
    }

    @Test
    fun onlyUnpinnedProducesAllSection() {
        val sections = groupBookshelf(listOf(entry("a"), entry("b")))
        assertEquals(1, sections.size)
        assertEquals(BookshelfGroup.All, sections[0].group)
        assertEquals(2, sections[0].entries.size)
    }

    @Test
    fun pinnedComesFirstAndAllExcludesPinned() {
        val sections = groupBookshelf(
            listOf(entry("a"), entry("p1", pinned = true), entry("b"), entry("p2", pinned = true)),
        )
        assertEquals(2, sections.size)
        assertEquals(BookshelfGroup.Pinned, sections[0].group)
        assertEquals(listOf("p1", "p2"), sections[0].entries.map { it.id })
        assertEquals(BookshelfGroup.All, sections[1].group)
        assertEquals(listOf("a", "b"), sections[1].entries.map { it.id })
    }

    @Test
    fun everyBookAppearsExactlyOnce() {
        val entries = (1..10).map { entry("b$it", pinned = it % 3 == 0) }
        val grouped = groupBookshelf(entries).flatMap { it.entries }.map { it.id }
        assertEquals("每本书必须且只能出现一次", entries.size, grouped.size)
        assertEquals(entries.map { it.id }.toSet(), grouped.toSet())
    }

    @Test
    fun orderWithinSectionFollowsInput() {
        // 分组只切段、不重排：段内顺序由调用方决定——0.19.0-alpha03 起是
        // `ui/sortBookshelf`（按用户选择的 BookshelfSort 排好后再传进来）。
        val sections = groupBookshelf(listOf(entry("z"), entry("m"), entry("a")))
        assertEquals(listOf("z", "m", "a"), sections.single().entries.map { it.id })
    }

    @Test
    fun titlesCarryCounts() {
        assertEquals("置顶 2 本", BookshelfGroup.Pinned.title(2))
        assertEquals("全部 5 本", BookshelfGroup.All.title(5))
        assertEquals("置顶 0 本", BookshelfGroup.Pinned.title(0))
    }
}


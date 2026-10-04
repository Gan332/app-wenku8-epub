package com.example.hyperreader

import com.example.hyperreader.model.BookshelfEntry
import com.example.hyperreader.settings.BookshelfSort
import com.example.hyperreader.ui.sortBookshelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书架排序纯函数（0.19.0-alpha03）。
 *
 * 重点锁定两条不变量：置顶恒在最前、同键值有确定顺序
 * （后者缺失会让 DataStore 每次发射时列表抖动）。
 */
class BookshelfSortTest {

    private fun entry(
        id: String,
        title: String = "书 $id",
        pinned: Boolean = false,
        addedAt: Long = 0L,
        lastReadAt: Long = 0L,
        wordCount: Long? = null,
    ) = BookshelfEntry(
        id = id,
        bookId = "book-$id",
        title = title,
        isPinned = pinned,
        addedAt = addedAt,
        lastReadAt = lastReadAt,
        wordCount = wordCount,
    )

    // ---- 置顶优先（所有排序方式都必须成立）----

    @Test
    fun pinnedAlwaysComesFirstUnderEverySort() {
        val entries = listOf(
            entry("a", addedAt = 300),
            entry("p", pinned = true, addedAt = 1),
            entry("b", addedAt = 200),
        )
        BookshelfSort.entries.forEach { sort ->
            val sorted = sortBookshelf(entries, sort)
            assertTrue(
                "排序方式 $sort 下置顶必须仍在最前",
                sorted.first().isPinned,
            )
            assertEquals("置顶书必须且只能出现一次", entries.size, sorted.size)
        }
    }

    @Test
    fun pinnedGroupKeepsItsOwnOrderAmongMultiplePinned() {
        val entries = listOf(
            entry("p1", pinned = true, addedAt = 100),
            entry("x", addedAt = 500),
            entry("p2", pinned = true, addedAt = 300),
        )
        // 置顶组内同样按所选方式排序（最近加入 → p2 在 p1 前）
        val sorted = sortBookshelf(entries, BookshelfSort.RecentlyAdded)
        assertEquals(listOf("p2", "p1", "x"), sorted.map { it.id })
    }

    // ---- 各排序方式 ----

    @Test
    fun recentReadFallsBackToAddedAtForNeverReadBooks() {
        val entries = listOf(
            entry("neverOld", addedAt = 10),
            entry("read", lastReadAt = 99),
            entry("neverNew", addedAt = 50),
        )
        val sorted = sortBookshelf(entries, BookshelfSort.RecentRead)
        // read(99) > neverNew(50) > neverOld(10)
        assertEquals(listOf("read", "neverNew", "neverOld"), sorted.map { it.id })
    }

    @Test
    fun recentlyAddedPutsNewestFirst() {
        val entries = listOf(
            entry("old", addedAt = 1),
            entry("new", addedAt = 3),
            entry("mid", addedAt = 2),
        )
        assertEquals(
            listOf("new", "mid", "old"),
            sortBookshelf(entries, BookshelfSort.RecentlyAdded).map { it.id },
        )
    }

    @Test
    fun titleSortsAscending() {
        val entries = listOf(
            entry("c", title = "C 书"),
            entry("a", title = "A 书"),
            entry("b", title = "B 书"),
        )
        assertEquals(
            listOf("a", "b", "c"),
            sortBookshelf(entries, BookshelfSort.Title).map { it.id },
        )
    }

    @Test
    fun wordCountPutsUnknownLast() {
        val entries = listOf(
            entry("unknown", wordCount = null),
            entry("small", wordCount = 100),
            entry("big", wordCount = 9000),
        )
        val sorted = sortBookshelf(entries, BookshelfSort.WordCount)
        assertEquals(listOf("big", "small", "unknown"), sorted.map { it.id })
    }

    // ---- 确定性与稳定性 ----

    @Test
    fun equalKeysFallBackToTitleThenIdSoOrderIsDeterministic() {
        // 三本书 addedAt 完全相同：必须仍产出唯一确定的顺序，
        // 否则 DataStore 每次发射顺序可能不同，列表会莫名跳动。
        val entries = listOf(
            entry("z", title = "同名", addedAt = 7),
            entry("a", title = "同名", addedAt = 7),
            entry("m", title = "同名", addedAt = 7),
        )
        val once = sortBookshelf(entries, BookshelfSort.RecentlyAdded).map { it.id }
        val twice = sortBookshelf(entries.reversed(), BookshelfSort.RecentlyAdded).map { it.id }
        assertEquals(listOf("a", "m", "z"), once)
        assertEquals("输入顺序不同但键相同，结果必须一致", once, twice)
    }

    @Test
    fun sortingDoesNotMutateInput() {
        val entries = listOf(entry("b", addedAt = 1), entry("a", addedAt = 2))
        val snapshot = entries.map { it.id }
        sortBookshelf(entries, BookshelfSort.RecentlyAdded)
        assertEquals("入参不得被改动", snapshot, entries.map { it.id })
    }

    @Test
    fun emptyShelfStaysEmpty() {
        BookshelfSort.entries.forEach { sort ->
            assertTrue(sortBookshelf(emptyList(), sort).isEmpty())
        }
    }

    @Test
    fun everyEntrySurvivesEverySort() {
        val entries = (1..12).map {
            entry("b$it", pinned = it % 4 == 0, addedAt = it.toLong(), wordCount = (it * 10).toLong())
        }
        BookshelfSort.entries.forEach { sort ->
            val sorted = sortBookshelf(entries, sort)
            assertEquals("排序 $sort 不得丢书", entries.size, sorted.size)
            assertEquals("排序 $sort 不得重复或丢书", entries.map { it.id }.toSet(), sorted.map { it.id }.toSet())
        }
    }

    // ---- 枚举回退 ----

    @Test
    fun sortFromNameFallsBackToDefaultForUnknownOrNull() {
        assertEquals(BookshelfSort.RecentRead, BookshelfSort.fromName(null))
        assertEquals(BookshelfSort.RecentRead, BookshelfSort.fromName("NoSuchSort"))
        assertEquals(BookshelfSort.WordCount, BookshelfSort.fromName("WordCount"))
    }
}

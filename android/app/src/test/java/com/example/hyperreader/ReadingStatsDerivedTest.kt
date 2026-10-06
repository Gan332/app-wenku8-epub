package com.example.hyperreader

import com.example.hyperreader.data.streakEndingAt
import com.example.hyperreader.data.withCurrentDay
import com.example.hyperreader.model.ReadingStats
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 阅读统计派生字段的按日重算（0.19.0-alpha03 修复）。
 *
 * 缺陷背景：「今日」与「连续天数」原先只在 `recordSession` 写入时算一次并存盘，
 * 于是隔天打开应用、还没读书时仍显示昨天的时长。这些用例锁定**读取时**按当前日期重算。
 */
class ReadingStatsDerivedTest {

    private fun stats(vararg days: Pair<String, Long>) = ReadingStats(
        dailySeconds = days.toMap(),
        totalSeconds = days.sumOf { it.second },
    )

    // ---- 「今日」必须跟着日期走 ----

    @Test
    fun todaySecondsFollowsTheCurrentDateNotTheStoredValue() {
        // 存的是「昨天读了 600 秒」，但今天是 2026-03-02 → 今日必须归零
        val stored = stats("2026-03-01" to 600L).copy(todaySeconds = 600L)
        val refreshed = stored.withCurrentDay(LocalDate.of(2026, 3, 2))
        assertEquals("跨天后「今日」不能继续显示昨天的时长", 0L, refreshed.todaySeconds)
    }

    @Test
    fun todaySecondsReadsTodaysBucket() {
        val stored = stats("2026-03-01" to 600L, "2026-03-02" to 120L)
        assertEquals(120L, stored.withCurrentDay(LocalDate.of(2026, 3, 2)).todaySeconds)
    }

    @Test
    fun emptyHistoryYieldsZeroToday() {
        assertEquals(0L, ReadingStats().withCurrentDay(LocalDate.of(2026, 3, 2)).todaySeconds)
    }

    // ---- 连续天数 ----

    @Test
    fun streakCountsConsecutiveDaysEndingToday() {
        val stored = stats(
            "2026-03-02" to 10L,
            "2026-03-01" to 10L,
            "2026-02-28" to 10L,
        )
        // 3/2、3/1、2/28 连续三天（2/29 不存在于 2026）
        assertEquals(3, stored.withCurrentDay(LocalDate.of(2026, 3, 2)).currentStreak)
    }

    @Test
    fun missingTodayDoesNotBreakStreakYet() {
        // 今天还没读，但昨天读了 → 连续天数保留（给用户当天补读的余地）
        val stored = stats("2026-03-01" to 10L, "2026-02-28" to 10L)
        assertEquals(2, stored.withCurrentDay(LocalDate.of(2026, 3, 2)).currentStreak)
    }

    @Test
    fun gapOfTwoDaysResetsStreakToZero() {
        // 今天与昨天都没读 → 连续中断
        val stored = stats("2026-02-27" to 10L, "2026-02-26" to 10L)
        assertEquals(0, stored.withCurrentDay(LocalDate.of(2026, 3, 2)).currentStreak)
    }

    @Test
    fun staleStreakIsCorrectedOnRead() {
        // 存盘时连续 5 天，但那之后断了 10 天 → 读取时必须是 0，而不是旧的 5
        val stored = stats("2026-02-20" to 10L, "2026-02-19" to 10L).copy(currentStreak = 5)
        assertEquals(0, stored.withCurrentDay(LocalDate.of(2026, 3, 2)).currentStreak)
    }

    // ---- 跨年 / 闰日 ----

    @Test
    fun streakCrossesYearBoundary() {
        val stored = stats(
            "2026-01-01" to 10L,
            "2025-12-31" to 10L,
            "2025-12-30" to 10L,
        )
        assertEquals(3, stored.withCurrentDay(LocalDate.of(2026, 1, 1)).currentStreak)
    }

    @Test
    fun leapDayIsCountedNormally() {
        // 2024 是闰年，2/29 存在
        val stored = stats(
            "2024-03-01" to 10L,
            "2024-02-29" to 10L,
            "2024-02-28" to 10L,
        )
        assertEquals(3, stored.withCurrentDay(LocalDate.of(2024, 3, 1)).currentStreak)
    }

    // ---- 最长连续只增不减 ----

    @Test
    fun longestStreakNeverRegresses() {
        // 历史峰值 9 天，当前只剩 1 天 → 最长连续必须仍是 9
        val stored = stats("2026-03-02" to 10L).copy(longestStreak = 9)
        val refreshed = stored.withCurrentDay(LocalDate.of(2026, 3, 2))
        assertEquals(1, refreshed.currentStreak)
        assertEquals("历史峰值不该因今天没读而回退", 9, refreshed.longestStreak)
    }

    @Test
    fun longestStreakRisesToMeetCurrentStreak() {
        val stored = stats(
            "2026-03-02" to 1L,
            "2026-03-01" to 1L,
            "2026-02-28" to 1L,
        ).copy(longestStreak = 1)
        assertEquals(3, stored.withCurrentDay(LocalDate.of(2026, 3, 2)).longestStreak)
    }

    // ---- 不触碰其它字段 ----

    @Test
    fun otherFieldsAreLeftAlone() {
        val stored = ReadingStats(
            totalSeconds = 12345L,
            totalSessions = 7,
            lastReadAt = 999L,
            dailySeconds = mapOf("2026-03-02" to 60L),
            bookSeconds = mapOf("b1" to 60L),
            bookTitles = mapOf("b1" to "书"),
        )
        val refreshed = stored.withCurrentDay(LocalDate.of(2026, 3, 2))
        assertEquals(12345L, refreshed.totalSeconds)
        assertEquals(7, refreshed.totalSessions)
        assertEquals(999L, refreshed.lastReadAt)
        assertEquals(mapOf("b1" to 60L), refreshed.bookSeconds)
        assertEquals(mapOf("b1" to "书"), refreshed.bookTitles)
    }

    // ---- streakEndingAt 直接覆盖（withCurrentDay 的核心算法）----

    @Test
    fun streakEndingAtHandlesEmptyAndSingleDay() {
        val today = LocalDate.of(2026, 3, 2)
        assertEquals(0, streakEndingAt(emptySet(), today))
        assertEquals(1, streakEndingAt(setOf("2026-03-02"), today))
        // 只有更早的孤立一天（既非今天也非昨天）→ 0
        assertEquals(0, streakEndingAt(setOf("2026-02-01"), today))
    }

    @Test
    fun streakEndingAtIgnoresFutureDates() {
        // 未来日期不该被算进「截至今天」的连续里
        val today = LocalDate.of(2026, 3, 2)
        val days = setOf("2026-03-05", "2026-03-02", "2026-03-01")
        assertEquals(2, streakEndingAt(days, today))
    }

    @Test
    fun streakEndingAtCountsFromYesterdayWhenTodayIsMissing() {
        val today = LocalDate.of(2026, 3, 2)
        assertEquals(2, streakEndingAt(setOf("2026-03-01", "2026-02-28"), today))
        // 今天没读、昨天也没读 → 0
        assertEquals(0, streakEndingAt(setOf("2026-02-28"), today))
    }
}

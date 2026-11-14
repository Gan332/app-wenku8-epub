package com.example.hyperreader.data

import com.example.hyperreader.model.ReadingStats
import com.example.hyperreader.model.ReadingSession
import java.time.LocalDate

/** 阅读统计页最多保留的最近阅读会话数；超出后丢弃最旧的记录。 */
const val MAX_RECENT_READING_SESSIONS = 200

/**
 * 阅读统计的派生字段重算（纯函数，可单测）。
 *
 * == 为什么需要它 ==
 * [ReadingStats.todaySeconds] 与 [ReadingStats.currentStreak] 是**派生值**：
 * 它们完全由 `dailySeconds` 与「今天是哪天」决定。但历史实现把结果在
 * `recordSession` 写入时算好并存盘，于是只有「下次阅读」才会刷新：
 *
 * - 隔天打开应用、还没读书时，「今日」显示的仍是**昨天**的时长；
 * - 连续中断两天后打开，「连续天数」仍显示中断前的旧值。
 *
 * 修法是让这两个字段**只在读取时**按当前日期推导。存储里它们退化为无意义的缓存值，
 * 真正的来源永远是 `dailySeconds`。
 *
 * [today] 可注入，便于单测覆盖跨天/跨年/闰日；生产代码用 [LocalDate.now]。
 */
fun ReadingStats.withCurrentDay(today: LocalDate = LocalDate.now()): ReadingStats {
    val key = today.toString()
    val todaySeconds = dailySeconds[key] ?: 0L
    val currentStreak = streakEndingAt(dailySeconds.keys, today)
    return copy(
        todaySeconds = todaySeconds,
        currentStreak = currentStreak,
        // 最长连续只增不减：历史峰值不该因为「今天没读」而回退
        longestStreak = maxOf(longestStreak, currentStreak),
    )
}

/**
 * 截至 [today] 的连续阅读天数。
 *
 * 口径：**今天没读不算断**——从昨天往前数（给用户当天补读的余地，
 * 也是各类阅读统计 App 的通行做法）；但今天和昨天都没读就是 0。
 */
internal fun streakEndingAt(days: Set<String>, today: LocalDate): Int {
    var cursor = if (today.toString() in days) today else today.minusDays(1)
    var count = 0
    while (cursor.toString() in days) {
        count++
        cursor = cursor.minusDays(1)
    }
    return count
}

/**
 * 最近 [days] 天（含今天）的阅读秒数。
 *
 * 用于统计页「近 7 天 / 近 30 天」汇总；只按日期键累加，不改动存储。
 */
internal fun readingSecondsInLastDays(
    dailySeconds: Map<String, Long>,
    today: LocalDate,
    days: Int,
): Long {
    if (days <= 0) return 0L
    return (0 until days).sumOf { offset ->
        dailySeconds[today.minusDays(offset.toLong()).toString()] ?: 0L
    }
}

/**
 * 连续达成每日目标的天数。
 *
 * 口径与 [streakEndingAt] 一致：今天还没达标不算断，从昨天往前数；
 * 今天和昨天都没达标就是 0。`goalMinutes <= 0`（未设置目标）恒为 0。
 */
internal fun goalStreakEndingAt(
    dailySeconds: Map<String, Long>,
    goalMinutes: Int,
    today: LocalDate,
): Int {
    if (goalMinutes <= 0) return 0
    val goalSeconds = goalMinutes * 60L
    fun met(date: LocalDate): Boolean =
        (dailySeconds[date.toString()] ?: 0L) >= goalSeconds

    var cursor = if (met(today)) today else today.minusDays(1)
    var count = 0
    while (met(cursor)) {
        count++
        cursor = cursor.minusDays(1)
    }
    return count
}

/**
 * 追加一条阅读会话并保留最近 [limit] 条。
 *
 * 纯函数：统计页只展示尾部，存储层不应无限增长——每读一章都写一次，
 * 一年下来会变成几千条 JSON。
 */
internal fun appendReadingSession(
    sessions: List<ReadingSession>,
    session: ReadingSession,
    limit: Int = MAX_RECENT_READING_SESSIONS,
): List<ReadingSession> {
    if (limit <= 0) return emptyList()
    return (sessions + session).takeLast(limit)
}

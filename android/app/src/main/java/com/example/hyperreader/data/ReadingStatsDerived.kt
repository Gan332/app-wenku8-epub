package com.example.hyperreader.data

import com.example.hyperreader.model.ReadingStats
import java.time.LocalDate

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

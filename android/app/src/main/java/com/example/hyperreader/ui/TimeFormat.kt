package com.example.hyperreader.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 书架「上次阅读时间」的相对化显示（纯函数，可单测）。
 *
 * - 时间戳 ≤ 0 → 空串（调用方据此整行隐藏）
 * - < 1 分钟：刚刚；< 1 小时：N 分钟前；< 24 小时：N 小时前
 * - 昨天：昨天；< 7 天：N 天前
 * - 再早：M月d日（跨年显示 yyyy年M月d日）
 *
 * 「天」按日历日算而不是 `delta/24h`，否则昨天下午到今天上午会被算成「前天」。
 */
fun formatRelativeReadTime(now: Long, timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val delta = now - timestamp
    if (delta < 60_000L) return "刚刚"
    if (delta < 3_600_000L) return "${delta / 60_000L} 分钟前"
    if (delta < 86_400_000L) return "${delta / 3_600_000L} 小时前"

    val dayDiff = calendarDaysBetween(timestamp, now)
    return when {
        dayDiff <= 0 -> "刚刚"
        dayDiff == 1 -> "昨天"
        dayDiff < 7 -> "$dayDiff 天前"
        else -> {
            val pattern = if (sameYear(timestamp, now)) "M月d日" else "yyyy年M月d日"
            SimpleDateFormat(pattern, Locale.CHINA).format(java.util.Date(timestamp))
        }
    }
}

/** 日历日差（四舍五入抵消夏令时的 23/25 小时日）。 */
internal fun calendarDaysBetween(from: Long, to: Long): Int {
    val start = Calendar.getInstance().apply {
        timeInMillis = minOf(from, to)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val end = Calendar.getInstance().apply {
        timeInMillis = maxOf(from, to)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val diff = end.timeInMillis - start.timeInMillis
    val days = (diff + 43_200_000L) / 86_400_000L
    return if (from <= to) days.toInt() else -days.toInt()
}

private fun sameYear(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
}

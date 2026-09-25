package com.wenku8.epubstudio.http

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

object HttpRateLimiter {
    private const val MIN_INTERVAL_MS = 1_000L
    private const val FALLBACK_429_DELAY_MS = 5_000L
    private const val MAX_RETRY_AFTER_MS = 120_000L
    private var nextAllowedAt = 0L

    @Synchronized
    fun acquire() {
        val now = System.currentTimeMillis()
        val waitMs = max(0L, nextAllowedAt - now)
        nextAllowedAt = max(now, nextAllowedAt) + MIN_INTERVAL_MS
        if (waitMs > 0) Thread.sleep(waitMs)
    }

    @Synchronized
    fun defer(delayMs: Long) {
        nextAllowedAt = max(nextAllowedAt, System.currentTimeMillis() + delayMs.coerceAtLeast(0L))
    }

    fun retryAfterMs(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        value.trim().toLongOrNull()?.let { seconds ->
            return (seconds.coerceAtLeast(0L) * 1_000L).coerceAtMost(MAX_RETRY_AFTER_MS)
        }
        return try {
            val timestamp = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (timestamp - System.currentTimeMillis()).coerceIn(0L, MAX_RETRY_AFTER_MS)
        } catch (_: Exception) {
            0L
        }
    }

    fun fallbackDelayMs(): Long = FALLBACK_429_DELAY_MS

    fun formatStatus(status: Int): String = String.format(Locale.ROOT, "HTTP %d", status)
}

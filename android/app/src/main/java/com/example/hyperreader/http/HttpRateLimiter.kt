package com.example.hyperreader.http

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 全站共享的源站请求节流器。
 *
 * 源站对匿名访客有 1 秒/请求 的节奏约束，批量抓取（书目缓存、导出）**必须**遵守，
 * 否则会触发 HTTP 429、退避重试，反而更慢，且可能被封 IP。
 *
 * 但「用户点开一本书」这类**单次按需请求**如果也吃满 1 秒，交互就会明显发顿：
 * 点一次等 1 秒、再点一次再等 1 秒。因此这里区分两档：
 *
 * - [Mode.BATCH]（默认）：批量节奏，[BATCH_INTERVAL_MS] 间隔，源站保护不变；
 * - [Mode.INTERACTIVE]：交互节奏，[INTERACTIVE_INTERVAL_MS] 间隔，
 *   仅在**短窗口**（[INTERACTIVE_WINDOW_MS]）内生效，窗口内最多
 *   [INTERACTIVE_MAX_IN_WINDOW] 次，之后自动退回批量节奏。
 *
 * 关键性质：交互档只是「允许突刺」，**不降低批量节奏**；
 * 且窗口有次数上限，连续点击也不会把请求频率推到源站警戒线以上。
 */
object HttpRateLimiter {
    private const val BATCH_INTERVAL_MS = 1_000L
    private const val INTERACTIVE_INTERVAL_MS = 120L
    private const val INTERACTIVE_WINDOW_MS = 6_000L
    private const val INTERACTIVE_MAX_IN_WINDOW = 6
    private const val FALLBACK_429_DELAY_MS = 5_000L
    private const val MAX_RETRY_AFTER_MS = 120_000L

    /** 请求节奏档位。 */
    enum class Mode {
        /** 批量抓取 / 导出：1 秒/请求，源站保护节奏。 */
        BATCH,

        /** 探索与详情等用户按需请求：允许短时突刺，超窗口自动降级。 */
        INTERACTIVE,
    }

    private var nextAllowedAt = 0L
    private var interactiveWindowStart = 0L
    private var interactiveUsed = 0

    /**
     * 预约下一个请求时隙；实际等待用协程 [delay]，因此取消请求时不会被
     * 长退避（429 最多 120 秒）卡住，也不会占用 Dispatchers.IO 的线程。
     */
    private val acquireMutex = Mutex()

    suspend fun acquire(mode: Mode = Mode.BATCH) {
        // 串行化预约，且只在真正可以发请求时推进 nextAllowedAt：
        // 否则取消/退避会让被取消的请求也占掉时隙，后续请求被白白拖慢。
        acquireMutex.withLock {
            while (true) {
                val waitMs = synchronized(this) {
                    max(0L, nextAllowedAt - System.currentTimeMillis())
                }
                if (waitMs <= 0L) break
                delay(waitMs)
            }
            synchronized(this) {
                val now = System.currentTimeMillis()
                val interval = if (mode == Mode.INTERACTIVE && tryUseInteractive(now)) {
                    INTERACTIVE_INTERVAL_MS
                } else {
                    BATCH_INTERVAL_MS
                }
                nextAllowedAt = max(now, nextAllowedAt) + interval
            }
        }
    }

    /**
     * 交互档配额：滑动窗口内最多 [INTERACTIVE_MAX_IN_WINDOW] 次。
     * 超限返回 false（调用方退回批量节奏）。
     */
    private fun tryUseInteractive(now: Long): Boolean {
        if (now - interactiveWindowStart > INTERACTIVE_WINDOW_MS) {
            interactiveWindowStart = now
            interactiveUsed = 0
        }
        if (interactiveUsed >= INTERACTIVE_MAX_IN_WINDOW) return false
        interactiveUsed += 1
        return true
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

    /** 仅供测试：重置节流状态。 */
    internal fun resetForTest() {
        nextAllowedAt = 0L
        interactiveWindowStart = 0L
        interactiveUsed = 0
    }

    /** 仅供测试：读取两档间隔常量，避免测试里写死魔数。 */
    internal fun batchIntervalForTest(): Long = BATCH_INTERVAL_MS

    internal fun interactiveIntervalForTest(): Long = INTERACTIVE_INTERVAL_MS

    internal fun interactiveWindowForTest(): Long = INTERACTIVE_WINDOW_MS

    internal fun interactiveMaxInWindowForTest(): Int = INTERACTIVE_MAX_IN_WINDOW
}

package com.example.hyperreader.ui

/**
 * 字数按千分位分组（如 207559 → 207,559）。
 *
 * 原先定义在 0.17.0 删除的 `BookDetailScreen.kt` 里，书架卡片与探索详情都在用；
 * 删文件时一并迁到这里（`ui` 包，`internal` 同包可见）。
 */
internal fun formatWordCount(value: Long): String {
    val negative = value < 0
    val digits = kotlin.math.abs(value).toString()
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (negative) "-$grouped" else grouped
}
package com.example.hyperreader.reader

import com.example.hyperreader.model.Chapter

/**
 * 在线阅读的「起始章 + 起始页」决策（0.18.0）。
 *
 * 抽成纯函数是因为这里有一条容易出错的不变量（AGENTS §4.8）：
 * **xy-reader 的页进度只与「已按该起始章打开的页轴」配对**。因此
 * - 显式指定起始章（详情页目录里点某一章）时，页码必须归零——旧页进度属于另一条页轴；
 * - 章节 id 必须在目录里真实存在，否则视为未指定（防止源站改号后跳到不存在的章）；
 * - 未指定时沿用「已记进度 → 旧章序 → 章首」的既有回落顺序。
 */
internal object StartPosition {
    data class Start(val chapterId: String?, val page: Int)

    fun resolve(
        requestedChapterId: String?,
        catalog: List<Chapter>,
        rememberedChapterId: String?,
        rememberedPage: Int?,
        rememberedStartChapterId: String? = null,
    ): Start {
        val requested = requestedChapterId?.takeIf { id -> catalog.any { it.id == id } }
        if (requested != null) return Start(chapterId = requested, page = 0)
        val chapterId = rememberedChapterId?.takeIf { id -> catalog.any { it.id == id } }
            ?: catalog.firstOrNull()?.id
        // 页码只对「当初那条页轴的起始章」有意义：起始章已从目录消失、回落到别的章时，
        // 旧页码（相对旧页轴）套到新页轴上会跳到章尾甚至下一章，必须归零。
        val pageApplies = rememberedStartChapterId == null || rememberedStartChapterId == chapterId
        return Start(chapterId = chapterId, page = if (pageApplies) rememberedPage ?: 0 else 0)
    }
}
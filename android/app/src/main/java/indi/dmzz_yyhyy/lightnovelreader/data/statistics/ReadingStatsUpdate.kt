/*
 * Copyright (C) 2025 走路 simply
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package indi.dmzz_yyhyy.lightnovelreader.data.statistics

import java.time.LocalDateTime

/**
 * 一次阅读统计更新。LNR 原实现里 `@Serializable data class`，由 Room 的 DAO 消费。
 * 本工程统计走 DataStore（[com.example.hyperreader.data.ReadingStatsRepository]），
 * P3 的适配层只需要 `bookId` / `readEventDelta` / `currentTime` 三个语义，因此这里
 * 去掉序列化注解，避免给「不进 Room」的项目留一个可序列化但无人消费的模型。
 */
data class ReadingStatsUpdate(
    val bookId: String,
    val readEventDelta: Int = 0,
    val accumulateReadSeconds: Int = 0,
    val currentTime: LocalDateTime = LocalDateTime.now(),
)

/**
 * LNR 引入适配：`StatsRepository` 的最小接口。
 *
 * 只保留 reader VM 真正调用的三个方法（onStart 会话、时长累计、读完标记），
 * 不引入 LNR 的 Room 实现与整棵 statistics 聚合。P3 由本工程的
 * `ReadingStatsRepository` 实现，保持「前台 onStart→onStop 累计、单次最长 30 分钟」
 * 的既有语义（AGENTS.md 4.3）。
 */
interface StatsRepository {
    suspend fun updateReadingStatistics(update: ReadingStatsUpdate)

    suspend fun accumulateBookReadTime(bookId: String, seconds: Int)

    suspend fun markBookFinished(bookId: String)
}

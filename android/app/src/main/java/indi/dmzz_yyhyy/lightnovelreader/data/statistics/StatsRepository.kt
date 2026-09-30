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

/**
 * LNR 引入适配：`StatsRepository` 的最小接口。
 *
 * LNR 原实现依赖 Room DAO（`BookRecordDao`/`DailyCountDao`）与 Hilt 注入，
 * 而 AGENTS.md 4.3 明确「不引入 Room」。reader 只调用下述三个方法
 * （见 [ReaderViewModel]），P3 由本工程 `ReadingStatsRepository`
 * （DataStore 实现、语义与 AGENTS 4.3 的统计口径一致）提供实现。
 */
interface StatsRepository {
    suspend fun accumulateBookReadTime(bookId: String, seconds: Int)
    suspend fun updateReadingStatistics(update: ReadingStatsUpdate)
    suspend fun markBookFinished(bookId: String)
}

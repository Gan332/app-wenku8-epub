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

package indi.dmzz_yyhyy.lightnovelreader.data.book

import com.github.michaelbull.result.Result
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.flow.Flow

/**
 * LNR 阅读器依赖的书目仓储**最小接口**。
 *
 * LNR 原实现 `BookRepository` 把在线数据源（`WebBookDataSourceProvider`）、本地
 * Room 缓存、书架聚合、`WorkManager` 预取全塞在一个类里。本工程一概不引入：
 *  - 在线/离线内容由本工程 `ReaderBook`（EPUB zip 条目 / wenku8 在线源）负责
 *  - 缓存与进度由 DataStore 负责（AGENTS.md 4.3：明确不引入 Room）
 *  - 预取不需要 WorkManager
 *
 * 因此这里只声明 reader 三个内容 VM 真正调用到的 6 个方法，`priority` 参数保留
 * 因为 LNR 的 VM 会显式传 `WebDataSourcePriority`；P3 的适配实现可以忽略它——
 * 本工程的限流策略由 `HttpRateLimiter.Mode` 决定，不接受调用方指定。
 */
interface BookRepository {
    fun getBookVolumesFlow(
        id: String,
        priority: WebDataSourcePriority = WebDataSourcePriority.Default,
    ): Flow<Result<BookVolumes, WebRequestError>>

    fun getChapterContentFlow(
        chapterId: String,
        bookId: String,
        priority: WebDataSourcePriority = WebDataSourcePriority.Default,
    ): Flow<Result<ChapterContent, WebRequestError>>

    suspend fun preloadChapterContent(
        chapterId: String,
        bookId: String,
        priority: WebDataSourcePriority = WebDataSourcePriority.Default,
    )

    suspend fun getUserReadingData(bookId: String): UserReadingData

    suspend fun updateUserReadingData(
        id: String,
        update: (UserReadingData) -> UserReadingData,
    )
}

package com.wenku8.epubstudio

import android.app.Application
import com.wenku8.epubstudio.core.Wenku8SessionStore
import com.wenku8.epubstudio.core.Wenku8SearchProvider
import com.wenku8.epubstudio.service.ExportJobManager
import com.wenku8.epubstudio.data.BookshelfRepository
import com.wenku8.epubstudio.data.ReadingStatsRepository
import com.wenku8.epubstudio.core.ExploreRepository
import com.wenku8.epubstudio.core.Wenku8DataSource
import com.wenku8.epubstudio.settings.SettingsRepository
import java.io.File

class Wenku8Application : Application() {
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val sessionStore: Wenku8SessionStore by lazy { Wenku8SessionStore(this) }
    val jobManager: ExportJobManager by lazy { ExportJobManager(this, sessionStore) }
    val searchProvider: Wenku8SearchProvider by lazy { Wenku8SearchProvider(jobManager.httpClient(), sessionStore) }
    val bookshelfRepository: BookshelfRepository by lazy { BookshelfRepository(this) }
    val readingStatsRepository: ReadingStatsRepository by lazy { ReadingStatsRepository(this) }
    val exploreRepository: ExploreRepository by lazy { ExploreRepository(Wenku8DataSource(jobManager.httpClient(), sessionStore)) }
    val catalogRepository: com.wenku8.epubstudio.core.CatalogRepository by lazy { com.wenku8.epubstudio.core.CatalogRepository(this) }
    val coverRepository: com.wenku8.epubstudio.ui.cover.CoverRepository by lazy { com.wenku8.epubstudio.ui.cover.CoverRepository(this) }
    val onlineReaderSource: com.wenku8.epubstudio.reader.OnlineReaderSource by lazy { com.wenku8.epubstudio.reader.OnlineReaderSource(File(filesDir, "online-cache"), jobManager.httpClient()) }
}

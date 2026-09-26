package com.example.hyperreader

import android.app.Application
import com.example.hyperreader.core.Wenku8SessionStore
import com.example.hyperreader.core.Wenku8SearchProvider
import com.example.hyperreader.service.ExportJobManager
import com.example.hyperreader.data.BookshelfRepository
import com.example.hyperreader.data.ReadingStatsRepository
import com.example.hyperreader.core.ExploreRepository
import com.example.hyperreader.core.Wenku8DataSource
import com.example.hyperreader.settings.SettingsRepository
import java.io.File

class Wenku8Application : Application() {
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val sessionStore: Wenku8SessionStore by lazy { Wenku8SessionStore(this) }
    val jobManager: ExportJobManager by lazy { ExportJobManager(this, sessionStore) }
    val searchProvider: Wenku8SearchProvider by lazy { Wenku8SearchProvider(jobManager.httpClient(), sessionStore) }
    val bookshelfRepository: BookshelfRepository by lazy { BookshelfRepository(this) }
    val readingStatsRepository: ReadingStatsRepository by lazy { ReadingStatsRepository(this) }
    val exploreRepository: ExploreRepository by lazy { ExploreRepository(Wenku8DataSource(jobManager.httpClient(), sessionStore)) }
    val catalogRepository: com.example.hyperreader.core.CatalogRepository by lazy { com.example.hyperreader.core.CatalogRepository(this) }
    val coverRepository: com.example.hyperreader.ui.cover.CoverRepository by lazy { com.example.hyperreader.ui.cover.CoverRepository(this) }
    val onlineReaderSource: com.example.hyperreader.reader.OnlineReaderSource by lazy { com.example.hyperreader.reader.OnlineReaderSource(File(filesDir, "online-cache"), jobManager.httpClient()) }
}
